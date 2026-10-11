param(
    [string]$JdkRoot = 'C:/DevTools/jdk-21',
    [string]$JsonJar,
    [string]$OutputDir,
    [string]$LogName = 'latest.log'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'artifacts/v12-android-store' }
if (-not $JsonJar) { $JsonJar = Join-Path $repoRoot 'artifacts/audio-source-integration/java-test/json.jar' }
if (-not (Test-Path -LiteralPath $JsonJar -PathType Leaf)) { throw 'Pass -JsonJar with the local org.json test library.' }
$JsonJar = [IO.Path]::GetFullPath($JsonJar)
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
$classes = Join-Path $OutputDir 'classes'
$data = Join-Path $OutputDir 'data'
New-Item -ItemType Directory -Path $classes, $data -Force | Out-Null
$log = Join-Path $OutputDir ([IO.Path]::GetFileName($LogName))
$source = Join-Path $repoRoot 'android/src/org/floatmusic/player/PlaybackQueueStore.java'
$sources = @(Join-Path $repoRoot 'tests/android/PlaybackQueueStoreTest.java')
if (Test-Path -LiteralPath $source -PathType Leaf) { $sources = @($source) + $sources }
"PlaybackQueueStore JVM verification; Java release 8; $(Get-Date -Format o)" | Set-Content -LiteralPath $log -Encoding utf8
$compileOutput = & (Join-Path $JdkRoot 'bin/javac.exe') '--release' '8' '-Xlint:all,-options' '-encoding' 'UTF-8' '-cp' $JsonJar '-d' $classes @sources 2>&1
$compileCode = $LASTEXITCODE
$compileOutput | Out-File -LiteralPath $log -Append -Encoding utf8
if ($compileCode -ne 0) { Write-Output "PlaybackQueueStore compilation failed; log: $log"; exit $compileCode }
$testOutput = & (Join-Path $JdkRoot 'bin/java.exe') '-cp' "$classes;$JsonJar" 'org.floatmusic.player.PlaybackQueueStoreTest' $data 2>&1
$testCode = $LASTEXITCODE
$testOutput | Out-File -LiteralPath $log -Append -Encoding utf8
$summary = $testOutput | Where-Object { $_.ToString().StartsWith('PlaybackQueueStore:') } | Select-Object -Last 1
if ($summary) { Write-Output $summary } else { Write-Output 'PlaybackQueueStore did not produce a test summary.' }
Write-Output "Log: $log"
exit $testCode
