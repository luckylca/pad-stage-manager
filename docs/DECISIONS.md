# 技术决策记录

## ADR-001：原生 Android 工程

- 状态：已采纳
- 选择：Kotlin + Jetpack Compose + Gradle Kotlin DSL。
- 原因：项目依赖 Android 窗口、任务、Surface、Binder 和系统权限接口；原生工程比跨平台 UI 框架更直接。

## ADR-002：先 PoC，再完整 UI

- 状态：已采纳
- 选择：先验证 TaskOrganizer / Task Surface 是否能在目标 ColorOS 构建上工作。
- 原因：这是整个产品最核心、也最不确定的技术门槛。先投入完整 UI 会掩盖系统权限限制。

## ADR-003：最低权限优先

- 状态：已采纳
- 顺序：Public API → Shizuku / ADB Shell → KernelSU Root。
- 原因：减少系统侵入、维护成本和 OTA 风险。
- 限制：Root 不等于 signature 权限；必须验证每个接口的实际授权结果。

## ADR-004：真实 Surface 才算实时预览

- 状态：已采纳
- 选择：以系统 Task Surface 托管为目标；MediaProjection 不作为主实现。
- 原因：截图或屏幕捕获不能完整替代多个独立 Task 的持续渲染、焦点和输入控制。

## ADR-005：PoC 阶段保持单模块

- 状态：已采纳
- 选择：先使用单 app module + 按职责分包。
- 原因：避免技术路线未验证前出现大量空模块和过早抽象。
