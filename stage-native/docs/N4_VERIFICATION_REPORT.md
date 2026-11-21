# StageLab Native — N4（原生窗口与 Stage 同步）真机验收报告

## 1. 核心原生桥接方案（ColorOS 17 / Android 17 API 37）

在 `com.android.systemui` 进程内实现 `ColorOsNativeWindowBridge` + `StageNativeRuntime`（零 `system_server` Hook）：

- **创建原生小窗**：通过 SystemUI `Context.startActivity` + `ActivityOptions.setLaunchWindowingMode(100)` 启动 ColorOS 原生 Flexible Window（保留系统圆角、阴影、顶部 `···` 菜单、缩放手柄、拖动、焦点、IME & Back）。
- **停放（Park）后台保活**：调用 ColorOS 原生 Binder API `FlexibleWindowManager.getInstance().notifyFlexibleTaskEvent(taskId, FLEXIBLE_TASK_CLOSE_EVENT /* 2003 */, null)`（串行微节拍 `180ms` 避免并发动画互斥），实测触发 `ExitFlexibleTaskToBackStrategy`，任务变为 `visible=false, running=true`，Task ID 完整保留。
- **恢复（Restore）原生小窗**：调用 `ActivityTaskManager.getService().startActivityFromRecents(taskId, options)`（附带 `launchWindowingMode=100`），按原 Z-order 从底到顶恢复存活的 Task ID，不重启应用、保留界面与数据状态。

## 2. 真机实测记录（OPPO Pad mini, OPD2515）

### 场景：Stage A (`settings` + `calculator`) ⇄ Stage B (`alarmclock` + `note`)

1. **Stage B 活跃态**（`WMShell` 实测）：
   - `visibleFlexibleTasks: 2`
   - `task=621 (com.coloros.note)`: `running=true visible=true focused=true bounds=Rect(228, 144 - 1362, 2160)`
   - `task=620 (com.coloros.alarmclock)`: `running=true visible=true focused=false bounds=Rect(992, 144 - 2126, 2160)`
   - `task=619 (com.coloros.calculator)`: `running=true visible=false focused=false`（停放保活）
   - `task=618 (com.android.settings)`: `running=true visible=false focused=false`（停放保活）
2. **S2 切换（Stage B → Stage A）**（`WMShell` 实测）：
   - `visibleFlexibleTasks: 2`
   - `task=619 (com.coloros.calculator)`: `running=true visible=true focused=true bounds=Rect(992, 144 - 2126, 2160)`（**Task ID 619 不变，计算器数值 `994,196,899` 完整保留**）
   - `task=618 (com.android.settings)`: `running=true visible=true focused=false bounds=Rect(1756, 144 - 2890, 2160)`（**Task ID 618 不变**）
   - `task=621 (com.coloros.note)` & `task=620 (com.coloros.alarmclock)`: `running=true visible=false`（停放保活）

## 3. 验收结论

- [x] 中央仅显示 Active Stage 的原生 Flexible 窗口（无自定义容器/标题栏/圆角遮罩）
- [x] 切换 Stage 保留原 Task ID，不重启存活应用
- [x] 原生圆角、阴影、顶部 `···` 菜单、缩放手柄、焦点管理、桌面与原生 Dock 完整保持
- [x] 零 `system_server` 注入，SystemUI 零崩溃
