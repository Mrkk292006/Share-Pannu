package com.localshare.app

import android.content.*
import android.content.ContentUris
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.localshare.app.databinding.ActivitySharingBinding
import java.io.File

class SharingActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySharingBinding
    private lateinit var logAdapter: TransferLogAdapter
    private lateinit var receivedAdapter: ReceivedFilesAdapter
    private var serviceBound = false
    private var transferService: FileTransferService? = null

    // ── SAF multi-file picker ──
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val svc = transferService ?: run {
            Toast.makeText(this, "Server not ready", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        var addedCount = 0
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            val (name, size) = queryUriMeta(uri)
            if (size > 0) {
                svc.addSharedFile(SharedFileEntry(name = name, uri = uri, size = size))
                addedCount++
                logAdapter.addEntry("[+] Shared: $name (${fmtSize(size)})")
                binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
            }
        }
        if (addedCount > 0) {
            updateSharedCount()
            Toast.makeText(this, "$addedCount file(s) added", Toast.LENGTH_SHORT).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            transferService = (binder as FileTransferService.LocalBinder).getService()
            serviceBound = true
            updateUI()
        }
        override fun onServiceDisconnected(name: ComponentName) { serviceBound = false }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(FileTransferService.EXTRA_LOG_MSG) ?: return
            logAdapter.addEntry(msg)
            binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
            updateSharedCount()
            // Auto-refresh received list when a new upload completes
            if (msg.contains("Completed") && msg.contains("/upload")) {
                refreshReceivedFiles()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupLog()
        setupReceivedFiles()
        setupButtons()
        bindServiceConnection()
        registerLogReceiver()
    }

    // ──────────────────────────────────
    // Setup
    // ──────────────────────────────────
    private fun setupLog() {
        logAdapter = TransferLogAdapter()
        binding.rvLog.apply {
            adapter = logAdapter
            layoutManager = LinearLayoutManager(this@SharingActivity)
        }
    }

    private fun setupReceivedFiles() {
        receivedAdapter = ReceivedFilesAdapter(onDelete = { item ->
            var ok = false
            if (item.contentId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ok = contentResolver.delete(item.uri, null, null) > 0
            } else {
                val file = File(item.uri.path ?: "")
                ok = file.delete()
                if (ok) MediaScannerConnection.scanFile(
                    this, arrayOf(file.absolutePath), null, null
                )
            }
            if (ok) {
                logAdapter.addEntry("[✓] Deleted: ${item.name}")
                binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
                refreshReceivedFiles()
                Toast.makeText(this, "Deleted ${item.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not delete ${item.name}", Toast.LENGTH_SHORT).show()
            }
        })
        binding.rvReceivedFiles.apply {
            adapter = receivedAdapter
            layoutManager = LinearLayoutManager(this@SharingActivity)
        }
        refreshReceivedFiles()
    }

    // ──────────────────────────────────
    // Refresh received files from public storage
    // ──────────────────────────────────
    private fun refreshReceivedFiles() {
        val files: List<ReceivedFileItem> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryReceivedFilesMediaStore()
        } else {
            queryReceivedFilesLegacy()
        }
        receivedAdapter.submitList(files)
        binding.tvReceivedCount.text = if (files.isEmpty())
            "No files received yet — upload from browser"
        else
            "${files.size} file(s) in Downloads/${FileTransferServer.APP_FOLDER}"
    }

    private fun queryReceivedFilesMediaStore(): List<ReceivedFileItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val result     = mutableListOf<ReceivedFileItem>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.RELATIVE_PATH
        )
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        contentResolver.query(
            collection, projection,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("${FileTransferServer.BASE_PATH}/%"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val modCol  = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val id  = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val sub = (cursor.getString(pathCol) ?: "")
                    .removePrefix("${FileTransferServer.BASE_PATH}/").trimEnd('/')
                result.add(ReceivedFileItem(
                    uri       = uri,
                    contentId = id,
                    name      = cursor.getString(nameCol) ?: "",
                    size      = cursor.getLong(sizeCol),
                    modified  = cursor.getLong(modCol) * 1000L,
                    subfolder = sub
                ))
            }
        }
        return result
    }

    private fun queryReceivedFilesLegacy(): List<ReceivedFileItem> {
        val shareDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FileTransferServer.APP_FOLDER
        )
        if (!shareDir.exists()) return emptyList()
        return shareDir.walkTopDown()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .map { f ->
                ReceivedFileItem(
                    uri       = Uri.fromFile(f),
                    contentId = -1L,
                    name      = f.name,
                    size      = f.length(),
                    modified  = f.lastModified(),
                    subfolder = f.parentFile?.name ?: "Others"
                )
            }.toList()
    }

    // ──────────────────────────────────
    // Button wiring
    // ──────────────────────────────────
    private fun setupButtons() {
        binding.btnStop.setOnClickListener { stopServer(); finish() }

        binding.btnShareFiles.setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }

        binding.btnClearFiles.setOnClickListener {
            transferService?.clearSharedFiles()
            updateSharedCount()
            logAdapter.addEntry("[✓] Cleared all shared files")
            binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
            Toast.makeText(this, "Shared list cleared", Toast.LENGTH_SHORT).show()
        }

        binding.btnRefreshReceived.setOnClickListener {
            refreshReceivedFiles()
        }

        // Clear all received files from public storage
        binding.btnClearReceived.setOnClickListener {
            var deleted = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                deleted = contentResolver.delete(
                    collection,
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf("${FileTransferServer.BASE_PATH}/%")
                )
            } else {
                val shareDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FileTransferServer.APP_FOLDER
                )
                shareDir.walkTopDown().filter { it.isFile }.forEach {
                    if (it.delete()) {
                        deleted++
                        MediaScannerConnection.scanFile(this, arrayOf(it.absolutePath), null, null)
                    }
                }
            }
            refreshReceivedFiles()
            logAdapter.addEntry("[✓] Cleared $deleted received file(s)")
            binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
            Toast.makeText(this, "$deleted file(s) deleted", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLog.setOnClickListener {
            logAdapter.clear()
        }
    }

    // ──────────────────────────────────
    // Service binding
    // ──────────────────────────────────
    private fun bindServiceConnection() {
        val intent = Intent(this, FileTransferService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun registerLogReceiver() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver, IntentFilter(FileTransferService.ACTION_LOG)
        )
    }

    // ──────────────────────────────────
    // UI updates
    // ──────────────────────────────────
    private fun updateUI() {
        val svc = transferService ?: return
        val ip  = WifiUtils.getLocalIpAddress(this) ?: "?.?.?.?"
        val url = "http://$ip:${FileTransferService.PORT}"

        binding.tvUrl.text = url
        binding.tvPin.text = "PIN: ${svc.generatedPin}"
        generateQrCode(url)
        binding.tvWaiting.startAnimation(
            AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        )
        updateSharedCount()
        refreshReceivedFiles()
    }

    private fun updateSharedCount() {
        val count = transferService?.getFiles()?.size ?: 0
        binding.tvSharedCount.text = if (count == 0)
            "No files shared yet — tap ＋ Add Files"
        else
            "$count file(s) shared  •  visible in browser"
    }

    private fun generateQrCode(content: String) {
        try {
            val size   = 512
            val writer = QRCodeWriter()
            val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp    = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size)
                for (y in 0 until size)
                    bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            binding.ivQr.setImageBitmap(bmp)
        } catch (e: Exception) {
            logAdapter.addEntry("[✗] QR generation failed: ${e.message}")
        }
    }

    private fun stopServer() {
        startService(Intent(this, FileTransferService::class.java).apply {
            action = FileTransferService.ACTION_STOP
        })
    }

    // ──────────────────────────────────
    // SAF URI metadata helper
    // ──────────────────────────────────
    private fun queryUriMeta(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) { }
        return Pair(name, size)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 1024L               -> "$bytes B"
        bytes < 1024L * 1024        -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else                        -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
