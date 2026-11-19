# StageLab 产品重构审查与 iPad Stage Manager 复刻方案

> 审查日期：2026-11-19
> 工程：`E:\\codex\\pad-stage-manager`
> 当前 HEAD：`0455ea4`
> 本文是对现有工程和真机验收材料的代码审查及产品定义，不代表下述重构已经实现。

## 1. 产品重新定义

StageLab 不应该是一个带有诊断、测试和窗口管理按钮的“多任务控制台”。

目标产品是一个**Stage Manager 模式开关**：

- 未开启时：应用只有一个明确的开启/关闭控制。首次使用时才进入必要的 Shizuku 授权流程。
- 开启时：直接进入 Stage Manager 工作空间，日常操作不再出现 StageLab 品牌头栏、诊断入口、帮助面板、演示按钮、重整按钮或调试状态。
- 工作空间：复刻 iPad Stage Manager 的主要视觉结构和交互，包括左侧最近应用组、中央可自由排列的窗口、底部 Dock、应用组切换、拖动、缩放、重叠、最小化/关闭和过渡动画。
- 关闭时：退出 Stage Manager 模式并回到 ColorOS 桌面/常规应用体验；不应把“关闭模式”实现成“关闭全部应用任务”。

“完全一样”作为视觉和交互验收目标；Android/ColorOS 无法提供与 iPadOS 相同的私有系统实现，无法完全相同的系统集成细节必须明确标记，不得用静态截图伪装。

## 2. iPad Stage Manager 的目标交互基准

根据 Apple 官方 iPad 用户指南：

- 当前工作应用/应用组位于主工作区；其他最近使用的应用组显示在屏幕左侧。
- 点击左侧最近应用组或 Dock 图标切换；当前组进入最近应用列表，被选中的组进入工作区。
- 一个工作区可以包含多个应用窗口；窗口可以移动、缩放、重叠。
- 可以把左侧最近应用或 Dock 中的应用拖入当前工作区，形成应用组。
- 可以把工作区窗口拖回左侧最近应用区；最小化组内窗口时，该窗口会离开当前组。
- 最近应用栏可自动隐藏，并可从左边缘手势重新显示。

参考：
- https://support.apple.com/guide/ipad/organize-windows-with-stage-manager-ipad1240f36f/ipados
- https://support.apple.com/guide/ipad/work-with-multiple-windows-at-the-same-time-ipad08c9970c/ipados

本项目此前定义的“预览必须保持实时变化”继续保留为额外要求。若 Android freeform 窗口最小尺寸限制导致无法缩成 iPad 侧栏中的小预览，需要单独解决实时预览承载方案，不能偷偷退化为定时截图。

## 3. 当前工程审查结论

### 3.1 已经真正实现、应该保留的底层资产

当前工程并非从零开始，底层工作有价值：

- `StageEngine` / `TaskMutations` 已经通过 Shizuku Shell UID 2000 调用 ActivityTaskManager，创建 FREEFORM 真实任务、读取任务、改变 bounds、切换焦点和关闭任务。
- `ShizukuChannel`、`RootChannel`、`AdbChannel` 共用同一个 JSON 命令协议，特权传输和任务操作已经分层。
- `StageRuntime` 已经管理任务列表、taskId、持久化、轮询、服务重连和固定布局同步。
- 当前窗口是 WindowManager 管理的真实 Android Task，不是 MediaProjection 截图卡片；真机自动化对三个测试任务做了前后画面差分，证明三个测试 Surface 在被动观察时持续变化。
- Shizuku 已在目标 OPPO Pad mini 上实测持有 `MANAGE_ACTIVITY_TASKS`；日常 Stage 功能不必强制要求 Root。
- `AppIconCache` 和 `loadLaunchableApps` 可以作为应用图标、标签和启动入口的基础，但需要扩展多 Activity / 多实例模型。

建议保留并继续复用：
`core/privileged/`、`core/ipc/`、`core/shizuku/`、`core/stage/` 中已经验证的底层逻辑，以及现有真机测试脚本和测试靶。

### 3.2 当前 UI 为什么与目标差距很大

