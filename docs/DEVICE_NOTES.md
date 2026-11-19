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
- 已安装 Android SDK Build-Tools 37.0.0。
- 已安装 Platform Tools 36.0.2。
- 当前项目搭建检查时，adb devices -l 没有列出已连接设备，因此尚未对平板执行安装或运行测试。

设备数据以之后真实 ADB 输出为准；不要把推测型号或系统属性写成已验证事实。
