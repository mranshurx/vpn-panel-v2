package com.example.ffcapture

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapWriter(private val out: OutputStream) {

    private val lock = Any()

    init {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(0xa1b2c3d4.toInt())  // magic (little-endian file)
        h.putShort(2)                 // version major
        h.putShort(4)                 // version minor
        h.putInt(0)                   // thiszone
        h.putInt(0)                   // sigfigs
        h.putInt(65535)               // snaplen
        h.putInt(101)                 // LINKTYPE_RAW
        synchronized(lock) { out.write(h.array()); out.flush() }
    }

    fun writePacket(data: ByteArray, len: Int, tsMillis: Long) {
        val h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt((tsMillis / 1000).toInt())
        h.putInt(((tsMillis % 1000) * 1000).toInt())
        h.putInt(len)
        h.putInt(len)
        synchronized(lock) {
            out.write(h.array())
            out.write(data, 0, len)
            out.flush()   // flush per packet: crash-safe, good enough here
        }
    }

    fun close() = synchronized(lock) { out.flush(); out.close() }
}
