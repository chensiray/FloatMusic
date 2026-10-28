# Android 音源解析器本地测试

`1.5.0-preview.2` 的默认歌词窗口回归增加至35项：新增 `player-description-fold`（长简介展开收起、按钮实际高度、短简介、在线详情）与 `player-settings-navigation`（六类设置、返回、独立歌词直达及中英文浅深色）。复制场景必须置于触摸检查末尾，避免系统剪贴板预览拦截后续手势。

1.4 新增无需手机的回归入口 `./tests/android/run-v14-contract-tests.ps1`：编译真实原生源码，检查语言目录、歌词行数/渐隐/时间轴/小屏排布及原有队列、睡眠定时、通知策略。脚本使用已有 JSON 测试库，不下载依赖、不连接设备；悬浮歌词触摸与生命周期的真机验收项见 [Android 1.4 验收说明](../../docs/Android-1.4-验收说明.md)。

歌词跳动及播放器切句跟随的原生回归：`./tests/android/run-lyric-regression.ps1 -DeviceSerial emulator-5580`。默认只接受已启动且有硬件加速的本地模拟器；用户授权真机测试后，必须显式传手机序列号与 `-AllowPhysicalDevice`。它将实际生产 Java 与隔离 Instrumentation 打包，不启动 Qt 或播放音频，并在测试包自己的目录保存截图。检查窗口身份、开关动效、横向锚点、无关刷新、相邻句跟随与手动返回，详见[歌词修复说明](../../docs/Android-1.4.0-preview.1-歌词修复.md)。

`preview.2` 将原生回归扩展为 13 项：增加文字栅格稳定、三行／九行零可见表面重排、硬件合成、静止停止重绘及实际文字拖动／行间透传。支持 `-AdbPort` 使用独立调试服务。主机软件图形后端若崩溃，可用模拟器 `-gpu host -feature -Vulkan` 后端复验，见[滚动优化说明](../../docs/Android-1.4.0-preview.2-滚动优化.md)。

`preview.3` 扩展为 21 项，新增两处真实动画帧间隔、九行、160 行长双语文本、静止刷新、跨 UID 点击透传、关闭动效后的手动返回，以及无障碍与键盘浏览。`-Cases 'floating-frame-pacing,player-frame-pacing'` 可选择场景。帧间隔预算按 60Hz 两帧加采样误差（35ms）检查 p95，并记录屏幕帧回调，以区别掉帧与整数像素停顿。整列高度检查排除视口居中留白，关注文字内容是否因高亮重新排版。测试额外安装 `org.floatmusic.lyricbackdrop` 作为无个人内容的独立背景，系统输入只注入合成歌词区域；完成后可移除它和 `org.floatmusic.lyricfixture`。详情见[真机动效优化说明](../../docs/Android-1.4.0-preview.3-真机动效优化.md)。

1.4 发布后审查将默认场景扩展到 24 项：`floating-drag-cache` 在九行短句下连续横向移动 80 次，统计文字位图重建与主线程处理时间；`floating-cache-invalidation` 检查屏幕边缘缩小、返回后恢复字号，以及主题和字号变化；`player-rebuild-no-lyrics` 在滚动中换成无逐句歌词的新曲，检查旧动画停止、新提示保持可见。拖动耗时用于同环境比较，不作为设备通用速度承诺。`OverlayV12ContractTest` 同时覆盖英文搜索反馈和音质说明的最终值翻译。

1.5 将原生窗口回归扩展到 28 项：新增 `floating-opacity-settings`（真实像素透明度、动画中调节、重建保存、0%边缘透传）、`floating-lock-recenter`、`floating-settings-ui`（真实滑杆键盘操作与中英文浅深色截图）、`floating-empty-touch-through`（首次无任何可见文字时的跨应用透传）。默认仅使用隔离模拟器；不会覆盖安装正式浮音。

`1.5.0-preview.1` 将默认场景扩展到 33 项：`player-bottom-visible` 在 16/18/30 字号与 0.8/1/1.5 行距下实际上下拖动、惯性到底，检查末句字形与父窗口裁切；`player-copy-all`、`player-copy-description`、`player-copy-drag`、`player-copy-ranking` 检查真实长按、整首歌词、完整简介、空内容保护、拖动不误复制与榜单简介短击打开。复制用例放在末尾，并等待 Android 系统剪贴板预览消失后再对底部卡片注入下一次手势，避免命中系统窗口。纯文本复制同时由 `OverlayV12ContractTest` 检查大小写元数据、Unicode、缺失译文与换歌隔离。

开启页退出另运行 `run-exit-regression.ps1 -DeviceSerial emulator-5580`，8 项覆盖无服务、主／工作线程、重复退出、刚请求启动服务、实际播放、暂停保存后重开、只有服务以及普通返回。实际 Android Activity／任务、MediaPlayer、服务、通知与悬浮窗均真实运行；QtActivity 的原生加载超类用测试 Activity 替换，故不能把此 fixture 当作完整 ARM64 APK 的真机验收。边界见 [exit-fixture/README.md](exit-fixture/README.md)。桌面 `player_tests::exitTerminatesProcess` 还直接加载 AndroidMain.qml 的真实退出按钮，验证主动退出不再被关闭事件否决。

