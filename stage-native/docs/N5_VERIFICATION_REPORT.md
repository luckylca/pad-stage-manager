# StageLab Native — N5（左侧 Recent Strip 与预览技术路径）真机验收报告

## 1. 左侧 Recent Strip 实现与真机效果

组件位置：
- `stage-native/module/src/main/java/dev/stagelab/nativehook/ui/strip/RecentStripOverlayController.kt`
- `stage-native/module/src/main/java/dev/stagelab/nativehook/ui/strip/NativeTaskSnapshotLoader.kt`

严格对齐 iPad Stage Manager 视觉与交互要求：
- **SystemUI 原生集成**：直接挂载于 `com.android.systemui` 进程（`StageLabRecentStrip`，`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_HARDWARE_ACCELERATED`），不抢占应用输入焦点与 IME。
- **纯净悬浮视觉**：最多显示 4 个最近 Stage，自然悬浮在原生桌面壁纸左侧，带柔和 3D 内倾透视（`rotationY = 10f`）与圆角阴影；**无外框侧栏背景容器、无“工作区 1/3”文字卡片、无新建 `+` 按钮、无上下箭头**。
- **多窗口组合缩略图**：2~4 窗 Stage 以层叠空间组合呈现每个窗口的真实画面与应用图标角标。
- **交互**：
  - 点击缩略图：已真机实测通过手指触摸直接触发 `RECENT_STRIP click switch`，完成 S2 切换并保留原 Task ID。
  - 向右拖入中央（`dx >= 72dp`）：触发 S3 合并到当前 Active Stage（满 4 窗自动 S4 溢出）。
  - 侧栏隐藏与左边缘呼出：支持隐藏为 18dp 透明左边缘手势热区，右滑临时呼出。

## 2. 实时预览 vs `TaskSnapshot` 技术路径诚实结论

在 OPPO Pad mini (ColorOS 17 / Android 17 API 37) 实测确认：

| 预览模式 (`PreviewMode`) | 技术机制 | 真机状态与边界 |
| --- | --- | --- |
| `NATIVE_TASK_SNAPSHOT` | Android 17 `android.window.TaskSnapshotManager.getInstance().getTaskSnapshot(taskId, 1)`（低分辨率优先，缺失时 `takeTaskSnapshot`）+ `Bitmap.wrapHardwareBuffer` | **已真机实测通过**（如 `793x1411` 高清窗口画面，清晰渲染设置/计算器/闹钟真实内容）。 |
| `LIVE_SURFACE_MIRROR` | `ShellTaskOrganizer.getTaskSurface(taskId)` 镜像 | **仅对当前可见 Task 有效**；当任务通过 `ExitFlexibleTaskToBackStrategy` 停放为 `visible=false` 后，Android WMS / SurfaceFlinger 会停止向不可见任务生产新帧，因此**不将静态 `TaskSnapshot` 冒充为后台实时画面**。 |
| `UNAVAILABLE_OR_SECURE` | `TaskSnapshot.isRealSnapshot() == false`（`FLAG_SECURE`）或无缓冲时降级 | 严格遵守 `FLAG_SECURE`，降级显示柔和毛玻璃卡片 + 居中应用图标。 |
