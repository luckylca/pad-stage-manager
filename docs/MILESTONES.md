# Stage Manager 重构里程碑记录（M0–M6）

> 记录日期：2026-11-20　设备：OPPO Pad mini（OPD2515 / ColorOS 17 / Android 17 / SDK 37）
> 所有“真机通过”均指在本设备上通过 ADB 实际执行并观察，截图见 `docs/m1-*.png`、`docs/m2-*.png`、`docs/m3-*.png`、`docs/m4-*.png`。

## M0 基线冻结

- 保留 `core/privileged`、`core/ipc`、`core/shizuku`、`core/stage` 的全部底层能力。
- 新增 `docs/PRODUCT_REDESIGN_ANALYSIS.md` 作为产品基线（已提交）。
- 确认 HEAD 构建通过，Shizuku Shell（UID 2000）连接可用。

## M1 产品入口与生命周期 —— 已实现，真机通过

| 项 | 状态 | 证据 |
|---|---|---|
| 极简 OFF 页（一个开关 + 状态文字） | 完成 | `docs/m1-control.png` |
| 首次使用才走 Shizuku 授权 | 完成 | 授权弹窗 + 失败卡片只在缺权限时出现 |
| `ControlActivity` / `StageHostActivity` 分离 | 完成 | 两个 Activity，职责互斥 |
| OFF/STARTING/ON/STOPPING 状态机 | 完成 | `StageModeController` |
| 关闭模式不杀应用 | 完成 | 退出后前台应用 `mBounds=Rect(0,0-1680,2520)`，任务仍在 |
| 系统设置安全恢复 | 完成 | `StageSettingsGuard`：先记录原值，只在“当前值仍是我们写入的值”时恢复 |
| `hello()` 不再写全局设置 | 完成 | 全局设置只在 `StageModeController.start()` 中写入 |
| 进程死亡后的状态 | 完成 | 启动一律为 OFF；只有连接成功才会进入 ON，避免空白工作区 |
| ColorOS 冻结 Shizuku 导致断连 | 缓解 | 启动时把 Shizuku 与本应用加入 deviceidle 白名单 |

## M2 视觉结构 —— 已实现，真机通过

- 移除品牌头栏、诊断/帮助按钮、演示/重整/全部关闭按钮与 taskId 芯片。
- 左侧最近应用组栏（滑入面板 + 组卡片 + 实时预览 + 新建组按钮）。
- 中央自由工作区：多窗口、层叠、重叠。
- 底部 Dock：真实应用图标（Via / 便签 / 信息 / 计算器 / B 站 / 音乐…）、应用库入口、退出入口。
- 窗口外观：iPad 风格标题栏（红黄交通灯 + 居中标题 + 焦点蓝边）、右下缩放手柄、右/下边手柄。
- 证据：`docs/m2-windows.png`（三窗重叠 + 标题栏）、`docs/m2-rail.png`（左侧组栏含实时预览）。

## M3 组与窗口模型 —— 已实现，真机通过

- `StageSession` / `StageGroup` / `StageWindow` / `WindowGeometry` 持久化到 SharedPreferences。
- taskId 只是运行时句柄；窗口拥有稳定 id、几何、z 序、最小化标记、所属组。
- 组切换：点击左侧组 → 该组窗口回到工作区，原活动组进入左栏；实测 `activeGroup` 切换并恢复两个窗口。
- 恢复：进程/任务丢失时，先尝试接管同应用的孤儿任务，找不到才重新启动该应用（避免重复窗口）。
- 证据：`docs/m3-switch.png`。

## M4 真实交互 —— 已实现，真机通过

交互不是“改后端 API”，而是真实的触摸管线：窗口标题栏/缩放手柄是 `TYPE_APPLICATION_OVERLAY` 悬浮窗，位于真实任务之上，其余区域仍由应用原生接收触摸。

| 手势 | 结果 | 证据 |
|---|---|---|
| 拖动标题栏移动窗口 | 通过（`mBounds` 跟随手指变化） | `docs/m4-drag.png` |
| 右下角缩放 | 通过（宽高同时变化） | `docs/m4-resize.png` |
| 右边/下边缩放 | 通过 | 真机测量 |
| 最小化（黄灯） | 通过（任务移到舞台之后，画面消失，状态保留） | `docs/m4-minimize.png` |
| 从最小化托盘恢复 | 通过 | 同上 |
| 关闭（红灯） | 通过（`removeTask`，失败时保留窗口不误删） | 真机 |
| Dock 点击打开应用 | 通过 | `docs/m2-windows.png` |
| 长按 Dock 拖入工作区 | 已实现（拖拽落点决定窗口位置） | 待补自动化 |

