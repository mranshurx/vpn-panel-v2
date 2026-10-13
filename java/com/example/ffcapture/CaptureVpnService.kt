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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startCapture()
        return START_STICKY
    }

    /** Another VPN took over. Tear down instead of leaving a half-dead service. */
    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    private fun startCapture() {
        if (running) return

        startForeground(NOTIF_ID, buildNotification("Capturing Free Fire packets"))

        val b = Builder()
            .setSession("FF Capture")
            .setMtu(MTU)
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

        val pfd = try { b.establish() } catch (e: Exception) { null }
        if (pfd == null) {
            CaptureState.scope = "not established"
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
        val d = File(dir, "$base.ffp")
        try {
            pcap = PcapWriter(f)
            dump = PayloadDumper(d)
        } catch (e: Exception) {
            shutdown()
            return
        }
        CaptureState.currentFile = f.absolutePath
        CaptureState.currentDumpFile = d.absolutePath
        CaptureState.count = 0

        running = true
        CaptureState.running = true
        thread(name = "tun-read") { readLoop() }
        thread(name = "flow-sweeper") { sweepLoop() }
    }

    private fun readLoop() {
        val buf = ByteArray(65535)
        val inS = tin ?: return
        while (running) {
            val n = try { inS.read(buf) } catch (e: Exception) { -1 }
            if (n < 0) break                    // TUN closed/revoked
            if (n == 0) continue
            pcap?.writePacket(buf, n, System.currentTimeMillis())
            CaptureState.count++
            try { dumpAppPayload(buf, n, Direction.OUTBOUND) } catch (e: Exception) { }
            try { route(buf, n) } catch (e: Exception) { }
        }
        if (running) shutdown()
    }

    /** Reaps idle flows so sockets/threads don't accumulate forever. */
    private fun sweepLoop() {
        while (running) {
            try { Thread.sleep(5_000) } catch (e: InterruptedException) { break }
            val now = System.currentTimeMillis()

            for ((k, f) in udpFlows) {
                if (now - f.lastSeen > UDP_IDLE_MS) {
                    udpFlows.remove(k)
                    runCatching { f.socket.close() }
                }
            }
            for ((k, f) in tcpFlows) {
                if (now - f.lastSeen > TCP_IDLE_MS) {
                    tcpFlows.remove(k)
                    runCatching { f.socket.close() }
                }
            }
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
        if (hl < 20 || hl > n) return
        val total = minOf(PacketUtils.totalLength(p), n)
        if (total < hl + 8) return

        val srcIp = PacketUtils.readInt(p, 12)
        val dstIp = PacketUtils.readInt(p, 16)
        val sp = PacketUtils.readShort(p, hl)
        val dp = PacketUtils.readShort(p, hl + 2)
        val off = hl + 8
        val len = total - off
        if (len < 0) return

        val key = "$srcIp:$sp-$dstIp:$dp"
        var flow = udpFlows[key]
        if (flow == null) {
            val sock = DatagramSocket()
            protect(sock)
            sock.connect(InetAddress.getByAddress(PacketUtils.toBytes(dstIp)), dp)
            val nf = UdpFlow(sock, srcIp, sp, dstIp, dp)
            udpFlows[key] = nf
            thread { udpReader(nf, key) }
            flow = nf
        }
        val f = flow!!
        f.lastSeen = System.currentTimeMillis()

        val raw = p.copyOfRange(off, off + len)
        // skip DNS and low ports; game traffic lives up in the 10k range
        val out = if (dp >= 1000) patcher.mutate(raw, raw.size, Direction.OUTBOUND) else raw
        runCatching { f.socket.send(DatagramPacket(out, out.size)) }
    }

    private fun udpReader(flow: UdpFlow, key: String) {
        val rbuf = ByteArray(65535)
        while (running && !flow.socket.isClosed) {
            val dg = DatagramPacket(rbuf, rbuf.size)
            try { flow.socket.receive(dg) } catch (e: Exception) { break }
            flow.lastSeen = System.currentTimeMillis()

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
        udpFlows.remove(key)
    }

    // ---------------- TCP ----------------

    private fun handleTcp(p: ByteArray, n: Int) {
        val hl = PacketUtils.ipHeaderLen(p)
        if (hl < 20 || hl > n) return
        val total = minOf(PacketUtils.totalLength(p), n)

        val srcIp = PacketUtils.readInt(p, 12)
        val dstIp = PacketUtils.readInt(p, 16)
        val sp = PacketUtils.readShort(p, hl)
        val dp = PacketUtils.readShort(p, hl + 2)
        val seq = PacketUtils.readInt(p, hl + 4)
        val ackNum = PacketUtils.readInt(p, hl + 8)
        if (hl + 20 > n) return
        val dataOff = ((p[hl + 12].toInt() and 0xF0) shr 4) * 4
        val flags = p[hl + 13].toInt() and 0xFF
        val poff = hl + dataOff
        val plen = total - poff

        val key = "$srcIp:$sp-$dstIp:$dp"

        // ---- SYN (no ACK): open the real socket, exactly once per flow ----
        if ((flags and SYN) != 0 && (flags and ACK) == 0) {
            if (tcpFlows.containsKey(key)) return   // retransmitted SYN while connecting
            val sock = Socket()
            protect(sock)
            val flow = TcpFlow(sock, srcIp, sp, dstIp, dp)
            flow.theirSeq = seq + 1
            tcpFlows[key] = flow
            thread { tcpConnect(flow, key) }
            return
        }

        val flow = tcpFlows[key]

        // ---- unknown flow: RST so the client fails fast instead of hanging ----
        if (flow == null) {
            if ((flags and RST) == 0) {
                val rstSeq = if ((flags and ACK) != 0) ackNum else 0
                val ackNo = if ((flags and ACK) != 0) 0
                            else seq + plen + (if ((flags and (SYN or FIN)) != 0) 1 else 0)
                writeToTun(PacketUtils.buildTcp(dstIp, srcIp, dp, sp,
                    rstSeq, ackNo, RST or ACK, null, 0, 0))
            }
            return
        }

        flow.lastSeen = System.currentTimeMillis()

        if ((flags and RST) != 0) {
            runCatching { flow.socket.close() }
            tcpFlows.remove(key)
            return
        }

        if (plen > 0) {
            if (seq != flow.theirSeq) {
                // gap or duplicate: cumulative ACK, let the peer recover
                sendAck(flow, ACK, dstIp, srcIp, dp, sp)
                return
            }
            if (!writeToSocket(flow, p, poff, plen)) {
                // socket not ready - don't ACK, peer will retransmit
                return
            }
            flow.theirSeq += plen
            sendAck(flow, ACK, dstIp, srcIp, dp, sp)
        }

        if ((flags and FIN) != 0) {
            flow.theirSeq++
            sendAck(flow, ACK, dstIp, srcIp, dp, sp)
            runCatching { flow.socket.shutdownOutput() }   // half-close, keep reading
            flow.clientFin = true
            if (flow.serverDone) {
                runCatching { flow.socket.close() }
                tcpFlows.remove(key)
            }
        }
    }

    private fun tcpConnect(flow: TcpFlow, key: String) {
        try {
            flow.socket.connect(InetSocketAddress(
                InetAddress.getByAddress(PacketUtils.toBytes(flow.dstIp)), flow.dstPort), 8000)
        } catch (e: Exception) {
            writeToTun(PacketUtils.buildTcp(flow.dstIp, flow.srcIp, flow.dstPort, flow.srcPort,
                flow.ourSeq, flow.theirSeq, RST or ACK, null, 0, 0))
            tcpFlows.remove(key)
            return
        }
        flow.established = true
        writeToTun(PacketUtils.buildTcp(flow.dstIp, flow.srcIp, flow.dstPort, flow.srcPort,
            flow.ourSeq, flow.theirSeq, SYN or ACK, null, 0, 0, MSS))
        flow.ourSeq++
        tcpServerReader(flow, key)
    }

    private fun writeToSocket(flow: TcpFlow, p: ByteArray, off: Int, len: Int): Boolean = try {
        val os = flow.socket.getOutputStream()
        os.write(p, off, len)
        os.flush()
        true
    } catch (e: Exception) { false }

    private fun sendAck(flow: TcpFlow, flags: Int, srcIp: Int, dstIp: Int, sp: Int, dp: Int) {
        writeToTun(PacketUtils.buildTcp(srcIp, dstIp, sp, dp,
            flow.ourSeq, flow.theirSeq, flags, null, 0, 0))
    }

    private fun tcpServerReader(flow: TcpFlow, key: String) {
        val buf = ByteArray(MSS)
        var closed = false
        try {
            val ins = flow.socket.getInputStream()
            while (running && !flow.socket.isClosed) {
                val r = ins.read(buf)
                if (r < 0) { closed = true; break }
                if (r == 0) continue
                flow.lastSeen = System.currentTimeMillis()
                dump?.write(Direction.INBOUND, PacketUtils.PROTO_TCP,
                    flow.dstPort, flow.srcPort, buf, 0, r)
                var pkt = PacketUtils.buildTcp(flow.dstIp, flow.srcIp, flow.dstPort, flow.srcPort,
                    flow.ourSeq, flow.theirSeq, 0x18, buf, 0, r)
                pkt = transformer.mutate(pkt, pkt.size, Direction.INBOUND)
                flow.ourSeq += r
                pcap?.writePacket(pkt, pkt.size, System.currentTimeMillis())
                writeToTun(pkt)
            }
        } catch (e: Exception) { closed = true }

        if (closed && running) {
            // THE fix: propagate the server's FIN to the client so it stops waiting
            writeToTun(PacketUtils.buildTcp(flow.dstIp, flow.srcIp, flow.dstPort, flow.srcPort,
                flow.ourSeq, flow.theirSeq, FIN or ACK, null, 0, 0))
            flow.ourSeq++
            flow.serverDone = true
            if (flow.clientFin) {
                runCatching { flow.socket.close() }
                tcpFlows.remove(key)
            }
        }
    }

    // ---------------- plumbing ----------------

    private fun writeToTun(pkt: ByteArray) {
        val o = tout ?: return
        synchronized(outLock) { runCatching { o.write(pkt) } }
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
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("FF Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun shutdown() {
        running = false
        CaptureState.running = false

        udpFlows.values.forEach { runCatching { it.socket.close() } }
        tcpFlows.values.forEach { runCatching { it.socket.close() } }
        udpFlows.clear()
        tcpFlows.clear()

        runCatching { pcap?.close() }
        runCatching { dump?.close() }
        runCatching { tun?.close() }

        tun = null
        pcap = null
        dump = null
        tin = null
        tout = null

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) shutdown()
    }

    class UdpFlow(val socket: DatagramSocket, val srcIp: Int, val srcPort: Int,
                  val dstIp: Int, val dstPort: Int) {
        @Volatile var lastSeen = System.currentTimeMillis()
    }

    class TcpFlow(val socket: Socket, val srcIp: Int, val srcPort: Int,
                  val dstIp: Int, val dstPort: Int) {
        @Volatile var ourSeq = (Math.random() * Int.MAX_VALUE).toInt()
        @Volatile var theirSeq = 0
        @Volatile var established = false
        @Volatile var clientFin = false
        @Volatile var serverDone = false
        @Volatile var lastSeen = System.currentTimeMillis()
    }
}
