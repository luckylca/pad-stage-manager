# 官方研究资料

## AOSP WindowManager Shell

- TaskView：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/WindowManager/Shell/src/com/android/wm/shell/TaskView.java
- TaskViewTaskController：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/WindowManager/Shell/src/com/android/wm/shell/TaskViewTaskController.java
- TaskOrganizer：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/window/TaskOrganizer.java
- Framework Manifest permissions：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml

注意：AOSP TaskView 位于 WindowManager Shell 内部代码，不是普通第三方 APK 可直接使用的稳定 SDK。源码可用于理解系统实现，不代表 OEM 构建对外开放相同接口。

## Android 官方构建文档

- Android 17 SDK setup：https://developer.android.com/about/versions/17/setup-sdk
- Android Gradle Plugin releases：https://developer.android.com/build/releases/about-agp
- Android Gradle Plugin release notes：https://developer.android.com/build/releases/about-agp
- Jetpack Compose BOM：https://developer.android.com/develop/ui/compose/bom
- Android build JDK selection：https://developer.android.com/build/jdks

## Shizuku

- GitHub：https://github.com/RikkaApps/Shizuku
- API / developer documentation：https://github.com/RikkaApps/Shizuku-API- Shizuku API Maven dependency：`dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5`

## 本仓库采用的基线

- Android Gradle Plugin 9.5.1
- Gradle Wrapper 9.9.1
- Kotlin 2.4.21
- Jetpack Compose BOM 2026.09.00
- Android compileSdk 37
- Android targetSdk 36

具体版本与兼容关系应在依赖升级时重新核对官方发布说明。
