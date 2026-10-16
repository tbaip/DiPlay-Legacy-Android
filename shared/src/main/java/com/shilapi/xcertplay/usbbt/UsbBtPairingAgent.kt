package com.shilapi.xcertplay.usbbt

import android.util.Log
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_IO_CAPABILITY_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_LINK_KEY_NOTIFICATION
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_SIMPLE_PAIRING_COMPLETE
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_USER_CONFIRMATION_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.IO_CAP_DISPLAY_YES_NO
import com.shilapi.xcertplay.usbbt.HciProtocol.IO_CAP_NO_INPUT_NO_OUTPUT
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_IO_CAPABILITY_REQUEST_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_USER_CONFIRMATION_REQUEST_REPLY
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Secure Simple Pairing agent for the USB Bluetooth dongle (Phase 4).
 *
 * Two modes, selected by [uiCallback]:
 * - null (default): "Just Works" — IO capability NoInputNoOutput, pairing
 *   auto-confirmed. Appropriate for a head unit with no trusted display.
 * - non-null: Numeric Comparison — IO capability DisplayYesNo; the 6-digit
 *   number from User_Confirmation_Request is delivered to [PairingUiCallback]
 *   and the user's accept/reject decision is sent back. On UI timeout the
 *   agent falls back to auto-confirm (Just Works).
 *
 * Pairing is triggered by the peer (iPhone) connecting; this agent only
 * answers. Start via [start], stop via [stop]. Thread-safe.
 *
 * Clean-room: SSP flow per Bluetooth Core Spec Vol 2, Part E.
 */
