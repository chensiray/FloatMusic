# 浮音 1.0：多平台歌单、榜单与搜索数量

Goal: 双端接入已验证的匿名 QQ、酷我歌单/榜单接口；设置搜索结果上限（多曲库合计）。

Architecture: 保留 Qt/C++ 公共数据层与现有播放解析；新接口输出既有歌单/歌曲模型。在线资源带平台与类型标识，歌曲继续使用 source + songId，避免跨平台 ID 冲突。QML 与 Android 原生悬浮页使用相同控制器设置。

Tech Stack: Qt 6 / C++17 / QML / Android Java；有限 HTTP 请求；便携 AES 客户端协议；Qt Test 与本地响应夹具。

Spec: 当前用户请求及 docs/QQ音乐与酷我音乐歌单排行榜接口调研-2026-10-05.md。该调研方案已得到用户接入授权。

Global Constraints:
- 匿名公开数据，不要求登录或付费；歌单信息可用不等于所有歌曲可播放。
- 网易云旧功能、混合收藏、导出、歌词设置与播放恢复保持兼容。
- 搜索数量为合计上限，默认 30，选项 10/20/30/50/100；歌曲与歌单共用设置，重启保留。
- 搜索按所选平台顺序轮流合并，最多显示设置条数；没有足够结果时显示实际数量。
- QQ 榜单读取动态目录；酷我只展示已验证的热歌 16、新歌 17、飙升 93，歌曲实时加载。
- 在线歌单最多加载 2000 首；分页去重、保留次序、失败允许已有部分结果并明确提示。
- 已有滚动结构保留，顶部控件随列表滚动；新增选择控件紧凑并适配窄屏。
- 仅制作待验收版本，当前请求不包含发布 GitHub。

Review Focus: 平台/资源类型路由；MID/RID 原样保留；取消与过期响应；分页退出；安全解析非标准响应；合计上限；双端布局与桥接。

## 接口约定

- MusicApi 新增 search(query, sources, int limit)，searchPlaylists(query, sources, int limit)，fetchRankings(source, done)，保留旧重载。
- fetchPlaylist(id, done) 接受旧网易云数字 ID，以及 tencent:playlist:<dissid>、tencent:ranking:<topId>、kuwo:playlist:<pid>、kuwo:ranking:<id>。
- 在线摘要增加 source/sourceName/kind/resourceId，id 是可直接传给 openOnline 的资源标识。网易云原有数字 ID 保持兼容。
- PlayerController 增加 int searchResultLimit（读写、searchSettingsChanged 信号）、QString rankingSource（只读、rankingsChanged）。
- libraryAction("searchPlaylists", {query}) 使用 searchSources 与 searchResultLimit。
- libraryAction("loadRankings", {source}) 保存当前榜单平台；没有 source 时使用 rankingSource。
- Android 状态新增 searchResultLimit、rankingSource；命令 searchResultLimit 使用整数文本。在线页面返回位置字段 onlineSource 仍是 search/rankings，不用作平台。

## Task 1：公共接口与协议
- [x] 先加入本地夹具测试，覆盖 QQ POST、酷我单引号解析、AES 向量、分页、源标识、合计上限、错误和取消。
- [x] 实现三平台歌单搜索、QQ/酷我榜单及详情；保留网易云原路径与播放解析。
- [x] 编解码文件独立且有许可证/来源说明；网络地址可注入本地测试。

Owned files: src/musicapi.*；新 src/*catalog*、src/*codec* 文件；tests/catalog_api_tests.cpp 及所需夹具。

## Task 2：桌面页面
- [x] 歌曲/歌单搜索都可以选择现有曲库；无勾选时禁用搜索。
- [x] 榜单页平台切换与当前平台信息；歌单/详情明确来源、简介、歌曲数量。
- [x] 设置中新增搜索数量选择与合计上限提示；版本文字更新为 1.0.0-preview。
- [x] 保留列表滚动和歌词设置；检查长标题与窄屏换行。

Owned files: qml/Main.qml。

## Task 3：Android 页面
- [x] 与桌面相同的平台选择、歌单来源、榜单切换、搜索数量设置。
- [x] 保留手势滚动、分页、输入焦点与重建时机；控件适配小悬浮窗口。
- [x] 增加/更新必要的桥接合同检查；版本文字更新。

Owned files: android/src/org/floatmusic/player/OverlayWindow.java；tests/android/*v1*。

## Task 4：控制器、导入兼容与交付
- [x] 控制器设置持久化、搜索合计参数与榜单平台切换，先增加合同测试。
- [x] QQ、酷我公开歌单链接可按平台导入；裸数字仍为网易云。混合 JSON 不变。
- [x] CMake 接入新增文件/测试；版本 1.0.0-preview，Android versionCode 13。
- [x] 桌面与 Android 编译、必要本地检查；生产接口小样本核验。
- [x] 更新 README、CHANGELOG、验收说明；提供双端待验收包。

Owned files: src/playercontroller.*、src/playerlibrary.cpp、src/playlistdocument.*、CMakeLists.txt、src/main.cpp、现有控制器/文档测试、版本文档和打包产物。

## 执行记录

使用现有工作区的 codex/v1-online-playlists 分支，遵循宿主指令优先复用当前检出，避免复制正在开发的共享工作区。Windows 构建使用带所有权标记的临时目录。并行任务有不同文件所有权，公共接口由上面的约定固定。验收前进行一次整体验证与代码审查。

2026-10-06：用户反馈验收无误，并授权上传 GitHub。发布沿用已验收的双端文件；README、更新记录与下载入口同步为 v1.0.0-preview，发布前重新核对回归、源文件快照和包校验值。
