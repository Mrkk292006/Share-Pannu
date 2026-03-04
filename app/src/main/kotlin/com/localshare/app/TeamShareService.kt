package com.localshare.app

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.*

class TeamShareService : Service() {

    companion object {
        const val CHANNEL_ID         = "localshare_team"
        const val NOTIF_ID           = 2
        const val ACTION_LOG         = "com.localshare.LOG_TEAM"
        const val EXTRA_LOG_MSG      = "team_log_msg"
        const val ACTION_STOP        = "com.localshare.STOP_TEAM"
        const val ACTION_FILES_CHANGED = "com.localshare.TEAM_FILES_CHANGED"
        const val PORT               = 8081
    }

    private var server: TeamShareServer? = null
    private val binder = LocalBinder()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    var generatedPin: String = ""
        private set

    inner class LocalBinder : Binder() {
        fun getService(): TeamShareService = this@TeamShareService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalShare::TeamShareWakeLock")

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LocalShare::TeamShareWifiLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        generatedPin = (100000..999999).random().toString()
        startForeground(NOTIF_ID, buildNotification())

        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hour max timeout
        wifiLock?.acquire()

        startServer()
        return START_STICKY
    }

    private fun startServer() {
        server = TeamShareServer(
            context     = applicationContext,
            pin         = generatedPin,
            logCallback = { msg -> broadcastLog(msg) }
        )
        try {
            server!!.start(10_000, false)
            broadcastLog("[✓] Team Share server started on port $PORT")
        } catch (e: Exception) {
            broadcastLog("[✗] Team Share server failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        val srv = server
        srv?.clearAllFiles()
        srv?.stop()

        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()

        // Delete the whole cache directory as final cleanup
        File(cacheDir, TeamShareServer.TEAM_CACHE_DIR).deleteRecursively()
        broadcastLog("[✓] Team Share session ended — all files wiped")
        super.onDestroy()
    }

    // ── Public API for TeamSharingActivity ──────────────

    fun getSessionFiles(): List<TeamFileEntry> = server?.getSessionFiles() ?: emptyList()

    fun removeFile(id: String) { server?.removeFile(id) }

    fun sessionBytes(): Long = server?.sessionBytes() ?: 0L

    /**
     * Adds one file from the phone's storage into the team session cache.
     * Enforces the 10 GB session cap, uses 256 KB streaming (never loads file into RAM),
     * registers the entry, and broadcasts log + files-changed so the UI updates.
     * Runs on a background thread to avoid blocking the UI.
     */
    fun addFileFromPhone(uri: Uri) {
        val srv = server ?: return
        Thread {
            val displayName = resolveDisplayName(uri)
            val contentLength = resolveFileSize(uri)

            // Session cap check (Dynamic Free Space check)
            if (!srv.hasSufficientSpace(contentLength)) {
                broadcastLog("[✗] PHONE upload rejected — insufficient device storage on phone")
                return@Thread
            }

            val fileId    = java.util.UUID.randomUUID().toString()
            val cacheName = "${fileId}_$displayName"
            val cacheDir  = File(cacheDir, TeamShareServer.TEAM_CACHE_DIR).also { it.mkdirs() }
            val cacheFile = File(cacheDir, cacheName)
            val mime      = contentResolver.getType(uri) ?: "application/octet-stream"

            broadcastLog("[⇄] PHONE upload → $displayName — Receiving…")

            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open: $uri")

                val bytesWritten = input.use { inp ->
                    java.io.BufferedOutputStream(cacheFile.outputStream(), StreamUtils.BUFFER_SIZE).use { out ->
                        StreamUtils.streamWithSpeedTracking(
                            inp, out, contentLength, displayName, "PHONE"
                        ) { msg -> broadcastLog(msg) }
                    }
                }

                val entry = TeamFileEntry(
                    id          = fileId,
                    displayName = displayName,
                    size        = bytesWritten,
                    mime        = mime,
                    cacheFile   = cacheFile,
                    uploadedAt  = System.currentTimeMillis()
                )
                srv.addFileEntry(entry)  // register in server registry
                broadcastLog("[✓] PHONE upload → $displayName (${fmtLog(bytesWritten)})")
                // Notify UI to refresh list
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent(ACTION_FILES_CHANGED))

            } catch (e: Exception) {
                cacheFile.delete()
                broadcastLog("[✗] PHONE upload error: ${e.message}")
            }
        }.start()
    }

