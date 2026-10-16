package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Diagnostic and self-test utility for external USB Bluetooth dongles.
 *
 * Runs clean-room HCI verification:
 * 1. Enumeration & permission check via Android USB Host API.
 * 2. HCI endpoint discovery (Interrupt IN, Bulk IN, Bulk OUT).
 * 3. Control transfer: HCI_RESET (0x0C03) and awaits Command Complete event.
 * 4. Control transfer: HCI_READ_BD_ADDR (0x1009) to read local Bluetooth MAC.
 * 5. Control transfer: HCI_READ_BUFFER_SIZE (0x1005) to read ACL buffer capacities.
 */
object UsbBtSelfTest {
    private const val TAG = "UsbBtSelfTest"
    private const val TIMEOUT_MS = 2000

    data class Report(
        val ok: Boolean,
        val status: String,
        val message: String,
        val device: UsbDevice? = null,
        val bdAddr: String? = null,
        val vendorLabel: String? = null,
        val needsPermission: Boolean = false,
        val details: List<String> = emptyList(),
    )

    fun run(context: Context, targetDevice: UsbDevice? = null): Report {
        val usbManager = runCatching {
            context.getSystemService(Context.USB_SERVICE) as? UsbManager
        }.getOrNull() ?: return Report(
            ok = false,
            status = "NoUsbManager",
            message = "USB Host service unavailable on this system",
        )
        return runWithManager(context, usbManager, targetDevice)
    }