当前 `StageManagerScreen.kt` 是工程验证界面，不是 iPad Stage Manager 的产品界面：

- 顶部有 “STAGE LAB / Android multitasking stage” 品牌头栏。
- 显示 Shizuku / Shell UID 状态胶囊、诊断按钮和帮助按钮。
- 背景有明显网格和开发工具式光晕。
- 窗口布局固定为左侧一个主窗口、右侧窄栏多个预览。
- Dock 显示窗口标题芯片和 taskId 文案，不是 iPad 风格的应用图标 Dock。
- Dock 内含“演示 / 重整 / 全部关闭 / +”等测试/维护操作。
- 未连接时显示权限说明卡片；没有窗口时显示“准备就绪/选择应用”空状态卡片。
- 窗口外框只是 Compose 绘制的 halo，不是完整的窗口标题栏、控制点、缩放手柄和窗口交互系统。

这些元素都应该从正式工作空间中移除。诊断和测试入口可以保留在 Debug 构建或隐藏维护入口，不应占据日常 UI。

### 3.3 当前没有实现的交互

代码核对结果：

- `StageManagerScreen` 没有 `detectDragGestures`、`draggable`、拖动回调或 resize 回调。
- `MainActivity` 只传入 focus、close、reopen、closeAll、reflow、demo、picker 等回调；没有窗口移动/缩放交互接口。
- `StageRuntime` 可以调用后端改变窗口 bounds，但这是程序化布局同步，不等于用户可以拖动窗口。
- 自动化脚本主要验证三个自有 DemoActivity 任务的存在、画面变化和点击切换后 taskId 不变；它没有自动化验证手势拖动、手势缩放、窗口重叠、应用组、Dock 拖放或系统级过渡动画。
- 当前只用 `StageLayoutEngine` 计算一个 main rect 和一列 preview rect，没有自由窗口布局、重叠层级、每组独立布局或窗口 z-order 模型。

因此，测试报告里的“move / resize pass”应理解为后端 bounds API/手动命令层面的验证，不能理解为产品已经有可用的拖动和缩放手势。

### 3.4 当前任务数据模型不足以支撑 Stage Manager

当前 `StageRuntime.ManagedWindow` 主要只有：
`id / packageName / title / activity / taskId / alive / lastBounds`。

当前 `StageStateView` 只有扁平窗口列表、focusedTaskId、backend 状态；`StageLayoutEngine` 固定返回 main、previews、dock、rail。

这不能表达 iPad Stage Manager 的核心概念：

- 最近应用组（Stage Group）。
- 一个组中包含多个独立窗口。
- 当前活动组与后台最近组。
- 每个窗口独立的 x/y/width/height、z-order、最小化状态。
- 同一应用的多个 Activity / 多实例。
- 窗口在组之间移动。
- 组切换时的来源/目标布局和动画状态。

需要从“一个扁平任务数组”升级为“应用组 + 窗口”的数据模型。

### 3.5 当前模式生命周期不完整

当前 `MainActivity` 本身就是 Stage host：

- 启动后立即连接 Shizuku，并自动尝试其他后端。
- 一启动就渲染完整 StageManagerScreen。
- 始终设置 `FLAG_KEEP_SCREEN_ON`。
- 有活动任务时设置 `FLAG_NOT_FOCUSABLE`，以免 Stage host 抢到应用窗口前面。
- onPause / onResume 通过 suppressRecovery 和 ensureVisible 恢复被全屏 Activity 遮住的任务。

当前没有真正的 OFF 状态，也没有明确的“退出模式但保留应用”的流程。当前 `closeAll()` 是删除托管任务，不是关闭 Stage Manager 模式。

此外，`StageEngine.hello()` 每次握手都会执行 `enableFreeformGlobally()`，该函数写入全局设置 `enable_freeform_support=1` 和 `force_resizable_activities=1`。当前没有配套的模式退出恢复机制。正式产品必须明确管理这些全局副作用，不能让用户以为关闭模式就恢复了原系统状态。

### 3.6 当前文档和验收口径存在不一致

