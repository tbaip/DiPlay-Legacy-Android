package com.shilapi.xcertplay.network

import android.util.Log
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Userspace NCM bridge backed by lwIP.
 *
 * Operates entirely in user space without requiring Android's VpnService or a Linux TUN interface.
 * Incoming Ethernet frames from USB CDC-NCM are passed to lwIP, which handles IPv6 Link-Local
 * addressing, NDP, TCP handshakes and TCP byte stream relaying directly into local loopback (127.0.0.1).
 */
class LwipNcmBridge(
    private val ncm: NcmUsbBridge,
    private val hostMac: ByteArray,
    private val hostLinkLocal: String,
    private val onDebugLog: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit,
    @Volatile var mdnsResponder: WiredMdnsResponder? = null,
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private val hostLinkLocalBytes = InetAddress.getByName(hostLinkLocal).address
    private val fe80_1_Bytes = byteArrayOf(
        0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    )
    private val allNodesIpv6 = byteArrayOf(
        0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
    )
    private val allNodesMac = byteArrayOf(0x33, 0x33, 0, 0, 0, 0x01)

    private var loggedInbound = false
    private var inboundLogBudget = 16
    private var reportInboundBudget = 8
    private var reportTcpBudget = 8
    private var mdnsAnswerLogBudget = 6
    private var unmatchedMdnsLogBudget = 6

    val injectedTcpPackets = AtomicInteger(0)
    private var lwipInjectLogBudget = 8

    @Volatile
    private var lastNaTimeNanos = 0L
    private val running = AtomicBoolean(false)
    private lateinit var ncmRxThread: Thread
    private lateinit var ncmTxThread: Thread
    // Issue #187: lwIP's linkoutput runs on the native worker thread while holding the native
    // ctx lock; doing blocking USB bulkTransfer there (up to 2s) convoys every other native
    // call and can wedge a fragile USB host driver (whole-unit freeze on T3 cold-start wired).
    // Hand TX frames to a dedicated thread instead; the queue is bounded so a wedged USB
    // endpoint drops frames instead of growing without limit.
    private val txQueue = LinkedBlockingQueue<ByteArray>(TX_QUEUE_CAPACITY)
    private val droppedTxFrames = AtomicInteger(0)
    private var txDropLogBudget = 4

    private val lwipNative = LwipNcmNative(
        onTxFrameCallback = ::onLwipTxFrame,
    )

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }

        val created = lwipNative.create(hostMac, hostLinkLocal)
        if (!created) {
            val err = IOException("Failed to initialize userspace lwIP stack")
            onError(err)
            throw err
        }

        sendUnsolicitedNeighborAdvertisement(force = true)

        ncmRxThread = Thread(::runNcmRx, "ncm-lwip-rx").apply {
            isDaemon = true
            start()
        }
        txQueue.clear()
        ncmTxThread = Thread(::runNcmTx, "ncm-lwip-tx").apply {
            isDaemon = true
            start()
        }
    }

    fun registerPort(port: Int, isTcp: Boolean) {
        lwipNative.registerPort(port, isTcp)
    }

    private fun onLwipTxFrame(frame: ByteArray) {
        if (!running.get()) return
        // Non-blocking: never stall the native lwIP worker thread on USB I/O (issue #187).
        if (!txQueue.offer(frame)) {
            droppedTxFrames.incrementAndGet()
            if (txDropLogBudget > 0) {
                txDropLogBudget--
                runCatching { onDebugLog("ncm lwip tx queue full; dropping frame bytes=${frame.size}") }
            }
        }
    }

    private fun runNcmTx() {
        try {
            while (running.get()) {
                val frame = txQueue.poll(TX_POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                try {
                    ncm.send(frame, WRITE_TIMEOUT_MILLIS)
                } catch (error: Exception) {
                    Log.w(TAG, "ncm send lwip tx frame failed: ${error.message}")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun runNcmRx() {
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS)
                if (frame == null) {
                    if (peerMac == null) {
                        sendUnsolicitedNeighborAdvertisement()
                    }
                    continue
                }

                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                peerMac = ipv6.sourceMac

                val ns = EthernetIpv6Codec.parseNeighborSolicitation(frame, ipv6.payloadOffset, ipv6.payloadLength)
                if (ns != null) {
                    val isDad = ns.sourceIp.all { it == 0.toByte() }
                    val isHostAddress = ns.targetIp.contentEquals(hostLinkLocalBytes) ||
                        ns.targetIp.contentEquals(fe80_1_Bytes)
                    val isLinkLocal = (ns.targetIp[0].toInt() and 0xff) == 0xfe &&
                        (ns.targetIp[1].toInt() and 0xc0) == 0x80
                    val shouldAnswer = if (isDad) {
                        isHostAddress
                    } else {
                        isHostAddress || (isLinkLocal && !ns.targetIp.contentEquals(ns.sourceIp))
                    }

                    if (shouldAnswer) {
                        val replyDstMac = if (isDad) allNodesMac else (ns.sourceMac ?: ipv6.sourceMac)
                        val replyDstIp = if (isDad) allNodesIpv6 else ns.sourceIp
                        val naFrame = EthernetIpv6Codec.buildNeighborAdvertisement(
                            hostMac = hostMac,
                            peerMac = replyDstMac,
                            hostIpv6 = ns.targetIp,
                            peerIpv6 = replyDstIp,
                            solicited = !isDad,
                        )
                        ncm.send(naFrame, WRITE_TIMEOUT_MILLIS)
                        val targetStr = runCatching { InetAddress.getByAddress(ns.targetIp).hostAddress }.getOrDefault("unknown")
                        val peerStr = runCatching { InetAddress.getByAddress(replyDstIp).hostAddress }.getOrDefault("unknown")
                        val naMsg = "ncm replied ${if (isDad) "unsolicited" else "solicited"} NA for $targetStr to $peerStr (${replyDstMac.macString()})"
                        Log.i(TAG, naMsg)
                        runCatching { onDebugLog(naMsg) }
                        continue
                    } else {
                        val targetStr = runCatching { InetAddress.getByAddress(ns.targetIp).hostAddress }.getOrDefault("unknown")
                        Log.d(TAG, "ncm ignoring DAD NS for peer candidate $targetStr")
                    }
                }

                val responder = mdnsResponder
                val mdnsAnswers = responder?.answerFrames(
                    frame, ipv6.payloadOffset, ipv6.payloadLength, hostMac, ipv6.sourceMac,
                ) ?: emptyList()
                if (mdnsAnswers.isNotEmpty()) {
                    for (mdnsAnswer in mdnsAnswers) {
                        ncm.send(mdnsAnswer, WRITE_TIMEOUT_MILLIS)
                    }
                    if (mdnsAnswerLogBudget > 0) {
                        mdnsAnswerLogBudget--
                        val answerMsg = "WIRED-CHAIN [15/16] mDNS _airplay query answered frames=${mdnsAnswers.size} " +
                            "bytes=${mdnsAnswers.sumOf { it.size }}"
                        Log.i(TAG, answerMsg)
                        runCatching { onDebugLog(answerMsg) }
                    }
                    continue
                }

                if (responder != null && unmatchedMdnsLogBudget > 0) {
                    val queryName = responder.unmatchedQueryName(frame, ipv6.payloadOffset, ipv6.payloadLength)
                    if (queryName != null) {
                        unmatchedMdnsLogBudget--
                        runCatching { onDebugLog("ncm wired mdns query not answered name=$queryName") }
                    }
                }

                if (!loggedInbound) {
                    loggedInbound = true
                    val inMsg = "ncm first inbound ipv6 bytes=${ipv6.payloadLength} peer=${ipv6.sourceMac.macString()}"
                    Log.i(TAG, inMsg)
                    runCatching { onDebugLog(inMsg) }
                    sendUnsolicitedNeighborAdvertisement(force = true, targetMac = ipv6.sourceMac)
                }

                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    Log.i(TAG, "ncm inbound ${frame.summary(ipv6.payloadOffset)}")
                }

                val inboundNextHeader =
                    if (ipv6.payloadLength >= 40) (frame[ipv6.payloadOffset + 6].toInt() and 0xff) else -1
                if (inboundNextHeader == 6 && reportTcpBudget > 0) {
                    reportTcpBudget--
                    runCatching { onDebugLog("ncm inbound diag ${frame.diagnosticSummary(ipv6.payloadOffset)}") }
                } else if (reportInboundBudget > 0) {
                    reportInboundBudget--
                    runCatching { onDebugLog("ncm inbound diag ${frame.diagnosticSummary(ipv6.payloadOffset)}") }
                }

                if (inboundNextHeader == 58 && ipv6.payloadLength >= 48) {
                    val icmpType = frame[ipv6.payloadOffset + 40].toInt() and 0xff
                    if (icmpType == 133) {
                        Log.i(TAG, "ncm inbound Router Solicitation from iPhone")
                        runCatching { onDebugLog("ncm inbound RS from peer ${ipv6.sourceMac.macString()}") }
                        sendUnsolicitedNeighborAdvertisement(force = true, targetMac = ipv6.sourceMac)
                    }
                }

                val isTcp = inboundNextHeader == 6
                if (isTcp) {
                    val tcpCount = injectedTcpPackets.incrementAndGet()
                    if (tcpCount == 1) {
                        val tcpMsg = "WIRED-CHAIN [16/16] first TCP packet passed to lwIP (iPhone -> 7000)"
                        Log.i(TAG, tcpMsg)
                        runCatching { onDebugLog(tcpMsg) }
                    }
                }

                lwipNative.inputFrame(frame)

                if (isTcp && lwipInjectLogBudget > 0) {
                    lwipInjectLogBudget--
                    val dstPort = if (ipv6.payloadLength >= 44) {
                        ((frame[ipv6.payloadOffset + 42].toInt() and 0xff) shl 8) or
                            (frame[ipv6.payloadOffset + 43].toInt() and 0xff)
                    } else -1
                    val flags = if (ipv6.payloadLength >= 54) frame[ipv6.payloadOffset + 40 + 13].toInt() and 0xff else 0
                    val isSyn = (flags and 0x02) != 0
                    val dstLocal = ipv6.payloadLength >= 40 &&
                        (frame.copyOfRange(ipv6.payloadOffset + 24, ipv6.payloadOffset + 40).contentEquals(hostLinkLocalBytes) ||
                         frame.copyOfRange(ipv6.payloadOffset + 24, ipv6.payloadOffset + 40).contentEquals(fe80_1_Bytes))
                    runCatching {
                        onDebugLog(
                            "ncm lwip inject tcp bytes=${ipv6.payloadLength} dstPort=$dstPort${if (isSyn) " [SYN]" else ""} dstLocal=$dstLocal ok",
                        )
                    }
                }
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    fun tcpSnapshot(): String = lwipNative.getSnapshot()

    fun sendUnsolicitedNeighborAdvertisement(force: Boolean = false, targetMac: ByteArray? = null) {
        if (!running.get()) return
        val now = System.nanoTime()
        synchronized(this) {
            if (!force && now - lastNaTimeNanos < NA_RETRY_INTERVAL_NANOS) return
            lastNaTimeNanos = now
        }
        try {
            val dstMac = targetMac ?: peerMac ?: allNodesMac
            val frame = EthernetIpv6Codec.buildNeighborAdvertisement(
                hostMac = hostMac,
                peerMac = dstMac,
                hostIpv6 = hostLinkLocalBytes,
                peerIpv6 = allNodesIpv6,
                solicited = false,
            )
            ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            val naMsg = "ncm sent unsolicited neighbor advertisement for $hostLinkLocal"
            Log.i(TAG, naMsg)
            runCatching { onDebugLog(naMsg) }
        } catch (error: Exception) {
            Log.w(TAG, "ncm unsolicited NA send failed: ${error.message}")
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        // Stop the TX thread first so no new USB writes start; remaining queued frames
        // are dropped with the link going down. Guard for a start() that threw before
        // the thread was created.
        if (::ncmTxThread.isInitialized) join(ncmTxThread)
        ncm.close()
        lwipNative.destroy()
        join(ncmRxThread)
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int): String {
        val payloadBytes = size - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        const val NA_RETRY_INTERVAL_NANOS = 500_000_000L
        const val TX_QUEUE_CAPACITY = 64
        const val TX_POLL_MILLIS = 200L
    }
}
