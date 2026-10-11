# Manual preparation for the user-authorized prototype. Does not run tests or build an APK.
# All downloaded tools, Java home data, SDK caches and temporary files stay in this project's D: cache.
$ErrorActionPreference = 'Stop'
$prototypeRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.cache/android-prototype'))
if ([System.IO.Path]::GetPathRoot($prototypeRoot) -ne 'D:\') { throw 'Prototype tools must stay on D:' }
New-Item -ItemType Directory -Path $prototypeRoot -Force | Out-Null
$downloadRoot = Join-Path $prototypeRoot 'downloads'
$temporaryRoot = Join-Path $prototypeRoot 'tmp'
New-Item -ItemType Directory -Path $downloadRoot,$temporaryRoot -Force | Out-Null
$env:TEMP = $temporaryRoot
$env:TMP = $temporaryRoot

function Get-PrototypeArchive([string]$Url, [string]$Name, [string]$Hash, [string]$Algorithm) {
    if (-not $Url.StartsWith('https://')) { throw 'Tool downloads require HTTPS' }
    $path = [System.IO.Path]::GetFullPath((Join-Path $downloadRoot $Name))
    if (-not $path.StartsWith($downloadRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid archive path' }
    if ((Test-Path -LiteralPath $path) -and (Get-FileHash -LiteralPath $path -Algorithm $Algorithm).Hash -eq $Hash) {
        Write-Host "Using cached $Name"
    } else {
        Write-Host "Downloading $Name"
        & curl.exe --fail --location --continue-at - --connect-timeout 30 --speed-time 60 --speed-limit 1024 --retry 4 --retry-delay 3 --output $path $Url
        if ($LASTEXITCODE -ne 0) { throw "Tool download failed: $Name (exit $LASTEXITCODE)" }
        if ((Get-FileHash -LiteralPath $path -Algorithm $Algorithm).Hash -ne $Hash) { throw "Download checksum mismatch: $Name" }
    }
    return $path
}

$jdkAssets = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
$jdkPackage = $jdkAssets[0].binary.package
$gradleHashBytes = (Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256').Content
$gradleHash = if ($gradleHashBytes -is [byte[]]) { [System.Text.Encoding]::ASCII.GetString($gradleHashBytes).Trim() } else { ([string]$gradleHashBytes).Trim() }
if ($gradleHash -notmatch '^[0-9a-f]{64}$') { throw 'Invalid Gradle checksum response' }
$manifest = [ordered]@{ jdkRelease=$jdkAssets[0].release_name; jdkUrl=$jdkPackage.link; jdkSha256=$jdkPackage.checksum; jdkSize=$jdkPackage.size;
    gradleVersion='8.13'; gradleSha256=$gradleHash;
    cmdlineTools='19.0'; cmdlineToolsSha1='54a582f3bf73e04253602f2d1c80bd5868aac115' }
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $prototypeRoot 'tool-downloads.json') -Encoding utf8
# Assign only the last pipeline output (archive path); progress is emitted separately below.
$jdkResult = @(Get-PrototypeArchive $jdkPackage.link 'jdk17.zip' $jdkPackage.checksum 'SHA256')
$jdkResult | Select-Object -SkipLast 1 | ForEach-Object { Write-Output $_ }
$jdkArchive = $jdkResult[-1]
$gradleResult = @(Get-PrototypeArchive 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' 'gradle-8.13-bin.zip' $gradleHash 'SHA256')
$gradleResult | Select-Object -SkipLast 1 | ForEach-Object { Write-Output $_ }
$gradleArchive = $gradleResult[-1]
$cliResult = @(Get-PrototypeArchive 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' 'commandline-tools-19.zip' '54a582f3bf73e04253602f2d1c80bd5868aac115' 'SHA1')
$cliResult | Select-Object -SkipLast 1 | ForEach-Object { Write-Output $_ }
$cliArchive = $cliResult[-1]

$javaRoot = Join-Path $prototypeRoot 'java'
$javaDirectory = Join-Path $javaRoot $jdkAssets[0].release_name
if (-not (Test-Path -LiteralPath (Join-Path $javaDirectory 'bin/javac.exe'))) {
    Write-Output 'Extracting JDK 17'
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath $javaRoot -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $javaDirectory 'bin/javac.exe'))) { throw 'JDK layout is unexpected' }
$gradleDirectory = Join-Path $prototypeRoot 'gradle-8.13'
if (-not (Test-Path -LiteralPath (Join-Path $gradleDirectory 'bin/gradle.bat'))) {
    Write-Output 'Extracting Gradle 8.13'
    Expand-Archive -LiteralPath $gradleArchive -DestinationPath $prototypeRoot -Force
}
$sdkRoot = Join-Path $prototypeRoot 'sdk'
$cliDirectory = Join-Path $sdkRoot 'cmdline-tools/19.0'
if (-not (Test-Path -LiteralPath (Join-Path $cliDirectory 'bin/sdkmanager.bat'))) {
    Write-Output 'Extracting Android command-line tools'
    $bootstrapDirectory = Join-Path $prototypeRoot 'cli-bootstrap'
    Expand-Archive -LiteralPath $cliArchive -DestinationPath $bootstrapDirectory -Force
    $cliSource = [System.IO.Path]::GetFullPath((Join-Path $bootstrapDirectory 'cmdline-tools'))
    $cliTarget = [System.IO.Path]::GetFullPath($cliDirectory)
    # Verify both final absolute paths before moving this directory, using one native shell throughout.
    if (-not $cliSource.StartsWith($prototypeRoot + '\', [System.StringComparison]::OrdinalIgnoreCase) -or
        -not $cliTarget.StartsWith($prototypeRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid CLI installation paths' }
    if (Test-Path -LiteralPath $cliTarget) { throw 'Incomplete CLI target exists; preserve it for inspection' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $cliTarget) -Force | Out-Null
    Move-Item -LiteralPath $cliSource -Destination $cliTarget
}
$env:JAVA_HOME = $javaDirectory
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $prototypeRoot 'android-user'
$env:ANDROID_AVD_HOME = Join-Path $prototypeRoot 'avd'
$env:GRADLE_USER_HOME = Join-Path $prototypeRoot 'gradle-user'
$javaUserDirectory = Join-Path $prototypeRoot 'java-user'
$env:JAVA_TOOL_OPTIONS = '-Duser.home="' + $javaUserDirectory + '"'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME,$env:ANDROID_AVD_HOME,$env:GRADLE_USER_HOME,$javaUserDirectory -Force | Out-Null
$sdkManager = Join-Path $cliDirectory 'bin/sdkmanager.bat'
Write-Output 'Installing only the pinned SDK packages needed for the prototype; no emulator image'
1..30 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses
if ($LASTEXITCODE -ne 0) { throw "SDK license setup failed: $LASTEXITCODE" }
& $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw "SDK package installation failed: $LASTEXITCODE" }
[ordered]@{ javaHome=$javaDirectory; gradle=$gradleDirectory; sdk=$sdkRoot; javaUserHome=$javaUserDirectory;
    androidUserHome=$env:ANDROID_USER_HOME; gradleUserHome=$env:GRADLE_USER_HOME; avdHome=$env:ANDROID_AVD_HOME; temporary=$temporaryRoot } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $prototypeRoot 'paths.json') -Encoding utf8
Write-Output 'Prototype toolchain preparation complete; no compilation or tests executed'
