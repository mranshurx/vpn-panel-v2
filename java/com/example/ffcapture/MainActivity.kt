package com.example.ffcapture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

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

        val share = Button(this).apply { text = "SHARE LAST .PCAP" }

        root.addView(toggle)
        root.addView(share)
        root.addView(status)
        setContentView(root)

        toggle.setOnClickListener {
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, 1)
            else startService(Intent(this, CaptureVpnService::class.java)
                .setAction(CaptureVpnService.ACTION_START))
        }

        share.setOnClickListener { shareLast() }

        ui.post(object : Runnable {
            override fun run() {
                status.text = "packets: ${CaptureState.count}\nfile: ${CaptureState.currentFile ?: "-"}"
                ui.postDelayed(this, 500)
            }
        })
    }

    private fun shareLast() {
        val path = CaptureState.currentFile ?: return
        val f = File(path)
        if (!f.exists()) return
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share capture"))
    }
}
