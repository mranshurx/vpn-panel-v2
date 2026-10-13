package com.example.ffcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class CaptureVpnService : VpnService() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"

        private const val CH = "ffcap"
        private const val NOTIF_ID = 1

        private const val MTU = 1500
        private const val MSS = 1400            // keeps IP total <= 1440, under MTU

        private const val UDP_IDLE_MS = 60_000L
        private const val TCP_IDLE_MS = 180_000L

        private const val SYN = 0x02
        private const val ACK = 0x10
        private const val FIN = 0x01
        private const val RST = 0x04

        val FF_PACKAGES = listOf("com.dts.freefireth", "com.dts.freefiremax")

        /** When true, ONLY Free Fire is routed through the VPN. */
        @Volatile var onlyFreeFire = true
    }

    private var tun: ParcelFileDescriptor? = null
    private var tin: FileInputStream? = null
    private var tout: FileOutputStream? = null
    private var pcap: PcapWriter? = null
    private var dump: PayloadDumper? = null

    @Volatile private var running = false
    private val outLock = Any()

    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()

    /** TCP inbound rewriting hook. */
    private val transformer: PacketTransformer = NoOpTransformer

    /** UDP payload rewriting hook - this is what edits packets in flight. */
    private val patcher: UdpPayloadTransformer = FfPatcher()

    override fun onStartCommand(intent: Intent?, flags: Int,
