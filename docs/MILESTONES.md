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

### P2 横屏工作台与点击切换 —— 已实现，真机通过

- **横屏左栏常驻**：`computeLayout()` 按方向划分布局——横屏时 rail 为固定左栏（238dp），工作区自动让位（`rail.right + 14dp`）；竖屏 rail 变为抽屉，左边缘唤出（`edgeStrip`），切组后自动收起。
- **磁贴分级**：系统 freeform 最小窗口 ~220dp 限制了实时预览数量。最近一组用**真实任务实时预览**磁贴（256dp 高，`focusable=true` 保证能被合成到宿主之上），更早的组用紧凑磁贴（图标计数 + 名称）。实测修复了 `focusable(false)` 导致预览黑洞的问题。
- **点击切换**：左栏点击由透明 overlay 层接管（真实预览窗口在宿主之上，Compose 点不到）。磁贴点击 → `activateGroup()` → 目标组 `ensureGroupAlive` + 几何恢复 + 聚焦前台窗口。
- **滚动**：组数超出栏高时出现 ▲▼ 箭头（overlay 点击层内实现 `scrollRail`），滚出视野的组任务自动 `toBack` 停放。
- **空状态**：无其他组时左栏显示"最近工作台/切换后会出现在这里"引导文案，功能入口不消失。
- **验收（真机，三组四轮轮切）**：任务 ID 完全稳定（502/523/386 → 532/524 → 526 → 532/524），无重启；窗口位置与层级恢复；顺序正确。
- 证据：`docs/p2-rail.png`（横屏常驻栏 + 实时预览 + 紧凑磁贴 + 滚动箭头）、`docs/p2-landscape.png`、`docs/p2-switch.png`。

### P4 窗口完整控制 —— 已完成，真机通过

- **最小化改为 iPad 语义**：黄色圆点不再"塞到宿主后面 + 底部托盘"，而是把窗口从当前组**移除**，作为单窗口组放到左侧最近列表顶部（标题用应用名）；任务与进程保持存活（`dumpsys` 确认 task 535 仍在）。
- **恢复**：点击左栏该组卡片 → 窗口整体回到中央工作区（`activateGroup`），几何保留。
- **关闭**：红点只关闭所选 Task；`closeWindow` 在引擎返回失败时保留窗口，不误删。
- **缩放**：8 个手柄（四角 + 四边）全部接入 `handleTouch`；右下角与右边缘实测改变 bounds。
- **最大化/还原**：绿点与双击标题栏切换（`toggleZoom`，保存恢复矩形，扩到工作区内 4dp）。
- **长按窗口控制菜单**：`ChromeOverlayView` 实现真正 420ms 长按检测（同时兼容交通灯单击与标题栏拖动），`WindowChromeController.showWindowMenu()` 在真实任务之上弹出悬浮控制菜单，支持最大化/还原、贴靠左侧半屏、贴靠右侧半屏、居中排列、最小化到最近列表、移至新工作台、移至已有工作台与关闭窗口。
- 移除了 Dock 中开发者风格的"最小化托盘"（MinimizedTray）。
- 验收证据：`docs/p4-minimize.png`、`docs/p4-menu.png`（真机长按弹出并点击"贴靠左侧半屏"，`taskId 547` bounds 实测更新为 `712,143,1575,1360`）。

### P3 工作台拖放闭环 —— 已完成，真机通过

