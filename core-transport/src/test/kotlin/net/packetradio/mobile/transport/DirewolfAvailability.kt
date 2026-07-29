package net.packetradio.mobile.transport

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Lets the Direwolf-dependent live tests skip themselves gracefully when no rig is running. */
object DirewolfAvailability {
    fun agwpeReachable(): Boolean = reachable(8000)
    fun kissTcpReachable(): Boolean = reachable(8001)

    private fun reachable(port: Int): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            true
        } catch (_: IOException) {
            false
        }
}
