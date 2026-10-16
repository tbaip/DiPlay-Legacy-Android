package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE, shadows = [
    UsbBtDongleDetectorTest.ShadowManager::class,
    UsbBtDongleDetectorTest.ShadowDev::class,
    UsbBtDongleDetectorTest.ShadowIface::class,
])
class UsbBtDongleDetectorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val detector by lazy { UsbBtDongleDetector(context) }

    @Test
    fun identifiesUgreenHaylouVendor() {
        val label = UsbBtDongleDetector.resolveVendorLabel(0x2b89, viaClassCode = true)
        assertEquals("UGREEN / Haylou (0x2b89)", label)

        val fallback = UsbBtDongleDetector.resolveVendorLabel(0x2b89, viaClassCode = false, productId = 0x6275)
        assertEquals("UGREEN / Haylou (0x2b89:0x6275)", fallback)
    }

    @Test
    fun identifiesTpLinkVendor() {
        val label = UsbBtDongleDetector.resolveVendorLabel(0x2357, viaClassCode = true)
        assertEquals("TP-Link (0x2357)", label)

        val fallback = UsbBtDongleDetector.resolveVendorLabel(0x2357, viaClassCode = false, productId = 0x0604)
        assertEquals("TP-Link (0x2357:0x0604)", fallback)
    }

    @Test
    fun identifiesAsusVendor() {
        val label = UsbBtDongleDetector.resolveVendorLabel(0x0b05, viaClassCode = true)
        assertEquals("ASUS (0x0b05)", label)
    }

    @Test
    fun identifiesGenericBluetoothVendor() {
        val label = UsbBtDongleDetector.resolveVendorLabel(0x9999, viaClassCode = true)
        assertEquals("Generic Bluetooth (0x9999)", label)
    }

    @Test
    fun knownVendorsTableContainsMajorManufacturers() {
        val table = UsbBtDongleDetector.KNOWN_BT_VENDORS
        assertTrue(table.containsKey(0x0bda)) // Realtek
        assertTrue(table.containsKey(0x2b89)) // UGREEN / Haylou
        assertTrue(table.containsKey(0x2357)) // TP-Link
        assertTrue(table.containsKey(0x0b05)) // ASUS
        assertTrue(table.containsKey(0x7392)) // Edimax
        assertTrue(table.containsKey(0x2001)) // D-Link
        assertTrue(table.containsKey(0x0a5c)) // Broadcom
        assertTrue(table.containsKey(0x0a12)) // CSR / Qualcomm
        assertTrue(table.containsKey(0x8087)) // Intel
        assertTrue(table.containsKey(0x10d7)) // UGREEN / Actions ATS2851
    }

    @Test
    fun identifiesUgreenCm591Ats2851DongleViaPidWhitelist() {
        val dev = createDevice(
            vid = 0x10d7,
            pid = 0xb012, // CM591 ATS2851 Bluetooth 5.3
            devClass = 0xff, // Vendor-specific
        )
        val dongle = detector.identify(dev)
        assertNotNull(dongle)
        assertEquals(false, dongle!!.viaClassCode)
        assertEquals("UGREEN / Actions Semiconductor (0x10d7:0xb012)", dongle.vendorLabel)
    }

    @Test
    fun identifiesStandardBluetoothDongleViaDeviceClass() {
        val dev = createDevice(
            vid = 0x0bda,
            pid = 0x8771,
            devClass = UsbBtDongleDetector.USB_CLASS_WIRELESS_CONTROLLER,
            devSubclass = 0x01,
            devProtocol = 0x01,
        )
        val dongle = detector.identify(dev)
        assertNotNull(dongle)
        assertTrue(dongle!!.viaClassCode)
        assertEquals("Realtek (0x0bda)", dongle.vendorLabel)
    }

    @Test
    fun identifiesStandardBluetoothDongleViaInterfaceClass() {
        val iface = createInterface(
            id = 0,
            cls = UsbBtDongleDetector.USB_CLASS_WIRELESS_CONTROLLER,
            sub = 0x01,
            proto = 0x01,
        )
        val dev = createDevice(
            vid = 0x2b89,
            pid = 0x6275,
            devClass = 0x00,
            interfaces = listOf(iface),
        )
        val dongle = detector.identify(dev)
        assertNotNull(dongle)
        assertTrue(dongle!!.viaClassCode)
        assertEquals("UGREEN / Haylou (0x2b89)", dongle.vendorLabel)
    }

    @Test
    fun identifiesKnownDongleWithoutClassCodeViaPidWhitelist() {
        val dev = createDevice(
            vid = 0x2b89,
            pid = 0x6275, // CM390 RTL8761BUV
            devClass = 0xff, // Vendor-specific
        )
        val dongle = detector.identify(dev)
        assertNotNull(dongle)
        assertEquals(false, dongle!!.viaClassCode)
        assertEquals("UGREEN / Haylou (0x2b89:0x6275)", dongle.vendorLabel)
    }

    @Test
    fun rejectsAppleDeviceRegardlessOfClass() {
        val dev = createDevice(
            vid = UsbBtDongleDetector.APPLE_VENDOR_ID, // 0x05ac
            pid = 0x12a8,
            devClass = UsbBtDongleDetector.USB_CLASS_WIRELESS_CONTROLLER,
            devSubclass = 0x01,
            devProtocol = 0x01,
        )
        assertNull(detector.identify(dev))
    }

    @Test
    fun rejectsDashcamVideoDeviceEvenFromKnownVendor() {
        // Dashcam from UGREEN (0x2b89) or Lenovo (0x17ef)
        val videoDev = createDevice(
            vid = 0x2b89,
            pid = 0x9999, // Unrelated peripheral PID
            devClass = UsbBtDongleDetector.USB_CLASS_VIDEO, // 0x0e
        )
        assertNull("Dashcam with video class must be rejected", detector.identify(videoDev))

        val uvcIface = createInterface(id = 0, cls = UsbBtDongleDetector.USB_CLASS_VIDEO)
        val miscDevWithVideoIface = createDevice(
            vid = 0x17ef, // Lenovo
            pid = 0x8888,
            devClass = UsbBtDongleDetector.USB_CLASS_MISC, // 0xef
            interfaces = listOf(uvcIface),
        )
        assertNull("Dashcam with video interface must be rejected", detector.identify(miscDevWithVideoIface))
    }

    @Test
    fun rejectsMassStorageUDiskEvenFromKnownVendor() {
        val uDisk = createDevice(
            vid = 0x2b89, // UGREEN U-Disk
            pid = 0x1234,
            devClass = UsbBtDongleDetector.USB_CLASS_MASS_STORAGE,
        )
        assertNull("Mass storage device must be rejected", detector.identify(uDisk))
    }

    @Test
    fun multiUsbPortScenario_t3CarHeadunitWithDashcamAndBtDongle() {
        // Port 1: Dashcam (UGREEN 0x2b89, PID 0x9999, Video class)
        val dashcam = createDevice(
            vid = 0x2b89,
            pid = 0x9999,
            devClass = UsbBtDongleDetector.USB_CLASS_VIDEO,
            id = 101,
            name = "/dev/bus/usb/001/002",
        )

        // Port 2: UGREEN CM390 Bluetooth Dongle (0x2b89:0x6275)
        val btDongle = createDevice(
            vid = 0x2b89,
            pid = 0x6275,
            devClass = UsbBtDongleDetector.USB_CLASS_WIRELESS_CONTROLLER,
            devSubclass = 0x01,
            devProtocol = 0x01,
            id = 102,
            name = "/dev/bus/usb/002/002",
        )

        val mgr = Shadow.newInstanceOf(UsbManager::class.java)
        val shadowMgr = Shadow.extract<ShadowManager>(mgr)
        shadowMgr.devices = mapOf(
            dashcam.deviceName to dashcam,
            btDongle.deviceName to btDongle,
        )
        shadowMgr.permitted = setOf(btDongle)

        val dongles = detector.findDongles(mgr)
        assertEquals("Must only discover the Bluetooth dongle and ignore the dashcam", 1, dongles.size)
        assertEquals(btDongle, dongles[0].device)
        assertEquals("UGREEN / Haylou (0x2b89)", dongles[0].vendorLabel)
    }

    @Test
    fun sortsPermittedDongleFirstWhenMultipleDonglesPresent() {
        val unpermittedDongle = createDevice(
            vid = 0x0a12, // CSR
            pid = 0x0001,
            devClass = UsbBtDongleDetector.USB_CLASS_WIRELESS_CONTROLLER,
            devSubclass = 0x01,
            devProtocol = 0x01,
            id = 201,
            name = "/dev/bus/usb/001/005",
        )

        val permittedDongle = createDevice(
            vid = 0x2b89, // CM390
            pid = 0x6275,
            devClass = UsbBtDongleDetector.USB_CLASS_WIRELESS_CONTROLLER,
            devSubclass = 0x01,
            devProtocol = 0x01,
            id = 202,
            name = "/dev/bus/usb/002/005",
        )

        val mgr = Shadow.newInstanceOf(UsbManager::class.java)
        val shadowMgr = Shadow.extract<ShadowManager>(mgr)
        shadowMgr.devices = mapOf(
            unpermittedDongle.deviceName to unpermittedDongle,
            permittedDongle.deviceName to permittedDongle,
        )
        shadowMgr.permitted = setOf(permittedDongle)

        val dongles = detector.findDongles(mgr)
        assertEquals(2, dongles.size)
        assertEquals("Permitted dongle must be ranked first", permittedDongle, dongles[0].device)
        assertEquals("Unpermitted dongle must be ranked second", unpermittedDongle, dongles[1].device)
    }

    private fun createDevice(
        vid: Int,
        pid: Int,
        devClass: Int = 0,
        devSubclass: Int = 0,
        devProtocol: Int = 0,
        interfaces: List<UsbInterface> = emptyList(),
        id: Int = 1,
        name: String = "/dev/bus/usb/001/001",
    ): UsbDevice {
        val dev = Shadow.newInstanceOf(UsbDevice::class.java)
        runCatching {
            org.robolectric.util.ReflectionHelpers.setField(dev, "mName", name)
        }
        val shadow = Shadow.extract<ShadowDev>(dev)
        shadow.vid = vid
        shadow.pid = pid
        shadow.devClass = devClass
        shadow.devSubclass = devSubclass
        shadow.devProtocol = devProtocol
        shadow.ifaces = interfaces
        shadow.devId = id
        shadow.devName = name
        return dev
    }

    private fun createInterface(
        id: Int,
        cls: Int,
        sub: Int = 0,
        proto: Int = 0,
    ): UsbInterface {
        val iface = Shadow.newInstanceOf(UsbInterface::class.java)
        val shadow = Shadow.extract<ShadowIface>(iface)
        shadow.ifaceId = id
        shadow.ifaceClass = cls
        shadow.ifaceSubclass = sub
        shadow.ifaceProtocol = proto
        return iface
    }

    @Implements(UsbDevice::class)
    class ShadowDev {
        var vid: Int = 0
        var pid: Int = 0
        var devClass: Int = 0
        var devSubclass: Int = 0
        var devProtocol: Int = 0
        var ifaces: List<UsbInterface> = emptyList()
        var devId: Int = 1
        var devName: String = "/dev/bus/usb/001/001"

        @Implementation fun getVendorId(): Int = vid
        @Implementation fun getProductId(): Int = pid
        @Implementation fun getDeviceClass(): Int = devClass
        @Implementation fun getDeviceSubclass(): Int = devSubclass
        @Implementation fun getDeviceProtocol(): Int = devProtocol
        @Implementation fun getInterfaceCount(): Int = ifaces.size
        @Implementation fun getInterface(index: Int): UsbInterface = ifaces[index]
        @Implementation fun getDeviceId(): Int = devId
        @Implementation fun getDeviceName(): String = devName

        @Implementation
        override fun equals(other: Any?): Boolean {
            if (other !is UsbDevice) return false
            val shadowOther = Shadow.extract<ShadowDev>(other)
            return shadowOther.devName == devName
        }

        @Implementation
        override fun hashCode(): Int = devName.hashCode()
    }

    @Implements(UsbInterface::class)
    class ShadowIface {
        var ifaceId: Int = 0
        var ifaceClass: Int = 0
        var ifaceSubclass: Int = 0
        var ifaceProtocol: Int = 0

        @Implementation fun getId(): Int = ifaceId
        @Implementation fun getInterfaceClass(): Int = ifaceClass
        @Implementation fun getInterfaceSubclass(): Int = ifaceSubclass
        @Implementation fun getInterfaceProtocol(): Int = ifaceProtocol
        @Implementation fun getEndpointCount(): Int = 0
    }

    @Implements(UsbManager::class)
    class ShadowManager {
        var devices: Map<String, UsbDevice> = emptyMap()
        var permitted: Set<UsbDevice> = emptySet()

        @Implementation fun getDeviceList(): HashMap<String, UsbDevice> = HashMap(devices)

        @Implementation
        fun hasPermission(device: UsbDevice): Boolean {
            val devShadow = Shadow.extract<ShadowDev>(device)
            return permitted.any {
                val pShadow = Shadow.extract<ShadowDev>(it)
                pShadow.devName == devShadow.devName
            }
        }
    }
}
