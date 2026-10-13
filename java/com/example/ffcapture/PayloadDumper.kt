package com.example.ffcapture

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dumps the exact application-layer bytes (what Free Fire itself sends/receives),
 * with all IP/UDP/TCP headers stripped.
 *
 * File = magic "FFRAW1" followed by records:
 *   byte  direction  (0 = OUTBOUND, 1 = INBOUND)
 *   long  timestamp  (epoch millis)
 *   byte  protocol   (6 = TCP, 17 = UDP)
 *   short srcPort
 *   short dstPort
 *   int   length
 *   byte[length] payload   <- verbatim
 */
class PayloadDumper(file: File) {

    private val out: OutputStream = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val lock = Any()
    private var sinceFlush = 0

    init {
        synchronized(lock) { out.write("FFRAW1".toByteArray()) }
    }

    fun write(dir: Direction, proto: Int, srcPort: Int, dstPort: Int,
              payload: ByteArray, off: Int, len: Int) {
        val h = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        h.put(if (dir == Direction.OUTBOUND) 0 else 1)
        h.putLong(System.currentTimeMillis())
        h.put(proto.toByte())
        h.putShort(srcPort.toShort())
        h.putShort(dstPort.toShort())
        h.putInt(len)
        synchronized(lock) {
            out.write(h.array())
            out.write(payload, off, len)
            if (++sinceFlush >= 64) {
                out.flush()
                sinceFlush = 0
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { out.flush() }
            runCatching { out.close() }
        }
    }
}
