package com.example.ffcapture

/** Mutates a UDP payload (IP/UDP headers already stripped). */
interface UdpPayloadTransformer {
    fun mutate(payload: ByteArray, length: Int, direction: Direction): ByteArray
}

object NoOpPayloadTransformer : UdpPayloadTransformer {
    override fun mutate(payload: ByteArray, length: Int, direction: Direction) = payload
}
