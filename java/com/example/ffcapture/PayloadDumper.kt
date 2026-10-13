package com.example.ffcapture

import java.io.FileOutputStream
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
class PayloadDumper(private val out: FileOutputStream) {

    private val lock = Any()

    init {
        synchronized(lock) {
            out.write("FFRAW1".toByteArray())
            out.flush()
        }
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
            out.flush()
        }
    }

    fun close() {
        synchronized(lock) { out.flush(); out.close() }
    }
}
