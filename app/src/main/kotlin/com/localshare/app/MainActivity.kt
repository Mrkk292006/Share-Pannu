package com.localshare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.localshare.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Tracks which mode (normal / team) the permission request was initiated for */
    private var pendingMode: String = MODE_NORMAL

    companion object {
        const val MODE_NORMAL = "normal"
        const val MODE_TEAM   = "team"
        const val MODE_P2P    = "p2p"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            when (pendingMode) {
                MODE_TEAM -> startTeamSharing()
                MODE_P2P -> startP2PSharing()
                else -> startSharing()
            }
        } else {
            Toast.makeText(this, "Storage permission needed to share files", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartSharing.setOnClickListener {
            if (!WifiUtils.isOnNetwork(this)) {
                binding.tvWifiStatus.text = "✗ Not connected to WiFi"
                binding.tvWifiStatus.setTextColor(0xFFFF4444.toInt())
                Toast.makeText(this, "Connect to WiFi or enable hotspot first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingMode = MODE_NORMAL
            requestPermissionsAndStart()
        }

        binding.btnTeamShare.setOnClickListener {
            if (!WifiUtils.isOnNetwork(this)) {
                binding.tvWifiStatus.text = "✗ Not connected to WiFi"
                binding.tvWifiStatus.setTextColor(0xFFFF4444.toInt())
                Toast.makeText(this, "Connect to WiFi or enable hotspot first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingMode = MODE_TEAM
            requestPermissionsAndStart()
        }

        binding.btnP2pShare.setOnClickListener {
            if (!WifiUtils.isOnNetwork(this)) {
                binding.tvWifiStatus.text = "✗ Not connected to WiFi"
                binding.tvWifiStatus.setTextColor(0xFFFF4444.toInt())
                Toast.makeText(this, "Connect to WiFi or enable hotspot first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingMode = MODE_P2P
            requestPermissionsAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        updateNetworkStatus()
    }

    private fun updateNetworkStatus() {
        if (WifiUtils.isOnNetwork(this)) {
            val ip = WifiUtils.getLocalIpAddress(this) ?: "Unknown"
            binding.tvWifiStatus.text = "✓ Connected"
            binding.tvWifiStatus.setTextColor(0xFF00FF88.toInt())
            binding.tvLocalIp.text = "http://$ip:${FileTransferService.PORT}"
        } else {
            binding.tvWifiStatus.text = "✗ No WiFi"
            binding.tvWifiStatus.setTextColor(0xFFFF4444.toInt())
            binding.tvLocalIp.text = "—"
        }
    }

    private fun requestPermissionsAndStart() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES))
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (!hasPermission(Manifest.permission.READ_MEDIA_VIDEO))
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (!hasPermission(Manifest.permission.READ_MEDIA_AUDIO))
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE))
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (perms.isEmpty()) {
            when (pendingMode) {
                MODE_TEAM -> startTeamSharing()
                MODE_P2P -> startP2PSharing()
                else -> startSharing()
            }
        } else {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun startSharing() {
        val serviceIntent = Intent(this, FileTransferService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        startActivity(Intent(this, SharingActivity::class.java))
    }

    private fun startTeamSharing() {
        val serviceIntent = Intent(this, TeamShareService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        startActivity(Intent(this, TeamSharingActivity::class.java))
    }

    private fun startP2PSharing() {
        startActivity(Intent(this, ActivityPhoneToPhone::class.java))
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
}

