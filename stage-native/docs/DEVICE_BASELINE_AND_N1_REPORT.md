# StageLab Native — 设备基线与 N1 ColorOS 17 原生小窗调查报告

## 1. N0 工程冻结基线

- **旧工程路径**：`E:\codex\pad-stage-manager`（保留完整 Git 历史与 `app/` 模块，仅作历史参考与回归工具，不删除）。
- **实际 Git HEAD（冻结基线）**：`26349b4`（分支 `main`，工作区干净；原说明基线 `9f746a9` 之后已有 12 个提交，以实际 `26349b4` 为准）。
- **新子工程路径**：`E:\codex\pad-stage-manager\stage-native`（独立 Gradle 工程，包含 `:core` 状态机与 `:module` LSPosed 模块）。

## 2. 真机系统与 Hook 环境基线

于 2026-11-21 通过 ADB (`d5163fa2`) 实测采集：

| 项目 | 实测值 |
| --- | --- |
| 机型 / 代号 | `OPD2515` (`OP6548L1`) — OPPO Pad mini |
| 系统版本 | ColorOS `OPD2515_17.0.0.103(CN01)` / `V17.0.0` |
| Android / SDK | Android 17 / API 37 |
| 内核 | `6.12.69-android16-6-g242e1a07f878-ab15865446-4k` (aarch64) |
| 显示参数 | Physical `1680x2520`, 420 dpi（横屏 `2520x1680` / `960dp x 640dp`） |
| SELinux | `Enforcing`（严禁擅自关闭） |
| Root | `KernelSU 3.3.1` (`/system/bin/su`, `u:r:ksu:s0`) |
| Zygisk | `NeoZygisk v2.4 (301-cc0bbba-release)` (`/data/adb/modules/zygisksu`) |
| Xposed / LSPosed | `Vector v2.2 (3119-6f6b976e-JingMatrix-Vector)` (`/data/adb/modules/zygisk_vector`) — 原生支持 Android 8.1 ~ 17，API Version 102 |
| 命令行管理工具 | `/data/adb/lspd/cli` (`vector-cli`: `status`, `modules`, `scope`, `config`, `db`, `log`) |
| Freeform 系统开关 | `enable_freeform_support=1`, `force_resizable_activities=1`, `UNIVERSAL_RESIZABLE_BY_DEFAULT=true` |
| 关键系统包 | `com.android.systemui` (`/system_ext/priv-app/SystemUI/SystemUI.apk`), `com.android.launcher` (`/system_ext/priv-app/OplusLauncher/OplusLauncher.apk`) |
| 关键框架 JAR | `/system/framework/oplus-framework.jar`, `/system/framework/oplus-services.jar` |

## 3. N1 真机实测：ColorOS 17 原生小窗能力验证

### 3.1 `windowingMode=5` (AOSP Freeform) vs `windowingMode=100` (ColorOS Flexible Window)

1. **AOSP Freeform (`am start --windowingMode 5`)**：
   - 实测可同时打开 4 个 `wmMode=5` 任务（`settings`, `calculator`, `alarmclock`, `note`），全部 `running=true visible=true`，支持 `am task resize` 及点击原生焦点提升。
   - **关键限制**：ColorOS 17 的 `ShellTaskOrganizer` 未为 `TASK_LISTENER_TYPE_FREEFORM` 注册 Listener（`listener=null`），因此纯 `wmMode=5` **缺少原生圆角、原生阴影、顶部 `···` 菜单、缩放手柄和拖动条**。
2. **ColorOS 原生自由浮窗 / Flexible Window (`am start --windowingMode 100`)**：
   - 实测启动 `--windowingMode 100` 直接进入 ColorOS 17 原生 **Flexible Window**（`FlexibleTaskController` 管理，`scenario=1`, `scale=0.6180555`, `hasCaption=true`, `flexibleWindowScenario=true`）。
   - **原生视觉与交互 100% 完整**：系统原生大圆角、柔和阴影、顶部原生 `···` 菜单胶囊（聚焦窗口实心 / 非聚焦空心）、左下角弧形缩放手柄、底部拖动条，且底层原生桌面与底部玻璃态 Dock 保持可见。
   - **4 窗同时运行实测通过**：连续启动 4 个应用（`com.android.settings`, `com.coloros.calculator`, `com.coloros.alarmclock`, `com.coloros.note`），`WMShell` 报告 `FullscreenTaskListener: visibleFlexibleTasks:4`，4 个窗口全部 `running=true visible=true`，`FlexibleTaskController.mFlexibleTasks` 同时持有 4 个 Task。
   - **第 5 窗实测（溢出行为）**：再启动第 5 个应用（`com.coloros.calendar`）时，ColorOS 自动将最早的第 1 个任务（`com.android.settings`, task 560）置为 `visible=false`，但**保持 `running=true` 不杀任务**，屏幕上恰好保持 `visibleFlexibleTasks:4`。
   - **停放与后台保活**：按 `KEYCODE_HOME` 返回桌面时，4 个 Flexible Task 仍保留在 `FlexibleTaskController` 中且 `running=true`；原生退出到后台策略由 `com.android.server.wm.ExitFlexibleTaskToBackStrategy` 提供。

