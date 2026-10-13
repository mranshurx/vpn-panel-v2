package com.example.ffcapture.net

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap

class PacketVpnService : VpnService() {

    companion object {
        @Volatile var running = false
        private const val MTU = 1500
        private val GAMES = listOf("com.dts.freefireth", "com.dts.freefiremax")
    }

    private var tun: ParcelFileDescriptor? = null
    private var sink: FileOutputStream? = null
    private val flows = ConcurrentHashMap<String, Flow>()
    private val wlock = Any()

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (tun == null) start()
        return START_STICKY
    }

    private fun start() {
        val b = Builder()
            .setSession("cyberaim")
            .setMtu(MTU)
            .addAddress("10.66.0.2", 32).addRoute("0.0.0.0", 0)
            .addAddress("fd00:66::2", 128).addRoute("::", 0)

        var scoped = false
        for (p in GAMES) try { b.addAllowedApplication(p); scoped = true } catch (_: Throwable) {}
        if (!scoped) { /* no FF installed: capture the whole device */ }

        tun = b.establish() ?: return
        sink = FileOutputStream(tun!!.fileDescriptor)
        running = true
        Thread { pump() }.also { it.isDaemon = true; it.start() }
        Thread { janitor() }.also { it.isDaemon = true; it.start() }
    }

    private fun pump() {
        val ins = FileInputStream(tun!!.fileDescriptor)
        val buf = ByteArray(MTU + 64)
        while (running) {
            val n = try { ins.read(buf) } catch (t: Throwable) { break }
            if (n <= 0) continue
            try { route(buf, n) } catch (_: Throwable) { }
        }
    }

    private fun route(buf: ByteArray, n: Int) {
        val ip = Packets.Ip(buf, 0)
        when (ip.proto) {
            Packets.UDP -> {
                val u = Packets.Udp(buf, ip.payload)
                if (Editors.onOutbound(buf, ip, u)) Packets.fixUdp4(buf, 0, ip.payload, u.len)
                val key = "${ip.src}:${u.sport}>${ip.dst}:${u.dport}"
                flows.getOrPut(key) { Flow(ip.srcBytes, u.sport, ip.dstBytes, u.dport) }
                    .up(buf, ip.payload + 8, u.payloadLen)
            }
            Packets.TCP -> TcpRelay.onOutbound(this, buf, ip)
        }
    }

    fun inject(pkt: ByteArray) { synchronized(wlock) { sink?.write(pkt) } }

    private fun janitor() {
        while (running) {
            val now = System.currentTimeMillis()
            flows.entries.removeIf { now - it.value.last > 20_000 }
            try { Thread.sleep(5000) } catch (_: InterruptedException) { }
        }
    }

    private inner class Flow(sB: ByteArray, sPort: Int, dB: ByteArray, dPort: Int) {
        private val src = sB; private val dst = dB
        private val sp = sPort; private val dp = dPort
        @Volatile var last = System.currentTimeMillis()

        private val ch = DatagramChannel.open().also {
            protect(it.socket()); it.bind(InetSocketAddress(0))
            it.connect(InetSocketAddress(java.net.InetAddress.getByAddress(dB), dPort))
        }

        init {
            Thread {
                val bb = ByteBuffer.allocate(MTU + 64)
                while (running) {
                    try {
                        bb.clear()
                        val r = ch.read(bb)
                        if (r > 0) {
                            last = System.currentTimeMillis()
                            val p = ByteArray(r); bb.flip(); bb.get(p)
                            inject(Packets.udp4(dst, src, dp, sp, p))
                        }
                    } catch (_: Throwable) { break }
                }
            }.also { it.isDaemon = true; it.start() }
        }

        fun up(buf: ByteArray, off: Int, len: Int) {
            last = System.currentTimeMillis()
            try { ch.write(ByteBuffer.wrap(buf, off, len)) } catch (_: Throwable) { }
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { tun?.close() }
        tun = null
        super.onDestroy()
    }
}
