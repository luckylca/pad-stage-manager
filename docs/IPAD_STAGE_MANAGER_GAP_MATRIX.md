# iPad Stage Manager ⇄ StageLab 差距矩阵

> 审计日期：2026-11-20　代码基线：`1654515`　设备：OPPO Pad mini（OPD2515 / Android 17 / SDK 37）
> 依据：`docs/IPAD_STAGE_MANAGER_BEHAVIOR_SPEC.md`
> 状态口径：**代码存在 ≠ 编译 ≠ 自动化通过 ≠ 真机手动通过 ≠ 第三方应用通过**。本表全部按最后一档记。

## 图例

| 标记 | 含义 |
|---|---|
| ✅ | 真机手动通过（第三方应用） |
| 🟡 | 已实现 / 编译通过，仅有 Demo 或自动化证据 |
| 🟠 | 部分实现（有入口但行为不符合 iPad 语义） |
| ⛔ | 未实现 |

## 矩阵

| # | iPad Stage Manager 行为 | 当前 | 证据 / 源码 | 缺什么 | 要改的文件 | 验收方法 | 优先级 |
|---|---|---|---|---|---|---|---|
| 1 | 真实应用窗口四角圆角（整窗连续） | ⛔ | `ChromeOverlayView` 只画圆角标题条；Task 是独立系统窗口 | 没有作用于 Task Surface 的圆角或角落遮罩 | `windows/ChromeOverlayView.kt`、`windows/WindowChromeController.kt` | 浏览器/便签/B站 三应用四角逐一截图 | **P1** |
| 2 | 窗口阴影、焦点描边、非焦点态 | 🟠 | 标题栏有焦点蓝边；无窗口阴影 | 阴影层、非焦点描边统一 | `StageWorkspaceScreen.kt`、`WindowChromeController.kt` | 双窗口重叠截图 | **P1** |
| 3 | 标题栏 + 内容区构成同一圆角窗口 | 🟠 | 标题条圆角 26px，内容方角 | 半径统一 | 同上 | 同上 | **P1** |
| 4 | 横屏左侧最近工作台列表默认常驻 | ⛔ | `railVisible=false` 默认；`AnimatedVisibility` 隐藏 | 横屏常驻 + 工作区让位 | `StageWorkspace.kt`（布局）、`StageWorkspaceScreen.kt` | 横屏进入即见左栏 | **P2** |
| 5 | 点击左栏卡片切换整个工作台 | 🟠 | `activateGroup()` 存在；入口默认隐藏 | 常驻入口、卡片点击层 | 同上 | 三工作台来回点击 | **P2** |
| 6 | 当前组进入最近列表；最近顺序 | 🟡 | `sortedGroups()` by lastActiveAt | 视觉与顺序验证 | `StageSession.kt` | 切换 10 次观察顺序 | **P2** |
| 7 | 左栏可滚动（组多时） | ⛔ | `tileRect()` 压缩高度 | 滚动偏移 + 可见窗口窗格 | `StageWorkspace.kt` | 5 个组 | **P2** |
| 8 | 竖屏适配（栏收缩/边缘唤出） | 🟠 | 竖屏默认隐藏，可边缘唤出 | 与横屏统一的 LayoutSpec | `StageWorkspace.kt` | 旋转后检查 | **P2** |
| 9 | 每工作台独立保存几何/z/焦点/最小化 | ✅ | `StageSession` 持久化 | —（已真机验证切组恢复） | — | 切组后 bounds 对比 | 已达成 |
| 10 | Dock → 拖入当前组 | 🟠 | 长按拖动已实现，落点决定位置 | 拖放高亮与语义区分 | `StageWorkspaceScreen.kt` | 拖入后窗口出现在组内 | **P3** |
| 11 | 左栏 → 拖入当前组 | ⛔ | 无（卡片只能点击） | 卡片拖拽 + 落点判定 | 同上 + `StageWorkspace.kt` | 从左栏把窗口拖进中央 | **P3** |
| 12 | 中央窗口 → 拖出到左栏（移出组） | ⛔ | `moveWindowToGroup` 仅模型层 | 拖拽命中左栏区域 | 同上 | 拖出后该窗口回到最近列表 | **P3** |
| 13 | 中央窗口 → 另一组卡片 | ⛔ | 同上 | 同上 | 同上 | 跨组移动 | **P3** |
| 14 | 四边/四角缩放 | 🟠 | 右/下边 + 右下角 | 上/左边、四个角 | `WindowChromeController.kt` | 八向缩放 | **P4** |
| 15 | 最大化 / 还原 | ⛔ | 无 | 几何保存 + 双击 | 同上 + `ChromeOverlayView` | 双击标题栏 | **P4** |
| 16 | 双击标题栏缩放 | ⛔ | 无 | 双击识别 | `ChromeOverlayView` | 同上 | **P4** |
| 17 | 最小化 = 离开当前组回到最近列表 | ⛔ | 当前是“移到宿主后面 + 底部托盘” | 改组语义 + 卡片回栏 | `StageWorkspace.kt`、`StageWorkspaceScreen.kt` | 最小化后出现在左栏 | **P4** |
| 18 | 长按窗口控制菜单 | ⛔ | 只有红/黄两个点 | 菜单（Overlay 弹窗） | `ChromeOverlayView`/controller | 长按标题栏 | **P4** |
| 19 | 关闭只关所选 Task | ✅ | `removeTask` + 失败保留 | — | — | 关闭后其他窗口在 | 已达成 |
| 20 | Dock 固定应用 / 最近 / 建议 / 应用库 | 🟠 | 硬编码优先列表 + 应用库 | 最近使用、活动指示、可配置 | `ui/DockApps.kt`、`StageWorkspaceScreen.kt` | Dock 截图 | **P5** |
| 21 | Dock 长按上下文菜单 / New Window / Show All Windows | ⛔ | 长按只用于拖动 | 菜单 + 能力探测 | 同上 | 长按图标 | **P5** |
| 22 | Dock 自动隐藏 / 底边唤出 | ⛔ | 常显 | 隐藏状态 + 边缘条 | `WindowChromeController` | 底边轻扫 | **P5/P7** |
| 23 | 同应用多窗口 | ⛔ | 无 | 多实例探测 | `StageWorkspace`、`DockApps` | 两个便签窗口 | **P6** |
| 24 | App Exposé / Show All Windows | ⛔ | 无 | 概览视图 | 新文件 `stage/WindowExpose.kt` | 触发概览 | **P6** |
| 25 | 左边缘滑入最近组 | 🟡 | `edgeStrip` 有，但横屏常驻后语义变化 | 常驻/隐藏两种模式 | controller | 边缘滑动 | **P7** |
| 26 | 底边上滑停顿 → 概览 | ⛔ | 无 | 手势 + 概览 | 同上 | 手势 | **P7** |
| 27 | 底部左右滑动切组 | ⛔ | 无 | 手势 | 同上 | 手势 | **P7** |
| 28 | 真实窗口动画（非 Compose 边框） | ⛔ | 逐帧 bounds 跟手；无过渡动画 | 过渡/吸附/组切换动画 | `StageWorkspace`、`WindowChromeController` | 录屏逐帧 | **P8** |
| 29 | 菜单栏 / 快捷键 / 外接显示器 | ⛔ | 无 | 全部 | — | — | **P9** |
| 30 | 输入/IME/Back/无障碍不回归 | ✅ | FREEFORM + Overlay 方案 | 每次改动后回归 | — | 每次验收 | 持续 |