class UsbBtPairingAgent(
    private val hci: UsbBtHci,
    private val linkKeys: LinkKeyStore,
    private val uiCallback: PairingUiCallback? = null,
) {

    companion object {
        private const val TAG = "UsbBtPairingAgent"

        /** How long to wait for the UI decision before falling back to auto-confirm. */
        private const val UI_DECISION_TIMEOUT_MS = 30_000L

        /**
         * Extract the 6-digit Numeric Comparison value from a
         * User_Confirmation_Request event: params = BD_ADDR(6) |
         * numeric_value(4 LE). Returns null when params are too short.
         * Pure function, unit-tested.
         */
        fun extractSixDigitNumber(params: ByteArray): Int? {
            if (params.size < 10) return null
            val raw = (params[6].toInt() and 0xFF) or
                ((params[7].toInt() and 0xFF) shl 8) or
                ((params[8].toInt() and 0xFF) shl 16) or
                ((params[9].toInt() and 0xFF) shl 24)
            return ((raw.toLong() and 0xFFFFFFFFL) % 1_000_000).toInt()
        }
    }

    /**
     * UI hook for Numeric Comparison pairing. Called on the pairing agent's
     * background thread; implementations must not block it indefinitely —
     * [onNumericComparison] should return promptly after showing the UI and
     * invoke [confirm] from the UI thread when the user decides.
     */
    interface PairingUiCallback {
        fun onNumericComparison(bdAddr: String, sixDigitNumber: Int, confirm: (accepted: Boolean) -> Unit)
    }

    private val running = AtomicBoolean(false)
    private var executor: java.util.concurrent.ExecutorService? = null

    /** Start the pairing agent. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "UsbBtPairing").apply { isDaemon = true }
        }
        Log.i(TAG, "pairing agent started")
    }

    fun stop() {
        if (!running.compareAndSet(false, true)) return
        executor?.shutdownNow()
        executor = null
        Log.i(TAG, "pairing agent stopped")
    }

    /**
     * Dispatch an incoming HCI event to the pairing agent.
     * Asynchronously processed on the pairing worker thread to avoid blocking the HCI pump.
     */
    fun onEvent(event: HciProtocol.HciEvent) {
        if (!running.get()) return
        when (event.code) {
            EVT_IO_CAPABILITY_REQUEST,
            EVT_USER_CONFIRMATION_REQUEST,
            EVT_LINK_KEY_NOTIFICATION,
            EVT_SIMPLE_PAIRING_COMPLETE -> {
                executor?.execute {
                    runCatching { handleEvent(event) }
                        .onFailure { Log.w(TAG, "pairing event handler failed", it) }
                }
            }
        }
    }

    private fun handleEvent(event: HciProtocol.HciEvent) {
        when (event.code) {
            EVT_IO_CAPABILITY_REQUEST -> onIoCapabilityRequest(event)
            EVT_USER_CONFIRMATION_REQUEST -> onUserConfirmationRequest(event)
            EVT_LINK_KEY_NOTIFICATION -> onLinkKeyNotification(event)
            EVT_SIMPLE_PAIRING_COMPLETE -> onSimplePairingComplete(event)
            else -> { /* not pairing-related; ignore */ }
        }
    }

    private fun bdAddrFrom(event: HciProtocol.HciEvent): String? {
        if (event.params.size < 6) return null
        return (5 downTo 0).joinToString(":") { i ->
            "%02X".format(event.params[i])
        }
    }

    /**
     * IO_Capability_Request: params = BD_ADDR(6). Reply with DisplayYesNo when
     * a UI callback is present (Numeric Comparison), else NoInputNoOutput
     * (Just Works).
     */
    private fun onIoCapabilityRequest(event: HciProtocol.HciEvent) {
        val bdAddr = bdAddrFrom(event) ?: return
        val ioCap = if (uiCallback != null) IO_CAP_DISPLAY_YES_NO else IO_CAP_NO_INPUT_NO_OUTPUT
        // Reply params: BD_ADDR(6) | io_capability(1) | oob_data(1)=0 | auth_req(1)
        // auth_req: bit0 = MITM not required, bit2 = general bonding.
        val reply = event.params.copyOfRange(0, 6) +
            byteArrayOf(ioCap.toByte(), 0x00, 0x04)
        when (val r = hci.sendCommand(OP_IO_CAPABILITY_REQUEST_REPLY, reply)) {
            is UsbBtHci.HciResult.Ok ->
                Log.i(TAG, "IO capability replied ($ioCap) for $bdAddr")
            is UsbBtHci.HciResult.Failed ->
                Log.w(TAG, "IO capability reply failed for $bdAddr: ${r.reason}")
        }
    }

    /**
     * User_Confirmation_Request: params = BD_ADDR(6) | numeric_value(4 LE).
     * With a UI callback, deliver the 6-digit number and wait for the user's
     * decision (timeout -> auto-confirm). Without, auto-confirm (Just Works).
     */
    private fun onUserConfirmationRequest(event: HciProtocol.HciEvent) {
        val bdAddr = bdAddrFrom(event) ?: return
        val callback = uiCallback
        if (callback == null || event.params.size < 10) {
            autoConfirm(bdAddr, event)
            return
        }
        // Numeric value is a 32-bit LE integer; displayed as 6 decimal digits.
        val sixDigits = extractSixDigitNumber(event.params) ?: run {
            autoConfirm(bdAddr, event)
            return
        }
        val latch = CountDownLatch(1)
        var accepted = true // fallback on UI timeout: auto-confirm (Just Works)
        var decided = false
        Log.i(TAG, "Numeric Comparison for $bdAddr: %06d".format(sixDigits))
        runCatching {
            callback.onNumericComparison(bdAddr, sixDigits) { ok ->
                synchronized(latch) {
                    if (!decided) {
                        decided = true
                        accepted = ok
                        latch.countDown()
                    }
                }
            }
        }.onFailure { Log.w(TAG, "pairing UI callback failed; auto-confirming", it) }
        latch.await(UI_DECISION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val addrBytes = event.params.copyOfRange(0, 6)
        val opcode = if (accepted) {
            OP_USER_CONFIRMATION_REQUEST_REPLY
        } else {
            OP_USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY
        }
        when (val r = hci.sendCommand(opcode, addrBytes)) {
            is UsbBtHci.HciResult.Ok ->
                Log.i(TAG, "pairing ${if (accepted) "confirmed" else "rejected"} for $bdAddr")
            is UsbBtHci.HciResult.Failed ->
                Log.w(TAG, "confirmation reply failed for $bdAddr: ${r.reason}")
        }
    }

    /** Just Works: confirm without showing the value. */
    private fun autoConfirm(bdAddr: String, event: HciProtocol.HciEvent) {
        val reply = event.params.copyOfRange(0, 6)
        when (val r = hci.sendCommand(OP_USER_CONFIRMATION_REQUEST_REPLY, reply)) {
            is UsbBtHci.HciResult.Ok ->
                Log.i(TAG, "auto-confirmed pairing for $bdAddr (Just Works)")
            is UsbBtHci.HciResult.Failed ->
                Log.w(TAG, "auto-confirm failed for $bdAddr: ${r.reason}")
        }
    }

    /**
     * Link_Key_Notification: params = BD_ADDR(6) | link_key(16) | key_type(1).
     * Persist the key so future connections can skip pairing.
     */
    private fun onLinkKeyNotification(event: HciProtocol.HciEvent) {
        if (event.params.size < 23) {
            Log.w(TAG, "malformed Link_Key_Notification (${event.params.size} bytes)")
            return
        }
        val bdAddr = bdAddrFrom(event) ?: return
        val key = event.params.copyOfRange(6, 22)
        linkKeys.store(bdAddr, key)
    }

    private fun onSimplePairingComplete(event: HciProtocol.HciEvent) {
        if (event.params.size < 8) return
        val status = event.params[0].toInt() and 0xFF
        val bdAddr = bdAddrFrom(event) ?: "?"
        Log.i(
            TAG,
            if (status == 0) "pairing complete with $bdAddr"
            else "pairing FAILED with $bdAddr status=0x${status.toString(16)}",
        )
    }
}
