# StageLab Native

基于 **ColorOS 17 原生 Flexible Window（自由浮窗）+ `ShellTaskOrganizer` + 原生 Taskbar/Dock** 的 LSPosed (Vector) 系统级 iPad Stage Manager（台前调度）模块。

> 严格原则：所有应用窗口、圆角、阴影、拖动、缩放、焦点、IME 与 Back 均由 Android / ColorOS 原生窗口系统管理；模块仅负责动态 Stage 逻辑编排、原生窗口停放/恢复同步与左侧悬浮 Recent Strip。

## 目录结构

- `core/` — 纯 Kotlin/JVM 动态 Stage 状态机（`Stage`, `StageTask`, `StageSession`, `StageRegistry`, `StageTransitionController`, `NativeWindowBridge`, `RecentStageController`, `DockIntegrationController`）与完整 JUnit 测试。
- `module/` — Android LSPosed 模块 APK (`dev.stagelab.nativehook`)：
  - `xposed/`：`StageLabHookEntry`, `HookGuard`, `LauncherDockHook`, `FlexibleMenuHook`
  - `bridge/`：`ColorOsNativeWindowBridge`, `StageNativeRuntime`
  - `ui/strip/`：`RecentStripOverlayController`, `NativeTaskSnapshotLoader`
  - `ui/MainActivity.kt`：极简 Material You 设置页
- `docs/`：
  - [DEVICE_BASELINE_AND_N1_REPORT.md](docs/DEVICE_BASELINE_AND_N1_REPORT.md)
  - [N2_N3_VERIFICATION_REPORT.md](docs/N2_N3_VERIFICATION_REPORT.md)
  - [N4_VERIFICATION_REPORT.md](docs/N4_VERIFICATION_REPORT.md)
  - [N5_VERIFICATION_REPORT.md](docs/N5_VERIFICATION_REPORT.md)
  - [N6_N7_N8_ACCEPTANCE_REPORT.md](docs/N6_N7_N8_ACCEPTANCE_REPORT.md)
  - [SAFE_RECOVERY.md](docs/SAFE_RECOVERY.md)

## 编译与测试

```powershell
cd E:\codex\pad-stage-manager\stage-native
.\gradlew.bat :core:test :module:assembleDebug
```

## 快速启用与安全回滚（Vector CLI）

```bash
# 启用并限定作用域（不注入 system_server）
adb shell su -c "/data/adb/lspd/cli modules enable dev.stagelab.nativehook"
adb shell su -c "/data/adb/lspd/cli scope set dev.stagelab.nativehook com.android.systemui/0 com.android.launcher/0 dev.stagelab.nativehook/0"

# 紧急禁用与恢复 ColorOS 原生体验
adb shell su -c "/data/adb/lspd/cli modules disable dev.stagelab.nativehook"
adb shell su -c "pkill -f com.android.systemui; pkill -f com.android.launcher"
```
