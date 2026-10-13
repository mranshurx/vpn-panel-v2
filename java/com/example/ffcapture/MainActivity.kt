package com.example.ffcapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var activate: Button
    private lateinit var status: TextView
    private var pendingOverlay = false

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant == PackageManager.PERMISSION_GRANTED) {
            ShizukuHelper.ready = InputInjector.init()
            toast(if (ShizukuHelper.ready) "Shizuku ready" else "Shizuku bind failed")
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                AimService.pendingCode = r.resultCode
                AimService.pendingData = r.data
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(Intent(this, AimService::class.java))
                else startService(Intent(this, AimService::class.java))
                startService(Intent(this, OverlayService::class.java))
            } else toast("Screen capture denied")
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Shizuku.addRequestPermissionResultListener(shizukuListener)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(60, 180, 60, 60)
        }
        val title = TextView(this).apply { text = "CYBER AIM"; textSize = 26f; setPadding(0, 0, 0, 40) }
        activate = Button(this).apply { text = "ACTIVATE"; textSize = 20f }
        status = TextView(this).apply { textSize = 13f; setPadding(0, 40, 0, 0) }

        root.addView(title); root.addView(activate); root.addView(status)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        activate.setOnClickListener { if (AimService.running) stopAll() else begin() }
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = buildString {
            append("state: ${if (AimService.running) "RUNNING" else "IDLE"}\n")
            append("shizuku: ${if (!ShizukuHelper.alive()) "not running" else if (ShizukuHelper.granted()) "granted" else "not granted"}\n\n")
            append("1. Install + start Shizuku\n2. ACTIVATE\n3. Enable enemy outline in-game (red)")
        }
        activate.text = if (AimService.running) "DEACTIVATE" else "ACTIVATE"
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!ShizukuHelper.alive()) { toast("Start Shizuku first"); return }
        if (!ShizukuHelper.granted()) { ShizukuHelper.request(); return }
        if (!ShizukuHelper.ready && !InputInjector.init()) { toast("Shizuku bind failed"); return }
        ShizukuHelper.ready = true
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (pendingOverlay && Settings.canDrawOverlays(this)) { pendingOverlay = false; begin() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }

    private fun stopAll() {
        stopService(Intent(this, AimService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        AimService.running = false
        activate.postDelayed({ refreshStatus() }, 300)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
