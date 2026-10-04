# Android 音源解析器本地测试

在桌面 JDK 21 运行 APK 使用的 `AudioResolver.java` 与 `PlaybackService` 实际调用的 `TrackState.java`，由本地 HTTP 服务提供固定响应，不连接真实音源、读取用户配置或操作手机。

保留此前六项网易云用例，覆盖 GD 音质参数与签名地址、错误音频/HTTP 523/Meting 重定向回退、自定义 API 独占、全部失败与冷却、取消及空 Meting 数组。另覆盖 QQ 普通音质和同 MID 备用、酷我原址优先与同 ID 中转备用、签名参数、来源排除、上游错误、ID 边界、网易云自定义服务对其他平台的隔离、请求中取消、总时限，以及 QQ CDN 的 HTTPS 等价地址白名单。

`TrackStateTest` 覆盖三平台完整身份、同数字 ID 的来源区分、非法/冲突身份、暂停现场保存字段白名单、临时 URL 排除、本地导入路径和跨平台音质规则。此测试不覆盖 Android 的播放器解码、通知、音频焦点和后台生命周期。

在仓库根目录打开 PowerShell 运行以下脚本；可用 `-JdkRoot` 指定本机 JDK。Android 自带 `org.json`，桌面 JVM 使用本地测试 JSON 库，不作为 APK 依赖。脚本依次查找 `artifacts/android-resolver-test/json.jar` 与 `artifacts/audio-source-integration/java-test/json.jar`，也可用 `-JsonJar` 指定已有的库。

```powershell
.\tests\android\run-resolver-tests.ps1
```

成功输出 22 项解析器 `PASS` 与 6 项曲目状态 `PASS`，退出码 0。默认 CTest 不执行此程序；本次运行结果见[验证说明](../../VERIFICATION.md)。

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
