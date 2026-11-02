# FloatMusic 1.7 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 双端增加音乐资料和只读评论，不干扰播放。
**Architecture:** MusicApi 接入公开端点；MusicDetails 独立管理异步状态、候选选择与磁盘快照；QML 与 Android 使用同一状态对象。
**Tech Stack:** Qt 6 / C++17 / QML / Android Java，沿用现有构建与桥接。
**Spec:** docs/superpowers/specs/2026-10-31-v17-music-details-design.md

## Global Constraints
- 保留原布局、虹彩风格及 1.6 preview.4 修复。Windows 和 Android 同步；双语与明暗模式。
- 缺失计数不等于零；平台统计不混合；不支持的平台明确显示状态。
- 不登录、不发评论；切换来源不改变音频；跨来源候选必须用户确认。

## Review Focus
- 迟到响应：切换歌曲/来源/排序后不能串数据。
- 网络失败：保留此前成功快照，离线内容有明确提示。
- 平台拒绝与畸形 JSON：不能解释为零条评论。
- 页边界重复：去重且保留正确页游标，限制内存与请求。
- 滚动与触摸：小悬浮窗长评论可读，不被定时刷新打断。

### Task 1: 数据接口与状态
Files: src/musicdetailsapi.cpp, src/musicdetails.h/.cpp, src/musicapi.h, CMakeLists.txt, tests/musicdetails_tests.cpp
Interfaces: MusicApi::fetchSongDetails(track, done), fetchComments(track, order, page, done); MusicDetails::state() -> QVariantMap; action(action,args); changed(). State includes track/details/comments/candidates/source/order/loading/error/stale/hasMore.
- [x] 写本地 HTTP 测试：计数缺失、最新评论分页、错误不覆盖、切换后迟到响应、缓存恢复与来源候选。
- [x] 运行测试确认功能缺失；实现规范化接口与请求代次、成功快照缓存、界限控制。
- [x] 重跑 music_details 测试直至通过。

### Task 2: 双端界面与封面
Files: qml/MusicDetailsPage.qml, qml/Main.qml, src/main.cpp, src/playercontroller.h/.cpp, android/src/org/floatmusic/player/OverlayWindow.java, android/src/org/floatmusic/player/AlbumArt.java
Interfaces: player.musicDetails QObject; Android ui.musicDetails object; musicDetails event {action,args}.
- [x] 为资料入口与显示写界面/桥接回归并确认缺失。
- [x] 新增详情入口；只在详情页展示资料、来源切换、候选、排序、评论展开及加载更多。继承当前主题。
- [x] 补在线歌单封面及可用统计，封面异步读取并限额缓存。
- [x] 执行桌面界面测试与 Android 编译/界面检查。

### Task 3: 集成验收与交付
Files: 版本信息、翻译表、docs/1.7-音乐资料与评论.md、artifacts/v1.7-preview/
- [x] 更新为 1.7.0-preview.1 / Android versionCode 31，生成双语资源。
- [x] 实测公开歌曲详情和评论，记录各来源可用范围。
- [x] 运行 Windows 全套 CTest，构建签名 Android 安装包；独立审查与修复重要问题。
- [x] 提供可运行的 Windows 程序及 Android 包、验证记录；本次不发布远端。
