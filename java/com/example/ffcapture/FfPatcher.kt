package com.example.ffcapture

/**
 * Rewrites specific bytes in Free Fire's UDP bodies.
 *
 * Two modes:
 *  - cipher == null  -> blind XOR patch (no key needed; a genuine headshot
 *                       becomes a bodyshot, which is the usual trade-off)
 *  - cipher != null  -> decrypt, edit only if the value matches, re-encrypt
 */
class FfPatcher : UdpPayloadTransformer {

    // ============ EDIT THESE AFTER YOUR OWN CAPTURE ============

    /** Offset where Free Fire's own body starts (after its header). */
    var bodyStart = 6

    /** Only touch packets whose FF type byte equals this (0 = any). */
    var onlyType = 0

    /** Hitbox rule. Offset is relative to bodyStart. -1 = off. */
    var hitboxOffset = -1
    var bodyValue = 0x00
    var headValue = 0x01

    /** Enemy-seen rule. Offset is relative to bodyStart. -1 = off. */
    var seenOffset = -1
    var seenNoValue = 0x00
    var seenYesValue = 0x01

    var patchOutbound = true
    var patchInbound = false

    /** Session key for value-aware edits. Null = blind XOR mode. */
    var cipher: FfCipher? = null

    // ==========================================================

    var enabled = true

    override fun mutate(payload: ByteArray, length: Int, direction: Direction): ByteArray {
        if (!enabled || length <= bodyStart) return payload
        if (direction == Direction.OUTBOUND && !patchOutbound) return payload
        if (direction == Direction.INBOUND && !patchInbound) return payload

        if (onlyType != 0 && (payload[2].toInt() and 0xFF) != onlyType) return payload

        val c = cipher
        if (c != null) c.apply(payload, bodyStart, length)
        try {
            if (hitboxOffset >= 0) edit(payload, length, hitboxOffset, bodyValue, headValue)
            if (seenOffset >= 0) edit(payload, length, seenOffset, seenNoValue, seenYesValue)
        } finally {
            if (c != null) c.apply(payload, bodyStart, length)
        }
        return payload
    }

    private fun edit(buf: ByteArray, length: Int, relOff: Int, from: Int, to: Int) {
        val i = bodyStart + relOff
        if (i >= length) return
        if (cipher == null) {
            buf[i] = (buf[i].toInt() xor (from xor to)).toByte()   // blind
        } else if ((buf[i].toInt() and 0xFF) == from) {
            buf[i] = to.toByte()                                    // value-aware
        }
    }
}
