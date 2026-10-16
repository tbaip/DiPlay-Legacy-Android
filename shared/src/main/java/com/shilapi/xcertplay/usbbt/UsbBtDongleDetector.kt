package com.shilapi.xcertplay.usbbt

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import android.util.Log
import com.shilapi.xcertplay.usbbt.HciProtocol.USB_CLASS_WIRELESS_CONTROLLER
import com.shilapi.xcertplay.usbbt.HciProtocol.USB_PROTOCOL_BLUETOOTH
import com.shilapi.xcertplay.usbbt.HciProtocol.USB_SUBCLASS_RF

/**
 * Detects USB Bluetooth dongles through the Android USB Host API (Phase 1).
 *
 * A dongle is recognized when either:
 * - the device or one of its interfaces advertises USB class 0xE0 (Wireless
 *   Controller) / subclass 0x01 (RF) / protocol 0x01 (Bluetooth), or
 * - its vendor ID is in the well-known Bluetooth vendor table below.
 *
 * Well-known VIDs are public hardware identifiers (same list every OS USB
 * Bluetooth driver ships); matching by class code is preferred because it
 * also covers unlisted vendors.
 *
 * Clean-room: detection logic derived from the USB-IF class-code documents and
 * public hardware IDs only.
 */
class UsbBtDongleDetector(private val appContext: Context) {

    /** A USB device identified as a Bluetooth dongle. */
    data class Dongle(
        val device: UsbDevice,
        /** Human-readable vendor, e.g. "CSR (0x0a12)". "Unknown" when matched by class code only. */
        val vendorLabel: String,
        /** True when matched via the 0xE0/0x01/0x01 class code rather than the VID table. */
        val viaClassCode: Boolean,
    )

    sealed class PermissionState {
        /** The app already holds USB permission for this device. */
        data object Granted : PermissionState()

        /** A permission request was sent; the caller must handle the broadcast. */
        data object RequestSent : PermissionState()
    }