- `README.md` 的“move / resize”表述容易被理解成已有用户手势，但 UI 中没有相应手势。
- `TEST_REPORT.md` 对窗口 move/resize 的验证，不等于验证用户交互手势。
- `POC_PLAN.md` 顶部称 P0–P5 核心目标完成，但正文 P1 真机验证仍留有未完成项，且 P3/P4/P5 章节仍保留原计划描述。
- `DECISIONS.md` ADR-004 明确选择 FREEFORM 窗口而不是 Task leash reparent；该决策是为了保住原生输入/IME，但代价是系统窗口 bounds 一次到位、缺少 iPad 式系统级窗口动画。

后续应以代码和可复现真机证据为准，修正文档口径。

## 4. 建议的新产品架构

### 4.1 两个清晰的产品状态

**OFF：控制页**

- 整个页面只有一个主开关。
- 显示简短状态，例如“Stage Manager 已关闭 / 已开启”。
- 第一次启用时，若 Shizuku 未启动或未授权，只进入一次性授权引导。
- 不显示测试、诊断、任务列表、后端 UID、帮助面板和演示按钮。
- 再次点击开关进入工作空间。

**ON：Stage Manager 工作空间**

- 由独立的 `StageHostActivity` 承载工作空间背景和系统 UI chrome。
- 工作空间中不显示 StageLab 品牌头栏。
- 左侧为最近应用组栏；中央为自由排列、可重叠的窗口。
- 底部为真正的应用 Dock，使用应用图标，不使用 taskId 芯片。
- 窗口具有自己的拖动区域、缩放手柄和窗口控制菜单。
- 侧栏、Dock 支持自动隐藏和边缘手势。
- 通过系统级或应用级可达的退出入口关闭模式；退出模式不等于杀死所有托管应用。

控制页与工作空间应分离。不要继续让当前 MainActivity 同时承担“应用入口、权限控制台、舞台 host、诊断页面”四种职责。

### 4.2 推荐模块划分

- `ControlActivity`：唯一开关页、一次性权限引导。
- `StageHostActivity`：全屏舞台背景、左侧组栏、底部 Dock、手势入口。
- `StageModeController`：OFF/STARTING/ON/STOPPING 状态机、持久化、恢复与退出。
- `StageSessionRepository`：StageGroup / StageWindow 数据、持久化、最近组顺序。
- `StageRuntime`：保留任务轮询和状态同步能力，改为按 session 工作。
- `TaskBackend`：统一 Shizuku / Root / ADB 的实际任务操作。
- `WindowInteractionController`：拖动、缩放、窗口控制和手势仲裁。
- `StageTransitionController`：窗口和组切换动画。
- `RecentGroupsRail`：左侧最近应用组。
- `StageWorkspace`：当前组内自由窗口画布。
- `StageDock`：底部真实应用图标 Dock。
- `DebugDiagnostics`：只在 Debug / 隐藏维护入口可访问。

### 4.3 新数据模型

建议至少具备：

- `StageGroup`: groupId、窗口列表、lastActiveAt、group order、当前活动状态。
- `StageWindow`: stable windowId、taskId、package、component、label、icon key、bounds、zOrder、minimized/alive、groupId、last known windowing mode。
- `StageSession`: enabled、activeGroupId、groups、dock configuration、sidebar visibility、orientation/layout metadata。
- `WindowGeometry`: x/y/width/height，使用 dp 或归一化坐标持久化，运行时转换成物理像素。
- `TransitionState`: source/target group、source/target bounds、focus、progress。

taskId 是系统运行时身份，不应该作为用户工作区的唯一持久身份。

## 5. 最大技术风险：窗口操作和动画不能只靠美化 Compose

当前方案通过 `IActivityTaskManager.resizeTask()` 修改系统真实窗口 bounds。它可以保留 WindowManager 对触摸、IME、Back 和无障碍的原生管理，这是现有实现最重要的优势。

但当前方案也有明确限制：

