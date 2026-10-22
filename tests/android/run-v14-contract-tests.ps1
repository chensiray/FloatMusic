param(
    [string]$JdkRoot = 'C:/DevTools/jdk-21',
    [string]$SdkRoot = 'C:/Android/Sdk',
    [string]$QtRoot = 'C:/Qt/6.11.2/android_arm64_v8a',
    [string]$JsonJar = '',
    [string]$OutputDir = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (!$JsonJar) { $JsonJar = Join-Path $projectRoot 'artifacts/audio-source-integration/java-test/json.jar' }
if (!$OutputDir) { $OutputDir = Join-Path $projectRoot 'artifacts/v1.4-android-preview-2026-10-20/tests/contracts' }
if (!(Test-Path -LiteralPath $JsonJar)) { throw 'Provide an existing org.json jar; nothing is downloaded.' }
$runDirectory = Join-Path $OutputDir (Get-Date -Format 'yyyyMMdd-HHmmss')
$classes = Join-Path $runDirectory 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpath = "$JsonJar;$SdkRoot/platforms/android-36/android.jar;$QtRoot/jar/Qt6Android.jar"
$sources = @(Get-ChildItem -LiteralPath "$projectRoot/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object FullName)
$bindings = @(Get-ChildItem -LiteralPath "$QtRoot/src/android/java/src/org/qtproject/qt/android/bindings" -Filter '*.java' | ForEach-Object FullName)
$tests = @('FloatingLyricRulesTest','UiStringsTest','OverlayV09ContractTest','OverlayV1ContractTest','OverlayV11ContractTest','OverlayV12ContractTest','PlaybackNotificationsTest')
$testSources = @($tests | ForEach-Object { Join-Path $PSScriptRoot "$_.java" })
& "$JdkRoot/bin/javac.exe" '--release' '8' '-Xlint:-options' '-encoding' 'UTF-8' '-cp' $classpath '-d' $classes @sources @bindings @testSources
if ($LASTEXITCODE -ne 0) { throw 'Android source compilation failed.' }
foreach ($test in $tests) {
    & "$JdkRoot/bin/java.exe" '-cp' "$classes;$classpath" "org.floatmusic.player.$test" | Tee-Object (Join-Path $runDirectory "$test.txt")
    if ($LASTEXITCODE -ne 0) { throw "$test failed." }
}
Write-Output "PASS all seven device-free mobile suites. Logs: $runDirectory"
