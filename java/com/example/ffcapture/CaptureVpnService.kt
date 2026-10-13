package com.example.ffcapture

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class CaptureVpnService : VpnService() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        private const val CH = "ffcap"
        private const val NOTIF_ID = 1
        val FF_PACKAGES = listOf("com.dts.freefireth", "com.dts.freefiremax")
        @Volatile var packetCount = 0L
    }

    private var tun: ParcelFileDescriptor? = null
    private var tin: FileInputStream? = null
    private var tout: FileOutputStream? = null
    private var pcap: PcapWriter? = null
    @Volatile private var running = false
    private val outLock = Any()

    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()

    // ---- v2 lives here ----
    private val transformer: PacketTransformer = NoOpTransformer

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        if (running) return
        startForeground(NOTIF_ID, notification("Capturing Free Fire packets"))

        val b = Builder()
            .setSession("FF Capture")
            .setMtu(1500)
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        val installed = FF_PACKAGES.filter { isInstalled(it) }
        if (installed.isNotEmpty()) installed.forEach { b.addAllowedApplication(it) }
        // else: capture everything

        val pfd = b.establish() ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE); return
        }
        tun = pfd
        tin = FileInputStream(pfd.fileDescriptor)
        tout = FileOutputStream(pfd.fileDescriptor)

        val dir = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val f = File(dir, "ff_${System.currentTimeMillis()}.pcap")
        pcap = PcapWriter(FileOutputStream(f))
        CaptureState.currentFile = f.absolutePath
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
            val ts = System.currentTimeMillis()
            pcap?.writePacket(buf, n, ts)
            packetCount++; CaptureState.count = packetCount
            try { route(buf, n) } catch (e: Exception) { /* keep going */ }
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
            flow = UdpFlow(sock, srcIp, sp, dstIp, dp)
            udpFlows[key] = flow
            thread { udpReader(flow!!) }
        }
        val payload = p.copyOfRange(off, off + len)
        flow.socket.send(DatagramPacket(payload, payload.size))
    }

    private fun udpReader(flow: UdpFlow) {
        val rbuf = ByteArray(65535)
        while (running && !flow.socket.isClosed) {
            val dg = DatagramPacket(rbuf, rbuf.size)
            try { flow.socket.receive(dg) } catch (e: Exception) { break }
            var pkt = PacketUtils.buildUdp(
                flow.dstIp, flow.srcIp, flow.dstPort, flow.srcPort,
                rbuf, 0, dg.length)
            pkt = transformer.mutate(pkt, pkt.size, Direction.INBOUND)
            pcap?.writePacket(pkt, pkt.size, System.currentTimeMillis())
            writeToTun(pkt)
        }
    }

    // ---------------- TCP (compact relay) ----------------

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

        val key = "$srcIp:$sp-$dstIp:$dp"
        val SYN = 0x02; val ACK = 0x10; val FIN = 0x01; val RST = 0x04; val PSH = 0x08

        if (flags and SYN != 0 && flags and ACK == 0) {
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

        if (flags and RST != 0) { flow.socket.close(); tcpFlows.remove(key); return }

        if (plen > 0) {
            // retransmit guard
            if (seq != flow.theirSeq) {
                sendAck(flow, ACK, dstIp, srcIp, dp, sp)
                return
            }
            try { flow.socket.getOutputStream().write(p, poff, plen) } catch (e: Exception) {}
            flow.theirSeq += plen
            sendAck(flow, ACK, dstIp, srcIp, dp, sp)
        }

        if (flags and FIN != 0) {
            flow.theirSeq++
            sendAck(flow, ACK or FIN, dstIp, srcIp, dp, sp)
            flow.ourSeq++
            try { flow.socket.close() } catch (e: Exception) {}
            tcpFlows.remove(key)
        }
    }

    private fun sendAck(flow: TcpFlow, flags: Int, srcIp: Int, dstIp: Int, sp: Int, dp: Int) {
        val pkt = PacketUtils.buildTcp(srcIp, dstIp, sp, dp,
            flow.ourSeq, flow.theirSeq, flags, null, 0, 0)
        writeToTun(pkt)
    }

    private fun tcpServerReader(flow: TcpFlow, key: String) {
        val buf = ByteArray(16 * 1024)
        try {
            val ins = flow.socket.getInputStream()
            while (running && !flow.socket.isClosed) {
                val r = ins.read(buf)
                if (r < 0) break
                if (r == 0) continue
                var pkt = PacketUtils.buildTcp(flow.dstIp, flow.srcIp, flow.dstPort, flow.srcPort,
                    flow.ourSeq, flow.theirSeq, 0x18 /*PSH|ACK*/, buf, 0, r)
                pkt = transformer.mutate(pkt, pkt.size, Direction.INBOUND)
                flow.ourSeq += r
                pcap?.writePacket(pkt, pkt.size, System.currentTimeMillis())
                writeToTun(pkt)
            }
        } catch (e: Exception) {}
        tcpFlows.remove(key)
    }

    // ---------------- plumbing ----------------

    private fun writeToTun(pkt: ByteArray) {
        val o = tout ?: return
        synchronized(outLock) { try { o.write(pkt) } catch (e: Exception) {} }
    }

    private fun isInstalled(pkg: String) =
        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun notification(text: String): Notification {
        val ch = ChannelBuilder(CH, "FF Capture")
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("FF Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun shutdown() {
        running = false
        udpFlows.values.forEach { try { it.socket.close() } catch (e: Exception) {} }
        tcpFlows.values.forEach { try { it.socket.close() } catch (e: Exception) {} }
        udpFlows.clear(); tcpFlows.clear()
        try { pcap?.close() } catch (e: Exception) {}
        try { tun?.close() } catch (e: Exception) {}
        tun = null; pcap = null; tin = null; tout = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { super.onDestroy(); if (running) shutdown() }

    class UdpFlow(val socket: DatagramSocket, val srcIp: Int, val srcPort: Int,
                  val dstIp: Int, val dstPort: Int)

    class TcpFlow(val socket: Socket, val srcIp: Int, val srcPort: Int,
                  val dstIp: Int, val dstPort: Int) {
        @Volatile var ourSeq = (Math.random() * Int.MAX_VALUE).toInt()
        @Volatile var theirSeq = 0
        @Volatile var established = false
    }
}
