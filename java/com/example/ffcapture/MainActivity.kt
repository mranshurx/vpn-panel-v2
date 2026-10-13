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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var scopeBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) sendAction(CaptureVpnService.ACTION_START, true)
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        toggle = Button(this).apply { text = "START CAPTURE" }
        scopeBtn = Button(this).apply { text = scopeLabel() }
        val share = Button(this).apply { text = "SHARE LAST .PCAP" }
        val shareRaw = Button(this).apply { text = "SHARE RAW PAYLOADS (.FFP)" }
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }

        root.addView(toggle)
        root.addView(scopeBtn)
        root.addView(share)
        root.addView(shareRaw)
        root.addView(status)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        toggle.setOnClickListener {
            if (CaptureState.running) {
                sendAction(CaptureVpnService.ACTION_STOP, false)
            } else {
                val prep = VpnService.prepare(this)
                if (prep != null) {
                    vpnPermission.launch(prep)   // start happens in the callback
                } else {
                    sendAction(CaptureVpnService.ACTION_START, true)
                }
            }
        }

        scopeBtn.setOnClickListener {
            CaptureVpnService.onlyFreeFire = !CaptureVpnService.onlyFreeFire
            scopeBtn.text = scopeLabel()
        }

        share.setOnClickListener { share(CaptureState.currentFile, "Share capture") }
        shareRaw.setOnClickListener { share(CaptureState.currentDumpFile, "Share raw payloads") }

        ui.post(object : Runnable {
            override fun run() {
                toggle.text = if (CaptureState.running) "STOP CAPTURE" else "START CAPTURE"
                status.text = buildString {
                    append("scope: ${CaptureState.scope}\n")
                    append("packets: ${CaptureState.count}\n")
                    append("pcap: ${CaptureState.currentFile ?: "-"}\n")
                    append("raw: ${CaptureState.currentDumpFile ?: "-"}")
                }
                ui.postDelayed(this, 500)
            }
        })
    }

    private fun scopeLabel() =
        if (CaptureVpnService.onlyFreeFire) "SCOPE: FREE FIRE ONLY" else "SCOPE: ALL APPS"

    private fun sendAction(action: String, foreground: Boolean) {
        val i = Intent(this, CaptureVpnService::class.java).setAction(action)
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun share(path: String?, label: String) {
        if (path == null) return
        val f = File(path)
        if (!f.exists()) return

        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.files",
            f
        )

        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            label
        ))
    }
}
