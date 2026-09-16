# 浮音 FloatMusic · 0.3

基于 Qt 6.11.2 / C++17 / QML 的 Windows 与 Android 音乐播放器。

当前版本支持本地音乐、网易云兼容 API 搜索、本地歌单、播放/暂停/进度拖动、上一首/下一首、音频输出选择和音量调节。Windows 仅运行一个实例。Android 使用独立系统悬浮页面和后台播放服务。

仓库：https://github.com/chensiray/FloatMusic

## Windows 使用步骤

1. 打开 `C:\Dev\FloatMusic\dist\windows`，双击 `FloatMusic.exe`。保留旁边的 DLL、plugins、qml 等文件，不能只复制 EXE。
2. 点击绿色音符图标打开播放器。按住图标拖动可以移动它；重复打开程序会唤起已有窗口。
3. 点击“导入音乐”，或把一首本地音频拖入图标/音乐界面。导入后加入当前歌单，不自动播放。
4. 在歌单中点击“播放”，使用播放/暂停和进度条。手动上一首/下一首循环切换；自然播放完成后续播下一首，到歌单末尾停止。加载失败会提示，不循环重试。
5. 在“我的歌单”中新建、重命名或删除歌单。至少保留一个歌单。筛选框按歌名/歌手过滤显示，不改变播放顺序。“移除”仅移出当前歌单；删除歌单也不删除音频副本。
6. 向下滚动可找到“音频输出”和音量滑块。可以跟随系统默认，或选定耳机/扬声器。
7. “收为图标”只收起；“退出应用”或关闭窗口会停止音乐并结束进程。

## 网易云搜索配置

使用用户提供的 NeteaseCloudMusicApi 兼容服务。这是第三方兼容接口，不是网易云官方开放平台 SDK；项目不内置或自动部署服务，也不提供账号登录、付费解锁或下载功能。

1. 点击“网易云搜索”标签。
2. 填写你自己的 API 服务根地址（优先使用 HTTPS；也支持本地/局域网 HTTP 服务），然后点击“保存地址”。地址不应包含账号密码、查询参数或单个接口路径。
3. 输入歌名或歌手，按回车或点击“搜索”。每次展示最多 30 条结果。
4. 点击结果右侧“+ 歌单”，加入当前选择的歌单；同一网易云歌曲在同一歌单内不重复添加。
5. 回到“我的歌单”，点击歌曲的“播放”。每次播放重新获取音频 URL；URL 不写入歌单，避免保存已过期链接。

服务需兼容以下响应：

- `GET /cloudsearch?keywords=...&type=1&limit=30`：`code: 200`，`result.songs` 内有数字 `id`、`name`、`ar[].name`（或 `artists[].name`）。
- `GET /song/url/v1?id=...&level=standard`：`code: 200`，`data[0].url` 为 HTTP(S) 音频地址。无可播放地址时明确提示，不尝试绕过权限。

接口参考：[NeteaseCloudMusicApi Enhanced 文档](https://docs-neteasecloudmusicapi.focalors.ltd/)。API 地址只保存在本机设置中，不随源码上传。手机不能用 `127.0.0.1` 访问电脑服务，应使用手机可访问的服务地址。

本轮按用户确认使用模拟 HTTP API 测试；没有配置真实服务，因此不宣称已验证真实网易云搜索或版权歌曲播放。

## Android 使用与验证范围

1. 安装 ARM64 APK，在主界面使用歌单和搜索。
2. 点击“收为图标”，首次需要授予显示悬浮窗权限。
3. 离开主界面后显示图标；点击图标打开独立悬浮播放页面，不再直接打开主界面。
4. 悬浮页面可播放、暂停、拖动进度、上一首/下一首、调音量；拖动顶部移动页面。大小滑块调整宽度，不透明度支持 40%–100%。这些设置保存在本机。
5. “主界面”打开完整歌单/搜索界面；进入主界面时悬浮层隐藏。导入通过系统文件选择器完成，之后返回悬浮页面。
6. 返回桌面后原生前台服务负责后台播放和续播，通知支持下一首。退出悬浮播放会停止服务并移除悬浮层。

0.3 已在电脑上交叉编译生成 APK；本轮手机未连接，悬浮交互、权限、后台续播与手机音频路由待真机复测。0.2 的真机结果不作为 0.3 的验证结果。

## 文件与歌单保存规则

- 本地导入支持 MP3、WAV、FLAC、OGG、M4A、AAC；每次一首，最大 30 MiB（31,457,280 字节）。验证扩展名、文件头和读取大小，具体编码能否播放由解码器决定。
- 本地音频复制到应用私有 `imports` 目录，原文件不修改、不上传。0.3 保留副本用于下次启动；从歌单移除时也保留副本，暂不自动清理磁盘。
- 歌单以 `playlists.json` 保存，使用原子替换；损坏文件保留备份。Windows 一般位于 `%LOCALAPPDATA%\FloatMusic\FloatMusic`。音量与 API 地址通过 QSettings 保存在本机。
- 网易云歌单条目只保存歌曲编号、名称及歌手，不保存临时播放地址。这里的歌单是本应用本地歌单，不同步网易云账号。
- 0.2 没有持久音乐库，因此升级后需要重新导入旧版临时音频。

## 构建与测试

主源码目录：`C:\Users\qw152\Documents\ChatGPT\音乐播放器`。脚本同步到 `C:\Dev\FloatMusic` 构建，避开 Qt 工具的中文路径问题。只修改主源码目录。

当前脚本采用本机路径：Qt 6.11.2、MinGW 13.1、CMake 3.30.5、Ninja、JDK 21、SDK 36、NDK 27.2.12479018。Android HTTPS 依赖现有 `C:\Android\Sdk\android_openssl`，脚本通过 `FLOATMUSIC_ANDROID_OPENSSL` 指定并将 SSL 库打入 APK。其他电脑需要调整 `scripts/build.ps1` 中的路径。

打开 PowerShell，依次执行：

```powershell
Set-Location 'C:\Users\qw152\Documents\ChatGPT\音乐播放器'
& .\scripts\build.ps1 -Target windows -Package
```

Windows 构建自动运行四组测试：文件导入策略、播放及界面退出、模拟 API 与歌单、单实例与崩溃恢复。测试隔离在独立应用数据目录，不修改用户歌单。全部通过后打包到 `C:\Dev\FloatMusic\dist\windows`。

安卓构建（不要求连接手机）：

```powershell
& .\scripts\build.ps1 -Target android -Package
```

生成 APK：`C:\Dev\FloatMusic\build\android\android-build\build\outputs\apk\debug\android-build-debug.apk`。连接手机后可使用 `adb install -r` 安装；本轮不执行手机安装。

## 代码结构

| 文件 | 职责 |
|---|---|
| `qml/Main.qml` | 界面、搜索和歌单操作、桌面图标 |
| `src/playercontroller.*` | 播放状态、输出设备、导入、歌曲切换、Android 桥接 |
| `src/playliststore.*` | 本地歌单与持久化 |
| `src/musicapi.*` | 网易云兼容 API、错误处理、过期搜索响应丢弃 |
| `src/singleinstance.*` | 进程锁与重复启动唤起 |
| `android/src/org/floatmusic/player/PlaybackService.java` | Android 播放、队列、独立悬浮页与后台服务 |
| `tests/` | 文件、播放、界面、API、持久化与多进程测试 |

更新记录见 [CHANGELOG.md](CHANGELOG.md)，待办见 [BACKLOG.md](BACKLOG.md)，验证范围见 [VERIFICATION.md](VERIFICATION.md)。构建产物、测试音频和个人设置不提交 Git。
