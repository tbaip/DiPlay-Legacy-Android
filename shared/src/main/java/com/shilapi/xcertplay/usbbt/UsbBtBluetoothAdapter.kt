package com.shilapi.xcertplay.usbbt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import android.util.Log
import com.shilapi.xcertplay.bluetooth.CarPlayBluetooth
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothDevice
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_CONNECTION_COMPLETE
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_CONNECTION_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_DISCONNECTION_COMPLETE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_ACCEPT_CONNECTION_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_CREATE_CONNECTION
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_DISCONNECT
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeAcceptConnection
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeBdAddr
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeCreateConnection
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeDisconnect
import com.shilapi.xcertplay.usbbt.HciProtocol.parseConnectionComplete
import com.shilapi.xcertplay.usbbt.HciProtocol.parseConnectionRequest
import com.shilapi.xcertplay.usbbt.HciProtocol.parseDisconnectionComplete
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CarPlayBluetooth] implementation backed by a USB Bluetooth dongle driven
 * through the Android USB Host API (Phase 5).
 *
 * Lifecycle (all blocking calls belong on a background thread):
 * - [isSupported]: a dongle is plugged in (USB device scan)
 * - [enable]: request USB permission if needed, open HCI, initialize,
 *   start the pairing agent
 * - [openRfcommStream]: HCI Create_Connection -> L2CAP (SDP query for the
 *   iAP2 channel, fallback to channel 1) -> RFCOMM DLC -> byte stream
 *
 * Threading: a single event-pump thread routes HCI events (connection
 * completions) and ACL data to the L2CAP layer. Public methods are
 * thread-safe.
 *
 * Clean-room: integration logic only; protocols from the public spec.
 */
