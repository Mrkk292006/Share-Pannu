package com.localshare.app

import android.app.*
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class P2PReceiverService : Service() {

    companion object {
        const val CHANNEL_ID         = "localshare_p2p"
        const val NOTIF_ID           = 3
        const val ACTION_LOG         = "com.localshare.LOG_P2P"
        const val EXTRA_LOG_MSG      = "p2p_log_msg"
        const val ACTION_STOP        = "com.localshare.STOP_P2P"
        const val PORT               = 8082
    }

    private var server: FileTransferServer? = null
    private val binder = LocalBinder()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    var generatedPin: String = ""
        private set

    inner class LocalBinder : Binder() {
        fun getService(): P2PReceiverService = this@P2PReceiverService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalShare::P2PWakeLock")

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LocalShare::P2PWifiLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        generatedPin = (100000..999999).random().toString()
        startForeground(NOTIF_ID, buildNotification())

        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hour max
        wifiLock?.acquire()

        startServer()
        return START_STICKY
    }

    private fun startServer() {
        // Reuse FileTransferServer for P2P Receiver!
        server = FileTransferServer(
            context     = applicationContext,
            pin         = generatedPin,
            sharedFiles = mutableListOf(), // Receiver doesn't share files back
            logCallback = { msg -> broadcastLog(msg) },
            port        = PORT
        )
        try {
            server!!.start(10_000, false)
            broadcastLog("[✓] P2P Receiver server started on port $PORT")
        } catch (e: Exception) {
            broadcastLog("[✗] P2P Receiver server failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        server?.stop()

        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()

        broadcastLog("[✓] P2P Receiver session ended")
        super.onDestroy()
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent(ACTION_LOG).putExtra(EXTRA_LOG_MSG, msg)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "P2P Transfer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Receiving files via P2P"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, P2PReceiverService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, ActivityP2PReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P Receiver Active")
            .setContentText("Listening for incoming files")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .build()
    }
}
