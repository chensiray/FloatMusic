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
if (!$OutputDir) { $OutputDir = Join-Path $projectRoot 'artifacts/v11-implementation/android-contract' }
if (!(Test-Path -LiteralPath $JsonJar)) { throw 'Provide a local org.json jar; no dependencies are downloaded.' }
$runDirectory = Join-Path $OutputDir ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$runDirectory/classes" -Force | Out-Null
$classpath = "$JsonJar;$SdkRoot/platforms/android-36/android.jar;$QtRoot/jar/Qt6Android.jar"
$sources = @(Get-ChildItem -LiteralPath "$projectRoot/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object { $_.FullName })
$qtSources = @(Get-ChildItem -LiteralPath "$QtRoot/src/android/java/src/org/qtproject/qt/android/bindings" -Filter '*.java' | ForEach-Object { $_.FullName })
$tests = @('OverlayV11ContractTest.java','OverlayV1ContractTest.java','OverlayV09ContractTest.java','OverlayV1UiTest.java','OverlayV09UiTest.java')
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'OverlayV11UiTest.java')) { $tests += 'OverlayV11UiTest.java' }
$testSources = @($tests | ForEach-Object { Join-Path $PSScriptRoot $_ })
& "$JdkRoot/bin/javac.exe" '--release' '8' '-Xlint:-options' '-encoding' 'UTF-8' '-cp' $classpath '-d' "$runDirectory/classes" @sources @qtSources @testSources
if ($LASTEXITCODE -ne 0) { throw 'Overlay Java compilation failed.' }
foreach ($testClass in @('org.floatmusic.player.OverlayV11ContractTest','org.floatmusic.player.OverlayV1ContractTest','org.floatmusic.player.OverlayV09ContractTest')) {
    & "$JdkRoot/bin/java.exe" '-cp' "$runDirectory/classes;$classpath" $testClass
    if ($LASTEXITCODE -ne 0) { throw "Overlay contract regression failed: $testClass" }
}
Write-Output "Overlay 1.1 Java compilation and contract checks passed. Classes: $runDirectory/classes"
