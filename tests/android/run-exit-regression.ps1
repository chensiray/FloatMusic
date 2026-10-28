param([string]$DeviceSerial='emulator-5580',[string]$Label='run',[int]$AdbPort=5037,[switch]$BuildOnly,[switch]$AllowPhysicalDevice)
$ErrorActionPreference='Stop'
if($Label -notmatch '^[a-zA-Z0-9-]+$'){throw 'Label must contain only letters, numbers and hyphens.'}
if($DeviceSerial -notmatch '^emulator-\d+$' -and -not $AllowPhysicalDevice){throw 'A physical device requires explicit -AllowPhysicalDevice.'}
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$run=Join-Path $root ('artifacts/v1.5-2026-10-25/exit/'+$Label+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path "$run/classes","$run/dex" -Force | Out-Null
$jdk='C:/DevTools/jdk-21/bin';$sdk='C:/Android/Sdk';$build="$sdk/build-tools/36.0.0";$androidJar="$sdk/platforms/android-36/android.jar";$adb="$sdk/platform-tools/adb.exe"
function Run([string]$Executable,[string[]]$Arguments){if($Executable -eq $adb){$Arguments=@('-P',"$AdbPort")+$Arguments};& $Executable @Arguments;if($LASTEXITCODE -ne 0){throw "Command failed: $Executable"}}
$sources=@(Get-ChildItem "$root/android/src/org/floatmusic/player" -Filter '*.java' | ForEach-Object FullName)
Run "$jdk/javac.exe" (@('--release','8','-Xlint:-options','-encoding','UTF-8','-cp',$androidJar,'-d',"$run/classes")+$sources+@("$PSScriptRoot/exit-fixture/QtActivity.java","$PSScriptRoot/AndroidExitRegressionTest.java"))
Run "$jdk/jar.exe" @("-J-Djava.io.tmpdir=$run",'cf',"$run/fixture.jar",'-C',"$run/classes",'.')
Run "$jdk/java.exe" @('-cp',"$build/lib/d8.jar",'com.android.tools.r8.D8','--min-api','28','--lib',$androidJar,'--output',"$run/dex","$run/fixture.jar")
Copy-Item -LiteralPath "$PSScriptRoot/exit-fixture/AndroidManifest.xml" -Destination "$run/AndroidManifest.xml"
Run "$build/aapt.exe" @('package','-f','-M',"$run/AndroidManifest.xml",'-S',"$root/android/res",'-I',$androidJar,'-F',"$run/unsigned.apk")
Push-Location "$run/dex"
try{Run "$build/aapt.exe" (@('add',"$run/unsigned.apk")+@(Get-ChildItem -Filter '*.dex' | ForEach-Object Name))}finally{Pop-Location}
Run "$build/apksigner.bat" @('sign','--ks',"$env:USERPROFILE/.android/debug.keystore",'--ks-pass','pass:android','--key-pass','pass:android','--out',"$run/fixture.apk","$run/unsigned.apk")
Write-Output "Exit fixture: $run/fixture.apk"
if($BuildOnly){return}
Run $adb @('-s',$DeviceSerial,'install','-r',"$run/fixture.apk")
Run $adb @('-s',$DeviceSerial,'shell','appops','set','org.floatmusic.exitfixture','SYSTEM_ALERT_WINDOW','allow')
Run $adb @('-s',$DeviceSerial,'shell','pm','grant','org.floatmusic.exitfixture','android.permission.POST_NOTIFICATIONS')
$result=@(& $adb '-P' $AdbPort '-s' $DeviceSerial 'shell' 'am' 'instrument' '-w' '-r' 'org.floatmusic.exitfixture/org.floatmusic.player.AndroidExitRegressionTest' 2>&1)
$instrumentExit=$LASTEXITCODE
$result | Set-Content -LiteralPath "$run/results.txt" -Encoding utf8
$result
$text=$result -join "`n"
Write-Output "Exit artifacts: $run"
if($instrumentExit -ne 0 -or $text -notmatch 'exitPassed=true' -or $text -notmatch '(?m)^INSTRUMENTATION_RESULT: passed=8\s*$' -or $text -notmatch '(?m)^INSTRUMENTATION_CODE: -1\s*$' -or $text -match 'FAIL|Process crashed|INSTRUMENTATION_FAILED'){throw "Android exit regression failed: $run/results.txt"}