    companion object {
        private const val TAG = "UsbBtDongleDetector"

        /**
         * Well-known Bluetooth controller vendor IDs. Public hardware identifiers;
         * kept as a fallback for dongles whose descriptors lack the class code.
         */
        val KNOWN_BT_VENDORS: Map<Int, String> = mapOf(
            0x0a12 to "CSR / Qualcomm",
            0x0a5c to "Broadcom",
            0x0bda to "Realtek",
            0x8087 to "Intel",
            0x0cf3 to "Qualcomm Atheros",
            0x1286 to "Marvell",
            0x2b89 to "UGREEN / Haylou",
            0x2357 to "TP-Link",
            0x0b05 to "ASUS",
            0x7392 to "Edimax",
            0x2001 to "D-Link",
            0x050d to "Belkin",
            0x13d3 to "AzureWave",
            0x17ef to "Lenovo",
            0x413c to "Dell",
            0x03f0 to "HP",
            0x10d6 to "Actions Semiconductor",
            0x10d7 to "UGREEN / Actions Semiconductor",
            0x0489 to "Foxconn",
        )

        /**
         * Resolves a human-readable vendor label for diagnostics and UI display.
         */
        fun resolveVendorLabel(vendorId: Int, viaClassCode: Boolean, productId: Int? = null): String {
            val vidHex = "%04x".format(vendorId)
            val vendor = KNOWN_BT_VENDORS[vendorId]
            return if (viaClassCode) {
                vendor?.let { "$it (0x$vidHex)" } ?: "Generic Bluetooth (0x$vidHex)"
            } else {
                val pidHex = productId?.let { ":0x${"%04x".format(it)}" } ?: ""
                vendor?.let { "$it (0x$vidHex$pidHex)" } ?: "Unknown (0x$vidHex$pidHex)"
            }
        }

        /** Broadcast action for the USB permission result; host activity routes it. */
        fun permissionAction(packageName: String): String =
            "$packageName${PERMISSION_ACTION_SUFFIX}"

        private const val PERMISSION_ACTION_SUFFIX = ".USB_BT_PERMISSION"

        // Standard USB Class Codes (defined locally for Android API < 21 compatibility)
        const val USB_CLASS_PER_INTERFACE = 0x00
        const val USB_CLASS_AUDIO = 0x01
        const val USB_CLASS_COMM = 0x02
        const val USB_CLASS_HID = 0x03
        const val USB_CLASS_PHYSICAL = 0x05
        const val USB_CLASS_STILL_IMAGE = 0x06
        const val USB_CLASS_PRINTER = 0x07
        const val USB_CLASS_MASS_STORAGE = 0x08
        const val USB_CLASS_HUB = 0x09
        const val USB_CLASS_CDC_DATA = 0x0A
        const val USB_CLASS_CSCID = 0x0B
        const val USB_CLASS_CONTENT_SEC = 0x0C
        const val USB_CLASS_VIDEO = 0x0E
        const val USB_CLASS_WIRELESS_CONTROLLER = 0xE0
        const val USB_CLASS_MISC = 0xEF
        const val USB_CLASS_APP_SPEC = 0xFE
        const val USB_CLASS_VENDOR_SPEC = 0xFF

        const val APPLE_VENDOR_ID = 0x05AC

        /**
         * Device classes that indicate non-Bluetooth peripherals (cameras, mass storage, hubs, etc.).
         */
        val NON_BLUETOOTH_DEVICE_CLASSES: Set<Int> = setOf(
            USB_CLASS_AUDIO,
            USB_CLASS_COMM,
            USB_CLASS_HID,
            USB_CLASS_PHYSICAL,
            USB_CLASS_STILL_IMAGE,
            USB_CLASS_PRINTER,
            USB_CLASS_MASS_STORAGE,
            USB_CLASS_HUB,
            USB_CLASS_CDC_DATA,
            USB_CLASS_VIDEO,
        )

        /**
         * Interface classes that definitively rule out pure Bluetooth dongles.
         * Dashcams expose USB_CLASS_VIDEO (0x0E); USB flash drives expose USB_CLASS_MASS_STORAGE (0x08).
         */
        val NON_BLUETOOTH_INTERFACE_CLASSES: Set<Int> = setOf(
            USB_CLASS_VIDEO,
            USB_CLASS_MASS_STORAGE,
            USB_CLASS_HUB,
            USB_CLASS_PRINTER,
            USB_CLASS_STILL_IMAGE,
        )

        /**
         * Known Bluetooth dongle (VID, PID) pairs. Used strictly as a fallback
         * when a dongle lacks the 0xE0/0x01/0x01 class code descriptors.
         * Under no circumstance should a peripheral be assumed Bluetooth based
         * on VID alone.
         */
        val KNOWN_BT_DEVICE_PIDS: Set<Pair<Int, Int>> = setOf(
            // Realtek RTL8761B / RTL8761BUV / RTL8723 / RTL8821 / RTL8822 / RTL8852
            0x2b89 to 0x8771, // UGREEN CM390 (RTL8761B)
            0x2b89 to 0x6275, // UGREEN CM390 (RTL8761BUV)
            0x2357 to 0x0604, // TP-Link UB500
            0x0b05 to 0x190e, // ASUS USB-BT500
            0x7392 to 0xc611, // Edimax BT-8500
            0x0bda to 0x8771, // Realtek generic RTL8761B
            0x0bda to 0x8772,
            0x0bda to 0xb761,
            0x0bda to 0x8761,
            0x0bda to 0xa761,
            0x0bda to 0x8763,
            0x0bda to 0x8821,
            0x0bda to 0xb822,
            0x0bda to 0xc821,
            0x0bda to 0x8852,
            0x0bda to 0x885b,
            0x0bda to 0xb85b,
            0x0bda to 0xa85b,
            // CSR8510 A10
            0x0a12 to 0x0001,
            // Broadcom BCM20702
            0x0a5c to 0x21e8,
            0x0a5c to 0x21ec,
            0x0a5c to 0x21e1,
            0x0a5c to 0x21f3,
            // Intel Wireless-AC / AX Bluetooth
            0x8087 to 0x0025,
            0x8087 to 0x0026,
            0x8087 to 0x0029,
            0x8087 to 0x0032,
            0x8087 to 0x0aaa,
            // Actions Semiconductor ATS2851 / UGREEN CM591
            0x10d7 to 0xb012, // UGREEN CM591 (ATS2851 Bluetooth 5.3)
            0x10d7 to 0xb011,
            0x10d6 to 0x1101, // Actions generic ATS2851
            0x10d6 to 0x2851,
            0x2b89 to 0x3599, // UGREEN CM591 (35995)
        )
    }

