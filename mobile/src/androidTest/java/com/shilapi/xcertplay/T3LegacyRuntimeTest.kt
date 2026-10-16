package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbInterface
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.CarPlayVpnService
import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.network.LwipNcmNative
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real Dalvik/native/socket/AudioTrack tests, not an iPhone or a T3 kernel simulation. */
class T3LegacyRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun settingsOpensEveryDriverCategoryOnKitKat() {
        val activity = instrumentation.startActivitySync(Intent(context, DiPlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val settings = texts(activity.window.decorView).first { it.text.toString() == activity.getString(R.string.settings) }
                assertTrue(settings.performClick())
            }
            instrumentation.waitForIdleSync()
            // The app language can differ from the emulator system language.
            val titles = listOf(R.string.connection, R.string.settings_display, R.string.audio,
                R.string.settings_navigation, R.string.settings_vehicle, R.string.diagnostics,
                R.string.settings_advanced).map { activity.getString(it) }
            for (title in titles) {
                instrumentation.runOnMainSync {
                    val row = texts(activity.window.decorView).firstOrNull { it.text.toString() == title }
                    assertNotNull("Missing category $title", row)
                    var target: View = row!!
                    while (!target.isClickable && target.parent is View) target = target.parent as View
                    assertTrue("Open $title", target.performClick())
                }
                instrumentation.waitForIdleSync()
                assertFalse(activity.isFinishing)
                // Compact screens show category links on Overview rather than a persistent rail.
                instrumentation.runOnMainSync { activity.onBackPressed() }
                instrumentation.waitForIdleSync()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun nativeTunBlockingUsesAuthorizedFdOnKitKat() {
        val type = Class.forName("com.shilapi.xcertplay.network.LegacyTunBlocking")
        val owner = type.getField("INSTANCE").get(null)
        val pipes = ParcelFileDescriptor.createPipe()
        try {
            type.getMethod("enable", ParcelFileDescriptor::class.java).invoke(owner, pipes[0])
        } finally { pipes.forEach { it.close() } }
    }

    @Test fun connectionEntryScreensLaunchAndCloseWithoutModernApis() {
        val modes = listOf(com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL,
            com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P, null)
        for (mode in modes) {
            AirPlayPersistence.saveWirelessEnabled(context, mode != null)
            if (mode != null) AirPlayPersistence.saveWirelessHotspotMode(context, mode)
            AirPlayPersistence.saveManualHotspotSsid(context, "T3 runtime test")
            AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
            val activity = instrumentation.startActivitySync(Intent(context, CarPlayHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                assertFalse("Connection entry $mode finished unexpectedly", activity.isFinishing)
                assertTrue("Connection entry $mode has no content", texts(activity.window.decorView).isNotEmpty())
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL)
    }

    @Test fun pairingCryptoRoundTripsOnDalvik() {
        val first = AirPlayCrypto.x25519Generate()
        val second = AirPlayCrypto.x25519Generate()
        assertArrayEquals(AirPlayCrypto.x25519Shared(first.privateKey, second.publicKey),
            AirPlayCrypto.x25519Shared(second.privateKey, first.publicKey))
        val signing = AirPlayCrypto.ed25519Generate()
        val payload = byteArrayOf(1, 2, 3, 4)
        assertTrue(AirPlayCrypto.ed25519Verify(signing.publicKey, payload,
            AirPlayCrypto.ed25519Sign(signing.privateKey, payload)))
        val key = AirPlayCrypto.hkdfSha512(first.privateKey, byteArrayOf(5), byteArrayOf(6))
        val nonce = AirPlayCrypto.nonce64(1)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload)
        assertArrayEquals(payload, AirPlayCrypto.chachaOpen(key, nonce, sealed))
    }

    @Test fun chachaRejectsTamperedCiphertextWithoutLegacyClassLinkageFailure() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = AirPlayCrypto.nonce64(2)
        val payload = byteArrayOf(1, 2, 3, 4)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload)
        assertArrayEquals(payload, AirPlayCrypto.chachaOpen(key, nonce, sealed))
        val tampered = sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val failure = runCatching { AirPlayCrypto.chachaOpen(key, nonce, tampered) }.exceptionOrNull()
        assertNotNull("Modified authentication tag must be rejected", failure)
        assertFalse("Crypto must verify and load on legacy Android", failure is LinkageError)
    }

    @Test fun bundledAuthenticationLoadsAndSignsLocally() {
        val bootstrap = Class.forName("com.shilapi.xcertplay.DiPlayBootstrap")
        bootstrap.getMethod("ensure", Context::class.java, com.shilapi.xcertplay.orchestration.MfiTarget::class.java)
            .invoke(bootstrap.getField("INSTANCE").get(null), context, com.shilapi.xcertplay.orchestration.MfiTarget.LOCAL)
        val root = if (Build.VERSION.SDK_INT >= 21) context.noBackupFilesDir else context.filesDir
        val client = com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient.load(java.io.File(root, "offline-mfi"))
        assertTrue(client.readCertificate(16384).isNotEmpty())
        assertEquals(64, client.signChallenge(ByteArray(32)).size)
    }

    @Test fun kitKatUsbDescriptorAdapterPreservesRealConfigurationAndAlternates() {
        if (Build.VERSION.SDK_INT >= 21) return
        val type = Class.forName("com.shilapi.xcertplay.transport.KitKatUsbDescriptors")
        val owner = type.getField("INSTANCE").get(null)
        val raw = intArrayOf(9,2,32,0,1,6,0,128,50, 9,4,3,1,2,10,0,0,0,
            7,5,0x81,2,0,2,0, 7,5,2,2,0,2,0).map { it.toByte() }.toByteArray()
        val configs = type.getMethod("parse", ByteArray::class.java).invoke(owner, raw) as List<*>
        val config = configs.single()!!
        assertEquals(6, config.javaClass.getMethod("getId").invoke(config))
        val info = (config.javaClass.getMethod("getInterfaces").invoke(config) as List<*>).single()!!
        val adapter = type.methods.single { it.name.startsWith("platformInterface") }
        val iface = adapter.invoke(owner, info) as UsbInterface
        assertEquals(3, iface.id)
        assertEquals(1, IphoneAlternate(iface))
        assertEquals(2, iface.endpointCount)
        assertEquals(512, iface.getEndpoint(0).maxPacketSize)
    }

    @Test fun pcmAudioStartsWritesAndClosesWithLegacyFocus() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val ready = CountDownLatch(1)
        val started = CountDownLatch(1)
        val sink = AndroidMediaSink(context = context, audioFocusEnabled = true, mediaBufferMillis = 60,
            onAudioDiagnostic = { line ->
                lines += line
                if (line.startsWith("Audio: ready")) ready.countDown()
                if (line.contains("first PCM")) started.countDown()
            })
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 96)
        try {
            sink.onAudioStarted(id, format, 0)
            assertTrue("AudioTrack initialization: $lines", ready.await(5, TimeUnit.SECONDS))
            repeat(40) { packet ->
                sink.onAudioRtp(id, format, ByteArray(12 + 1920).apply { this[0] = 0x80.toByte() }, packet * 480)
            }
            assertTrue("PCM write path: $lines", started.await(5, TimeUnit.SECONDS))
            assertFalse("Renderer failure: $lines", lines.any { it.contains("renderer failed") })
            sink.onMediaAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            sink.onMediaAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_GAIN)
        } finally { sink.onAudioStopped(id); sink.close() }
    }

    @Test fun h264FirstFrameRendersAndDecoderReleasesOnKitKat() {
        val texture = android.graphics.SurfaceTexture(1)
        val surface = android.view.Surface(texture)
        val rendered = CountDownLatch(1)
        val frameAvailable = CountDownLatch(1)
        texture.setOnFrameAvailableListener { frameAvailable.countDown() }
        val sink = AndroidMediaSink(surface = surface, videoWidth = 320, videoHeight = 240)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        sink.setVideoDiagnosticHandler(0) { line ->
            lines += line
            if (line.contains("first frame rendered")) rendered.countDown()
        }
        val encoder = android.media.MediaCodec.createEncoderByType("video/avc")
        var encoderStarted = false
        try {
            val format = android.media.MediaFormat.createVideoFormat("video/avc", 320, 240).apply {
                setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, 500000)
                setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            val info = android.media.MediaCodec.BufferInfo()
            var submitted = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline && rendered.count > 0) {
                if (submitted < 10) {
                    val input = encoder.dequeueInputBuffer(10000)
                    if (input >= 0) {
                        val pixels = ByteArray(320 * 240 * 3 / 2) { 128.toByte() }
                        encoder.inputBuffers[input].apply { clear(); put(pixels) }
                        encoder.queueInputBuffer(input, 0, pixels.size, submitted * 33333L, 0)
                        submitted++
                    }
                }
                val output = encoder.dequeueOutputBuffer(info, 10000)
                if (output == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    fun parameter(name: String): ByteArray {
                        val buffer = encoder.outputFormat.getByteBuffer(name)!!.duplicate()
                        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                        val prefix = if (bytes[2] == 1.toByte()) 3 else 4
                        return bytes.copyOfRange(prefix, bytes.size)
                    }
                    val sps = parameter("csd-0")
                    val pps = parameter("csd-1")
                    val avc = byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte(),
                        (sps.size ushr 8).toByte(), sps.size.toByte()) + sps +
                        byteArrayOf(1, (pps.size ushr 8).toByte(), pps.size.toByte()) + pps
                    sink.onVideoCodec(0, VideoCodec.H264)
                    sink.onVideoConfig(0, avc)
                } else if (output >= 0) {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buffer = encoder.outputBuffers[output].duplicate().apply {
                            position(info.offset); limit(info.offset + info.size)
                        }
                        sink.onVideoFrame(0, carPlayAccessUnit(ByteArray(info.size).also(buffer::get)))
                    }
                    encoder.releaseOutputBuffer(output, false)
                }
            }
            assertTrue("First decoded frame: $lines", rendered.await(2, TimeUnit.SECONDS))
            assertTrue("Surface received no frame: $lines", frameAvailable.await(2, TimeUnit.SECONDS))
        } finally {
            if (encoderStarted) encoder.stop()
            encoder.release()
            sink.close()
            val released = sink.awaitVideoReleased(3000)
            surface.release()
            texture.release()
            assertTrue("Video decoder did not release", released)
        }
    }

    @Test fun airPlayListenerAnswersInfoAndRecoversOccupiedPortTwice() {
        val bound = CountDownLatch(1)
        var service: CarPlayVpnService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as CarPlayVpnService.LocalBinder).service
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, CarPlayVpnService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            val address = InetAddress.getByName("127.0.0.1")
            val occupied = ServerSocket(0, 1, address)
            try {
                repeat(2) {
                    val result = service!!.attachWireless(address,
                        AirPlayConfig(deviceName = "T3 KitKat runtime test", deviceId = "02:00:00:00:00:02",
                            btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
                            main = AirPlayDisplayConfig(1024,600,fps=30), port = occupied.localPort),
                        AirPlayIdentity.generate(), PairingStore(), null,
                        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
                    assertTrue("Startup: $result", result is CarPlayVpnService.AttachResult.Started)
                    val port = service!!.boundPort()!!
                    assertNotEquals(occupied.localPort, port)
                    val client = URL("http://127.0.0.1:$port/info").openConnection() as HttpURLConnection
                    client.connectTimeout = 5000; client.readTimeout = 5000
                    try {
                        assertEquals(200, client.responseCode)
                        val plist = client.inputStream.use { BplistCodec.decode(it.readBytes()) } as Map<*, *>
                        assertEquals("T3 KitKat runtime test", plist["name"])
                    } finally { client.disconnect() }
                    service!!.detach()
                    assertFalse(service!!.isAttached())
                }
            } finally { occupied.close() }
        } finally { service?.detach(); context.unbindService(connection) }
    }

    // Android's encoder emits Annex B; CarPlay supplies length-prefixed NAL units.
    private fun carPlayAccessUnit(annexB: ByteArray): ByteArray {
        val units = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        while (cursor + 3 < annexB.size) {
            val prefix = when {
                annexB[cursor] != 0.toByte() || annexB[cursor + 1] != 0.toByte() -> 0
                annexB[cursor + 2] == 1.toByte() -> 3
                annexB[cursor + 2] == 0.toByte() && annexB[cursor + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) {
                units += cursor to cursor + prefix
                cursor += prefix
            } else cursor++
        }
        val output = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(output)
        if (units.isEmpty()) {
            // KitKat's software AVC encoder can return a single raw slice NAL.
            check(annexB.isNotEmpty() && (annexB[0].toInt() and 31) in listOf(1, 5)) {
                "Unexpected encoder access unit, bytes=${annexB.size}, header=${annexB.firstOrNull()}"
            }
            data.writeInt(annexB.size)
            data.write(annexB)
            return output.toByteArray()
        }
        units.forEachIndexed { index, (_, start) ->
            val end = units.getOrNull(index + 1)?.first ?: annexB.size
            check(end > start)
            data.writeInt(end - start)
            data.write(annexB, start, end - start)
        }
        return output.toByteArray()
    }

    private fun IphoneAlternate(iface: UsbInterface): Int =
        com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration.alternateSetting(iface)

    @Test fun lwipUserspaceStackInitializesOnDalvik() {
        val txQueue = LinkedBlockingQueue<ByteArray>()
        val native = LwipNcmNative(onTxFrameCallback = { txQueue.offer(it) })
        val hostMac = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
        val created = native.create(hostMac, "fe80::2")
        assertTrue("nativeCreate must succeed on Dalvik/KitKat", created)
        native.registerPort(7000, isTcp = true)
        Thread.sleep(100)
        native.destroy()
    }

    @Test fun lwipUserspaceFullPipelineTcpRelay() {
        val hostMac = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
        val iphoneMac = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01)
        val hostIp = InetAddress.getByName("fe80::2").address
        val iphoneIp = InetAddress.getByName("fe80::1").address

        val serverSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val boundPort = serverSocket.localPort
        assertTrue("ServerSocket must bind a valid ephemeral port", boundPort > 0)

        val txQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
        val native = LwipNcmNative(onTxFrameCallback = { txQueue.offer(it) })
        val created = native.create(hostMac, "fe80::2")
        assertTrue("nativeCreate must succeed on KitKat", created)

        try {
            native.registerPort(boundPort, isTcp = true)
            Thread.sleep(150)

            val iphonePort = 49152
            val initialClientSeq = 1000L

            // 1. 发送由 iPhone 发起的 Neighbor Solicitation 解析 Host Link-Local 地址
            val nsFrame = buildTestNeighborSolicitationFrame(
                srcMac = iphoneMac,
                dstMac = byteArrayOf(0x33.toByte(), 0x33.toByte(), 0x00, 0x00, 0x00, 0x02),
                srcIp = iphoneIp,
                targetIp = hostIp
            )
            native.inputFrame(nsFrame)

            // 2. 发送 TCP SYN
            val synFrame = buildTestIpv6TcpFrame(
                srcMac = iphoneMac,
                dstMac = hostMac,
                srcIp = iphoneIp,
                dstIp = hostIp,
                srcPort = iphonePort,
                dstPort = boundPort,
                seq = initialClientSeq,
                ack = 0L,
                flags = 0x02, // SYN
            )
            native.inputFrame(synFrame)

            // Expect lwIP to respond with TCP SYN-ACK (and optionally an NS which iPhone answers with NA)
            var synAckFrame: ByteArray? = null
            val deadline = System.currentTimeMillis() + 4000L
            while (System.currentTimeMillis() < deadline) {
                val frame = txQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (isIcmp6NeighborSolicitation(frame)) {
                    // lwIP is probing for iPhone MAC; answer with Neighbor Advertisement
                    val naFrame = buildTestNeighborAdvertisementFrame(
                        srcMac = iphoneMac,
                        dstMac = hostMac,
                        srcIp = iphoneIp,
                        dstIp = hostIp,
                        targetIp = iphoneIp
                    )
                    native.inputFrame(naFrame)
                    continue
                }
                if (isTcpSynAck(frame, iphonePort)) {
                    synAckFrame = frame
                    break
                }
            }

            assertNotNull("Must receive TCP SYN-ACK frame from lwIP without kernel TUN", synAckFrame)
            val serverSeq = extractTcpSeq(synAckFrame!!)
            val serverAck = extractTcpAck(synAckFrame)
            assertEquals("SYN-ACK ACK must acknowledge client SYN", initialClientSeq + 1, serverAck)

            // iPhone sends TCP ACK to complete 3-way handshake
            val ackFrame = buildTestIpv6TcpFrame(
                srcMac = iphoneMac,
                dstMac = hostMac,
                srcIp = iphoneIp,
                dstIp = hostIp,
                srcPort = iphonePort,
                dstPort = boundPort,
                seq = initialClientSeq + 1,
                ack = serverSeq + 1,
                flags = 0x10, // ACK
            )
            native.inputFrame(ackFrame)

            // lwIP establishes loopback connection to 127.0.0.1:boundPort
            serverSocket.soTimeout = 3000
            val acceptedClientSocket: Socket = serverSocket.accept()
            assertNotNull("ServerSocket must accept connection from lwIP loopback relay", acceptedClientSocket)
            assertTrue("Client socket must be connected", acceptedClientSocket.isConnected)

            // Test UPLINK: iPhone sends data to AirPlay server (GET /info RTSP/1.0)
            val requestText = "GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n"
            val requestBytes = requestText.toByteArray(Charsets.UTF_8)
            val pshFrame = buildTestIpv6TcpFrame(
                srcMac = iphoneMac,
                dstMac = hostMac,
                srcIp = iphoneIp,
                dstIp = hostIp,
                srcPort = iphonePort,
                dstPort = boundPort,
                seq = initialClientSeq + 1,
                ack = serverSeq + 1,
                flags = 0x18, // PSH + ACK
                payload = requestBytes,
            )
            native.inputFrame(pshFrame)

            val inputStream: InputStream = acceptedClientSocket.getInputStream()
            val readBuf = ByteArray(1024)
            acceptedClientSocket.soTimeout = 3000
            val bytesRead = inputStream.read(readBuf)
            assertTrue("Must read uplink data forwarded by lwIP", bytesRead > 0)
            val receivedRequest = String(readBuf, 0, bytesRead, Charsets.UTF_8)
            assertEquals("Received request must match sent payload", requestText, receivedRequest)

            // Test DOWNLINK: AirPlay server replies with RTSP 200 OK
            val responseText = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n"
            val responseBytes = responseText.toByteArray(Charsets.UTF_8)
            val outputStream: OutputStream = acceptedClientSocket.getOutputStream()
            outputStream.write(responseBytes)
            outputStream.flush()

            var downlinkPayload: ByteArray? = null
            val downlinkDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < downlinkDeadline) {
                val frame = txQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val payload = extractTcpPayload(frame, iphonePort)
                if (payload != null && payload.isNotEmpty()) {
                    downlinkPayload = payload
                    break
                }
            }

            assertNotNull("Must receive downlink TCP data frame from lwIP", downlinkPayload)
            val receivedResponse = String(downlinkPayload!!, Charsets.UTF_8)
            assertEquals("Downlink response must match server output", responseText, receivedResponse)

            acceptedClientSocket.close()
        } finally {
            serverSocket.close()
            native.destroy()
        }
    }

    @Test fun lwipUserspaceFullPipelineUdpRelay() {
        val hostMac = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
        val iphoneMac = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01)
        val hostIp = InetAddress.getByName("fe80::2").address
        val iphoneIp = InetAddress.getByName("fe80::1").address

        val datagramSocket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val boundPort = datagramSocket.localPort
        assertTrue("DatagramSocket must bind a valid ephemeral port", boundPort > 0)

        val txQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
        val native = LwipNcmNative(onTxFrameCallback = { txQueue.offer(it) })
        val created = native.create(hostMac, "fe80::2")
        assertTrue("nativeCreate must succeed on KitKat", created)

        try {
            native.registerPort(boundPort, isTcp = false)
            Thread.sleep(150)

            val iphonePort = 50000

            // 1. 发送 Neighbor Solicitation 让 lwIP 建立邻居表映射
            val nsFrame = buildTestNeighborSolicitationFrame(
                srcMac = iphoneMac,
                dstMac = byteArrayOf(0x33.toByte(), 0x33.toByte(), 0x00, 0x00, 0x00, 0x02),
                srcIp = iphoneIp,
                targetIp = hostIp,
            )
            native.inputFrame(nsFrame)

            // 2. iPhone 发送 UDP 报文到 fe80::2:boundPort (例如音频 RTP/RTCP 包)
            val testPayload = "RTP-AUDIO-PACKET-DATA-TEST".toByteArray(Charsets.UTF_8)
            val udpFrame = buildTestIpv6UdpFrame(
                srcMac = iphoneMac,
                dstMac = hostMac,
                srcIp = iphoneIp,
                dstIp = hostIp,
                srcPort = iphonePort,
                dstPort = boundPort,
                payload = testPayload,
            )
            native.inputFrame(udpFrame)

            // 3. 验证本地 127.0.0.1:boundPort 的 DatagramSocket 接收到转发的 UDP 报文
            datagramSocket.soTimeout = 3000
            val recvBuf = ByteArray(1024)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            datagramSocket.receive(recvPacket)

            val receivedBytes = recvPacket.data.copyOfRange(0, recvPacket.length)
            assertArrayEquals("Received UDP payload must match forwarded data", testPayload, receivedBytes)

            // 4. 反向测试：从本地服务向请求源 (127.0.0.1:loopback_fd_port) 回复 UDP 报文 (例如 RTCP 接收者报告)
            val replyPayload = "RTCP-RECEIVER-REPORT-TEST".toByteArray(Charsets.UTF_8)
            val replyPacket = DatagramPacket(replyPayload, replyPayload.size, recvPacket.socketAddress)
            datagramSocket.send(replyPacket)

            // 5. 验证 lwIP 将本地 UDP 回包封装并输出到 CDC-NCM 帧队列发往 iPhone
            var downlinkUdpPayload: ByteArray? = null
            val downlinkDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < downlinkDeadline) {
                val frame = txQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (isIcmp6NeighborSolicitation(frame)) {
                    val naFrame = buildTestNeighborAdvertisementFrame(
                        srcMac = iphoneMac,
                        dstMac = hostMac,
                        srcIp = iphoneIp,
                        dstIp = hostIp,
                        targetIp = iphoneIp,
                    )
                    native.inputFrame(naFrame)
                    continue
                }
                val payload = extractUdpPayload(frame, iphonePort)
                if (payload != null && payload.isNotEmpty()) {
                    downlinkUdpPayload = payload
                    break
                }
            }

            assertNotNull("Must receive downlink UDP data frame from lwIP", downlinkUdpPayload)
            assertArrayEquals("Downlink UDP payload must match server reply", replyPayload, downlinkUdpPayload)
        } finally {
            datagramSocket.close()
            native.destroy()
        }
    }

    private fun isTcpSynAck(frame: ByteArray, dstPort: Int): Boolean {
        if (frame.size < 74) return false
        val etherType = ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        if (etherType != 0x86dd) return false
        if ((frame[20].toInt() and 0xff) != 6) return false
        val port = ((frame[56].toInt() and 0xff) shl 8) or (frame[57].toInt() and 0xff)
        if (port != dstPort) return false
        val flags = frame[67].toInt() and 0xff
        return (flags and 0x02) != 0 && (flags and 0x10) != 0
    }

    private fun extractTcpSeq(frame: ByteArray): Long {
        return (frame[58].toLong() and 0xff shl 24) or
            (frame[59].toLong() and 0xff shl 16) or
            (frame[60].toLong() and 0xff shl 8) or
            (frame[61].toLong() and 0xff)
    }

    private fun extractTcpAck(frame: ByteArray): Long {
        return (frame[62].toLong() and 0xff shl 24) or
            (frame[63].toLong() and 0xff shl 16) or
            (frame[64].toLong() and 0xff shl 8) or
            (frame[65].toLong() and 0xff)
    }

    private fun extractTcpPayload(frame: ByteArray, dstPort: Int): ByteArray? {
        if (frame.size < 74) return null
        val etherType = ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        if (etherType != 0x86dd) return null
        if ((frame[20].toInt() and 0xff) != 6) return null
        val port = ((frame[56].toInt() and 0xff) shl 8) or (frame[57].toInt() and 0xff)
        if (port != dstPort) return null
        val dataOffset = (frame[66].toInt() and 0xf0) ushr 2
        val payloadStart = 54 + dataOffset
        return if (frame.size > payloadStart) frame.copyOfRange(payloadStart, frame.size) else null
    }

    private fun buildTestIpv6TcpFrame(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        window: Int = 32768,
    ): ByteArray {
        val tcpHeaderLen = 20
        val tcpLen = tcpHeaderLen + payload.size
        val tcp = ByteArray(tcpLen)

        tcp[0] = (srcPort ushr 8).toByte()
        tcp[1] = srcPort.toByte()
        tcp[2] = (dstPort ushr 8).toByte()
        tcp[3] = dstPort.toByte()

        tcp[4] = (seq ushr 24).toByte()
        tcp[5] = (seq ushr 16).toByte()
        tcp[6] = (seq ushr 8).toByte()
        tcp[7] = seq.toByte()

        tcp[8] = (ack ushr 24).toByte()
        tcp[9] = (ack ushr 16).toByte()
        tcp[10] = (ack ushr 8).toByte()
        tcp[11] = ack.toByte()

        tcp[12] = 0x50.toByte() // 5 words = 20 bytes header
        tcp[13] = flags.toByte()
        tcp[14] = (window ushr 8).toByte()
        tcp[15] = window.toByte()

        if (payload.isNotEmpty()) {
            payload.copyInto(tcp, tcpHeaderLen)
        }

        var sum = 0L
        fun add16(b1: Int, b2: Int) {
            sum += ((b1 and 0xff) shl 8) or (b2 and 0xff)
        }

        for (i in 0 until 16 step 2) add16(srcIp[i].toInt(), srcIp[i + 1].toInt())
        for (i in 0 until 16 step 2) add16(dstIp[i].toInt(), dstIp[i + 1].toInt())
        sum += (tcpLen ushr 16)
        sum += (tcpLen and 0xffff)
        sum += 6 // TCP protocol number

        for (i in 0 until tcpLen step 2) {
            val b1 = tcp[i].toInt()
            val b2 = if (i + 1 < tcpLen) tcp[i + 1].toInt() else 0
            add16(b1, b2)
        }

        while (sum ushr 16 != 0L) {
            sum = (sum and 0xffff) + (sum ushr 16)
        }
        val checksum = (sum.inv().toInt()) and 0xffff
        tcp[16] = (checksum ushr 8).toByte()
        tcp[17] = checksum.toByte()

        val ipv6 = ByteArray(40 + tcpLen)
        ipv6[0] = 0x60.toByte()
        ipv6[4] = (tcpLen ushr 8).toByte()
        ipv6[5] = tcpLen.toByte()
        ipv6[6] = 6
        ipv6[7] = 64
        srcIp.copyInto(ipv6, 8)
        dstIp.copyInto(ipv6, 24)
        tcp.copyInto(ipv6, 40)

        val frame = ByteArray(14 + ipv6.size)
        dstMac.copyInto(frame, 0)
        srcMac.copyInto(frame, 6)
        frame[12] = 0x86.toByte()
        frame[13] = 0xdd.toByte()
        ipv6.copyInto(frame, 14)
        return frame
    }

    private fun isIcmp6NeighborSolicitation(frame: ByteArray): Boolean {
        if (frame.size < 78) return false
        val etherType = ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        if (etherType != 0x86dd) return false
        if ((frame[20].toInt() and 0xff) != 0x3a) return false // Next header: ICMPv6 (58)
        val icmpType = frame[54].toInt() and 0xff
        return icmpType == 135 // Neighbor Solicitation
    }

    private fun buildTestNeighborSolicitationFrame(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        targetIp: ByteArray,
    ): ByteArray {
        val payloadLen = 32 // 24 bytes NS header + 8 bytes SLLAO
        val payload = ByteArray(payloadLen)
        payload[0] = 135.toByte() // ICMP6_TYPE_NS
        payload[1] = 0 // code
        payload[2] = 0 // checksum placeholder
        payload[3] = 0
        // reserved 4..7 = 0
        targetIp.copyInto(payload, 8)
        payload[24] = 1 // SLLAO Option type 1
        payload[25] = 1 // length in 8-byte units
        srcMac.copyInto(payload, 26)

        // dstIp is solicited-node multicast for targetIp
        val dstIp = byteArrayOf(
            0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 1, 0xff.toByte(), targetIp[13], targetIp[14], targetIp[15]
        )

        var sum = 0L
        fun add16(b1: Int, b2: Int) {
            sum += ((b1 and 0xff) shl 8) or (b2 and 0xff)
        }
        for (i in 0 until 16 step 2) add16(srcIp[i].toInt(), srcIp[i + 1].toInt())
        for (i in 0 until 16 step 2) add16(dstIp[i].toInt(), dstIp[i + 1].toInt())
        sum += (payloadLen ushr 16)
        sum += (payloadLen and 0xffff)
        sum += 0x3a // ICMPv6 next header (58)

        for (i in 0 until payloadLen step 2) {
            val b1 = payload[i].toInt()
            val b2 = if (i + 1 < payloadLen) payload[i + 1].toInt() else 0
            add16(b1, b2)
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xffff) + (sum ushr 16)
        }
        val chk = (sum.inv().toInt()) and 0xffff
        payload[2] = (chk ushr 8).toByte()
        payload[3] = chk.toByte()

        val ipv6 = ByteArray(40 + payloadLen)
        ipv6[0] = 0x60.toByte()
        ipv6[4] = (payloadLen ushr 8).toByte()
        ipv6[5] = payloadLen.toByte()
        ipv6[6] = 0x3a // ICMPv6
        ipv6[7] = 255.toByte() // Hop limit = 255 (RFC 4861 requirement)
        srcIp.copyInto(ipv6, 8)
        dstIp.copyInto(ipv6, 24)
        payload.copyInto(ipv6, 40)

        val frame = ByteArray(14 + ipv6.size)
        dstMac.copyInto(frame, 0)
        srcMac.copyInto(frame, 6)
        frame[12] = 0x86.toByte()
        frame[13] = 0xdd.toByte()
        ipv6.copyInto(frame, 14)
        return frame
    }

    private fun buildTestNeighborAdvertisementFrame(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        targetIp: ByteArray,
    ): ByteArray {
        val payloadLen = 32 // 24 bytes NA header + 8 bytes TLLAO
        val payload = ByteArray(payloadLen)
        payload[0] = 136.toByte() // ICMP6_TYPE_NA
        payload[1] = 0 // code
        payload[2] = 0 // checksum placeholder
        payload[3] = 0
        payload[4] = 0xe0.toByte() // Router(0x80) | Solicited(0x40) | Override(0x20)
        targetIp.copyInto(payload, 8)
        payload[24] = 2 // TLLAO Option type 2
        payload[25] = 1 // length in 8-byte units
        srcMac.copyInto(payload, 26)

        var sum = 0L
        fun add16(b1: Int, b2: Int) {
            sum += ((b1 and 0xff) shl 8) or (b2 and 0xff)
        }
        for (i in 0 until 16 step 2) add16(srcIp[i].toInt(), srcIp[i + 1].toInt())
        for (i in 0 until 16 step 2) add16(dstIp[i].toInt(), dstIp[i + 1].toInt())
        sum += (payloadLen ushr 16)
        sum += (payloadLen and 0xffff)
        sum += 0x3a // ICMPv6 next header (58)

        for (i in 0 until payloadLen step 2) {
            val b1 = payload[i].toInt()
            val b2 = if (i + 1 < payloadLen) payload[i + 1].toInt() else 0
            add16(b1, b2)
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xffff) + (sum ushr 16)
        }
        val chk = (sum.inv().toInt()) and 0xffff
        payload[2] = (chk ushr 8).toByte()
        payload[3] = chk.toByte()

        val ipv6 = ByteArray(40 + payloadLen)
        ipv6[0] = 0x60.toByte()
        ipv6[4] = (payloadLen ushr 8).toByte()
        ipv6[5] = payloadLen.toByte()
        ipv6[6] = 0x3a // ICMPv6
        ipv6[7] = 255.toByte() // Hop limit = 255
        srcIp.copyInto(ipv6, 8)
        dstIp.copyInto(ipv6, 24)
        payload.copyInto(ipv6, 40)

        val frame = ByteArray(14 + ipv6.size)
        dstMac.copyInto(frame, 0)
        srcMac.copyInto(frame, 6)
        frame[12] = 0x86.toByte()
        frame[13] = 0xdd.toByte()
        ipv6.copyInto(frame, 14)
        return frame
    }

    private fun buildTestIpv6UdpFrame(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpHeaderLen = 8
        val udpLen = udpHeaderLen + payload.size
        val udp = ByteArray(udpLen)

        udp[0] = (srcPort ushr 8).toByte()
        udp[1] = srcPort.toByte()
        udp[2] = (dstPort ushr 8).toByte()
        udp[3] = dstPort.toByte()
        udp[4] = (udpLen ushr 8).toByte()
        udp[5] = udpLen.toByte()
        udp[6] = 0 // Checksum placeholder
        udp[7] = 0

        if (payload.isNotEmpty()) {
            payload.copyInto(udp, udpHeaderLen)
        }

        var sum = 0L
        fun add16(b1: Int, b2: Int) {
            sum += ((b1 and 0xff) shl 8) or (b2 and 0xff)
        }

        for (i in 0 until 16 step 2) add16(srcIp[i].toInt(), srcIp[i + 1].toInt())
        for (i in 0 until 16 step 2) add16(dstIp[i].toInt(), dstIp[i + 1].toInt())
        sum += (udpLen ushr 16)
        sum += (udpLen and 0xffff)
        sum += 17 // UDP protocol number

        for (i in 0 until udpLen step 2) {
            val b1 = udp[i].toInt()
            val b2 = if (i + 1 < udpLen) udp[i + 1].toInt() else 0
            add16(b1, b2)
        }

        while (sum ushr 16 != 0L) {
            sum = (sum and 0xffff) + (sum ushr 16)
        }
        val checksum = (sum.inv().toInt()) and 0xffff
        val finalChecksum = if (checksum == 0) 0xffff else checksum
        udp[6] = (finalChecksum ushr 8).toByte()
        udp[7] = finalChecksum.toByte()

        val ipv6 = ByteArray(40 + udpLen)
        ipv6[0] = 0x60.toByte()
        ipv6[4] = (udpLen ushr 8).toByte()
        ipv6[5] = udpLen.toByte()
        ipv6[6] = 17 // Next header: UDP
        ipv6[7] = 64 // Hop limit
        srcIp.copyInto(ipv6, 8)
        dstIp.copyInto(ipv6, 24)
        udp.copyInto(ipv6, 40)

        val frame = ByteArray(14 + ipv6.size)
        dstMac.copyInto(frame, 0)
        srcMac.copyInto(frame, 6)
        frame[12] = 0x86.toByte()
        frame[13] = 0xdd.toByte()
        ipv6.copyInto(frame, 14)
        return frame
    }

    private fun extractUdpPayload(frame: ByteArray, dstPort: Int): ByteArray? {
        if (frame.size < 62) return null
        val etherType = ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        if (etherType != 0x86dd) return null
        if ((frame[20].toInt() and 0xff) != 17) return null // UDP next header
        val port = ((frame[56].toInt() and 0xff) shl 8) or (frame[57].toInt() and 0xff)
        if (port != dstPort) return null
        val udpLen = ((frame[58].toInt() and 0xff) shl 8) or (frame[59].toInt() and 0xff)
        val payloadLen = udpLen - 8
        if (payloadLen <= 0 || 62 + payloadLen > frame.size) return ByteArray(0)
        return frame.copyOfRange(62, 62 + payloadLen)
    }

    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
}
