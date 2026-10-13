package com.example.ffcapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.ffcapture.net.Headshot
import com.example.ffcapture.net.PacketVpnService
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object { const val EXTRA_VPN = "arm_vpn" }

    private lateinit var activate: Button
    private lateinit var packets: Button
    private lateinit var memory: Button
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
                startFg(AimService::class.java)
                startService(Intent(this, OverlayService::class.java))
            } else toast("Screen capture denied")
        }

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) startFg(PacketVpnService::class.java)
            else toast("VPN consent denied")
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        MemoryEngine.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 140, 60, 60)
        }
        val title = TextView(this).apply { text = "CYBER AIM"; textSize = 26f; setPadding(0, 0, 0, 40) }
        activate = Button(this).apply { text = "ACTIVATE"; textSize = 20f }
        packets  = Button(this).apply { text = "PACKETS";  textSize = 18f }
        memory   = Button(this).apply { text = "MEMORY";   textSize = 18f }
        status   = TextView(this).apply { textSize = 13f; setPadding(0, 40, 0, 0) }

        root.addView(title)
        root.addView(activate)
        root.addView(packets)
        root.addView(memory)
        root.addView(status)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        activate.setOnClickListener { if (AimService.running) stopAll() else begin() }
        packets.setOnClickListener { if (PacketVpnService.running) stopVpn() else armVpn() }
        memory.setOnClickListener { startActivity(Intent(this, MemoryActivity::class.java)) }

        refreshStatus()
        if (intent?.getBooleanExtra(EXTRA_VPN, false) == true) armVpn()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (i.getBooleanExtra(EXTRA_VPN, false)) armVpn()
    }

    private fun startFg(cls: Class<*>) {
        val i = Intent(this, cls)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun refreshStatus() {
        status.text = buildString {
            append("aim: ${if (AimService.running) "RUNNING" else "IDLE"}\n")
            append("packets: ${if (PacketVpnService.running) "RUNNING" else "IDLE"}\n")
            append("headshot: ${if (Headshot.enabled) "ON" else "OFF"}\n")
            append("shizuku: ${if (!ShizukuHelper.alive()) "not running" else if (ShizukuHelper.granted()) "granted" else "not granted"}\n\n")
            append("1. Install + start Shizuku\n")
            append("2. ACTIVATE for the aimbot\n")
            append("3. PACKETS for headshot mode\n")
            append("4. MEMORY to edit live values")
        }
        activate.text = if (AimService.running) "DEACTIVATE" else "ACTIVATE"
        packets.text = if (PacketVpnService.running) "PACKETS: ON" else "PACKETS: OFF"
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

    private fun armVpn() {
        val i = VpnService.prepare(this)
        if (i != null) vpnLauncher.launch(i) else startFg(PacketVpnService::class.java)
    }

    private fun stopVpn() {
        stopService(Intent(this, PacketVpnService::class.java))
        status.postDelayed({ refreshStatus() }, 400)
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
        stopService(Intent(this, PacketVpnService::class.java))
        AimService.running = false
        activate.postDelayed({ refreshStatus() }, 300)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