    fun runWithManager(context: Context, usbManager: UsbManager, targetDevice: UsbDevice? = null): Report {
        UsbBtLog.log(context, TAG, "=== 开始外置蓝牙硬件与通讯自检 ===")
        val detector = UsbBtDongleDetector(context.applicationContext)
        val dongles = detector.findDongles(usbManager)
        val candidate = targetDevice?.let { dev ->
            dongles.firstOrNull { it.device == dev } ?: detector.identify(dev)
        } ?: dongles.firstOrNull()

        if (candidate == null) {
            UsbBtLog.warn(context, TAG, "自检中止: 未检测到外置 USB 蓝牙适配器")
            return Report(
                ok = false,
                status = "NoDongle",
                message = "未检测到外置 USB 蓝牙适配器",
            )
        }

        val device = candidate.device
        val vendorLabel = candidate.vendorLabel
        val logDetails = mutableListOf<String>()
        fun failed(status: String, message: String): Report {
            UsbBtLog.warn(context, TAG, message)
            return Report(false, status, message, device = device,
                vendorLabel = vendorLabel, details = logDetails.toList())
        }
        val devInfo = "设备: $vendorLabel (VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)})"
        logDetails.add(devInfo)
        UsbBtLog.log(context, TAG, devInfo)

        if (!usbManager.hasPermission(device)) {
            UsbBtLog.warn(context, TAG, "自检中止: 未获取 USB 权限")
            return Report(
                ok = false,
                status = "NeedsPermission",
                message = "未获取 USB 权限，请点击授权",
                device = device,
                vendorLabel = vendorLabel,
                needsPermission = true,
                details = logDetails,
            )
        }

        // Open device
        val connection: UsbDeviceConnection = usbManager.openDevice(device) ?: run {
            UsbBtLog.warn(context, TAG, "自检中止: 打开 USB 设备连接失败 (openDevice returned null)")
            return Report(
                ok = false,
                status = "OpenFailed",
                message = "打开 USB 设备连接失败 (openDevice returned null)",
                device = device,
                vendorLabel = vendorLabel,
                details = logDetails,
            )
        }

        var claimedInterface: UsbInterface? = null
        try {
            val btIface = findBluetoothInterface(device) ?: run {
                UsbBtLog.warn(context, TAG, "自检中止: 未找到可用的蓝牙 HCI 接口")
                return Report(
                    ok = false,
                    status = "NoInterface",
                    message = "未找到可用的蓝牙 HCI 接口",
                    device = device,
                    vendorLabel = vendorLabel,
                    details = logDetails,
                )
            }
            val ifaceInfo = "HCI 接口 ID: ${btIface.id}"
            logDetails.add(ifaceInfo)
            UsbBtLog.log(context, TAG, ifaceInfo)

            if (!connection.claimInterface(btIface, true)) {
                UsbBtLog.warn(context, TAG, "自检中止: 占用接口失败 (claimInterface returned false)")
                return Report(
                    ok = false,
                    status = "ClaimFailed",
                    message = "占用接口失败 (claimInterface returned false)",
                    device = device,
                    vendorLabel = vendorLabel,
                    details = logDetails,
                )
            }
            claimedInterface = btIface

            val endpoints = findEndpoints(btIface) ?: run {
                UsbBtLog.warn(context, TAG, "自检中止: 未找到完整 HCI 端点")
                return Report(
                    ok = false,
                    status = "NoEndpoints",
                    message = "未找到完整 HCI 端点 (需要中断输入及批量收发端点)",
                    device = device,
                    vendorLabel = vendorLabel,
                    details = logDetails,
                )
            }
            val epInfo = "端点: Event=0x${endpoints.event.address.toString(16)} ACL_IN=0x${endpoints.aclIn.address.toString(16)} ACL_OUT=0x${endpoints.aclOut.address.toString(16)}"
            logDetails.add(epInfo)
            UsbBtLog.log(context, TAG, epInfo)

            // Step 1: Send HCI_RESET
            val resetPayload = HciProtocol.encodeCommand(HciProtocol.OP_RESET)
            val resetSent = connection.controlTransfer(
                HciProtocol.USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT,
                HciProtocol.USB_BT_CONTROL_REQUEST,
                0,
                btIface.id,
                resetPayload,
                resetPayload.size,
                TIMEOUT_MS,
            )
            if (resetSent != resetPayload.size) {
                UsbBtLog.warn(context, TAG, "自检中止: HCI Reset 控制传输失败 (sent $resetSent/${resetPayload.size})")
                return Report(
                    ok = false,
                    status = "ResetFailed",
                    message = "HCI Reset 控制传输失败 (sent $resetSent/${resetPayload.size})",
                    device = device,
                    vendorLabel = vendorLabel,
                    details = logDetails,
                )
            }
            logDetails.add("HCI Reset 命令发送成功")
            UsbBtLog.log(context, TAG, "HCI Reset 命令发送成功")

            // Wait for Reset Command Complete
            val resetEvent = pollEvent(connection, endpoints.event, HciProtocol.OP_RESET)
            if (resetEvent == null) {
                UsbBtLog.warn(context, TAG, "自检中止: HCI Reset 响应超时")
                return Report(
                    ok = false,
                    status = "ResetTimeout",
                    message = "HCI Reset 响应超时",
                    device = device,
                    vendorLabel = vendorLabel,
                    details = logDetails,
                )
            }
            val resetStatus = resetEvent.returnParams.firstOrNull()?.toInt()?.and(0xFF)
            if (resetStatus != 0) {
                return failed("ResetFailed", "HCI Reset 失败 (status=${resetStatus?.let { "0x${it.toString(16)}" } ?: "missing"})")
            }
            logDetails.add("HCI Reset 响应正常 (Command Complete)")
            UsbBtLog.log(context, TAG, "HCI Reset 响应正常 (Command Complete)")

            // Step 2: Read BD_ADDR
            val bdAddrPayload = HciProtocol.encodeCommand(HciProtocol.OP_READ_BD_ADDR)
            val bdAddrSent = connection.controlTransfer(
                HciProtocol.USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT,
                HciProtocol.USB_BT_CONTROL_REQUEST,
                0,
                btIface.id,
                bdAddrPayload,
                bdAddrPayload.size,
                TIMEOUT_MS,
            )
            if (bdAddrSent != bdAddrPayload.size) {
                return failed("ReadBdAddrFailed", "读取本地 BD_ADDR 控制传输失败 (sent $bdAddrSent/${bdAddrPayload.size})")
            }
            val bdAddrEvent = pollEvent(connection, endpoints.event, HciProtocol.OP_READ_BD_ADDR)
            val bdAddr = bdAddrEvent?.let { HciProtocol.parseBdAddr(it.returnParams) }
            if (bdAddr != null) {
                val macMsg = "读取本地 BD_ADDR: $bdAddr"
                logDetails.add(macMsg)
                UsbBtLog.log(context, TAG, macMsg)
            } else {
                return failed("ReadBdAddrFailed", "读取本地 BD_ADDR 失败、超时或格式不符")
            }

            // Step 3: Read Buffer Size
            val bufPayload = HciProtocol.encodeCommand(HciProtocol.OP_READ_BUFFER_SIZE)
            val bufSent = connection.controlTransfer(
                HciProtocol.USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT,
                HciProtocol.USB_BT_CONTROL_REQUEST,
                0,
                btIface.id,
                bufPayload,
                bufPayload.size,
                TIMEOUT_MS,
            )
            if (bufSent != bufPayload.size) {
                return failed("ReadBufferSizeFailed", "读取缓冲区控制传输失败 (sent $bufSent/${bufPayload.size})")
            }
            val bufEvent = pollEvent(connection, endpoints.event, HciProtocol.OP_READ_BUFFER_SIZE)
            val bufferSize = bufEvent?.let { HciProtocol.parseBufferSize(it.returnParams) }
            if (bufferSize != null && bufferSize.aclDataPacketLength > 0 && bufferSize.totalNumAclPackets > 0) {
                val bufMsg = "ACL 数据包限制: ${bufferSize.aclDataPacketLength} 字节, 容量: ${bufferSize.totalNumAclPackets}"
                logDetails.add(bufMsg)
                UsbBtLog.log(context, TAG, bufMsg)
            } else {
                return failed("ReadBufferSizeFailed", "读取缓冲区失败、超时或 ACL 容量无效")
            }

            val successMsg = "自检通过: 芯片通讯正常" + (if (bdAddr != null) " (MAC: $bdAddr)" else "")
            UsbBtLog.log(context, TAG, "=== $successMsg ===")
            return Report(
                ok = true,
                status = "Success",
                message = successMsg,
                device = device,
                bdAddr = bdAddr,
                vendorLabel = vendorLabel,
                details = logDetails,
            )
        } catch (t: Throwable) {
            UsbBtLog.warn(context, TAG, "自检异常", t)
            return Report(
                ok = false,
                status = "Exception",
                message = "自检异常: ${t.message ?: t.javaClass.simpleName}",
                device = device,
                vendorLabel = vendorLabel,
                details = logDetails,
            )
        } finally {
            claimedInterface?.let { runCatching { connection.releaseInterface(it) } }
            runCatching { connection.close() }
        }
    }

