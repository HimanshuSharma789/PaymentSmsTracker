package com.example.paymentsmstracker.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    fun localIpv4(context: Context): String? {
        val fromCm = ipv4FromConnectivityManager(context)
        if (!fromCm.isNullOrBlank()) return fromCm

        val fromWifi = ipv4FromWifiManager(context)
        if (!fromWifi.isNullOrBlank()) return fromWifi

        return ipv4FromInterfaces()
    }

    private fun ipv4FromConnectivityManager(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return null
        }
        val props: LinkProperties = cm.getLinkProperties(network) ?: return null
        return props.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    @Suppress("DEPRECATION")
    private fun ipv4FromWifiManager(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val ip = wm.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        // Locale.ROOT: in e.g. Arabic locales %d would print non-ASCII digits and break the URL.
        return String.format(
            java.util.Locale.ROOT,
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    private fun ipv4FromInterfaces(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}
