package com.localshare.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object WifiUtils {

    /**
     * Returns true if the device is on WiFi or acting as a WiFi hotspot.
     */
    fun isOnNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                isHotspotActive(context)
    }

    /**
     * Gets the device's local IPv4 address.
     * Tries WiFi IP first, then iterates all interfaces for hotspot / USB tethering.
     */
    fun getLocalIpAddress(context: Context): String? {
        // Try WiFi IP First (works for normal WiFi connections)
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                val ip = formatIp(ipInt)
                if (!ip.startsWith("0.")) return ip
            }
        } catch (_: Exception) {}

        val candidates = mutableListOf<String>()
        // Fallback: iterate all network interfaces (covers hotspot, USB tethering, etc.)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces.asSequence()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses.asSequence()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddr = addr.hostAddress ?: continue
                        candidates.add(hostAddr)
                    }
                }
            }
            
            // Prioritize standard local networks, avoid emulator IP (10.0.2.x) if possible
            val bestIp = candidates.firstOrNull { it.startsWith("192.168.") }
                ?: candidates.firstOrNull { it.startsWith("172.") }
                ?: candidates.firstOrNull { !it.startsWith("10.0.2.") }
                ?: candidates.firstOrNull()
                
            if (bestIp != null) return bestIp
        } catch (_: Exception) {}

        return null
    }

    /**
     * Check if WiFi hotspot is active via WifiManager (reflection-free approach).
     */
    private fun isHotspotActive(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wm) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Converts int IP from WifiInfo (little-endian) to dotted string.
     */
    private fun formatIp(ip: Int): String {
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }
}
