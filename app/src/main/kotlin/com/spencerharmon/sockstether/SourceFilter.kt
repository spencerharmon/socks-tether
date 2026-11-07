package com.spencerharmon.sockstether

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Only clients on a USB-tether style interface (or loopback, for `adb forward`
 * and tests) may use the proxy. This is what makes it safe to listen on the
 * wildcard address: the phone's Wi-Fi / mobile networks are never served.
 */
object SourceFilter {
    private val tetherPrefixes = listOf("rndis", "usb", "ncm", "eth")

    fun isTetherInterface(name: String): Boolean =
        tetherPrefixes.any { name.startsWith(it) }

    fun allowed(peer: InetAddress): Boolean {
        if (peer.isLoopbackAddress) return true
        if (peer !is Inet4Address) return false
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return false
        }
        for (ni in ifaces) {
            if (!isTetherInterface(ni.name)) continue
            for (ia in ni.interfaceAddresses) {
                val local = ia.address
                if (local is Inet4Address && sameSubnet(local, peer, ia.networkPrefixLength.toInt())) {
                    return true
                }
            }
        }
        return false
    }

    fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        if (prefix !in 1..32) return false
        val x = a.address
        val y = b.address
        var bits = prefix
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }
}
