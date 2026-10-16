package com.shilapi.xcertplay.usbbt

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RealtekFirmwareTest {
    private val bu = RealtekFirmware.profiles.single { it.name == "rtl8761bu" }
    private val version = byteArrayOf(0, 10, 11, 0, 10, 0x5d, 0, 0x61, 0x87.toByte())
    private val patched = version.copyOf().also { it[7] = 0x22; it[8] = 0xd9.toByte() }
    private val config = byteArrayOf(0x55, 0xab.toByte(), 0x23, 0x87.toByte(), 4, 0, 0x44, 0, 1, 0x33)

    private fun container(project: Int = 14): ByteArray {
        val b = ByteArray(58)
        val buffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("Realtech".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0x12345678).putShort(2).putShort(1).putShort(2)
        buffer.putShort(8).putShort(12).putInt(30).putInt(38)
        for (i in 30 until 50) b[i] = i.toByte()
        byteArrayOf(0xff.toByte(), project.toByte(), 1, 0, 0x51, 4, 0xfd.toByte(), 0x77).copyInto(b, 50)
        return b
    }

    private fun invalid(block: () -> Unit) {
        try { block(); fail("expected invalid firmware rejection") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun usbProfilesRequireManufacturerAndFullRomIdentity() {
        assertEquals(16, RealtekFirmware.profiles.size)
        for (profile in RealtekFirmware.profiles) {
            val identity = RealtekFirmware.Version(profile.hci, profile.revision, 0x005d, profile.subversion)
            assertEquals(profile, RealtekFirmware.match(identity))
            assertNull(RealtekFirmware.match(identity.copy(manufacturer = 15)))
            assertNull(RealtekFirmware.match(identity.copy(manufacturer = 13))) // Texas Instruments, not Realtek.
            assertNull(RealtekFirmware.match(identity.copy(revision = 0xffff)))
            if (profile.hci != 0) assertNull(RealtekFirmware.match(identity.copy(hci = 0xff)))
        }
        assertNull(RealtekFirmware.Version.parse(byteArrayOf(0)))
        assertNull(RealtekFirmware.Version.parse(version.copyOf().also { it[0] = 1 }))
        assertEquals(bu, RealtekFirmware.match(RealtekFirmware.Version.parse(version)!!))
        // RTL8761B UART has HCI 0x0a too, but this table selects the USB BU patch.
        assertEquals("usbbt_fw/rtl_bt/rtl8761bu_fw.bin", bu.firmwareAsset)
    }

    @Test fun selectsRomPatchAndReplacesVersionBeforeAppendingConfig() {
        val image = RealtekFirmware.image(container(), config, bu, 1)
        assertArrayEquals((38..45).map { it.toByte() }.toByteArray() +
            byteArrayOf(0x78, 0x56, 0x34, 0x12) + config, image)
        assertEquals(8, RealtekFirmware.image(container(), null, bu, 0).size)
        invalid { RealtekFirmware.image(container(), null, bu, 2) }
    }

    @Test fun rejectsWrongProjectNewContainerAndCorruptOffsets() {
        invalid { RealtekFirmware.image(container(3), null, bu, 0) }
        invalid { RealtekFirmware.image(container().also { "RTBTCore".toByteArray().copyInto(it) }, null, bu, 0) }
        invalid { RealtekFirmware.image(container().also { it[it.lastIndex] = 0 }, null, bu, 0) }
        for (offset in listOf(0, 14, 50, Int.MAX_VALUE, -1)) {
            invalid { RealtekFirmware.image(container().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(22, offset)
            }, null, bu, 0) }
        }
        invalid { RealtekFirmware.image(container().also { it[20] = 3; it[21] = 0 }, null, bu, 1) }
        invalid { RealtekFirmware.image(container().also { it[16] = 1 }, null, bu, 0) }
        invalid { RealtekFirmware.image(container().copyOf(20), null, bu, 0) }
    }

    @Test fun rejectsMissingOrMalformedMandatoryConfig() {
        val d = RealtekFirmware.profiles.single { it.name == "rtl8723d" }
        invalid { RealtekFirmware.image(container(9), null, d, 0) }
        invalid { RealtekFirmware.image(container(), byteArrayOf(), bu, 0) }
        invalid { RealtekFirmware.image(container(), config.copyOf().also { it[4] = 5 }, bu, 0) }
        invalid { RealtekFirmware.image(container(), config.copyOf().also { it[8] = 2 }, bu, 0) }
    }

    @Test fun exactMultipleEndsWithEmptyMarkedFragmentAndWrapsToOne() {
        val two = RealtekFirmware.fragments(ByteArray(504) { 0x55 }).toList()
        assertEquals(listOf(253, 253, 1), two.map { it.size })
        assertEquals(listOf(0, 1, 0x82), two.map { it[0].toInt() and 255 })
        val many = RealtekFirmware.fragments(ByteArray(252 * 255)).toList()
        assertEquals(0x7f, many[127][0].toInt() and 255)
        assertEquals(1, many[128][0].toInt() and 255)
        assertEquals(0x7f, many[254][0].toInt() and 255)
        assertEquals(0x81, many[255][0].toInt() and 255)
        val partial = RealtekFirmware.fragments(ByteArray(600)).toList()
        assertEquals(listOf(253, 253, 97), partial.map { it.size })
        assertEquals(0x82, partial.last()[0].toInt() and 255)
    }

    @Test fun everyBundledRomPatchAndConfigCanBePrepared() {
        val v2Roms = mapOf("rtl8822cu" to listOf(3), "rtl8852au" to listOf(1),
            "rtl8852bu" to listOf(0, 1), "rtl8852cu" to listOf(0, 1),
            "rtl8851bu" to listOf(0), "rtl8922au" to listOf(1), "rtl8852btu" to listOf(0))
        for (profile in RealtekFirmware.profiles) {
            val fw = asset(profile.firmwareAsset)!!
            val cfg = asset(profile.configAsset)
            if (profile.project == null) {
                assertArrayEquals(fw, RealtekFirmware.image(fw, cfg, profile, 0))
            } else if (RealtekFirmware.isV2(fw)) {
                for (rom in v2Roms.getValue(profile.name)) {
                    val image = RealtekFirmware.image(fw, cfg, profile, rom)
                    assertTrue(profile.name, image.size > 1_000)
                    assertTrue(RealtekFirmware.fragments(image).last()[0].toInt() and 0x80 != 0)
                    assertArrayEquals(cfg, image.takeLast(cfg!!.size).toByteArray())
                }
            } else {
                val buffer = ByteBuffer.wrap(fw).order(ByteOrder.LITTLE_ENDIAN)
                val count = buffer.getShort(12).toInt() and 65535
                for (i in 0 until count) {
                    val rom = (buffer.getShort(14 + i * 2).toInt() and 65535) - 1
                    if (rom < 0) continue // Placeholder chip ID zero cannot match a returned ROM ECO.
                    val length = buffer.getShort(14 + count * 2 + i * 2).toInt() and 65535
                    val image = RealtekFirmware.image(fw, cfg, profile, rom)
                    assertEquals(profile.name, length + (cfg?.size ?: 0), image.size)
                    assertArrayEquals(fw.copyOfRange(8, 12), image.copyOfRange(length - 4, length))
                    assertTrue(RealtekFirmware.fragments(image).last()[0].toInt() and 0x80 != 0)
                }
            }
        }
    }

    @Test fun oemDeviceUsesReportedChipAndOnlySendsPreparedPatch() {
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        val loader = UsbBtFirmwareLoader({ opcode, params, _ ->
            sent.add(opcode to params)
            UsbBtHci.HciResult.Ok(when (opcode) {
                RealtekFirmware.READ_ROM_VERSION -> byteArrayOf(0, 1)
                HciProtocol.OP_RTL_DOWNLOAD_COMMAND -> byteArrayOf(0, params[0])
                else -> patched
            })
        }, { if (it == bu.firmwareAsset) container() else if (it == bu.configAsset) config else null })
        // An unlisted OEM VID/PID still works via Realtek manufacturer + full HCI ROM identity.
        val result = loader.loadForDevice(0xffff, 0x1234, version)
        assertTrue(result.toString(), result is UsbBtFirmwareLoader.LoadResult.Uploaded)
        assertEquals(listOf(0xfc6d, 0xfc20, 0x1001), sent.map { it.first })
        assertArrayEquals(byteArrayOf(0x80.toByte()) + RealtekFirmware.image(container(), config, bu, 1), sent[1].second)
    }

    @Test fun failedAckStopsUploadAndDoesNotReportSuccess() {
        for (reply in listOf(byteArrayOf(0), byteArrayOf(0, 0x7f), byteArrayOf(1, 0))) {
            val sent = mutableListOf<Int>()
            val loader = UsbBtFirmwareLoader({ opcode, _, _ ->
                sent.add(opcode)
                UsbBtHci.HciResult.Ok(if (opcode == 0xfc6d) byteArrayOf(0, 1) else reply)
            }, { if (it == bu.firmwareAsset) container() else null })
            assertTrue(loader.loadForDevice(0x0bda, 0x8771, version) is UsbBtFirmwareLoader.LoadResult.Failed)
            assertEquals(listOf(0xfc6d, 0xfc20), sent)
        }
    }

    @Test fun noUploadForUnknownOrAlreadyPatchedIdentity() {
        val loader = UsbBtFirmwareLoader({ _, _, _ -> fail("no command expected"); error("unreachable") }, { null })
        assertTrue(loader.loadForDevice(0x2b89, 0x3599,
            version.copyOf().also { it[5] = 15 }) is UsbBtFirmwareLoader.LoadResult.Skipped)
        assertTrue(loader.loadForDevice(0x0bda, 0x8771, patched) is UsbBtFirmwareLoader.LoadResult.Skipped)
        assertTrue(loader.loadForDevice(0x0a5c, 0x21e8) is UsbBtFirmwareLoader.LoadResult.NoFirmware)
    }

    @Test fun invalidExactOverrideCannotFallThroughToAnotherFirmware() {
        val sent = mutableListOf<Int>()
        val loader = UsbBtFirmwareLoader({ opcode, _, _ ->
            sent.add(opcode); UsbBtHci.HciResult.Ok(byteArrayOf(0, 1))
        }, { if (it.endsWith("0bda_8771.bin")) container(3) else if (it == bu.firmwareAsset) container() else null })
        assertTrue(loader.loadForDevice(0x0bda, 0x8771, version) is UsbBtFirmwareLoader.LoadResult.Failed)
        assertEquals(listOf(0xfc6d), sent)
    }

    @Test fun unchangedRomAfterDownloadFailsActivationCheck() {
        val loader = UsbBtFirmwareLoader({ opcode, params, _ -> UsbBtHci.HciResult.Ok(when (opcode) {
            0xfc6d -> byteArrayOf(0, 1)
            0xfc20 -> byteArrayOf(0, params[0])
            else -> version
        }) }, { if (it == bu.firmwareAsset) container() else null })
        assertTrue(loader.loadForDevice(0x0bda, 0x8771, version) is UsbBtFirmwareLoader.LoadResult.Failed)
    }

    private fun v2Container(): ByteArray {
        // Out-of-order snippets, dummy and two security keys for the same ECO.
        val output = java.io.ByteArrayOutputStream()
        fun int(value: Int) = output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        output.write("RTBTCore".toByteArray())
        output.write(ByteArray(8))
        int(5)
        for ((opcode, priority, key, value) in listOf(
            listOf(1, 15, 0, 0x44), listOf(3, 1, 7, 0x11), listOf(3, 1, 8, 0x55),
            listOf(2, 3, 0, 0x22), listOf(1, 15, 0, 0x33),
        )) {
            int(opcode); int(13)
            output.write(byteArrayOf(1, 0, 0, 0))
            output.write(byteArrayOf(1, priority.toByte(), key.toByte(), 0)); int(1)
            output.write(value)
        }
        output.write(byteArrayOf(0xff.toByte(), 20, 1, 0, 0x51, 4, 0xfd.toByte(), 0x77))
        return output.toByteArray()
    }

    @Test fun v2SelectsEcoSecurityKeyAndPriorityWithoutChangingSnippetData() {
        val profile = RealtekFirmware.profiles.single { it.name == "rtl8852bu" }
        val fw = v2Container()
        assertArrayEquals(byteArrayOf(0x22, 0x33, 0x44), RealtekFirmware.image(fw, null, profile, 0))
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), RealtekFirmware.image(fw, null, profile, 0, 7))
        assertArrayEquals(byteArrayOf(0x55, 0x22, 0x33, 0x44), RealtekFirmware.image(fw, null, profile, 0, 8))
        invalid { RealtekFirmware.image(fw, null, profile, 1) }
        invalid { RealtekFirmware.image(fw.copyOf().also { it[24] = 0xff.toByte() }, null, profile, 0) }
        invalid { RealtekFirmware.image(fw.copyOf().also { it[36] = 0xff.toByte() }, null, profile, 0) }
    }

    @Test fun v2LoaderReadsKeyAndOmitsConfigOnSecuredController() {
        val profile = RealtekFirmware.profiles.single { it.name == "rtl8852bu" }
        val identity = byteArrayOf(0, 11, 11, 0, 11, 0x5d, 0, 0x52, 0x88.toByte())
        val loaded = identity.copyOf().also { it[7] = 0x23 }
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        val loader = UsbBtFirmwareLoader({ opcode, params, _ ->
            sent.add(opcode to params)
            UsbBtHci.HciResult.Ok(when (opcode) {
                0xfc61 -> byteArrayOf(0, 7, 0)
                0xfc6d -> byteArrayOf(0, 0)
                0xfc20 -> byteArrayOf(0, params[0])
                else -> loaded
            })
        }, { when (it) { profile.firmwareAsset -> v2Container(); profile.configAsset -> error("secured config must not be loaded"); else -> null } })
        assertTrue(loader.loadForDevice(0x0bda, 0x1234, identity) is UsbBtFirmwareLoader.LoadResult.Uploaded)
        assertArrayEquals(byteArrayOf(0x10, 0xa4.toByte(), 0xad.toByte(), 0, 0xb0.toByte()), sent.first().second)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x11, 0x22, 0x33, 0x44), sent.single { it.first == 0xfc20 }.second)
    }

    @Test fun bundledBytesMatchPinnedSourceManifestAndLicenseIsPresent() {
        val manifest = asset("usbbt_fw/manifest.json")!!.toString(Charsets.UTF_8)
        val records = Regex("""\{\s*"path": "([^"]+)",\s*"upstream_path": "[^"]+",\s*"bytes": (\d+),\s*"sha256": "([0-9a-f]{64})"\s*}""").findAll(manifest).toList()
        assertEquals(31, records.size)
        for (record in records) {
            val data = asset("usbbt_fw/" + record.groupValues[1])!!
            assertEquals(record.groupValues[2].toInt(), data.size)
            val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(record.groupValues[1], record.groupValues[3], hash)
        }
        val license = asset("usbbt_fw/LICENCE.rtlwifi_firmware.txt")!!.toString(Charsets.UTF_8)
        assertTrue(license.contains("Realtek Semiconductor Corporation"))
        assertTrue(license.contains("Redistribution"))
        assertTrue(manifest.contains("38c1a7096dfb2188f55f342b835f14c362c59409"))
    }

    private fun asset(path: String): ByteArray? {
        val root = listOf(File("src/main/assets"), File("shared/src/main/assets")).first { it.isDirectory }
        return File(root, path).takeIf { it.isFile }?.readBytes()
    }
}
