# StageLab Native — 安全模式、ADB 恢复与卸载说明

本模块为 LSPosed (Vector) 系统级模块。任何阶段都必须保证在无需刷机、无需重置数据的前提下可通过 ADB 在 10 秒内完全禁用并恢复 ColorOS 原生体验。

## 1. 运行时安全开关（无需重启）

模块在加载和每次回调前都会检查开关与安全模式标记文件（同时支持应用内设置页与 ADB 命令控制）：

- **启用/禁用 Hook**：
  ```bash
  # 立即进入安全模式（停止所有 Hook 逻辑，仅透传系统原生行为）
  adb shell su -c "touch /data/local/tmp/stagelab_native_safemode"

  # 退出安全模式
  adb shell su -c "rm -f /data/local/tmp/stagelab_native_safemode"
  ```

## 2. 通过 Vector CLI 立即禁用模块（推荐回滚流程）

设备已安装 `Vector v2.2` 并提供 `/data/adb/lspd/cli`：

```bash
# 1. 禁用 StageLab Native 模块
adb shell su -c "/data/adb/lspd/cli modules disable io.github.luckylca.stagelab"

# 2. 若仅 Hook 了 SystemUI，重启 SystemUI 即可完全脱离注入（无需重启整机）
adb shell su -c "pkill -f com.android.systemui"
```

## 3. 完全卸载模块

```bash
adb shell su -c "/data/adb/lspd/cli modules disable io.github.luckylca.stagelab"
adb uninstall io.github.luckylca.stagelab
adb shell su -c "rm -f /data/local/tmp/stagelab_native_safemode /data/local/tmp/stagelab_native_config.properties"
adb shell su -c "pkill -f com.android.systemui"
```

## 4. 极端异常恢复（若系统界面异常）

- **方案 A（ADB 在线）**：执行上方第 2 节禁用模块并重启 `com.android.systemui` 或 `adb reboot`。
- **方案 B（KernelSU 安全模式）**：开机时按 KernelSU 官方按键触发模块安全模式，系统将自动禁用全部 Zygisk/Vector 模块，进入正常桌面后卸载 `io.github.luckylca.stagelab`。

## 5. 日志排查

```bash
adb logcat -s StageLabNativeHook:V
```
