param(
    [string]$SdkRoot = 'C:/Android/Sdk',
    [string]$JdkRoot = 'C:/DevTools/jdk-21',
    [string]$BuildTools = '36.0.0',
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$DeviceSerial,
    [string]$KeyStore = "$env:USERPROFILE/.android/debug.keystore",
    [string]$OutputDir,
    [switch]$Screenshots
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) { throw 'Provide an explicit -DeviceSerial.' }
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$artifactsRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'artifacts')).TrimEnd([char[]]'\/')
if (-not $OutputDir) { $OutputDir = Join-Path $artifactsRoot 'v09-sync-verification/android-ui' }
$OutputDir = [IO.Path]::GetFullPath($OutputDir).TrimEnd([char[]]'\/')
$SdkRoot = [IO.Path]::GetFullPath($SdkRoot).TrimEnd([char[]]'\/')
$JdkRoot = [IO.Path]::GetFullPath($JdkRoot).TrimEnd([char[]]'\/')
$KeyStore = [IO.Path]::GetFullPath($KeyStore)

function Test-InDirectory([string]$Candidate, [string]$Directory) {
    return $Candidate.Equals($Directory, [StringComparison]::OrdinalIgnoreCase) -or
        $Candidate.StartsWith($Directory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
if ($OutputDir.Equals($artifactsRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $OutputDir.Equals($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
    -not ((Test-InDirectory $OutputDir $artifactsRoot) -or (Test-InDirectory $OutputDir $temporaryRoot))) {
    throw 'OutputDir must be a dedicated subdirectory of repository artifacts or the temporary directory.'
}
$keyDirectory = [IO.Path]::GetDirectoryName($KeyStore).TrimEnd([char[]]'\/')
foreach ($protectedDirectory in @($SdkRoot, $JdkRoot, $keyDirectory)) {
    if ((Test-InDirectory $OutputDir $protectedDirectory) -or (Test-InDirectory $protectedDirectory $OutputDir)) {
        throw 'OutputDir cannot contain or be inside the SDK, JDK or signing-key directory.'
    }
}
# Check existing ancestors before any write, so a junction cannot redirect artifacts elsewhere.
$outputAncestor = $OutputDir
while ($outputAncestor) {
    if (Test-Path -LiteralPath $outputAncestor) {
        $ancestorItem = Get-Item -LiteralPath $outputAncestor -Force
        if (-not $ancestorItem.PSIsContainer -or
            ($ancestorItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'OutputDir and its existing ancestors must be ordinary directories, without junctions or symbolic links.'
        }
    }
    $ancestorParent = [IO.Directory]::GetParent($outputAncestor)
    if ($null -eq $ancestorParent) { break }
    $outputAncestor = $ancestorParent.FullName
}

$tools = Join-Path $SdkRoot "build-tools/$BuildTools"
$sdkJar = Join-Path $SdkRoot 'platforms/android-36/android.jar'
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$javac = Join-Path $JdkRoot 'bin/javac.exe'
$java = Join-Path $JdkRoot 'bin/java.exe'
$d8 = Join-Path $tools 'lib/d8.jar'
$aapt = Join-Path $tools 'aapt.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
$manifestFile = Join-Path $PSScriptRoot 'overlay-v09-manifest.xml'
$fixtureFile = Join-Path $PSScriptRoot 'OverlayV09UiTest.java'
foreach ($requiredFile in @($sdkJar, $adb, $javac, $java, $d8, $aapt, $apksigner, $KeyStore, $manifestFile, $fixtureFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw "Required file is missing: $requiredFile" }
}
$testPackage = 'org.floatmusic.v09uitest'
$instrumentClass = 'org.floatmusic.v09uitest.OverlayV09UiTest'
$fixtureManifest = [xml](Get-Content -LiteralPath $manifestFile -Raw)
if ($fixtureManifest.manifest.package -ne $testPackage -or
    $fixtureManifest.manifest.instrumentation.GetAttribute('name','http://schemas.android.com/apk/res/android') -ne $instrumentClass -or
    $fixtureManifest.manifest.instrumentation.GetAttribute('targetPackage','http://schemas.android.com/apk/res/android') -ne 'org.floatmusic.player') {
    throw 'The fixture manifest must identify only the 0.9 UI test package and the FloatMusic target.'
}
function Invoke-Checked([string]$Executable, [string[]]$CommandArgs) {
    & $Executable @CommandArgs
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Executable" }
}

# A fresh run directory avoids deleting old artifacts or reusing stale compiled classes.
$runDirectory = Join-Path $OutputDir ("run-{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$runDirectory/classes", "$runDirectory/dex" -Force | Out-Null
Copy-Item -LiteralPath $manifestFile -Destination "$runDirectory/AndroidManifest.xml"
Invoke-Checked $javac @('--release', '8', '-Xlint:-options', '-encoding', 'UTF-8',
    '-cp', $sdkJar, '-d', "$runDirectory/classes", $fixtureFile)
$classFiles = @(Get-ChildItem -LiteralPath "$runDirectory/classes" -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
if ($classFiles.Count -eq 0) { throw 'The UI fixture did not produce class files.' }
Invoke-Checked $java (@('-cp', $d8, 'com.android.tools.r8.D8', '--min-api', '28', '--lib', $sdkJar,
    '--output', "$runDirectory/dex") + $classFiles)
Invoke-Checked $aapt @('package', '-f', '-M', "$runDirectory/AndroidManifest.xml", '-I', $sdkJar,
    '-F', "$runDirectory/ui-test-unsigned.apk")
Push-Location "$runDirectory/dex"
try { Invoke-Checked $aapt @('add', "$runDirectory/ui-test-unsigned.apk", 'classes.dex') }
finally { Pop-Location }
# The target and fixture use the same standard Android debug key and its public default password.
Invoke-Checked $apksigner @('sign', '--ks', $KeyStore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--out', "$runDirectory/ui-test.apk", "$runDirectory/ui-test-unsigned.apk")
$resultsFile = Join-Path $runDirectory 'ui-results.txt'
$installed = $false
try {
    Invoke-Checked $adb @('-s', $DeviceSerial, 'install', '-r', "$runDirectory/ui-test.apk")
    $installed = $true
    $instrumentArgs = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r')
    if ($Screenshots) { $instrumentArgs += @('-e', 'screenshots', 'true') }
    $result = @(& $adb @instrumentArgs "$testPackage/$instrumentClass" 2>&1)
    $instrumentExit = $LASTEXITCODE
    $result | Set-Content -LiteralPath $resultsFile -Encoding utf8
    $result
    $text = $result -join "`n"
    # Android prefixes the first stream line with INSTRUMENTATION_RESULT: stream=.
    $streamText = [regex]::Replace($text, '(?m)^INSTRUMENTATION_RESULT:\s*stream=', '')
    $cases = @('sources', 'empty-and-playlist-search', 'mixed-identities', 'quality-editing', 'gesture-refresh')
    $allCasesPassed = $true
    foreach ($scene in $cases) {
        if ([regex]::Matches($streamText, "(?m)^PASS $([regex]::Escape($scene))\s*$").Count -ne 1) { $allCasesPassed = $false }
    }
    if ($instrumentExit -ne 0 -or $text -match 'FAIL|Process crashed|INSTRUMENTATION_ABORTED' -or
        -not $allCasesPassed -or
        ([regex]::Matches($streamText, '(?m)^PASS [^\r\n]+\s*$')).Count -ne 5 -or
        $text -notmatch '(?m)^INSTRUMENTATION_RESULT:\s*uiPassed=true\s*$' -or
        $text -notmatch '(?m)^INSTRUMENTATION_RESULT:\s*passed=5\s*$' -or
        $text -notmatch '(?m)^INSTRUMENTATION_CODE:\s*-1\s*$') {
        throw "Overlay 0.9 UI regression failed. See $resultsFile"
    }
    Write-Output "Overlay 0.9 UI regression passed. Results: $resultsFile"
} finally {
    if ($installed) { Invoke-Checked $adb @('-s', $DeviceSerial, 'uninstall', 'org.floatmusic.v09uitest') }
}
