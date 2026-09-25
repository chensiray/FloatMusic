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

Windows 构建脚本运行四组 CTest：导入策略、播放器与界面、API 与歌单、单实例。测试使用独立测试数据目录。

0.5 的界面测试代码已随布局调整，但此版本交付未重新运行自动测试。历史通过记录不能作为当前全部测试通过的证明，具体范围见[验证说明](../VERIFICATION.md)。如果旧构建目录配置失败，可改用新的构建目录重新配置，不需要删除源码或应用数据。

`tests/live_api` 是单独的真实接口探测工具，不属于默认离线测试；使用方法见该目录 README。真实服务结果只代表测试时状态。

构建目录、测试音频、截图和日志位于 Git 忽略的目录。原始日志可能包含临时 CDN URL，请勿上传。

## 可选自定义音乐服务

应用允许配置兼容 API 的根地址，地址不应包含账号密码、查询参数或单个接口路径。默认接口实现见 `src/musicapi.cpp` 与 Android 的 `PlaybackService.java`。

自定义服务需要提供：

| 请求 | 响应要求 |
|---|---|
| `GET /cloudsearch?keywords=...&type=1&limit=30` | `code: 200`，`result.songs` 中包含数字 `id`、`name`、`ar[].name` 或 `artists[].name` |
| `GET /song/url/v1?id=...&level=...` | `code: 200`，`data[0].url` 为有效 HTTP(S) 音频地址 |
| `GET /lyric?id=...&tv=-1&lv=-1` | `code: 200`，`lrc.lyric`、可选 `tlyric.lyric`，或 `nolyric/uncollected` 状态 |

音质参数为 `standard`、`higher`、`exhigh`、`lossless`、`hires`。手机上的 `127.0.0.1` 指手机自身，不能用它访问电脑上的服务。

## 代码结构

| 位置 | 职责 |
|---|---|
| `qml/Main.qml` | Windows 悬浮卡片、共用展开区、桌面图标与缩放 |
| `qml/AndroidMain.qml` | Android 欢迎页与悬浮权限入口 |
| `src/playercontroller.*` | 播放状态、导入、收藏持久化、设备路由、Android 桥接 |
| `src/musicapi.*` | 搜索、歌词、音频地址和错误处理 |
| `src/playliststore.*` | 本地歌单与持久化 |
| `src/singleinstance.*` | Windows 单实例与重复启动唤起 |
| `android/src/org/floatmusic/player/` | Android 活动、后台服务、独立悬浮页 |
| `android/src/org/floatmusic/player/OverlayWindow.java` | 0.5 音乐卡片、共用展开区、等比缩放、窗口尺寸与焦点 |
| `tests/` | 策略、播放、UI、API、持久化和多进程测试 |

历史测试报告见 [VERIFICATION.md](../VERIFICATION.md)。报告按日期记录当时状态，未覆盖场景和用户反馈见 [BACKLOG.md](../BACKLOG.md)。
