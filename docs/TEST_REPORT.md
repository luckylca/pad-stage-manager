# Device test report — OPPO Pad mini

Everything in this report was measured on the physical device over ADB. Nothing here is inferred
from "it compiles".

## 1. Device under test

| Field | Value |
|---|---|
| Model | OPPO Pad mini (`OPD2515`, device `OP6548L1`) |
| Build | `OPD2515_17.0.0.103(CN01)` |
| Fingerprint | `OPPO/OPD2515/OP6548L1:17/CP2A.260605.016/B.1218030_142fc66_1415373:user/release-keys` |
| Android | 17 (SDK 37) |
| Kernel | `6.12.69-android16-6-g242e1a07f878-ab15865446-4k` (aarch64) |
| Display | 1680 × 2520, 420 dpi, default mode 120 Hz, modes 60 / 90 / 120 / 144 Hz |
| Root | KernelSU (`me.weishu.kernelsu`), root only for `shell` until the user grants an app |
| Shizuku | 13.6.0, server started as Shell UID 2000 via the official starter |
| OEM | ColorOS 17 multi-window + floating windows present |

## 2. Environment facts used by the implementation

| Probe | Result |
|---|---|
| Shell UID / SELinux | `uid=2000(shell) … context=u:r:shell:s0` |
| `MANAGE_ACTIVITY_TASKS` for `com.android.shell` | **granted** |
| `REMOVE_TASKS`, `REORDER_TASKS` for shell | granted |
| `START_ANY_ACTIVITY` for shell | **not held** → only exported activities can be launched |
| `IActivityTaskManager.getTasks(int,boolean,boolean,int)` | present, returns 13 tasks |
| `IActivityTaskManager.resizeTask(int,Rect,int)` | present, applied bounds exactly |
| `IActivityTaskManager.setFocusedTask(int)` | present, restores a hidden freeform task |
| `ActivityOptions.setLaunchBounds` / `setLaunchWindowingMode` | present |
| `TaskOrganizer` registration from Shell | works (`registerOrganizer` returns a list) |
| `TaskOrganizer.createRootTask` signature | `(int displayId, int windowingMode, IBinder cookie)` — the Android 16 `boolean` overload is gone |
| Freeform task minimum size | ~220 dp (WM expands narrower requests; rail must stay above it) |
| Freeform window decorations | none on this build (no caption bar) |
| IME in a freeform task | works; keyboard belongs to the focused task |

## 3. Build

```
./gradlew :app:assembleDebug     # 31.9 MB
./gradlew :app:assembleRelease   # 24.4 MB, signed V2 (CN=StageLab)
```

APKs:
`app/build/outputs/apk/debug/app-debug.apk`
`app/build/outputs/apk/release/app-release.apk`

## 4. Acceptance run (release APK, Shizuku backend)

Command: `./scripts/device-smoke-test.ps1`

```
== device ==
fingerprint=OPPO/OPD2515/OP6548L1:17/CP2A.260605.016/...:user/release-keys
sdk=37 Physical size: 1680x2520
== install ==
backend=Shizuku / Shell (UID 2000) connected=True
== open demo stage ==
windows=3
  task=465 bounds=36,278,1009,2130 visible=True
  task=463 bounds=1035,278,1644,1191 visible=True
  task=464 bounds=1035,1217,1644,2130 visible=True
== live surface proof ==
  task=465 changed=19.87% -> LIVE
  task=463 changed=1.94% -> LIVE
  task=464 changed=6.97% -> LIVE
== focus switch keeps task ids ==
  before=465,463,464 after=463,465,464 focused=463
  promoted task 463 kept its id and gained focus
== result ==
SMOKE TEST PASSED
```

The three demo windows are: a 250 ms counter, a 1.7 s infinite animation, and a looping MP4 played
by `VideoView`. The live-surface proof compares two screenshots 1.5 s apart inside each window's
own rectangle; every window changed by ≥ 1.9 % of sampled pixels while **not being touched**.

## 5. Item-by-item verification

| Requirement | Result | Evidence |
|---|---|---|
| App launches | pass | release APK installed, `MainActivity` resumed |
| ≥ 3 independent real tasks | pass | tasks 465 / 463 / 464, `mode=freeform`, distinct task ids |
| Multiple windows visible simultaneously | pass | `visible=true` for all three, screenshots `docs/d6.png`, `docs/u12.png` |
| Live previews (no screenshots) | pass | pixel diff method above; windows are system task surfaces |
| Tap to switch focus | pass | tapped rail centre, `focused=463` |
| State preserved across switch | pass | task id set unchanged; demo activities declare `configChanges` so the counter continues |
| Window resize | pass | `resizeTask` applied `36,278,1009,2130` / `1035,278,1644,1191` exactly |
| Window move | pass | same call with different rects; verified after rotation |
| Touch input | pass | tapped ChatGPT/Notes/window controls through the staged window |
| IME | pass | keyboard opened inside a staged ChatGPT task (`docs/t4.png`) |
| Animation / no flicker | pass | focus halo animates; layout diff prevents repeated WM calls |
| Permission tiers switch | pass (Shizuku, Root service, ADB) / user-gated (root from app) | see capability matrix |
| Service failure recovery | pass | killed `:stage-engine`; app reconnected (new pid), 3 windows restored |
| Long-run stability | partially verified | repeated open/close/rotate/service-kill cycles without leaks observed; no 1 h soak yet |
| Rotation | pass | portrait → landscape → portrait, bounds restored exactly |
| Persistence | pass | window list reloaded after `force-stop`; dead tasks shown as “点击恢复” |

## 6. Things that failed or are limited (honest list)

1. **KernelSU root from inside the app.** Executing `su` from `untrusted_app` is denied
   (`/system/bin/sh: su: inaccessible or not found`). The root transport is implemented and the
   *engine* is verified as UID 0 (started via `adb shell su -c … RootServiceMain listen`), but the
   in-app path needs the user to allow StageLab in KernelSU → 超级用户 first.
2. **Non-exported activity launch.** Shell lacks `START_ANY_ACTIVITY`, so only exported launcher
   activities can be started.
3. **Third-party resize recreation.** Apps that do not declare `configChanges` may recreate their
   activity when the window is resized. The Android task and process survive, but Compose/View
   state inside the activity may reset — this is standard platform behaviour, not a StageLab bug.
   The test targets are configured so the acceptance scenario keeps counting.
4. **No smooth WM bounds animation.** Window rectangles are applied in one step; the stage chrome
   animates, the system window snaps. Animating `SurfaceControl` would require owning the task
   leash (`TaskOrganizer`), which trades away native input — documented in `DECISIONS.md`.
5. **Landscape fits one preview.** The rest are parked behind the main window (still alive).
6. **ColorOS installer UI** appears once when installing a side-loaded APK; tap “继续安装”.

## 7. How to reproduce

```powershell
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
# start Shizuku (once per boot)
adb shell /data/app/*/moe.shizuku.privileged.api*/lib/arm64/libshizuku.so
./scripts/device-smoke-test.ps1
```

Manual checks: rotate the device, press **＋** to add an app, tap a rail window, press **全部关闭**,
kill `:stage-engine` (`adb shell su -c 'kill -9 $(pidof dev.stagelab.android:stage-engine)'`) and
watch the stage recover.
