package com.example.ffcapture

enum class Direction { OUTBOUND, INBOUND }

interface PacketTransformer {
    /**
     * Called for every game packet right before it leaves for the server.
     * Return the same array (untouched) or a new one (mutated).
     * v2: decode -> flip bodypart / aim vector -> re-encode here.
     */
    fun mutate(packet: ByteArray, length: Int, direction: Direction): ByteArray
}

/** v1: does nothing. */
object NoOpTransformer : PacketTransformer {
    override fun mutate(packet: ByteArray, length: Int, direction: Direction) = packet
}
