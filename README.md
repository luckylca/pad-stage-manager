<div align="center">
  <img src="stage-native/module/src/main/res/drawable/ic_launcher_foreground.xml" width="108" alt="StageLab Native Icon" />
  <h1>StageLab Native</h1>
  <p><strong>iPadOS-style Stage Manager Xposed Module for Android Tablets &amp; Foldables</strong></p>
  <p>
    <a href="README_CN.md">简体中文</a> ·
    <a href="#features">Features</a> ·
    <a href="#compatibility">Compatibility</a> ·
    <a href="#installation">Installation</a> ·
    <a href="#building">Building</a>
  </p>
</div>

---

StageLab Native brings a native, 120fps iPadOS-style **Stage Manager** experience to Android 13–17 tablets and foldables using real system window orchestration (`ShellTaskOrganizer`, `WindowContainerTransaction`, and ColorOS Flexible Window — never fake WebViews, MediaProjection clones, or accessibility-service overlays).

> Turning the module switch **OFF** (`enable/disable`) disables all active hooks immediately without lingering side effects, restores normal ColorOS/Android desktop & navigation behavior, and lets you safely recover anytime via the companion control panel or exit notification.

## Showcase

| Control Panel & Diagnostics | Side-by-Side Stage & 3D Strip | 120fps Magic Bloom App Search |
|---|---|---|
| ![Control Panel](docs/native/control-panel.png) | ![Stage Landscape](docs/native/stage-landscape.png) | ![App Search](docs/native/app-search.png) |

## Features

- **Real Multi-Window Stages**: Launch or merge apps into workspaces (`Stage`), run unlimited windows + per-Stage edge-tucked mini/sidebar windows (`FloatHandle`), and tile 2–3 active windows automatically with zero overlap.
- **Orientation-Adaptive Layouts**:
  - **Landscape**: Side-by-side multi-column tiling with full-height workspace canvases.
  - **Portrait**: Full-width stacked horizontal rows so windows remain crisp and readable without collapsing into narrow vertical strips.
  - **Seamless Portrait ↔ Landscape Rotation**: Live display listener & post-transition scale recalibration keep windows, Dock, Left Recent Strip, and App Search aligned with notch/cutout and navigation-bar safe insets.
- **3D Slanted Left Recent Strip & Magic Move**:
  - Real `TaskSnapshotManager` / `ActivityTaskManager` live thumbnails with `FLAG_SECURE` awareness.
  - 120fps Choreographer two-phase Stage switch animation (`StageMagicTransitionOverlay`) supporting tap-to-switch and drag-to-merge.
- **Unified Floating Dock & App Search (`⌕`)**:
  - Pre-warmed, zero-stutter physics-spring (`Choreographer`) App Search sheet with instant scrim/Dock touch interruption at any animation frame.
  - **Unified 120fps Free Drag & Drop**: Long-press (~200ms) any app in App Search or drag icons directly from the Dock to:
    - Pin into exact Dock slots with live sibling spacing & insertion bar preview (persisted across reboots),
    - Reorder pinned Dock icons horizontally,
    - Unpin by dragging to the top remove pill or past Dock side edges,
    - Merge directly into the Active Stage or drop onto any Left Recent Strip workspace card.
  - Auto-dismisses App Search smoothly whenever a workspace switch is initiated via Dock or exposed Recent Strip cards.
- **Built-in Compatibility Diagnostics**: Real-time inspection of every `system_server` & `SystemUI` hook probe (`HIT` / `FALLBACK` / `MISSING`) plus one-tap clipboard report copy inside `MainActivity`.

## Compatibility

| Component / Tier | Support Scope |
|---|---|
| **Android Versions** | **Android 13 – 17** (API 33 – 37), `minSdk = 33`, `targetSdk = 36` |
| **Framework** | **LSPosed / Vector** (`xposedminversion = 82`) |
| **Xposed Scope** | `system` (Android System), `com.android.systemui`, `com.android.launcher` |
| **ROM Integration** | **ColorOS 13 – 17 / OxygenOS / realme UI** (native Flexible Window tier) + **AOSP Freeform** (`WCT#setBounds` / `WINDOWING_MODE_FREEFORM` fallback) |
| **Form Factors** | Foldables (`Compact`), 8.8" Mini Tablets (`OPPO Pad mini`), 11"–12.1" Standard Tablets, 13.2"+ Pro Tablets |
| **Live Verified Hardware** | OPPO Pad mini (`OPD2515`), ColorOS `V17.0.0`, Android 17 (API 37), `1680×2520 @ 120 Hz` |

> Note: On non-ColorOS or AOSP builds where `FlexibleWindowManager` is absent, StageLab automatically falls back to standard `WindowContainerTransaction#setBounds` + `WINDOWING_MODE_FREEFORM`. Check the **Diagnostics** card inside the app to inspect active probes on your ROM.

## Installation

1. Download the latest APK from [GitHub Releases](../../releases) (or the LSPosed Module Repository once listed).
2. Install the APK on your device.
3. Open **LSPosed / Vector**, enable **StageLab Native (`dev.stagelab.nativehook`)**, and verify the target scope includes:
   - `system` (System Framework)
   - `com.android.systemui` (System UI)
   - `com.android.launcher` (System Launcher)
4. Reboot (or soft-reboot via KernelSU/LSPosed) once after enabling or updating the module.
5. Open **StageLab Native** and toggle **Enable Stage Manager** (notification permission allows the quick one-tap exit notification).

### Emergency Disable / Recovery

If anything unexpected occurs on an untested ROM:

```bash
# Disable Stage Manager mode cleanly via ADB
adb shell am broadcast -a dev.stagelab.nativehook.ACTION_STAGE_CMD --es cmd disable_module -p com.android.systemui

# Or disable the Xposed module completely via Vector/LSPosed CLI and restart SystemUI/Launcher
adb shell su -c "/data/adb/lspd/cli modules disable dev.stagelab.nativehook"
adb shell su -c "pkill -f com.android.systemui; pkill -f com.android.launcher"
```

See [stage-native/docs/SAFE_RECOVERY.md](stage-native/docs/SAFE_RECOVERY.md) for additional recovery steps.

## Building

```powershell
cd stage-native
.\gradlew.bat :core:test :module:assembleDebug :module:assembleRelease
```

APKs are written to:
- `stage-native/module/build/outputs/apk/debug/module-debug.apk`
- `stage-native/module/build/outputs/apk/release/module-release.apk`

For custom release signing, either define environment variables (`STAGELAB_KEYSTORE_FILE`, `STAGELAB_KEYSTORE_PASSWORD`, `STAGELAB_KEY_ALIAS`, `STAGELAB_KEY_PASSWORD`) or place an ignored `keystore.properties` file in `stage-native/`.

## Repository Structure

```text
pad-stage-manager/
├── stage-native/                 # Active LSPosed / Xposed implementation (Android 13–17)
│   ├── core/                     # Pure Kotlin Stage state machine & unit tests
│   └── module/                   # Android module APK (hooks, bridge, overlays, Material You UI)
├── app/                          # Archived v1 standalone Shizuku Freeform prototype
├── docs/
│   └── native/                   # Showcase screenshots & stage-native verification docs
├── .github/workflows/build.yml   # CI tests + release APK workflow
├── LICENSE                       # GNU General Public License v3.0
├── README.md                     # English documentation (LSPosed Repo)
└── README_CN.md                  # 简体中文文档
```

## License

This project is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE) for details.
