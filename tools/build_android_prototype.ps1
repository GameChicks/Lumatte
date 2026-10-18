# Manual build only. No tests, device launch, publishing, or automatic hooks.
param([switch]$CompileOnly, [string]$BuildHome = 'D:\AndroidBuild\Pineframe')
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localConfig = Join-Path $PSScriptRoot '.local/android-paths.json'
$paths = if (Test-Path -LiteralPath $localConfig) { Get-Content -LiteralPath $localConfig -Raw | ConvertFrom-Json } else { $null }
if ($paths) {
    $env:JAVA_HOME = $paths.javaHome
    $env:ANDROID_HOME = $paths.sdk
    $env:ANDROID_USER_HOME = $paths.androidUserHome
    $env:GRADLE_USER_HOME = $paths.gradleUserHome
    $BuildHome = $paths.javaUserHome
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to JDK 17. See BUILDING.md; no tools are installed automatically.'
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to your Android SDK. See BUILDING.md.' }
$BuildHome = [IO.Path]::GetFullPath($BuildHome)
if ([IO.Path]::GetPathRoot($BuildHome) -ne 'D:\') { throw 'Local Windows build caches must stay on D:' }
New-Item -ItemType Directory -Path $BuildHome -Force | Out-Null
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $BuildHome 'gradle' }
if (-not $env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $BuildHome 'android' }
$env:ANDROID_AVD_HOME = if ($paths) { $paths.avdHome } else { Join-Path $BuildHome 'avd' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_SDK_HOME = $null
$env:TEMP = Join-Path $BuildHome 'tmp'; $env:TMP = $env:TEMP
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Duser.home="' + $BuildHome + '" -Djava.io.tmpdir="' + $env:TEMP + '"'
$distribution = Join-Path $workspaceRoot 'dist'
New-Item -ItemType Directory -Path $distribution -Force | Out-Null
$log = Join-Path $distribution $(if ($CompileOnly) { 'source-compile.log' } else { 'prototype-build.log' })
$arguments = @('-p',$workspaceRoot,'--no-daemon','--console=plain','--max-workers=2',
    "-Porg.gradle.java.installations.paths=$($env:JAVA_HOME)",
    '-Porg.gradle.java.installations.auto-detect=false','-Porg.gradle.java.installations.auto-download=false')
$arguments += if ($CompileOnly) { @(':core:media:externalNativeBuildDebug', ':app:compileDebugKotlin') } else { ':app:assembleDebug' }
$gradle = if ($paths -and $paths.gradle) { Join-Path $paths.gradle 'bin/gradle.bat' } else {
    $installedGradle = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if (-not $installedGradle) { throw 'Install Gradle 8.13 and add its bin directory to PATH. See BUILDING.md.' }
    $installedGradle.Source
}
& $gradle @arguments *>&1 | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) { throw "Build failed; see $log" }
if ($CompileOnly) { Write-Output 'Source compilation completed; no tests or APK packaging.'; return }
$metadata = Get-Content -LiteralPath (Join-Path $workspaceRoot 'app/build/outputs/apk/debug/output-metadata.json') -Raw | ConvertFrom-Json
$version = $metadata.elements[0].versionName
if ($version -notmatch '^[0-9A-Za-z._-]+$') { throw 'Invalid version in APK metadata' }
$output = Join-Path $distribution ('lumix-film-mirror-prototype-' + $version + '-' + (Get-Date -Format yyyyMMdd-HHmmss) + '-debug.apk')
Copy-Item -LiteralPath (Join-Path $workspaceRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $output
Get-FileHash -LiteralPath $output -Algorithm SHA256 | Select-Object Path,Hash | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $distribution 'prototype-apk-sha256.json') -Encoding utf8
Write-Output "APK saved to $output; no tests, device validation or publishing executed."
