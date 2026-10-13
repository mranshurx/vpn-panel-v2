package com.example.ffcapture

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapWriter(file: File) {

    private val out: OutputStream = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val lock = Any()
    private var sinceFlush = 0

    init {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(0xa1b2c3d4.toInt())
        h.putShort(2)
        h.putShort(4)
        h.putInt(0)
        h.putInt(0)
        h.putInt(65535)
        h.putInt(101)
        synchronized(lock) { out.write(h.array()) }
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
