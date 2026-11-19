# 实时 Task Surface PoC 计划

## 验收目标

在 OPPO Pad mini 上同时展示至少三个独立 Android Task 的真实活动画面。计数器持续变化、运动图形持续运动、滚动列表可交互。点击某个窗口后，该 Task 能成为主窗口；其他 Task 不应因切换而被销毁或重新启动。

不接受静态截图、定时截屏或仅显示最近应用图标作为 PoC 成功。

## P0：设备与权限事实核验

在平板连接 ADB 后记录：

- 型号、产品代号、完整 Build Fingerprint。
- Android API level、ColorOS build、security patch。
- 完整 uname -a。
- Shell UID 实际权限。
- 当前 WindowManager / ActivityTaskManager 服务实现差异。

建议的初步命令：

    adb devices -l
    adb shell getprop ro.product.model
    adb shell getprop ro.product.device
    adb shell getprop ro.build.fingerprint
    adb shell getprop ro.build.version.sdk
    adb shell getprop ro.build.version.security_patch
    adb shell uname -a
    adb shell id
    adb shell pm check-permission android.permission.MANAGE_ACTIVITY_TASKS com.android.shell

pm check-permission 只是初筛；最终以真实 Binder 调用结果为准。不要假定 Shell 必然拥有该权限。

## P1：Shizuku / Shell TaskOrganizer 探针

1. 集成 Shizuku 的稳定 API。
2. 建立最小 Shizuku UserService / Binder 服务。
3. 记录服务运行 UID、PID 和权限检查结果。
4. 检查并尝试注册 TaskOrganizer。
5. 记录系统异常、SecurityException、OEM 拒绝原因。
6. 不执行 reparent，不修改任务边界，先只验证注册和回调。

通过标准：真实系统服务接受注册，并能收到可解释的任务回调。

失败标准：权限被拒绝、接口不可用或 ColorOS 实现不兼容。失败后保留日志，转入 Root/系统集成可行性评估，不进行盲目反射调用。

## P2：三个动态任务

使用仓库内的 DemoActivity 启动三个独立任务：

- Counter：周期性递增。
- Motion：持续动画。
- Scroll：可滚动列表。

先用系统 Recents 验证三个任务能独立存在、切换和恢复，再进入 Surface 托管实验。

## P3：真实 Surface 托管

在确认 TaskOrganizer 和所需事务权限后，研究 Task leash 与 SurfaceControl 宿主：

- 创建宿主 Surface。
- 获取任务 Surface leash。
- 受控地将 Task Surface 挂载到宿主。
- 保证宿主与 Task 生命周期同步。
- 测试多任务同时可见时是否持续刷新。
- 逐步加入尺寸调整和布局切换。

任何一步遇到权限拒绝，都先定位具体权限/接口，不用截图方案冒充实时预览。

## P4：输入与焦点

验证：

- 点击、滑动、长按是否进入正确 Task。
- 点击预览后焦点是否切换。
- 键盘、IME、文本选择和系统返回行为。
- 无障碍节点与系统导航手势。
- 宿主窗口与任务窗口 Insets 对齐。

## P5：动画与性能

目标：

- 主窗口切换有连续的缩放、位移和层级变化。
- UI 与 Surface 变换同步。
- 首轮目标 60 FPS，之后按设备刷新率优化。
- 记录帧时间、掉帧、输入延迟、Surface 重建和内存。
- 多个动态任务同时可见时，不应因频繁截图造成明显 CPU/GPU 开销。

## 兼容性矩阵

至少覆盖：

- 普通 Activity。
- 横竖屏变化。
- FLAG_SECURE 窗口。
- DRM 视频。
- PiP。
- 不支持 resize 的 Activity。
- 弹窗、权限页、输入法和系统设置。
- 应用进程被系统回收后的恢复行为。

## 阶段退出条件

只有 P3 成功，才进入完整 Stage Manager 工作区 UI。若系统不允许第三方托管真实 Task Surface，应明确记录限制，并评估系统级集成路线；不把普通 Overlay、截图或单纯启动应用包装成等价实现。
