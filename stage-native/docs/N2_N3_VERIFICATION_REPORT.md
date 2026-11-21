# StageLab Native — N2（只读 Hook）与 N3（核心状态机）验证报告

## 1. N3 核心状态机 (`:core`) 单元测试

执行命令：`stage-native\gradlew.bat :core:test` -> **`BUILD SUCCESSFUL`**

覆盖场景（`StageStateMachineTest.kt`）：
- `s1_launchNewAppCreatesStageAndMovesPreviousToRecentTop` — S1 自动新建 Stage，旧 Stage 进 Recent Strip 顶部
- `s1_launchExistingAppActivatesOwningStageWithoutDuplicatingTask` — S1 已有 App 激活原 Stage 不重复创建 Task
- `s2_switchStagePreservesTaskIdsBoundsAndZOrder` — S2 切换 Stage 保留原 Task ID、bounds 与 Z-order，不重启应用
- `s3_mergeFromRecentStageDestroysEmptySourceStage` — S3 拖入合并并自动销毁变空的源 Stage
- `s4_overflowPastFourWindowsEvictsDeterministicallyIntoNewRecentStage` — S4 超过 4 窗按确定性规则溢出到 Recent Strip 顶部（不关闭任务）
- `s5_minimizeMultiWindowSplitsAndSingleWindowShowsDesktop` — S5 多窗拆分 & 单窗最小化回原生桌面
- `s6_closingLastTaskAutoDestroysStageWithoutAffectingOthers` — S6 最后窗口关闭自动销毁 Stage
- `recentStripShowsMaxFourWhileKeepingHistoryAndHandles50RapidSwitches` — Recent Strip 最多显示 4 个、保留更早历史、50 次快速连续切换不变量通过
- `duplicateTaskCallbacksAreIdempotentAndSystemRestartRestoresCleanly` — 重复回调幂等性与系统重启恢复

## 2. N2 最小只读 LSPosed Hook (`:module`) 真机验证

- **目标设备**：OPPO Pad mini (`OPD2515`), ColorOS `17.0.0.103(CN01)`, Android 17 (API 37)
- **Hook 框架**：NeoZygisk v2.4 + Vector v2.2 (API 102)
- **作用域**：严格限定 `com.android.systemui/0` 与 `dev.stagelab.nativehook/0`（未注入 `system_server`）
- **构建产物**：`stage-native/module/build/outputs/apk/debug/module-debug.apk`
- **真机验证结果**：
  - 安装并通过 `/data/adb/lspd/cli` 启用与设置作用域后，软重启 `com.android.systemui`（PID `29988`），零崩溃、零异常。
  - 启动 `com.android.settings` 与 `com.coloros.calculator` 为 `--windowingMode 100` 原生 Flexible Window 时，`StageLabNativeHook` 实时观测到：
    - `FTL.APPEARED task=570 pkg=com.android.settings scenario=1 bounds=Rect(1756, 144 - 2890, 2160)`
    - `STO.APPEARED task=570 pkg=com.android.settings wmMode=1 ...`
    - `FTL.APPEARED task=571 pkg=com.coloros.calculator scenario=1 bounds=Rect(992, 144 - 2126, 2160)`
    - `FTL.CHANGED` 准确捕获焦点从 `task=570 (foc=false)` 切换到 `task=571 (foc=true)`
    - 关闭两个任务后，`FTL.VANISHED` / `STO.VANISHED` 准确触发 `SHADOW_STATE` 自动销毁 `stage-5` 与 `stage-4`（S6）。
  - **真机发现的 2 个细节（将在 N4 精确化）**：
    1. `ShellTaskOrganizer` 存在 `onTaskAppeared` 重载导致单次出现回调 2 次，`:core` 的幂等保护已正确去重，N4 将精确绑定单签名。
    2. Android 17 `TaskInfo` 的可见性应通过 `isVisible()` 方法而非 `isVisible` 字段读取。
