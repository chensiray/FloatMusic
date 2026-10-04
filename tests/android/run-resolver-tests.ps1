param(
    [string]$JdkRoot = 'C:/DevTools/jdk-21',
    [string]$JsonJar,
    [string]$OutputDir
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'artifacts/android-resolver-test' }
if (-not $JsonJar) {
    foreach ($candidate in @('artifacts/android-resolver-test/json.jar', 'artifacts/audio-source-integration/java-test/json.jar')) {
        $candidatePath = Join-Path $repoRoot $candidate
        if (Test-Path -LiteralPath $candidatePath) { $JsonJar = $candidatePath; break }
    }
}
if (-not $JsonJar -or -not (Test-Path -LiteralPath $JsonJar)) { throw 'Pass -JsonJar with the local org.json test library.' }
$JsonJar = [IO.Path]::GetFullPath($JsonJar)
$classes = Join-Path ([IO.Path]::GetFullPath($OutputDir)) 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(
    'android/src/org/floatmusic/player/AudioResolver.java',
    'android/src/org/floatmusic/player/TrackState.java',
    'tests/android/AudioResolverTest.java',
    'tests/android/TrackStateTest.java'
) | ForEach-Object { Join-Path $repoRoot $_ }
& (Join-Path $JdkRoot 'bin/javac.exe') '--release' '8' '-Xlint:-options' '-encoding' 'UTF-8' '-cp' $JsonJar '-d' $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Android JVM tests failed to compile.' }
foreach ($testClass in @('org.floatmusic.player.AudioResolverTest', 'org.floatmusic.player.TrackStateTest')) {
    & (Join-Path $JdkRoot 'bin/java.exe') '-cp' "$classes;$JsonJar" $testClass
    if ($LASTEXITCODE -ne 0) { throw "Android JVM test failed: $testClass" }
}
