package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBtFirmwareTest {

    @Test
    fun parseSingleRecord() {
        // opcode 0xFC2E LE, len 0 -> [2E FC 00]
        val bytes = byteArrayOf(0x2E, 0xFC.toByte(), 0x00)
        val records = UsbBtFirmwareLoader.HcdParser.parse(bytes)!!
        assertEquals(1, records.size)
        assertEquals(0xFC2E, records[0].opcode)
        assertArrayEquals(byteArrayOf(), records[0].payload)
    }

    @Test
    fun parseMultipleRecords() {
        val bytes = byteArrayOf(
            0x2E, 0xFC.toByte(), 0x00,          // record 1: 0xFC2E, empty
            0x03, 0x0C, 0x01, 0x05,             // record 2: 0x0C03, payload [05]
            0x1A, 0x0C, 0x02, 0x03, 0x04,       // record 3: 0x0C1A, payload [03 04]
        )
        val records = UsbBtFirmwareLoader.HcdParser.parse(bytes)!!
        assertEquals(3, records.size)
        assertEquals(0xFC2E, records[0].opcode)
        assertEquals(0x0C03, records[1].opcode)
        assertArrayEquals(byteArrayOf(0x05), records[1].payload)
        assertEquals(0x0C1A, records[2].opcode)
        assertArrayEquals(byteArrayOf(0x03, 0x04), records[2].payload)
    }

    @Test
    fun parseTruncatedHeaderReturnsNull() {
        assertNull(UsbBtFirmwareLoader.HcdParser.parse(byteArrayOf(0x2E, 0xFC.toByte())))
        assertNull(UsbBtFirmwareLoader.HcdParser.parse(byteArrayOf(0x2E)))
    }

    @Test
    fun parseTruncatedPayloadReturnsNull() {
        // Claims len=4 but only 2 payload bytes present.
        val bytes = byteArrayOf(0x03, 0x0C, 0x04, 0x01, 0x02)
        assertNull(UsbBtFirmwareLoader.HcdParser.parse(bytes))
    }

    @Test
    fun parseEmptyFileGivesEmptyList() {
        val records = UsbBtFirmwareLoader.HcdParser.parse(byteArrayOf())!!
        assertTrue(records.isEmpty())
    }

    @Test fun vendorFamilyHintsDoNotTreatEveryUgreenDeviceAsRealtek() {
        assertEquals(UsbBtFirmwareLoader.Companion.ChipFamily.REALTEK, UsbBtFirmwareLoader.detectChipFamily(0x2b89, 0x6275))
        assertEquals(UsbBtFirmwareLoader.Companion.ChipFamily.OTHER, UsbBtFirmwareLoader.detectChipFamily(0x2b89, 0x3599))
        assertEquals(UsbBtFirmwareLoader.Companion.ChipFamily.OTHER, UsbBtFirmwareLoader.detectChipFamily(0x10d7, 0xb012))
    }

    @Test fun localOverridesNeverFallBackToAnotherDevice() {
        assertEquals(listOf("usbbt_fw/2b89_6275.bin"), UsbBtFirmwareLoader.getCandidateFirmwareAssets(0x2b89, 0x6275))
        assertEquals(listOf("usbbt_fw/0b05_17cb.hcd"), UsbBtFirmwareLoader.getCandidateFirmwareAssets(0x0b05, 0x17cb))
    }
}
