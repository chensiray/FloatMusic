param([string]$DeviceSerial='emulator-5580',[string]$Label='run',[int]$AdbPort=5037)
$ErrorActionPreference='Stop'
if($DeviceSerial -notmatch '^emulator-\d+$'){throw 'Cache integration is emulator-only; physical devices are not authorized.'}
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$run=Join-Path $root ('artifacts/android-cache-integration-2026-10-30/'+$Label+'-'+(Get-Date -Format 'HHmmss'))
New-Item -ItemType Directory -Path "$run/classes","$run/dex","$run/screenshots" -Force | Out-Null
$jdk='C:/DevTools/jdk-21/bin';$sdk='C:/Android/Sdk';$qt='C:/Qt/6.11.2/android_arm64_v8a';$build="$sdk/build-tools/36.0.0";$androidJar="$sdk/platforms/android-36/android.jar";$qtJar="$qt/jar/Qt6Android.jar";$adb="$sdk/platform-tools/adb.exe"
function Run([string]$Executable,[string[]]$Arguments){if($Executable -eq $adb){$Arguments=@('-P',"$AdbPort")+$Arguments};& $Executable @Arguments;if($LASTEXITCODE -ne 0){throw "Command failed: $Executable"}}
$deviceState=& $adb -P $AdbPort -s $DeviceSerial get-state 2>&1
if($LASTEXITCODE -ne 0 -or $deviceState -notcontains 'device'){throw "Owned emulator is unavailable: $DeviceSerial"}
$sources=@(Get-ChildItem "$root/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object FullName)
$bindings=@(Get-ChildItem "$qt/src/android/java/src/org/qtproject/qt/android/bindings" -Filter '*.java' | ForEach-Object FullName)
Run "$jdk/javac.exe" (@('--release','8','-Xlint:-options','-encoding','UTF-8','-cp',"$androidJar;$qtJar",'-d',"$run/classes")+$sources+$bindings+@("$PSScriptRoot/CachePlaybackRegressionTest.java"))
Run "$jdk/jar.exe" @('cf',"$run/fixture.jar",'-C',"$run/classes",'.')
Run "$jdk/java.exe" @('-cp',"$build/lib/d8.jar",'com.android.tools.r8.D8','--min-api','28','--lib',$androidJar,'--output',"$run/dex","$run/fixture.jar",$qtJar)
Copy-Item -LiteralPath "$PSScriptRoot/cache-playback-manifest.xml" -Destination "$run/AndroidManifest.xml"
Run "$build/aapt.exe" @('package','-f','-M',"$run/AndroidManifest.xml",'-I',$androidJar,'-F',"$run/unsigned.apk")
Push-Location "$run/dex"
try{Run "$build/aapt.exe" (@('add',"$run/unsigned.apk")+@(Get-ChildItem -Filter '*.dex' | ForEach-Object Name))}finally{Pop-Location}
Run "$build/apksigner.bat" @('sign','--ks',"$env:USERPROFILE/.android/debug.keystore",'--ks-pass','pass:android','--key-pass','pass:android','--out',"$run/fixture.apk","$run/unsigned.apk")
Run $adb @('-s',$DeviceSerial,'install','-r',"$run/fixture.apk")
Run $adb @('-s',$DeviceSerial,'shell','appops','set','org.floatmusic.cachefixture','SYSTEM_ALERT_WINDOW','allow')
$result=@(& $adb -P $AdbPort -s $DeviceSerial shell am instrument -w -r org.floatmusic.cachefixture/org.floatmusic.player.CachePlaybackRegressionTest 2>&1 | Tee-Object -FilePath "$run/results.txt")
$text=$result -join "`n"
$artifactMatch=[regex]::Match($text,'(?m)^INSTRUMENTATION_RESULT: artifactDir=((?:/storage/emulated/0|/sdcard)/Android/data/org\.floatmusic\.cachefixture/files/run-\d+)\s*$')
if($artifactMatch.Success){Run $adb @('-s',$DeviceSerial,'pull',($artifactMatch.Groups[1].Value+'/.'),"$run/screenshots")}
Write-Output "Cache integration artifacts: $run"
if($text -notmatch 'cachePlaybackPassed=true' -or $text -match 'FAIL|Process crashed|INSTRUMENTATION_FAILED'){throw "Cache playback integration failed: $run/results.txt"}
