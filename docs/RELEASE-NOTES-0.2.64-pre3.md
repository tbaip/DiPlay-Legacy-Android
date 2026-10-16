# DiPlay v0.2.64-pre3 发布说明 (Pre-release)

本版本基于 `feature/wired-lwip-userspace` 分支构建，专门针对**车机存在多个 USB 端口（如全志 T3 车机双 USB 口，其中一个常驻插入行车记录仪/U盘，另一个用于插入 iPhone 互联）**的硬件环境进行了深度的外设隔离、拓扑感知与重枚举优化。

---

## 核心更新内容

### 1. 行车记录仪与非 Apple 外设精准隔离 (`IphoneUsbHost.kt`)
- **拔插广播隔离 (`ACTION_USB_DEVICE_ATTACHED` / `ACTION_USB_DEVICE_DETACHED`)**：
  - 新增 `parseDetachedDevice` 与 `registerDetachReceiver`。
  - 行车记录仪（UVC 摄像头、MassStorage U盘、串口外设等）在另一个 USB 口上发生重置、重新插拔或工作状态切换时，广播事件会被底层直接过滤为 `null`，**绝不影响当前正在运行的 CarPlay 会话**。
  - 只有当插在互联 USB 口上的 iPhone 被真正拔出时，才会感知并优雅平稳地复位至等待重连状态。
- **重枚举实例动态匹配 (`refreshDevice`)**：
  - 在双 USB 口环境下，当 iPhone 发送 `0x52` 模式切换指令后，动态匹配同一 Apple 厂商标识下的活跃实例，即便重枚举后 PID 变更也能毫秒级捕获，彻底避免被另一端口的非 Apple 设备干扰。

### 2. 多 USB 口拓扑感知与诊断增强 (`CarPlayController.kt`)
- **重枚举瞬态握手加速 (`pollReenumeration`)**：
  - 在全志 T3 等旧版内核上，同一物理 USB 口热插拔/重枚举后可能保留原有的 `deviceName`（导致原有逻辑强制等待 2.5 秒延迟）。
  - 新增配置就绪校验（`hasCarPlayConfig`）与实例 ID 校验，一旦检测到 CarPlay NCM 配置就绪即刻开始连接，握手提速数秒。
- **USB 总线拓扑诊断输出 (`logVisibleUsbDevicesThrottled`)**：
  - 在等待 iPhone 连接时，深度扫描总线上存在的设备，智能标注外设类型（如 `Dashcam/Video`、`MassStorage/UDisk`）。
  - 日志明确打印：`wired waiting for iPhone on available USB port; non-Apple peripheral(s) detected: [...]`，方便排查双口插入状态。

### 3. 全链路测试覆盖 (`IphoneUsbBringupTest.kt`)
- 新增 `multiUsbPortScenario_dashcamIgnoredAndIphoneDiscoveredAndDetached` 测试用例，完整模拟 USB 端口 1 插入行车记录仪、USB 端口 2 插入 iPhone 的共存场景，验证设备过滤、事件隔离与断开响应的 100% 可靠性。

---

## 包含产物 (Artifacts)
- `DiPlay-v0.2.64-pre3-legacy-release.apk`
- `DiPlay-v0.2.64-pre3-legacy-release.apk.sha256`
