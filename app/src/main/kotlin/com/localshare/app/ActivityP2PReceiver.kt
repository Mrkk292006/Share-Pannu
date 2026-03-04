package com.localshare.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.localshare.app.databinding.ActivityP2pReceiverBinding
import org.json.JSONObject

class ActivityP2PReceiver : AppCompatActivity() {

    private lateinit var binding: ActivityP2pReceiverBinding
    private var p2pService: P2PReceiverService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as P2PReceiverService.LocalBinder
            p2pService = binder.getService()
            updateUiWithPinInfo()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            p2pService = null
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(P2PReceiverService.EXTRA_LOG_MSG) ?: return
            val oldLog = binding.tvLogs.text.toString()
            binding.tvLogs.setText("$msg\n$oldLog")
            
            if (msg.contains("Upload →") && msg.contains("Receiving")) {
                binding.tvStatus.setText("● Receiving data")
                binding.tvStatus.setTextColor(android.graphics.Color.parseColor("#44AAFF"))
            } else if (msg.contains("complete") || msg.contains("Completed")) {
                binding.tvStatus.setText("● Transfer complete")
                binding.tvStatus.setTextColor(android.graphics.Color.parseColor("#00FF88"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityP2pReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            stopServiceAndFinish()
        }
        
        // Start Foreground Service
        val serviceIntent = Intent(this, P2PReceiverService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver, IntentFilter(P2PReceiverService.ACTION_LOG)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
    }

    override fun onBackPressed() {
        stopServiceAndFinish()
    }

    private fun stopServiceAndFinish() {
        val stopIntent = Intent(this, P2PReceiverService::class.java).apply {
            action = P2PReceiverService.ACTION_STOP
        }
        startService(stopIntent)
        finish()
    }

    private fun updateUiWithPinInfo() {
        val srv = p2pService ?: return
        val pin = srv.generatedPin
        val ip  = WifiUtils.getLocalIpAddress(this) ?: "127.0.0.1"

        binding.tvPin.setText(pin)
        
        val payload = JSONObject()
            .put("ip", ip)
            .put("port", P2PReceiverService.PORT)
            .put("pin", pin)
            .toString()
            
        generateQrCode(payload)
    }

    private fun generateQrCode(text: String) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
