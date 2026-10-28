# FloatMusic 1.5 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the shared dual-platform settings; use superpowers:dispatching-parallel-agents for the independent Android exit fix. Steps use checkbox syntax for tracking.

**Goal:** 双端可调悬浮歌词渐隐、位置操作完善，Android 开启页退出可靠。
**Architecture:** 现有窗口与设置持久化承载新选项；Android FloatingLyricRules 与 QML fadeAt 采用相同端点规则；退出由 PlayerBridge 协调 Android 主线程和 Activity 生命周期。
**Tech Stack:** Qt 6.11.2 Quick/C++17, Java 8 API28–36, CMake/JDK21。
**Spec:** `docs/superpowers/specs/2026-10-25-v15-floating-experience-design.md`。

## Global Constraints

保留已有布局和 1.4 审查修复；不上传远端；当前句10–100%、边缘0–当前句%、默认100/35；持久化、中英文、1/3/5/7/9行；Android最低API28；歌词文字外点击穿透。

## Review Focus

- 损坏或越界配置不产生 NaN、不隐藏全部歌词。
- 动画途中拖动透明度不重新定位、不重建文字位图。
- 0%边缘不留触摸区域，1行不受边缘配置影响。
- 锁定后长按仍可打开设置，居中可找回越界位置。
- 冷启动无服务退出、后台播放退出、重复退出均正确；不丢暂停现场。

## Task 1: 退出路径（独立）

Files: PlayerBridge.java, PlayerActivity.java, PlaybackService.java（如需）, src/playercontroller.cpp quit分支, 新建独立 Android 生命周期 fixture 与脚本。
Interfaces: PlayerBridge 原生退出入口，在 Android 主线程完成保存、停服务、结束 Activity；C++ Android quit 只使用此入口。
- [x] 新增真实原生退出回归，复现无服务／有服务的失败，并保存红证据。
- [x] 定位根因并最小修复，保持现有后台返回语义。
- [x] 检查服务通知、悬浮窗、保存、重复退出；回归转绿。

## Task 2: 两端透明度与位置

Files: qml/DesktopLyrics.qml, qml/Main.qml, FloatingLyricRules.java, FloatingLyricsWindow.java, OverlayWindow.java, src/uilanguage_en.inc, UiCatalog.java。
Interfaces: desktopPreferences.highlightOpacity/edgeOpacity/positionLocked；Android desktopLyricsHighlightOpacity/desktopLyricsEdgeOpacity/desktopLyricsLocked；FloatingLyricRules.opacity(distance,rows,highlight,edge)。
- [x] 在 FloatingLyricRulesTest、player_tests 和 LyricWindowRegressionTest 增加端点／渐变／动画中设置／穿透／锁定及居中回归并跑红。
- [x] 实现插值与参数约束，接入两端带百分比的设置、翻译和持久化。
- [x] 增加锁定和居中，避免每次可见度改变重建或取消动画。
- [x] 用相同测试跑绿，核查截图、键盘和小屏布局。

## Task 3: 集成验收与交付

Files: CMakeLists.txt, Main.qml/AndroidMain.qml 版本文案, 翻译目录, docs/1.5-验收与更新说明.md。
- [x] 更新为1.5.0-preview，Android versionCode23；说明默认值和操作入口。
- [x] 运行13组桌面回归、Android7组契约/87存储/23解析及扩展原生窗口/退出回归。
- [x] 构建Windows完整目录和ARM64 APK，核查源码快照、签名和截图。
- [x] 请求独立代码复审，处理重要问题；记录实测边界与产物路径。

## Ledger

- 2026-10-25: 用户授权自行决定细节和自行验收；使用当前工作区以保留尚未提交的1.4修复，原有差异备份至 artifacts/v1.5-2026-10-25/pre-v15-audit.patch。不自动提交旧审查改动或发布。

- 2026-10-25: 全量验收完成：桌面13组、Windows原生2场景、Android28窗口+8退出、7契约/87存储/23解析通过；完整Windows及ARM64包校验一致。QML开启页关闭否决和首次空触摸区经红→绿修复。未连接真机，边界见 docs/1.5-验收与更新说明.md。
