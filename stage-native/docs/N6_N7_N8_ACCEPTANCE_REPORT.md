# StageLab Native — N6 / N7 / N8 综合真机验收报告

## 1. 测试环境与交付产物

- **真机**：OPPO Pad mini (`OPD2515`), ColorOS `17.0.0.103(CN01)`, Android 17 (API 37), SELinux `Enforcing`
- **Hook 环境**：KernelSU `3.3.1` + NeoZygisk `v2.4` + Vector `v2.2 (API 102)`
- **作用域**：`com.android.systemui/0`, `com.android.launcher/0`, `dev.stagelab.nativehook/0`（**零 `system_server` 注入**）
- **模块 APK**：`stage-native/module/build/outputs/apk/debug/module-debug.apk` (`dev.stagelab.nativehook`, `1.0.0-native`)

## 2. 阶段实现与真机验收清单

| 阶段 | 验收项 | 状态 | 实测依据 |
| --- | --- | --- | --- |
| **N0** | 冻结旧工程 (`26349b4`)，建立独立 `stage-native` 子工程 | ✅ 通过 | 旧 `app/` 零改动，完整保留 Git 历史 |
| **N1** | OPPO Pad mini 原生小窗能力调查 | ✅ 通过 | 实测 `windowingMode=100` 原生支持 4 窗同时可见 (`visibleFlexibleTasks:4`)，具备原生圆角/阴影/顶部 `···` 菜单/缩放手柄 |
| **N2** | 最小只读 SystemUI Hook | ✅ 通过 | 实时观测 `ShellTaskOrganizer` & `FullscreenTaskListener` (`scenario=1`)，零崩溃 |
| **N3** | `:core` 冻结 Stage 状态机（S1–S6 + 边界 + 50 次快速切换 + 重启恢复） | ✅ 通过 | `.\gradlew.bat :core:test` 全部 9 项核心用例通过 |
| **N4** | 原生窗口与 Stage 同步 | ✅ 通过 | Stage A (`settings`+`calculator`) ⇄ Stage B (`alarmclock`+`note`) 真机切换，Task ID 全程不变，停放窗口 `visible=false, running=true`，恢复保留原内容 |
| **N5** | 左侧悬浮 Recent Strip + 原生 `TaskSnapshotManager` | ✅ 通过 | 5 个连续 Stage 真机实测：左侧自然悬浮恰好 4 张真实窗口画面缩略图（便签/闹钟/计算器/设置）+ 多窗层叠组合，触摸点击直接切换 Stage，支持右拖合并与隐藏/左边缘呼出 |
| **N6** | 原生 Dock (`OplusLauncher`) 与顶部 `···` 菜单 (`FlexibleMenuManager`) 集成 + Material You 设置页 | ✅ 通过 | `LauncherDockHook` 接管原生 Taskbar 点击 (S1) 与拖拽合并提示 (S3)；`FlexibleMenuHook` 同步原生 `···` 菜单最小化/关闭；设置页支持开关/隐藏侧栏/安全模式/同步窗口 |
| **N7** | 锁屏隐藏/解锁恢复、横竖屏旋转自适应、手势与系统任务同步 | ✅ 通过 | `ACTION_SCREEN_OFF` 自动卸载 Recent Strip、`ACTION_USER_PRESENT` 恢复、`ACTION_CONFIGURATION_CHANGED` 重排；系统 Recents 组合卡片受限于 `OplusLauncher` 私有 Recents 视图模型，已记录为独立后续里程碑 |
| **N8** | 连续切换压测与模块关闭还原 | ✅ 通过 | 10 轮连续真机快速切换 (`648 ⇄ 649`) 零崩溃、零异常；`disable_module` 彻底移除 `StageLabRecentStrip` 并保留用户应用任务 |

## 3. 诚实边界说明（不夸大未验证项）

1. **后台实时画面**：Android 17 / ColorOS 17 对 `visible=false` 的后台任务停止生产新帧，左侧 Recent Strip 明确使用 `android.window.TaskSnapshotManager` 原生静态高保真快照（并在 `FLAG_SECURE` 时降级为应用图标卡片），不冒充后台实时 Surface 镜像。
2. **系统 Recents 组合卡片**：ColorOS 原生 Recents (`OplusFallbackRecentsActivity`) 以单 Task 卡片为主，暂未替换或重写其 Recents APK，后续可在不替换系统 APK 前提下进一步研究 `TaskStackView` 组合卡片渲染。
3. **1 小时连续长稳老化与音视频/游戏综合耐久**：已完成 10 轮连续实机切换与 50 轮状态机压测；1 小时真实日常使用老化建议在平板日常使用场景下持续观察 `adb logcat -s StageLabNativeHook:V`。
