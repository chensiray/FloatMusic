param([ValidateSet('windows','android')][string]$Target = 'windows', [switch]$Package)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
$stageRoot = 'C:\Dev\FloatMusic'
$env:PATH = 'C:\Qt\Tools\mingw1310_64\bin;C:\Qt\Tools\Ninja;C:\Qt\6.11.2\mingw_64\bin;' + $env:PATH
$env:JAVA_HOME = 'C:\DevTools\jdk-21'
$cmake = 'C:\Qt\Tools\CMake_64\bin\cmake.exe'
New-Item -ItemType Directory -Path $stageRoot -Force | Out-Null
if ([IO.Path]::GetFullPath($sourceRoot) -ne $stageRoot) {
    foreach ($entry in @('CMakeLists.txt','src','qml','android','tests','scripts','README.md','VERIFICATION.md','BACKLOG.md','CHANGELOG.md')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $entry))) { continue }
        Copy-Item -LiteralPath (Join-Path $sourceRoot $entry) -Destination $stageRoot -Recurse -Force
    }
}
Set-Location -LiteralPath $stageRoot
$buildDir = Join-Path $stageRoot "build/$Target"
if ($Target -eq 'windows') {
    $env:QT_PLUGIN_PATH = 'C:\Qt\6.11.2\mingw_64\plugins'
    & $cmake -S $stageRoot -B $buildDir -G Ninja '-DCMAKE_BUILD_TYPE=Debug' '-DCMAKE_PREFIX_PATH=C:/Qt/6.11.2/mingw_64' '-DCMAKE_CXX_COMPILER=C:/Qt/Tools/mingw1310_64/bin/g++.exe' '-DCMAKE_C_COMPILER=C:/Qt/Tools/mingw1310_64/bin/gcc.exe' '-DFLOATMUSIC_TESTS=ON'
} else {
    & $cmake --fresh -S $stageRoot -B $buildDir -G Ninja '-DCMAKE_BUILD_TYPE=Debug' '-DCMAKE_TOOLCHAIN_FILE=C:/Android/Sdk/ndk/27.2.12479018/build/cmake/android.toolchain.cmake' '-DCMAKE_PREFIX_PATH=C:/Qt/6.11.2/android_arm64_v8a' '-DQT_HOST_PATH=C:/Qt/6.11.2/mingw_64' '-DANDROID_SDK_ROOT=C:/Android/Sdk' '-DANDROID_NDK=C:/Android/Sdk/ndk/27.2.12479018' '-DCMAKE_FIND_ROOT_PATH=C:/Qt/6.11.2/android_arm64_v8a' '-DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF' '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-28' '-DCMAKE_MAKE_PROGRAM=C:/Qt/Tools/Ninja/ninja.exe' '-DFLOATMUSIC_ANDROID_OPENSSL=C:/Android/Sdk/android_openssl'
}
if ($LASTEXITCODE -ne 0) { throw 'CMake configuration failed.' }
& $cmake --build $buildDir --parallel 4
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
if ($Target -eq 'windows') {
    & 'C:\Qt\Tools\CMake_64\bin\ctest.exe' --test-dir $buildDir --output-on-failure
    if ($LASTEXITCODE -ne 0) {
        Get-ChildItem -LiteralPath $buildDir -Filter '*-tests.txt' | ForEach-Object { Get-Content -LiteralPath $_.FullName -Tail 20 }
        throw 'Tests failed.'
    }
    if ($Package) {
        $runtimeDir = Join-Path $stageRoot 'dist/windows'
        New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $buildDir 'FloatMusic.exe') -Destination $runtimeDir -Force
        & 'C:\Qt\6.11.2\mingw_64\bin\windeployqt.exe' --release --verbose 0 --qmldir (Join-Path $stageRoot 'qml') --dir $runtimeDir (Join-Path $runtimeDir 'FloatMusic.exe')
        if ($LASTEXITCODE -ne 0) { throw 'Windows deployment failed.' }
    }
} elseif ($Package) {
    & $cmake --build $buildDir --target apk --parallel 4
    if ($LASTEXITCODE -ne 0) { throw 'APK packaging failed.' }
}

