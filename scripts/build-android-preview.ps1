<#
.SYNOPSIS
Builds and verifies the ARM64 Android preview without installing or launching it.
.EXAMPLE
.\scripts\build-android-preview.ps1 -ConfigureOnly
.EXAMPLE
.\scripts\build-android-preview.ps1 -Package
.NOTES
Uses $env:TEMP\FloatMusic-v13-android\work and preserves its incremental build.
Source files are copied only when size or last-write time differs; no files or
signing keys are deleted. Run outside the Windows compiler sandbox.
#>
[CmdletBinding()]
param(
    [switch]$Package,
    [switch]$ConfigureOnly,
    [string]$ArtifactRoot,
    [string]$StageRoot,
    [ValidateRange(1, 32)][int]$Parallel = 4,
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string]$ExpectedSigningSha256 = 'df368706c7d443cfc9a03955ea5ffdff707378e958b5e150fb3e06d8b3f1ec7c'
)

$ErrorActionPreference = 'Stop'
if ($ConfigureOnly -and $Package) { throw 'ConfigureOnly cannot be combined with Package.' }
$sourceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$taskTempRoot = [IO.Path]::GetFullPath((Join-Path $env:TEMP 'FloatMusic-v13-android'))
if (-not $StageRoot) { $StageRoot = Join-Path $taskTempRoot 'work' }
$StageRoot = [IO.Path]::GetFullPath($StageRoot)
$taskTempPrefix = $taskTempRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $StageRoot.StartsWith($taskTempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "StageRoot must be a child of the task's temporary directory: $taskTempRoot"
}
if ($StageRoot -match '[^\x00-\x7F]') { throw "Compilation requires an ASCII path: $StageRoot" }

$stageSource = Join-Path $StageRoot 'source'
$buildDir = Join-Path $StageRoot 'build'
if (-not $ArtifactRoot) { $ArtifactRoot = Join-Path $sourceRoot 'artifacts\v1.5-android-preview-2026-10-25' }
$artifactRoot = [IO.Path]::GetFullPath($ArtifactRoot)
$runId = 'run-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$logDir = Join-Path (Join-Path $artifactRoot 'logs') $runId
$qtRoot = 'C:\Qt\6.11.2\android_arm64_v8a'
$qtHostRoot = 'C:\Qt\6.11.2\mingw_64'
$sdkRoot = 'C:\Android\Sdk'
$ndkRoot = Join-Path $sdkRoot 'ndk\27.2.12479018'
$opensslRoot = Join-Path $sdkRoot 'android_openssl'
$jdkRoot = 'C:\DevTools\jdk-21'
$cmake = 'C:\Qt\Tools\CMake_64\bin\cmake.exe'
$ninja = 'C:\Qt\Tools\Ninja\ninja.exe'
$aapt = Join-Path $sdkRoot 'build-tools\36.0.0\aapt2.exe'
$apksigner = Join-Path $sdkRoot 'build-tools\36.0.0\apksigner.bat'
$debugKeystore = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\debug.keystore'

foreach ($requiredFile in @($cmake, $ninja, $aapt, $apksigner,
    (Join-Path $jdkRoot 'bin\java.exe'), (Join-Path $qtRoot 'lib\cmake\Qt6\Qt6Config.cmake'),
    (Join-Path $qtHostRoot 'bin\qmlimportscanner.exe'),
    (Join-Path $ndkRoot 'build\cmake\android.toolchain.cmake'),
    (Join-Path $sdkRoot 'platforms\android-36\android.jar'),
    (Join-Path $opensslRoot 'android_openssl.cmake'),
    (Join-Path $opensslRoot 'no-asm\ssl_3\arm64-v8a\libcrypto_3.so'),
    (Join-Path $opensslRoot 'no-asm\ssl_3\arm64-v8a\libssl_3.so'))) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw "Required tool or library is missing: $requiredFile" }
}
if ($Package -and -not (Test-Path -LiteralPath $debugKeystore -PathType Leaf)) {
    throw 'Existing Android debug keystore is missing; refusing to create a replacement signing key.'
}
New-Item -ItemType Directory -Path $StageRoot, $logDir -Force | Out-Null
$stageLock = $null
$locationPushed = $false
$environmentNames = @('PATH', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_NDK_ROOT', 'GRADLE_OPTS', 'QT_PLUGIN_PATH', 'QML_IMPORT_PATH', 'QML2_IMPORT_PATH')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$runInfo = [ordered]@{
    runId = $runId; startedAt = Get-Date -Format o; status = 'running'
    sourceRoot = $sourceRoot; stageRoot = $StageRoot; buildDir = $buildDir; logDir = $logDir
    package = [bool]$Package; configureOnly = [bool]$ConfigureOnly
    abi = 'arm64-v8a'; minSdk = 28; targetSdk = 36; qt = '6.11.2'; ndk = '27.2.12479018'
}

