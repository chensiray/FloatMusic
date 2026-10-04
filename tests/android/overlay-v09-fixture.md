# Android 0.9 悬浮 UI fixture

局部设计沿用现有浅/深主题：蓝色强调 `#2458D3 / #83ABFF`、正文 `#182338 / #EDF2FA`、副文 `#56657A / #ABB8CC`、表面 `#FFFFFF / #192333`。来源放在歌曲副行，不新增歌曲行高。三曲库使用一行原生复选框，保持 48dp 触摸区域、36dp 表面与 8dp 间隔，跟搜索输入和结果一起滚动。音质使用一个设置入口，网易云在弹出菜单中选择，QQ/酷我固定显示普通音质。

`run-overlay-v09-contract-test.ps1` 在本地 JDK 上编译实际 Java 源码及 fixture，执行 `OverlayV09ContractTest` 的四组行为回归。它不请求在线服务、不启动播放器、不安装手机包。默认复用已有 `artifacts/audio-source-integration/java-test/json.jar`；也可传入本地 `-JsonJar`。

```powershell
.\tests\android\run-overlay-v09-contract-test.ps1
```

`OverlayV09UiTest` 是针对实际已安装 0.9 APK 的独立 Instrumentation；它反射创建悬浮窗和注入内存快照，不通过 Qt 搜索或改写用户歌单。手机需已安装相同调试签名的 0.9 APK、授权悬浮窗和 USB 调试，并保持解锁。

`run-overlay-v09-ui-test.ps1` 会编译和打包 `overlay-v09-manifest.xml` 与 fixture，使用与主 APK 相同的 Android 调试密钥，安装临时 `org.floatmusic.v09uitest` 包并运行。必须显式传入设备序列号；默认工具路径沿用滚动回归脚本，可按本机配置传入 `-SdkRoot`、`-JdkRoot`、`-BuildTools`、`-KeyStore`。

```powershell
$fixtureOutput = Join-Path ([IO.Path]::GetTempPath()) 'FloatMusic-v09-ui-test'
.\tests\android\run-overlay-v09-ui-test.ps1 -DeviceSerial '手机序列号' -OutputDir $fixtureOutput -Screenshots
```

默认编译、DEX、APK 与 `ui-results.txt` 保留在 `artifacts/v09-sync-verification/android-ui/run-时间-随机ID/`。若仓库路径含中文，本机 `aapt` 可能无法读取，应如上指定英文临时子目录。自定义 `-OutputDir` 可使用本仓库 `artifacts` 或系统临时目录中的专用子目录，不能包含或位于 SDK、JDK、签名密钥目录，也不接受现有 junction 或符号链接。脚本不删除旧文件；成功安装测试包后，`finally` 只卸载该临时测试包，不卸载或清空浮音。Instrumentation 会重启目标进程，可能中断当前播放，结束后可重新打开浮音。

脚本使用 `am instrument -w -r` 的原始结果，要求五项唯一 `PASS`、`uiPassed=true`、`passed=5` 和正常 Instrumentation 结束码同时成立才报告通过；失败时保留原始日志。手机应解锁并回到桌面，避免系统设置等页面隐藏悬浮窗。

可选 `-Screenshots` 在完成布局后绘制实际控件树，跳到 Drawable 的最终状态，只包含内存样本及窗口自身；不截取背景桌面，不启动真实播放。PNG 位于目标包的 `cache/v09-ui-fixture/场景名.png`，可用 `adb exec-out run-as org.floatmusic.player cat` 读取。截图不替代系统触摸检查。

五组控件验收点：

1. 三来源默认全选、完整 JSON 事件、48dp 范围、来源与输入同属滚动内容，旧快照不回滚未确认的选择，确认快照不重建输入和光标。
2. 显式空选禁用歌曲搜索且 IME 仅提示；歌单搜索隐藏来源选择并继续发送网易云歌单查询。
3. QQ 结果播放与混合歌单多选原样传递 `tencent:AbC12xY`，不改 MID 大小写，歌曲副行有 QQ 来源。
4. 来源/音质更新保留正在编辑的服务地址和光标，QQ 使用一个禁用的普通音质入口及实际格式信息；跨回网易云恢复保存档位，加载另一平台时保留已加载歌曲的来源。
5. 新结果或来源快照在触摸结束后更新动态列表，输入控件持续保留。

2026-10-04 已对 MTN-AN80 上实际安装的 `0.9.0-preview`（版本码 12）运行以上五组控件回归，全部通过；五张控件树验收图已保存。双向列表滑动与多选滑动由 `OverlayScrollTest` 检查并通过，临时包均已移除，用户歌单文件在检查前后保持一致。原始日志与检查边界见[验证说明](../../VERIFICATION.md)；小屏、横屏、大字号、浅/深主题、拖动落点及完整播放体验仍需继续验收。
