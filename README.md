# 浮音 FloatMusic · 0.2 验证版

Qt 6.11.2 / C++17 / QML，支持 Windows x64 和 Android arm64-v8a。当前验证的是单首本地音频播放，不包含曲库、在线搜索或账号。

用户反馈及下次更新计划见 [BACKLOG.md](BACKLOG.md)。其中 Windows 退出入口、单实例和 Android 独立悬浮页面仅记录，本轮未修复。

## 第一步：直接体验 Windows 版

1. 按 `Win + E` 打开文件资源管理器。
2. 在地址栏输入 `C:\Dev\FloatMusic\dist\windows`，按回车。
3. 双击 `FloatMusic.exe`。旁边的 DLL、plugins 和 qml 等运行文件需要保留；不要只复制 EXE。
4. 点击绿色音符图标打开音乐界面，再点击“导入音乐”，选择一首本地音频。也可以从资源管理器拖入一个文件。
5. 等待歌曲名称、总时长出现，再点击“播放”。导入成功后不会自动发声。
6. 点击“暂停”，拖动进度条，松开后跳转；再次点击“播放”继续。
7. 点击“收为图标”返回固定大小的音符图标；按住图标拖动可移动它。
8. 点击图标打开音乐界面。音频输出下拉框可选择蓝牙耳机、扬声器或跟随系统默认；音量滑块支持 0–100%，0 为静音。连接/断开设备后列表自动更新，也可点击刷新。
9. 当前 Windows 退出入口存在用户反馈：窗口标题栏“×”会收为图标，退出操作不够明确，已记录在待办中，留待下次更新。

没有现成音频时，可使用本项目 `artifacts\demo-60s.wav`，它是自动生成的 60 秒轻提示音。

## 第二步：体验 Android 版

1. 已连接的手机已安装“浮音”。桌面打开它。
2. 点击“授权并开启系统悬浮窗”。在系统页面允许浮音显示悬浮窗，返回应用。已经授权后按钮为“开启系统悬浮窗”。
3. 点击悬浮图标打开音乐界面，再点击“导入音乐”。第一次出现通知授权时选择允许，可以在通知栏控制音乐。
4. 系统文件选择器中打开“下载内容”，选择 `FloatMusic-demo-60s.wav`，或自己的音频。选择文件需要系统界面，选择完成后回到音乐界面。
5. 点击播放/暂停；拖动进度条并松开完成跳转。
6. 在音乐界面内，悬浮图标自动隐藏；按返回键、Home 或“收为图标”回到后台，图标恢复显示。按住图标拖动，轻点图标重新打开音乐界面。图标大小固定，暂不提供大小、透明度调节。
7. 回到桌面或锁屏，音乐应继续。主 Activity 的返回键也会退到后台。
8. 点击音乐界面右上角“×”或通知中的“退出”，停止音乐并移除图标。音量滑块调整本应用播放音量，并记住设置；手机系统媒体音量仍会影响最终响度。

Android 0.2 的图标只负责移动和打开音乐界面，导入统一使用音乐界面的系统文件选择器。Windows 可把单个音频拖入图标或音乐界面。Android 输出设备选择通过系统音频路由请求实现，具体设备能否切换由系统决定，界面会显示播放时的实际输出。

## 第三步：明确导入规则

- 每次一首，支持 `.mp3`、`.wav`、`.flac`、`.ogg`、`.m4a`、`.aac`，扩展名不区分大小写。
- “30M”按 30 MiB 实现，即 `31,457,280` 字节；等于上限允许，超过拒绝。
- 校验扩展名、文件大小、文件头；复制过程中再次限制实际读取量，再由播放器检查能否解码。改扩展名不能把普通文本变成可播放音频。
- 音频复制到应用私有目录，原文件不修改、不上传服务器。更换音频和正常退出会清理当前副本。强制结束进程时可能留下临时副本；当前不是持久音乐库。
- 损坏文件、受保护音频或设备不支持的具体编码会提示错误。已实测的音频编码为 PCM WAV，其他格式仍需要用真实样本继续做兼容性测试。

## 第四步：了解代码放在哪里

源代码主目录：`C:\Users\qw152\Documents\ChatGPT\音乐播放器`。