## 关键结论

1. **P1 是硬阻塞**：没有真实圆角，产品观感无法达标。方案：非触摸角落遮罩 Overlay（不碰 Task Surface、不碰输入）+ 宿主阴影 + 焦点描边；SurfaceControl 圆角需要 TaskOrganizer 的 leash，会牺牲原生输入，留待 P8 实验。
2. **P2 是用户最高优先级**：横屏左栏必须常驻且可点击。当前布局里 `railVisible` 默认 false，且左栏是浮层，需改为横屏固定栏 + 工作区让位。
3. **P4 的最小化语义要改**：iPad 是“移出当前组、回到最近列表”，不是“塞到宿主后面 + 底部托盘”。
4. 两套布局计算（`StageModels.StageLayoutEngine` 与 `StageWorkspace` 内部）必须收敛为一套 `LayoutSpec`，否则横竖屏与验收标准无法一致。

## 逐阶段验收顺序

P1 圆角/外观 → P2 横屏工作台 → P4 窗口控制（含最小化语义）→ P3 拖放闭环 → P5 Dock → P6 多窗口/Exposé → P7 手势 → P8 动画 → P9 扩展。
（P3/P4 互换的理由：拖放的落点与最小化目标都依赖“左栏是常驻可见区域”，先完成 P4 的组语义再实现拖放，返工更少。）