    private fun resolveDisplayName(uri: Uri): String {
        val cursor = contentResolver.query(uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
    }

    private fun resolveFileSize(uri: Uri): Long {
        val cursor = contentResolver.query(uri,
            arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L
    }

    private fun fmtLog(bytes: Long): String = when {
        bytes < 1024L               -> "$bytes B"
        bytes < 1024L * 1024        -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else                        -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    /**
     * Copies a cache file to permanent public storage:
     *  API 29+  → MediaStore.Downloads
     *  API <29  → direct File write + MediaScanner
     * Returns true on success.
     */
    fun saveFileToPermanent(id: String): Boolean {
        val entry = server?.getFile(id) ?: return false
        if (!entry.cacheFile.exists()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(entry)
            } else {
                saveViaDirectFile(entry)
            }
        } catch (e: Exception) {
            broadcastLog("[✗] TEAM save error: ${e.message}")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(entry: TeamFileEntry): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, entry.displayName)
            put(MediaStore.Downloads.MIME_TYPE,    entry.mime)
            put(MediaStore.Downloads.RELATIVE_PATH,
                "${FileTransferServer.BASE_PATH}/${subfolderFor(entry.mime)}")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri    = contentResolver.insert(collection, values) ?: return false

        return try {
            contentResolver.openOutputStream(itemUri)!!.buffered(TeamShareServer.BUFFER_SIZE).use { out ->
                entry.cacheFile.inputStream().buffered(TeamShareServer.BUFFER_SIZE).use { it.copyTo(out) }
            }
            contentResolver.update(itemUri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            broadcastLog("[✓] TEAM saved → ${entry.displayName} (${FileTransferServer.APP_FOLDER})")
            true
        } catch (e: Exception) {
            contentResolver.delete(itemUri, null, null)
            throw e
        }
    }

    private fun saveViaDirectFile(entry: TeamFileEntry): Boolean {
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "${FileTransferServer.APP_FOLDER}/${subfolderFor(entry.mime)}"
        ).also { it.mkdirs() }

        val destFile = File(destDir, entry.displayName).let { base ->
            if (!base.exists()) base
            else {
                val dot  = entry.displayName.lastIndexOf('.')
                val stem = if (dot > 0) entry.displayName.substring(0, dot) else entry.displayName
                val ext  = if (dot > 0) entry.displayName.substring(dot) else ""
                var n = 1
                var f: File
                do { f = File(destDir, "$stem ($n)$ext"); n++ } while (f.exists())
                f
            }
        }

        entry.cacheFile.inputStream().buffered(TeamShareServer.BUFFER_SIZE).use { input ->
            destFile.outputStream().buffered(TeamShareServer.BUFFER_SIZE).use { input.copyTo(it) }
        }
        MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), arrayOf(entry.mime), null)
        broadcastLog("[✓] TEAM saved → ${destFile.name} (${FileTransferServer.APP_FOLDER})")
        return true
    }

    private fun subfolderFor(mime: String): String = when {
        mime.startsWith("image/")  -> "Images"
        mime.startsWith("video/")  -> "Videos"
        mime.startsWith("audio/")  -> "Audio"
        mime == "application/pdf"
            || mime.startsWith("text/")
            || mime.contains("document",     ignoreCase = true)
            || mime.contains("spreadsheet",  ignoreCase = true)
            || mime.contains("presentation", ignoreCase = true) -> "Documents"
        mime.contains("zip")  || mime.contains("rar")
            || mime.contains("7z") || mime.contains("tar")
            || mime.contains("gzip") || mime.contains("bzip") -> "Archives"
        else -> "Others"
    }

    // ── Notification ────────────────────────────────────

    private fun broadcastLog(msg: String) {
        val intent = Intent(ACTION_LOG).putExtra(EXTRA_LOG_MSG, msg)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Team Share",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running Team Share session"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TeamShareService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, TeamSharingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Team Share Active")
            .setContentText("Shared workspace on port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .build()
    }
}