在桌面 JDK 21 运行 APK 使用的 `AudioResolver.java` 与 `PlaybackService` 实际调用的 `TrackState.java`，由本地 HTTP 服务提供固定响应，不连接真实音源、读取用户配置或操作手机。

保留此前六项网易云用例，覆盖 GD 音质参数与签名地址、错误音频/HTTP 523/Meting 重定向回退、自定义 API 独占、全部失败与冷却、取消及空 Meting 数组。另覆盖 QQ 普通音质和同 MID 备用、酷我原址优先与同 ID 中转备用、签名参数、来源排除、上游错误、ID 边界、网易云自定义服务对其他平台的隔离、请求中取消、总时限，以及 QQ CDN 的 HTTPS 等价地址白名单。

`TrackStateTest` 覆盖三平台完整身份、同数字 ID 的来源区分、非法/冲突身份、暂停现场保存字段白名单、临时 URL 排除、本地导入路径和跨平台音质规则。此测试不覆盖 Android 的播放器解码、通知、音频焦点和后台生命周期。

在仓库根目录打开 PowerShell 运行以下脚本；可用 `-JdkRoot` 指定本机 JDK。Android 自带 `org.json`，桌面 JVM 使用本地测试 JSON 库，不作为 APK 依赖。脚本依次查找 `artifacts/android-resolver-test/json.jar` 与 `artifacts/audio-source-integration/java-test/json.jar`，也可用 `-JsonJar` 指定已有的库。

```powershell
.\tests\android\run-resolver-tests.ps1
```

1.1 将本地回归扩展到 QQ / 酷我逐档请求与回退、音频头和格式规格、曲目身份及目标时长、独立偏好迁移、同曲替换的最新进度，以及加载和普通 seek 的独立身份。成功输出 23 组解析器 / 状态策略 `PASS`，退出码 0。默认 CTest 不执行此程序；本次运行结果见[验证说明](../../VERIFICATION.md)。

## 悬浮列表真机回归

`OverlayScrollTest.java` 是独立 Instrumentation，反射创建已安装 APK 的实际 `OverlayWindow`，通过系统触摸输入检查歌单、歌曲搜索及歌单搜索的上下滚动，以及多选按钮、复选框和多选时的滚动。测试只使用内存样本，不启动 Qt、请求在线服务或写入用户歌单。

手机需已安装浮音、授权悬浮窗和 USB 调试，并解锁屏幕。测试包须与浮音使用同一调试签名；默认使用 Android 标准调试密钥及公开默认口令，不支持正式签名包。运行前会重启目标进程；测试结束后可重新打开浮音。

```powershell
.\tests\android\run-scroll-test.ps1 -DeviceSerial '手机序列号'
```

按本机配置修改 `-SdkRoot`、`-JdkRoot`、`-BuildTools`、`-KeyStore`。脚本编译和安装临时测试包，运行后自动移除该测试包；日志与中间产物只保留在忽略目录 `artifacts/android-scroll-test/`，可以用 `-OutputDir` 修改。

此回归在完成布局后直接滑动，不等待面板回顶动画，用于发现第一次滑动被吞掉的问题。返回顶部后的按钮检查会等待原生边缘效果结束。脚本只有三种列表均通过双向滚动且多选检查通过时才成功；不覆盖真实搜索返回、音频播放、排序落点保存、所有设备或全部缩放比例。

## 0.9 悬浮页状态与控件

`run-overlay-v09-contract-test.ps1` 使用实际 Java 代码检查四组本地状态契约；`run-overlay-v09-ui-test.ps1` 对已安装的 0.9 APK 检查五组实际控件，包括三曲库多选、空选、混合歌曲身份、音质和输入保留、触摸期间的结果刷新。工具配置、英文输出目录、可选控件图与真机命令见 [0.9 悬浮 UI fixture](overlay-v09-fixture.md)。

## 1.1 悬浮页状态与控件

`run-overlay-v11-contract-test.ps1` 编译实际生产 Java 与原生 fixture，运行 11 组本地状态契约。`run-overlay-v11-ui-test.ps1 -DeviceSerial '手机序列号' -Screenshots` 对已安装的 1.1 APK 检查 8 个真实控件场景，包括独立音质、竖直音量与小卡片、10–30 字号、歌词模式/长句、服务折叠、0.8–3.0 倍行距及手动翻看位置。使用隔离偏好与内存样本，不请求在线音源；测试后自动移除临时测试包。

两个脚本都支持本机工具目录；真机脚本可用 `-AdbPort` 指定 ADB 端口。场景、截图和覆盖边界见 [1.1 悬浮 UI fixture](overlay-v11-fixture.md)。