## M5 实时预览与动画 —— 部分完成，结论明确

**实时预览（不做截图）**：左栏组预览是**真实任务窗口**被移动到栏位并缩放到可容纳的尺寸（系统最小 220dp），由 SurfaceFlinger 实时合成。实测左栏预览内容持续变化（通知列表、B 站视频）。这不是截图刷新。

**动画**：
- 已实现：标题栏拖动/缩放的逐帧跟随（真实任务 bounds 逐帧更新）、焦点态动画、Dock 长按拖拽影子、左栏滑入滑出、组切换。
- 未实现（诚实结论）：`SurfaceControl` 级别的窗口位移动画（需要 TaskOrganizer 的 leash）。逐帧 `resizeTask` 的实测表现是“跟手但应用会 relayout”，在拖动 800ms 的过程中未出现 Activity 重建（demo 目标声明了 `configChanges`），但**第三方应用逐帧 resize 可能重建 Activity**，因此没有把“逐帧 resize”当作通用动画方案。
- 路线对照（实验结论）：`TaskOrganizer` 注册、`createRootTask(int,int,IBinder)`、`onTaskAppeared` 回调在本机可用（`docs/SHIZUKU_TASK_PROBE.md`），但把 leash 重挂到应用 SurfaceView 会失去原生输入命中，必须自建输入转发/IME/Back 路由；因此**保留 ADR-004**，本轮不切换路线。

## M6 真机验收 —— 部分完成

已验证：浏览器（Via）、便签、信息（聊天/通知）、B 站（视频）同时为真实任务；三窗口并存与重叠；组创建/切换；拖动；缩放；最小化/恢复；关闭；输入法（在托管窗口内可弹出）；返回键；横竖屏（上一轮 + 本轮布局均适配）；服务断线自动重连。

未完成：一小时长稳/泄漏压测、DRM/FLAG_SECURE 场景、系统过渡动画级联。

## 已知限制（本轮新增）

1. `WindowContainerTransaction.setHidden` 对本机系统组织的任务无效，因此“最小化/停放”改为把任务移到舞台宿主之后（宿主不透明全屏，视觉上等价隐藏）。`docs/DECISIONS.md` 已更新。
2. `handle` 拖动使用逐帧 `setBounds`；第三方应用若无 `configChanges`，拖动/缩放过程中可能看到自身重排。
3. 左栏预览高度受系统最小窗口高度限制（约 220dp），因此栏位比 iPad 略高；比 iPad 的“纯缩略图”更占空间，但**保持真实动态画面**。
4. 打开应用时若应用不在当前活动组，窗口会加入当前组；跨组拖动尚未提供手势（模型层 `moveWindowToGroup` 已实现）。

## 第二阶段（P1–P9 逐项）

> 依据 `docs/IPAD_STAGE_MANAGER_BEHAVIOR_SPEC.md`；差距清单见 `docs/IPAD_STAGE_MANAGER_GAP_MATRIX.md`。

### P1 窗口真实圆角与 chrome —— 已实现，真机通过（第三方应用）

- 方案：**非触摸角落遮罩 Overlay**。每个窗口一块 `FLAG_NOT_TOUCHABLE` 的 decoration 浮层，用“正方形 − 四分之一圆”的 Path 把四个角涂成桌面背景色，从而在不接触 Task Surface、不注入输入的前提下得到真实圆角。
- 与 iPad 一致：标题栏和内容区是同一个圆角窗口；焦点窗口有 2px 蓝色描边（非焦点无描边）；宿主绘制柔和阴影；圆角半径 22dp。
- 遮挡处理：当另一个窗口盖住某个角时，该角的遮罩自动关闭，避免把桌面色画到前台窗口上（实测修复了“下层窗口的角涂到上层窗口”的缺陷）。
- 缩放：8 个不可见手柄（四角 + 四边），只在右下角绘制极淡提示；内容区不受任何触摸影响。
- 决定：SurfaceControl/TaskOrganizer 的圆角需要 leash，会牺牲原生输入；按 ADR-004 保留 FREEFORM + Overlay 路线（见 `DECISIONS.md` ADR-013）。
- 证据：`docs/p1-rounded.png`（浏览器 + 便签 + B站 三应用同屏，四角圆润），`docs/p1-corner-zoom.png`（3× 放大，圆角与描边连续）。
