package com.example.ffcapture

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var activate: Button
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    private var pendingOverlay = false

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startAll()
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        HeadshotEngine.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 160, 60, 60)
        }
        val title = TextView(this).apply {
            text = "CYBER ENGINE"
            textSize = 26f
            setPadding(0, 0, 0, 40)
        }
        activate = Button(this).apply { text = "ACTIVATE"; textSize = 20f }
        status = TextView(this).apply { textSize = 13f; setPadding(0, 40, 0, 0) }

        root.addView(title)
        root.addView(activate)
        root.addView(status)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        activate.setOnClickListener {
            if (CaptureState.running) deactivate() else requestActivate()
        }

        ui.post(object : Runnable {
            override fun run() {
                activate.text = if (CaptureState.running) "DEACTIVATE" else "ACTIVATE"
                status.text = buildString {
                    append("state: ${if (CaptureState.running) "ACTIVE" else "IDLE"}\n")
                    append("phase: ${HeadshotEngine.phase}\n")
                    append("armed: ${HeadshotEngine.armed}\n")
                    append("offset: ${HeadshotEngine.offset}\n")
                    append("patched: ${HeadshotEngine.patched}\n\n")
                    append(HeadshotEngine.status)
                }
                ui.postDelayed(this, 500)
            }
        })
    }

    private fun requestActivate() {
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlay = true
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        continueActivate()
    }

    private fun continueActivate() {
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPermission.launch(prep) else startAll()
    }

    override fun onResume() {
        super.onResume()
        if (pendingOverlay && Settings.canDrawOverlays(this)) {
            pendingOverlay = false
            continueActivate()
        }
    }

    private fun startAll() {
        val i = Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        startService(Intent(this, OverlayService::class.java))
    }

    private fun deactivate() {
        val i = Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP)
        startService(i)
        stopService(Intent(this, OverlayService::class.java))
    }
}
