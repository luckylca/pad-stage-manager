# StageLab device smoke test
#
# Runs the acceptance scenario on a connected device:
#   1. install + launch the app
#   2. open the three live demo tasks (counter / motion / video) through the privileged backend
#   3. prove the three windows are real, simultaneously visible, and continuously updating
#      (pixel diff between two screenshots - not screenshots as UI, but proof of live surfaces)
#   4. focus a side preview and prove the task id does not change (no restart)
#
# Usage:
#   pwsh -File scripts/device-smoke-test.ps1 -Apk app\build\outputs\apk\release\app-release.apk

param(
    [string]$Apk = "app\build\outputs\apk\release\app-release.apk",
    [string]$Package = "dev.stagelab.android",
    [string]$Adb = "D:\Android\sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.Drawing

function Adb-Cmd([string]$arguments) {
    $out = & $Adb shell $arguments 2>&1
    return ($out | Out-String).Trim()
}

function Broadcast([string]$name) {
    & $Adb shell "am broadcast -n $Package/.core.StageCommandReceiver -a $Package.CMD --es cmd $name" | Out-Null
}

function Stage-State {
    & $Adb shell "am broadcast -n $Package/.core.StageCommandReceiver -a $Package.CMD --es cmd state" | Out-Null
    Start-Sleep -Milliseconds 900
    $line = (& $Adb logcat -d -s StageCmd:* | Select-String -Pattern 'state=' | Select-Object -Last 1).Line
    if (-not $line) { return $null }
    $json = $line.Substring($line.IndexOf('state=') + 6)
    return $json | ConvertFrom-Json
}

function Grab([string]$name) {
    $remote = "/sdcard/$name.png"
    $local = Join-Path $PSScriptRoot "..\docs\$name.png"
    & $Adb shell "screencap -p $remote" | Out-Null
    & $Adb pull $remote $local 2>&1 | Out-Null
    return (Resolve-Path $local).Path
}

function Region-Diff($a, $b, [int]$x1, [int]$y1, [int]$x2, [int]$y2) {
    $changed = 0; $total = 0
    for ($y = $y1; $y -lt $y2; $y += 8) {
        for ($x = $x1; $x -lt $x2; $x += 8) {
            $p1 = $a.GetPixel($x, $y); $p2 = $b.GetPixel($x, $y)
            $d = [math]::Abs($p1.R - $p2.R) + [math]::Abs($p1.G - $p2.G) + [math]::Abs($p1.B - $p2.B)
            if ($d -gt 12) { $changed++ }
            $total++
        }
    }
    if ($total -eq 0) { return 0 }
    return [math]::Round(100.0 * $changed / $total, 2)
}

Write-Host "== device ==" -ForegroundColor Cyan
& $Adb devices -l
$fingerprint = Adb-Cmd "getprop ro.build.fingerprint"
$sdk = Adb-Cmd "getprop ro.build.version.sdk"
$size = Adb-Cmd "wm size"
Write-Host "fingerprint=$fingerprint"
Write-Host "sdk=$sdk $size"

Write-Host "== install ==" -ForegroundColor Cyan
& $Adb install -r $Apk
& $Adb shell "am force-stop $Package"
& $Adb shell "am start -n $Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 6

$state = Stage-State
if (-not $state) { throw "The app did not report state; check logcat -s StageCmd" }
Write-Host ("backend=" + $state.backend + " connected=" + $state.connected)
if (-not $state.connected) {
    Write-Host "The privileged backend is not connected. Grant Shizuku/Root first." -ForegroundColor Yellow
}

Write-Host "== open demo stage ==" -ForegroundColor Cyan
Broadcast "closeAll"
Start-Sleep -Seconds 2
Broadcast "demo"
Start-Sleep -Seconds 9

$state = Stage-State
$windows = @($state.windows)
Write-Host ("windows=" + $windows.Count)
$windows | ForEach-Object { Write-Host ("  task=" + $_.taskId + " bounds=" + $_.bounds.l + "," + $_.bounds.t + "," + $_.bounds.r + "," + $_.bounds.b + " visible=" + $_.visible) }

if ($windows.Count -lt 3) { throw "Expected three staged windows, got $($windows.Count)" }

Write-Host "== live surface proof ==" -ForegroundColor Cyan
$shotA = Grab "smoke_a"
Start-Sleep -Milliseconds 1500
$shotB = Grab "smoke_b"
$bmpA = [System.Drawing.Bitmap]::FromFile($shotA)
$bmpB = [System.Drawing.Bitmap]::FromFile($shotB)

$ok = $true
foreach ($window in $windows) {
    $b = $window.bounds
    $pct = Region-Diff $bmpA $bmpB $b.l $b.t $b.r $b.b
    $verdict = if ($pct -ge 0.5) { "LIVE" } else { "STATIC" }
    if ($verdict -eq "STATIC") { $ok = $false }
    Write-Host ("  task=" + $window.taskId + " changed=" + $pct + "% -> " + $verdict)
}
$bmpA.Dispose(); $bmpB.Dispose()

Write-Host "== focus switch keeps task ids ==" -ForegroundColor Cyan
$before = @($state.windows | ForEach-Object { $_.taskId })
$preview = $state.windows | Where-Object { -not $_.focused } | Select-Object -First 1
if ($preview) {
    $cx = [int](($preview.bounds.l + $preview.bounds.r) / 2)
    $cy = [int](($preview.bounds.t + $preview.bounds.b) / 2)
    & $Adb shell "input tap $cx $cy" | Out-Null
    Start-Sleep -Seconds 3
    $after = Stage-State
    $afterIds = @($after.windows | ForEach-Object { $_.taskId })
    Write-Host ("  before=" + ($before -join ',') + " after=" + ($afterIds -join ',') + " focused=" + $after.focusedTaskId)
    # The stage reorders its own list when a window is promoted; the requirement is that the
    # underlying Android task ids survive the switch (no restart).
    $beforeSet = ($before | Sort-Object) -join ','
    $afterSet = ($afterIds | Sort-Object) -join ','
    if ($beforeSet -ne $afterSet) {
        $ok = $false
        Write-Host "  task id set changed!" -ForegroundColor Red
    } elseif ($after.focusedTaskId -ne $preview.taskId) {
        $ok = $false
        Write-Host "  promoted window did not gain focus" -ForegroundColor Red
    } else {
        Write-Host ("  promoted task " + $preview.taskId + " kept its id and gained focus")
    }
} else {
    Write-Host "  no preview window to promote" -ForegroundColor Yellow
}

Write-Host "== result ==" -ForegroundColor Cyan
if ($ok) { Write-Host "SMOKE TEST PASSED" -ForegroundColor Green } else { Write-Host "SMOKE TEST FAILED" -ForegroundColor Red }
