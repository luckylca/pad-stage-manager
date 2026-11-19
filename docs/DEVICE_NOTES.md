# 目标设备记录

## 用户提供的信息

- 设备：OPPO Pad mini
- 系统版本：ColorOS 17.0.0.0.103
- Android 版本：17
- 内核版本前缀：6.12.69-android16-6
- 目标：先以该平板完成真实 Task Surface PoC，再考虑通用 Android 平板适配。

## 尚未实测确认

- 完整产品型号/产品代号（OPD2515 仅是此前推测，未验证）。
- 完整内核构建字符串。
- ro.build.version.sdk。
- Build Fingerprint 和安全补丁日期。
- Shell UID 是否具有 MANAGE_ACTIVITY_TASKS。
- ColorOS 对 TaskOrganizer、WindowContainerTransaction 和 SurfaceControl 的具体限制。

## Windows 开发环境

- 工程路径：E:\codex\pad-stage-manager
- Android SDK：D:\Android\sdk
- 已安装 Android SDK Platform 37.2。
- 已安装 Android SDK Platform 37.0；Gradle 本次实际使用 37.0。
- SDK Command-line Tools 版本为 19.0；读取新版 SDK 元数据时出现 XML 版本兼容警告，但本次 SDK 安装和 Gradle 构建均成功。
- 已安装 Android SDK Build-Tools 37.0.0。
- 已安装 Android SDK Platform Tools 37.0.1，路径为 `D:\Android\sdk\platform-tools`。
- Windows 默认解析到 `C:\Windows\System32\adb.exe`（33.0.0）；后续 PoC 优先使用 `D:\Android\sdk\platform-tools\adb.exe`（37.0.1），避免混用旧版 ADB。
- 本次检查没有列出已连接的 Android 设备，因此尚未向平板安装 APK 或执行真机测试。

设备数据以之后真实 ADB 输出为准；不要把推测型号或系统属性写成已验证事实。
