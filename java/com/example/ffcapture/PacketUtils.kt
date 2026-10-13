package com.example.ffcapture

object PacketUtils {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
        (b[o + 3].toInt() and 0xFF)

    fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    fun readShort(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun writeShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun isIpv4(p: ByteArray): Boolean = (p[0].toInt() and 0xF0) == 0x40

    fun ipHeaderLen(p: ByteArray): Int = (p[0].toInt() and 0x0F) * 4

    fun protocol(p: ByteArray): Int = p[9].toInt() and 0xFF

    fun totalLength(p: ByteArray): Int = readShort(p, 2)

    fun toBytes(ip: Int): ByteArray = byteArrayOf(
        (ip ushr 24).toByte(),
        (ip ushr 16).toByte(),
        (ip ushr 8).toByte(),
        ip.toByte()
    )

    fun checksum(b: ByteArray, off: Int, len: Int, init: Long): Int {
        var sum = init
        var i = off
        var n = len
        while (n > 1) {
            sum += (((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)).toLong()
            i += 2
            n -= 2
        }
        if (n == 1) sum += ((b[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun pseudoSum(srcIp: Int, dstIp: Int, proto: Int, len: Int): Long {
        var s = 0L
        s += ((srcIp ushr 16) and 0xFFFF).toLong() + (srcIp and 0xFFFF).toLong()
        s += ((dstIp ushr 16) and 0xFFFF).toLong() + (dstIp and 0xFFFF).toLong()
        s += proto.toLong() + len.toLong()
        return s
    }

    fun buildUdp(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
                 payload: ByteArray, off: Int, len: Int): ByteArray {
        val total = 20 + 8 + len
        val p = ByteArray(total)
        p[0] = 0x45
        writeShort(p, 2, total)
        writeShort(p, 6, 0x4000)
        p[8] = 64
        p[9] = 17
        writeInt(p, 12, srcIp)
        writeInt(p, 16, dstIp)
        writeShort(p, 10, checksum(p, 0, 20, 0))
        writeShort(p, 20, srcPort)
        writeShort(p, 22, dstPort)
        writeShort(p, 24, 8 + len)
        System.arraycopy(payload, off, p, 28, len)
        val cs = checksum(p, 20, 8 + len, pseudoSum(srcIp, dstIp, 17, 8 + len))
        writeShort(p, 26, if (cs == 0) 0xFFFF else cs)
        return p
    }

    /**
     * Builds a TCP segment inside an IPv4 packet.
     * Pass mss > 0 to append a 4-byte MSS option (SYN / SYN-ACK only).
     */
    fun buildTcp(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
                 seq: Int, ack: Int, flags: Int,
                 payload: ByteArray?, off: Int, len: Int, mss: Int = 0): ByteArray {
        val optLen = if (mss > 0) 4 else 0
        val tcpHdr = 20 + optLen
        val total = 20 + tcpHdr + len
        val p = ByteArray(total)
        p[0] = 0x45
        writeShort(p, 2, total)
        writeShort(p, 6, 0x4000)
        p[8] = 64
        p[9] = 6
        writeInt(p, 12, srcIp)
        writeInt(p, 16, dstIp)
        writeShort(p, 10, checksum(p, 0, 20, 0))
        writeShort(p, 20, srcPort)
        writeShort(p, 22, dstPort)
        writeInt(p, 24, seq)
        writeInt(p, 28, ack)
        p[32] = ((tcpHdr / 4) shl 4).toByte()
        p[33] = flags.toByte()
        writeShort(p, 34, 65535)
        if (mss > 0) {
            p[40] = 2
            p[41] = 4
            writeShort(p, 42, mss)
        }
        if (len > 0 && payload != null) System.arraycopy(payload, off, p, 20 + tcpHdr, len)
        writeShort(p, 20 + 16,
            checksum(p, 20, tcpHdr + len, pseudoSum(srcIp, dstIp, 6, tcpHdr + len)))
        return p
    }
}
