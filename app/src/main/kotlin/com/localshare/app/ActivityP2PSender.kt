package com.localshare.app

import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.localshare.app.databinding.ActivityP2pSenderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ActivityP2PSender : AppCompatActivity() {

    private lateinit var binding: ActivityP2pSenderBinding

    private var targetIp: String? = null
    private var targetPort: Int = 8082
    private var sessionToken: String? = null

    private var wifiLock: WifiManager.WifiLock? = null

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            handleScanResult(result.contents)
        } else {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            startSendingFiles(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityP2pSenderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LocalShare::SenderWifiLock")

        binding.btnBack.setOnClickListener { finish() }

        binding.btnScanQr.setOnClickListener {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt("Scan Receiver's QR Code")
            options.setCameraId(0)
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(false)
            options.setOrientationLocked(true)
            barcodeLauncher.launch(options)
        }

        binding.btnSendFiles.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
    }

    private fun handleScanResult(jsonStr: String) {
        log("[✓] QR scanned")
        try {
            val jp = JSONObject(jsonStr)
            val ip = jp.getString("ip")
            val port = jp.getInt("port")
            val pin = jp.getString("pin")
            
            binding.tvTargetInfo.setText("Connecting...")
            binding.tvTargetInfo.setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
            log("[*] Connecting to $ip:$port...")
            
            lifecycleScope.launch(Dispatchers.IO) {
                authenticate(ip, port, pin)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid QR code format", Toast.LENGTH_SHORT).show()
            log("[✗] Invalid QR code format")
        }
    }

    private suspend fun authenticate(ip: String, port: Int, pin: String) {
        val maxRetries = 2
        for (attempt in 1..maxRetries) {
            try {
                val url = URL("http://$ip:$port/auth")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                val payload = JSONObject().put("pin", pin).toString().toByteArray()
                conn.outputStream.write(payload)
                conn.outputStream.flush()
                
                val code = conn.responseCode
                if (code == 200) {
                    val respStr = conn.inputStream.bufferedReader().readText()
                    val token = JSONObject(respStr).getString("token")
                    withContext(Dispatchers.Main) {
                        targetIp = ip
                        targetPort = port
                        sessionToken = token
                        binding.tvTargetInfo.setText("Connected")
                        binding.tvTargetInfo.setTextColor(android.graphics.Color.parseColor("#00FF88"))
                        
                        binding.btnSendFiles.setEnabled(true)
                        binding.btnSendFiles.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F57C00")))
                        binding.btnSendFiles.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                        log("[✓] Authentication successful")
                    }
                    return
                } else {
                    if (attempt == maxRetries) {
                        withContext(Dispatchers.Main) {
                            binding.tvTargetInfo.setText("Auth Failed (Code $code)")
                            binding.tvTargetInfo.setTextColor(android.graphics.Color.parseColor("#FF4444"))
                            log("[✗] Connection failed: Auth Failed (Code $code)")
                        }
                    }
                }
            } catch (e: Exception) {
                if (attempt == maxRetries) {
                    withContext(Dispatchers.Main) {
                        binding.tvTargetInfo.setText("Connection Error: ${e.message}")
                        binding.tvTargetInfo.setTextColor(android.graphics.Color.parseColor("#FF4444"))
                        log("[✗] Connection failed: timeout or error")
                    }
                }
            }
        }
    }

    private fun startSendingFiles(uris: List<Uri>) {
        if (targetIp == null || sessionToken == null) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
            
            for (i in uris.indices) {
                val uri = uris[i]
                val name = resolveDisplayName(uri)
                val size = resolveFileSize(uri)
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.setProgress(0)
                    binding.tvSpeed.setText("Preparing $name...")
                    binding.tvSpeed.setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                    val sizeFormatted = "%.1f MB".format(size / (1024.0 * 1024.0))
                    log("[*] Selected file: $name ($sizeFormatted)")
                    log("[*] Transfer started")
                }
                
                uploadFile(uri, name, size)
            }
            
            withContext(Dispatchers.Main) {
                binding.progressBar.setProgress(100)
                binding.tvSpeed.setText("All files sent successfully!")
                binding.tvSpeed.setTextColor(android.graphics.Color.parseColor("#00FF88"))
                log("[✓] Transfer completed successfully")
            }
            
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private suspend fun uploadFile(uri: Uri, name: String, size: Long) {
        try {
            val url = URL("http://$targetIp:$targetPort/upload")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-Auth-Token", sessionToken)
            conn.setRequestProperty("X-Filename", Uri.encode(name))
            if (size > 0) {
                conn.setRequestProperty("Content-Length", size.toString())
                conn.setFixedLengthStreamingMode(size)
            } else {
                conn.setChunkedStreamingMode(512 * 1024)
            }
            conn.doOutput = true
            
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedOutputStream(conn.outputStream, StreamUtils.BUFFER_SIZE).use { outputStream ->
                    StreamUtils.streamWithSpeedTracking(
                        inputStream, outputStream, size, name, "Sender"
                    ) { logMsg ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (logMsg.contains("MB/s") || logMsg.contains("KB/s")) {
                                val speedPart = logMsg.substringAfter("—").substringBefore("(").trim()
                                binding.tvSpeed.setText(speedPart)
                                log("[→] Speed: $speedPart")
                                
                                if (size > 0) {
                                    val pctMatch = Regex("(\\d+)%").find(logMsg)
                                    if (pctMatch != null) {
                                        binding.progressBar.setProgress(pctMatch.groupValues[1].toInt())
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            val code = conn.responseCode
            if (code != 200) {
                throw Exception("Server returned code $code")
            }
            
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                binding.tvSpeed.setText("Upload failed: ${e.message}")
                binding.tvSpeed.setTextColor(android.graphics.Color.parseColor("#FF4444"))
                log("[✗] Transfer failed: ${e.message}")
            }
        }
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

    private fun log(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val oldLog = binding.tvLogs.text.toString()
            binding.tvLogs.text = if (oldLog.isEmpty()) message else "$oldLog\n$message"
            binding.scrollViewLogs.post {
                binding.scrollViewLogs.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
