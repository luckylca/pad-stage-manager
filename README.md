# StageLab

StageLab 是面向 Android 平板的 Stage Manager 风格实时多任务窗口研究项目。

目标不是制作一组静态应用截图卡片，而是研究如何把**仍在运行的真实 Android Task / Surface** 放进自定义工作区，让用户看到持续变化的应用画面，并通过点击、拖动和动画切换当前任务。

> 当前仓库已实现 Shizuku UserService 与只读 TaskOrganizer 注册/回调探针，并通过 Windows Debug 编译。真实设备上的权限、注册和回调尚未实测；真实任务 Surface 嵌入与跨应用窗口控制仍未实现。

## 产品目标

- 左侧任务/工作组栏，主区域显示当前工作区。
- 多个真实运行的应用窗口保持活动，预览内容持续更新。
- 点击预览窗口即可切换为主窗口，窗口位置和尺寸使用连续动画。
- 后续探索窗口缩放、拖动、分组、焦点和触摸输入转发。
- 权限分层：Public API → ADB/Shizuku → KernelSU Root；每项能力优先使用最低权限后端。

## 技术路线

- Kotlin + Jetpack Compose：应用外壳、工作区布局、设置和交互。
- Task/Surface 后端：独立抽象，避免 UI 直接依赖某一种权限。
- 实时画面目标：TaskOrganizer / WindowContainerTransaction / SurfaceControl 一类系统窗口机制。
- PoC 先验证 Shell 身份是否具备所需权限，再决定 Shizuku 路径是否可行。
- KernelSU 仅作为可选研究后端；Root 本身不等于系统签名权限，也不保证能够直接调用 TaskOrganizer。
- MediaProjection 不作为多任务实时预览的主方案，因为它不能代替多个独立 Task 的可控 Surface。

## 当前工程状态

- [x] Android 原生 Gradle 工程
- [x] Kotlin / Jetpack Compose 基础 UI
- [x] 设备信息展示
- [x] 三个动态测试 Activity（计数器、运动图形、滚动列表），供后续任务窗口实验使用
- [x] Task Backend / capability 抽象
- [x] ADB、Shizuku、KernelSU 的分层技术文档
- [x] Shizuku API 13.1.5、Provider 与 UserService Binder 集成
- [x] Shell/Root UID、PID、SELinux context 与 MANAGE_ACTIVITY_TASKS 权限检查界面
- [x] 只读 TaskOrganizer 注册、任务回调记录与注销代码
- [ ] 在 OPPO Pad mini 上实测 Shizuku 授权、MANAGE_ACTIVITY_TASKS、注册结果与真实任务回调
- [ ] Task leash / SurfaceControl 托管 PoC
- [ ] 多任务触摸、焦点、IME 和窗口动画

说明：以上 `[x]` 代表对应代码已实现并通过编译，不代表 ColorOS 真机权限或运行结果已经验证。当前 ADB 未发现连接设备。

## 工程环境

- Windows 工作目录：E:\codex\pad-stage-manager
- Application ID：dev.stagelab.android
- Gradle：9.9.1（Wrapper）
- Android Gradle Plugin：9.5.1
- Kotlin：2.4.21
- Compose BOM：2026.09.00
- Compile SDK：37
- Target SDK：36（暂不主动启用 Android 17 的 target 37 行为变更）
- Min SDK：29
- Android SDK：D:\Android\sdk
- Android SDK Platform：37.0、37.2
- Android Build-Tools：37.0.0
- Android Platform Tools：37.0.1
- SDK Command-line Tools：19.0
- 推荐 ADB：`D:\Android\sdk\platform-tools\adb.exe`

`local.properties` 只记录本机 SDK 路径，已加入 `.gitignore`，不会提交到 Git。

当前 Windows 默认命中的 `C:\Windows\System32\adb.exe` 是旧版 33.0.0；连接设备和执行后续 PoC 时请优先使用上面的 SDK 内 ADB 绝对路径。


## 构建

在 PowerShell 中执行：

    cd E:\codex\pad-stage-manager
    .\gradlew.bat --version
    .\scripts\build-hidden-api-stubs.ps1
    .\gradlew.bat :app:assembleDebug

APK 输出位置：

    app/build/outputs/apk/debug/app-debug.apk

安装到已连接的设备：

    .\gradlew.bat :app:installDebug

或者显式使用 SDK 内的新版 ADB：

    D:\Android\sdk\platform-tools\adb.exe install -r .\app\build\outputs\apk\debug\app-debug.apk

如果 Gradle 下载依赖时需要使用本机代理，可在当前 PowerShell 会话中设置：

    $env:GRADLE_OPTS = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890"

不要把个人代理地址写入共享的 Gradle 配置或提交到仓库。

## 动态测试窗口

主界面提供三个独立任务启动入口：

1. Counter：持续递增的计数器。
2. Motion：持续运动的图形。
3. Scroll：可交互的长列表。

这些 Activity 是未来验证“后台任务是否持续活动、Surface 是否实时更新、窗口是否能被重新组织”的测试源。目前它们只是普通 Android Activity，尚未嵌入 StageLab 工作区。

## 重要平台限制

AOSP 的 TaskView 属于 WindowManager Shell 内部实现，不是普通第三方应用可直接依赖的稳定 SDK。TaskOrganizer 及任务组织操作受到系统权限控制。Shizuku 只提供被授权的 Shell 身份，不会自动赋予所有 signature 权限；KernelSU Root 也不会自动把普通 APK 变成系统签名应用。

因此，下一阶段必须在 OPPO Pad mini 的实际 ColorOS 构建上验证权限与 Binder 行为，不能仅凭 AOSP 源码推断设备一定允许。

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [PoC 计划](docs/POC_PLAN.md)
- [技术决策](docs/DECISIONS.md)
- [设备记录](docs/DEVICE_NOTES.md)
- [官方研究资料](docs/RESEARCH_REFERENCES.md)- [Shizuku / TaskOrganizer 探针说明](docs/SHIZUKU_TASK_PROBE.md)
