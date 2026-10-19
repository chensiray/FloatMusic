#Requires -Version 7.4
param(
    [string]$SdkRoot = 'C:/Android/Sdk',
    [string]$JdkRoot = 'C:/DevTools/jdk-21',
    [string]$QtRoot = 'C:/Qt/6.11.2/android_arm64_v8a',
    [string]$BuildTools = '36.0.0',
    [ValidateRange(1,65535)][int]$AdbPort = 5037,
    [string]$DeviceSerial,
    [string]$KeyStore = "$env:USERPROFILE/.android/debug.keystore",
    [string]$OutputDir,
    [switch]$CompileOnly,
    [switch]$BuildOnly,
    [switch]$Screenshots
)
$ErrorActionPreference = 'Stop'
if (-not ($CompileOnly -or $BuildOnly) -and [string]::IsNullOrWhiteSpace($DeviceSerial)) { throw 'Provide an explicit -DeviceSerial for a device run.' }
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$artifactsRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'artifacts')).TrimEnd([char[]]'\/')
if (-not $OutputDir) { $OutputDir = Join-Path $artifactsRoot 'v1.3-android-preview-2026-10-15/visual' }
$OutputDir = [IO.Path]::GetFullPath($OutputDir).TrimEnd([char[]]'\/')
$SdkRoot = [IO.Path]::GetFullPath($SdkRoot).TrimEnd([char[]]'\/')
$JdkRoot = [IO.Path]::GetFullPath($JdkRoot).TrimEnd([char[]]'\/')
$QtRoot = [IO.Path]::GetFullPath($QtRoot).TrimEnd([char[]]'\/')
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
foreach ($protectedDirectory in @($SdkRoot, $JdkRoot, $QtRoot, $keyDirectory)) {
    if ((Test-InDirectory $OutputDir $protectedDirectory) -or (Test-InDirectory $protectedDirectory $OutputDir)) {
        throw 'OutputDir cannot contain or be inside an SDK, runtime or signing-key directory.'
    }
}
$outputAncestor = $OutputDir
while ($outputAncestor) {
    if (Test-Path -LiteralPath $outputAncestor) {
        $ancestorItem = Get-Item -LiteralPath $outputAncestor -Force
        if (-not $ancestorItem.PSIsContainer -or ($ancestorItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'OutputDir and its existing ancestors must be ordinary directories, without junctions or symbolic links.'
        }
    }
    $ancestorParent = [IO.Directory]::GetParent($outputAncestor)
    if ($null -eq $ancestorParent) { break }
    $outputAncestor = $ancestorParent.FullName
}
$tools = Join-Path $SdkRoot "build-tools/$BuildTools"
$sdkJar = Join-Path $SdkRoot 'platforms/android-36/android.jar'
$qtJar = Join-Path $QtRoot 'jar/Qt6Android.jar'
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$javac = Join-Path $JdkRoot 'bin/javac.exe'
$java = Join-Path $JdkRoot 'bin/java.exe'
$jar = Join-Path $JdkRoot 'bin/jar.exe'
$d8 = Join-Path $tools 'lib/d8.jar'
$aapt = Join-Path $tools 'aapt.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
$manifestFile = Join-Path $PSScriptRoot 'overlay-v13-manifest.xml'
$fixtureFile = Join-Path $PSScriptRoot 'OverlayV13VisualTest.java'
$requiredFiles = @($sdkJar, $qtJar, $javac, $java, $manifestFile, $fixtureFile)
if (-not $CompileOnly) { $requiredFiles += @($adb, $jar, $d8, $aapt, $apksigner, $KeyStore) }
foreach ($requiredFile in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw "Required file is missing: $requiredFile" }
}
$testPackage = 'org.floatmusic.v13visualtest'
$instrumentClass = 'org.floatmusic.player.OverlayV13VisualTest'
$fixtureManifest = [xml](Get-Content -LiteralPath $manifestFile -Raw)
if ($fixtureManifest.manifest.package -ne $testPackage -or
    $fixtureManifest.manifest.instrumentation.GetAttribute('name','http://schemas.android.com/apk/res/android') -ne $instrumentClass -or
    $fixtureManifest.manifest.instrumentation.GetAttribute('targetPackage','http://schemas.android.com/apk/res/android') -ne 'org.floatmusic.player') {
    throw 'The fixture manifest must identify only the 1.3 UI test package and the FloatMusic target.'
}
function Invoke-Checked([string]$Executable, [string[]]$CommandArgs) {
    if ($Executable -eq $adb) { $CommandArgs = @('-P', [string]$AdbPort) + $CommandArgs }
    & $Executable @CommandArgs
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Executable" }
}
$runDirectory = Join-Path $OutputDir ("run-{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$runDirectory/classes", "$runDirectory/target-classes", "$runDirectory/dex" -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath "$repoRoot/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object { $_.FullName })
$qtSources = @(Get-ChildItem -LiteralPath "$QtRoot/src/android/java/src/org/qtproject/qt/android/bindings" -Filter '*.java' | ForEach-Object { $_.FullName })
Invoke-Checked $javac (@('-J-Duser.language=en', '-J-Duser.country=US', '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8',
    '-cp', "$sdkJar;$qtJar", '-d', "$runDirectory/target-classes") + $sources + $qtSources)
Invoke-Checked $javac @('-J-Duser.language=en', '-J-Duser.country=US', '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8',
    '-cp', "$runDirectory/target-classes;$sdkJar;$qtJar", '-d', "$runDirectory/classes", $fixtureFile)
if ($CompileOnly) { Write-Output "Overlay 1.3 UI fixture compilation passed. Classes: $runDirectory/classes"; return }
Copy-Item -LiteralPath $manifestFile -Destination "$runDirectory/AndroidManifest.xml"
$classFiles = @(Get-ChildItem -LiteralPath "$runDirectory/classes" -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
if ($classFiles.Count -eq 0) { throw 'The UI fixture did not produce class files.' }
# Production classes are compile-time dependencies only; the installed target owns them.
Invoke-Checked $jar @("-J-Djava.io.tmpdir=$runDirectory", '--create', '--file', "$runDirectory/target-dependencies.jar", '-C', "$runDirectory/target-classes", '.')
Invoke-Checked $java (@('-Duser.language=en', '-Duser.country=US', '-cp', $d8, 'com.android.tools.r8.D8', '--min-api', '28', '--lib', $sdkJar,
    '--classpath', "$runDirectory/target-dependencies.jar", '--output', "$runDirectory/dex") + $classFiles)
Invoke-Checked $aapt @('package', '-f', '-M', "$runDirectory/AndroidManifest.xml", '-I', $sdkJar, '-F', "$runDirectory/ui-test-unsigned.apk")
Push-Location "$runDirectory/dex"
try { Invoke-Checked $aapt @('add', "$runDirectory/ui-test-unsigned.apk", 'classes.dex') }
finally { Pop-Location }
Invoke-Checked $apksigner @('sign', '--ks', $KeyStore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--out', "$runDirectory/ui-test.apk", "$runDirectory/ui-test-unsigned.apk")
if ($BuildOnly) { Write-Output "Overlay 1.3 visual fixture APK built: $runDirectory/ui-test.apk"; return }
$resultsFile = Join-Path $runDirectory 'ui-results.txt'
$installed = $false
try {
    Invoke-Checked $adb @('-s', $DeviceSerial, 'install', '-r', "$runDirectory/ui-test.apk")
    $installed = $true
    $instrumentArgs = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r')
    if ($Screenshots) { $instrumentArgs += @('-e', 'screenshots', 'true') }
    $result = @(& $adb '-P' $AdbPort @instrumentArgs "$testPackage/$instrumentClass" 2>&1)
    $instrumentExit = $LASTEXITCODE
    $result | Set-Content -LiteralPath $resultsFile -Encoding utf8
    $result
    $text = $result -join "`n"
    $streamText = [regex]::Replace($text, '(?m)^INSTRUMENTATION_RESULT:\s*stream=', '')
    $cases = @(foreach ($theme in @('light','dark')) { foreach ($page in @('compact','lyrics','playlist','settings','popup','long-list')) { "$theme-$page" } })
    $allCasesPassed = $true
    foreach ($scene in $cases) {
        if ([regex]::Matches($streamText, "(?m)^PASS $([regex]::Escape($scene))\s*$").Count -ne 1) { $allCasesPassed = $false }
    }
    if ($instrumentExit -ne 0 -or $text -match 'FAIL|Process crashed|INSTRUMENTATION_ABORTED' -or -not $allCasesPassed -or
        ([regex]::Matches($streamText, '(?m)^PASS [^\r\n]+\s*$')).Count -ne 12 -or
        $text -notmatch '(?m)^INSTRUMENTATION_RESULT:\s*uiPassed=true\s*$' -or
        $text -notmatch '(?m)^INSTRUMENTATION_RESULT:\s*passed=12\s*$' -or
        $text -notmatch '(?m)^INSTRUMENTATION_CODE:\s*-1\s*$') {
        throw "Overlay 1.3 UI regression failed. See $resultsFile"
    }
    $captureDirectory = Join-Path $runDirectory 'captures'
    New-Item -ItemType Directory -Path $captureDirectory -Force | Out-Null
    foreach ($scene in $cases) {
        $extensions = @('geometry.json')
        if ($Screenshots) { $extensions += 'png' }
        foreach ($extension in $extensions) {
            $filename = "$scene.$extension"
            # PowerShell 7.4+ preserves native stdout bytes for PNG redirection.
            & $adb '-P' $AdbPort '-s' $DeviceSerial exec-out run-as org.floatmusic.player cat "cache/v13-ui-fixture/$filename" > (Join-Path $captureDirectory $filename)
            if ($LASTEXITCODE -ne 0) { throw "Could not collect native artifact: $filename" }
        }
    }
    Write-Output "Native screenshots and geometry: $captureDirectory"
    Write-Output "Overlay 1.3 UI regression passed. Results: $resultsFile"
} finally {
    if ($installed) { Invoke-Checked $adb @('-s', $DeviceSerial, 'uninstall', $testPackage) }
}
