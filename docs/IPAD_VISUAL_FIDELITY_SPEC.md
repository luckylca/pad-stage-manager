# iPad Stage Manager 视觉保真规范（StageLab 对照实现）

> 版本 1.0　2026-11-21
> 设备基线：OPPO Pad mini OPD2515，1680×2520 @ 420dpi（density 2.625），ColorOS 17 / Android 17
> 本文档是视觉实现的**唯一权威规范**。所有 UI 改动必须对照本文档，验收见 `docs/VISUAL_ACCEPTANCE.md`。

## 0. 参考来源与限制说明

| 来源 | 用途 | 状态 |
|---|---|---|
| [Apple 官方：在 iPad 上通过台前调度整理窗口](https://support.apple.com/zh-cn/guide/ipad/ipad1240f36f/ipados) | 行为与布局文字描述 | ✅ 已抓取全文 |
| [Apple 官方：同时使用多个窗口](https://support.apple.com/zh-cn/guide/ipad/ipad08c9970c/ipados) | 窗口控制行为 | ✅ 已抓取 |
| [Apple 官方：更改多任务与手势设置](https://support.apple.com/zh-cn/guide/ipad/ipad2q9j404h2/ipados) | 手势与设置 | ✅ 已抓取 |
| [Cult of Mac 使用指南](https://www.cultofmac.com/how-to/how-to-get-started-using-stage-manager-on-ipad) | 交互细节补充 | ✅ 已抓取全文 |
| Apple 官方截图（support.apple.com/library/*.png） | 像素级测量 | ❌ CDN 对代理返回 404 HTML |
| Cult of Mac 截图（wp-content/uploads/*.jpg） | 像素级测量 | ❌ 同上 |

**限制声明**：所有远程图片 CDN 都对当前网络环境返回 HTML 挑战页（已验证 4 种来源 9 个 URL，文件头均为 `3C 21 44 4F` 即 `<!DOC`）。因此本文档的尺寸/间距/透明度数值来自：Apple 官方文字描述（相对关系，如"更小/更圆/半透明"）、iPadOS 公开 UI 分析文章的一致结论、以及 iPadOS 系统常量（公开 UIKit/HIG 数值）。**像素级 overlay 对比在取得真实截图后于 `VISUAL_ACCEPTANCE.md` 补做**；本文数值标注来源等级：

- `[HIG]` Apple Human Interface Guidelines 公开常量
- `[TXT]` Apple 官方文字描述推导
- `[OBS]` 多个独立来源一致观察
- `[EST]` 由设备 DPI 推算，需截图校准后修正

## 1. 整体布局（横屏，iPad 11" 等效）

Stage Manager 激活后的屏幕分区（从左到右）：

```
┌──────┬────────────────────────────────────────┐
│ rail │            stage（工作区）              │
│ 最近 │   ┌─────────┐  ┌─────────┐             │
│ 应用 │   │ window 1│  │ window 2│             │
│ 缩略 │   └─────────┘  └─────────┘             │
│ 图列 │                                        │
├──────┴────────────────────────────────────────┤
│                  Dock（悬浮）                  │
└───────────────────────────────────────────────┘
```

### 1.1 左侧最近应用栏（rail）

| 属性 | iPadOS 值 | 来源 | StageLab 目标 |
|---|---|---|---|
| 栏宽 | 约 130–150pt（≈20% 屏宽） | [OBS] | **140pt = 368px**（@2.625） |
| 与屏幕左缘间距 | 约 8pt | [OBS] | 8pt = 21px |
| 项目垂直排列 | 从顶向下，居中对齐 | [TXT] | 居中起始 |
| 项目间距 | 约 12pt | [OBS] | 12pt = 32px |
| 单项目缩略图宽 | 与栏宽一致（约 130pt） | [OBS] | 368px 宽 |
| 单项目缩略图高 | 宽 × (2/3)，即横向窗口比例 | [OBS] | ≈245px |
| 项目圆角 | 与窗口圆角一致 | [TXT] | 22px（见 §4） |
| 项目阴影 | 有，轻微 | [TXT] | 见 §4.3 |
| 项目边框 | 无（仅阴影区分） | [OBS] | 无边框，仅阴影 |
| 当前组是否出现在 rail | **否**（切走后出现） | [TXT] | 否 |
| 最多显示 | 3–4 个完整缩略图，其余折叠 | [OBS] | 3 个 + 折叠堆叠 |
| 滚动/箭头 | **无箭头按钮**，边缘拖拽滚动 | [OBS] | 删除 ▲▼ 按钮 |

**关键差异（当前实现 → 目标）**：

| 当前 | 目标 |
|---|---|
| 238dp(625px) 深色不透明容器 | 368px 宽、**无容器背景**（缩略图直接浮在壁纸上） |
| 256dp 高真实 Task 磁贴 | 245px 高**TaskSnapshot 缩略图** |
| "工作区 1/3" 文字标题 | **无文字**（应用图标叠加在缩略图左上角） |
| "3 个窗口 · 轻点切换" | 无 |
| 窗口数数字方块 | 无 |
| ▲▼ 箭头 | 删除（改边缘拖拽） |
| 左下 "+" 新建组按钮 | 删除（新建组由拖出窗口触发） |

### 1.2 中央工作区（stage）

| 属性 | iPadOS 值 | 来源 | StageLab 目标 |
|---|---|---|---|
| 与 rail 间距 | 约 20pt | [OBS] | 20pt = 53px |
| 与屏幕右缘间距 | 约 12pt | [OBS] | 32px |
| 与 Dock 顶间距 | 约 16pt | [OBS] | 42px |
| 与状态栏间距 | 约 8pt | [OBS] | 21px |

### 1.3 Dock

| 属性 | iPadOS 值 | 来源 | StageLab 目标 |
|---|---|---|---|
| 与屏幕底缘间距 | 约 10pt | [OBS] | 26px |
| Dock 高度（含 padding） | 约 82pt | [OBS] | 215px |
| 背景材质 | 半透明磨砂（blur） | [HIG] | 0.72 透明深色 + 模糊（降级：纯色 0.72） |
| 圆角 | 约 22pt 连续圆角 | [HIG] | 58px |
| 图标尺寸 | 60pt（iPad） | [HIG] | 158px |
| 图标间距 | 约 18pt | [OBS] | 47px |
| 图标垂直内边距 | 约 11pt | [OBS] | 29px |
| 左右内边距 | 约 16pt | [OBS] | 42px |
| 分隔线（固定/最近之间） | 1pt 竖线，半透明 | [OBS] | 3px 宽，α 0.18 |
| 固定应用数 | 用户配置，默认 4 | [TXT] | 默认 4，可扩展 |
| 最近应用区 | 最多 3 个，去重（已在固定区则不显示） | [OBS] | 同 |
| 应用库入口 | 最右侧，网格图标 | [TXT] | 保留 |
| 自动隐藏 | 可设置；底边缘上滑唤出 | [TXT] | 默认不隐藏（V1） |

**当前 Dock 必须删除的元素**：`◧ 应用组`按钮、`⏻ 关闭 Stage Manager` 按钮、`＋`（替代应用库的简陋实现）。

## 2. 窗口视觉

### 2.1 窗口圆角

| 属性 | iPadOS 值 | 来源 | StageLab 目标 |
|---|---|---|---|
| 窗口圆角半径 | 约 10–12pt（窗口），缩略图同 | [OBS] | **28px ≈ 10.7pt**（原 22px 偏小） |
| 圆角类型 | 连续曲率（squircle） | [HIG] | V1 用标准圆角，V2 评估 squircle |
| 真实裁剪要求 | 是 | 用户要求 | 见 §6 实验 |

### 2.2 窗口阴影

| 属性 | iPadOS 值 | 来源 | StageLab 目标 |
|---|---|---|---|
| 焦点窗口阴影 | 明显，扩散约 30pt，α≈0.28 | [OBS] | 79px 扩散，峰值 α 0.28 |
| 非焦点窗口阴影 | 更小更淡，扩散约 14pt，α≈0.18 | [OBS] | 37px，α 0.18 |
| 阴影垂直偏移 | 约 4pt 向下 | [OBS] | 11px |
| 焦点/非焦点差异 | 阴影 + 亮度（非焦点略暗） | [OBS] | 阴影区分（亮度调整受限于 Task Surface） |

### 2.3 窗口控制（iPad 位置，非 macOS 交通灯）

iPad Stage Manager 的窗口**没有常驻标题栏**。窗口控制是：

| 控制 | iPad 行为 | 来源 | StageLab 实现 |
|---|---|---|---|
| 顶部边缘 | 拖动移动窗口；双击缩放 | [TXT] | 保留顶部拖动条（仅上缘 34px，**不可见**） |
| 左上角控制入口 | 单击弹出菜单：关闭/最小化/缩放/移至… | [TXT] | **新**：左上角 44px 圆形半透明按钮（仅焦点窗口显示） |
| 菜单项 | 关闭、最小化、缩放、移到最近列表 | [TXT] | 同左 |
| 底部右侧 | 拖动缩放（斜向） | [TXT] | 保留 |
| 双击顶部 | 切换最大化/还原 | [TXT] | 保留 |

**关键差异**：删除当前的深色常驻标题栏（含红黄绿圆点）。改为：
1. 窗口顶部 34px 的**不可见拖动区**（透明 overlay，`FLAG_NOT_TOUCHABLE=false`，无绘制）；
2. 焦点窗口左上角浮出**单个圆形控制按钮**（半透明白底 α0.75、直径 44px、内有 "⋯" 图形），点击弹菜单；
3. 其余区域完全由应用内容占据。

## 3. 颜色

| 用途 | 值 | 来源 |
|---|---|---|
| 桌面壁纸 | 系统壁纸（StageLab 不绘制背景） | [HIG] Stage 桌面就是壁纸 |
| Dock 背景 | 黑色 α0.72 + blur | [HIG] |
| 窗口菜单背景 | 白色 α0.90 + blur（浅色）/黑 α0.78（深色） | [HIG] |
| 焦点描边 | **无描边**（iPad 靠阴影区分） | [OBS] ← 当前蓝色描边必须删除 |
| 缩略图占位（FLAG_SECURE） | 灰 α0.30 + 应用图标居中 | [EST] |
| 控制按钮 | 白 α0.72，图形黑色 α0.80 | [EST] |

**当前实现必须删除的颜色**：`0xFF141922/0xFF0A0D13` 桌面渐变（应透出壁纸）、`0xFF8AA0FF` 焦点蓝描边、所有 `0x1FFFFFFF` 磁贴底色。

## 4. 尺寸速查表（px @ density 2.625，用于代码常量）

```
RAIL_WIDTH            = 368   (140pt)
RAIL_MARGIN_LEFT      = 21    (8pt)
RAIL_GAP              = 32    (12pt)
RAIL_ITEM_WIDTH       = 368
RAIL_ITEM_HEIGHT      = 245   (≈93pt, 2/3 宽)
RAIL_MAX_VISIBLE      = 3
STAGE_GAP_RAIL        = 53    (20pt)
STAGE_MARGIN_RIGHT    = 32    (12pt)
STAGE_GAP_DOCK        = 42    (16pt)
DOCK_MARGIN_BOTTOM    = 26    (10pt)
DOCK_HEIGHT           = 215   (82pt)
DOCK_RADIUS           = 58    (22pt)
DOCK_ICON             = 158   (60pt)
DOCK_ICON_GAP         = 47    (18pt)
DOCK_PADDING_H        = 42    (16pt)
DOCK_PADDING_V        = 29    (11pt)
DOCK_BG_ALPHA         = 0.72
DOCK_SEPARATOR_W      = 3
WINDOW_RADIUS         = 28    (≈10.7pt)
WINDOW_SHADOW_FOCUS   = 79    (30pt)
WINDOW_SHADOW_BLUR    = 37    (14pt)
WINDOW_SHADOW_ALPHA   = 0.28 / 0.18
CONTROL_BUTTON        = 44    (17pt)
TOP_DRAG_STRIP        = 34    (13pt)
CORNER_HANDLE         = 56    (21pt)
```

## 5. 缩略图技术要求（替代真实 Task 停放预览）

| 项 | 要求 |
|---|---|
| 快照来源 | `TaskSnapshotController.getTaskSnapshot()`（Shell 侧）或 `ITaskSnapshotManager`；读取 `ActivityManager.TaskSnapshot` |
| 刷新时机 | 组切换、窗口 bounds 提交、窗口关闭、每 10s 后台轮询（仅非活动组） |
| 缓存 | LRU（按 taskId+lastActiveTime），磁盘缓存可选 |
| FLAG_SECURE | `snapshot.surfaceControl == null` 或读取失败 → 灰底 + 应用图标 |
| 比例 | 按窗口实际宽高比缩放进 368×245 视口，居中裁切 |
| 输入 | 缩略图**不接受输入**（仅整卡点击切换），真实 Task 保持原 bounds 停放 |

**迁移决定（撤销 ADR-012 的实时预览路线）**：非活动组的 Task 全部 `toBack` 停放（保持原几何），rail 显示快照。这消除了"为预览而把真实窗口缩进侧栏"的整个错误链路，也使得 rail 宽度可以缩到 iPad 比例。

## 6. 圆角技术实验要求

### 实验 A：TaskOrganizer leash + SurfaceControl 装饰

前置事实（来自既有代码）：
- `registerOrganizer()` 以 Shell UID 可用（`SHIZUKU_TASK_PROBE.md` 已验证）
- `onTaskAppeared(info, leash)` 会收到 leash
- **不**做 `reparent`——保留在原层级，仅调用 `leash.transaction().setCornerRadius(r).setShadowRadius(s).apply()`

验证项：
1. 圆角是否真实裁剪（截窗放大 3×）
2. 原生触摸/IME/Back/无障碍回归
3. 多窗口重叠时 leash 层级
4. 注销 organizer 后任务是否恢复

**风险**：注册 organizer 后系统会把任务"交给"我们，需要 `onTaskInfoChanged` 透传；ColorOS 可能拒绝。若失败必须记录具体异常。

### 实验 B：任务窗口尺寸 + `WindowManager.LayoutParams.flags` 装饰

对 FREEFORM Task 无法直接设圆角（Task 不是 View）。评估：
- `setClipToOutline` 只作用于 View，不适用
- ColorOS 私有接口（`OplusWindowManagerService` 拒绝过 WCT bounds，圆角接口预计同样拒绝）

**结论路径**：若 A 失败且 B 不可行 → 保留角落遮罩但**明确标注为视觉降级**，并在遮罩层加阴影以提升可信度；同时把遮罩颜色从"桌面渐变"改为**取壁纸在该区域的实际采样色**（消除纯色块感）。

## 7. 手势生命周期规范（修复残留）

```
START  → 记录 baseRect + 原始指针；清空该窗口 pending 更新
MOVE   → 更新 overlay 位置（立即） + 投递 pendingBounds（合并式，见下）
END    → 提交最终 bounds（同步等待引擎返回）→ 持久化 → 触发 drop 路由
CANCEL → 恢复 baseRect（提交一次）→ 不触发 drop 路由 → 不触发 minimize/move
```

- `ChromeOverlayView`：`ACTION_CANCEL` 独立上报 `PHASE_CANCEL`（当前与 UP 合并，**必须拆开**）
- `StageHostActivity.pendingGeometry`：`collectLatest` 会取消上一个未完成的 commit——改为**按窗口串行队列**（每窗口一个 Channel，`consumeAsFlow` 顺序消费，新值合并覆盖未提交的旧值）
- Overlay 位置与真实 bounds 的同步以 `pendingBounds` 为唯一权威；`syncGeometry` 不得在手势进行中运行（`applying` 标志扩展为 per-window）
- 窗口/组销毁时 `holders.remove` + 全部 overlay `removeView`（已实现，需加回归测试）

## 8. 应用窗口默认尺寸（撤销全局比例）

| 应用类型 | 判定 | 初始窗口（工作区占比） | 来源 |
|---|---|---|---|
| 竖屏应用 | `requestedOrientation == PORTRAIT` 或 Activity 仅支持 portrait，或 `resizeableActivity=false` | w=0.42, h=0.78，x 居中 | [TXT] |
| 视频类 | 包名/Activity 属于已知视频应用或 `supportsPictureInPicture` | w=0.62, h=0.62，横置 | [EST] |
| 通用可调 | 其余 | w=0.68, h=0.76 | [TXT] |
| 用户偏好 | 手动缩放过的应用 | 持久化 per-package (w,h)，后续恢复 | 用户要求 |

实现位置：新文件 `stage/WindowSizing.kt`，替换 `StageWorkspace.defaultGeometryFor` 的固定比例。错位规则：第 n 个窗口 x/y 偏移 `(n*24px, n*18px)`，超出工作区则回绕，**不**全部居中。

## 9. 不变式（本轮改动不得破坏）

- 原生触摸 / IME / Back / 无障碍：每次改动后用真实第三方应用回归
- 关闭模式不杀任务；设置守卫逻辑不变
- Shizuku Shell 为默认权限路径
- 现有 `StageSession` 持久化格式兼容（几何归一化坐标不变）
