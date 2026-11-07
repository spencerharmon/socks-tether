package com.spencerharmon.sockstether

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal SOCKS5 server (RFC 1928): no authentication, CONNECT and UDP ASSOCIATE.
 *
 * Every outbound socket is created by this process, so the traffic leaves over
 * the phone's normal default network exactly like any other app's traffic.
 * Clients are restricted by [allow] (default: [SourceFilter.allowed]).
 *
 * Plain blocking sockets on cached daemon threads: no polling, no timers, so an
 * idle server costs nothing.
 */
class Socks5Server(
    private val port: Int,
    private val allow: (InetAddress) -> Boolean = SourceFilter::allowed,
    private val log: (String) -> Unit = {},
) {
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "socks5").apply { isDaemon = true }
    }
    private val open: MutableSet<Closeable> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    /** Number of client sessions currently being served. */
    val activeSessions = AtomicInteger()

    /** The port actually bound (useful when constructed with port 0). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        check(!running) { "already running" }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        pool.execute { acceptLoop(s) }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        for (c in open.toList()) closeQuietly(c)
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try {
                s.accept()
            } catch (_: IOException) {
                break
            }
            if (!allow(c.inetAddress)) {
                log("rejected ${c.inetAddress}")
                closeQuietly(c)
                continue
            }
            try {
                pool.execute { session(c) }
            } catch (_: Exception) {
                closeQuietly(c)
            }
        }
    }

    private fun track(c: Closeable) { open.add(c) }

    private fun untrack(c: Closeable) { open.remove(c); closeQuietly(c) }

    private fun session(c: Socket) {
        track(c)
        activeSessions.incrementAndGet()
        try {
            handle(c)
        } catch (_: IOException) {
            // client went away or sent garbage; nothing to report
        } finally {
            activeSessions.decrementAndGet()
            untrack(c)
        }
    }

    private fun handle(c: Socket) {
        c.tcpNoDelay = true
        c.keepAlive = true
        c.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = DataInputStream(c.getInputStream().buffered())
        val output = c.getOutputStream()

        // Greeting
        if (input.readUnsignedByte() != 5) return
        val nMethods = input.readUnsignedByte()
        val methods = ByteArray(nMethods).also { input.readFully(it) }
        if (methods.none { it.toInt() == 0 }) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(5, 0))
        output.flush()

        // Request
        if (input.readUnsignedByte() != 5) return
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val dst = readAddress(input) ?: run {
            reply(output, REP_ADDR_TYPE_UNSUPPORTED, null, 0)
            return
        }
        val dstPort = input.readUnsignedShort()

        when (cmd) {
            1 -> connect(c, input, output, dst, dstPort)
            3 -> udpAssociate(c, input, output)
            else -> reply(output, REP_CMD_UNSUPPORTED, null, 0)
        }
    }

    // ---------------------------------------------------------------- CONNECT

    private fun connect(c: Socket, input: InputStream, output: OutputStream, dst: Dst, dstPort: Int) {
        val remote = Socket()
        track(remote)
        try {
            val addrs = try {
                dst.resolve()
            } catch (_: UnknownHostException) {
                reply(output, REP_HOST_UNREACHABLE, null, 0)
                return
            }
            var code = REP_HOST_UNREACHABLE
            var connected = false
            for (a in addrs) {
                try {
                    remote.connect(InetSocketAddress(a, dstPort), CONNECT_TIMEOUT_MS)
                    connected = true
                    break
                } catch (_: ConnectException) {
                    code = REP_CONN_REFUSED
                } catch (_: NoRouteToHostException) {
                    code = REP_NET_UNREACHABLE
                } catch (_: SocketTimeoutException) {
                    code = REP_TTL_EXPIRED
                } catch (_: SocketException) {
                    code = REP_HOST_UNREACHABLE
                }
            }
            if (!connected) {
                reply(output, code, null, 0)
                return
            }
            remote.tcpNoDelay = true
            remote.keepAlive = true
            reply(output, REP_OK, remote.localAddress, remote.localPort)
            c.soTimeout = 0
            relay(c, remote)
        } finally {
            untrack(remote)
        }
    }

    private fun relay(a: Socket, b: Socket) {
        val other = pool.submit { copy(b, a) }
        copy(a, b)
        try { other.get() } catch (_: Exception) {}
    }

    private fun copy(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(COPY_BUFFER)
            val i = from.getInputStream()
            val o = to.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
            }
            try { to.shutdownOutput() } catch (_: IOException) {}
        } catch (_: IOException) {
            closeQuietly(from)
            closeQuietly(to)
        }
    }

    // ---------------------------------------------------------- UDP ASSOCIATE

    private fun udpAssociate(c: Socket, input: InputStream, output: OutputStream) {
        val client = c.inetAddress
        // Relay endpoint facing the client, on the same local address it reached us on.
        val facing = DatagramSocket(InetSocketAddress(c.localAddress, 0))
        // Outbound endpoint: wildcard, so the kernel picks the default network.
        val upstream = DatagramSocket()
        track(facing)
        track(upstream)
        try {
            reply(output, REP_OK, c.localAddress, facing.localPort)
            c.soTimeout = 0

            val clientAddr = AtomicReference<InetSocketAddress?>(null)

            pool.execute { // client -> internet
                val buf = ByteArray(UDP_MAX)
                val p = DatagramPacket(buf, buf.size)
                try {
                    while (true) {
                        p.length = buf.size
                        facing.receive(p)
                        if (p.address != client) continue
                        clientAddr.set(InetSocketAddress(p.address, p.port))
                        val parsed = parseUdp(buf, p.length) ?: continue
                        val target = try {
                            parsed.dst.resolve().firstOrNull() ?: continue
                        } catch (_: UnknownHostException) {
                            continue
                        }
                        upstream.send(
                            DatagramPacket(buf, parsed.dataOffset, p.length - parsed.dataOffset,
                                InetSocketAddress(target, parsed.port))
                        )
                    }
                } catch (_: IOException) {
                    // sockets closed
                }
            }

            pool.execute { // internet -> client
                val buf = ByteArray(UDP_MAX)
                val p = DatagramPacket(buf, HEADER_ROOM, buf.size - HEADER_ROOM)
                try {
                    while (true) {
                        p.setData(buf, HEADER_ROOM, buf.size - HEADER_ROOM)
                        upstream.receive(p)
                        val to = clientAddr.get() ?: continue
                        val src = p.address
                        val raw = src.address
                        val hdr = 4 + raw.size + 2
                        val start = HEADER_ROOM - hdr
                        buf[start] = 0
                        buf[start + 1] = 0
                        buf[start + 2] = 0
                        buf[start + 3] = if (src is Inet4Address) 1 else 4
                        System.arraycopy(raw, 0, buf, start + 4, raw.size)
                        buf[start + 4 + raw.size] = (p.port shr 8).toByte()
                        buf[start + 5 + raw.size] = p.port.toByte()
                        facing.send(DatagramPacket(buf, start, hdr + p.length, to))
                    }
                } catch (_: IOException) {
                    // sockets closed
                }
            }

            // The association lives as long as the control connection does.
            val sink = ByteArray(64)
            try {
                while (input.read(sink) >= 0) { /* ignore */ }
            } catch (_: IOException) {}
        } finally {
            untrack(facing)
            untrack(upstream)
        }
    }

    // ------------------------------------------------------------ wire format

    private sealed class Dst {
        abstract fun resolve(): List<InetAddress>
        class Ip(val addr: InetAddress) : Dst() { override fun resolve() = listOf(addr) }
        class Name(val name: String) : Dst() {
            override fun resolve(): List<InetAddress> = InetAddress.getAllByName(name).toList()
        }
    }

    private fun readAddress(input: DataInputStream): Dst? = when (input.readUnsignedByte()) {
        1 -> Dst.Ip(InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }))
        4 -> Dst.Ip(InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }))
        3 -> {
            val len = input.readUnsignedByte()
            Dst.Name(String(ByteArray(len).also { input.readFully(it) }, Charsets.ISO_8859_1))
        }
        else -> null
    }

    private class UdpPacket(val dst: Dst, val port: Int, val dataOffset: Int)

    /** Parse a SOCKS5 UDP request header. Fragmented datagrams are dropped. */
    private fun parseUdp(b: ByteArray, len: Int): UdpPacket? {
        if (len < 10 || b[2].toInt() != 0) return null
        var o = 4
        val dst = when (b[3].toInt()) {
            1 -> {
                if (len < o + 4 + 2) return null
                Dst.Ip(InetAddress.getByAddress(b.copyOfRange(o, o + 4))).also { o += 4 }
            }
            4 -> {
                if (len < o + 16 + 2) return null
                Dst.Ip(InetAddress.getByAddress(b.copyOfRange(o, o + 16))).also { o += 16 }
            }
            3 -> {
                val n = b[o].toInt() and 0xFF
                if (len < o + 1 + n + 2) return null
                Dst.Name(String(b, o + 1, n, Charsets.ISO_8859_1)).also { o += 1 + n }
            }
            else -> return null
        }
        val port = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        return UdpPacket(dst, port, o + 2)
    }

    private fun reply(out: OutputStream, code: Int, bind: InetAddress?, bindPort: Int) {
        val addr = bind?.address ?: ByteArray(4)
        val msg = ByteArray(6 + addr.size)
        msg[0] = 5
        msg[1] = code.toByte()
        msg[3] = if (addr.size == 4) 1 else 4
        System.arraycopy(addr, 0, msg, 4, addr.size)
        msg[4 + addr.size] = (bindPort shr 8).toByte()
        msg[5 + addr.size] = bindPort.toByte()
        out.write(msg)
        out.flush()
    }

    private fun closeQuietly(c: Closeable) {
        try { c.close() } catch (_: IOException) {}
    }

    companion object {
        const val REP_OK = 0
        const val REP_NET_UNREACHABLE = 3
        const val REP_HOST_UNREACHABLE = 4
        const val REP_CONN_REFUSED = 5
        const val REP_TTL_EXPIRED = 6
        const val REP_CMD_UNSUPPORTED = 7
        const val REP_ADDR_TYPE_UNSUPPORTED = 8

        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val COPY_BUFFER = 32 * 1024
        private const val UDP_MAX = 64 * 1024
        private const val HEADER_ROOM = 32 // room in front of a reply payload for the SOCKS UDP header
    }
}
