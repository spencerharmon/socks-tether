package com.spencerharmon.sockstether

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class Socks5ServerTest {
    private lateinit var proxy: Socks5Server
    private val lo = InetAddress.getByName("127.0.0.1")
    private val cleanup = mutableListOf<AutoCloseable>()

    @Before fun setUp() {
        proxy = Socks5Server(0, allow = { true })
        proxy.start()
    }

    @After fun tearDown() {
        proxy.stop()
        cleanup.forEach { try { it.close() } catch (_: Exception) {} }
    }

    private fun tcpEcho(): ServerSocket {
        val s = ServerSocket(0, 5, lo)
        cleanup += s
        thread(isDaemon = true) {
            while (true) {
                val c = try { s.accept() } catch (_: Exception) { return@thread }
                thread(isDaemon = true) {
                    try {
                        val buf = ByteArray(4096)
                        while (true) {
                            val n = c.getInputStream().read(buf)
                            if (n < 0) break
                            c.getOutputStream().write(buf, 0, n)
                        }
                        c.close()
                    } catch (_: Exception) {}
                }
            }
        }
        return s
    }

    private fun greeting(sock: Socket) {
        sock.getOutputStream().write(byteArrayOf(5, 1, 0))
        val r = ByteArray(2).also { DataInputStream(sock.getInputStream()).readFully(it) }
        assertArrayEquals(byteArrayOf(5, 0), r)
    }

    /** Sends a request, returns (reply code, bound addr bytes, bound port). */
    private fun request(sock: Socket, cmd: Int, atyp: Int, addr: ByteArray, port: Int): Triple<Int, ByteArray, Int> {
        val o = sock.getOutputStream()
        val head = byteArrayOf(5, cmd.toByte(), 0, atyp.toByte())
        o.write(head + addr + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val i = DataInputStream(sock.getInputStream())
        val h = ByteArray(4).also { i.readFully(it) }
        val len = if (h[3].toInt() == 4) 16 else 4
        val a = ByteArray(len).also { i.readFully(it) }
        val p = i.readUnsignedShort()
        return Triple(h[1].toInt(), a, p)
    }

    @Test fun connectEchoesBothWays() {
        val echo = tcpEcho()
        Socket(lo, proxy.boundPort).use { c ->
            greeting(c)
            val (code, _, _) = request(c, 1, 1, lo.address, echo.localPort)
            assertEquals(0, code)
            val msg = ByteArray(100_000) { (it % 251).toByte() }
            c.getOutputStream().write(msg)
            val got = ByteArray(msg.size).also { DataInputStream(c.getInputStream()).readFully(it) }
            assertArrayEquals(msg, got)
        }
    }

    @Test fun connectByDomainName() {
        val echo = tcpEcho()
        Socket(lo, proxy.boundPort).use { c ->
            greeting(c)
            val name = "localhost".toByteArray()
            val (code, _, _) = request(c, 1, 3, byteArrayOf(name.size.toByte()) + name, echo.localPort)
            assertEquals(0, code)
            c.getOutputStream().write("hi".toByteArray())
            val got = ByteArray(2).also { DataInputStream(c.getInputStream()).readFully(it) }
            assertEquals("hi", String(got))
        }
    }

    @Test fun connectRefusedIsReported() {
        val closed = ServerSocket(0, 1, lo).also { it.close() }
        Socket(lo, proxy.boundPort).use { c ->
            greeting(c)
            val (code, _, _) = request(c, 1, 1, lo.address, closed.localPort)
            assertEquals(Socks5Server.REP_CONN_REFUSED, code)
        }
    }

    @Test fun unsupportedCommandRejected() {
        Socket(lo, proxy.boundPort).use { c ->
            greeting(c)
            val (code, _, _) = request(c, 2 /* BIND */, 1, lo.address, 1)
            assertEquals(Socks5Server.REP_CMD_UNSUPPORTED, code)
        }
    }

    @Test fun noAcceptableAuthMethodRejected() {
        Socket(lo, proxy.boundPort).use { c ->
            c.getOutputStream().write(byteArrayOf(5, 1, 2)) // username/password only
            val r = ByteArray(2).also { DataInputStream(c.getInputStream()).readFully(it) }
            assertArrayEquals(byteArrayOf(5, 0xFF.toByte()), r)
        }
    }

    @Test fun udpAssociateRelaysBothWays() {
        val echo = DatagramSocket(InetSocketAddress(lo, 0)).also { cleanup += it }
        thread(isDaemon = true) {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (true) {
                    p.length = buf.size
                    echo.receive(p)
                    echo.send(DatagramPacket(buf, p.length, p.socketAddress))
                }
            } catch (_: Exception) {}
        }

        Socket(lo, proxy.boundPort).use { ctl ->
            greeting(ctl)
            val (code, _, relayPort) = request(ctl, 3, 1, ByteArray(4), 0)
            assertEquals(0, code)

            DatagramSocket().use { u ->
                u.soTimeout = 3000
                val payload = "ping-over-udp".toByteArray()
                val hdr = byteArrayOf(0, 0, 0, 1) + lo.address +
                    byteArrayOf((echo.localPort shr 8).toByte(), echo.localPort.toByte())
                val out = hdr + payload
                u.send(DatagramPacket(out, out.size, lo, relayPort))

                val buf = ByteArray(2048)
                val r = DatagramPacket(buf, buf.size)
                u.receive(r)
                // reply = header naming the echo server as source, then the payload
                assertEquals(1, buf[3].toInt())
                assertArrayEquals(lo.address, buf.copyOfRange(4, 8))
                val srcPort = ((buf[8].toInt() and 0xFF) shl 8) or (buf[9].toInt() and 0xFF)
                assertEquals(echo.localPort, srcPort)
                assertEquals("ping-over-udp", String(buf, 10, r.length - 10))
            }
        }
    }

    @Test fun fragmentedUdpIsDropped() {
        Socket(lo, proxy.boundPort).use { ctl ->
            greeting(ctl)
            val (_, _, relayPort) = request(ctl, 3, 1, ByteArray(4), 0)
            DatagramSocket().use { u ->
                u.soTimeout = 500
                val out = byteArrayOf(0, 0, 1 /* FRAG */, 1) + lo.address + byteArrayOf(0, 9) + "x".toByteArray()
                u.send(DatagramPacket(out, out.size, lo, relayPort))
                try {
                    u.receive(DatagramPacket(ByteArray(64), 64))
                    org.junit.Assert.fail("fragment should not be answered")
                } catch (_: java.net.SocketTimeoutException) {}
            }
        }
    }

    @Test fun disallowedClientIsClosed() {
        val strict = Socks5Server(0, allow = { false })
        strict.start()
        try {
            Socket(lo, strict.boundPort).use { c ->
                c.soTimeout = 2000
                c.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(-1, c.getInputStream().read())
            }
        } finally {
            strict.stop()
        }
    }

    @Test fun subnetMatching() {
        fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
        assertTrue(SourceFilter.sameSubnet(ip("192.168.42.129"), ip("192.168.42.5"), 24))
        assertFalse(SourceFilter.sameSubnet(ip("192.168.42.129"), ip("192.168.43.5"), 24))
        assertTrue(SourceFilter.sameSubnet(ip("10.137.80.158"), ip("10.137.80.71"), 24))
        assertTrue(SourceFilter.sameSubnet(ip("10.0.0.1"), ip("10.0.127.254"), 17))
        assertFalse(SourceFilter.sameSubnet(ip("10.0.0.1"), ip("10.0.128.1"), 17))
        assertFalse(SourceFilter.sameSubnet(ip("10.0.0.1"), ip("10.0.0.2"), 0))
        assertTrue(SourceFilter.isTetherInterface("rndis0"))
        assertFalse(SourceFilter.isTetherInterface("wlan0"))
        assertFalse(SourceFilter.isTetherInterface("rmnet_data1"))
    }
}
