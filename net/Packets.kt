package com.example.ffcapture.net

import java.net.InetAddress

/** Zero-copy views + builders for the IP/UDP/TCP headers we touch. */
object Packets {

    const val UDP = 17
    const val TCP = 6

    fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    fun p16(b: ByteArray, p: Int, v: Int) { b[p] = (v ushr 8).toByte(); b[p + 1] = v.toByte() }
    fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p+1].toLong() and 0xFF) shl 16) or
        ((b[p+2].toLong() and 0xFF) shl 8) or (b[p+3].toLong() and 0xFF)
    fun p32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v ushr 24).toByte(); b[p+1] = (v ushr 16).toByte()
        b[p+2] = (v ushr 8).toByte(); b[p+3] = v.toByte()
    }

    class Ip(val b: ByteArray, val off: Int) {
        val v4 = (b[off].toInt() ushr 4) == 4
        val hlen = if (v4) (b[off].toInt() and 0xF) * 4 else 40
        val proto = b[off + if (v4) 9 else 6].toInt() and 0xFF
        val srcBytes = if (v4) b.copyOfRange(off + 12, off + 16) else b.copyOfRange(off + 8, off + 24)
        val dstBytes = if (v4) b.copyOfRange(off + 16, off + 20) else b.copyOfRange(off + 24, off + 40)
        val src get() = InetAddress.getByAddress(srcBytes).hostAddress
        val dst get() = InetAddress.getByAddress(dstBytes).hostAddress
        val payload = off + hlen
    }

    class Udp(val b: ByteArray, val off: Int) {
        val sport = u16(b, off)
        val dport = u16(b, off + 2)
        val len = u16(b, off + 4)
        val payload = off + 8
        val payloadLen = (len - 8).coerceAtLeast(0)
    }

    class Tcp(val b: ByteArray, val off: Int) {
        val sport = u16(b, off); val dport = u16(b, off + 2)
        val seq = u32(b, off + 4); val ack = u32(b, off + 8)
        val flags = b[off + 13].toInt() and 0xFF
        val hlen = ((b[off + 12].toInt() and 0xF0) ushr 4) * 4
        val payload = off + hlen
        val FIN = flags and 0x01 != 0
        val SYN = flags and 0x02 != 0
        val RST = flags and 0x04 != 0
        val ACK = flags and 0x10 != 0
    }

    private fun sum(b: ByteArray, from: Int, to: Int, init: Int = 0): Int {
        var s = init; var i = from
        while (i + 1 < to) { s += u16(b, i); i += 2 }
        if (i < to) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }
    private fun fold(s0: Int): Int {
        var s = s0
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }

    fun fixUdp4(pkt: ByteArray, ipOff: Int, udpOff: Int, udpLen: Int) {
        p16(pkt, udpOff + 6, 0)
        var s = sum(pkt, ipOff + 12, ipOff + 20) + UDP + udpLen
        s = sum(pkt, udpOff, udpOff + udpLen, s)
        var c = fold(s); if (c == 0) c = 0xFFFF
        p16(pkt, udpOff + 6, c)
    }

    fun fixIp4(pkt: ByteArray, ipOff: Int) {
        val hlen = (pkt[ipOff].toInt() and 0xF) * 4
        p16(pkt, ipOff + 10, 0)
        p16(pkt, ipOff + 10, fold(sum(pkt, ipOff, ipOff + hlen)))
    }

    fun udp4(src: ByteArray, dst: ByteArray, sport: Int, dport: Int,
             pl: ByteArray, plOff: Int = 0, plLen: Int = pl.size): ByteArray {
        val b = ByteArray(28 + plLen)
        b[0] = 0x45; p16(b, 2, b.size); b[8] = 64; b[9] = UDP.toByte()
        System.arraycopy(src, 0, b, 12, 4); System.arraycopy(dst, 0, b, 16, 4)
        fixIp4(b, 0)
        p16(b, 20, sport); p16(b, 22, dport); p16(b, 24, 8 + plLen)
        System.arraycopy(pl, plOff, b, 28, plLen)
        fixUdp4(b, 0, 20, 8 + plLen)
        return b
    }

    fun tcp4(src: ByteArray, dst: ByteArray, sport: Int, dport: Int,
             seq: Long, ack: Long, flags: Int, pl: ByteArray? = null,
             plOff: Int = 0, plLen: Int = 0, win: Int = 65535): ByteArray {
        val b = ByteArray(40 + plLen)
        b[0] = 0x45; p16(b, 2, b.size); b[8] = 64; b[9] = TCP.toByte()
        System.arraycopy(src, 0, b, 12, 4); System.arraycopy(dst, 0, b, 16, 4)
        fixIp4(b, 0)
        p16(b, 20, sport); p16(b, 22, dport)
        p32(b, 24, seq); p32(b, 28, ack)
        b[32] = 0x50; b[33] = flags.toByte(); p16(b, 34, win); p16(b, 36, 0)
        if (plLen > 0 && pl != null) System.arraycopy(pl, plOff, b, 40, plLen)
        var s = sum(b, 12, 20) + TCP + (20 + plLen)
        s = sum(b, 20, 40 + plLen, s)
        p16(b, 36, fold(s))
        return b
    }
}
