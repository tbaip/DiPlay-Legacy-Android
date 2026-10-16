# DiPlay v0.2.64-pre1 — 用户态 lwIP 有线 CarPlay 网络中继与连接 (Pre-release)

## 本次更新内容

### 1. 用户态 lwIP TCP/IP 协议栈实现有线网络中继
- **绕过系统网络栈限制**：针对部分全志 T3、MTK 等老款车机内核无 TUN/TAP 支持、缺失系统 VPN 权限对话框或 VPN 服务受限的场景，引入纯用户态 lwIP（Lightweight IP）协议栈。
- **高性能原生 JNI 中继 (`lwip_ncm_relay.c`)**：直接在用户空间接管 USB Host 传输端点的 NCM 数据帧，实现无损、低延迟的二层网络帧解析与三层 TCP/UDP 报文中继转发。
- **平滑降级与无缝回退**：在具备系统 VPN 条件下保持原高效 VPN 桥接，在受限环境下自动切换到用户态 lwIP NCM 桥接（`LwipNcmBridge.kt`），确保有线互联的高可用性。

### 2. 全流程端到端自动化测试
- **端到端流程覆盖 (`T3LegacyRuntimeTest`)**：包含有线 CarPlay 握手、DHCP 地址分配、mDNS 服务通告、HTTP 控制会话建立及音视频媒体流的高压并发转发测试，确保网络中继稳定可靠。

### 3. 主线稳定基线合并
- 完整包含 v0.2.63 正式版的全部稳定修复（开机热点 60s 重试补试、悬浮球桌面导航、iAP2 静默媒体保护、Socket 转 Closeable 崩溃防御等）。

## 验证与测试建议

- **版本标识**：`versionName 0.2.64` / `versionCode 84`，最低支持 Android 4.2（API 17）。
- **签名保证**：包含完整 V1 + V2 签名，可与历史正式版本覆盖升级。
- **测试重点**：特别推荐在全志 T3 车机等受限 Android 车机上测试 USB 有线连接（支持即插即连，无需依赖车机系统 VPN 授权弹窗）。
