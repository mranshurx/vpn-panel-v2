package com.example.ffcapture

import android.content.Context
import android.os.Build
import kotlin.math.abs

/**
 * CYBER ENGINE core.
 *
 * Fixes over v1:
 *  - solve() scans EVERY packet signature, not just the most frequent one
 *  - candidates are ranked (binary bytes win) and cycled automatically if nothing lands
 *  - live proof-of-life counters (seenOut / seenShot / patched)
 *  - self-heals a rotated keystream instead of sitting dead
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
    @Volatile var seenOut = 0L
    @Volatile var seenShot = 0L
    @Volatile var status = "idle"
    @Volatile var lastError = "-"

    @Volatile var candidates: List<Candidate> = emptyList()
    @Volatile var chosenIndex = 0

    private const val MIN_SAMPLES = 10
    private const val BURST_GAP_MS = 250L
    private const val PREFS = "cyber_engine"
    private const val LIVE_WINDOW = 400
    private const val AUTO_ADVANCE_AFTER = 600L
    private const val LEN_SLACK = 8

    private val samples = ArrayList<Sample>(16384)
    private val liveHist = HashMap<Int, Int>()
    private var liveSeen = 0

    @Volatile private var lastTs = 0L
    @Volatile private var solving = false
    private var appCtx: Context? = null

    private val logRing = ArrayDeque<String>()

    data class Sample(val head: Boolean, val type: Int, val len: Int, val data: ByteArray)
    data class Candidate(val offset: Int, val score: Double, val bVal: Int, val hVal: Int,
                         val type: Int, val lMin: Int, val lMax: Int, val binary: Boolean)

    fun log(s: String) {
        synchronized(logRing) { logRing.addLast(s); while (logRing.size > 80) logRing.removeFirst() }
        status = s
    }
    fun logs(): List<String> = synchronized(logRing) { logRing.toList() }

    private fun key(type: Int, len: Int): Long = (type.toLong() shl 24) or (len and 0xFFFFFF).toLong()
    private fun fail(msg: String) { phase = Phase.FAILED; lastError = msg; log("FAILED: $msg") }

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
        log("loaded saved training (off=$offset type=$shotType)")
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
            .putInt("offset", offset).putInt("type", shotType)
            .putInt("lenMin", lenMin).putInt("lenMax", lenMax)
            .putInt("delta", delta).putInt("bodyVal", bodyVal)
            .apply()
    }

    // ------------------------------------------------------------- training

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
        bodyCount = 0; headCount = 0; patched = 0; seenShot = 0
        armed = false; offset = -1; bodyVal = -1; headVal = -1
        trainHead = false; trainBody = false; candidates = emptyList()
        phase = Phase.IDLE; lastError = "-"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        log("training reset")
    }

    fun flip() {
        if (bodyVal < 0) return
        val t = bodyVal; bodyVal = headVal; headVal = t
        delta = bodyVal xor headVal
        log("flipped: b=0x%02x h=0x%02x".format(bodyVal, headVal))
    }

    fun nudge(d: Int) {
        if (offset < 0) return
        offset += d
        log("offset nudged -> $offset")
    }

    fun nextCandidate() {
        if (candidates.isEmpty()) return
        chosenIndex = (chosenIndex + 1) % candidates.size
        applyCandidate(candidates[chosenIndex])
        log("candidate ${chosenIndex + 1}/${candidates.size} off=${candidates[chosenIndex].offset}")
    }

    @Synchronized
    fun solve() {
        val body: List<Sample>; val head: List<Sample>
        synchronized(samples) {
            body = samples.filter { !it.head }
            head = samples.filter { it.head }
        }
        if (body.size < MIN_SAMPLES || head.size < MIN_SAMPLES) {
            fail("need more shots: b=${body.size} h=${head.size}"); return
        }

        // group by packet signature across EVERY type
        val bySig = HashMap<Long, Array<ArrayList<Sample>>>()
        fun add(list: List<Sample>, idx: Int) {
            for (s in list) {
                val arr = bySig.getOrPut(key(s.type, s.len)) { arrayOf(ArrayList<Sample>(), ArrayList<Sample>()) }
                arr[idx].add(s)
            }
        }
        add(body, 0); add(head, 1)

        val cands = ArrayList<Candidate>()
        for ((k, arr) in bySig) {
            val bg = arr[0]; val hg = arr[1]
            if (bg.size < MIN_SAMPLES || hg.size < MIN_SAMPLES) continue
            val sigType = (k shr 24).toInt()
            val sigLen = (k and 0xFFFFFF).toInt()
            if (sigLen <= 8) continue

            for (o in 0 until sigLen) {
                val bv = HashMap<Int, Int>(); val hv = HashMap<Int, Int>()
                for (s in bg) bv.merge(s.data[o].toInt() and 0xFF, 1, Int::plus)
                for (s in hg) hv.merge(s.data[o].toInt() and 0xFF, 1, Int::plus)
                val union = bv.keys + hv.keys
                if (union.size < 2 || union.size > 4) continue
                var tvd = 0.0
                for (v in union) tvd += abs((bv[v] ?: 0).toDouble() / bg.size - (hv[v] ?: 0).toDouble() / hg.size)
                tvd /= 2.0
                if (tvd < 0.85) continue
                val bDom = bv.maxByOrNull { it.value }?.key ?: continue
                val hDom = hv.maxByOrNull { it.value }?.key ?: continue
                if (bDom == hDom) continue
                val binary = union.size == 2
                cands.add(Candidate(o, tvd + (if (binary) 0.5 else 0.0),
                    bDom, hDom, sigType, sigLen, sigLen, binary))
            }
        }

        if (cands.isEmpty()) { fail("no candidate byte found"); return }

        candidates = cands.sortedByDescending { it.score }.take(10)
        chosenIndex = 0
        applyCandidate(candidates[0])
        phase = Phase.READY
        trainHead = false; trainBody = false
        lastError = "-"
        appCtx?.let { save(it) }
        log("TRAINED + SAVED off=${candidates[0].offset} type=${candidates[0].type} score=%.2f".format(candidates[0].score))
    }

    private fun applyCandidate(c: Candidate) {
        offset = c.offset
        bodyVal = c.bVal
        headVal = c.hVal
        delta = c.bVal xor c.hVal
        shotType = c.type
        lenMin = (c.lMin - LEN_SLACK).coerceAtLeast(0)
        lenMax = c.lMax + LEN_SLACK
        armed = true
        seenShot = 0
    }

    // ------------------------------------------------------------- runtime

    fun observe(p: ByteArray, len: Int) {
        seenOut++
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
        if (shotType < 0 || len < lenMin || len > lenMax || len <= offset) return
        if ((p[2].toInt() and 0xFF) != shotType) return
        seenShot++

        val v = p[offset].toInt() and 0xFF
        var ready = false
        synchronized(liveHist) {
            liveHist.merge(v, 1, Int::plus); liveSeen++
            if (liveSeen >= LIVE_WINDOW) ready = true
        }
        if (ready) { rederive(); synchronized(liveHist) { liveHist.clear(); liveSeen = 0 } }

        // nothing landing -> try the next ranked candidate automatically
        if (patched == 0L && seenShot >= AUTO_ADVANCE_AFTER && candidates.size > 1) {
            seenShot = 0
            nextCandidate()
            log("no hits yet -> next candidate")
        }
    }

    /** Only remaps when the stored body value has gone stale. */
    private fun rederive() {
        val total = synchronized(liveHist) { liveHist.values.sum() }
        if (total <= 0) return
        val dom = synchronized(liveHist) { liveHist.maxByOrNull { it.value }?.key } ?: return
        val bc = synchronized(liveHist) { liveHist[bodyVal] ?: 0 }
        if (bc * 5 >= total) return          // still valid, leave it
        bodyVal = dom
        headVal = dom xor delta
        log("re-mapped b=0x%02x h=0x%02x".format(bodyVal, headVal))
    }

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
