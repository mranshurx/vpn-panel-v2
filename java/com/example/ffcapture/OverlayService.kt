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
import com.example.ffcapture.net.Headshot
import com.example.ffcapture.net.Learner
import com.example.ffcapture.net.PacketVpnService

class OverlayService : Service() {

    companion object { @Volatile var running = false }

    private var wm: WindowManager? = null
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private lateinit var header: TextView
    private lateinit var content: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var aimBtn: Button
    private lateinit var pktBtn: Button
    private lateinit var hsBtn: Button

    private var open = false
    private var overlayError = "-"

    private val ui = Handler(Looper.getMainLooper())

    private val GAIN     = floatArrayOf(0.2f, 0.3f, 0.4f, 0.6f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f)
    private val STRENGTH = floatArrayOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)
    private val RADIUS   = floatArrayOf(0.05f, 0.08f, 0.12f, 0.18f, 0.25f, 0.35f, 0.45f, 0.6f)
    private val DEADZONE = floatArrayOf(0f, 1f, 2f, 3f, 4f, 6f, 8f, 12f, 20f, 30f, 40f)
    private val HEADBIAS = floatArrayOf(0f, 0.05f, 0.10f, 0.15f, 0.20f, 0.30f, 0.40f, 0.50f)

    private fun next(cur: Float, ladder: FloatArray): Float {
        for (v in ladder) if (v > cur + 0.001f) return v
        return ladder[0]
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
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
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 300 }

        overlayError = runCatching { wm?.addView(v, p) }.exceptionOrNull()?.message ?: "-"
        root = v
        lp = p
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
            text = "CYBER AIM"
            setTextColor(0xFF22D3EE.toInt())
            textSize = 11f
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 10)
        }
        r.addView(header)

        aimBtn = option("AIMBOT: ON") {
            AimService.enabled = !AimService.enabled
            rebuild()
        }
        r.addView(aimBtn)

        pktBtn = option("PACKETS: OFF") { togglePackets() }
        r.addView(pktBtn)

        hsBtn = option("HEADSHOT: OFF") {
            Headshot.enabled = !Headshot.enabled
            hsBtn.text = "HEADSHOT: ${if (Headshot.enabled) "ON" else "OFF"}"
        }
        r.addView(hsBtn)

        r.addView(option("TUNE") { open = !open; rebuild() })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }
        r.addView(content)

        infoText = TextView(this).apply {
            setTextColor(0xFFB9E8FF.toInt())
            textSize = 10f
            setPadding(6, 6, 6, 0)
        }
        r.addView(infoText)

        header.setOnTouchListener(drag())
        return r
    }

    private fun togglePackets() {
        if (PacketVpnService.running) {
            stopService(Intent(this, PacketVpnService::class.java))
            ui.postDelayed({ pktBtn.text = "PACKETS: OFF" }, 500)
        } else {
            // consent needs a real Activity result, so bounce through MainActivity
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_VPN, true))
            ui.postDelayed({ pktBtn.text = if (PacketVpnService.running) "PACKETS: ON" else "PACKETS: OFF" }, 900)
        }
    }

    private fun option(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(0xFFE2F6FF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(0x3311BBCB)
                setStroke(2, 0xFF22D3EE.toInt())
            }
            minWidth = 0
            minimumWidth = 0
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
                cornerRadius = 14f
                setColor(0x2200E5FF)
                setStroke(1, 0x8822D3EE.toInt())
            }
        }

    private fun drag(): View.OnTouchListener {
        var dx = 0
        var dy = 0
        return View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX.toInt() - (lp?.x ?: 0)
                    dy = e.rawY.toInt() - (lp?.y ?: 0)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp?.x = e.rawX.toInt() - dx
                    lp?.y = e.rawY.toInt() - dy
                    runCatching { root?.let { lp?.let { l -> wm?.updateViewLayout(it, l) } } }
                    true
                }
                else -> false
            }
        }
    }

    private fun rebuild() {
        content.removeAllViews()
        aimBtn.text = "AIMBOT: ${if (AimService.enabled) "ON" else "OFF"}"

        if (!open) {
            content.visibility = View.GONE
            return
        }
        content.visibility = View.VISIBLE

        content.addView(small("MEMORY EDITOR") {
            startActivity(Intent(this, MemoryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        content.addView(small("SNAP NOW") { AimService.snapNow() })
        content.addView(small("PICK COLOUR  #%06X".format(AimService.target and 0xFFFFFF)) {
            AimService.pickColour(); rebuild()
        })
        content.addView(small("GAIN  %.2f".format(AimService.gain)) {
            AimService.gain = next(AimService.gain, GAIN); rebuild()
        })
        content.addView(small("STRENGTH  %.2f".format(AimService.strength)) {
            AimService.strength = next(AimService.strength, STRENGTH); rebuild()
        })
        content.addView(small("RADIUS  %.2f".format(AimService.radiusFrac)) {
            AimService.radiusFrac = next(AimService.radiusFrac, RADIUS); rebuild()
        })
        content.addView(small("DEADZONE  %.0f".format(AimService.deadzone)) {
            AimService.deadzone = next(AimService.deadzone, DEADZONE); rebuild()
        })
        content.addView(small("HEAD BIAS  %.2f".format(AimService.headBias)) {
            AimService.headBias = next(AimService.headBias, HEADBIAS); rebuild()
        })
        content.addView(small("CALIBRATE PACKETS") {
            Learner.clear(); Learner.armed = true
        })
        content.addView(small("RESET TUNE") {
            AimService.gain = 1f
            AimService.strength = 0.8f
            AimService.radiusFrac = 0.28f
            AimService.deadzone = 4f
            AimService.headBias = 0.10f
            rebuild()
        })
    }

    private val refresh = object : Runnable {
        override fun run() {
            header.text = "CYBER AIM · ${if (AimService.enabled) "ON" else "OFF"}"
            infoText.text = buildString {
                append("fps ${AimService.frames}\n")
                append("target ${AimService.seen}  aims ${AimService.aims}\n")
                append("d(${AimService.lastDx},${AimService.lastDy})  gain %.2f\n".format(AimService.gain))
                append("tol ${AimService.tol}\n")
                append("pkt ${if (PacketVpnService.running) "up" else "down"}  hs ${Headshot.hits.get()}\n")
                append("samples ${Learner.snapshot().size}\n")
                append("shizuku ${if (ShizukuHelper.ready) "ok" else "no"}\n")
                append("ovl ${if (overlayError == "-") "ok" else overlayError}")
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { root?.let { wm?.removeView(it) } }
        root = null
        running = false
        super.onDestroy()
    }
}
