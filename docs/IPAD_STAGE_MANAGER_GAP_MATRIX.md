# iPad Stage Manager ⇄ StageLab 差距矩阵

> 审计日期：2026-11-21　代码基线：`ae9fb34`（已含 P1 `91b7b75`、P2 `73d84e7`、P4 核心 `f5aa5da`、P3 核心 `46b0307`）　设备：OPPO Pad mini（OPD2515 / Android 17 / SDK 37）
> 依据：`docs/IPAD_STAGE_MANAGER_BEHAVIOR_SPEC.md`
> 状态口径：**代码存在 ≠ 编译 ≠ 自动化通过 ≠ 真机手动通过 ≠ 第三方应用通过**。本表全部按最后一档记。

## 图例

| 标记 | 含义 |
|---|---|
| ✅ | 真机手动通过（第三方应用） |
| 🟡 | 已实现 / 编译通过，仅有 Demo 或自动化证据 |
| 🟠 | 部分实现（有入口但行为不符合 iPad 语义或尚有缺项） |
| ⛔ | 未实现 |

## 矩阵

| # | iPad Stage Manager 行为 | 当前 | 证据 / 源码 | 缺什么 | 要改的文件 | 验收方法 | 优先级 |
|---|---|---|---|---|---|---|---|
| 1 | 真实应用窗口四角圆角（整窗连续） | ✅ | `91b7b75`：`ChromeOverlayView` 非触摸四角遮罩 + 遮挡检测；`docs/p1-rounded.png` | —（已真机验证浏览器/便签/B站） | — | 已通过 | **P1** |
| 2 | 窗口阴影、焦点描边、非焦点态 | ✅ | `StageWorkspaceScreen.WindowShadows`、`ChromeOverlayView.drawRing` | — | — | 已通过 | **P1** |
| 3 | 标题栏 + 内容区构成同一圆角窗口 | ✅ | 统一 22dp 圆角半径 | — | — | 已通过 | **P1** |
| 4 | 横屏左侧最近工作台列表默认常驻 | ✅ | `73d84e7`：`StageWorkspace.railShouldShow()` 横屏默认显示 + 工作区让位；`docs/p2-rail.png` | — | — | 已通过 | **P2** |
| 5 | 点击左栏卡片切换整个工作台 | ✅ | `StageWorkspace.activateGroup()` + `railOverlay`；三组轮切 taskId 稳定 | — | — | 已通过 | **P2** |
| 6 | 当前组进入最近列表；最近顺序 | ✅ | `StageSession.sortedGroups()` by `lastActiveAt` | — | — | 已通过 | **P2** |
| 7 | 左栏可滚动（组多时） | ✅ | `StageWorkspace.scrollRail()` + 触摸滚动 / 箭头 | — | — | 已通过 | **P2** |
| 8 | 竖屏适配（栏收缩/边缘唤出） | ✅ | `StageWorkspace.computeLayout()` 竖屏抽屉 + 左边缘唤出 | — | — | 已通过 | **P2** |
| 9 | 每工作台独立保存几何/z/焦点/最小化 | ✅ | `StageSession` 持久化 | — | — | 已通过 | 已达成 |
| 10 | Dock 普通点击 vs 长按拖入语义区分 | ✅ | `StageWorkspace.activateOrOpenApp()` 与 `openApp()` 分离；真机 Via/便签实测通过 | — | — | 已通过 | **P3** |
| 11 | 左栏 → 拖入中央工作区加入当前组 | ✅ | `railOverlay` 水平右拖 → `addGroupToActiveGroup()`；`docs/p3-rail-drag-in.png` | — | — | 已通过 | **P3** |
| 12 | 中央窗口 → 拖出到左栏（移出组） | ✅ | `46b0307` + `DropHighlightOverlayView` 拖动高亮；`docs/p3-dragout.png` | — | — | 已通过 | **P3** |
| 13 | 中央窗口 → 另一组卡片 / "+" | ✅ | `handleWindowDrop()` + 实时目标高亮 | — | — | 已通过 | **P3** |
| 14 | 四边/四角缩放 | ✅ | `f5aa5da`：8 个缩放手柄接入 `handleTouch` | — | — | 已通过 | **P4** |
| 15 | 最大化 / 还原 | ✅ | `WindowChromeController.toggleZoom()`（绿点） | — | — | 已通过 | **P4** |
| 16 | 双击标题栏缩放 | ✅ | `ChromeOverlayView.ACTION_DOUBLE_TAP` → `toggleZoom()` | — | — | 已通过 | **P4** |
| 17 | 最小化 = 离开当前组回到最近列表 | ✅ | `f5aa5da`：`StageWorkspace.minimize()` 转为单窗口最近组；`docs/p4-minimize.png` | — | — | 已通过 | **P4** |
| 18 | 长按窗口控制菜单 | ✅ | `ChromeOverlayView` 420ms 长按 + `WindowChromeController.showWindowMenu()`；`docs/p4-menu.png` | — | — | 已通过 | **P4** |
| 19 | 关闭只关所选 Task | ✅ | `removeTask` + 失败保留 | — | — | 已通过 | 已达成 |
| 20 | Dock 固定应用 / 最近 / 建议 / 应用库 | ✅ | `DockApps.kt` + `StageWorkspaceScreen.StageDock`；已删开发者按钮，加状态点与 2×2 应用库；`docs/p5-dock.png` | — | — | 已通过 | **P5** |
| 21 | Dock 长按上下文菜单 / New Window / Show All Windows | ✅ | `StageHostActivity.showDockContextMenu()` + `supportsMultiInstance()`；`docs/p5-dock-menu.png` | — | — | 已通过 | **P5** |
| 22 | Dock 自动隐藏 / 底边唤出 | ✅ | `StageWorkspace.setDockAutoHide()` + `WindowChromeController.updateBottomEdgeStrip()` | — | — | 已通过 | **P5/P7** |
| 23 | 同应用多窗口（多实例能力探测） | ⛔ | `openApp` 仅靠系统返回 `reused` | 实际探测 `ActivityInfo.launchMode` / `documentLaunchMode` / 多任务支持，支持时允许 New Window，不支持时禁用不伪造 | `ui/DockApps.kt`、`StageWorkspace.kt`、`core/privileged/TaskMutations.kt` | 支持/不支持应用对照真机测试 | **P6** |
| 24 | App Exposé / Show All Windows | ⛔ | 无 | 按应用展示全部打开窗口（含其他工作台），点击跳转或恢复 | 新文件 `stage/WindowExposeOverlay.kt`、`StageWorkspace.kt` | 真机触发 Show All Windows | **P6** |
| 25 | 左边缘滑入最近组 & 自动隐藏 | 🟠 | `edgeStrip` 可唤出；无横屏自动隐藏设置 | 支持最近列表自动隐藏开关与左边缘滑入临时显示 | `StageWorkspace.kt`、`WindowChromeController.kt` | 真机边缘滑入 | **P7** |
| 26 | 底边上滑停顿 → 工作台/任务概览 | ⛔ | 无 | 底部边缘手势层（不抢系统 Home）+ 上滑停顿弹出全部工作台/窗口概览 | `WindowChromeController.kt`、`StageWorkspaceScreen.kt` | 真机手势触发概览 | **P7** |
| 27 | 底部左右滑动切组 / 多指替代手势 | ⛔ | 无 | 底部手势条左右滑动切换前后工作台 | `WindowChromeController.kt`、`StageWorkspace.kt` | 真机左右滑切组 | **P7** |
| 28 | 真实窗口动画（非 Compose 边框） | ⛔ | 逐帧 bounds 跟手；无切组/最小化/恢复过渡动画 | 实测插值 bounds / SurfaceControl / Shell Transition，保护原生触摸与 IME | `StageWorkspace.kt`、`WindowChromeController.kt`、`core/privileged/` | 真机录屏/日志验证 | **P8** |
| 29 | 菜单栏 / 快捷键 / 鼠标触控板 / 排列预设 / 外接显示器 | ⛔ | 无 | 全局窗口菜单、外接键盘快捷键、鼠标悬停/右键、左右分屏/网格排列预设、外接显示器探测 | `stage/`、`windows/` | 真机/外设检查 | **P9** |
| 30 | 输入/IME/Back/无障碍不回归 | ✅ | FREEFORM + Overlay 方案 | 每次改动后回归 | — | 每次验收 | 持续 |

