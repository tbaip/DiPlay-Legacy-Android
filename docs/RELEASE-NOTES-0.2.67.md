# DiPlay v0.2.67 — 修复 Android 4.x 启动闪退与 MultiDex 主 DEX 规则补全

## 更新内容

### 1. Android 4.4 / 4.2 启动闪退 (NoClassDefFoundError) 修复
- **MultiDex 主 DEX 规则补全**：Android 4.x (API < 21) 虚拟机（Dalvik）启动时，`DiPlayApplication.onCreate()` 在主 DEX（classes.dex）中运行。由于此前 `LoggingGate` 和 `FloatingDotOverlay` 被打包进了次 DEX（classes3.dex），导致在类加载时直接触发 `java.lang.NoClassDefFoundError: com.shilapi.xcertplay.LoggingGate` 并崩溃闪退（提示 "Unfortunately, DiPlay has stopped"）。
- **优化 multidex-config.pro**：显式将 `LoggingGate` 与 `FloatingDotOverlay` 包含在主 DEX keep 规则中，确保应用冷启动瞬间 Dalvik 虚拟机即可直接加载。
- **启动方法字节码降级**：避免在 Application 冷启动方法内生成内联 `kotlin.Result`，改用底层经典 `try-catch`，彻底杜绝老旧 Dalvik 字节码验证失败。

---

## 验证与发布

- **实机/模拟器测试**：已在官方 Android 4.4.2 (API 19) 及 Android 4.2.2 (API 17) 模拟器环境实测，冷启动与主界面渲染 100% 正常，无任何崩溃。
- **版本标识**：`versionName 0.2.67` / `versionCode 87`，最低支持 Android 4.2（API 17）。
- **签名校验**：Release APK 包含完整的 V1 + V2 签名方案，签名证书 SHA-256 与历史版本完全一致（`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`）。
- **自动化测试**：全量单元测试与构建校验全部通过。
