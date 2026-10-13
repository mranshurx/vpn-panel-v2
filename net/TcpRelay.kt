package com.example.ffcapture.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

object TcpRelay {

    private val sessions = ConcurrentHashMap<String, Sess>()

    fun onOutbound(vpn: PacketVpnService, buf: ByteArray, ip: Packets.Ip) {
        val t = Packets.Tcp(buf, ip.payload)
        val key = "${ip.src}:${t.sport}>${ip.dst}:${t.dport}"
        if (t.RST) { sessions.remove(key)?.kill(); return }
        val s = sessions.getOrPut(key) { Sess(vpn, ip.srcBytes, t.sport, ip.dstBytes, t.dport) }
        s.fromClient(buf, ip, t)
    }

    private class Sess(
        val vpn: PacketVpnService,
        val cSrc: ByteArray, val cSport: Int,
        val cDst: ByteArray, val cDport: Int
    ) {
        private val downIsn = Random.nextLong(0, 0xFFFFFFFFL)
        private var downSent = 0L
        private var clientNext = 0L
        @Volatile private var sock: Socket? = null
        @Volatile private var dead = false
        private var pending = ArrayList<ByteArray>()
        private var greeted = false

        fun kill() { dead = true; runCatching { sock?.close() } }

        private fun emit(flags: Int, payload: ByteArray? = null, off: Int = 0, len: Int = 0) {
            vpn.inject(Packets.tcp4(cDst, cSrc, cDport, cSport,
                downIsn + downSent, clientNext, flags, payload, off, len))
            if (len > 0) downSent += len
        }

        fun fromClient(buf: ByteArray, ip: Packets.Ip, t: Packets.Tcp) {
            if (dead) return
            val plLen = t.payload.let { if (ip.payload + t.hlen >= ip.payload) (ip.v4.let { 0 } + 0) else 0 } // placeholder
            val dataLen = (ip.let { 0 } + 0)
            // payload length = udp-style: for TCP derive from IP total minus headers
            val ipTotal = if (ip.v4) Packets.u16(buf, 2) else Packets.u16(buf, 4)
            val dataLenReal = ipTotal - ip.hlen - t.hlen

            if (t.SYN && !greeted) {
                greeted = true
                clientNext = (t.seq + 1) and 0xFFFFFFFFL
                emit(0x12)                       // SYN|ACK, our own ISN
                connect()
            } else {
                if (dataLenReal > 0) {
                    val chunk = buf.copyOfRange(t.payload, t.payload + dataLenReal)
                    clientNext = (clientNext + dataLenReal) and 0xFFFFFFFFL
                    sock?.let { s -> runCatching { s.getOutputStream().write(chunk) } }
                        ?: pending.add(chunk)
                }
                if (t.FIN) runCatching { sock?.shutdownOutput() }
            }
        }

        private fun connect() {
            thread(isDaemon = true) {
                val s = Socket()
                try {
                    vpn.protect(s)
                    s.connect(InetSocketAddress(InetAddress.getByAddress(cDst), cDport), 8000)
                    s.tcpNoDelay = true
                    sock = s
                    pending.forEach { runCatching { s.getOutputStream().write(it) } }
                    pending.clear()

                    val inb = s.getInputStream()
                    val b = ByteArray(8192)
                    while (!dead) {
                        val r = inb.read(b)
                        if (r < 0) break
                        emit(0x18, b, 0, r)          // PSH|ACK
                    }
                    emit(0x11)                        // FIN|ACK
                } catch (_: Throwable) {
                    emit(0x14)                        // RST|ACK
                } finally { dead = true; runCatching { s.close() } }
            }
        }
    }
}
