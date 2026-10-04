# 开发与构建

此文档供从源码构建 FloatMusic 的开发者使用。产品功能和使用方式见 [README](../README.md)。

## 工具链

当前验证使用 Qt 6.11.2、C++17、CMake 3.30.5、Ninja；Windows 使用 MinGW 13.1。Android 使用 JDK 21、SDK 36、NDK 27.2.12479018，目标为 ARM64，最低 API 28。

Qt 需安装 Quick、QuickControls2、Multimedia、Concurrent、Network 模块；运行桌面测试还需 Qt Test。Android HTTPS 需要 Qt Android OpenSSL bundle。

## 首次从源码运行

1. 将仓库克隆或解压到英文路径，例如 `C:\Dev\FloatMusic`，避免部分构建工具对中文路径的兼容问题。
2. 安装对应 Qt 桌面组件、MinGW、CMake 和 Ninja。若构建 Android，还需安装 Android ARM64 Qt 组件并配置 JDK、SDK、NDK 和 OpenSSL。
3. 在 Qt Creator 中打开根目录的 `CMakeLists.txt`，选择 Windows MinGW 或 Android ARM64 构建套件。
4. Android 项目需在 CMake 配置中设置 `FLOATMUSIC_ANDROID_OPENSSL`，指向含 `android_openssl.cmake` 的目录。
5. 完成配置后构建并运行。Windows 首先显示音符图标；Android 显示欢迎页，通过“显示悬浮窗”继续操作。

源代码压缩包不能直接运行。需要给其他电脑使用时，应生成包含 Qt 运行库和插件的完整目录；Android 需生成 APK。

## 在 Windows 上构建

`scripts/build.ps1` 包含维护者机器的工具路径。首次使用前，请按本机安装位置修改 Qt、编译器、CMake、Ninja、JDK、SDK、NDK 和 OpenSSL 路径，并检查暂存目录 `C:\Dev\FloatMusic` 未被其他项目使用。

脚本会把源码复制到该英文路径，避开部分工具对中文路径的兼容问题。请修改仓库中的源码，不要修改暂存副本。

在仓库根目录打开 PowerShell：

```powershell
# 编译、运行桌面测试并打包
.\scripts\build.ps1 -Target windows -Package

# 交叉编译并打包 Android，无需连接手机
.\scripts\build.ps1 -Target android -Package
```

默认产物：

- Windows：`C:\Dev\FloatMusic\dist\windows`，分发时保留完整目录。
- Android：`C:\Dev\FloatMusic\build\android\android-build\build\outputs\apk\debug\android-build-debug.apk`。

Android 当前构建的是调试包。正式分发所需的签名、密钥保管及发布流程需另行配置，不应将签名密钥提交到仓库。

## 测试

Windows 构建脚本运行七组 CTest：导入策略、播放器与界面、API 与歌单、单实例、多曲库 API、混合歌单文档、多曲库控制器。测试使用独立测试数据目录；网易云专项 fixture 明确选择单库，多曲库 fixture 显式配置本地服务，默认不访问公网。

0.8 在用户追加授权后完成四组 CTest，全部通过；新增排行榜回归及欢迎页横竖屏截图检查，单独实测排行榜与热歌榜读取。范围与限制见[QtTest 验证记录](QtTest-0.8-验证记录.md)及[验证说明](../VERIFICATION.md)。配置和构建时也应将终端工作目录切换到英文暂存路径，避免工具对中文当前目录的兼容问题。

Qt Quick 界面使用 `player_tests -platform offscreen` 和 `QT_QUICK_BACKEND=software` 离屏渲染，设置 `FLOATMUSIC_TEST_ARTIFACTS` 可保存截图。排行榜联网检查需显式设置 `FLOATMUSIC_LIVE_TESTS=1`，仅运行 `library_tests liveRankings`；默认 CTest 跳过联网检查。

`tests/live_api` 是单独的真实接口探测工具，不属于默认离线测试；使用方法见该目录 README。真实服务结果只代表测试时状态。

0.9 的匿名实测需显式设置 `FLOATMUSIC_LIVE_MULTISOURCE=1`。`multisource_api_tests liveAnonymousQq liveAnonymousKuwo` 检查搜索、解析、歌词；指定 `FLOATMUSIC_QT_PROBE` 为 `tests/music_api_playback_probe.cpp` 编译出的探针时还检查实际解码、暂停、跳转和继续播放。`liveAnonymousQqBackup` 单独检查 QQ 备用解析与 HTTPS 音频头。`multisource_controller_tests liveMixedPlaylistPlayback` 使用实际控制器检查 QQ/酷我混合歌单切歌、歌词、普通音质和恢复；这些测试音量为零，使用隔离数据。默认 CTest 跳过上述联网项。

备用音源回归包含 HTTP 523、错误音频、超时、冷却、取消、签名地址及播放器解码失败的换源。真实解析和播放分别运行 `library_tests liveResolveAudio liveAudioSourcesPlayback`，需设置 `FLOATMUSIC_LIVE_TESTS=1`；`FLOATMUSIC_LIVE_SONG`、`FLOATMUSIC_LIVE_QUALITY` 可指定样本，`FLOATMUSIC_LIVE_EXCLUDED=gd,byfuns` 可对照 INJAHOW。默认解析器遵循系统代理配置，实测网络条件见[备用音源报告](备用音源接入与实测-2026-10-01.md)。

