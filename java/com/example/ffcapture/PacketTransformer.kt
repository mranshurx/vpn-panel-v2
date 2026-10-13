package com.example.ffcapture

enum class Direction { OUTBOUND, INBOUND }

interface PacketTransformer {
    fun mutate(packet: ByteArray, length: Int, direction: Direction): ByteArray
}

object NoOpTransformer : PacketTransformer {
    override fun mutate(packet: ByteArray, length: Int, direction: Direction): ByteArray =
        packet
}
