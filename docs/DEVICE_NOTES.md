# 目标设备记录（全部经 ADB 实测）

## 设备

| 项目 | 实测值 |
|---|---|
| 型号 | OPPO Pad mini，`OPD2515`，device `OP6548L1` |
| 构建 | `OPD2515_17.0.0.103(CN01)` |
| Fingerprint | `OPPO/OPD2515/OP6548L1:17/CP2A.260605.016/B.1218030_142fc66_1415373:user/release-keys` |
| Android | 17（SDK 37） |
| 安全补丁 | 2026-09-01 |
| Kernel | `6.12.69-android16-6-g242e1a07f878-ab15865446-4k`，aarch64 |
| 平台 | `canoe`，ABI `arm64-v8a` |
| 屏幕 | 1680 × 2520，420 dpi，默认模式 120 Hz；可选 60 / 90 / 120 / 144 Hz |
| 圆角/挖孔 | 圆角 117 px，顶部挖孔 105 px |
| Root | KernelSU（`me.weishu.kernelsu`），`su` 仅对 `shell` 直接可用 |
| Shizuku | 13.6.0，服务以 Shell UID 2000 运行 |

## 权限实测

```
uid=2000(shell) context=u:r:shell:s0
android.permission.MANAGE_ACTIVITY_TASKS: granted=true
android.permission.REMOVE_TASKS: granted=true
android.permission.REORDER_TASKS: granted=true
START_ANY_ACTIVITY: 未持有（无法启动未导出 Activity）
```

## 窗口能力实测

* `am start --windowingMode 5` 可把应用启动为 **freeform** 任务；该构建下没有 caption/标题栏，
  应用内容直接铺满任务矩形。
* `am task resize` 与 `IActivityTaskManager.resizeTask(int,Rect,int)` 均精确生效。
* `setFocusedTask` 可以让被全屏任务遮挡的 freeform 任务重新可见。
* freeform 任务有系统最小尺寸，约 **220 dp**（更窄的请求会被 WM 向右扩宽并顶出屏幕）。
  因此右侧预览栏宽度必须 > 220 dp（当前 232 dp / 横屏 244 dp）。
* 多个 freeform 任务可以同时 `visible=true`；但被全屏 Activity 覆盖时会变为不可见，
  需要重新 focus 才能恢复（StageLab 的 `ensureVisible` 即为此实现）。
* IME 属于当前聚焦的 freeform 任务，键盘会覆盖舞台底部（与 iPad 行为类似）。
* ColorOS 安装侧载 APK 时会弹一次“继续安装”确认页。

## Android 17 隐藏 API 签名（与本工程相关）

```
android.window.TaskOrganizer
  registerOrganizer() -> List
  createRootTask(int,int,IBinder) -> void          // 注意：16 的 boolean 重载已移除
  createTask(TaskCreationParams) -> TaskAppearedInfo
  deleteRootTask(WindowContainerToken) -> boolean
  getRootTasks(int,int[]) -> List
  onTaskAppeared(RunningTaskInfo,SurfaceControl)
  onTaskVanished(RunningTaskInfo)
  onTaskInfoChanged(RunningTaskInfo)
  onBackOnTaskRoot(RunningTaskInfo,boolean,boolean,boolean)   // 旧 onBackPressedOnTaskRoot 已改名

android.app.IActivityTaskManager
  getTasks(int,boolean,boolean,int) -> List
  resizeTask(int,Rect,int) -> void
  setFocusedTask(int) -> void
  removeTask(int) -> boolean
  startActivityAsUser(IApplicationThread,String,String,Intent,String,IBinder,String,int,int,ProfilerInfo,Bundle,int) -> int
  getTaskBounds(int) -> Rect
```

`app/src/hiddenApiStubs/java/android/window/TaskOrganizer.java` 只保留编译所需的子类方法；
其余全部通过 `HiddenApi` 运行时解析，避免 OEM 改动导致 `NoSuchMethodError`。

## Windows 开发环境

* 工程：`E:\codex\pad-stage-manager`
* SDK：`D:\Android\sdk`（Platform 37.0 / 37.2，Build-Tools 37.0.0，Platform-Tools 37.0.1）
* JDK 23；Gradle Wrapper 9.9.1；AGP 9.5.1；Kotlin 2.4.21
* 外网依赖通过 `127.0.0.1:7890` 代理下载（仅构建进程配置）
