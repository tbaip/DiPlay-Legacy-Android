package com.shilapi.xcertplay.usbbt

import android.util.Log
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_BD_ADDR
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_BUFFER_SIZE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_LOCAL_VERSION
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_RESET
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_LOCAL_NAME
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_SCAN_ENABLE
import com.shilapi.xcertplay.usbbt.HciProtocol.SCAN_INQUIRY_AND_PAGE
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeLocalName
import com.shilapi.xcertplay.usbbt.HciProtocol.parseBdAddr

/**
 * Optional diagnostic mode: run the full HCI bring-up step by step and
 * produce a per-step timing report for real-device tuning.
 *
 * Usage (background thread): open the dongle with [UsbBtHci.open], then
 * `UsbBtTimingProbe(hci).run("DiPlay").toText()` and attach the text to the
 * diagnostic report. Unlike [UsbBtHci.initialize], the probe never performs
 * firmware recovery — it only measures.
 *
 * Clean-room: sequence per Bluetooth Core Spec Vol 4; timing policy per
 * [UsbBtTiming] (public references documented there).
 */
class UsbBtTimingProbe(private val hci: UsbBtHci) {

    companion object {
        private const val TAG = "UsbBtTimingProbe"
    }

    fun run(localName: String = "DiPlay"): UsbBtTiming.TimingReport {
        val steps = mutableListOf<UsbBtTiming.StepTiming>()
        val start = System.currentTimeMillis()

        fun cmd(
            name: String,
            policy: UsbBtTiming.RetryPolicy,
            opcode: Int,
            params: ByteArray = byteArrayOf(),
            note: (ByteArray) -> String? = { null },
        ) {
            val (result, timing) = UsbBtTiming.runTimed(name, policy) { timeout ->
                when (val r = hci.sendCommand(opcode, params, timeout)) {
                    is UsbBtHci.HciResult.Ok -> Result.success(r.value to note(r.value))
                    is UsbBtHci.HciResult.Failed -> Result.failure(RuntimeException(r.reason))
                }
            }
            steps.add(timing)
            val detail = (result.getOrNull() as? Pair<*, *>)?.second as? String
            Log.i(TAG, "$name: ${if (timing.success) "OK" else "FAIL"} " +
                "${timing.durationMs}ms attempts=${timing.attempts}${detail?.let { " $it" } ?: ""}")
        }

        cmd("HCI_Reset", UsbBtTiming.RESET_POLICY, OP_RESET)
        cmd("Read_Local_Version", UsbBtTiming.READ_POLICY, OP_READ_LOCAL_VERSION) { rp ->
            if (rp.size >= 8) {
                "hciVer=${rp[1].toInt() and 0xFF} mfr=${(rp[6].toInt() and 0xFF) or ((rp[7].toInt() and 0xFF) shl 8)}"
            } else null
        }
        cmd("Read_BD_ADDR", UsbBtTiming.READ_POLICY, OP_READ_BD_ADDR) { rp ->
            parseBdAddr(rp)?.let { "addr=$it" }
        }
        cmd("Read_Buffer_Size", UsbBtTiming.READ_POLICY, OP_READ_BUFFER_SIZE)
        cmd(
            "Write_Scan_Enable",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_SCAN_ENABLE,
            byteArrayOf(SCAN_INQUIRY_AND_PAGE.toByte()),
        )
        cmd(
            "Write_Local_Name",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_LOCAL_NAME,
            encodeLocalName(localName),
        )

        return UsbBtTiming.TimingReport(
            steps = steps,
            totalMs = System.currentTimeMillis() - start,
        )
    }
}
