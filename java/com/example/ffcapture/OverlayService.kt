package com.example.ffcapture

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Floating CYBER ENGINE menu drawn over the game. */
class OverlayService : Service() {

    companion object { @Volatile var running = false }

    private var wm: WindowManager? = null
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private lateinit var header: TextView
    private lateinit var content: LinearLayout

    private var section = 0
    private var overlayError = "-"

    private var trainHeadBtn: Button? = null
    private var trainBodyBtn: Button? = null
    private var armBtn: Button? = null
    private var infoText: TextView? = null

    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (root == null) show()
        return START_STICKY
    }

    private fun show() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = build()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 260 }

        val err = runCatching { wm?.addView(v, p) }.exceptionOrNull()
        overlayError = err?.message ?: "-"
        root = v; lp = p
        running = true
        ui.post(refresh)
    }

    private fun build(): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(0xEE0A1120.toInt())
                setStroke(3, 0xFF22D3EE.toInt())
            }
            setPadding(18, 12, 18, 12)
        }

        header = TextView(this).apply {
            text = "CYBER ENGINE"
            setTextColor(0xFF22D3EE.toInt())
            textSize = 11f
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 10)
        }
        r.addView(header)
        r.addView(option("AIM & LOCATION") { toggle(1) })
        r.addView(option("SETUP") { toggle(2) })
        r.addView(option("INFO") { toggle(3) })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }
        r.addView(content)
        header.setOnTouchListener(drag())
        return r
    }

    private fun option(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(0xFFE2F6FF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18f; setColor(0x3311BBCB); setStroke(2, 0xFF22D3EE.toInt())
            }
            minWidth = 0; minimumWidth = 0
            setPadding(18, 6, 18, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
            setOnClickListener { onClick() }
        }

    private fun small(label: String, onClick: () -> Unit): Button =
        option(label, onClick).apply {
            textSize = 11f
            background = GradientDrawable().apply {
                cornerRadius = 14f; setColor(0x2200E5FF); setStroke(1, 0x8822D3EE.toInt())
            }
        }

    private fun drag(): View.OnTouchListener {
        var dx = 0; var dy = 0
        return View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX.toInt() - (lp?.x ?: 0); dy = e.rawY.toInt() - (lp?.y ?: 0); true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp?.x = e.rawX.toInt() - dx; lp?.y = e.rawY.toInt() - dy
                    runCatching { root?.let { lp?.let { l -> wm?.updateViewLayout(it, l) } } }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggle(s: Int) { section = if (section == s) 0 else s; rebuild() }

    private fun rebuild() {
        trainHeadBtn = null; trainBodyBtn = null; armBtn = null; infoText = null
        content.removeAllViews()
        if (section == 0) { content.visibility = View.GONE; return }
        content.visibility = View.VISIBLE

        when (section) {
            1 -> {
                armBtn = small("") { HeadshotEngine.armed = !HeadshotEngine.armed; refreshTexts() }
                content.addView(armBtn)
                content.addView(small("FLIP B/H") { HeadshotEngine.flip() })
                content.addView(small("OFFSET -1") { HeadshotEngine.nudge(-1) })
                content.addView(small("OFFSET +1") { HeadshotEngine.nudge(1) })
                content.addView(small("NEXT CANDIDATE") { HeadshotEngine.nextCandidate() })
                content.addView(small("RESET TRAINING") { HeadshotEngine.reset(this) })
            }
            2 -> {
                trainHeadBtn = small("") { HeadshotEngine.setTrain(true, !HeadshotEngine.trainHead); refreshTexts() }
                trainBodyBtn = small("") { HeadshotEngine.setTrain(false, !HeadshotEngine.trainBody); refreshTexts() }
                content.addView(trainHeadBtn)
                content.addView(trainBodyBtn)
                content.addView(small("SOLVE NOW") { HeadshotEngine.solve() })
            }
            3 -> {
                infoText = TextView(this).apply {
                    setTextColor(0xFFB9E8FF.toInt()); textSize = 10f; setPadding(6, 4, 6, 4)
                }
                content.addView(infoText)
            }
        }
        refreshTexts()
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    private fun refreshTexts() {
        trainHeadBtn?.text = "TRAIN HEAD: ${onOff(HeadshotEngine.trainHead)}"
        trainBodyBtn?.text = "TRAIN BODY: ${onOff(HeadshotEngine.trainBody)}"
        armBtn?.text = "ARM: ${onOff(HeadshotEngine.armed)}"
        infoText?.text = buildString {
            append("phase ${HeadshotEngine.phase}  armed ${HeadshotEngine.armed}\n")
            append("pkt seen ${HeadshotEngine.seenOut}  shot ${HeadshotEngine.seenShot}\n")
            append("patched ${HeadshotEngine.patched}\n")
            append("off ${HeadshotEngine.offset} b=0x%02x h=0x%02x\n".format(HeadshotEngine.bodyVal, HeadshotEngine.headVal))
            append("samples b/h ${HeadshotEngine.bodyCount}/${HeadshotEngine.headCount}\n")
            append("err ${HeadshotEngine.lastError}\n")
            append("ovl ${if (overlayError == "-") "ok" else overlayError}\n")
            append("---\n")
            append(HeadshotEngine.logs().takeLast(6).joinToString("\n"))
        }
        header.text = when {
            HeadshotEngine.trainHead || HeadshotEngine.trainBody -> "CYBER ENGINE · TRAIN"
            HeadshotEngine.armed -> "CYBER ENGINE · ARMED"
            else -> "CYBER ENGINE"
        }
    }

    private val refresh = object : Runnable {
        override fun run() { refreshTexts(); ui.postDelayed(this, 500) }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { root?.let { wm?.removeView(it) } }
        root = null; running = false
        super.onDestroy()
    }
}
