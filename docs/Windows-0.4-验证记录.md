# 浮音 Windows 0.4 验证记录

日期：2026-09-20。基线：0.3.0（`d507dbb`）。本轮仅 Windows 开发与验证，不扩展歌单、不生成 Android APK、不上传 GitHub。

## 已实现

- 内置歌名搜索：默认无需配置服务器，最多显示 30 条；搜索结果直接播放，不自动加入歌单。保留原有可选兼容 API 配置。
- 五档音质：standard / higher / exhigh / lossless / hires，默认 standard，选择保存在本机。切换时重新解析地址，尽量保留进度和暂停状态；地址解析失败保留原音源。
- 原文、译文及合并歌词展示；歌词请求与播放分离，可独立重试。暂无歌词、纯音乐、无译文、连接失败分别提示。当前是可滚动文字，不做逐句高亮。
- 错误处理：HTTP 状态、业务 code、超时、域名解析、TLS、格式错误、无效播放地址与音频解码/连接失败。接口响应上限 2 MiB、接口等待上限 15 秒、音频初次加载上限 20 秒。
- 不改写第三方返回的音频 URL；不追加 `.mp3`；歌词与歌曲名称按普通文本显示。
- 复用浮音原有播放、暂停、进度、输出设备、音量和悬浮图标。未添加在线歌单、推荐、榜单或下载功能。

## 验证结果

环境：Windows 11，Qt 6.11.2，MinGW 13.1，FFmpeg 7.1.5。自动播放测试采用静音或静音测试音频，没有将其描述为真人听感确认。

### 本机回归

最终 Windows CTest：**4/4 测试组通过，0 失败**。

| 测试组 | 覆盖 |
|---|---|
| import_policy | 格式、文件头、大小边界等原有导入规则 |
| player_integration | 本地播放/暂停/进度、音量/输出、退出、图标、QML 搜索/音质/歌词控件 |
| search_and_playlists | 原有兼容 API 与歌单回归、新内置协议、五档参数、错误响应和独立试听 |
| single_instance | 原有单实例与异常退出恢复 |

新增关键断言：

- 中文、`&`、`+` 搜索参数正确编码；快速搜索只显示最后一次结果。
- 第三方纯文本地址原样保留签名与扩展名，五个 level 参数正确。
- HTTP 503、业务拒绝、错误 JSON、缺失歌曲结构、超大响应、请求超时、连接失败、无可用音源均显示错误。
- 直接试听不会新增歌单条目。
- 暂停在约 4 秒时切换音质，加载后仍暂停且保留位置。
- 切歌后迟到的旧歌词不覆盖新歌词。
- 获取新音质地址失败时原歌曲继续播放；可重试恢复。
- 歌词接口失败不打断播放，可重试；纯音乐状态可正常显示。

测试过程中捕获并修正了歌词标签宽度的 QML 绑定循环，最终 QML 引擎警告断言通过。先前 CTest 播放器测试的控制台启动异常本轮未再复现；测试改用明确文件日志。离屏 Qt 字体路径提示及打包工具的可选 D3D12 DLL 提示仍可能出现；本轮截图文本正常、测试通过，未验证 D3D12 渲染。

### 真实 API 与播放器

调用浮音正式 `MusicApi` 和 `PlayerController`，不是仅运行之前的独立探测程序。使用进程默认网络配置；没有新增应用代理设置、账号或 Cookie 配置。

歌曲样本：`347230`《海阔天空》。从真实搜索结果选择后，五档均通过播放、暂停、跳到约第 10 秒和续播。

| 请求音质 | 实际解码结果 | 本次播放测试 |
|---|---|---|
| standard | MP3 / 128 kbps，约 326.034 秒 | 通过 |
| higher | MP3 / 192 kbps，约 326.034 秒 | 通过 |
| exhigh | MP3 / 320 kbps，约 326.034 秒 | 通过 |
| lossless | FLAC / 44.1 kHz / 16 bit，326 秒 | 通过 |
| hires | FLAC / 44.1 kHz / 16 bit，326 秒 | 通过，但不代表取得 Hi-Res 规格 |

本次 Hi-Res 和无损仍返回相同规格，界面显示请求档位及实际解码格式，并提醒可能回落。

歌词：`347230` 返回原文 1,241 字符、无译文；`4337372`《Yesterday (Remastered)》返回原文 938 字符、译文 409 字符，均通过新接口获取。验证日志只记字符数，不保存歌词全文。

### 真实 QML 交互

通过正式 QML 界面输入歌名并触发搜索，在可见结果中模拟鼠标点击“播放”，确认播放状态和歌词获取，切换歌词标签并检查非空文字。独立用例通过，无 QML 引擎警告。截图在 `artifacts/verification-0.4/`。

联网测试默认不放入每次 CTest 的自动请求范围，因此常规日志中两个 live 用例显示为跳过；本轮已分别显式开启并运行通过。

## 交付与复现

- 可运行程序：`C:\Dev\FloatMusic\dist\windows\FloatMusic.exe`。保留同目录 DLL、qml、plugins 等依赖，不要只复制 EXE。
- 包内 EXE 已与本次构建结果核对 SHA-256 一致：`E555D4A000153A1D57A839F7CA1CAAF9E741060212F618E62A48EB01834F4F2C`。
- 补充启动检查已通过：从打包目录启动 EXE，等待 3 秒后进程仍正常运行、响应状态为 true；没有自动播放音频。此前因自动审批额度限制未执行的启动检查现已完成。
- 回归构建：在仓库根目录运行 `./scripts/build.ps1 -Target windows -Package`。
- 联网控制器测试：在配置好 Qt/MinGW PATH 的 PowerShell 中设置 `$env:FLOATMUSIC_LIVE_TESTS='1'`，运行 `C:\Dev\FloatMusic\build\windows\library_tests.exe liveBuiltinWindows -o C:/Dev/FloatMusic/build/windows/live-0.4-tests.txt,txt`。
- 联网界面测试：同样开启该变量，设置 `$env:QT_QUICK_BACKEND='software'`，运行 `player_tests.exe -platform offscreen liveWindowSearchAndLyrics -o C:/Dev/FloatMusic/build/windows/live-ui-0.4.txt,txt`。可设置 `FLOATMUSIC_TEST_ARTIFACTS` 为英文截图目录。
- 结果日志：`artifacts/verification-0.4/LastTest.log`、`player-tests.txt`、`library-tests.txt`、`instance-tests.txt`、`live-0.4-tests.txt`、`live-ui-0.4.txt`。

截图和原始日志保存在 Git 忽略的 artifacts 目录；媒体日志可能含临时 CDN 地址，不应当作固定音源或上传仓库。

## 验证边界

没有进行 Android、全部曲库、长时间连续播放或本轮蓝牙耳机真人听感验收。接口可能随时间、歌曲和网络改变；提供了具体错误与重试入口，不保证每首歌都能提供五种实际音质。本轮代码与文档留在本地，尚未提交或推送 Git。
