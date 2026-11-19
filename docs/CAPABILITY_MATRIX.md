# Permission capability matrix

Measured on OPPO Pad mini / ColorOS 17 / Android 17 (SDK 37). “Shell” means UID 2000 through
Shizuku, “Root” means the same engine running as UID 0.

| Capability | Level 1 — normal app | Level 2 — Shizuku / Shell | Level 3 — KernelSU Root |
|---|---|---|---|
| Launch an exported app | yes (normal `startActivity`) | yes | yes |
| Launch a non-exported activity | no | no (`START_ANY_ACTIVITY` not held) | yes as UID 0 |
| Enumerate system tasks | no | yes — `getTasks` returns 13 tasks | yes |
| Create FREEFORM real task window | no — platform restriction | yes — `startActivityAsUser` + `setLaunchWindowingMode(FREEFORM)` | yes |
| Move / resize an external task | no | yes — `resizeTask`, exact bounds | yes |
| Change task focus | no | yes — `setFocusedTask` | yes |
| Close an external task | no | yes — `removeTask` | yes |
| Hide / park a task | no | partial — `WindowContainerTransaction` accepted; parking currently implemented by covering with the main window | same |
| Live task surface (no screenshot) | no | yes — system composited task surface | yes |
| Real-time previews of background tasks | no | yes — the rail windows are the tasks themselves | yes |
| Native input in hosted tasks | n/a | yes | yes |
| IME in hosted tasks | n/a | yes | yes |
| Register `TaskOrganizer` | no | yes (`registerOrganizer` returns the task list) | yes |
| `createRootTask` | no | yes — 3-argument signature on Android 17 | yes |
| Read task token / `WindowContainerToken` | no | yes (reflection on `TaskInfo.token`) | yes |
| Start the privileged process | no | Shizuku server must run (user) | KernelSU must grant `su` (user) |
| Actually usable without user action | yes (launcher only) | after one-time Shizuku grant | after one-time KernelSU grant |

## Why Level 1 cannot host tasks

An ordinary application cannot obtain another app's task surface or move its window:

* `ActivityOptions.setLaunchBounds` is public, but the *windowing mode* and the task token are not,
  and `resizeTask` / `setFocusedTask` require `MANAGE_ACTIVITY_TASKS`;
* `TaskOrganizer` is `@SystemApi` and requires the same permission;
* `SurfaceControl` reparenting of a task leash requires the leash, which only a registered
  organizer receives.

StageLab reports this honestly in the UI (`普通权限（仅启动应用）`) instead of faking windows with
screenshots.

## Transport notes

| Transport | Process | UID | When it is used |
|---|---|---|---|
| Shizuku UserService | `dev.stagelab.android:stage-engine` | 2000 | default when Shizuku is running and granted |
| KernelSU root service | `stagelab-root` (`app_process`) | 0 | fallback, requires KernelSU `su` grant for the app |
| ADB loopback service | `stagelab-root` started from a PC | 2000 | development / bring-up |
| — (public only) | app | 10xxx | no privileged backend available |