function Invoke-LoggedCommand {
    param([string]$Name, [string]$Executable, [string[]]$Arguments)
    $commandFile = Join-Path $logDir "$Name-command.json"
    $commandInfo = [ordered]@{ executable = $Executable; arguments = $Arguments; workingDirectory = (Get-Location).Path; startedAt = Get-Date -Format o }
    $commandInfo | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $commandFile -Encoding utf8
    Write-Host "$Name -> $(Join-Path $logDir "$Name.log")"
    & $Executable @Arguments 2>&1 | Tee-Object -FilePath (Join-Path $logDir "$Name.log")
    $commandExitCode = $LASTEXITCODE
    $commandInfo['finishedAt'] = Get-Date -Format o
    $commandInfo['exitCode'] = $commandExitCode
    $commandInfo | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $commandFile -Encoding utf8
    if ($commandExitCode -ne 0) { throw "$Name failed with exit code $commandExitCode. See $logDir" }
}

try {
    $stageLock = [IO.File]::Open((Join-Path $StageRoot '.build.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $markerPath = Join-Path $StageRoot '.floatmusic-android-preview.json'
    if (Test-Path -LiteralPath $markerPath) {
        $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
        if ($marker.sourceRoot -ne $sourceRoot) { throw 'The staging directory belongs to a different source checkout.' }
    } elseif (Test-Path -LiteralPath $stageSource) {
        throw 'The staging source directory already exists without this script owner marker.'
    } else {
        @{ sourceRoot = $sourceRoot; createdAt = Get-Date -Format o } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
    }
    New-Item -ItemType Directory -Path $stageSource, $buildDir -Force | Out-Null
    $sourceFiles = @()
    foreach ($entry in @('CMakeLists.txt', 'src', 'qml', 'android', 'assets', 'resources')) {
        $entryPath = Join-Path $sourceRoot $entry
        if (-not (Test-Path -LiteralPath $entryPath)) { throw "Required source entry is missing: $entryPath" }
        if (Test-Path -LiteralPath $entryPath -PathType Leaf) { $sourceFiles += Get-Item -LiteralPath $entryPath }
        else { $sourceFiles += Get-ChildItem -LiteralPath $entryPath -File -Recurse }
    }
    $sourceSnapshot = @()
    $sourcePathSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $copiedFiles = 0
    foreach ($file in ($sourceFiles | Sort-Object FullName)) {
        if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Source links are not supported: $($file.FullName)" }
        if ($file.Extension -in @('.jks', '.keystore')) { throw 'Signing keys must not be staged with product sources.' }
        $relativePath = $file.FullName.Substring($sourceRoot.TrimEnd('\', '/').Length + 1)
        $null = $sourcePathSet.Add($relativePath)
        $destination = Join-Path $stageSource $relativePath
        $existing = Get-Item -LiteralPath $destination -ErrorAction SilentlyContinue
        if (-not $existing -or $existing.Length -ne $file.Length -or $existing.LastWriteTimeUtc -ne $file.LastWriteTimeUtc) {
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
            $copiedFiles++
        }
        $sourceHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $stageHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($sourceHash -ne $stageHash) { throw "Staged content differs despite matching metadata: $relativePath. Use a new StageRoot." }
        $sourceSnapshot += [ordered]@{ path = $relativePath; bytes = $file.Length; lastWriteTimeUtc = $file.LastWriteTimeUtc.ToString('o'); sha256 = $sourceHash }
    }
    foreach ($stagedFile in (Get-ChildItem -LiteralPath $stageSource -File -Recurse)) {
        $relativePath = $stagedFile.FullName.Substring($stageSource.TrimEnd('\', '/').Length + 1)
        if (-not $sourcePathSet.Contains($relativePath)) { throw "Stale staging file found: $relativePath. Use a new StageRoot; this script does not delete files." }
    }
    $snapshotPath = Join-Path $logDir 'source-sha256.json'
    $sourceSnapshot | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $snapshotPath -Encoding utf8
    $runInfo['sourceSnapshotSha256'] = (Get-FileHash -LiteralPath $snapshotPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $runInfo['sourceFiles'] = $sourceSnapshot.Count
    $runInfo['copiedFiles'] = $copiedFiles
    Write-Host "Staging: $StageRoot ($copiedFiles files copied)"
    Write-Host "Logs: $logDir"

    $env:JAVA_HOME = $jdkRoot
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_NDK_ROOT = $ndkRoot
    # A persistent daemon inherits the native command's output handles on Windows,
    # keeping PowerShell's logging pipeline open after CMake has already exited.
    $env:GRADLE_OPTS = ($savedEnvironment['GRADLE_OPTS'] + ' -Dorg.gradle.daemon=false').Trim()
    $env:PATH = "$(Join-Path $jdkRoot 'bin');C:\Qt\Tools\Ninja;C:\Qt\Tools\mingw1310_64\bin;$(Join-Path $qtHostRoot 'bin');" + $savedEnvironment['PATH']
    foreach ($name in @('QT_PLUGIN_PATH', 'QML_IMPORT_PATH', 'QML2_IMPORT_PATH')) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    Push-Location -LiteralPath $StageRoot
    $locationPushed = $true
    Invoke-LoggedCommand -Name 'cmake-version' -Executable $cmake -Arguments @('--version')
    Invoke-LoggedCommand -Name 'ninja-version' -Executable $ninja -Arguments @('--version')
    Invoke-LoggedCommand -Name 'java-version' -Executable (Join-Path $jdkRoot 'bin\java.exe') -Arguments @('-version')
    Copy-Item -LiteralPath (Join-Path $ndkRoot 'source.properties') -Destination (Join-Path $logDir 'ndk-version.properties')
    $cachePath = Join-Path $buildDir 'CMakeCache.txt'
    if (Test-Path -LiteralPath $cachePath) {
        $homeLine = Select-String -LiteralPath $cachePath -Pattern '^CMAKE_HOME_DIRECTORY:INTERNAL=' | Select-Object -First 1
        $cacheHome = if ($homeLine) { $homeLine.Line.Split('=', 2)[1] } else { '' }
        if (-not $cacheHome -or [IO.Path]::GetFullPath($cacheHome) -ne $stageSource) { throw 'CMake cache belongs to another source directory.' }
    }
    Invoke-LoggedCommand -Name 'configure' -Executable $cmake -Arguments @(
        '-S', $stageSource, '-B', $buildDir, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Debug',
        "-DCMAKE_TOOLCHAIN_FILE=$(Join-Path $ndkRoot 'build\cmake\android.toolchain.cmake')",
        "-DCMAKE_PREFIX_PATH=$qtRoot", "-DQT_HOST_PATH=$qtHostRoot", "-DANDROID_SDK_ROOT=$sdkRoot",
        "-DANDROID_NDK=$ndkRoot", "-DCMAKE_FIND_ROOT_PATH=$qtRoot", '-DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF',
        '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-28', "-DCMAKE_MAKE_PROGRAM=$ninja",
        "-DFLOATMUSIC_ANDROID_OPENSSL=$opensslRoot", '-DFLOATMUSIC_TESTS=OFF'
    )
    $deploymentPath = Join-Path $buildDir 'android-FloatMusic-deployment-settings.json'
    $deployment = Get-Content -LiteralPath $deploymentPath -Raw | ConvertFrom-Json
    Copy-Item -LiteralPath $deploymentPath -Destination (Join-Path $logDir 'deployment-settings.json')
    $runInfo['versionName'] = $deployment.'android-version-name'
    $runInfo['versionCode'] = [int]$deployment.'android-version-code'
    if (-not $ConfigureOnly) {
        if ($Package -and $deployment.'android-version-name' -notmatch '^1\.6\.') { throw 'Android version metadata must be updated to 1.6 before packaging.' }
        if ($Package -and $runInfo.versionCode -le 27) { throw 'Android versionCode must exceed the preceding 1.6 preview code 27.' }
        $target = if ($Package) { 'apk' } else { 'FloatMusic' }
        Invoke-LoggedCommand -Name 'build' -Executable $cmake -Arguments @('--build', $buildDir, '--target', $target, '--parallel', "$Parallel")
        if ($Package) {
            $builtApk = Join-Path $buildDir 'android-build\build\outputs\apk\debug\android-build-debug.apk'
            if (-not (Test-Path -LiteralPath $builtApk -PathType Leaf)) { throw "Expected APK is missing: $builtApk" }
            Invoke-LoggedCommand -Name 'signature' -Executable $apksigner -Arguments @('verify', '--verbose', '--print-certs', $builtApk)
            $signatureText = Get-Content -LiteralPath (Join-Path $logDir 'signature.log') -Raw
            $signatureMatch = [regex]::Match($signatureText, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
            if (-not $signatureMatch.Success -or $signatureMatch.Groups[1].Value -ne $ExpectedSigningSha256) { throw 'APK signing certificate does not match the expected installed certificate.' }
            Invoke-LoggedCommand -Name 'badging' -Executable $aapt -Arguments @('dump', 'badging', $builtApk)
            $badgingText = Get-Content -LiteralPath (Join-Path $logDir 'badging.log') -Raw
            if ($badgingText -notmatch "package: name='org\.floatmusic\.player'") { throw 'APK package ID is incorrect.' }
            if ($badgingText -notmatch "versionCode='$($runInfo.versionCode)'" -or $badgingText -notmatch "versionName='$([regex]::Escape($runInfo.versionName))'") { throw 'APK version differs from configured metadata.' }
            if ($badgingText -notmatch "native-code: 'arm64-v8a'" -or $badgingText -notmatch "minSdkVersion:'28'" -or $badgingText -notmatch "targetSdkVersion:'36'") { throw 'APK ABI or SDK requirements differ from the expected configuration.' }
            $apkArchive = [IO.Compression.ZipFile]::OpenRead($builtApk)
            try {
                $nativeLibraries = @($apkArchive.Entries.FullName | Where-Object { $_ -like 'lib/*' } | Sort-Object)
                foreach ($requiredLibrary in @('lib/arm64-v8a/libFloatMusic_arm64-v8a.so', 'lib/arm64-v8a/libcrypto_3.so', 'lib/arm64-v8a/libssl_3.so')) {
                    if ($nativeLibraries -notcontains $requiredLibrary) { throw "APK is missing a required native library: $requiredLibrary" }
                }
                $nativeLibraries | Set-Content -LiteralPath (Join-Path $logDir 'apk-native-libraries.txt') -Encoding utf8
            } finally { $apkArchive.Dispose() }
            foreach ($entry in ($sourceSnapshot | Where-Object { $_.path -like 'android\src\*' -or $_.path -like 'android\res\*' })) {
                $deployedPath = Join-Path (Join-Path $buildDir 'android-build') $entry.path.Substring('android\'.Length)
                if (-not (Test-Path -LiteralPath $deployedPath) -or (Get-FileHash -LiteralPath $deployedPath -Algorithm SHA256).Hash -ne $entry.sha256) {
                    throw "Packaged Android source differs from the staged snapshot: $($entry.path)"
                }
            }
            foreach ($entry in $sourceSnapshot) {
                if ((Get-FileHash -LiteralPath (Join-Path $sourceRoot $entry.path) -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Source changed during compilation: $($entry.path). Rebuild the new snapshot." }
            }
            $apkName = "FloatMusic-$($runInfo.versionName)-android-arm64-v8a.apk"
            $finalApk = Join-Path $artifactRoot $apkName
            Copy-Item -LiteralPath $builtApk -Destination $finalApk -Force
            $runInfo['apkPath'] = $finalApk
            $runInfo['apkBytes'] = (Get-Item -LiteralPath $finalApk).Length
            $runInfo['apkSha256'] = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
            $runInfo['signingCertificateSha256'] = $signatureMatch.Groups[1].Value.ToLowerInvariant()
            "$($runInfo.apkSha256)  $apkName" | Set-Content -LiteralPath (Join-Path $artifactRoot 'SHA256SUMS.txt') -Encoding ascii
            Copy-Item -LiteralPath $snapshotPath -Destination (Join-Path $artifactRoot 'source-sha256.json') -Force
            Write-Host "Android preview: $finalApk"
        }
    }
    $runInfo['status'] = 'succeeded'
} catch {
    $runInfo['status'] = 'failed'
    $runInfo['error'] = $_.Exception.Message
    throw
} finally {
    $runInfo['finishedAt'] = Get-Date -Format o
    $runInfo | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $logDir 'run.json') -Encoding utf8
    if ($runInfo.status -eq 'succeeded' -and $runInfo.Contains('apkPath')) {
        $runInfo | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $artifactRoot 'build-verification.json') -Encoding utf8
    }
    if ($locationPushed) { Pop-Location }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    if ($stageLock) { $stageLock.Dispose() }
}
