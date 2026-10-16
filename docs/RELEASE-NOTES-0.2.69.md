# DiPlay v0.2.69 — 修复 Android 4.2 音乐播放媒体按键注册闪退

## 更新内容

### 1. Android 4.2 / API 17 音乐播放闪退修复
- **适配 API < 18 媒体按键注册接口**：在 Android 4.2（API 17）等低版本系统上，`AudioManager.registerMediaButtonEventReceiver(PendingIntent)` 与 `unregisterMediaButtonEventReceiver(PendingIntent)` 接口尚未引入（API 18 引入），导致在点击音乐播放或进入 Now Playing 时直接抛出 `java.lang.NoSuchMethodError: android.media.AudioManager.registerMediaButtonEventReceiver` 并崩溃闪退 (#197)。
- **版本自适应 ComponentName 注册与注销**：在 API < 18 下自适应切换使用经典 `registerMediaButtonEventReceiver(ComponentName)` 与 `unregisterMediaButtonEventReceiver(ComponentName)`，确保老旧系统顺利接收方控与媒体按键事件。
### 2. 车机外置 USB 蓝牙适配增强
- **内置 16 款 Realtek 蓝牙控制器全系固件**：针对老旧车机无自带蓝牙或蓝牙仅支持音频无法传输数据的痛点，内置覆盖 RTL8723A/B/D、RTL8761A/B/C、RTL8821A/C、RTL8822B/C、RTL8851B、RTL8852A/B/C、RTL8922A 等 16 大 Realtek USB 蓝牙系列固件，插入即用。
- **Android 4.4 方向盘按键响应修复**：修复 Android 4.4 及以下因 MediaSession 为空导致物理方控按键无法转发至 CarPlay 的问题。

---

## 验证与发布

- **版本标识**：`versionName 0.2.69` / `versionCode 89`，最低支持 Android 4.2（API 17）。
- **签名校验**：Release APK 包含完整的 V1 + V2 签名方案，签名证书 SHA-256 与历史版本完全一致（`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`）。
- **自动化测试**：全量单元测试与构建校验全部通过。
