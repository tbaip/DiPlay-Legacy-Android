package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.CountDownTimer
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.bluetooth.HybridBluetoothAdapter
import com.shilapi.xcertplay.usbbt.UsbBtPairingAgent
import java.lang.ref.WeakReference

/**
 * Application-level router: delivers USB-Bluetooth Numeric Comparison
 * pairing requests from the background pairing agent to whichever Activity
 * is currently in the foreground.
 *
 * Wiring: the host Activity registers itself in onResume() and unregisters
 * in onPause(). [UsbBtBluetoothAdapter] installs a [PairingUiCallback] that
 * calls [route] — the dialog is then shown on the UI thread of the current
 * Activity. If no Activity is registered the request is declined
 * (negative reply) so pairing does not hang.
 */
object UsbBtPairingUiRouter {
    private const val TAG = "UsbBtPairingUi"

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun register(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unregister(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    /**
     * Show the Numeric Comparison dialog. Must be called from any thread;
     * the dialog itself runs on the Activity's UI thread. [confirm] is
     * invoked exactly once with the user's decision (false on cancel,
     * timeout, or when no Activity is available).
     */
    fun route(bdAddr: String, sixDigitNumber: Int, confirm: (Boolean) -> Unit) {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing) {
            Log.w(TAG, "no foreground activity; declining pairing for $bdAddr")
            confirm(false)
            return
        }
        activity.runOnUiThread {
            if (activity.isFinishing) {
                confirm(false)
                return@runOnUiThread
            }
            UsbBtPairingDialog.show(activity, bdAddr, sixDigitNumber, confirm)
        }
    }
    /**
     * Install the Numeric Comparison UI on a [HybridBluetoothAdapter]'s USB
     * dongle stack. After this, pairing requests are routed to the
     * foreground Activity via [UsbBtPairingUiRouter]; the Activity must call
     * [UsbBtPairingUiRouter.register]/[unregister] in onResume/onPause.
     * Idempotent.
     */
    fun installOn(hybrid: HybridBluetoothAdapter) {
        val dongle = hybrid.usbDongle ?: return
        if (dongle.pairingUiCallback != null) return
        dongle.pairingUiCallback = object : UsbBtPairingAgent.PairingUiCallback {
            override fun onNumericComparison(
                bdAddr: String,
                sixDigitNumber: Int,
                confirm: (accepted: Boolean) -> Unit,
            ) {
                // Called on the pairing agent's background thread; the router
                // hops to the UI thread and guarantees exactly-once confirm.
                UsbBtPairingUiRouter.route(bdAddr, sixDigitNumber, confirm)
            }
        }
        Log.i(TAG, "USB-BT pairing UI installed")
    }
}

/**
 * Numeric Comparison pairing confirmation dialog.
 *
 * Shows the 6-digit number the user must compare with the iPhone's screen,
 * plus Confirm/Cancel buttons and a 30-second countdown. Exactly one of
 * confirm(true)/confirm(false) is delivered.
 */
object UsbBtPairingDialog {
    private const val TAG = "UsbBtPairingDialog"
    const val COUNTDOWN_MS = 30_000L

    fun show(
        activity: Activity,
        bdAddr: String,
        sixDigitNumber: Int,
        confirm: (Boolean) -> Unit,
    ) {
        var decided = false
        fun decide(ok: Boolean) {
            if (!decided) {
                decided = true
                runCatching { confirm(ok) }
                    .onFailure { Log.w(TAG, "pairing confirm callback failed", it) }
            }
        }

        val number = "%06d".format(sixDigitNumber)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val messageView = TextView(activity).apply {
            text = "Bluetooth pairing request from $bdAddr.\n" +
                "Confirm the number matches your iPhone:"
            textSize = 15f
        }
        val numberView = TextView(activity).apply {
            text = number
            textSize = 40f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 24, 0, 8)
        }
        val countdownView = TextView(activity).apply {
            textSize = 13f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        layout.addView(messageView)
        layout.addView(numberView)
        layout.addView(countdownView)

        var dialog: AlertDialog? = null
        val timer = object : CountDownTimer(COUNTDOWN_MS, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownView.text = "Auto-decline in ${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                decide(false)
                runCatching { dialog?.dismiss() }
            }
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("Confirm Bluetooth pairing")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                timer.cancel()
                decide(true)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                timer.cancel()
                decide(false)
            }
            .setOnCancelListener {
                timer.cancel()
                decide(false)
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        timer.start()
        Log.i(TAG, "pairing dialog shown for $bdAddr number=$number")
    }
}
