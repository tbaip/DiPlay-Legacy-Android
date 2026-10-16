# DiPlay v0.2.64-pre2 发布说明 (Pre-release)

本版本基于 `feature/wired-lwip-userspace` 分支构建，专门针对全志 T3 / Android 4.4 等低版本老车机在无 VPN 权限及无 Root 环境下的有线互联问题进行关键修复：**打通 lwIP 用户态 UDP 双向中继，彻底解决有线模式下音频与控制通道 UDP 数据丢失问题**。

---

## 核心更新内容

### 1. 实现用户态 lwIP UDP 双向中继 (`lwip_ncm_relay.c`)
- **UDP 端口自动监听注册**：在 JNI 层为 `is_tcp == false` 的端口映射分配独立 `UdpRelay` 槽位，创建 IPv6 `udp_pcb` 并绑定至 NCM 网络接口 (`[fe80::2]:port`)。
- **入站中继 (iPhone -> 车机)**：
  - 挂接 `udp_recv` 回调，捕获 iPhone 发来的 UDP 数据报（音频 RTP 流、RTCP 发送者报告、NTP 时钟同步等）；
  - 动态记录 iPhone 对端 IPv6 地址与源端口；
  - 创建非阻塞回环 UDP 套接字 (`loopback_fd`)，将数据无缝转交至本地 `127.0.0.1:port` 运行的 AirPlay 服务。
- **出站中继 (车机 -> iPhone)**：
  - 在 lwIP 工作线程的 `select` 轮询中将激活的 UDP `loopback_fd` 纳入监控；
  - 当本地服务产生回包（如 RTCP 接收者报告、NTP 响应等）时，自动组装为 IPv6 UDP 数据报，通过 lwIP 链路层由 CDC-NCM 封装发回 iPhone。
- **资源生命周期管理**：在连接销毁或上下文重置时完整清理所有 UDP PCB、套接字与缓冲区，杜绝句柄泄漏。
- **lwIP 内存池扩容**：将 `MEMP_NUM_UDP_PCB` 上限从 8 调整为 32，满足多音频流（主音频、导航语音、Siri）与控制信道并发需求。

### 2. 全链路自动化测试验证 (`T3LegacyRuntimeTest.kt`)
- 新增 `lwipUserspaceFullPipelineUdpRelay` 测试用例；
- 模拟 iPhone IPv6 链路本地地址向车机发送 UDP 数据报；
- 验证本地 `127.0.0.1` 上的 `DatagramSocket` 正常接收 payload，并验证本地回复能够经由 lwIP 完整回发给 iPhone。

---

## 包含产物 (Artifacts)
- `DiPlay-v0.2.64-pre2-legacy-release.apk`
- `DiPlay-v0.2.64-pre2-legacy-release.apk.sha256`
