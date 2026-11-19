# Starts the StageLab privileged service from a PC over ADB (no Shizuku, no root install).
#
# This is the "bring-up" transport: the service runs as Shell UID 2000 in an app_process and listens
# on 127.0.0.1:39721. StageLab connects to that loopback port automatically when it cannot use
# Shizuku. The service dies when the device reboots or when the process is killed.
#
# Usage:
#   pwsh -File scripts/adb-service.ps1            # start (or restart)
#   pwsh -File scripts/adb-service.ps1 -Stop      # stop

param(
    [switch]$Stop,
    [string]$Adb = "D:\Android\sdk\platform-tools\adb.exe",
    [string]$Package = "dev.stagelab.android",
    [int]$Port = 39721
)

function Adb-Cmd([string]$args) { return ((& $Adb shell $args 2>&1) | Out-String).Trim() }

if ($Stop) {
    & $Adb shell "su -c 'pkill -f stagelab-root'" 2>&1 | Out-Null
    & $Adb shell "pkill -f stagelab-root" 2>&1 | Out-Null
    Write-Host "stopped"
    exit 0
}

$apkPath = Adb-Cmd "pm path $Package"
if (-not $apkPath) { throw "$Package is not installed" }
$apk = $apkPath -replace "package:", "" -replace "`r", ""

# Prefer root (uid 0); fall back to a plain shell process (uid 2000) when su is unavailable.
$hasRoot = (Adb-Cmd "su -c id") -match "uid=0"
$command = "CLASSPATH=$apk nohup app_process /system/bin --nice-name=stagelab-root " +
    "dev.stagelab.android.core.privileged.RootServiceMain listen $Port " +
    "> /data/local/tmp/stagelab-root.log 2>&1 &"

if ($hasRoot) {
    & $Adb shell "su -c '$command'" | Out-Null
    Write-Host "started as root (uid 0)"
} else {
    & $Adb shell "$command" | Out-Null
    Write-Host "started as shell (uid 2000)"
}

Start-Sleep -Seconds 2
Write-Host (Adb-Cmd "cat /data/local/tmp/stagelab-root.log")
Write-Host (Adb-Cmd "ps -A | grep stagelab-root")
Write-Host "StageLab will connect to 127.0.0.1:$Port"
