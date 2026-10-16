package com.shilapi.xcertplay.usbbt

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class UsbBtLogTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun logsToBluetoothDiagnosticAndDiplayLogFiles() {
        val logDir = File(context.filesDir, "logs")
        logDir.mkdirs()
        val diplayLog = File(logDir, "diplay.log")
        diplayLog.writeText("existing header\n")

        UsbBtLog.log(context, "TestTag", "Bluetooth diagnostic event happened")

        val btLog = File(logDir, "bluetooth-diagnostic.log")
        assertTrue("bluetooth-diagnostic.log must exist", btLog.exists())
        val btText = btLog.readText()
        assertTrue("bluetooth-diagnostic.log must contain tag", btText.contains("[TestTag]"))
        assertTrue("bluetooth-diagnostic.log must contain message", btText.contains("Bluetooth diagnostic event happened"))

        val diplayText = diplayLog.readText()
        assertTrue("diplay.log must contain tag", diplayText.contains("[TestTag]"))
        assertTrue("diplay.log must contain message", diplayText.contains("Bluetooth diagnostic event happened"))
    }

    @Test
    fun handlesNullContextGracefully() {
        // Should not throw NPE or any exception
        UsbBtLog.log(null, "TestTag", "Event without context")
        UsbBtLog.warn(null, "TestTag", "Warn without context", RuntimeException("dummy"))
    }
}
