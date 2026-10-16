package com.shilapi.xcertplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LwipNcmNativeTest {

    @Test
    fun nativeBridgeGracefullyHandlesMissingNativeLibraryOnHostJvm() {
        var txFrameReceived: ByteArray? = null
        val bridge = LwipNcmNative(
            onTxFrameCallback = { frame -> txFrameReceived = frame },
        )

        // On host JVM, native library is not present, so create returns false safely without throwing
        val created = bridge.create(byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01), "fe80::2")
        assertFalse(created)

        // Subsequent calls with nativeHandle == 0 must be safe no-ops
        bridge.registerPort(7000, isTcp = true)
        bridge.inputFrame(byteArrayOf(0x01, 0x02, 0x03))
        bridge.destroy()

        // Callback can be triggered directly from JNI worker thread
        val testPayload = byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte())
        bridge.onTxFrame(testPayload)
        assertArrayEquals(testPayload, txFrameReceived)
    }
}
