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
if (!$OutputDir) { $OutputDir = Join-Path $projectRoot 'artifacts/v12-android-ui/contracts' }
if (!(Test-Path -LiteralPath $JsonJar)) { throw 'Provide a local org.json jar; no dependencies are downloaded.' }
$runDirectory = Join-Path $OutputDir ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$runDirectory/classes" -Force | Out-Null
$classpath = "$JsonJar;$SdkRoot/platforms/android-36/android.jar;$QtRoot/jar/Qt6Android.jar"
$sources = @(Get-ChildItem -LiteralPath "$projectRoot/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object { $_.FullName })
$qtSources = @(Get-ChildItem -LiteralPath "$QtRoot/src/android/java/src/org/qtproject/qt/android/bindings" -Filter '*.java' | ForEach-Object { $_.FullName })
$testSources = @('OverlayV12ContractTest.java','OverlayV11ContractTest.java','OverlayV1ContractTest.java','OverlayV09ContractTest.java',
    'OverlayV11UiTest.java','OverlayV1UiTest.java','OverlayV09UiTest.java')
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'OverlayV12UiTest.java')) { $testSources += 'OverlayV12UiTest.java' }
$testSources = @($testSources | ForEach-Object { Join-Path $PSScriptRoot $_ })
& "$JdkRoot/bin/javac.exe" '-J-Duser.language=en' '-J-Duser.country=US' '--release' '8' '-Xlint:-options' '-encoding' 'UTF-8' '-cp' $classpath '-d' "$runDirectory/classes" @sources @qtSources @testSources
if ($LASTEXITCODE -ne 0) { throw 'Overlay Java compilation failed.' }
foreach ($testClass in @('org.floatmusic.player.OverlayV12ContractTest','org.floatmusic.player.OverlayV11ContractTest','org.floatmusic.player.OverlayV1ContractTest','org.floatmusic.player.OverlayV09ContractTest')) {
    & "$JdkRoot/bin/java.exe" '-Duser.language=en' '-Duser.country=US' '-cp' "$runDirectory/classes;$classpath" $testClass
    if ($LASTEXITCODE -ne 0) { throw "Overlay contract regression failed: $testClass" }
}
Write-Output "Overlay 1.2 Java compilation and contract checks passed. Classes: $runDirectory/classes"
