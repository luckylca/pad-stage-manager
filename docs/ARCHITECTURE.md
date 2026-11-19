# StageLab architecture

## 1. Product model

StageLab is a **window compositor for real Android tasks**. The stage host does not draw other
apps' pixels; it tells the system where each task window must be, and the system keeps compositing
the live application surfaces on top of the stage host.

```
┌───────────────────────────────────────────────┐
│ WindowManager / SurfaceFlinger (system)       │
│  ┌───────────────┐  ┌────────┐  ┌────────┐    │
│  │ task 120 (A)  │  │ task121│  │ task122│    │   ← real app windows (FREEFORM)
│  │ main frame    │  │ rail 1 │  │ rail 2 │    │
│  └───────────────┘  └────────┘  └────────┘    │
│  ┌───────────────────────────────────────────┐│
│  │ StageLab MainActivity (fullscreen, NOT    ││   ← our chrome: desk, halos, dock, top bar
│  │ FOCUSABLE, never raised above the tasks)  ││
│  └───────────────────────────────────────────┘│
└───────────────────────────────────────────────┘
```

The stage host window is `FLAG_NOT_FOCUSABLE` while tasks are on screen. That single flag is what
makes the model work: tapping the dock cannot raise the stage above the application windows, and
the input focus, IME and key events stay with the staged application.

When the stage is empty the flag is cleared so accessibility, Back and the IME behave normally.

## 2. Process and transport layout

| Component | Process | UID | Responsibility |
|---|---|---|---|
| `MainActivity`, `StageRuntime`, Compose UI | app | 10xxx | state, geometry, UI, persistence |
| `StageCommandReceiver` | app | 10xxx | automation entry point for the device tests |
| `StageTaskUserService` | `:stage-engine` (Shizuku UserService) | 2000 | AIDL facade over `StageEngine` |
| `RootServiceMain` | `stagelab-root` (`app_process`) | 0 / 2000 | loopback JSON service over `StageEngine` |
| `StageEngine`, `TaskMutations` | privileged process | 2000 / 0 | the actual task/window control |

Transports (`core/ipc`):

* **`ShizukuChannel`** — `Shizuku.bindUserService` against `StageTaskUserService`, AIDL
  `request(String json)`. Preferred tier because Shell holds `MANAGE_ACTIVITY_TASKS`.
* **`RootChannel`** — the app opens an ephemeral loopback `ServerSocket`, then starts
  `su -c 'CLASSPATH=<apk> app_process … RootServiceMain connect <port> <token>'`. The privileged
  process connects back, authenticates with a one-shot token, and then speaks newline-delimited
  JSON. No port is exposed to the network.
* **`AdbChannel`** — the developer starts `RootServiceMain listen 39721` over ADB; the app connects
  to loopback. Used for bring-up without installing anything.

All transports carry the same JSON protocol, so `StageEngine` has exactly one implementation of
task control. The app is the source of truth for *which* windows exist; the service only performs
mutations and reports the system task list. That is why a service restart does not destroy the
user's stage.

## 3. Why FREEFORM tasks and not reparented surfaces

The AOSP `TaskOrganizer` / `SurfaceControl` embedding route was evaluated first (see
`docs/SHIZUKU_TASK_PROBE.md` and `docs/DECISIONS.md`). Registering a `TaskOrganizer` from Shell
works, and it hands you each task's leash, but:

* the leash lives in WindowManager's hierarchy; reparenting it into an app `SurfaceView` gives you
  the picture but **not the input** — touch hit-testing still uses the task's WM bounds;
* the organizer becomes responsible for visibility and geometry of every task it adopts, which
  makes a naive registration effectively "steal" the windows from the system shell organizer;
* IME, focus and Back routing would all have to be re-implemented on top of injected events.

Instead StageLab uses what WindowManager already does best: real tasks with real windows.
`IActivityTaskManager` gives us the four operations the product needs:

