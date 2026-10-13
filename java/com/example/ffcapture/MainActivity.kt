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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply { textSize = 15f }
        toggle = Button(this).apply { text = "START CAPTURE" }
        val scopeBtn = Button(this).apply { text = "SCOPE: FREE FIRE / ALL APPS" }
        val share = Button(this).apply { text = "SHARE LAST .PCAP" }
        val shareRaw = Button(this).apply { text = "SHARE RAW PAYLOADS (.FFP)" }

        root.addView(toggle)
        root.addView(scopeBtn)
        root.addView(share)
        root.addView(shareRaw)
        root.addView(status)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        toggle.setOnClickListener {
            val prep = VpnService.prepare(this)
            if (prep != null) {
                startActivityForResult(prep, 1)
            } else {
                startService(Intent(this, CaptureVpnService::class.java)
                    .setAction(CaptureVpnService.ACTION_START))
            }
        }

        scopeBtn.setOnClickListener {
            CaptureVpnService.onlyFreeFire = !CaptureVpnService.onlyFreeFire
        }

        share.setOnClickListener { share(CaptureState.currentFile, "Share capture") }
        shareRaw.setOnClickListener { share(CaptureState.currentDumpFile, "Share raw payloads") }

        ui.post(object : Runnable {
            override fun run() {
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
