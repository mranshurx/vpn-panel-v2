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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        if (running) return

        startForeground(NOTIF_ID, buildNotification("Capturing Free Fire packets"))

        val b = Builder()
            .setSession("FF Capture")
            .setMtu(1500)
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        // Scope the VPN to Free Fire. Empty list == capture everything.
        val installed = if (onlyFreeFire) FF_PACKAGES.filter { isInstalled(it) } else emptyList()
        if (installed.isNotEmpty()) {
            installed.forEach { b.addAllowedApplication(it) }
            CaptureState.scope = "Free Fire only"
        } else {
            CaptureState.scope = "ALL apps"
        }

        val pfd = b.establish()
        if (pfd == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        tun = pfd
        tin = FileInputStream(pfd.fileDescriptor)
        tout = FileOutputStream(pfd.fileDescriptor)

        val dir = File(getExternalFilesDir(null), "captures")
        dir.mkdirs()
        val base = "ff_${System.currentTimeMillis()}"

        val f = File(dir, "$base.pcap")
        pcap = PcapWriter(FileOutputStream(f))
        CaptureState.currentFile = f.absolutePath

        val d = File(dir, "$base.ffp")
        dump = PayloadDumper(FileOutputStream(d))
        CaptureState.currentDumpFile = d.absolutePath

        CaptureState.count = 0

        running = true
        thread(name = "tun-read") { readLoop() }
    }

    private fun readLoop() {
        val buf = ByteArray(65535)
        val inS = tin ?: return
        while (running) {
            val n = try { inS.read(buf) } catch (e: Exception) { break }
            if (n <= 0) continue
            pcap?.writePacket(buf, n, System.currentTimeMillis())
            CaptureState.count++
            try { dumpAppPayload(buf, n, Direction.OUTBOUND) } catch (e: Exception) { }
            try { route(buf, n) } catch (e: Exception) { }
        }
    }

    /** Strips IP/UDP/TCP headers and dumps only the application bytes. */
    private fun dumpAppPayload(p: ByteArray, n: Int, dir: Direction) {
        if (!PacketUtils.isIpv4(p)) return
        val hl = PacketUtils.ipHeaderLen(p)
        if (hl < 20 || hl > n) return
        val end = minOf(PacketUtils.totalLength(p), n)
        when (PacketUtils.protocol(p)) {
            PacketUtils.PROTO_UDP -> {
                val off = hl + 8
                if (end <= off) return
                dump?.write(dir, PacketUtils.PROTO_UDP,
                    PacketUtils.readShort(p, hl), PacketUtils.readShort(p, hl + 2),
                    p, off, end - off)
            }
            PacketUtils.PROTO_TCP -> {
                if (hl + 20 > n) return
                val dataOff = ((p[hl + 12].toInt() and 0xF0) shr 4) * 4
                val off = hl + dataOff
                if (end <= off) return
                dump?.write(dir, PacketUtils.PROTO_TCP,
                    PacketUtils.readShort(p, hl), PacketUtils.readShort(p, hl + 2),
                    p, off, end - off)
            }
        }
    }

    private fun route(p: ByteArray, n: Int) {
        if (!PacketUtils.isIpv4(p)) return
        when (PacketUtils.protocol(p)) {
            PacketUtils.PROTO_UDP -> handleUdp(p, n)
            PacketUtils.PROTO_TCP -> handleTcp(p, n)
        }
    }

    // ---------------- UDP ----------------

    private fun handleUdp(p: ByteArray, n: Int) {
        val hl = PacketUtils.ipHeaderLen(p)
        val srcIp = PacketUtils.readInt(p, 12)
        val dstIp = PacketUtils.readInt(p, 16)
        val sp = PacketUtils.readShort(p, hl)
        val dp = PacketUtils.readShort(p, hl + 2)
        val off = hl + 8
        val len = PacketUtils.totalLength(p) - hl - 8
        if (len <= 0) return

        val key = "$srcIp:$sp"
        var flow = udpFlows[key]
        if (flow == null) {
            val sock = DatagramSocket()
            protect(sock)
            sock.connect(InetAddress.getByAddress(PacketUtils.toBytes(dstIp)), dp)
            val nf = UdpFlow(sock, srcIp, sp, dstIp, dp)
            udpFlows[key] = nf
            thread { udpReader(nf) }
            flow = nf
        }
        val f = flow!!
        val raw = p.copyOfRange(off, off + len)
        // skip DNS and low ports; game traffic lives up in the 10k range
        val out = if (dp >= 1000) patcher.mutate(raw, raw.size, Direction.OUTBOUND) else raw
        f.socket.send(DatagramPacket(out, out.size))
    }

    private fun udpReader(flow: UdpFlow) {
        val rbuf = ByteArray(65535)
        while (running && !flow.socket.isClosed) {
            val dg = DatagramPacket(rbuf, rbuf.size)
            try { flow.socket.receive(dg) } catch (e: Exception) { break }
            val raw = rbuf.copyOf(dg.length)
            dump?.write(Direction.INBOUND, PacketUtils.PROTO_UDP,
                flow.dstPort, flow.srcPort, raw, 0, raw.size)
            val out = if (flow.dstPort >= 1000)
                patcher.mutate(raw, raw.size, Direction.INBOUND) else raw
            var pkt = PacketUtils.buildUdp(
                flow.dstIp, flow.srcIp, flow.dstPort, flow.srcPort,
                out, 0, out.size)
            pkt = transformer.mutate(pkt, pkt.size, Direction.INBOUND)
            pcap?.writePacket(pkt, pkt.size, System.currentTimeMillis())
            writeToTun(pkt)
        }
    }

    // ---------------- TCP ----------------

    private fun handleTcp(p: ByteArray, n: Int) {
        val hl = PacketUtils.ipHeaderLen(p)
        val srcIp = PacketUtils.readInt(p, 12)
        val dstIp = PacketUtils.readInt(p, 16)
        val sp = PacketUtils.readShort(p, hl)
        val dp = PacketUtils.readShort(p, hl + 2)
        val seq = PacketUtils.readInt(p, hl + 4)
        val dataOff = ((p[hl + 12].toInt() and 0xF0) shr 4) * 4
        val flags = p[hl + 13].toInt() and 0xFF
        val poff = hl + dataOff
        val plen = PacketUtils.totalLength(p) - hl - dataOff

        val SYN = 0x02
        val ACK = 0x10
        val FIN = 0x01
        val RST = 0x04

        val key = "$srcIp:$sp-$dstIp:$dp"

        if ((flags and SYN) != 0 && (flags and ACK) == 0) {
            val sock = Socket()
            protect(sock)
            val flow = TcpFlow(sock, srcIp, sp, dstIp, dp)
            flow.theirSeq = seq + 1
            tcpFlows[key] = flow
            thread {
                try {
                    sock.connect(InetSocketAddress(
                        InetAddress.getByAddress(PacketUtils.toBytes(dstIp)), dp), 8000)
                    flow.established = true
                    val synack = PacketUtils.buildTcp(dstIp, srcIp, dp, sp,
                        flow.ourSeq, flow.theirSeq, SYN or ACK, null, 0, 0)
                    flow.ourSeq++
                    writeToTun(synack)
                    tcpServerReader(flow, key)
                } catch (e: Exception) {
                    val rst = PacketUtils.buildTcp(dstIp, srcIp, dp, sp,
                        flow.ourSeq, flow.theirSeq, RST or ACK, null, 0, 0)
                    writeToTun(rst)
                    tcpFlows.remove(key)
                }
            }
            return
        }

        val flow = tcpFlows[key] ?: return

        if ((flags and RST) != 0) {
            try { flow.socket.close() } catch (e: Exception) { }
            tcpFlows.remove(key)
            return
        }

        if (plen > 0) {
            if (seq != flow.theirSeq) {
                sendAck(flow, ACK, dstIp, srcIp, dp, sp)
                return
            }
            try { flow.socket.getOutputStream().write(p, poff, plen) } catch (e: Exception) { }
            flow.theirSeq += plen
            sendAck(flow, ACK, dstIp, srcIp, dp, sp)
        }

        if ((flags and FIN) != 0) {
            flow.theirSeq++
            sendAck(flow, ACK or FIN, dstIp, srcIp, dp, sp)
            flow.ourSeq++
            try { flow.socket.close() } catch (e: Exception) { }
            tcpFlows.remove(key)
        }
    }

    private fun sendAck(flow: TcpFlow, flags: Int, srcIp: Int, dstIp: Int, sp: Int, dp: Int) {
        writeToTun(PacketUtils.buildTcp(srcIp, dstIp, sp, dp,
            flow.ourSeq, flow.theirSeq, flags, null, 0, 0))
    }

    private fun tcpServerReader(flow: TcpFlow, key: String) {
        val buf = ByteArray(16 * 1024)
        try {
            val ins = flow.socket.getInputStream()
            while (running && !flow.socket.isClosed) {
                val r = ins.read(buf)
                if (r < 0) break
                if (r == 0) continue
                dump?.write(Direction.INBOUND, PacketUtils.PROTO_TCP,
                    flow.dstPort, flow.srcPort, buf, 0, r)
                var pkt = PacketUtils.buildTcp(flow.dstIp, flow.srcIp, flow.dstPort, flow.srcPort,
                    flow.ourSeq, flow.theirSeq, 0x18, buf, 0, r)
                pkt = transformer.mutate(pkt, pkt.size, Direction.INBOUND)
                flow.ourSeq += r
                pcap?.writePacket(pkt, pkt.size, System.currentTimeMillis())
                writeToTun(pkt)
            }
        } catch (e: Exception) { }
        tcpFlows.remove(key)
    }

    // ---------------- plumbing ----------------

    private fun writeToTun(pkt: ByteArray) {
        val o = tout ?: return
        synchronized(outLock) { try { o.write(pkt) } catch (e: Exception) { } }
    }

    private fun isInstalled(pkg: String) =
        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "FF Capture", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("FF Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun shutdown() {
        running = false
        udpFlows.values.forEach { try { it.socket.close() } catch (e: Exception) { } }
        tcpFlows.values.forEach { try { it.socket.close() } catch (e: Exception) { } }
        udpFlows.clear()
        tcpFlows.clear()
        try { pcap?.close() } catch (e: Exception) { }
        try { dump?.close() } catch (e: Exception) { }
        try { tun?.close() } catch (e: Exception) { }
        tun = null
        pcap = null
        dump = null
        tin = null
        tout = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) shutdown()
    }

    class UdpFlow(val socket: DatagramSocket, val srcIp: Int, val srcPort: Int,
                  val dstIp: Int, val dstPort: Int)

    class TcpFlow(val socket: Socket, val srcIp: Int, val srcPort: Int,
                  val dstIp: Int, val dstPort: Int) {
        @Volatile var ourSeq = (Math.random() * Int.MAX_VALUE).toInt()
        @Volatile var theirSeq = 0
        @Volatile var established = false
    }
}
