package com.example.ffcapture

import android.content.Context
import android.os.Build
import kotlin.math.abs

/**
 * CYBER ENGINE core.
 *
 * FF's UDP body is a position-keyed XOR stream, so a plaintext flag maps to a
 * ciphertext byte at the SAME offset. We learn + rewrite on ciphertext only.
 *
 * Training: TRAIN HEAD on -> shoot heads; TRAIN BODY on -> shoot bodies.
 * When both corpora are full it solves, arms and SAVES automatically.
 */
object HeadshotEngine {

    enum class Phase { IDLE, TRAINING, READY, FAILED }

    @Volatile var phase = Phase.IDLE
    @Volatile var armed = false
    @Volatile var trainHead = false
    @Volatile var trainBody = false

    @Volatile var offset = -1
    @Volatile var bodyVal = -1
    @Volatile var headVal = -1
    @Volatile var delta = 1
    @Volatile var shotType = -1
    @Volatile var lenMin = 0
    @Volatile var lenMax = 0

    @Volatile var bodyCount = 0
    @Volatile var headCount = 0
    @Volatile var patched = 0L
    @Volatile var status = "idle"

    @Volatile var candidates: List<Candidate> = emptyList()
    @Volatile var chosenIndex = 0

    private const val MIN_SAMPLES = 12
    private const val BURST_GAP_MS = 200L
    private const val PREFS = "cyber_engine"
    private const val LIVE_WINDOW = 300

    private val samples = ArrayList<Sample>(8192)
    private val liveHist = HashMap<Int, Int>()
    private var liveSeen = 0

    @Volatile private var lastTs = 0L
    @Volatile private var solving = false
    private var appCtx: Context? = null

    private val logRing = ArrayDeque<String>()

    data class Sample(val head: Boolean, val type: Int, val len: Int, val data: ByteArray)
    data class Candidate(val offset: Int, val score: Double, val bVal: Int, val hVal: Int,
                         val type: Int, val lMin: Int, val lMax: Int)

    fun log(s: String) {
        synchronized(logRing) { logRing.addLast(s); while (logRing.size > 60) logRing.removeFirst() }
        status = s
    }

    fun logs(): List<String> = synchronized(logRing) { logRing.toList() }

