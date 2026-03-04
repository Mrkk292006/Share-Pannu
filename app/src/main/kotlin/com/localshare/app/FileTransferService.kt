package com.localshare.app

import android.app.*
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class FileTransferService : Service() {

    companion object {
        const val CHANNEL_ID = "localshare_server"
        const val NOTIF_ID = 1
        const val ACTION_LOG = "com.localshare.LOG"
        const val EXTRA_LOG_MSG = "log_msg"
        const val ACTION_STOP = "com.localshare.STOP"
        const val PORT = 8080
    }

    private var server: FileTransferServer? = null
    private val sharedFiles = mutableListOf<SharedFileEntry>()
    private val binder = LocalBinder()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    var generatedPin: String = ""
        private set

    inner class LocalBinder : Binder() {
        fun getService(): FileTransferService = this@FileTransferService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalShare::FileTransferWakeLock")
        
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LocalShare::FileTransferWifiLock")
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
        server = FileTransferServer(
            context = applicationContext,
            pin = generatedPin,
            sharedFiles = sharedFiles,
            logCallback = { msg -> broadcastLog(msg) }
        )
        try {
            server!!.start(10_000, false)  // non-daemon thread, 10s timeout
            broadcastLog("[✓] Server started on port $PORT")
        } catch (e: Exception) {
            broadcastLog("[✗] Server failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        server?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        broadcastLog("[✓] Server stopped")
        super.onDestroy()
    }

    fun addSharedFile(entry: SharedFileEntry) {
        synchronized(sharedFiles) { sharedFiles.add(entry) }
    }

    fun clearSharedFiles() {
        synchronized(sharedFiles) { sharedFiles.clear() }
        broadcastLog("[✓] Shared file list cleared")
    }

    fun getFiles(): List<SharedFileEntry> = synchronized(sharedFiles) { sharedFiles.toList() }

    private fun broadcastLog(msg: String) {
        val intent = Intent(ACTION_LOG).putExtra(EXTRA_LOG_MSG, msg)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "LocalShare Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running file transfer server"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FileTransferService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SharingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalShare Active")
            .setContentText("Server running on port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)   // Non-dismissable
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .build()
    }
}
