package com.localshare.app

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.localshare.app.databinding.ActivityTeamSharingBinding

class TeamSharingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeamSharingBinding
    private lateinit var logAdapter: TransferLogAdapter
    private lateinit var teamFilesAdapter: TeamSessionFilesAdapter
    private var serviceBound = false
    private var teamService: TeamShareService? = null

    // Handler for polling team file list every 3 seconds
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshTeamFiles()
            handler.postDelayed(this, 3_000L)
        }
    }

    // File picker — opens system picker for any file type, multiple selection
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) {
                binding.tvPhoneUploadStatus.text = "No files selected"
                return@registerForActivityResult
            }
            val svc = teamService
            if (svc == null) {
                Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val count = uris.size
            binding.tvPhoneUploadStatus.text = "Uploading $count file${if (count > 1) "s" else ""}…"
            binding.btnPhoneUpload.isEnabled = false
            uris.forEach { uri -> svc.addFileFromPhone(uri) }
            // Re-enable button after a short delay (uploads run on background thread)
            handler.postDelayed({
                binding.btnPhoneUpload.isEnabled = true
                binding.tvPhoneUploadStatus.text = "$count file${if (count > 1) "s" else ""} sent to workspace"
            }, 1500L)
        }

    // Receiver for immediate list refresh when a phone-upload completes
    private val filesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshTeamFiles()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            teamService = (binder as TeamShareService.LocalBinder).getService()
            serviceBound = true
            updateUI()
            handler.post(pollRunnable) // start polling
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            handler.removeCallbacks(pollRunnable)
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(TeamShareService.EXTRA_LOG_MSG) ?: return
            logAdapter.addEntry(msg)
            binding.rvTeamLog.scrollToPosition(logAdapter.itemCount - 1)
            // Refresh file list whenever an upload or delete completes
            if (msg.contains("upload") || msg.contains("delete", ignoreCase = true)) {
                refreshTeamFiles()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeamSharingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupLog()
        setupTeamFiles()
        setupButtons()
        bindService(Intent(this, TeamShareService::class.java), serviceConnection, BIND_AUTO_CREATE)
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(logReceiver, IntentFilter(TeamShareService.ACTION_LOG))
        lbm.registerReceiver(filesChangedReceiver, IntentFilter(TeamShareService.ACTION_FILES_CHANGED))
    }

    // ──────────────────────────────────
    // Setup
    // ──────────────────────────────────
    private fun setupLog() {
        logAdapter = TransferLogAdapter()
        binding.rvTeamLog.apply {
            adapter = logAdapter
            layoutManager = LinearLayoutManager(this@TeamSharingActivity)
        }
    }

    private fun setupTeamFiles() {
        teamFilesAdapter = TeamSessionFilesAdapter(
            onDownload = { entry -> openCacheFile(entry) },
            onSave     = { entry -> saveFile(entry) },
            onDelete   = { entry -> deleteFile(entry) }
        )
        binding.rvTeamFiles.apply {
            adapter = teamFilesAdapter
            layoutManager = LinearLayoutManager(this@TeamSharingActivity)
        }
    }

    private fun setupButtons() {
        binding.btnTeamStop.setOnClickListener {
            stopTeamServer()
            finish()
        }
        binding.btnClearTeamLog.setOnClickListener {
            logAdapter.clear()
        }
        binding.btnPhoneUpload.setOnClickListener {
            // Launch system file picker — any MIME type, multiple files
            filePicker.launch(arrayOf("*/*"))
        }
    }

    // ──────────────────────────────────
    // Team file actions
    // ──────────────────────────────────
    private fun openCacheFile(entry: TeamFileEntry) {
        try {
            val fileUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                entry.cacheFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, entry.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with…"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFile(entry: TeamFileEntry) {
        val svc = teamService ?: return
        val ok  = svc.saveFileToPermanent(entry.id)
        if (ok) {
            Toast.makeText(this, "Saved: ${entry.displayName}", Toast.LENGTH_SHORT).show()
            logAdapter.addEntry("[✓] Saved to Downloads: ${entry.displayName}")
            binding.rvTeamLog.scrollToPosition(logAdapter.itemCount - 1)
        } else {
            Toast.makeText(this, "Save failed for ${entry.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteFile(entry: TeamFileEntry) {
        teamService?.removeFile(entry.id)
        refreshTeamFiles()
        logAdapter.addEntry("[✓] Deleted from session: ${entry.displayName}")
        binding.rvTeamLog.scrollToPosition(logAdapter.itemCount - 1)
        Toast.makeText(this, "Deleted: ${entry.displayName}", Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────
    // Refresh list from service
    // ──────────────────────────────────
    private fun refreshTeamFiles() {
        val files = teamService?.getSessionFiles() ?: return
        teamFilesAdapter.submitList(files)
        val used  = teamService?.sessionBytes() ?: 0L
        binding.tvTeamFileCount.text = if (files.isEmpty())
            "Session empty — upload files from any device"
        else
            "${files.size} file(s) · ${fmtSize(used)} / 10 GB used"
    }

    // ──────────────────────────────────
    // UI init
    // ──────────────────────────────────
    private fun updateUI() {
        val svc = teamService ?: return
        val ip  = WifiUtils.getLocalIpAddress(this) ?: "?.?.?.?"
        val url = "http://$ip:${TeamShareService.PORT}"

        binding.tvTeamUrl.text = url
        binding.tvTeamPin.text = "PIN: ${svc.generatedPin}"
        generateQrCode(url)
        binding.tvTeamWaiting.startAnimation(
            AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        )
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
            binding.ivTeamQr.setImageBitmap(bmp)
        } catch (e: Exception) {
            logAdapter.addEntry("[✗] QR generation failed: ${e.message}")
        }
    }

    private fun stopTeamServer() {
        startService(Intent(this, TeamShareService::class.java).apply {
            action = TeamShareService.ACTION_STOP
        })
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 1024L               -> "$bytes B"
        bytes < 1024L * 1024        -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else                        -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(logReceiver)
        lbm.unregisterReceiver(filesChangedReceiver)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