构建脚本会把源码同步到 `C:\Dev\FloatMusic` 后编译。这是为了避开这台机器上 Qt 工具处理中文路径时的问题。日常修改主目录；不要同时修改两份源码，下次同步会覆盖构建副本中的同名文件。已有 `C:\Dev\QtEnvCheck` 没有修改。

| 文件 | 职责 |
|---|---|
| `CMakeLists.txt` | 双端构建、QML、Android 包配置、测试目标 |
| `qml/Main.qml` | Windows 悬浮窗与 Android 入口页面、拖放、播放按钮、进度条 |
| `src/playercontroller.*` | QML 状态与命令、Windows 播放和异步导入、Android JNI 桥接 |
| `src/audiofilepolicy.*` | 桌面端扩展名、大小、文件头校验 |
| `android/src/org/floatmusic/player/PlayerActivity.java` | 系统文件选择器、权限、返回键后台行为 |
| `android/src/org/floatmusic/player/PlayerBridge.java` | Qt 与 Android 服务通信和状态快照 |
| `android/src/org/floatmusic/player/PlaybackService.java` | Android 原生悬浮窗、播放器、音频焦点、通知、后台生命周期 |
| `tests/` | 文件限制、真实解码播放、暂停、跳转、QML 窗口及拖放测试 |
| `scripts/build.ps1` | 按本机已安装的工具路径编译和打包 |

界面使用 QML，业务入口使用 C++。Windows 使用 Qt Multimedia；Android 使用 Java 平台桥接实现系统悬浮窗和 `mediaPlayback` 前台服务，服务持有原生播放器。切换应用或锁屏时无需保持 QML 界面在前台。这里的“前台服务”指带通知的系统服务，应用界面仍可处于后台。参考 [Android 媒体播放服务说明](https://developer.android.com/develop/background-work/services/fgs/service-types#media)。

## 第五步：自己重新编译

当前脚本针对已经配置好的本机环境：Qt 6.11.2、MinGW 13.1、CMake 3.30.5、Ninja、JDK 21、SDK 36、NDK 27.2.12479018。无需重新安装。

1. 右键开始菜单，打开“终端”，使用 PowerShell 标签页。
2. 复制下面一行，按回车：

```powershell
Set-Location 'C:\Users\qw152\Documents\ChatGPT\音乐播放器'
```

3. 编译、测试并打包 Windows：

```powershell
& .\scripts\build.ps1 -Target windows -Package
```

4. 等待结束。测试输出应显示 `100% tests passed`。运行文件位于 `C:\Dev\FloatMusic\dist\windows\FloatMusic.exe`。
5. 回到源目录，编译 Android：

```powershell
Set-Location 'C:\Users\qw152\Documents\ChatGPT\音乐播放器'
& .\scripts\build.ps1 -Target android -Package
```

6. 看到 `BUILD SUCCESSFUL` 后，APK 位于 `C:\Dev\FloatMusic\build\android\android-build\build\outputs\apk\debug\android-build-debug.apk`。
7. 手机连接电脑、保持 USB 调试授权，运行：

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' devices
```

8. 设备后面显示 `device` 后安装：

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' install -r 'C:\Dev\FloatMusic\build\android\android-build\build\outputs\apk\debug\android-build-debug.apk'
```

9. 看到 `Success` 后重新打开浮音。安装更新会重启应用，原来的播放会停止。

用 Qt Creator 看代码时，可以打开主目录的 `CMakeLists.txt`；编译建议先用上述脚本。若要在 Creator 里编译，打开同步后的 `C:\Dev\FloatMusic\CMakeLists.txt`，选择 Desktop Qt 6.11.2 MinGW 64-bit 或 Qt 6.11.2 for Android arm64-v8a 套件，使用各自独立构建目录。

## 验证边界

这是 Debug 功能验证包。Windows 自动测试与 Android 真机记录见 `VERIFICATION.md`。

已验证 Home、返回键和短时锁屏播放；不保证被用户“强制停止”、厂商清理任务或系统杀进程后还能播放。强制停止应用后应停止播放。不同品牌的长时间省电策略需要后续专项测试；若出现熄屏很久后被停止，可在该手机的应用电池设置里允许浮音后台运行。

下一步应先用几首真实 MP3/FLAC/M4A 做兼容性验收，再做曲库、搜索和视觉完善。