- Android freeform 最小宽度约 220dp，限制侧栏实时窗口缩略尺寸。
- 当前 Task 是独立系统窗口，Stage host 在它们后面；Stage host 画出的窗口边框无法接收被真实任务窗口覆盖区域内的拖动触摸。
- 当前没有 overlay 手势层，也没有窗口拖动/缩放输入管线。
- `resizeTask` 目前是一次性改 bounds；Compose 动画只动画了 halo，不会让真实任务窗口平滑过渡。
- 反复逐帧调用 resizeTask 可能触发应用反复 relayout / configuration change，不能未经测试就当作动画方案。

不能仅仅给 `StageManagerScreen` 加几个 `animate*AsState` 就宣称实现了 iPad 动画。

### 5.1 必须进行的两条路线技术验证

**路线 A：继续使用系统 FREEFORM Task（优先保住原生输入）**

研究并实测：

- Stage host 上方的透明交互层 / 窗口装饰层。
- 是否可以用可控 touchable region 只拦截标题栏和 resize handle，其余触摸继续交给真实应用窗口。
- Overlay 的授权、层级、焦点和 IME 行为。
- 通过 WindowManager task bounds、WindowContainerTransaction 或系统 Transition API 实现窗口几何变化。
- 动画过程是否会导致应用 Activity 重建、卡顿或输入中断。

优点：当前底层能力可复用，真实应用触摸/IME/Back 更容易保留。

风险：应用层 overlay 的输入区域和系统窗口层级受 Android/ColorOS 限制；无法保证动画与 iPad 完全一致。

**路线 B：重新评估 TaskOrganizer / Shell Transition / SurfaceControl**

不能沿用“把 leash 直接 reparent 到普通 SurfaceView”这一种朴素做法。需要研究：

- Android 17 / ColorOS 对 TaskOrganizer 的任务所有权和 root task 分配。
- leash 变换与 WindowManager 输入命中区域的同步。
- InputMonitor / InputChannel / 输入转发能力及其权限。
- IME、Back、无障碍、焦点、弹窗和权限窗口的完整路由。
- WindowContainerTransaction 与 Shell transition 的兼容性。
- 是否能在不把窗口输入交给普通应用层的情况下，实现系统级窗口过渡。

优点：更接近自定义系统窗口管理器，动画和布局控制潜力更高。

风险：实现范围大，输入、IME 和无障碍容易退化；Root 并不自动解决 InputDispatcher 的命中区域问题。

**决策原则：**先做小型真机对照实验，测量动画、触摸、IME、窗口重建和性能；不要现在就删除 ADR-004，也不要把它当成永远不能改变的结论。路线选择必须以实验数据决定。

## 6. 模式开启与关闭的正确语义

### 开启

1. 检查 Shizuku 是否运行并已授权。
2. 默认优先 Shizuku Shell；只有用户明确选择或 Shizuku 不可用时，才尝试 Root。
3. 建立 session，恢复上次工作区和窗口组。
4. 启动 StageHostActivity。
5. 将当前/指定应用组放入前台 Stage。
6. 保留原有 taskId 和应用状态，不无故重启应用。

### 关闭

关闭模式不能调用当前的 `closeAll()` 作为默认实现。

建议流程：

1. 停止 StageHost UI、手势层和动画。
2. 对由 StageLab 创建或接管的 FREEFORM tasks，恢复为普通全屏任务模式，或按保存的原始 windowing mode/bounds 恢复。
3. 保留任务本身和应用进程，不主动杀应用。
4. 将当前主应用作为正常前台应用或回到 ColorOS Launcher。
5. 停止 Stage 专属轮询/后台服务。
6. 仅恢复本应用实际修改过的系统全局设置，并且只恢复自己记录的原值；不得覆盖用户后来手动修改的设置。
7. 将 session 状态标记为 OFF。

退出入口可由控制页开关、通知动作或 Quick Settings tile 提供。工作空间本身不应出现一个开发者风格的“全部关闭”大按钮。

## 7. 实施顺序

### M0：冻结产品定义与基线

- 保留当前可运行版本和测试脚本。
- 增加本文件作为产品重构基线。
- 明确 Stage Manager 的左侧最近组、中央工作区、底部 Dock 和 ON/OFF 生命周期。

