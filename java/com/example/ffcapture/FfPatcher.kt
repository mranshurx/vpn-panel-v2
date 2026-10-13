package com.example.ffcapture

/** Feeds outbound game traffic into the auto-headshot engine. */
class FfPatcher : UdpPayloadTransformer {
    override fun mutate(payload: ByteArray, length: Int, direction: Direction): ByteArray {
        if (direction == Direction.OUTBOUND) {
            HeadshotEngine.observe(payload, length)   // learn / track
            HeadshotEngine.applyPatch(payload, length) // body -> head
        }
        return payload
    }
}
