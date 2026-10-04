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
if (!$OutputDir) { $OutputDir = Join-Path $projectRoot 'artifacts/v09-android-ui' }
if (!(Test-Path -LiteralPath $JsonJar)) { throw 'Provide -JsonJar with a local org.json jar; this test does not download dependencies.' }
New-Item -ItemType Directory -Path "$OutputDir/classes" -Force | Out-Null
$classpath = "$JsonJar;$SdkRoot/platforms/android-36/android.jar;$QtRoot/jar/Qt6Android.jar"
$sources = @(Get-ChildItem -LiteralPath "$projectRoot/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object { $_.FullName })
$qtSources = @(Get-ChildItem -LiteralPath "$QtRoot/src/android/java/src/org/qtproject/qt/android/bindings" -Filter '*.java' | ForEach-Object { $_.FullName })
& "$JdkRoot/bin/javac.exe" '--release' '8' '-Xlint:-options' '-encoding' 'UTF-8' '-cp' $classpath '-d' "$OutputDir/classes" @sources @qtSources (Join-Path $PSScriptRoot 'OverlayV09ContractTest.java') (Join-Path $PSScriptRoot 'OverlayV09UiTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Overlay Java and fixture compilation failed.' }
& "$JdkRoot/bin/java.exe" '-cp' "$OutputDir/classes;$classpath" 'org.floatmusic.player.OverlayV09ContractTest'
if ($LASTEXITCODE -ne 0) { throw 'Overlay 0.9 contract regression failed.' }
