# Offline Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 双端持久保存歌曲与歌词，断网重启可播放，统一管理手动与自动缓存。
**Architecture:** 各平台独立存储/下载模块，播放入口优先读取本地。Windows 由 C++ 管理缓存；Android 由 Java 服务拥有索引与文件，Qt 桥提供歌词解析协作。UI 仅调用公开操作。
**Tech Stack:** Qt 6/C++17/QML，Android Java，JSON，现有网络与音源解析器。
**Spec:** ../specs/2026-10-29-offline-cache-design.md

## Global Constraints
- 用户已批准自动缓存开启、1 GB、Android 仅 Wi-Fi、手动保存长期保留。
- 不覆盖原有 1.6 歌词改动；不提交或推送；本次在当前工作目录延续开发。
- 独立后台下载不得阻塞音源解析或播放。文件与索引提交前不可标记离线可用。
- 手动项目与当前播放受保护，取消与失败清临时文件；禁止删除用户导入原文件。
- 同步中英文，保持明暗主题与现有布局；最终版本 1.6.0-preview.2。

## Review Focus
- 缓存命中和进程重启后是否仍偷偷依赖在线解析/歌词。
- 取消、退出、音源回调与删除竞争是否导致复活任务或孤儿文件。
- 音质回退、跨平台同 ID 与自动转手动是否正确关联。
- Wi-Fi 变化、未知大小下载、受保护文件导致超限时是否可控。
- Android 原生服务与 Qt 歌词桥能否正确恢复后台歌词。

## Task 1: Desktop cache engine + playback integration
Owner: desktop implementer. Files: new src/offlinecache.*, src/playercontroller.*, optional src/playercache.cpp, CMakeLists.txt, new tests/offlinecache_tests.cpp and controller fixture tests.
- [x] 写真实文件/本地 HTTP 测试，覆盖持久化、失败响应、截断、取消、LRU、保护、音质命中。
- [x] 实现原子索引、完整下载、状态/字节进度、原文译文和错误状态；独立 MusicApi 实例避免取消播放解析。
- [x] 接入原歌单/队列播放、自动缓存、离线歌词；协商 Android 桥接口。
- [x] 提供 QML API（与 root 确认）：cacheEntries、cacheSummary、cacheSettings 和保存/批量/播放/保留/重试/取消/删除/清理/设置操作。
- [x] 编译针对测试，回报实际结果和接口；不修改 QML、语言目录或 Android Java。

## Task 2: Android cache engine + native UI + playback
Owner: Android implementer. Files: new android/src/org/floatmusic/player/OfflineCache*.java, PlaybackService.java, PlayerBridge.java, OverlayWindow.java, new tests/android cache fixtures.
- [x] 为持久化/任务/离线命中设计可执行测试并先运行。
- [x] 实现 Java 独占缓存，独立执行器，Wi-Fi 默认策略，音频/歌词状态持久化，生命周期退出取消。
- [x] 接入原生媒体播放和缓存管理/单曲/歌单入口；向 desktop implementer 明确 Qt 桥契约。
- [x] 测试断网重启、进度拖动、歌词、取消和限额；新增文案交 root 统一翻译。
- [x] 回报变更和证据；不改 C++/QML/CMake/语言目录。

## Task 3: Desktop UI + common language/version integration
Owner: root. Files: qml/Main.qml, optional qml/CachePage.qml, src/uilanguage_en.inc, generated UiCatalog.java, src/main.cpp, docs.
- [x] 用现有设置二级页加入缓存页，显示分类/占用/任务/音质/歌词，支持全部批准操作及清理确认。
- [x] 单曲与歌单入口接 controller API，明确已加载范围。
- [x] 中英文、明暗、长标题和零缓存状态验收。
- [x] 更新版本元数据，与 Android 共享 API 整合。

## Task 4: Review, build, acceptance
- [x] 独立审查对照规范、并发和删除边界，修复发现。
- [x] 完整 desktop CTest 和原生缓存 UI/断网测试。
- [x] Android host contracts + 原生隔离模拟器缓存/离线重启测试，实体手机只安装最终验收包。
- [x] 生成双端包，核对源码和产物哈希，记录验证范围和未覆盖限制。

## Ledger
- 2026-10-29 用户批准规格默认设置。
- Ruling: 延续当前未提交工作，不新建空工作树，以保留已验收的 1.6 双端歌词修复。按文件所有权并行，整合后统一审查。

- 2026-10-30 Windows 核心22项、真实媒体重启控制器7项通过（父进程的子进程探针槽跳过，子进程实际运行通过）。
- 2026-10-30 Android JVM 48检查；原生隔离模拟器54检查；歌词回归6场景通过。
- 2026-10-30 独立审查修复：多音质精确播放、实际音质合并保护、在线当前曲保护、待删除撤销、歌词即时刷新、206部分内容拒绝、损坏索引孤儿清理、原子查询与准备播放保护。
- Ruling: Windows 已完整音频但未完成的歌词启动时不自动重试，提供管理页补取；离线播放不等待网络，第一版保留此边界。
- Ruling: 不改动 GitHub、不提交；没有实体设备连接，最终Android包留供实体机验收，测试使用隔离模拟器与生产媒体服务。
- 2026-10-30 最终完整 Windows CTest 16/16 通过，原生缓存界面3/3（含初始化/清理）通过；Windows包 run-20261030-155346-922ab385 成功。
- 2026-10-30 Android ARM64包 code28/preview.2 构建、签名、92文件源码快照校验通过；最终共享源码与Windows staging逐项一致。
- 2026-10-30 测试收尾：旧网络套件禁用缓存并按用例隔离数据；简介长按用例等待真实viewport布局。保留所有原断言。已卸载隔离fixture并关闭专用模拟器。
- Completion: 双端实现、独立审查、回归测试、产物与文档完成。保留当前分支和未提交修改供用户验收。