class UsbBtBluetoothAdapter(
    private val appContext: Context,
) : CarPlayBluetooth {

    companion object {
        private const val TAG = "UsbBtBluetoothAdapter"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val FALLBACK_IAP2_CHANNEL = 1
    }

    private val usbManager: UsbManager? =
        runCatching {
            appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        }.getOrNull()
    private val detector = UsbBtDongleDetector(appContext)
    private val linkKeys = LinkKeyStore(appContext)

    /** Guard for lifecycle transitions. */
    private val lock = Any()

    @Volatile private var hci: UsbBtHci? = null
    private var pairingAgent: UsbBtPairingAgent? = null
    @Volatile private var activeDevice: UsbDevice? = null
    @Volatile private var localBdAddr: String? = null
    private var dongleLabel: String? = null

    /** BD_ADDR -> pending ACL connection latch. */
    private val pendingAcl = ConcurrentHashMap<String, PendingAcl>()

    /** ACL handle -> L2CAP layer (one per peer connection). */
    private val l2capLayers = ConcurrentHashMap<Int, L2capLayer>()

    private data class PendingAcl(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var handle: Int = -1,
        @Volatile var status: Int = -1,
    )

    private val pumpRunning = AtomicBoolean(false)
    private var pumpThread: Thread? = null

    /** USB permission / detach receivers; registered on first enable() attempt. */
    private var usbReceivers: Closeable? = null

    /** Set when enable() is waiting for the user to grant USB permission. */
    private val awaitingPermission = AtomicBoolean(false)

    /** BD_ADDR (uppercase) -> ACL handle for established connections. */
    private val aclHandleByAddr = ConcurrentHashMap<String, Int>()

    /** ACL handle -> active user count (RFCOMM streams sharing one ACL). */
    private val aclRefCount = ConcurrentHashMap<Int, AtomicInteger>()

    /** BD_ADDRs in most-recently-connected order (for getConnectedDevice). */
    private val connectedOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()

    // ------------------------------------------------------------------
    // CarPlayBluetooth
    // ------------------------------------------------------------------

    override fun isSupported(): Boolean {
        val mgr = usbManager ?: return false
        return runCatching { detector.findDongles(mgr).isNotEmpty() }.getOrDefault(false)
    }

    override fun isEnabled(): Boolean = hci != null && pumpRunning.get()

    override fun getAdapterName(): String = "UsbBt(dongle${dongleLabel?.let { ":$it" } ?: ""})"

    /**
     * Bring up the dongle: permission -> open -> initialize -> pairing agent.
     * Returns true when the HCI layer is ready.
     *
     * If USB permission is not yet granted, requests it and returns false; the
     * permission broadcast receiver (registered here) automatically retries
     * enable() on a background thread once the user grants access.
     */
    override fun enable(): Boolean = synchronized(lock) {
        if (isEnabled()) return true
        ensureUsbReceivers()
        val mgr = usbManager ?: return false
        val candidates = runCatching { detector.findDongles(mgr) }.getOrElse { emptyList() }
        if (candidates.isEmpty()) {
            Log.w(TAG, "enable: no dongle found")
            return false
        }

        // Try candidate dongles that already have permission first.
        val (granted, ungranted) = candidates.partition { mgr.hasPermission(it.device) }

        for (dongle in granted) {
            val opened = UsbBtHci.open(mgr, dongle.device)
            if (opened == null) {
                Log.w(TAG, "enable: UsbBtHci.open failed for ${dongle.vendorLabel}")
                continue
            }
            val firmwareLoader = UsbBtFirmwareLoader(opened, appContext.assets)
            when (val init = opened.initialize(firmwareLoader = firmwareLoader)) {
                is UsbBtHci.HciResult.Failed -> {
                    Log.w(TAG, "enable: HCI init failed for ${dongle.vendorLabel}: ${init.reason}")
                    runCatching { opened.close() }
                    continue
                }
                is UsbBtHci.HciResult.Ok -> {
                    activeDevice = dongle.device
                    localBdAddr = init.value.bdAddr
                    dongleLabel = dongle.vendorLabel
                    initSteps = init.value.steps
                    hci = opened
                    pairingAgent = UsbBtPairingAgent(opened, linkKeys, pairingUiCallback).also { it.start() }
                    startPump(opened)
                    awaitingPermission.set(false)
                    val enableMsg = "USB Bluetooth enabled: bdAddr=$localBdAddr vendor=${dongle.vendorLabel} advertising=DiPlay-${localBdAddr?.takeLast(5)?.replace(":", "") ?: "BT"}"
                    UsbBtLog.log(appContext, TAG, enableMsg)
                    return true
                }
            }
        }

        // If none of the permitted devices could be initialized, request permission for the best ungranted candidate.
        if (ungranted.isNotEmpty()) {
            val target = ungranted.first()
            if (awaitingPermission.compareAndSet(false, true)) {
                val reqMsg = "enable: requesting USB permission for candidate: ${target.vendorLabel}"
                UsbBtLog.log(appContext, TAG, reqMsg)
                detector.ensurePermission(mgr, target)
            }
            return false
        }

        UsbBtLog.warn(appContext, TAG, "enable: all candidate dongles failed to open/initialize")
        return false
    }

    /** HCI init step log, exposed for diagnostics. */
    @Volatile private var initSteps: List<String> = emptyList()

    /** Optional UI hook for Numeric Comparison pairing (item 2). Null = Just Works. */
    @Volatile var pairingUiCallback: UsbBtPairingAgent.PairingUiCallback? = null

    /**
     * Register USB permission + device-detach receivers once. The permission
     * receiver retries enable() in the background after the user grants access;
     * the detach receiver shuts the stack down when the dongle is unplugged.
     */
    private fun ensureUsbReceivers() {
        if (usbReceivers != null) return
        val permissionAction = UsbBtDongleDetector.permissionAction(appContext.packageName)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    permissionAction -> {
                        val device = if (realSdkInt() >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                        }
                        val granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false,
                        )
                        Log.i(TAG, "USB permission result: granted=$granted device=$device")
                        if (granted && device != null &&
                            detector.identify(device) != null
                        ) {
                            // Retry on a background thread; enable() blocks.
                            Thread({
                                runCatching { enable() }
                                    .onFailure { Log.w(TAG, "permission retry enable failed", it) }
                            }, "UsbBtPermissionRetry").apply {
                                isDaemon = true
                                start()
                            }
                        } else {
                            awaitingPermission.set(false)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = if (realSdkInt() >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                        }
                        if (device != null) {
                            val currentActive = synchronized(lock) { activeDevice }
                            val isTargetDongle = if (currentActive != null) {
                                device.deviceId == currentActive.deviceId || device.deviceName == currentActive.deviceName
                            } else {
                                detector.identify(device) != null
                            }
                            if (isTargetDongle) {
                                Log.i(TAG, "dongle detached (device=${device.deviceName}); shutting down USB Bluetooth")
                                shutdown()
                            } else {
                                Log.i(TAG, "non-active USB peripheral detached (device=${device.deviceName}); ignoring")
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val registered = if (realSdkInt() >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        usbReceivers = Closeable {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        // Keep a reference so lint does not flag the unused value; the Closeable
        // is released in shutdown().
        if (registered == null) Log.w(TAG, "receiver registration returned null")
    }

    override fun localAddress(context: Context): String? = synchronized(lock) { localBdAddr }

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        // Paired = we hold a link key. Names are unknown without inquiry;
        // the address is the stable identifier.
        return linkKeys.peers().map { addr ->
            CarPlayBluetoothDevice(name = "iPhone ($addr)", address = addr, isConnected = false)
        }
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        val addr = connectedOrder.peekLast() ?: return null
        return CarPlayBluetoothDevice(name = "iPhone ($addr)", address = addr, isConnected = true)
    }

    /**
     * Open an RFCOMM stream to [deviceAddress] for the iAP2 service ([uuid]
     * is accepted for interface compatibility; the channel comes from SDP).
     *
     * ACL connections are shared per peer: opening a second stream to an
     * already-connected BD_ADDR reuses the ACL (refcounted).
     */
    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        val h = synchronized(lock) { hci } ?: throw IOException("USB Bluetooth not enabled")
        if (!isEnabled()) throw IOException("USB Bluetooth not enabled")

        // 1) ACL connection (reused when already up for this peer).
        val handle = createAclConnection(h, deviceAddress)
        val freshAcl = aclRefCount[handle]?.get() == 1
        try {
            // 2) L2CAP layer on this handle + ACL receive pump hookup.
            // One L2CAP instance per ACL handle, shared by all streams on it.
            val l2cap = getOrCreateL2cap(handle, h)

            // 3) SDP: find the iAP2 RFCOMM channel.
            val sdp = SdpClient(l2cap, handle)
            val channel = when (val r = sdp.findIap2RfcommChannel()) {
                is SdpClient.SdpResult.Ok -> r.value
                is SdpClient.SdpResult.Failed -> {
                    Log.w(TAG, "SDP failed (${r.reason}); falling back to channel $FALLBACK_IAP2_CHANNEL")
                    FALLBACK_IAP2_CHANNEL
                }
            }
            sdp.close()

            // 4) RFCOMM DLC to the channel.
            val rfcomm = RfcommLayer(l2cap, when (val r = l2cap.connect(HciProtocol.L2CAP_PSM_RFCOMM)) {
                is L2capLayer.L2capResult.Ok -> r.value
                is L2capLayer.L2capResult.Failed ->
                    throw IOException("RFCOMM L2CAP connect failed: ${r.reason}")
            })
            val dlc = when (val r = rfcomm.openDlc(channel)) {
                is RfcommLayer.RfcommResult.Ok -> r.value
                is RfcommLayer.RfcommResult.Failed ->
                    throw IOException("RFCOMM DLC open failed: ${r.reason}")
            }

            // 5) Byte-stream facade.
            val stream = UsbBtRfcommStream(
                rfcomm = rfcomm,
                dlc = dlc,
                onClose = { teardown(handle) },
            )
            UsbBtLog.log(appContext, TAG, "openRfcommStream: connected successfully to $deviceAddress (handle=0x${handle.toString(16)}, channel=$channel)")
            return stream
        } catch (t: Throwable) {
            UsbBtLog.warn(appContext, TAG, "openRfcommStream failed for $deviceAddress", t)
            // Only tear down the ACL when we created it for this call.
            if (freshAcl) teardown(handle) else releaseAclRef(handle)
            throw t
        }
    }

    // ------------------------------------------------------------------
    // ACL connection management (multi-peer, refcounted).
    // ------------------------------------------------------------------

    /**
     * Get or establish the ACL connection to [bdAddr]. Returns the handle and
     * bumps its refcount; callers must pair every success with [releaseAclRef]
     * (via [teardown]).
     */
    private fun createAclConnection(h: UsbBtHci, bdAddr: String): Int {
        val key = bdAddr.uppercase()
        aclHandleByAddr[key]?.let { existing ->
            aclRefCount.getOrPut(existing) { AtomicInteger(0) }.incrementAndGet()
            Log.i(TAG, "ACL reused: $bdAddr handle=0x${existing.toString(16)}")
            return existing
        }
        val addrBytes = encodeBdAddr(bdAddr)
            ?: throw IOException("malformed BD_ADDR: $bdAddr")
        val pending = PendingAcl()
        pendingAcl[key] = pending
        try {
            when (val r = h.sendCommand(OP_CREATE_CONNECTION, encodeCreateConnection(addrBytes))) {
                is UsbBtHci.HciResult.Failed ->
                    throw IOException("Create_Connection failed: ${r.reason}")
                is UsbBtHci.HciResult.Ok -> { /* Command Status ok; completion follows */ }
            }
            if (!pending.latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("ACL connection timeout to $bdAddr")
            }
            if (pending.status != 0) {
                throw IOException(
                    "ACL connection failed to $bdAddr status=0x${pending.status.toString(16)}",
                )
            }
            val handle = pending.handle
            aclHandleByAddr[key] = handle
            aclRefCount.getOrPut(handle) { AtomicInteger(0) }.incrementAndGet()
            connectedOrder.remove(key)
            connectedOrder.addLast(key)
            Log.i(TAG, "ACL up: $bdAddr handle=0x${handle.toString(16)}")
            return handle
        } finally {
            pendingAcl.remove(key)
        }
    }

    /** Get or create L2capLayer on [handle], registering the SDP server with CarPlay/iAP2 records. */
    private fun getOrCreateL2cap(handle: Int, h: UsbBtHci): L2capLayer {
        l2capLayers[handle]?.let { return it }
        return synchronized(l2capLayers) {
            l2capLayers[handle] ?: run {
                val l2cap = L2capLayer(aclHandle = handle, hci = h)
                val identity = UsbBtIdentity(
                    name = "DiPlay",
                )
                l2cap.registerSdpServer(SdpCodec.defaultRecords(identity))
                l2capLayers[handle] = l2cap
                l2cap
            }
        }
    }

    /** Release one user of [handle]; disconnects HCI only when the last user goes. */
    private fun releaseAclRef(handle: Int) {
        val remaining = aclRefCount[handle]?.decrementAndGet() ?: 0
        if (remaining <= 0) {
            aclRefCount.remove(handle)
            // Find and drop the BD_ADDR mapping for this handle.
            aclHandleByAddr.entries.find { it.value == handle }?.let { entry ->
                aclHandleByAddr.remove(entry.key)
                connectedOrder.remove(entry.key)
            }
            l2capLayers.remove(handle)?.let { runCatching { it.close() } }
            val h = synchronized(lock) { hci } ?: return
            runCatching { h.sendCommand(OP_DISCONNECT, encodeDisconnect(handle)) }
            Log.i(TAG, "ACL down: handle=0x${handle.toString(16)}")
        }
    }

    private fun onPeerDisconnected(handle: Int) {
        aclRefCount.remove(handle)
        aclHandleByAddr.entries.find { it.value == handle }?.let { entry ->
            aclHandleByAddr.remove(entry.key)
            connectedOrder.remove(entry.key)
        }
        l2capLayers.remove(handle)?.let { runCatching { it.close() } }
        Log.i(TAG, "ACL down (peer disconnected): handle=0x${handle.toString(16)}")
    }

    private fun teardown(handle: Int) = releaseAclRef(handle)

    // ------------------------------------------------------------------
    // Event / ACL pump.
    // ------------------------------------------------------------------

    private fun startPump(h: UsbBtHci) {
        if (!pumpRunning.compareAndSet(false, true)) return
        pumpThread = Thread({
            while (pumpRunning.get()) {
                // HCI events (connection completions, inbound connection requests, pairing, etc.).
                val event = runCatching { h.pollEvent(200) }.getOrNull()
                if (event != null) {
                    pairingAgent?.onEvent(event)
                    when (event.code) {
                        EVT_CONNECTION_REQUEST -> {
                            val req = parseConnectionRequest(event)
                            if (req != null) {
                                Log.i(TAG, "incoming connection request from ${req.bdAddr} linkType=${req.linkType}")
                                if (req.linkType == 1) { // ACL
                                    val acceptCmd = encodeAcceptConnection(req.rawAddr)
                                    val res = h.sendCommand(OP_ACCEPT_CONNECTION_REQUEST, acceptCmd)
                                    Log.i(TAG, "accept connection result for ${req.bdAddr}: $res")
                                }
                            }
                        }
                        EVT_CONNECTION_COMPLETE -> {
                            val cc = parseConnectionComplete(event)
                            if (cc != null && cc.bdAddr != null) {
                                val key = cc.bdAddr.uppercase()
                                if (cc.status == 0) {
                                    aclHandleByAddr[key] = cc.handle
                                    connectedOrder.remove(key)
                                    connectedOrder.addLast(key)
                                    getOrCreateL2cap(cc.handle, h)
                                    Log.i(TAG, "ACL up: $key handle=0x${cc.handle.toString(16)}")
                                } else {
                                    Log.w(TAG, "ACL connection failed: $key status=0x${cc.status.toString(16)}")
                                }
                                pendingAcl[key]?.let { pending ->
                                    pending.handle = cc.handle
                                    pending.status = cc.status
                                    pending.latch.countDown()
                                }
                            }
                        }
                        EVT_DISCONNECTION_COMPLETE -> {
                            val dc = parseDisconnectionComplete(event)
                            if (dc != null && dc.status == 0) {
                                onPeerDisconnected(dc.handle)
                            }
                        }
                    }
                }
                // ACL data: route to the owning L2CAP layer.
                val acl = runCatching { h.pollAcl(200) }.getOrNull()
                if (acl != null) {
                    l2capLayers[acl.handle]?.onAclData(acl.data)
                }
            }
        }, "UsbBtPump").apply {
            isDaemon = true
            start()
        }
    }

    /** Shut the adapter down; idempotent. */
    fun shutdown() {
        pumpRunning.set(false)
        pumpThread?.interrupt()
        pumpThread = null
        pairingAgent?.stop()
        pairingAgent = null
        runCatching { usbReceivers?.close() }
        usbReceivers = null
        awaitingPermission.set(false)
        synchronized(lock) {
            hci?.let { runCatching { it.close() } }
            hci = null
            activeDevice = null
            localBdAddr = null
            initSteps = emptyList()
        }
        aclHandleByAddr.keys.toList().forEach { addr ->
            aclHandleByAddr.remove(addr)?.let { handle ->
                aclRefCount.remove(handle)
                l2capLayers.remove(handle)?.let { runCatching { it.close() } }
            }
        }
        aclRefCount.clear()
        connectedOrder.clear()
        pendingAcl.clear()
        UsbBtLog.log(appContext, TAG, "USB Bluetooth shut down")
    }

    /** Snapshot for diagnostics (LegacyConnectionProbe). */
    fun diagnosticState(): String {
        val dongle = synchronized(lock) {
            activeDevice?.let { dev -> detector.identify(dev) }
        } ?: runCatching {
            usbManager?.let { detector.findDongles(it).firstOrNull() }
        }.getOrNull()
        return buildString {
            append("dongle=")
            append(dongle?.let { "vid=0x${it.device.vendorId.toString(16)} pid=0x${it.device.productId.toString(16)} ${it.vendorLabel}" } ?: "none")
            append(" hciUp=${hci != null}")
            append(" initSteps=${initSteps.size}")
            append(" aclCount=${aclHandleByAddr.size}")
            append(" peers=[${aclHandleByAddr.keys.joinToString(",")}]")
            append(" linkKeys=${linkKeys.peers().size}")
            append(" awaitingPermission=${awaitingPermission.get()}")
        }
    }

    data class StatusInfo(
        val isSupported: Boolean,
        val isEnabled: Boolean,
        val device: UsbDevice?,
        val vendorLabel: String?,
        val hasPermission: Boolean,
        val localBdAddr: String?,
        val aclCount: Int,
        val peers: List<String>,
        val awaitingPermission: Boolean,
    )

    fun getStatus(): StatusInfo {
        val mgr = usbManager
        // UI reads must never wait for enable()'s USB initialization/retry lock.
        val dongle = activeDevice?.let { detector.identify(it) } ?: runCatching {
            mgr?.let { detector.findDongles(it).firstOrNull() }
        }.getOrNull()
        val dev = dongle?.device
        val hasPerm = if (mgr != null && dev != null) mgr.hasPermission(dev) else false
        return StatusInfo(
            isSupported = isSupported(),
            isEnabled = isEnabled(),
            device = dev,
            vendorLabel = dongle?.vendorLabel,
            hasPermission = hasPerm,
            localBdAddr = localBdAddr,
            aclCount = aclHandleByAddr.size,
            peers = aclHandleByAddr.keys.toList(),
            awaitingPermission = awaitingPermission.get(),
        )
    }
}

/**
 * [BlockingDuplexByteStream] over an RFCOMM DLC.
 *
 * Inbound UIH frames are reassembled: [recv] returns up to [maxBytes] from an
 * internal buffer and keeps the remainder for the next call, so callers never
 * lose the tail of a frame larger than their read size.
 */
private class UsbBtRfcommStream(
    private val rfcomm: RfcommLayer,
    private val dlc: RfcommLayer.Dlc,
    private val onClose: () -> Unit,
) : BlockingDuplexByteStream {

    private val inbound = LinkedBlockingQueue<ByteArray>(64)
    private val closed = AtomicBoolean(false)

    /** Reassembly buffer for frames larger than one recv() call. Guarded by itself. */
    private val reassembly = java.io.ByteArrayOutputStream()

    init {
        rfcomm.onData(dlc) { data ->
            // Drop when the app is not draining; never block the BT thread.
            inbound.offer(data)
        }
    }

    override fun send(data: ByteArray) {
        if (closed.get()) throw IOException("stream closed")
        if (!rfcomm.send(dlc, data)) throw IOException("RFCOMM send failed")
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        if (closed.get()) return ByteArray(0)
        synchronized(reassembly) {
            if (reassembly.size() > 0) return takeFromReassembly(maxBytes)
        }
        val chunk = inbound.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return null
        synchronized(reassembly) {
            reassembly.write(chunk, 0, chunk.size)
            return takeFromReassembly(maxBytes)
        }
    }

    /** Take up to [maxBytes] from the reassembly buffer, keeping the rest. */
    private fun takeFromReassembly(maxBytes: Int): ByteArray {
        val buf = reassembly.toByteArray()
        return if (buf.size <= maxBytes) {
            reassembly.reset()
            buf
        } else {
            val out = buf.copyOf(maxBytes)
            reassembly.reset()
            reassembly.write(buf, maxBytes, buf.size - maxBytes)
            out
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { rfcomm.close() }
        runCatching { onClose() }
    }
}