    private data class Endpoints(
        val event: UsbEndpoint,
        val aclIn: UsbEndpoint,
        val aclOut: UsbEndpoint,
    )

    private fun findBluetoothInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == HciProtocol.USB_CLASS_WIRELESS_CONTROLLER &&
                intf.interfaceSubclass == HciProtocol.USB_SUBCLASS_RF &&
                intf.interfaceProtocol == HciProtocol.USB_PROTOCOL_BLUETOOTH
            ) {
                return intf
            }
        }
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }

    private fun findEndpoints(intf: UsbInterface): Endpoints? {
        var event: UsbEndpoint? = null
        var aclIn: UsbEndpoint? = null
        var aclOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            val isIn = ep.direction == UsbConstants.USB_DIR_IN
            when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_INT -> if (isIn && event == null) event = ep
                UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                    if (isIn && aclIn == null) aclIn = ep
                    if (!isIn && aclOut == null) aclOut = ep
                }
                else -> Unit
            }
        }
        val e = event ?: return null
        val inEp = aclIn ?: return null
        val outEp = aclOut ?: return null
        return Endpoints(e, inEp, outEp)
    }

    private fun pollEvent(
        connection: UsbDeviceConnection,
        eventEndpoint: UsbEndpoint,
        expectedOpcode: Int,
        timeoutMs: Int = TIMEOUT_MS,
    ): HciProtocol.CommandComplete? {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        val buf = ByteArray(HciProtocol.MAX_EVENT_PACKET_SIZE)
        while (System.nanoTime() < deadline) {
            val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            val read = connection.bulkTransfer(eventEndpoint, buf, buf.size, remaining.coerceIn(1, 500).toInt())
            if (read > 0) {
                val evt = HciProtocol.parseEvent(buf, read) ?: continue
                if (evt.code == HciProtocol.EVT_COMMAND_COMPLETE) {
                    val cc = HciProtocol.parseCommandComplete(evt)
                    if (cc != null && cc.opcode == expectedOpcode) {
                        return cc
                    }
                }
            }
        }
        return null
    }
}
