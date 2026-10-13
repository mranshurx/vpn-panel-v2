package com.example.ffcapture.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TUN + userspace forwarder scoped to Free Fire only.
 *
 * IPv4 only on purpose: the reply builder emits IPv4 headers, so routing IPv6
 * through here without an IPv6 builder would silently break the game's flows.
 * Without an IPv6 route/address, v6 traffic just uses the real interface.
 */
class PacketVpnService : VpnService() {

    companion object {
        @Volatile var running = false
        private const val MTU = 1500
        private const val NOTIF_ID = 9
        private const val FLOW_IDLE_MS = 20_000L
        private val GAMES = listOf("com.dts.freefireth", "com.dts.freefiremax")
        private var hooked = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var sink: FileOutputStream? = null
    private val flows = ConcurrentHashMap<String, Flow>()
    private val wlock = Any()

    val pkts = AtomicLong()

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (tun == null) start()
        return START_STICKY
    }

    private fun start() {
        // Foreground first: startForegroundService() gives us 5s to call this.
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, notif())

        if (!hooked) { Editors.register(Headshot); hooked = true }

        val b = Builder()
            .setSession("cyberaim")
            .setMtu(MTU)
            .addAddress("10.66.0.2", 32)
            .addRoute("0.0.0.0", 0)

        var scoped = false
        for (p in GAMES) try { b.addAllowedApplication(p); scoped = true } catch (_: Throwable) { }
        // scoped == false means FF isn't installed; we then carry the whole
        // device, which the TCP splice below is expected to cope with.

        val fd = b.establish()
        if (fd == null) { stopSelf(); return }

        tun = fd
        sink = FileOutputStream(fd.fileDescriptor)
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
        if (!ip.v4) return
        pkts.incrementAndGet()

        when (ip.proto) {
            Packets.UDP -> {
                val u = Packets.Udp(buf, ip.payload)
                if (Editors.onOutbound(buf, ip, u)) Packets.fixUdp4(buf, 0, ip.payload, u.len)
                Learner.record(buf, u)
                val key = "${ip.src}:${u.sport}>${ip.dst}:${u.dport}"
                flows.getOrPut(key) { Flow(ip.srcBytes, u.sport, ip.dstBytes, u.dport) }
                    .up(buf, ip.payload + 8, u.payloadLen)
            }
            Packets.TCP -> TcpRelay.onOutbound(this, buf, ip)
        }
    }

    /** Single writer into the TUN. */
    fun inject(pkt: ByteArray) {
        synchronized(wlock) { runCatching { sink?.write(pkt) } }
    }

    private fun janitor() {
        while (running) {
            val now = System.currentTimeMillis()
            val it = flows.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value.last > FLOW_IDLE_MS) { e.value.close(); it.remove() }
            }
            try { Thread.sleep(5000) } catch (_: InterruptedException) { }
        }
    }

    private inner class Flow(sB: ByteArray, sPort: Int, dB: ByteArray, dPort: Int) {
        private val src = sB
        private val dst = dB
        private val sp = sPort
        private val dp = dPort

        @Volatile var last = System.currentTimeMillis()

        private val ch = DatagramChannel.open().also {
            protect(it.socket())
            it.bind(InetSocketAddress(0))
            it.connect(InetSocketAddress(InetAddress.getByAddress(dB), dPort))
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
                            val p = ByteArray(r)
                            bb.flip(); bb.get(p)
                            inject(Packets.udp4(dst, src, dp, sp, p))
                        }
                    } catch (_: Throwable) { break }
                }
            }.also { it.isDaemon = true; it.start() }
        }

        fun up(buf: ByteArray, off: Int, len: Int) {
            last = System.currentTimeMillis()
            runCatching { ch.write(ByteBuffer.wrap(buf, off, len)) }
        }

        fun close() = runCatching { ch.close() }.let { }
    }

    private fun notif(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(
                NotificationChannel("packets", "PACKETS", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "packets")
            .setContentTitle("CYBER AIM · packets")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        flows.values.forEach { it.close() }
        flows.clear()
        runCatching { sink?.close() }
        runCatching { tun?.close() }
        sink = null
        tun = null
        runCatching { stopForeground(true) }
        super.onDestroy()
    }
}
