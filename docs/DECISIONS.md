# 技术决策记录

## ADR-001：原生 Android 工程

- 状态：已采纳
- 选择：Kotlin + Jetpack Compose + Gradle Kotlin DSL。
- 原因：项目依赖 Android 窗口、任务、Surface、Binder 和系统权限接口；原生工程比跨平台 UI 框架更直接。

## ADR-002：先底层验证，再完整 UI

- 状态：已完成
- 选择：先用 `app_process` 直接以 Shell / Root 身份跑通任务列举、启动、resize、focus、close，
  再写 Stage UI。
- 结果：`ShellProbeMain` 成为可复用的真机探针，也是 KernelSU / ADB 服务的入口。

## ADR-003：最低权限优先

- 状态：已采纳
- 顺序：Public API → Shizuku（Shell 2000）→ KernelSU Root。
- 实测：Shell 已持有 `MANAGE_ACTIVITY_TASKS`，因此 Shizuku 即可完成全部 Stage 功能，Root 不是必需。

## ADR-004：用系统 FREEFORM 任务承载窗口，而不是把 leash 重挂到应用 SurfaceView

- 状态：已采纳（替代早期 TaskOrganizer 嵌入方案）
- 方案：通过 `IActivityTaskManager.startActivityAsUser` + `ActivityOptions.setLaunchWindowingMode(FREEFORM)`
  创建真实任务窗口，用 `resizeTask` / `setFocusedTask` / `removeTask` 控制。
- 原因：
  1. `TaskOrganizer` 虽然能拿到每个任务的 leash，但把 leash `reparent` 到应用 SurfaceView 后，
     输入命中的仍是 WindowManager 里的任务矩形，触摸/IME/Back 都要自己注入和转发；
  2. 注册组织者会让本进程接管被交给它的任务，容易把系统 shell 的窗口管理“抢”过来；
  3. FREEFORM 任务由系统维护窗口、输入、IME、无障碍和生命周期，**实时画面 + 原生交互**天然成立。
- 代价：窗口位移/缩放没有系统级过渡动画（矩形一次到位），舞台自身的光晕/卡片用 Compose 动画补足。
- 备注：`TaskOrganizer` 代码仍保留在诊断页（注册/回调探针）与后续“嵌入式画布”实验入口，
  但**不作为默认路径**，避免与自由窗口管理混用。

## ADR-005：应用侧是窗口列表的唯一真相来源

- 状态：已采纳
- 选择：`StageRuntime` 保存窗口列表（package/activity/taskId/顺序），特权进程只做操作与状态上报。
- 原因：Shizuku UserService / root 服务随时可能被系统杀死；把列表放在特权进程会让用户丢失工作区。
- 兜底：列表持久化到 `SharedPreferences`；恢复时先按 taskId 匹配，再按“freeform + 顶层 Activity
  一致”接管，绝不按包名盲目匹配（否则会把 StageLab 自己的主任务当成被托管窗口）。

## ADR-006：隐藏 API 全部运行时反射解析

- 状态：已采纳
- 选择：仅对 `TaskOrganizer` 保留编译桩（需要继承），其余（AMS、WCT、token、TaskInfo 字段）
  一律运行时按名字解析并缓存。
- 原因：Android 17 已经出现签名变化（`createRootTask` 去掉 boolean 重载、`onBackOnTaskRoot` 改名），
  硬链接会在 OEM 更新后直接 `NoSuchMethodError`。运行时解析把问题降级成可诊断的错误信息。

## ADR-007：窗口容量与“停放”

- 状态：已采纳
- 背景：freeform 任务有约 220 dp 的系统最小尺寸，竖屏右侧只能稳定放 2 个预览，横屏 1 个。
- 选择：按可用高度计算 rail 容量，超出容量的窗口把矩形设为主窗口矩形（被主窗口完全覆盖），
  任务保持存活；点击 Dock 芯片即可提升。
- 原因：不隐藏、不销毁、不做缩略图，仍可一键恢复，且不会把窗口挤出屏幕。

## ADR-008：舞台窗口在非空时不可获焦

- 状态：已采纳
- 选择：存在被托管窗口时给 `MainActivity` 加 `FLAG_NOT_FOCUSABLE`；舞台为空时移除该 flag。
- 原因：可获焦窗口被点击时会把 StageLab 任务提到最前，全屏遮住所有 freeform 任务（实测导致窗口被隐藏）。
- 代价：舞台空置时仍可获焦，因此无障碍/返回/输入法在空舞台下保持正常。
