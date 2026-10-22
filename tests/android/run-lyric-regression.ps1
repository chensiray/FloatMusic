param([string]$DeviceSerial='emulator-5580',[string]$Label='run',[int]$AdbPort=5037,[switch]$AllowPhysicalDevice,[string]$Cases='')
$ErrorActionPreference='Stop'
if($DeviceSerial -notmatch '^emulator-\d+$' -and -not $AllowPhysicalDevice){throw 'A physical device requires an explicit serial and -AllowPhysicalDevice.'}
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$run=Join-Path $root ('artifacts/android-lyric-jump-2026-10-20/'+$Label+'-'+(Get-Date -Format 'HHmmss'))
New-Item -ItemType Directory -Path "$run/classes","$run/dex","$run/screenshots" -Force | Out-Null
$jdk='C:/DevTools/jdk-21/bin';$sdk='C:/Android/Sdk';$qt='C:/Qt/6.11.2/android_arm64_v8a';$build="$sdk/build-tools/36.0.0";$androidJar="$sdk/platforms/android-36/android.jar";$qtJar="$qt/jar/Qt6Android.jar";$adb="$sdk/platform-tools/adb.exe"
function Run([string]$Executable,[string[]]$Arguments){if($Executable -eq $adb){$Arguments=@('-P',"$AdbPort")+$Arguments};& $Executable @Arguments;if($LASTEXITCODE -ne 0){throw "Command failed: $Executable"}}
$sources=@(Get-ChildItem "$root/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object FullName)
$bindings=@(Get-ChildItem "$qt/src/android/java/src/org/qtproject/qt/android/bindings" -Filter '*.java' | ForEach-Object FullName)
Run "$jdk/javac.exe" (@('--release','8','-Xlint:-options','-encoding','UTF-8','-cp',"$androidJar;$qtJar",'-d',"$run/classes")+$sources+$bindings+@("$PSScriptRoot/LyricWindowRegressionTest.java"))
Run "$jdk/jar.exe" @('cf',"$run/fixture.jar",'-C',"$run/classes",'.')
Run "$jdk/java.exe" @('-cp',"$build/lib/d8.jar",'com.android.tools.r8.D8','--min-api','28','--lib',$androidJar,'--output',"$run/dex","$run/fixture.jar",$qtJar)
Copy-Item -LiteralPath "$PSScriptRoot/lyric-regression-manifest.xml" -Destination "$run/AndroidManifest.xml"
Run "$build/aapt.exe" @('package','-f','-M',"$run/AndroidManifest.xml",'-I',$androidJar,'-F',"$run/unsigned.apk")
Push-Location "$run/dex"
try{Run "$build/aapt.exe" (@('add',"$run/unsigned.apk")+@(Get-ChildItem -Filter '*.dex' | ForEach-Object Name))}finally{Pop-Location}
Run "$build/apksigner.bat" @('sign','--ks',"$env:USERPROFILE/.android/debug.keystore",'--ks-pass','pass:android','--key-pass','pass:android','--out',"$run/fixture.apk","$run/unsigned.apk")
Run $adb @('-s',$DeviceSerial,'install','-r',"$run/fixture.apk")
Run $adb @('-s',$DeviceSerial,'shell','appops','set','org.floatmusic.lyricfixture','SYSTEM_ALERT_WINDOW','allow')
New-Item -ItemType Directory -Path "$run/backdrop" -Force | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/lyric-backdrop-manifest.xml" -Destination "$run/backdrop/AndroidManifest.xml"
Run "$build/aapt.exe" @('package','-f','-M',"$run/backdrop/AndroidManifest.xml",'-I',$androidJar,'-F',"$run/unsigned-backdrop.apk")
Push-Location "$run/dex"
try{Run "$build/aapt.exe" (@('add',"$run/unsigned-backdrop.apk")+@(Get-ChildItem -Filter '*.dex' | ForEach-Object Name))}finally{Pop-Location}
Run "$build/apksigner.bat" @('sign','--ks',"$env:USERPROFILE/.android/debug.keystore",'--ks-pass','pass:android','--key-pass','pass:android','--out',"$run/backdrop.apk","$run/unsigned-backdrop.apk")
Run $adb @('-s',$DeviceSerial,'install','-r',"$run/backdrop.apk")
$instrumentArgs=@('-P',"$AdbPort",'-s',$DeviceSerial,'shell','am','instrument','-w','-r')
if($Cases){if($Cases -notmatch '^[a-z,-]+$'){throw 'Invalid case list.'};$instrumentArgs+=@('-e','cases',$Cases)}
$result=@(& $adb @instrumentArgs org.floatmusic.lyricfixture/org.floatmusic.player.LyricWindowRegressionTest 2>&1 | Tee-Object -FilePath "$run/results.txt")
$result | Tee-Object "$run/results.txt"
$text=$result -join "`n"
$artifactMatch=[regex]::Match($text,'(?m)^INSTRUMENTATION_RESULT: artifactDir=((?:/storage/emulated/0|/sdcard)/Android/data/org\.floatmusic\.lyricfixture/files/run-\d+)\s*$')
if($artifactMatch.Success){Run $adb @('-s',$DeviceSerial,'pull',($artifactMatch.Groups[1].Value+'/.'),"$run/screenshots")}
Write-Output "Regression artifacts: $run"
if($text -notmatch 'uiPassed=true' -or $text -match 'FAIL|Process crashed|INSTRUMENTATION_FAILED'){throw "Lyric window regression failed: $run/results.txt"}