### 3.2 N1 关键问题结论汇总

| # | 问题 | OPPO Pad mini (ColorOS 17) 实测结论 |
| --- | --- | --- |
| 1 | 原生小窗由哪个进程管理 | `system_server` (`FlexibleTaskController`) 负责任务状态与策略；`com.android.systemui` (`ShellTaskOrganizer` + `FullscreenTaskListener` + `FlexibleWindowTransitionHandler`) 负责 Shell 组织、动画与 `WindowDecoration` / `Caption` |
| 2 | 原生小窗如何创建 | 通过 `windowingMode = 100`（ColorOS Flexible / Zoom 入口）或 `FlexibleWindowManager` API 触发，系统自动映射为 `FullscreenTaskListener` 下的 `scenario=1` Flexible Task |
| 3 | 如何打开指定 App 为原生小窗 | `am start --windowingMode 100 <component>` 或 `ActivityOptions` 设置 launchWindowingMode=100 / `FlexibleWindowManager` |
| 4 | 原生窗口 bounds 如何调整 | `FlexibleWindowManager.adjustFlexibleTask(Bundle)` / `applyTransaction(WindowContainerTransaction)` / `ShellTaskOrganizer.applyTransaction(...)` |
| 5 | 原生窗口如何提升焦点 | 系统原生点击窗口即由 WMS 提升焦点与 Z-order（已真机验证 `mCurrentFocus` / `mFocusedApp` 切换） |
| 6 | 如何停放和恢复而不杀任务 | 停放：`ExitFlexibleTaskToBackStrategy` 或 `WindowContainerTransaction.reorder(token, false)` 移至后台保活；恢复：通过原 `taskId` 触发 `reorder(token, true)` / `moveTaskToFront`，不重建 Activity |
| 7 | 是否可同时运行 4 个原生小窗 | **是（已真机实测并截图证实）**：`visibleFlexibleTasks:4`，第 5 个自动将最早任务转入后台保活 |
| 8 | 原生动画和圆角由谁负责 | `com.android.systemui` 中的 `FlexibleWindowTransitionHandler` / `FlexibleTaskTransitionHandler` / `ZoomTransitionController` 与 `WindowDecoration` / `FullscreenTaskWindowDecorController` |

## 4. ColorOS 17 实测类名与 Hook 映射表（来自真机 DEX 扫描）

### 4.1 `com.android.systemui` (`/system_ext/priv-app/SystemUI/SystemUI.apk`)
- `com.android.wm.shell.ShellTaskOrganizer`
  - `onTaskAppeared(android.window.TaskAppearedInfo)`
  - `onTaskInfoChanged(android.app.ActivityManager$RunningTaskInfo)`
  - `onTaskVanished(android.app.ActivityManager$RunningTaskInfo)`
  - `getVisibleFlexibleTaskInfo(): android.util.SparseArray`
  - `hasVisibleFlexibleTasks(): boolean`
  - `getLastFocusedTaskInfo(): android.app.ActivityManager$RunningTaskInfo`
  - `getTaskSurface(int): android.view.SurfaceControl`
  - `applyTransaction(android.window.WindowContainerTransaction)`
- `com.android.wm.shell.fullscreen.FullscreenTaskListener`
  - `onTaskAppeared(android.app.ActivityManager$RunningTaskInfo, android.view.SurfaceControl)`
  - `onTaskInfoChanged(android.app.ActivityManager$RunningTaskInfo)`
  - `onTaskVanished(android.app.ActivityManager$RunningTaskInfo)`
  - `getScenario(android.content.res.Configuration): int`
  - `getVisibleFlexibleTasks(): int`
- Transition Handlers:
  - `FlexibleWindowTransitionHandler`, `FlexibleTaskTransitionHandler`, `FlexibleActivityTransitionHandler`, `ZoomTransitionController`

### 4.2 `oplus-framework.jar`
- `com.oplus.flexiblewindow.FlexibleWindowManager`
  - `getInstance()`, `adjustFlexibleTask(Bundle)`, `applyTransaction(WindowContainerTransaction)`, `getFlexibleWindowState(TaskInfo)`, `getFlexibleMinimizedTaskId()`, `getMinimizedInfo(Context)`
- `com.oplus.flexiblewindow.IFlexibleWindowObserver`
  - `onTaskAppeared`, `onTaskInfoChanged`, `onTaskVanished`, `onTaskReparent`, `onTaskRectOrientationChanged`, `onBackPressedOnTaskRoot`

### 4.3 `oplus-services.jar` (`system_server`)
- `com.android.server.wm.FlexibleTaskController`
- `com.android.server.wm.ExitFlexibleTaskToBackStrategy`
- `com.android.server.wm.FlexibleTaskCaptionView` / `FlexibleCaptionView`

### 4.4 `OplusLauncher.apk` (`com.android.launcher`)
- Taskbar / Dock: `OplusTaskbar*`, `TransientTaskbar*`, `TaskbarPopupPresenter`
- Recents: `OplusFallbackRecentsActivityBinding` 等