    /**
     * Scan [usbManager] for Bluetooth dongles. Never throws; returns an empty
     * list when the device list is unavailable.
     *
     * Candidates are prioritized by:
     * 1. Already possessing USB permission (avoids prompting if another device is active)
     * 2. Identified via standard USB Bluetooth class code (0xE0/0x01/0x01)
     */
    fun findDongles(usbManager: UsbManager): List<Dongle> {
        val devices = runCatching { usbManager.deviceList.values.toList() }
            .getOrElse {
                Log.w(TAG, "USB device list unavailable", it)
                return emptyList()
            }
        return devices
            .mapNotNull { device ->
                identify(device)?.also {
                    val candidateInfo = "Bluetooth dongle candidate: vid=0x${device.vendorId.toString(16)} " +
                        "pid=0x${device.productId.toString(16)} vendor=${it.vendorLabel} " +
                        "viaClassCode=${it.viaClassCode} hasPermission=${usbManager.hasPermission(device)}"
                    UsbBtLog.log(appContext, TAG, candidateInfo)
                }
            }
            .sortedWith(
                compareByDescending<Dongle> { usbManager.hasPermission(it.device) }
                    .thenByDescending { it.viaClassCode }
            )
    }

    /**
     * Returns null when [device] is not a Bluetooth dongle.
     *
     * In multi-USB car environments (e.g. Allwinner T3 with dashcam on port 1 and
     * Bluetooth on port 2), this strictly distinguishes Bluetooth controllers from:
     * - Apple devices (iPhone/iPod on the car bus)
     * - Dashcams / UVC webcams (USB_CLASS_VIDEO 0x0E)
     * - USB flash drives / SD card readers (USB_CLASS_MASS_STORAGE 0x08)
     * - USB Hubs (USB_CLASS_HUB 0x09)
     */
    fun identify(device: UsbDevice): Dongle? {
        // 1. Explicitly ignore Apple devices
        if (device.vendorId == APPLE_VENDOR_ID) {
            return null
        }

        // 2. Check for standard Bluetooth class code
        val hasBtClass = hasBluetoothClassCode(device)

        // 3. Exclude non-Bluetooth peripheral classes (dashcams, mass storage, hubs)
        if (!hasBtClass && hasNonBluetoothClass(device)) {
            return null
        }

        if (hasBtClass) {
            return Dongle(
                device = device,
                vendorLabel = resolveVendorLabel(device.vendorId, viaClassCode = true),
                viaClassCode = true,
            )
        }

        // 4. Fallback: match only against verified (VID, PID) pairs.
        // Never match on vendor ID alone, which would mistakenly classify UGREEN/Lenovo/ASUS dashcams as dongles.
        val vidPid = device.vendorId to device.productId
        if (vidPid in KNOWN_BT_DEVICE_PIDS) {
            return Dongle(
                device = device,
                vendorLabel = resolveVendorLabel(device.vendorId, viaClassCode = false, productId = device.productId),
                viaClassCode = false,
            )
        }

        return null
    }

    private fun hasNonBluetoothClass(device: UsbDevice): Boolean {
        if (device.deviceClass in NON_BLUETOOTH_DEVICE_CLASSES) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass in NON_BLUETOOTH_INTERFACE_CLASSES) {
                return true
            }
        }
        return false
    }

    private fun hasBluetoothClassCode(device: UsbDevice): Boolean {
        if (isBluetoothClass(device.deviceClass, device.deviceSubclass, device.deviceProtocol)) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (isBluetoothClass(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol)) {
                return true
            }
        }
        return false
    }

    private fun isBluetoothClass(classCode: Int, subClass: Int, protocol: Int): Boolean =
        classCode == USB_CLASS_WIRELESS_CONTROLLER &&
            subClass == USB_SUBCLASS_RF &&
            protocol == USB_PROTOCOL_BLUETOOTH

    /**
     * Ensure USB permission for [dongle]. Returns [PermissionState.Granted] when
     * the app can open the device now, [PermissionState.RequestSent] when the
     * user must confirm the system dialog first.
     */
    fun ensurePermission(usbManager: UsbManager, dongle: Dongle): PermissionState {
        if (usbManager.hasPermission(dongle.device)) return PermissionState.Granted
        val intent = Intent(permissionAction(appContext.packageName))
            .setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (realSdkInt() >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(dongle.device, pi)
        return PermissionState.RequestSent
    }
}
