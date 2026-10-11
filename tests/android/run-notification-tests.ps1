param(
    [string]$JdkRoot = 'C:/DevTools/jdk-21',
    [string]$SdkRoot = 'C:/Android/Sdk',
    [string]$BuildTools = '36.0.0',
    [string]$JsonJar = '',
    [string]$OutputDir = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $JsonJar) { $JsonJar = Join-Path $repoRoot 'artifacts/audio-source-integration/java-test/json.jar' }
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'artifacts/v12-android-notifications' }
$JsonJar = [IO.Path]::GetFullPath($JsonJar)
$androidJar = Join-Path $SdkRoot 'platforms/android-36/android.jar'
$javac = Join-Path $JdkRoot 'bin/javac.exe'
$java = Join-Path $JdkRoot 'bin/java.exe'
$aapt2 = Join-Path $SdkRoot "build-tools/$BuildTools/aapt2.exe"
foreach ($required in @($JsonJar, $androidJar, $javac, $java, $aapt2)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required local tool or dependency is missing: $required" }
}
# Each invocation has fresh classes; there are no stale successes or removals.
$runDirectory = Join-Path ([IO.Path]::GetFullPath($OutputDir)) ('run-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $runDirectory 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(
    (Join-Path $repoRoot 'android/src/org/floatmusic/player/PlaybackNotifications.java'),
    (Join-Path $PSScriptRoot 'PlaybackNotificationsTest.java')
)
$classpath = "$JsonJar;$androidJar"
& $javac '--release' '8' '-Xlint:-options' '-encoding' 'UTF-8' '-cp' $classpath '-d' $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Notification helper and policy tests failed to compile against the Android SDK.' }
$results = & $java '-cp' "$classes;$classpath" 'org.floatmusic.player.PlaybackNotificationsTest' 2>&1
$testExit = $LASTEXITCODE
$results | ForEach-Object { Write-Output $_ }
$results | Set-Content -LiteralPath (Join-Path $runDirectory 'policy-results.txt') -Encoding utf8
if ($testExit -ne 0) { throw "Notification policy test failed. Evidence: $runDirectory/policy-results.txt" }
# Windows aapt2 cannot open a Unicode workspace path. Only the helper's vector
# inputs are staged under an ASCII temporary path; final evidence stays in artifacts.
$resourceTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
$resourceStage = [IO.Path]::GetFullPath((Join-Path $resourceTempRoot ('floatmusic-notification-res-' + [Guid]::NewGuid().ToString('N'))))
if (-not [IO.Path]::GetDirectoryName($resourceStage).Equals($resourceTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Resource staging must remain in its dedicated temporary directory.'
}
$resourceDrawables = Join-Path $resourceStage 'res/drawable'
New-Item -ItemType Directory -Path $resourceDrawables -Force | Out-Null
try {
    $vectorFiles = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'android/res/drawable') -Filter 'floatmusic_*.xml' -File)
    if ($vectorFiles.Count -eq 0) { throw 'The notification vector resources are missing.' }
    Copy-Item -LiteralPath $vectorFiles.FullName -Destination $resourceDrawables
    $compiledResources = Join-Path $resourceStage 'resources.zip'
    & $aapt2 'compile' '--dir' (Join-Path $resourceStage 'res') '-o' $compiledResources
    if ($LASTEXITCODE -ne 0) { throw 'Notification vector resources failed Android resource compilation.' }
    Copy-Item -LiteralPath $compiledResources -Destination (Join-Path $runDirectory 'notification-resources.zip')
} finally {
    # The resolved absolute target was checked above before any writes or delete.
    Remove-Item -LiteralPath $resourceStage -Recurse -Force
}
Write-Output 'PASS Android vector resource compilation'
Add-Content -LiteralPath (Join-Path $runDirectory 'policy-results.txt') -Value 'PASS Android vector resource compilation' -Encoding utf8
Write-Output "Notification policy checks passed. Evidence: $runDirectory/policy-results.txt"
