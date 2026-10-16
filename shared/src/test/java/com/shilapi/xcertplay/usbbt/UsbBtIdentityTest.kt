package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UsbBtIdentityTest {

    @Test
    fun testCarPlayAndIap2UuidsInEir() {
        val identity = UsbBtIdentity(name = "DiPlay-CarPlay")
        val eir = identity.extendedInquiryResponse(txPowerLevel = 4)

        // EIR is composed of TLV chunks: [length (1 byte), tag (1 byte), data...]
        var pos = 0
        var foundTag6 = false
        var foundTag7 = false
        var foundTag9 = false
        var foundTag10 = false

        while (pos < eir.size) {
            val len = eir[pos].toInt() and 0xFF
            if (len == 0) break
            val tag = eir[pos + 1].toInt() and 0xFF
            val data = eir.copyOfRange(pos + 2, pos + 1 + len)
            when (tag) {
                UsbBtIdentity.EIR_INCOMPLETE_128_BIT_UUIDS -> {
                    foundTag6 = true
                    // Must contain 32 bytes: iAP2 accessory UUID in big-endian (16) + little-endian (16)
                    assertEquals(32, data.size)
                    val be = uuidBigEndian(UsbBtIdentity.IAP2_ACCESSORY_UUID)
                    val le = uuidLittleEndian(UsbBtIdentity.IAP2_ACCESSORY_UUID)
                    assertTrue((be + le).contentEquals(data))
                }
                UsbBtIdentity.EIR_COMPLETE_128_BIT_UUIDS -> {
                    foundTag7 = true
                    // Must contain 16 bytes: CarPlay UUID in little-endian
                    assertEquals(16, data.size)
                    val le = uuidLittleEndian(UsbBtIdentity.CARPLAY_UUID)
                    assertTrue(le.contentEquals(data))
                }
                UsbBtIdentity.EIR_COMPLETE_LOCAL_NAME -> {
                    foundTag9 = true
                    assertEquals("DiPlay-CarPlay", String(data, Charsets.UTF_8))
                }
                UsbBtIdentity.EIR_TX_POWER_LEVEL -> {
                    foundTag10 = true
                    assertEquals(1, data.size)
                    assertEquals(4, data[0].toInt())
                }
            }
            pos += 1 + len
        }

        assertTrue("Tag 6 (iAP2 UUID) must be in EIR", foundTag6)
        assertTrue("Tag 7 (CarPlay UUID) must be in EIR", foundTag7)
        assertTrue("Tag 9 (Complete Local Name) must be in EIR", foundTag9)
        assertTrue("Tag 10 (Tx Power) must be in EIR", foundTag10)
    }

    @Test
    fun testLongNameTruncation() {
        val longName = "A".repeat(300)
        val identity = UsbBtIdentity(name = longName)
        val eir = identity.extendedInquiryResponse()

        assertTrue(eir.size <= 240)
        // Should contain Shortened Local Name (tag 8)
        var pos = 0
        var foundTag8 = false
        while (pos < eir.size) {
            val len = eir[pos].toInt() and 0xFF
            if (len == 0) break
            val tag = eir[pos + 1].toInt() and 0xFF
            if (tag == UsbBtIdentity.EIR_SHORTENED_LOCAL_NAME) {
                foundTag8 = true
            }
            pos += 1 + len
        }
        assertTrue("Long names should fallback to Shortened Local Name (tag 8)", foundTag8)
    }

    @Test
    fun testClassOfDeviceDefault() {
        val identity = UsbBtIdentity(name = "Car")
        // Default handsfree car audio class of device: 0x200408 (2098184)
        assertEquals(0x200408, identity.classOfDevice)
    }
}
