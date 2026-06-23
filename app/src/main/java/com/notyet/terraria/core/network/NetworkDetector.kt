package com.notyet.terraria.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import java.net.Inet4Address
import java.net.NetworkInterface

enum class NetworkType {
    LAN,
    TAILSCALE,
    ZEROTIER,
    VPN,
    OTHER
}

data class ServerNetwork(
    val ipAddress: String,
    val ipv6Address: String?,
    val interfaceName: String,
    val type: NetworkType
)

class NetworkDetector(
    private val context: Context
) {

    fun isPortAvailable(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use {
                it.reuseAddress = true
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getAvailableNetworks(): List<ServerNetwork> {
        val networks = mutableListOf<ServerNetwork>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                // Skip loopback and down/inactive interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val interfaceName = networkInterface.name.lowercase()
                val type = when {
                    interfaceName.startsWith("wlan") || interfaceName.startsWith("eth") -> NetworkType.LAN
                    interfaceName.startsWith("tailscale") -> NetworkType.TAILSCALE
                    interfaceName.startsWith("zt") || interfaceName.contains("zerotier") -> NetworkType.ZEROTIER
                    interfaceName.startsWith("wg") || interfaceName.startsWith("tun") || interfaceName.startsWith("tap") -> NetworkType.VPN
                    else -> NetworkType.OTHER
                }

                var ipv4: String? = null
                var ipv6: String? = null

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress) {
                        if (address is Inet4Address) {
                            ipv4 = address.hostAddress
                        } else if (address is java.net.Inet6Address) {
                            val ip = address.hostAddress
                            // Filter out link-local ipv6
                            if (ip != null && !ip.contains("%")) {
                                ipv6 = ip
                            }
                        }
                    }
                }
                
                if (ipv4 != null) {
                    networks.add(ServerNetwork(ipv4, ipv6, networkInterface.name, type))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Sort to prefer LAN > ZEROTIER > TAILSCALE > VPN > OTHER
        return networks.sortedBy { it.type.ordinal }
    }

    fun getBestIpv4Address(): String? {
        val networks = getAvailableNetworks()
        // Return the first one (LAN is preferred due to sorting, then ZeroTier, etc.)
        return networks.firstOrNull()?.ipAddress
    }

    fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
