$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\sdk' }
$platformCandidates = @('android-37.0', 'android-37.2')
$androidJar = $platformCandidates | ForEach-Object { Join-Path $sdkRoot "platforms\$_\android.jar" } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $androidJar) { throw "No Android 37 android.jar found under $sdkRoot\platforms" }

$javacCommand = Get-Command javac -ErrorAction SilentlyContinue
$jarCommand = Get-Command jar -ErrorAction SilentlyContinue
if (-not $javacCommand -or -not $jarCommand) {
    $jdkBin = 'C:\Program Files\Java\jdk-23\bin'
    $javacPath = Join-Path $jdkBin 'javac.exe'
    $jarPath = Join-Path $jdkBin 'jar.exe'
    if (-not (Test-Path $javacPath) -or -not (Test-Path $jarPath)) {
        throw 'JDK 17+ javac and jar must be available on PATH or under C:\Program Files\Java\jdk-23\bin.'
    }
} else {
    $javacPath = $javacCommand.Source
    $jarPath = $jarCommand.Source
}

$source = Join-Path $projectRoot 'app\src\hiddenApiStubs\java\android\window\TaskOrganizer.java'
$outputDir = Join-Path $projectRoot 'app\build\hidden-api-stubs\classes'
$jarDir = Join-Path $projectRoot 'app\libs'
$jarFile = Join-Path $jarDir 'android-hidden-api-stubs.jar'
if (-not (Test-Path $source)) { throw "Hidden API stub source not found: $source" }

if (Test-Path $outputDir) { Remove-Item $outputDir -Recurse -Force }
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
New-Item -ItemType Directory -Path $jarDir -Force | Out-Null

& $javacPath --release 17 -classpath $androidJar -d $outputDir $source
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
if (Test-Path $jarFile) { Remove-Item $jarFile -Force }
& $jarPath --create --file $jarFile -C $outputDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Output "Created compile-only stub: $jarFile"
Write-Output "Source: $source"
Write-Output "Android API reference: $androidJar"
Get-Item $jarFile | Select-Object FullName, Length, LastWriteTime
