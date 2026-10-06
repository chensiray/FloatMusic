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
if (!$OutputDir) { $OutputDir = Join-Path $projectRoot 'artifacts/v1-implementation/android-contract' }
if (!(Test-Path -LiteralPath $JsonJar)) { throw 'Provide a local org.json jar; no dependencies are downloaded.' }
$runDirectory = Join-Path $OutputDir ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$runDirectory/classes" -Force | Out-Null
$classpath = "$JsonJar;$SdkRoot/platforms/android-36/android.jar;$QtRoot/jar/Qt6Android.jar"
$sources = @(Get-ChildItem -LiteralPath "$projectRoot/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object { $_.FullName })
$qtSources = @(Get-ChildItem -LiteralPath "$QtRoot/src/android/java/src/org/qtproject/qt/android/bindings" -Filter '*.java' | ForEach-Object { $_.FullName })
& "$JdkRoot/bin/javac.exe" '--release' '8' '-Xlint:-options' '-encoding' 'UTF-8' '-cp' $classpath '-d' "$runDirectory/classes" @sources @qtSources (Join-Path $PSScriptRoot 'OverlayV1ContractTest.java') (Join-Path $PSScriptRoot 'OverlayV09ContractTest.java') (Join-Path $PSScriptRoot 'OverlayV1UiTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Overlay Java compilation failed.' }
foreach ($testClass in @('org.floatmusic.player.OverlayV1ContractTest','org.floatmusic.player.OverlayV09ContractTest')) {
    & "$JdkRoot/bin/java.exe" '-cp' "$runDirectory/classes;$classpath" $testClass
    if ($LASTEXITCODE -ne 0) { throw "Overlay contract regression failed: $testClass" }
}
