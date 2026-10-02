# Android 音源解析器本地测试

在桌面 JDK 21 运行 APK 使用的 `AudioResolver.java`，由本地 HTTP 服务提供固定响应，不连接真实音源、读取用户配置或操作手机。

覆盖 GD 音质参数与签名地址、错误音频/HTTP 523/Meting 重定向回退、自定义 API 独占、全部失败与冷却、取消，以及空 Meting 数组。此测试不覆盖 Android 的播放器解码、通知、音频焦点和后台生命周期。

在仓库根目录打开 PowerShell，按本机 JDK 路径调整 `javac` 与 `java`。Android 自带 `org.json`，桌面 JVM 需下载测试专用 JSON 库；文件只放在忽略目录，不作为 APK 依赖。

```powershell
$javaTest = Join-Path (Get-Location) 'artifacts/android-resolver-test'
New-Item -ItemType Directory -Path "$javaTest/classes" -Force | Out-Null
Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile "$javaTest/json.jar"
javac -encoding UTF-8 -cp "$javaTest/json.jar" -d "$javaTest/classes" android/src/org/floatmusic/player/AudioResolver.java tests/android/AudioResolverTest.java
if ($LASTEXITCODE -ne 0) { throw 'Java 测试编译失败' }
java -cp "$javaTest/classes;$javaTest/json.jar" org.floatmusic.player.AudioResolverTest
if ($LASTEXITCODE -ne 0) { throw 'Android 解析器测试失败' }
```

成功输出六项 `PASS`，退出码 0。默认 CTest 不执行此程序；本次运行结果见[验证说明](../../VERIFICATION.md)。

## 悬浮列表真机回归

`OverlayScrollTest.java` 是独立 Instrumentation，反射创建已安装 APK 的实际 `OverlayWindow`，通过系统触摸输入检查歌单、歌曲搜索及歌单搜索的上下滚动，以及多选按钮、复选框和多选时的滚动。测试只使用内存样本，不启动 Qt、请求在线服务或写入用户歌单。

手机需已安装浮音、授权悬浮窗和 USB 调试，并解锁屏幕。测试包须与浮音使用同一调试签名；默认使用 Android 标准调试密钥及公开默认口令，不支持正式签名包。运行前会重启目标进程；测试结束后可重新打开浮音。

```powershell
.\tests\android\run-scroll-test.ps1 -DeviceSerial '手机序列号'
```

按本机配置修改 `-SdkRoot`、`-JdkRoot`、`-BuildTools`、`-KeyStore`。脚本编译和安装临时测试包，运行后自动移除该测试包；日志与中间产物只保留在忽略目录 `artifacts/android-scroll-test/`，可以用 `-OutputDir` 修改。

此回归在完成布局后直接滑动，不等待面板回顶动画，用于发现第一次滑动被吞掉的问题。返回顶部后的按钮检查会等待原生边缘效果结束。脚本只有三种列表均通过双向滚动且多选检查通过时才成功；不覆盖真实搜索返回、音频播放、排序落点保存、所有设备或全部缩放比例。
