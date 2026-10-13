package com.example.ffcapture

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapWriter(private val out: OutputStream) {

    private val lock = Any()

    init {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(0xa1b2c3d4.toInt())
        h.putShort(2)
        h.putShort(4)
        h.putInt(0)
        h.putInt(0)
        h.putInt(65535)
        h.putInt(101)
        synchronized(lock) {
            out.write(h.array())
            out.flush()
        }
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
            out.flush()
        }
    }

    fun close() {
        synchronized(lock) {
            out.flush()
            out.close()
        }
    }
}
