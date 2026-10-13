package com.example.ffcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.abs

class AimService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var enabled = true

        // tuning
        @Volatile var gain = 1.0f          // crosshair travel per detected pixel
        @Volatile var strength = 0.8f      // how much of the gap to close per tick
        @Volatile var radiusFrac = 0.28f   // engage only within this % of width
        @Volatile var deadzone = 4f        // px, ignore tiny errors
        @Volatile var headBias = 0.10f     // capture px down from the crown
        @Volatile var target = 0xFFFF3B30.toInt()
        @Volatile var tol = 70

        // stats
        @Volatile var aims = 0L
        @Volatile var frames = 0L
        @Volatile var seen = 0
        @Volatile var lastDx = 0
        @Volatile var lastDy = 0

        var pendingCode = 0
        var pendingData: Intent? = null

        // one-shot snap request: written by the overlay, consumed by tick()
        @Volatile private var snapping = false
        fun snapNow() { snapping = true }

        // one-shot colour-pick request
        @Volatile private var pickRequest = false
        fun pickColour() { pickRequest = true }
    }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var w = 0; private var h = 0
    private var realW = 0f; private var realH = 0f
    private var scaleX = 1f; private var scaleY = 1f

    private var pixels: IntArray? = null
    @Volatile private var fresh = false
    private var lastInject = 0L

    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(7, notif())

        val data = pendingData
        if (data != null && projection == null) startProjection(pendingCode, data)
        return START_STICKY
    }

    private fun startProjection(code: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = runCatching { mgr.getMediaProjection(code, data) }.getOrNull() ?: return
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)

        val dm = resources.displayMetrics
        realW = dm.widthPixels.toFloat()
        realH = dm.heightPixels.toFloat()
        // /4 keeps the per-tick pixel scan cheap. Use /3 or /2 if far, thin
        // outlines keep vanishing (costs CPU: the scan runs on the main thread).
        w = (dm.widthPixels / 4).coerceAtLeast(180)
        h = (dm.heightPixels / 4).coerceAtLeast(320)
        scaleX = realW / w
        scaleY = realH / h
        pixels = IntArray(w * h)

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val stride = plane.rowStride
                val px = pixels ?: return@setOnImageAvailableListener
                if (stride == w * 4) { buf.rewind(); buf.asIntBuffer().get(px) }
                else for (y in 0 until h) {
                    buf.position(y * stride)
                    buf.asIntBuffer().get(px, y * w, w)
                }
                frames++
                val now = System.currentTimeMillis()
                if (enabled && now - lastInject >= 70L) {
                    lastInject = now
                    tick()
                }
            } catch (e: Throwable) {
            } finally { img.close() }
        }, ui)
        reader = r

        vd = p.createVirtualDisplay("cyberaim", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        running = true
    }

    private fun tick() {
        val px = pixels ?: return

        if (pickRequest) { pickRequest = false; pick() }

        // consume a pending SNAP NOW (one-shot)
        val snap = snapping
        snapping = false

        val head = findHead(px)
        if (head == null) { seen = 0; return }
        seen = 1

        val cx = w / 2f
        val cy = h / 2f
        val dx = (head.first - cx) * scaleX
        val dy = (head.second - cy) * scaleY

        val radius = realW * radiusFrac
        if (!snap && dx * dx + dy * dy > radius * radius) return

        val ax = dx * gain * strength
        val ay = dy * gain * strength
        if (!snap && abs(ax) < deadzone && abs(ay) < deadzone) return

        lastDx = ax.toInt(); lastDy = ay.toInt()
        val ox = realW * 0.72f
        val oy = realH * 0.50f
        InputInjector.drag(ox, oy, ox + ax, oy + ay, 16L, 3)
        aims++
    }

    /**
     * Nearest outline cluster to the crosshair; returns the top of its head.
     *
     * Colour cannot separate a head from a hand (same colour), so geometry
     * does it instead: the head is the topmost solid run of the silhouette.
     */
    private fun findHead(px: IntArray): Pair<Float, Float>? {
        val cell = 4
        val gw = w / cell; val gh = h / cell
        if (gw < 4 || gh < 4) return null

        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF

        fun hit(x: Int, y: Int): Boolean {
            val c = px[y * w + x]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return abs(r - tr) <= tol && abs(g - tg) <= tol && abs(b - tb) <= tol
        }

        val grid = BooleanArray(gw * gh)
        var any = false
        for (y in 0 until h) {
            val gy = y / cell
            for (x in 0 until w) {
                if (hit(x, y)) { grid[gy * gw + x / cell] = true; any = true }
            }
        }
        if (!any) return null

        // Pick the cluster nearest the crosshair, with Y squashed so a head
        // above the crosshair beats a hand stuck out to the side.
        val cx = w / 2; val cy = h / 2
        var best = -1; var bestD = Float.MAX_VALUE
        for (gy in 0 until gh) for (gx in 0 until gw) {
            if (!grid[gy * gw + gx]) continue
            val ddx = (gx * cell + cell / 2) - cx
            val ddy = ((gy * cell + cell / 2) - cy) * 0.55f
            val d = (ddx * ddx + ddy * ddy).toFloat()
            if (d < bestD) { bestD = d; best = gy * gw + gx }
        }
        if (best < 0) return null

        val seenG = BooleanArray(gw * gh)
        val stack = ArrayDeque<Int>()
        stack.addLast(best); seenG[best] = true
        var minX = gw; var maxX = -1; var minY = gh; var maxY = -1
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val gx = i % gw; val gy = i / gw
            if (gx < minX) minX = gx
            if (gx > maxX) maxX = gx
            if (gy < minY) minY = gy
            if (gy > maxY) maxY = gy
            if (gx + 1 < gw) { val n = i + 1; if (!seenG[n] && grid[n]) { seenG[n] = true; stack.addLast(n) } }
            if (gx - 1 >= 0) { val n = i - 1; if (!seenG[n] && grid[n]) { seenG[n] = true; stack.addLast(n) } }
            if (gy + 1 < gh) { val n = i + gw; if (!seenG[n] && grid[n]) { seenG[n] = true; stack.addLast(n) } }
            if (gy - 1 >= 0) { val n = i - gw; if (!seenG[n] && grid[n]) { seenG[n] = true; stack.addLast(n) } }
        }

        // Walk down from the cluster top and take the centre of the first
        // solid run — that is the crown of the head.
        val x0 = (minX * cell).coerceAtLeast(0)
        val x1 = ((maxX + 1) * cell).coerceAtMost(w)
        val y0 = (minY * cell).coerceAtLeast(0)
        val yEnd = (y0 + 12).coerceAtMost((maxY + 1) * cell).coerceAtMost(h)

        var headX = -1f
        var headY = -1f
        for (y in y0 until yEnd) {
            var run = 0; var start = 0; var bestLen = 0; var bestStart = 0
            for (x in x0 until x1) {
                if (hit(x, y)) {
                    if (run == 0) start = x
                    run++
                    if (run > bestLen) { bestLen = run; bestStart = start }
                } else run = 0
            }
            if (bestLen >= 2) {
                headX = bestStart + bestLen / 2f
                headY = y + headBias * 6f
                break
            }
        }

        if (headX < 0f) {                  // fallback: bbox estimate
            headX = (minX + maxX + 1) * cell / 2f
            headY = minY * cell + ((maxY - minY + 1) * cell * headBias)
        }
        return headX to headY
    }

    /** Adopt the most saturated pixel near the crosshair as the outline colour. */
    private fun pick() {
        val px = pixels ?: return
        val box = 24
        val cx = w / 2; val cy = h / 2
        var bestSat = -1
        var best = 0
        for (y in (cy - box).coerceAtLeast(0) until (cy + box).coerceAtMost(h)) {
            for (x in (cx - box).coerceAtLeast(0) until (cx + box).coerceAtMost(w)) {
                val c = px[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val sat = maxOf(r, g, b) - minOf(r, g, b)
                if (sat > bestSat) { bestSat = sat; best = c }
            }
        }
        if (bestSat > 40) {
            target = best or 0xFF000000.toInt()
            tol = 45
        }
    }

    private fun notif(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("cyberaim", "CYBER AIM", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, "cyberaim")
            .setContentTitle("CYBER AIM")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        running = false
        super.onDestroy()
    }
}
