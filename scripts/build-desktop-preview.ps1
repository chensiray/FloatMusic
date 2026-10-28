<#
.SYNOPSIS
Builds the desktop preview from an ASCII staging directory without launching it.
.EXAMPLE
.\scripts\build-desktop-preview.ps1 -Package
.EXAMPLE
.\scripts\build-desktop-preview.ps1 -Targets player_tests -SkipTests
.NOTES
The default staging directory is $env:TEMP\FloatMusic-v13-desktop\work.
Repeated runs reuse its build tree. Source files are copied only when their size
or last-write time differs. Existing files and directories are never deleted.
Targets limits compilation; use SkipTests until every CTest executable is built.
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Package,
    [switch]$ConfigureOnly,
    [string[]]$Targets = @(),
    [string]$ArtifactRoot,
    [string]$StageRoot,
    [ValidateRange(1, 32)][int]$Parallel = 4
)

$ErrorActionPreference = 'Stop'
if ($ConfigureOnly -and $Package) {
    throw 'ConfigureOnly cannot be combined with Package.'
}

$sourceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$taskTempRoot = [IO.Path]::GetFullPath((Join-Path $env:TEMP 'FloatMusic-v13-desktop'))
if (-not $StageRoot) { $StageRoot = Join-Path $taskTempRoot 'work' }
$StageRoot = [IO.Path]::GetFullPath($StageRoot)
$taskTempPrefix = $taskTempRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $StageRoot.StartsWith($taskTempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "StageRoot must be a child of the task's temporary directory: $taskTempRoot"
}
if ($StageRoot -match '[^\x00-\x7F]') {
    throw "Compilation requires an ASCII staging path: $StageRoot"
}

$stageSource = Join-Path $StageRoot 'source'
$buildDir = Join-Path $StageRoot 'build'
if (-not $ArtifactRoot) { $ArtifactRoot = Join-Path $sourceRoot 'artifacts\v1.5-desktop-preview-2026-10-25' }
$artifactRoot = [IO.Path]::GetFullPath($ArtifactRoot)
$appDir = Join-Path $artifactRoot 'app'
$runId = 'run-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$logDir = Join-Path (Join-Path $artifactRoot 'logs') $runId
$testArtifactDir = Join-Path (Join-Path $StageRoot 'test-artifacts') $runId
$qtRoot = 'C:\Qt\6.11.2\mingw_64'
$compilerRoot = 'C:\Qt\Tools\mingw1310_64\bin'
$cmake = 'C:\Qt\Tools\CMake_64\bin\cmake.exe'
$ctest = 'C:\Qt\Tools\CMake_64\bin\ctest.exe'
$ninja = 'C:\Qt\Tools\Ninja\ninja.exe'
$deployQt = Join-Path $qtRoot 'bin\windeployqt.exe'

foreach ($requiredTool in @($cmake, $ctest, $ninja, (Join-Path $compilerRoot 'g++.exe'))) {
    if (-not (Test-Path -LiteralPath $requiredTool -PathType Leaf)) {
        throw "Required desktop build tool is missing: $requiredTool"
    }
}
if ($Package -and -not (Test-Path -LiteralPath $deployQt -PathType Leaf)) {
    throw "Required packaging tool is missing: $deployQt"
}

New-Item -ItemType Directory -Path $stageSource, $buildDir, $logDir -Force | Out-Null
$runInfo = [ordered]@{
    runId = $runId
    startedAt = Get-Date -Format o
    sourceRoot = $sourceRoot
    stageRoot = $StageRoot
    buildDir = $buildDir
    logDir = $logDir
    appDir = $appDir
    targets = $Targets
    skipTests = [bool]$SkipTests
    package = [bool]$Package
    configureOnly = [bool]$ConfigureOnly
    status = 'running'
}
$runInfo | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logDir 'run.json') -Encoding utf8

function Invoke-LoggedCommand {
    param([string]$Name, [string]$Executable, [string[]]$Arguments)
    $stepInfo = [ordered]@{
        executable = $Executable
        arguments = $Arguments
        workingDirectory = (Get-Location).Path
        startedAt = Get-Date -Format o
    }
    $stepFile = Join-Path $logDir "$Name-command.json"
    $stepInfo | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $stepFile -Encoding utf8
    Write-Host "$Name -> $(Join-Path $logDir "$Name.log")"
    & $Executable @Arguments 2>&1 | Tee-Object -FilePath (Join-Path $logDir "$Name.log")
    $stepExitCode = $LASTEXITCODE
    $stepInfo['finishedAt'] = Get-Date -Format o
    $stepInfo['exitCode'] = $stepExitCode
    $stepInfo | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $stepFile -Encoding utf8
    if ($stepExitCode -ne 0) { throw "$Name failed with exit code $stepExitCode. See $logDir" }
}

