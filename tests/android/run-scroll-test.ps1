param(
    [string]$SdkRoot = 'C:/Android/Sdk',
    [string]$JdkRoot = 'C:/DevTools/jdk-21',
    [string]$BuildTools = '36.0.0',
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [string]$KeyStore = "$env:USERPROFILE/.android/debug.keystore",
    [string]$OutputDir
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'artifacts/android-scroll-test' }
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
$tools = Join-Path $SdkRoot "build-tools/$BuildTools"
$sdkJar = Join-Path $SdkRoot 'platforms/android-36/android.jar'
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
function Invoke-Checked([string]$Executable, [string[]]$CommandArgs) {
    & $Executable @CommandArgs
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Executable" }
}
New-Item -ItemType Directory -Path "$OutputDir/classes", "$OutputDir/dex" -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'scroll-manifest.xml') -Destination "$OutputDir/AndroidManifest.xml" -Force
Invoke-Checked (Join-Path $JdkRoot 'bin/javac.exe') @('--release', '8', '-Xlint:-options', '-encoding', 'UTF-8',
    '-cp', $sdkJar, '-d', "$OutputDir/classes", (Join-Path $PSScriptRoot 'OverlayScrollTest.java'))
$classFiles = @(Get-ChildItem -LiteralPath "$OutputDir/classes" -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
Invoke-Checked (Join-Path $JdkRoot 'bin/java.exe') (@('-cp', (Join-Path $tools 'lib/d8.jar'),
    'com.android.tools.r8.D8', '--min-api', '28', '--lib', $sdkJar, '--output', "$OutputDir/dex") + $classFiles)
Invoke-Checked (Join-Path $tools 'aapt.exe') @('package', '-f', '-M', "$OutputDir/AndroidManifest.xml",
    '-I', $sdkJar, '-F', "$OutputDir/scroll-test-unsigned.apk")
Push-Location "$OutputDir/dex"
try { Invoke-Checked (Join-Path $tools 'aapt.exe') @('add', "$OutputDir/scroll-test-unsigned.apk", 'classes.dex') }
finally { Pop-Location }
Invoke-Checked (Join-Path $tools 'apksigner.bat') @('sign', '--ks', $KeyStore, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', "$OutputDir/scroll-test.apk", "$OutputDir/scroll-test-unsigned.apk")
$installed = $false
try {
    Invoke-Checked $adb @('-s', $DeviceSerial, 'install', '-r', "$OutputDir/scroll-test.apk")
    $installed = $true
    $result = & $adb -s $DeviceSerial shell am instrument -w org.floatmusic.scrolltest/org.floatmusic.scrolltest.OverlayScrollTest 2>&1
    $result | Set-Content -LiteralPath "$OutputDir/scroll-results.txt" -Encoding utf8
    $result
    $text = $result -join "`n"
    if ($LASTEXITCODE -ne 0 -or $text -match 'FAIL|Process crashed' -or
        ([regex]::Matches($text, '(?m)^PASS (playlist|song-search|playlist-search) scrolls')).Count -ne 3 -or
        $text -notmatch 'PASS playlist button, checkbox and multi-select scrolling') {
        throw "Overlay scroll regression failed. See $OutputDir/scroll-results.txt"
    }
} finally {
    if ($installed) { Invoke-Checked $adb @('-s', $DeviceSerial, 'uninstall', 'org.floatmusic.scrolltest') }
}
