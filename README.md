# StageLab — an iPad style Stage Manager for Android

StageLab is a **mode switch**: off, the app is one switch; on, it becomes a full Stage Manager
workspace with a recent-groups rail on the left, free floating application windows in the middle and
an iPad style dock at the bottom.

It is **not** a launcher with screenshots. There is no MediaProjection, no WebView fake, no
periodic bitmap capture. Every window, including the rail previews, is a real Android task surface
composited by SurfaceFlinger.

Verified on **OPPO Pad mini (OPD2515)**, ColorOS 17.0.0.103, Android 17 / SDK 37, 1680×2520 @ 120 Hz.

| OFF | ON |
|---|---|
| ![off](docs/m1-control.png) | ![on](docs/m2-windows.png) |

| recent-group rail (live preview) | drag / resize chrome |
|---|---|
| ![rail](docs/m2-rail.png) | ![drag](docs/m4-drag.png) |

## Product model

* **OFF** — a single switch, a status line, and (only on first use) the Shizuku grant. No task
  list, no diagnostics, no developer buttons.
* **ON** — immersive workspace: left recent-group rail with live previews, overlapping real app
  windows with traffic-light title bars, bottom dock with real app icons.
* Turning the mode off restores every staged task to full screen and leaves the user on the
  foreground application; no app is killed, and the global settings StageLab changed are restored
  (only if the user did not change them meanwhile).

## What works today (device verified)

* **Multiple real tasks on screen at once**, freely positioned and overlapping.
* **Live rail previews** — the rail tiles are the real parked task windows, not screenshots.
* **Groups** — create a group, switch groups from the rail, each group keeps its own windows and
  geometry, persisted across restarts.
* **Real gestures** — drag a window by its title bar, resize from the corner/edges, minimize
  (traffic light), restore from the minimized tray, close, tap a dock icon to open.
* **Native input / IME / Back** stay with the application: the chrome is a thin overlay, everything
  else is the app's own window.
* **Self healing** — dropped Shizuku UserService reconnects with backoff; orphan tasks are adopted
  instead of duplicating windows.

## Permission tiers

StageLab always tries the lowest privilege that can do the job.

| Tier | Requirement | What it can do |
|---|---|---|
| Level 1 | nothing | launch apps, show the chrome. Cannot host or arrange other apps' tasks (platform restriction). |
| Level 2 | **Shizuku** (Shell UID 2000) | full stage: create FREEFORM tasks, move/resize/focus/close them, live previews, IME. |
| Level 3 | **KernelSU / root** | same engine running as UID 0. Requires KernelSU to allow `su` for StageLab. |

The privileged engine is identical for every tier — only the transport differs
(Shizuku UserService / KernelSU `app_process` service / ADB loopback service).

## Quick start

```powershell
# build
./gradlew :app:assembleRelease

# install (ColorOS asks you to confirm the side-loaded APK once)
adb install -r app/build/outputs/apk/release/app-release.apk

# start Shizuku once per boot (root or the official adb starter)
adb shell /data/app/*/moe.shizuku.privileged.api*/lib/arm64/libshizuku.so

# run the acceptance scenario on the device
./scripts/device-smoke-test.ps1
```

Then open StageLab → “连接 Shizuku” → allow → press **＋** and pick apps, or press **演示** to open
the counter / motion / video test windows.

## How it works (short version)

1. The app asks the privileged service to start an app with
   `ActivityOptions#setLaunchBounds` + `setLaunchWindowingMode(FREEFORM)`.
2. The service (Shell UID) calls `IActivityTaskManager.startActivityAsUser`, then keeps the task
   list fresh through `getTasks`.
3. Geometry is applied with `IActivityTaskManager.resizeTask`; focus with `setFocusedTask`;
   closing with `removeTask`.
4. The stage UI is a full-screen, non-focusable window *behind* the tasks. It draws the desk,
   focus halos, top bar and dock; the task windows are composited above it by the system.
5. When the user taps a rail window, WindowManager focuses it. StageLab notices the focus change
   and swaps the rail and main rectangles — the task itself never restarts.

Details, including the AOSP references and the hidden API surface, are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Measured results are in
[docs/TEST_REPORT.md](docs/TEST_REPORT.md), and the per-tier truth table is in
[docs/CAPABILITY_MATRIX.md](docs/CAPABILITY_MATRIX.md).

## Known limitations (measured, not guessed)

* Freeform tasks have a system minimum size (~220 dp on this build). The rail is sized above it;
  extra windows are **parked behind the main window** instead of being squeezed out of the screen.
* Rotating to landscape leaves room for one preview, so the second preview is parked.
* Third-party apps may be recreated by Android when their window is resized. StageLab keeps the
  task and process, and the demo targets declare `configChanges` so their counters keep running.
* Launching a non-exported activity needs `START_ANY_ACTIVITY`, which Shell does not hold; the
  app picker therefore lists exported launcher activities only.
* KernelSU blocks `su` exec for `untrusted_app` until the user grants root to StageLab. The root
  engine itself is verified (see the test report).

## Repository layout

```
app/src/main/java/dev/stagelab/android/
  core/privileged/   engine that runs as Shell/Root: TaskMutations, StageEngine, organizers
  core/ipc/          transports (Shizuku UserService, KernelSU root, ADB loopback)
  core/stage/        layout engine + StageRuntime (state, polling, geometry sync)
  ui/                Compose stage, app picker, diagnostics
  demo/              live counter / motion / video test targets
scripts/             hidden API stubs, device smoke test, ADB service launcher
docs/                architecture, test report, capability matrix, device notes
```
