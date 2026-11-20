# iPad Stage Manager 视觉差距报告（2026-11-21）

> 对照 `docs/IPAD_VISUAL_FIDELITY_SPEC.md`；基线 commit `9f746a9`。
> 每项标注：**根因** → **修复方案** → **涉及文件**。

## 差距清单

| # | 差距 | iPad 目标 | 当前实现 | 根因 | 修复 | 文件 | 优先级 |
|---|---|---|---|---|---|---|---|
| G1 | rail 宽度 625px，占屏 25%（iPad 约 15%） | 368px 无容器 | 深色不透明大容器 + 卡片 | `RecentGroupsRail` 绘制了容器背景与磁贴卡片 | 重写为无背景缩略图列 | `StageWorkspaceScreen.kt` | P0 |
| G2 | rail 用真实 Task 当预览，被迫 256dp 高 | TaskSnapshot 缩略图 | `syncGeometry` 把非活动组 bounds 改到 rail | ADR-012 路线错误 | 非活动组 `toBack` 停放原几何；rail 画快照 | `StageWorkspace.kt` + 新 `windows/TaskSnapshots.kt` | P0 |
| G3 | 文字标题/窗口数/提示语 | 无 | `工作区 1`、`3 个窗口 · 轻点切换`、数字方块 | 磁贴用了文字卡片 | 全部删除，改图标叠加 | `StageWorkspaceScreen.kt` | P0 |
| G4 | ▲▼ 箭头按钮 | 无（边缘拖拽） | 独立箭头按钮 | 滚动实现方式 | 删除，改 rail 边缘纵向拖拽 | 同上 + `WindowChromeController` | P0 |
| G5 | 左下 "+" 新建组 | 无 | 圆形加号 | 组创建入口设计 | 删除（拖出窗口即建组） | 同上 | P0 |
| G6 | Dock 含开发者按钮 | 纯应用 Dock | `◧ 应用组`、`⏻ 关闭`、`＋` | Dock 混入了模式控制 | 全删；应用库改网格图标；退出移至控制页 | `StageWorkspaceScreen.kt`、`StageDock` | P0 |
| G7 | Dock 无磨砂/图标 48dp/间距小 | 60pt 图标、blur、22pt 圆角 | 硬编码 48dp、纯色 0xE6 | 尺寸未按 spec | 按 §1.3 常量重写 | 同上 | P1 |
| G8 | 无固定/最近分隔 | 有分隔线 | 无 | Dock 未分组 | 加分隔线 + 最近应用区 | `ui/DockApps.kt`、Screen | P1 |
| G9 | macOS 式常驻标题栏（红黄绿圆点） | iPad 无标题栏 | 深色 89px 标题栏常驻 | 错误参考 macOS | 删标题栏；改顶部不可见拖动条 + 焦点左上角控制按钮 | `ChromeOverlayView`、`WindowChromeController` | P1 |
| G10 | 蓝色焦点描边 | 无描边，仅阴影 | 2px `0xFF8AA0FF` | 同上 | 删除描边，改阴影强度区分 | `ChromeOverlayView.drawRing` | P1 |
| G11 | 窗口阴影过弱/无差异 | 焦点 30pt α0.28 | 多层矩形 α≤0.18 | 绘制方式 | 按常量重画（可加 `setShadowRadius` 若实验 A 成功） | `StageWorkspaceScreen.WindowShadows` | P1 |
| G12 | 圆角是遮罩非真裁剪 | 真实裁剪 | 角落遮罩 ADR-013 | 未做实验 A | 执行 §6 实验 A，按结果落地 | `core/privileged`、`windows` | P2 |
| G13 | 新窗口近正方形、居中堆叠 | 按应用类型 0.42–0.68 | 全局 0.52×0.72 | `defaultGeometryFor` 固定比例 | 新 `stage/WindowSizing.kt`（orientation/PiP 判定 + per-package 偏好持久化） | `StageWorkspace.kt` + 新文件 | P1 |
| G14 | 拖动/缩放残留 | 无 | 用户报告 | ACTION_CANCEL 与 UP 合并；collectLatest 取消并发提交 | §7 手势生命周期重写 | `ChromeOverlayView`、`StageHostActivity` | P0 |
| G15 | 非焦点窗口无视觉区分 | 亮度略暗+小阴影 | 相同 | Surface 层不可调 | 仅阴影差异（G11 落地） | Screen | P2 |
| G16 | 桌面是自绘渐变 | 系统壁纸 | `Brush.verticalGradient` | 宿主填满全屏 | 宿主背景改透明/壁纸透出 | `StageWorkspaceScreen`、`StageHostActivity` | P1 |

## 修复顺序（按用户指定）

1. **G1–G5**：重做左栏（快照缩略图）— P0
2. **G6–G8**：重做 Dock — P0
3. **G13**：中央窗口布局与默认尺寸 — P1
4. **G9–G11, G16**：窗口控制与颜色 — P1
5. **G12**：真实圆角实验 A/B — P2
6. **G14**：拖动缩放残留修复 — P0（与 1 并行，因为同一批文件）
7. 动画与细节统一 — 最后

## 像素对比验收的约束

Apple 官方截图 CDN（support.apple.com/library）与 Cult of Mac 图床在当前网络下均返回 HTML 挑战页（文件头 `3C 21 44 4F`，已验证 9 个 URL）。因此：

- 尺寸规范以 Apple 官方**文字描述**（相对关系）+ iPadOS HIG 公开常量 + 多来源一致观察推导，来源等级标注在 spec 各表；
- `docs/VISUAL_ACCEPTANCE.md` 的 overlay 对比部分**待真实截图可用后补做**，当前以"常量符合 spec + 用户目视验收"为替代标准；
- 每次真机截图记录 device build、density、insets、Git commit（见 VISUAL_ACCEPTANCE 模板）。