$environmentNames = @('PATH', 'QT_PLUGIN_PATH', 'QML_IMPORT_PATH', 'QML2_IMPORT_PATH', 'QT_QPA_PLATFORM', 'QT_QUICK_BACKEND', 'FLOATMUSIC_TEST_ARTIFACTS', 'FLOATMUSIC_LIVE_TESTS', 'FLOATMUSIC_LIVE_CATALOGS', 'FLOATMUSIC_LIVE_MULTISOURCE', 'FLOATMUSIC_LIVE_QUALITY')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$locationPushed = $false
try {
    $copiedFiles = 0
    foreach ($entry in @('CMakeLists.txt', 'src', 'qml', 'assets', 'resources', 'tests')) {
        $entryPath = Join-Path $sourceRoot $entry
        if (-not (Test-Path -LiteralPath $entryPath)) { throw "Required source entry is missing: $entryPath" }
        $files = if (Test-Path -LiteralPath $entryPath -PathType Leaf) {
            @(Get-Item -LiteralPath $entryPath)
        } else {
            @(Get-ChildItem -LiteralPath $entryPath -File -Recurse)
        }
        foreach ($file in $files) {
            $relativePath = $file.FullName.Substring($sourceRoot.TrimEnd('\', '/').Length + 1)
            $destination = Join-Path $stageSource $relativePath
            $existing = Get-Item -LiteralPath $destination -ErrorAction SilentlyContinue
            if ($existing -and $existing.Length -eq $file.Length -and $existing.LastWriteTimeUtc -eq $file.LastWriteTimeUtc) { continue }
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
            $copiedFiles++
        }
    }
    $runInfo['copiedFiles'] = $copiedFiles
    Write-Host "Staging: $StageRoot ($copiedFiles files copied)"
    Write-Host "Logs: $logDir"

    $env:PATH = "$compilerRoot;C:\Qt\Tools\Ninja;$(Join-Path $qtRoot 'bin');" + $savedEnvironment['PATH']
    $env:QT_PLUGIN_PATH = Join-Path $qtRoot 'plugins'
    $env:QML_IMPORT_PATH = Join-Path $qtRoot 'qml'
    $env:QML2_IMPORT_PATH = $env:QML_IMPORT_PATH
    $env:QT_QPA_PLATFORM = 'offscreen'
    $env:QT_QUICK_BACKEND = 'software'
    $env:FLOATMUSIC_TEST_ARTIFACTS = $testArtifactDir
    # Live tests check whether these variables exist, so remove rather than set 0.
    foreach ($name in @('FLOATMUSIC_LIVE_TESTS', 'FLOATMUSIC_LIVE_CATALOGS', 'FLOATMUSIC_LIVE_MULTISOURCE', 'FLOATMUSIC_LIVE_QUALITY')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Push-Location -LiteralPath $StageRoot
    $locationPushed = $true
    Invoke-LoggedCommand -Name 'configure' -Executable $cmake -Arguments @(
        '-S', $stageSource, '-B', $buildDir, '-G', 'Ninja',
        '-DCMAKE_BUILD_TYPE=Debug', "-DCMAKE_PREFIX_PATH=$qtRoot",
        "-DCMAKE_CXX_COMPILER=$(Join-Path $compilerRoot 'g++.exe')",
        "-DCMAKE_MAKE_PROGRAM=$ninja", '-DFLOATMUSIC_TESTS=ON'
    )
    if (-not $ConfigureOnly) {
        $buildArguments = @('--build', $buildDir, '--parallel', "$Parallel")
        if ($Targets.Count -gt 0) {
            $buildTargets = @($Targets)
            if ($Package -and $buildTargets -notcontains 'FloatMusic') { $buildTargets += 'FloatMusic' }
            $buildArguments += @('--target') + $buildTargets
        }
        Invoke-LoggedCommand -Name 'build' -Executable $cmake -Arguments $buildArguments
        if (-not $SkipTests) {
            try {
                Invoke-LoggedCommand -Name 'ctest' -Executable $ctest -Arguments @('--test-dir', $buildDir, '--output-on-failure', '--output-junit', (Join-Path $logDir 'ctest.xml'))
            } finally {
                $testLogDir = Join-Path $logDir 'tests'
                New-Item -ItemType Directory -Path $testLogDir -Force | Out-Null
                Get-ChildItem -LiteralPath $buildDir -Filter '*-tests.txt' -File | ForEach-Object {
                    Copy-Item -LiteralPath $_.FullName -Destination $testLogDir -Force
                }
                $lastTestLog = Join-Path $buildDir 'Testing\Temporary\LastTest.log'
                if (Test-Path -LiteralPath $lastTestLog) { Copy-Item -LiteralPath $lastTestLog -Destination $testLogDir -Force }
                if (Test-Path -LiteralPath $testArtifactDir) { Copy-Item -LiteralPath $testArtifactDir -Destination (Join-Path $logDir 'screenshots') -Recurse }
            }
        }
        if ($Package) {
            $runtimeDir = Join-Path (Join-Path $StageRoot 'packages') $runId
            New-Item -ItemType Directory -Path $runtimeDir | Out-Null
            Copy-Item -LiteralPath (Join-Path $buildDir 'FloatMusic.exe') -Destination $runtimeDir
            Invoke-LoggedCommand -Name 'package' -Executable $deployQt -Arguments @('--release', '--verbose', '0', '--qmldir', (Join-Path $stageSource 'qml'), '--dir', $runtimeDir, (Join-Path $runtimeDir 'FloatMusic.exe'))
            New-Item -ItemType Directory -Path $appDir -Force | Out-Null
            Get-ChildItem -LiteralPath $runtimeDir -Force | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $appDir -Recurse -Force
            }
            $runInfo['packageDir'] = $runtimeDir
            Write-Host "Desktop preview: $(Join-Path $appDir 'FloatMusic.exe')"
        }
    }
    $runInfo['status'] = 'succeeded'
} catch {
    $runInfo['status'] = 'failed'
    $runInfo['error'] = $_.Exception.Message
    throw
} finally {
    $runInfo['finishedAt'] = Get-Date -Format o
    $runInfo | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logDir 'run.json') -Encoding utf8
    if ($locationPushed) { Pop-Location }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