### M1：重构应用入口与模式生命周期

- MainActivity 不再启动即显示 Stage UI。
- 新增唯一开关页。
- 分离 StageHostActivity。
- 实现 OFF/STARTING/ON/STOPPING 状态机。
- 实现不杀任务的退出模式路径。
- 处理首次 Shizuku 授权。

### M2：重建 Stage Manager 视觉结构

- 移除开发者头栏、诊断按钮、帮助按钮、演示/重整/全部关闭按钮和测试空状态。
- 实现左侧最近应用组栏。
- 实现中央 Stage 工作区。
- 实现底部应用 Dock（真实应用图标）。
- 先做静态布局对照验收，再连接真实任务。

### M3：组与窗口数据模型

- StageGroup / StageWindow / StageSession。
- 同组多个窗口、窗口 z-order、重叠布局。
- 组切换和持久化。
- 支持同一应用多 Activity / 多实例（应用自身允许时）。

### M4：窗口交互

- 拖动窗口。
- 缩放窗口。
- 窗口控制菜单。
- 最小化、关闭、恢复。
- 将窗口拖入/拖出应用组。
- 侧栏和 Dock 自动隐藏/边缘唤出。

### M5：系统级动画路线验证与实现

- 对照路线 A / B。
- 真实 Task 窗口移动、缩放、切换动画。
- 动画过程中持续验证触摸、IME、Back 和无障碍。
- 不能以只有 Compose 装饰层动了作为验收。

### M6：真机兼容和性能验收

- 至少三个不同第三方应用同时运行。
- 浏览器、视频、聊天/笔记、WebView、DRM/FLAG_SECURE 等场景。
- 应用组创建/切换/恢复。
- 横竖屏、120/144Hz、输入法、系统手势。
- 连续操作和至少一小时长稳测试。
- 检查 Task ID、进程、Activity 重建、帧时间、输入延迟和资源泄漏。

## 8. 新验收标准

正式产品验收至少应包含：

- 打开应用时只有一个开启/关闭控制。
- 点击开启后直接进入 Stage Manager 工作空间。
- 工作空间没有 StageLab 品牌头栏、诊断面板或测试按钮。
- 最近应用组在左侧，而不是右侧独立任务窄栏。
- 当前组可有多个真实窗口，支持重叠。
- 底部 Dock 使用真实应用图标。
- 点击左侧组可切换，当前组进入最近组列表。
- 可以把应用从 Dock/最近组拖入当前组。
- 可以把窗口移回最近组。
- 窗口可拖动、缩放、最小化、关闭和恢复。
- 实时预览持续变化，不以截图刷新伪装。
- 窗口切换/缩放动画作用于真实任务窗口，不只是外框。
- 原生触摸、IME、Back 和无障碍不被动画方案破坏。
- 关闭模式后应用任务不被无故杀死，设备回到正常桌面/应用状态。
- Shizuku 为默认最低权限路径；Root 是可选增强，不是默认要求。

测试证据必须区分：
- 后端 API 可调用；
- ADB 脚本可以改变 bounds；
- 用户手势真的能移动/缩放窗口；
- 真实第三方应用能够正常交互；
- 动画真实作用于系统窗口；
- 长稳测试完成。

这些不能混为同一个“通过”。

## 9. 本轮审查的最终判断

当前工程的底层突破是有价值的：真实 FREEFORM tasks、实时系统合成、Shizuku Shell 权限和任务焦点切换已经在目标设备上验证。

但当前产品还不是 iPad Stage Manager 的复刻，主要缺少：

1. 真正的 ON/OFF 产品生命周期；
2. 左侧最近应用组与应用组管理；
3. 中央多窗口自由布局和重叠；
4. 底部应用图标 Dock；
5. 用户可操作的拖动和缩放；
6. 作用于真实任务窗口的过渡动画；
7. 正确的关闭模式与系统状态恢复。

因此下一轮不是简单“换皮”，而是**保留任务后端，重做产品状态机、窗口模型、工作空间布局和输入/动画层**。这才符合用户要求的产品方向。
