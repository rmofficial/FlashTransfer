package com.rahulmandal.flashtransfer

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class NetAddr(val ip: String, val iface: String, val label: String)

object NetUtils {

    private val skipPrefixes = listOf("rmnet", "ccmni", "v4-", "tun", "dummy", "clat", "lo", "ifb", "sit")
    private val hotspotRegex = Regex("^(ap|swlan|softap|wlan1)\\d*$")

    /** IPv4 addresses other devices could reach, hotspot first, then Wi-Fi. */
    fun list(): List<NetAddr> {
        val out = mutableListOf<NetAddr>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in Collections.list(all)) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (skipPrefixes.any { name.startsWith(it) }) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        out.add(NetAddr(ip, name, labelFor(name)))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { rank(it.iface) }
    }

    private fun labelFor(n: String): String = when {
        hotspotRegex.matches(n) -> "Hotspot ($n)"
        n.startsWith("wlan") -> "Wi-Fi ($n)"
        n.startsWith("rndis") || n.startsWith("usb") -> "USB tethering ($n)"
        n.startsWith("eth") -> "Ethernet ($n)"
        n.startsWith("p2p") -> "Wi-Fi Direct ($n)"
        else -> n
    }

    private fun rank(n: String): Int = when {
        hotspotRegex.matches(n) -> 0
        n.startsWith("wlan") -> 1
        n.startsWith("rndis") || n.startsWith("usb") -> 2
        else -> 3
    }
}
