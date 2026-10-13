package com.example.ffcapture.net

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object Editors {
    interface Hook { fun onOutbound(pkt: ByteArray, ip: Packets.Ip, u: Packets.Udp): Boolean }
    private val hooks = CopyOnWriteArrayList<Hook>()
    fun register(h: Hook) { hooks.add(h) }
    fun onOutbound(pkt: ByteArray, ip: Packets.Ip, u: Packets.Udp): Boolean {
        var changed = false
        for (h in hooks) if (h.onOutbound(pkt, ip, u)) changed = true
        return changed
    }
}

/**
 * len     exact UDP payload length, or -1 for any
 * guardOff/guardVal  optional sanity check on the packet before touching it
 * off/set byte to force
 */
data class Rule(val len: Int, val off: Int, val set: Int,
                val guardOff: Int = -1, val guardVal: Int = -1, val note: String = "")

object Headshot : Editors.Hook {

    @Volatile var enabled = false
    @Volatile var rules: List<Rule> = emptyList()
    val hits = AtomicLong()

    override fun onOutbound(pkt: ByteArray, ip: Packets.Ip, u: Packets.Udp): Boolean {
        if (!enabled) return false
        for (r in rules) {
            if (r.len != -1 && r.len != u.payloadLen) continue
            if (r.guardOff >= 0 &&
                (pkt[u.payload + r.guardOff].toInt() and 0xFF) != r.guardVal) continue
            val at = u.payload + r.off
            if (at >= pkt.size) continue
            if ((pkt[at].toInt() and 0xFF) != r.set) {
                pkt[at] = r.set.toByte()
                hits.incrementAndGet()
                return true
            }
        }
        return false
    }
}

/** Records outbound payloads so you can find the field without Wireshark. */
object Learner {

    data class Sample(val len: Int, val hex: String, val at: Long)

    private val ring = ArrayDeque<Sample>()
    private const val CAP = 400
    @Volatile var armed = false

    fun record(pkt: ByteArray, u: Packets.Udp) {
        if (!armed) return
        val hex = buildString {
            for (i in 0 until minOf(u.payloadLen, 96))
                append("%02x".format(pkt[u.payload + i]))
        }
        synchronized(ring) {
            ring.addLast(Sample(u.payloadLen, hex, System.currentTimeMillis()))
            while (ring.size > CAP) ring.removeFirst()
        }
    }

    fun snapshot(): List<Sample> = synchronized(ring) { ring.toList() }
    fun clear() = synchronized(ring) { ring.clear() }

    /** For the most common length, report which byte positions vary. */
    fun varying(): Map<Int, List<Int>> {
        val byLen = snapshot().groupBy { it.len }
        val out = HashMap<Int, List<Int>>()
        for ((len, list) in byLen) {
            if (list.size < 4) continue
            val rows = list.map { it.hex.chunked(2) }
            val n = rows.minOf { it.size }
            val vary = (0 until n).filter { i -> rows.any { it[i] != rows[0][i] } }
            out[len] = vary
        }
        return out
    }
}
