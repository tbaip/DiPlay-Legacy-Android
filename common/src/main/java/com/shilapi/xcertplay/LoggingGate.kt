package com.shilapi.xcertplay

import android.content.Context

/**
 * App-wide switch for session log output (issue #186).
 *
 * When disabled, the session diagnostic funnel drops every line before formatting, redaction
 * or file IO, sparing old head units the CPU/IO cost of log collection. The flag is loaded once
 * per process start ([init] is called from the Application), so toggling the setting takes
 * effect after restarting the app, as the Settings copy states.
 */
object LoggingGate {
    @Volatile
    var enabled: Boolean = true
        private set

    fun init(context: Context) {
        enabled = AirPlayPersistence.loadDebugLogsEnabled(context)
    }
}
