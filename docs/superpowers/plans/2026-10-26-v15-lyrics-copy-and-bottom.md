# 1.5 歌词底部与长按复制 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for Android investigation and implementation; independent desktop copy work may use superpowers:dispatching-parallel-agents. Preserve existing uncommitted 1.4/1.5 work.

**Goal:** 修复手机悬浮播放器手动翻到底部时最后一行被裁切；双端长按歌词复制全部歌词，长按歌单简介复制完整简介。
**Architecture:** 沿用原生 ScrollView/LyricColumn 与 QML 歌词视图；原生 clipboard 与 Qt clipboard 分别处理复制，手势由原生长按／Qt TapHandler 处理，不能干扰滚动和简介单击编辑。
**Tech Stack:** Qt Quick/C++17, Android Java/API28–36。
**Spec:** 用户 2026-10-26 请求及后续纠正：“长按复制全部歌词，不要逐句复制”。

## Global Constraints

- 任何歌词长按都复制当前歌曲的全部可用歌词，按原文／译文／双语模式；去时间标记，不复制占位提示。
- 简介复制完整原始内容，不能复制省略号或翻译用户文本；同时覆盖本地歌单与在线歌单详情。
- 空内容不覆盖剪贴板，成功有短提示；保持单击简介编辑和手动滚动／返回当前句。
- 独立透明桌面歌词的长按仍打开设置；本次“悬浮窗内”指播放器歌词区域。
- 旧布局、1.4审查修复与1.5透明度／退出修复全部保留；无发布。

## Review Focus

- 最后一行长双语／大字号／紧行距；手动拖动、惯性滚动、返回当前、重新定位后仍完整。
- 长按复制整首，原文／译文／双语均不误带系统占位文字。
- 长按和滚动不会互相误触，简介长按不会再打开编辑窗口。
- 截断简介、Unicode、换行和“收藏夹”等用户文本完整保留。
- 空歌词／空简介不改已有剪贴板。

## Tasks

- [x] Android 底部：真实原生手动上下滚动与到底复现，记录最后一行文本／父视口可见边界；定位根因，先红后绿；截图检查。
- [x] Android 全文复制：补原生剪贴板与长按回归，再接入全部歌词／简介及提示；测滚动不误复制。
- [x] Windows 全文复制：补真实QML长按与剪贴板回归，接入全部歌词／简介、短提示、保持点击编辑；测完整截断内容。
- [x] 双端版本使用1.5.0-preview.1/code24，跑完整回归、独立review、构建并校验安装包与Windows目录。

## Ledger

- 2026-10-26：用户已把逐句复制纠正为全部歌词，采用当前显示模式。保留当前工作区，开始前差异备份 artifacts/v1.5.1-2026-10-26/pre-fix.patch。不重新设计布局。
- 2026-10-26：用户补充原文16号、1.5行距；原生复现确认内列 clipToPadding 裁掉末句下沿，仅关闭内列该选项，外层ScrollView继续裁切。九种配置与截图通过。
- 2026-10-26：新增长按后补测发现文本拖动漏掉手势开始、榜单简介短击被消费，两项均先红后绿；完整Android原生33项通过。桌面完整14项通过，Windows原生补验另修正测试越界滚动，生产代码无变化。
- 2026-10-26：最终Windows原生17/17、离屏17/17；默认构建的聚焦CTest再次通过。双端包构建完成，87个Android文件、42个桌面生产文件及最终二进制哈希一致；隔离模拟器和测试包已清理，无真机安装或远端发布。
