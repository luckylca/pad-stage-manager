# Shizuku / TaskOrganizer 探针

## 目标

先验证 ColorOS 是否允许第三方工作进程通过 Shizuku 的 Shell/Root 身份注册 `android.window.TaskOrganizer`，并观察任务回调。此阶段只做权限与回调探测，不尝试托管或改变任何任务窗口。

## 实现

- Shizuku API：`dev.rikka.shizuku:api:13.1.5` 与 `dev.rikka.shizuku:provider:13.1.5`。
- Manifest 注册 `ShizukuProvider`。
- `IStageTaskService.aidl` 定义 Binder 协议，`destroy()` 使用 Shizuku 约定的 transaction code `16777114`。
- `StageTaskUserService` 由 Shizuku UserService 在独立特权进程运行，返回实际 UID、PID、SELinux context。
- UI 查询 Shizuku 服务身份，并调用 `Shizuku.checkRemotePermission("android.permission.MANAGE_ACTIVITY_TASKS")`。
- `StageTaskOrganizer` 继承框架 `TaskOrganizer`，仅记录 appeared、vanished、info changed、back pressed 回调。
- 注册后记录系统返回的初始 task 数量；注销时调用 `unregisterOrganizer()`。

## 隐藏 API 编译桩

Android SDK 的公开 `android.jar` 不包含 `android.window.TaskOrganizer`。仓库因此提供一个最小编译桩：

- 源码：`app/src/hiddenApiStubs/java/android/window/TaskOrganizer.java`
- 生成脚本：`scripts/build-hidden-api-stubs.ps1`
- 生成文件：`app/libs/android-hidden-api-stubs.jar`
- Gradle 依赖使用 `compileOnly`，该桩不会被打包进 APK；运行时由 Android framework 提供真实 `TaskOrganizer` 类。

修改桩的方法签名时，必须对照目标 Android API 的 AOSP 实现，不能把桩的空方法当成运行时实现。

## 操作路径

1. 在平板安装并启动 Shizuku；非 Root 路径可使用系统无线调试启动 Shizuku。
2. 打开 StageLab 主界面的 **OPEN SHIZUKU PROBE**。
3. 点击 **Request access / connect**，在 Shizuku 中授权 StageLab。
4. 检查服务 UID：`2000` 表示 Shell，`0` 表示 Root；同时记录 SELinux context 和 `MANAGE_ACTIVITY_TASKS` 检查结果。
5. 点击 **Register TaskOrganizer (read-only)**。
6. 注册后点击 **Open Counter / Motion / Scroll tasks**，返回探针页后刷新回调日志。
7. 完成后点击 **Unregister**。

## 安全边界

探针明确不调用 `createRootTask`、`WindowContainerTransaction`、`SurfaceControl.Transaction`，不执行 `reparent`、`setBounds`、`setHidden`、`reorder` 或任务删除。测试任务仍按普通 Activity 启动。

Shizuku 授权仅表示应用获准使用 Shizuku API；Shell/Root 进程是否具有 `MANAGE_ACTIVITY_TASKS` 仍需独立检查。权限检查只是预检，真实 `registerOrganizer()` Binder 调用的结果才是决定性证据。Root UID 可能直接通过 Shizuku 的远端权限检查，但 OEM Binder/SELinux 行为仍须在设备上验证。

## 当前验证状态（2026-11-19，OPPO Pad mini 实测）

- [x] Windows Debug / Release APK 编译成功。
- [x] AIDL、Shizuku Provider、UserService、TaskOrganizer observer 已纳入 APK 构建。
- [x] 注册失败异常会返回到 UI；任务回调可以查询。
- [x] 真机安装与 Shizuku 授权（Shizuku 13.6.0，服务由官方 starter 以 Shell 启动）。
- [x] 真机 UID / SELinux / `MANAGE_ACTIVITY_TASKS` 实测：
      `uid=2000(shell) selinux=u:r:shell:s0`，`MANAGE_ACTIVITY_TASKS: granted=true`。
- [x] 真机 `registerOrganizer()`：**注册成功**，返回初始任务列表；回调可读。
- [x] Android 17 的 `TaskOrganizer` 签名已核对，与 Android 16 AOSP 有三处差异：
      `createRootTask(int,int,IBinder)`（无 boolean 重载）、`createTask(TaskCreationParams)`、
      `onBackOnTaskRoot(RunningTaskInfo,boolean,boolean,boolean)`（原 `onBackPressedOnTaskRoot`）。
      本仓库的编译桩与运行时探测均已按此更新。
- [x] Shizuku UserService 进程 `dev.stagelab.android:stage-engine` 以 UID 2000 运行，
      通过同一 `StageEngine` 完成真实的窗口创建、resize、focus、close。

结论：本机 Shell 权限足以支撑完整 Stage 功能，`TaskOrganizer` 嵌入路线不再是必需路径；
默认实现改用系统 FREEFORM 任务（见 `DECISIONS.md` ADR-004）。

## 参考

- Shizuku API guide：https://github.com/RikkaApps/Shizuku-API
- AOSP TaskOrganizer：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/window/TaskOrganizer.java
- AOSP TaskViewTaskController：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/WindowManager/Shell/src/com/android/wm/shell/taskview/TaskViewTaskController.java