    // ------------------------------------------------------------- restore

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getLong("vc", -2L) != versionCode(ctx)) { log("game updated -> retrain"); return }
        val o = sp.getInt("offset", -1)
        if (o < 0) return
        offset = o
        shotType = sp.getInt("type", -1)
        lenMin = sp.getInt("lenMin", 0)
        lenMax = sp.getInt("lenMax", 0)
        delta = sp.getInt("delta", 1)
        bodyVal = sp.getInt("bodyVal", -1)
        headVal = bodyVal xor delta
        armed = true
        phase = Phase.READY
        log("loaded saved training (offset=$offset)")
    }

    private fun versionCode(ctx: Context): Long {
        for (pkg in CaptureVpnService.FF_PACKAGES) {
            try {
                val pi = ctx.packageManager.getPackageInfo(pkg, 0)
                return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            } catch (e: Exception) { }
        }
        return -1L
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("vc", versionCode(ctx))
            .putInt("offset", offset)
            .putInt("type", shotType)
            .putInt("lenMin", lenMin)
            .putInt("lenMax", lenMax)
            .putInt("delta", delta)
            .putInt("bodyVal", bodyVal)
            .apply()
    }

    // ------------------------------------------------------------- training

    /** Exactly one training class at a time. */
    fun setTrain(head: Boolean, on: Boolean) {
        if (on) {
            if (head) { trainHead = true; trainBody = false } else { trainBody = true; trainHead = false }
            phase = Phase.TRAINING
            log(if (head) "TRAIN HEAD: shoot bots in the head" else "TRAIN BODY: shoot bots in the body")
        } else {
            if (head) trainHead = false else trainBody = false
            if (!trainHead && !trainBody && !armed) phase = Phase.IDLE
        }
    }

    fun reset(ctx: Context) {
        synchronized(samples) { samples.clear() }
        bodyCount = 0; headCount = 0; patched = 0
        armed = false; offset = -1; bodyVal = -1; headVal = -1
        trainHead = false; trainBody = false
        phase = Phase.IDLE
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        log("training reset")
    }

    fun flip() {
        if (bodyVal < 0) return
        val t = bodyVal; bodyVal = headVal; headVal = t
        delta = bodyVal xor headVal
        log("flipped: body=0x%02x head=0x%02x".format(bodyVal, headVal))
    }

    fun nextCandidate() {
        if (candidates.isEmpty()) return
        chosenIndex = (chosenIndex + 1) % candidates.size
        applyCandidate(candidates[chosenIndex])
        log("candidate ${chosenIndex + 1}/${candidates.size} offset=${candidates[chosenIndex].offset}")
    }

    @Synchronized
    fun solve() {
        val body: List<Sample>; val head: List<Sample>
        synchronized(samples) {
            body = samples.filter { !it.head }
            head = samples.filter { it.head }
        }
        if (body.size < MIN_SAMPLES || head.size < MIN_SAMPLES) {
            phase = Phase.FAILED
            log("need more shots: body=${body.size} head=${head.size}")
            return
        }

        val sigCount = HashMap<Long, Int>()
        for (s in body + head) {
            val key = (s.type.toLong() shl 24) or (s.len and 0xFFFFFF).toLong()
            sigCount.merge(key, 1, Int::plus)
        }
        val sig = sigCount.maxByOrNull { it.value }
        if (sig == null) { phase = Phase.FAILED; log("no signature"); return }
        val sigType = (sig.key shr 24).toInt()
        val sigLen = (sig.key and 0xFFFFFF).toInt()

        val bg = body.filter { it.type == sigType && it.len == sigLen }
        val hg = head.filter { it.type == sigType && it.len == sigLen }
        if (bg.size < MIN_SAMPLES || hg.size < MIN_SAMPLES) {
            phase = Phase.FAILED
            log("signature mismatch b=${bg.size} h=${hg.size} type=$sigType len=$sigLen")
            return
        }

        val cands = ArrayList<Candidate>()
        for (o in 0 until sigLen) {
            val bv = HashMap<Int, Int>(); val hv = HashMap<Int, Int>()
            for (s in bg) bv.merge(s.data[o].toInt() and 0xFF, 1, Int::plus)
            for (s in hg) hv.merge(s.data[o].toInt() and 0xFF, 1, Int::plus)
            val union = bv.keys + hv.keys
            if (union.size < 2 || union.size > 4) continue
            var tvd = 0.0
            for (v in union) tvd += abs((bv[v] ?: 0).toDouble() / bg.size - (hv[v] ?: 0).toDouble() / hg.size)
            tvd /= 2.0
            if (tvd < 0.8) continue
            val bDom = bv.maxByOrNull { it.value }?.key ?: continue
            val hDom = hv.maxByOrNull { it.value }?.key ?: continue
            if (bDom == hDom) continue
            cands.add(Candidate(o, tvd, bDom, hDom, sigType, sigLen, sigLen))
        }

        if (cands.isEmpty()) { phase = Phase.FAILED; log("no candidate byte found"); return }

        candidates = cands.sortedByDescending { it.score }
        chosenIndex = 0
        applyCandidate(candidates[0])
        phase = Phase.READY
        trainHead = false; trainBody = false
        appCtx?.let { save(it) }
        log("TRAINED + SAVED  offset=${candidates[0].offset}  score=%.2f".format(candidates[0].score))
    }

    private fun applyCandidate(c: Candidate) {
        offset = c.offset
        bodyVal = c.bVal
        headVal = c.hVal
        delta = c.bVal xor c.hVal
        shotType = c.type
        lenMin = c.lMin
        lenMax = c.lMax
        armed = true
    }

    // ------------------------------------------------------------- runtime

    /** Called for every outbound game packet, BEFORE patching. */
    fun observe(p: ByteArray, len: Int) {
        if (len < 8) return
        val now = System.currentTimeMillis()
        val burst = now - lastTs < BURST_GAP_MS
        lastTs = now
        if (!burst) return

        if (trainHead) record(p, len, true)
        else if (trainBody) record(p, len, false)
        else if (armed) liveObserve(p, len)
    }

    private fun record(p: ByteArray, len: Int, head: Boolean) {
        synchronized(samples) {
            if (samples.size >= 20000) return
            if (head) headCount++ else bodyCount++
            samples.add(Sample(head, p[2].toInt() and 0xFF, len, p.copyOf(len)))
        }
        if (!armed && !solving && headCount >= MIN_SAMPLES && bodyCount >= MIN_SAMPLES) {
            solving = true
            try { solve() } finally { solving = false }
        }
    }

    private fun liveObserve(p: ByteArray, len: Int) {
        if (shotType < 0 || len <= offset) return
        if ((p[2].toInt() and 0xFF) != shotType) return
        val v = p[offset].toInt() and 0xFF
        var ready = false
        synchronized(liveHist) {
            liveHist.merge(v, 1, Int::plus)
            liveSeen++
            if (liveSeen >= LIVE_WINDOW) ready = true
        }
        if (ready) { rederive(); synchronized(liveHist) { liveHist.clear(); liveSeen = 0 } }
    }

    private fun rederive() {
        val dom = synchronized(liveHist) { liveHist.maxByOrNull { it.value }?.key } ?: return
        if (dom == bodyVal) return
        bodyVal = dom
        headVal = dom xor delta
        log("re-mapped body=0x%02x head=0x%02x".format(bodyVal, headVal))
    }

    /** Rewrites a body value into the head value. True if changed. */
    fun applyPatch(p: ByteArray, len: Int): Boolean {
        if (!armed || offset < 0) return false
        if (len < lenMin || len > lenMax || len <= offset) return false
        if ((p[2].toInt() and 0xFF) != shotType) return false
        if ((p[offset].toInt() and 0xFF) != bodyVal) return false
        p[offset] = headVal.toByte()
        patched++
        return true
    }
}
