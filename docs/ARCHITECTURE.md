# StageLab 架构说明

## 1. 目标边界

StageLab 的核心对象是 Android 的真实 Task，而不是应用图标、UsageStats 记录或截图。

理想状态下，多个 Task 保持运行，并将各自的实时 Surface 显示在 StageLab 的工作区布局中。切换主窗口时，只改变任务容器、边界、层级和焦点，不销毁或重新启动 Task。

## 2. 分层

    StageLab UI (Jetpack Compose)
      ├─ Workspace / Sidebar / Task cards
      ├─ Layout and transition state
      └─ Diagnostics
              |
              v
    Task Host
      ├─ Live Surface host
      ├─ Input / focus / IME coordination
      └─ Transition coordinator
              |
              v
    Task Backend contract
      ├─ Public API backend
      ├─ Shizuku / Shell backend
      └─ KernelSU / Root backend (optional)
              |
              v
    Android Window Manager / SurfaceFlinger

UI 不直接调用隐藏系统接口。所有后端必须报告自己真实具备的能力，不能用“Root 已授权”推断“任务窗口管理已可用”。

## 3. 权限层级

### Public API

用于应用目录、启动 Intent、常规窗口和工作区 UI。UsageStats、悬浮窗、无障碍等特殊访问需要用户明确授权。该层不承诺能获取或托管其他应用的真实 Task Surface。

### ADB / Shizuku

作为首个系统级 PoC 路径。先验证 Shell UID 是否拥有目标权限，并通过最小 Binder 探针确认系统服务实际接受请求。

Shizuku 是身份/IPC 桥接，不是权限提升到任意 signature 权限的通行证。不同 Android/OEM 构建的权限分配可能不同。

### KernelSU Root

仅在 Shell 路径被系统权限明确阻断后研究。Root 可用于受控的特权桥接或进一步系统集成研究，但 uid=0 不自动满足 signature|recents 权限检查。任何系统分区修改、系统签名或框架 Hook 都必须作为单独的高风险方案评估，不能混入基础应用。

## 4. 实时画面与动画

静态截图和 MediaProjection 仅可用于诊断/兼容性回退，不满足“多个后台应用画面持续更新”的核心要求。

目标方向是由系统任务组织接口提供 Task leash / SurfaceControl，再将真实任务 Surface 放入可控的宿主容器。动画应尽量在 Surface 层与 UI 布局层同步，避免先动画截图、再替换真实窗口造成闪烁。

待验证事项：

- 任务组织接口是否可由 Shell 调用。
- Task leash 的生命周期与重挂载规则。
- SurfaceControl transaction 的权限与同步时序。
- 宿主 Surface 销毁/重建时的恢复。
- 多任务同时可见是否真的保持持续绘制。
- 触摸命中、输入焦点、IME、无障碍和窗口 Insets。
- FLAG_SECURE、DRM、PiP、旋转及不支持 resize 的应用行为。

## 5. 任务状态模型

建议后续使用显式状态机：

- Discovered：发现任务。
- Organizing：正在请求系统组织。
- Hosted：真实 Surface 已挂入宿主。
- Focused：当前输入焦点任务。
- Backgrounded：不在主焦点，但仍在工作区。
- Detaching：从宿主移除。
- Unavailable：系统拒绝或任务不兼容。
- Destroyed：任务结束。

状态必须由系统回调和实际 Surface 生命周期驱动，不能仅凭 UI 点击推断成功。

## 6. 模块化策略

PoC 阶段保持单 Android app module，包结构按职责拆分。只有在 TaskOrganizer/Surface 路径验证后，再拆成独立 Gradle modules，避免过早建立大量空模块。