- **Dock 普通点击 vs 拖放语义区分**：`StageWorkspace.activateOrOpenApp()` 实现 iPad 语义——普通点击 Dock 应用时，若该应用已有窗口则切换到其工作台并聚焦（实测 Via taskId 545 不变），若无且当前工作台非空则新建工作台（实测点击便签自动创建"便签"工作台，taskId 546）；仅长按拖入中央工作区才将应用加入当前工作台，拖到左栏磁贴则加入该工作台。
- **左栏磁贴拖入中央工作区**：`WindowChromeController.railOverlay` 支持向右水平拖拽磁贴，松手落在工作区时调用 `addGroupToActiveGroup()` 将该组窗口移入当前工作台（真机 `input swipe 330 400 1700 800 500` 实测通过，便签 taskId 546 与 Via taskId 545 同组并存，空源组自动清理）。
- **中央窗口拖到左栏 / 跨组 / "+"**：`handleWindowDrop()` 路由到 `moveWindowToGroup` / `minimize` / `createGroup`。
- **拖动目标高亮反馈**：新增非触摸 `DropHighlightOverlayView`（位于真实 Task 之上，不被实时预览遮挡），拖动窗口、左栏卡片或 Dock 图标时实时高亮目标区域并显示操作标签（"加入当前工作台" / "移入 …" / "移出当前工作台" / "新建工作台"）。
- 验收证据：`docs/p3-dragout.png`、`docs/p3-rail-drag-in.png`。

### P5 Dock 完整重构 —— 已完成，真机通过

- **删除开发者风格操作**：彻底移除 Dock 中的 `◧ 应用组`、`⏻ 关闭 Stage Manager` 和文字 `＋` 按钮。
- **固定 / 最近 / 建议分区**：`DockConfigStore` 持久化用户固定应用列表与最近使用记录；Dock 左侧为固定应用、中间细分隔线、右侧为最近/建议应用，最右侧为 iPad 风格 2×2 彩色网格应用库图标；横竖屏自动调整可见图标数量。
- **活动状态指示点**：当前活动工作台中的应用下方显示亮白色圆点，后台工作台运行中的应用显示半透明圆点。
- **长按应用上下文菜单（兼容拖拽）**：长按静止弹出悬浮上下文菜单（加入当前工作台、仅支持多实例应用显示的新建窗口、显示所有窗口、固定/从 Dock 移除、自动隐藏 Dock），手指移动超过阈值自动关闭菜单并无缝进入拖放。
- **自动隐藏与底边唤出**：支持持久化 `dockAutoHide`；隐藏时工作区和左栏向下延展，通过居中窄底边条上滑唤出（不冲突系统 Home/Back 手势）。
- 验收证据：`docs/p5-dock.png`（重构后 Dock、活动状态圆点、左栏计算器实时预览）、`docs/p5-dock-menu.png`（真机长按 Via 图标弹出菜单，正确隐藏不支持多实例应用的 New Window 项）。

### P6 应用窗口管理 —— 已完成，真机通过

- **真实多实例能力探测**：`supportsMultiInstance()` 检查目标 Activity 的 `launchMode` / `documentLaunchMode`；`StageWorkspace.openNewWindow()` 仅对真正支持多实例的应用创建独立新任务，绝不为 `singleTask` / `singleInstance` 应用伪造重复窗口（实测便签支持多实例显示"+ 新建窗口"并同屏运行 `Task #546` 与 `Task #547`，Via 为 `singleTask` 自动隐藏 New Window）。
- **App Exposé / Show All Windows**：新增 `StageExposeOverlay`（位于真实任务之上的全屏半透明浮层），支持从 Dock 上下文菜单或窗口控制菜单打开，展示同一应用在所有工作台中的全部窗口，支持一键切换聚焦、将后台组窗口拉入当前工作台、关闭单个窗口及新建窗口。
- **所有工作台概览（Stage Overview）**：`StageExposeOverlay.showStageOverview()` 展示全部工作台及其窗口列表，支持快速切换与新建工作台。
- 验收证据：`docs/p6-expose.png`（便签双独立真实窗口 `Task #546` / `Task #547` 的 App Exposé 视图与"+ 新建窗口"按钮）。

### P7 手势体系 —— 已完成，真机通过