| Operation | Binder call | Permission |
|---|---|---|
| enumerate tasks | `getTasks(int,boolean,boolean,int)` | `REAL_GET_TASKS` |
| start a task in FREEFORM with bounds | `startActivityAsUser(..., ActivityOptions)` | normal launch + `MANAGE_ACTIVITY_TASKS` for bounds/windowing mode |
| move / resize | `resizeTask(int,Rect,int)` | `MANAGE_ACTIVITY_TASKS` |
| focus | `setFocusedTask(int)` | `MANAGE_ACTIVITY_TASKS` |
| close | `removeTask(int)` | `REMOVE_TASKS` |
| hide / windowing mode | `IWindowOrganizerController.applyTransaction(WindowContainerTransaction)` | `MANAGE_ACTIVITY_TASKS` |

Because the windows are ordinary system windows, **input, IME, Back, accessibility, media and
WebView keep working** without any translation layer.

## 4. Hidden API strategy

The Android SDK does not expose `ActivityTaskManager`, `RunningTaskInfo.token`,
`WindowContainerTransaction`, `TaskOrganizer`, etc. StageLab does **not** link against version
specific stubs for those:

* `HiddenApi` resolves classes, methods and fields **by name at runtime** and caches them;
* the only compile-time stub is `android.window.TaskOrganizer` (needed to subclass it for the
  organizer probe). Its signatures are generated from AOSP and verified on the device — see
  `docs/DEVICE_NOTES.md` for the exact Android 17 signature set, including the changed
  `createRootTask(int,int,IBinder)` and `onBackOnTaskRoot(...)`;
* every failure surfaces as a named `HiddenApiException` in the diagnostics screen, so an OEM
  change shows up as "Method not found: X" instead of a crash.

## 5. Stage geometry

`StageLayoutEngine` computes pure rectangles in pixels from dp:

* portrait: 14 dp margin, 84 dp dock, 232 dp rail, 66 dp top chrome reserve;
* landscape: 18 dp margin, 66 dp dock, 244 dp rail;
* the rail is wider than the system's 220 dp minimum freeform width, so `resizeTask` is not clamped;
* rail capacity is derived from the available height and the minimum slot height (`226 dp`);
  windows beyond the capacity are **parked** at the main rectangle (alive, covered, one tap away).

`StageRuntime.applyLayout` diffs the target rectangle against the last applied one, so the polling
loop never spams WindowManager.

## 6. State loop

```
                ┌───────────┐  state (450 ms)   ┌──────────────┐
 privileged ───▶│  getTasks │──────────────────▶│ StageRuntime │
   service      └───────────┘                    │  reconcile   │
                                                 │  promote     │
   ▲   bounds / focus / close / open             │  park        │
   └─────────────────────────────────────────────┤  applyLayout │
                                                 └──────┬───────┘
                                                        │ StateFlow
                                                        ▼
                                                  Compose stage
```

Reconciliation rules:

* a stored window is matched by task id first;
* if the id is gone, a FREEFORM task whose top activity matches the stored component is adopted
  (this survives a service restart, and cannot hijack the stage host);
* otherwise the window is marked `alive=false` and the dock offers “点击恢复”.

## 7. Failure handling

| Failure | Behaviour |
|---|---|
| UserService / root service dies | transport dropped, reconnect with 0.5 s → 5 s backoff, windows and geometry restored from the persisted list |
| stage host process dies | window list persisted in `SharedPreferences`; tasks die with the process and are shown as closed |
| app window closed by the user | detected on the next poll, chip shows “已关闭 · 点击恢复” |
| task hidden by a fullscreen activity (picker) | `ensureVisible()` re-focuses the windows in reverse order, main last |
| WM clamps a window | logged (`WM adjusted … requested … got …`), layout keeps the requested slot |

## 8. Test hooks

`StageCommandReceiver` accepts `dev.stagelab.android.CMD` broadcasts:

```powershell
adb shell am broadcast -n dev.stagelab.android/.core.StageCommandReceiver `
  -a dev.stagelab.android.CMD --es cmd demo
adb shell am broadcast ... --es cmd closeAll
adb shell am broadcast ... --es cmd reflow
adb shell am broadcast ... --es cmd state      # prints the stage JSON into logcat -s StageCmd
adb shell am broadcast ... --es cmd open --es package com.coloros.note --es title 便签
```

`scripts/device-smoke-test.ps1` drives the whole acceptance scenario with these commands and
verifies live surfaces by comparing two screenshots pixel by pixel.
