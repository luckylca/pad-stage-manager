# StageLab Native (Gradle Subproject)

Android 13–17 Xposed / LSPosed system-level Stage Manager module (`io.github.luckylca.stagelab`).
For full user documentation, screenshots, and LSPosed Release installation, see the root [README.md](../README.md) ([简体中文](../README_CN.md)).

## Architecture

- `core/` — Pure Kotlin/JVM dynamic Stage state machine (`Stage`, `StageTask`, `StageSession`, `StageRegistry`, `StageTransitionController`, `NativeWindowBridge`, `RecentStageController`, `DockIntegrationController`) with JUnit tests (`:core:test`).
- `module/` — Android Xposed module APK (`io.github.luckylca.stagelab`, `minSdk = 33`, `targetSdk = 36`, `compileSdk = 37`):
  - `xposed/` — `StageLabHookEntry`, `SystemServerFlexibleHook`, `LauncherBackdropHook`, `LauncherDockHook`, `FlexibleMenuHook`, `HookGuard`
  - `compat/` — `OplusCompatAdapter` multi-version ROM / class / method / bundle probe adapter (`ColorOS 13–17`, `OxygenOS`, `realme UI`, `AOSP`)
  - `bridge/` — `ColorOsNativeWindowBridge`, `StageNativeRuntime` (with `WINDOWING_MODE_FREEFORM` fallback, orientation-aware tiling & scale recovery)
  - `ui/` — `StageLayoutMetrics` (`DeviceTier` + cutout/nav-bar `DisplaySnapshot`), `MainActivity` (Material You control panel & live diagnostics), `StageModeNotificationHelper`, `StageModeStateReceiver`
  - `ui/dock/` — `StageDockOverlayController`, `AppSearchOverlayController` (120fps physics spring + unified drag & drop + persistent pins)
  - `ui/strip/` — `RecentStripOverlayController`, `StageMagicTransitionOverlay`, `NativeTaskSnapshotLoader`

## Scope

In LSPosed / Vector (`xposedscope`), select:
- `system`
- `com.android.systemui`
- `com.android.launcher`

## Build & Test

```powershell
cd E:\codex\pad-stage-manager\stage-native
.\gradlew.bat :core:test :module:assembleDebug :module:assembleRelease --offline
```

## ADB Debug Deploy & Safe Rollback

```bash
# Deploy and soft-reboot (KernelSU LKM friendly)
adb push stage-native/module/build/outputs/apk/debug/module-debug.apk /data/local/tmp/stagelab-native-debug.apk
adb shell su -c "pm install -r /data/local/tmp/stagelab-native-debug.apk && sleep 2 && /data/adb/ksud soft-reboot"

# Enable & set scope via Vector CLI
adb shell su -c "/data/adb/lspd/cli modules enable io.github.luckylca.stagelab"
adb shell su -c "/data/adb/lspd/cli scope set io.github.luckylca.stagelab system/0 com.android.systemui/0 com.android.launcher/0 io.github.luckylca.stagelab/0"

# Disable Stage Mode or roll back completely
adb shell am broadcast -a io.github.luckylca.stagelab.ACTION_STAGE_CMD --es cmd disable_module -p com.android.systemui
adb shell su -c "/data/adb/lspd/cli modules disable io.github.luckylca.stagelab"
adb shell su -c "pkill -f com.android.systemui; pkill -f com.android.launcher"
```

See [docs/SAFE_RECOVERY.md](docs/SAFE_RECOVERY.md) and root [README.md](../README.md) for more details.