- **左侧最近工作台自动隐藏与左边缘滑入**：新增 `railAutoHide` 持久化开关（横屏默认常驻，开启自动隐藏后左边缘右滑临时唤出，切换工作台后自动收起）。
- **底部安全手势条（不抢占 ColorOS 系统导航/Home/IME）**：在系统导航栏 inset 之上居中放置带微弱抓手指示条的 `bottomEdgeStrip`，真机实测彻底避开 ColorOS 底部系统 Dock 拦截：
  - 底部快速上滑：唤出自动隐藏的 Dock；
  - 底部上滑停顿（或大幅上滑）：弹出 **"所有工作台" Stage Overview**；
  - 底部左右滑动：调用 `StageWorkspace.switchAdjacentGroup()` 切换前后最近工作台。
- **窗口拖动与八向缩放手势**：继续由标题栏和八方向手柄稳定承接。
- 验收证据：`docs/p7-overview.png`（真机 `input swipe 1400 1585 1020 1585 260` 实测从"工作区 1"切到"计算器" #548，随后 `input swipe 1260 1585 1260 1260 460` 实测唤出所有工作台概览，ColorOS 系统导航零冲突）。

### P8 真实窗口动画 —— 已完成，真机通过

- **路线结论（ADR-004 复核）**：保留 FREEFORM Task 路线以确保原生触摸、IME、Back 和无障碍 100% 不退化（`TaskOrganizer` 接管 leash 仍会破坏系统输入命中区域）。
- **真实 Task 缓动动画器（`StageWindowAnimator`）**：采用 cubic-out 缓动曲线（5–6 个关键帧 / 130–160ms）同步驱动真实系统 Task bounds（`IActivityTaskManager.resizeTask`）与窗口装饰层，覆盖：
  - 工作台切换动画（旧工作台前台窗口滑向左栏预览位，新工作台前台窗口从左栏滑入中央工作区）；
  - 最小化动画（窗口滑入左栏首位后留存为实时预览）；
  - Dock 启动进入工作区动画（从底部上浮进入目标位置）；
  - 窗口排列预设动画（左半屏 / 右半屏 / 居中 / 最大化平滑过渡）。
- **真机验收**：在 OPPO Pad mini 上连续点击左栏切换"计算器"与"工作区 1"，窗口平滑过渡且 `taskId`（524）与几何坐标（`1121,305,2046,1198`）完整保留，无进程重启或输入异常。
- 验收证据：`docs/p8-animated-switch.png`。

## 第二阶段进度状态（P1–P8 已提交）

| 阶段 | 状态 | 关键提交 |
|---|---|---|
| P1 真实圆角/chrome | 真机通过（浏览器/便签/B站） | 91b7b75 |
| P2 横屏工作台 + 点击切换 | 真机通过（三组轮切，taskId 稳定） | 73d84e7 |
| P3 拖放闭环 | 真机通过（Dock点击/拖入、左栏拖入、拖出、目标高亮） | f370050 |
| P4 窗口完整控制 | 真机通过（八向缩放、最小化语义、长按控制菜单与排列） | f5aafea |
| P5 Dock 完整重构 | 真机通过（固定/最近分区、状态点、长按菜单、自动隐藏） | a4bc2e9 |
| P6 应用窗口管理 | 真机通过（多实例探测、App Exposé、Show All Windows） | 86fa218 |
| P7 手势体系 | 真机通过（左边缘/底边唤出、上滑概览、底边左右切组） | f4b1240 |
| P8 真实窗口动画 | 真机通过（`StageWindowAnimator` 缓动真实 Task bounds，保护原生输入） | 本次提交 |

### 遗留清单（按优先级）

1. **P4 余项**：长按窗口控制菜单（关闭/最小化/最大化/移到组）。
2. **P3 余项**：拖动过程中目标磁贴高亮；Dock 拖到磁贴的语义；左栏把窗口拖回中央。
3. **P5 Dock 重构**：固定/最近/建议应用、长按菜单、自动隐藏（删除"应用组/退出"入口）。
4. **P6**：同应用多窗口（需多实例探测）、App Exposé。
5. **P7**：手势体系（底边上滑概览、左右切组）。
6. **P8**：真实窗口过渡动画（ADR-004 保持，逐帧 bounds 为降级方案）。
7. **P9**：菜单栏/快捷键/外接显示器。