Android 解析器可在桌面 JVM 使用本地 HTTP 响应测试，无需连接手机，步骤见[Android 解析器测试](../tests/android/README.md)。该测试不覆盖 `PlaybackService` 的系统音频焦点、通知和后台生命周期；这些需另行真机验收。

0.9 的 `run-resolver-tests.ps1` 同时执行实际 `AudioResolver` 的 22 项解析回归与服务实际调用的 `TrackState` 的 6 项身份、暂停现场和音质规则检查。`run-overlay-v09-contract-test.ps1` 编译实际悬浮页与原生 fixture，执行四组本地 UI 状态契约。五组实际控件检查另见 [Android 0.9 悬浮 UI fixture](../tests/android/overlay-v09-fixture.md)，需先安装对应 APK。

Android 原生悬浮列表使用独立 Instrumentation 检查已安装 APK，通过系统触摸输入覆盖歌单、歌曲搜索、歌单搜索及多选模式。运行条件和命令见[悬浮列表真机回归](../tests/android/README.md#悬浮列表真机回归)；仅使用内存样本，结束后移除临时测试包，需重新打开浮音。

构建目录、测试音频、截图和日志位于 Git 忽略的目录。原始日志可能包含临时 CDN URL，请勿上传。

## 可选自定义音乐服务

应用允许配置网易云兼容 API 的根地址，地址不应包含账号密码、查询参数或单个接口路径。默认音频解析实现见 `src/musicapi.cpp` 与 Android 的 `AudioResolver.java`；Android 的 `PlaybackService.java` 负责播放和换源。网易云默认模式使用 GD、原接口、INJAHOW 的有限回退；网易云自定义模式仅访问配置的服务。

两端 0.9 的其他曲库服务均不受该地址影响。自定义服务需要提供：

| 请求 | 响应要求 |
|---|---|
| `GET /cloudsearch?keywords=...&type=1&limit=30` | `code: 200`，`result.songs` 中包含数字 `id`、`name`、`ar[].name` 或 `artists[].name` |
| `GET /search?keywords=...&type=1000&offset=0&limit=30` | `code: 200`，`result.playlists` 提供 `id`、`name`、`description`、`trackCount`、`creator.nickname` |
| `GET /toplist` | `code: 200`，`list` 提供榜单 `id`、`name`，可选 `description`、`trackCount`、`updateFrequency`、`updateTime`；榜单 ID 复用歌单详情 |
| `GET /playlist/detail?id=...` | `code: 200`，`playlist` 提供歌单信息及 `tracks` 或 `trackIds`；提供完整 ID 列表与总数便于判断完整度 |
| `GET /song/detail?ids=ID1,ID2,...` | `code: 200`，`songs` 提供歌曲信息；按每批最多 100 首补全详情，单张在线歌单最多读取 2000 首 |
| `GET /song/url/v1?id=...&level=...` | `code: 200`，`data[0].url` 为有效 HTTP(S) 音频地址 |
| `GET /lyric?id=...&tv=-1&lv=-1` | `code: 200`，`lrc.lyric`、可选 `tlyric.lyric`，或 `nolyric/uncollected` 状态 |

音质参数为 `standard`、`higher`、`exhigh`、`lossless`、`hires`。手机上的 `127.0.0.1` 指手机自身，不能用它访问电脑上的服务。

## 代码结构

| 位置 | 职责 |
|---|---|
| `qml/Main.qml` | Windows 悬浮卡片、共用展开区、桌面图标与缩放 |
| `qml/AndroidMain.qml` | Android 欢迎页与悬浮权限入口 |
| `src/playercontroller.*` | 播放状态、音频导入、设备路由、Android 桥接 |
| `src/playerlibrary.cpp` | 歌单搜索与详情状态、批量整理、导入导出和双端操作入口 |
| `src/musicapi.*` | 歌曲与歌单搜索、歌单详情、歌词、音频地址和错误处理 |
| `src/playliststore.*` | 本地歌单、收藏迁移、排序、批量操作与持久化 |
| `src/playlistdocument.*` | JSON 歌单解析与导出、链接识别、容量限制和本地引用校验 |
| `src/singleinstance.*` | Windows 单实例与重复启动唤起 |
| `android/src/org/floatmusic/player/` | Android 活动、后台服务、独立悬浮页 |
| `android/src/org/floatmusic/player/OverlayWindow.java` | 音乐卡片、歌单操作面板、共用展开区、等比缩放、窗口尺寸与焦点 |
| `android/src/org/floatmusic/player/AudioResolver.java` | 在线音源、音频头探测、有限回退、冷却与取消 |
| `android/src/org/floatmusic/player/TrackState.java` | 三平台曲目身份、保存字段白名单、音质与来源规则 |
| `android/src/org/floatmusic/player/PlaylistDocuments.java` | Android 歌单文件选择、读取与保存 |
| `tests/` | 策略、播放、UI、API、持久化和多进程测试 |

历史测试报告见 [VERIFICATION.md](../VERIFICATION.md)。报告按日期记录当时状态，未覆盖场景和用户反馈见 [BACKLOG.md](../BACKLOG.md)。
