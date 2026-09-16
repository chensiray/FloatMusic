# 浮音验证记录

## 0.3 · 2026-09-16

本轮设备：Windows 11，Qt 6.11.2 / MinGW 13.1；手机未连接。网易云没有真实服务地址，按用户确认采用本地模拟 HTTP API。

最终 Windows CTest：4/4 通过，0 失败，10.35 秒。

| 验证项 | 结果 |
|---|---|
| 文件导入回归 | 扩展名、文件头、30 MiB 大小边界、错误文件、拖入路径通过 |
| 播放回归 | 实际 WAV 解码、播放进度前进、暂停、跳转、输出切换、音量上下界通过 |
| 明确退出 | 子进程中分别关闭 QML 窗口和触发“退出应用”，进程正常结束、退出码 0 |
| 单实例 | 3 次重复启动通知主实例；强制结束测试主实例后可恢复启动，自动测试通过 |
| 打包程序 | 实际 EXE 重复启动退出码 0，Windows 只保留一个 FloatMusic 进程，主窗口被唤起 |
| 搜索 | 中文与特殊字符、空结果、HTTP 错误、API 拒绝、错误 JSON、断网失败、旧搜索迟到不覆盖新结果，通过 |
| 歌单 | 新建/改名/选择/删除、保留最后一个歌单、重复网易云歌曲去重、移除、重建对象恢复保存数据，通过 |
| 在线播放流程 | 模拟 API 返回 HTTP WAV，经过真实网络请求和 Qt 解码播放；无权限/空 URL 显示错误且保留原歌曲，通过 |
| 上一首/下一首 | 手动循环切换、自然结束自动续播、歌单末尾停止，通过 |
| 本地持久化 | 删除原始测试 WAV 后私有副本仍可播放，新播放器对象恢复歌单，通过 |
| 界面 | 图标展开/收起、歌单页和搜索页渲染、标签对比度、退出入口检查，无 QML 引擎警告 |
| HTTPS 依赖 | Windows QSslSocket 支持 SSL；Android APK 内核对 libssl_3.so 与 libcrypto_3.so 存在 |
| Android 构建 | ARM64 C++/Java 编译和 Gradle APK 打包成功，未安装手机 |

本地测试报告：`artifacts/LastTest.log`、`artifacts/library-tests.txt`、`artifacts/instance-tests.txt`。截图在构建目录 `C:/Dev/FloatMusic/test-results`。这些测试产物不提交 Git。

未完成的验证：真实网易云服务/账号权限、Android 0.3 悬浮页面交互/后台续播/旋转/权限撤销、其他蓝牙设备和长时播放。编译成功不等于手机运行验证成功。原生 Android 悬浮页没有用 Windows 截图代替验收。

以下内容为历史版本记录，功能行为以当前 README 为准。

## 0.1 历史验证

验证日期：2026-09-15。Windows 11 / Qt 6.11.2 / MinGW 13.1；Android 真机荣耀 X70（MTN-AN80），arm64-v8a。

| 验证项 | 结果与证据 |
|---|---|
| Windows CMake 编译 | 通过 |
| Windows 文件格式校验 | 自动测试通过：扩展名大小写、错误类型、伪装音频、格式头 |
| 大小边界 | 自动测试通过：等于 30 MiB 允许、超过 1 字节拒绝、空文件拒绝 |
| Windows 实际读取与播放 | 自动生成 8 秒 PCM WAV，完成异步导入、解码并读出 8000 ms 时长 |
| Windows 播放/暂停/跳转 | 播放进度实际前进；暂停后保持；暂停时跳至 4 秒、播放时跳至 6 秒，测试通过 |
| Windows 拖放接入 | 向 QML 窗口发送真实 Qt 拖入和放下事件，已接受拖放并触发错误文件校验 |
| Windows 窗口 | 置顶、无边框标记验证通过；展开/收起页面渲染成功，无 QML 引擎警告 |
| Windows 原生启动 | 使用 Windows 平台插件启动 EXE，正常关闭，退出码 0 |
| Windows 独立运行目录 | 清除 Qt 插件环境变量、PATH 仅保留 Windows 系统目录后，dist/windows/FloatMusic.exe 仍正常启动并以退出码 0 关闭 |
| Android APK | ARM64 Debug APK 构建并安装成功 |
| Android 导入 | 系统文件选择器从 Download 选择 60 秒 PCM WAV，媒体会话读出正确文件名和时长 |
| Android 悬浮窗 | 在桌面之上显示，点击歌名展开主界面，拖动顶部后位置从约 y=56 移至 y=642 |
| Android 暂停和进度条 | 拖到约 19,978 ms，暂停后多次读取位置不变 |
| Android 后台播放 | 切回桌面后，PLAYING 状态和进度继续前进 |
| Android 锁屏 | 设备为 Dozing 时，进度从 0 前进至 27,446 ms，仍为 PLAYING；记录在 artifacts/lockscreen-before.txt 和 lockscreen-after.txt |
| Android 悬浮窗再次导入 | 从悬浮窗启动系统选择器，选择后自动返回悬浮窗，重新加载音频 |
| Android 错误文件 | 伪装 MP3 被拒绝，显示格式不匹配；当前音乐保留 |
| Android 超大文件 | 31,457,281 字节 WAV 被拒绝，显示超过 30 MiB；当前音乐保留 |
| Android 返回键 | 曾发现返回导致 Qt 进程退出；已改为退到后台，复测返回后仍为 PLAYING，进程和服务保持运行 |
| Android 显式退出 | 最终安装包在播放时点击悬浮窗 ×：播放服务消失、媒体会话移除、悬浮窗消失 |

自动测试入口是 `scripts/build.ps1 -Target windows`，包含 `import_policy` 与 `player_integration` 两组 CTest 测试。最新结果见 `artifacts/windows-tests.log`。生成的桌面截图为 `artifacts/windows-compact.png` 和 `artifacts/windows-expanded.png`。

尚未完成的兼容性测试：其他手机品牌、长时间息屏省电、Android 跨应用文件拖放、所有允许格式的真实编码样本、通话中断/蓝牙拔出等硬件场景。文件头白名单不等于验证所有编码；最终能否播放由解码器决定。

当前实现保存一首音频的临时副本，不持久保存音乐库。系统强制停止进程时音乐停止，不自动复活进程。

## 0.2 本轮验证

- 修复 Windows 未监听音频输出变化的问题：监听 QMediaDevices::audioOutputsChanged，跟随系统默认；可选择独立设备，设备断开后回退默认。
- 播放中切换耳机 SIBYL WS200 PRO、Realtek 扬声器和系统默认，自动测试通过。用户亲自确认蓝牙耳机有声音、音量滑块有效。
- 音量支持 0–100%，上下界与播放状态测试通过；音量设置持久保存。Android 音频焦点恢复时保留用户音量，不再强制恢复到最大。
- Windows：图标点击打开窗口、窗口隐藏后图标恢复、播放/暂停/seek/文件校验自动测试通过（CTest 2/2）。
- Android 真机：固定 56dp 图标拖动、点击打开应用、应用内 isVisible=false、Home 后 isVisible=true 已验证。滑块修改为 27%，私有设置中保存为 27。
- 最终包已更新安装到手机：冷启动自动恢复已授权的图标服务；实测应用内图标隐藏、Home 后显示、点击图标回到应用后再次隐藏。
- Windows 图标固定 64px。未添加透明度/大小调节。0.2 不再在 Android 图标内直接提供拖入文件；请从音乐界面导入。
- 待扩展验证：Android 各品牌蓝牙路由策略、Windows 蓝牙通话模式切换、长时间省电场景。
