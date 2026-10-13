package com.example.ffcapture

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var toggle: Button
    private lateinit var scopeBtn: Button
    private lateinit var learnBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var solveBtn: Button
    private lateinit var flipBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) sendAction(CaptureVpnService.ACTION_START, true)
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        HeadshotEngine.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        toggle = Button(this).apply { text = "START CAPTURE" }
        scopeBtn = Button(this).apply { text = scopeLabel() }
        learnBtn = Button(this).apply { text = "LEARN: FIRE AT BODY" }
        nextBtn = Button(this).apply { text = "STEP 2: AIM HEAD" }
        solveBtn = Button(this).apply { text = "SOLVE + ARM" }
        flipBtn = Button(this).apply { text = "FLIP BODY/HEAD" }
        val share = Button(this).apply { text = "SHARE LAST .PCAP" }
        val shareRaw = Button(this).apply { text = "SHARE RAW (.FFP)" }
        status = TextView(this).apply { textSize = 14f; setPadding(0, 32, 0, 0) }
        logView = TextView(this).apply { textSize = 11f; setPadding(0, 24, 0, 0) }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(toggle)
        root.addView(scopeBtn)
        root.addView(learnBtn)
        root.addView(nextBtn)
        root.addView(solveBtn)
        root.addView(flipBtn)
        root.addView(share)
        root.addView(shareRaw)
        root.addView(status)
        root.addView(scroll)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        toggle.setOnClickListener {
            if (CaptureState.running) sendAction(CaptureVpnService.ACTION_STOP, false)
            else ensureCapture()
        }
        scopeBtn.setOnClickListener {
            CaptureVpnService.onlyFreeFire = !CaptureVpnService.onlyFreeFire
            scopeBtn.text = scopeLabel()
        }
        learnBtn.setOnClickListener { ensureCapture(); HeadshotEngine.startLearn() }
        nextBtn.setOnClickListener { HeadshotEngine.beginHeadPhase() }
        solveBtn.setOnClickListener {
            HeadshotEngine.solve()
            if (HeadshotEngine.armed) HeadshotEngine.save(this)
        }
        flipBtn.setOnClickListener { HeadshotEngine.flip() }
        share.setOnClickListener { share(CaptureState.currentFile, "Share capture") }
        shareRaw.setOnClickListener { share(CaptureState.currentDumpFile, "Share raw payloads") }

        ui.post(object : Runnable {
            override fun run() {
                toggle.text = if (CaptureState.running) "STOP CAPTURE" else "START CAPTURE"
                val e = HeadshotEngine
                status.text = buildString {
                    append("phase: ${e.phase}    armed: ${e.armed}\n")
                    append("body shots: ${e.bodyCount}    head shots: ${e.headCount}\n")
                    append("offset: ${e.offset}    body=0x%02x head=0x%02x\n".format(e.bodyVal, e.headVal))
                    append("type: ${e.shotType}    patched: ${e.patched}\n")
                    append("scope: ${CaptureState.scope}    packets: ${CaptureState.count}\n\n")
                    append(e.status)
                }
                logView.text = e.logs().joinToString("\n")
                ui.postDelayed(this, 500)
            }
        })
    }

    private fun scopeLabel() =
        if (CaptureVpnService.onlyFreeFire) "SCOPE: FREE FIRE ONLY" else "SCOPE: ALL APPS"

    private fun ensureCapture() {
        if (CaptureState.running) return
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPermission.launch(prep)
        else sendAction(CaptureVpnService.ACTION_START, true)
    }

    private fun sendAction(action: String, foreground: Boolean) {
        val i = Intent(this, CaptureVpnService::class.java).setAction(action)
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun share(path: String?, label: String) {
        if (path == null) return
        val f = File(path)
        if (!f.exists()) return
        val uri: Uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, label))
    }
}
