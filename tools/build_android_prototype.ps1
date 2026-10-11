# Manual, user-authorized prototype packaging only. No unit/lint/regression/device/benchmark task.
param([switch]$RecompileCamera)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$prototypeRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.cache/android-prototype'))
$pathsFile = Join-Path $prototypeRoot 'paths.json'
if (-not (Test-Path -LiteralPath $pathsFile)) { throw 'Prototype tool preparation is not complete' }
$paths = Get-Content -Raw -LiteralPath $pathsFile | ConvertFrom-Json
foreach ($name in @('javaHome','gradle','sdk','javaUserHome','androidUserHome','gradleUserHome','avdHome','temporary')) {
    $path = [System.IO.Path]::GetFullPath([string]$paths.$name)
    if (-not $path.StartsWith($prototypeRoot + '\', [System.StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetPathRoot($path) -ne 'D:\') { throw "Tool path outside D: prototype cache: $name" }
}
$env:JAVA_HOME = $paths.javaHome
$env:ANDROID_HOME = $paths.sdk
$env:ANDROID_SDK_ROOT = $paths.sdk
$env:ANDROID_USER_HOME = $paths.androidUserHome
$env:ANDROID_SDK_HOME = $null
$env:ANDROID_AVD_HOME = $paths.avdHome
$env:GRADLE_USER_HOME = $paths.gradleUserHome
$env:TEMP = $paths.temporary
$env:TMP = $paths.temporary
$env:JAVA_TOOL_OPTIONS = '-Duser.home="' + $paths.javaUserHome + '" -Djava.io.tmpdir="' + $paths.temporary + '"'
$gradle = Join-Path $paths.gradle 'bin/gradle.bat'
foreach ($required in @($gradle, (Join-Path $paths.javaHome 'bin/javac.exe'),
    (Join-Path $paths.sdk 'platforms/android-36/android.jar'), (Join-Path $paths.sdk 'ndk/28.2.13676358/source.properties'),
    (Join-Path $paths.sdk 'cmake/3.22.1/source.properties'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required build tool missing: $required" }
}
$distribution = Join-Path $workspaceRoot 'dist'
New-Item -ItemType Directory -Path $distribution -Force | Out-Null
$log = Join-Path $distribution 'prototype-build.log'
# New debug signing material is generated under the isolated D: Java/Android home by AGP.
# Do not read or import signing material from any previous app, machine home or reference APK.
$gradleArguments = @('-p', $workspaceRoot, '--no-daemon', '--console=plain', '--max-workers=2', '--stacktrace',
    "-Porg.gradle.java.installations.paths=$($paths.javaHome)",
    '-Porg.gradle.java.installations.auto-detect=false', '-Porg.gradle.java.installations.auto-download=false')
if ($RecompileCamera) { $gradleArguments += @(':core:camera:compileDebugKotlin', '--rerun') }
$gradleArguments += ':app:assembleDebug'
& $gradle @gradleArguments *>&1 | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) { throw "Prototype compilation failed (exit $LASTEXITCODE); see dist/prototype-build.log" }
$apk = Join-Path $workspaceRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Gradle finished without the expected APK' }
$output = Join-Path $distribution ('lumix-film-mirror-prototype-0.1.0-' + (Get-Date -Format yyyyMMdd-HHmmss) + '-debug.apk')
Copy-Item -LiteralPath $apk -Destination $output
Get-FileHash -LiteralPath $output -Algorithm SHA256 | Select-Object Path,Hash |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $distribution 'prototype-apk-sha256.json') -Encoding utf8
Write-Output "Prototype APK saved to $output; no tests or device validation executed"