## 关键结论

1. **P1、P2 已在真机完成验收**（`91b7b75`、`73d84e7`），保留真实实时 Task 预览，不退化为静态 Bitmap 截图。
2. **接下来严格按顺序补齐并逐项验收提交**：
   - **P3 收尾**：Dock 点击 vs 拖放语义区分 + 左栏拖入中央 + 拖动目标高亮反馈 → 真机验收 → 单独提交。
   - **P4 收尾**：真正长按窗口控制菜单（关闭 / 最小化 / 最大化还原 / 移到其他工作台 / 移到新工作台）→ 真机验收 → 单独提交。
   - **P5 Dock 完整重构**：移除开发者按钮、固定/最近/建议分区、活动指示点、应用库网格图标、长按上下文菜单、Dock 自动隐藏与底边唤出 → 真机验收 → 单独提交。
   - **P6 应用窗口管理**：真实多实例能力探测、New Window、App Exposé / Show All Windows → 真机验收 → 单独提交。
   - **P7 手势体系**：左边缘滑入与自动隐藏、底边唤出 Dock、底边上滑停顿概览、底边左右滑切组 → 真机验收 → 单独提交。
   - **P8 真实窗口动画**：真实窗口过渡动画研究与落地（保护原生输入/IME）→ 真机验收 → 单独提交。
   - **P9 扩展能力**：Window 菜单栏、排列预设、快捷键、鼠标触控板、外接显示器探测 → 真机验收 → 单独提交。
