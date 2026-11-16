<#
.SYNOPSIS
    Builds the NDS Station Android app (APK) with Gradle.

.PARAMETER Debug
    Build the debug variant instead of release.

.NOTES
    Needs the Android SDK (Android Studio installs it) and a JDK 17-21. Gradle 8.14 cannot
    run on newer JDKs such as Android Studio's bundled JDK 25, so JAVA_HOME is pointed at
    an installed JDK 21 (winget install EclipseAdoptium.Temurin.21.JDK) when found.
    NDK and CMake are downloaded by Gradle on the first build.
#>
param([switch]$Debug)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not (Test-Path (Join-Path $root 'external\melonDS\CMakeLists.txt'))) {
    Write-Host 'Fetching melonDS sources...'
    git -C $root submodule update --init --recursive
}

$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$localProps = Join-Path $root 'local.properties'
if (-not (Test-Path $localProps)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Set-Content -Encoding ascii $localProps
}

$variant = if ($Debug) { 'Debug' } else { 'Release' }
& (Join-Path $root 'gradlew.bat') -p $root "assemble$variant" --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }

$apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\$($variant.ToLower())") -Filter *.apk | Select-Object -First 1
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$version = (Select-String -Path (Join-Path $root 'app\build.gradle.kts') -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
$target = Join-Path $dist "NDSStation-$version$(if ($Debug) { '-debug' }).apk"
Copy-Item $apk.FullName $target -Force
Write-Host "APK: $target"
