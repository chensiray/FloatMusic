import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import QtCore

ApplicationWindow {
    id: root
    property bool quitting: false
    readonly property string languageCode: player.uiLanguage.code
    function t(source) { var dependency = languageCode; return player.uiLanguage.text(String(source || "")) }
    function setUiLanguage(code) { player.uiLanguage.code = code }
    function toggleDesktopLyrics() { desktopPreferences.enabled = !desktopPreferences.enabled; desktopPreferences.sync() }
    function desktopRowCount(value) { return Math.max(1, Math.min(9, Math.floor(value / 2) * 2 + 1)) }
    readonly property var playlistLabels: player.playlists.map(function(p) {
        return { id: p.id, name: p.id === "favorites" ? root.t("收藏夹") : p.name }
    })
    Settings {
        id: desktopPreferences; category: "desktopLyrics"
        property bool enabled: false
        property int rowCount: 3
        property int fontSize: 30
        property int themeMode: 0
        property int left: -10000
        property int top: -10000
        Component.onCompleted: {
            rowCount = root.desktopRowCount(rowCount)
            themeMode = Math.max(0, Math.min(2, themeMode))
            fontSize = Math.max(18, Math.min(48, fontSize))
            sync()
        }
    }
    DesktopLyrics {
        id: desktopLyrics
        property rect availableArea: Qt.rect(0, 0, 1280, 720)
        visible: desktopPreferences.enabled && !root.quitting
        timeline: player.lyricLines
        activeIndex: player.currentLyricIndex
        lineCount: root.desktopRowCount(desktopPreferences.rowCount)
        textSize: Math.max(18, Math.min(48, desktopPreferences.fontSize))
        motionEnabled: appearance.animationsEnabled
        themeMode: desktopPreferences.themeMode
        darkStyle: themeMode === 2 || (themeMode === 0 && root.darkMode)
        availableHeight: availableArea.height - 16
        themeText: root.t("歌词主题")
        followText: root.t("跟随播放器")
        lightText: root.t("浅色")
        darkText: root.t("深色")
        onThemeRequested: function(mode) { desktopPreferences.themeMode = mode; desktopPreferences.sync() }
        onInputRectsChanged: player.setDesktopLyricInputRegion(desktopLyrics, inputRects)
        width: Math.min(760, availableArea.width - 16)
        emptyText: root.t(player.lyricsMessage || root.t("暂无逐句歌词"))
        hideText: root.t("隐藏桌面歌词")
        settingsText: root.t("歌词设置")
        lockText: locked ? root.t("解锁位置") : root.t("锁定位置")
        onLockRequested: locked = !locked
        onHideRequested: { desktopPreferences.enabled = false; desktopPreferences.sync() }
        onSettingsRequested: { root.activateWindow(); root.openDetail("settings"); Qt.callLater(function() { settingsScroll.contentItem.contentY = 0 }) }
        onMoved: function(left, top) {
            root.fitWindow(desktopLyrics)
            desktopPreferences.left = Math.round(desktopLyrics.x)
            desktopPreferences.top = Math.round(desktopLyrics.y)
            desktopPreferences.sync()
        }
        function refreshArea() { availableArea = player.desktopWorkArea(x + 32, y + 32) }
        onXChanged: refreshArea()
        onYChanged: refreshArea()
        function place() {
            refreshArea()
            var area = availableArea
            x = desktopPreferences.left === -10000 ? area.x + (area.width - width) / 2 : desktopPreferences.left
            y = desktopPreferences.top === -10000 ? area.y + area.height - height - 70 : desktopPreferences.top
            root.fitWindow(desktopLyrics)
        }
        onVisibleChanged: if (visible) Qt.callLater(place)
        onHeightChanged: if (visible) Qt.callLater(function() { root.fitWindow(desktopLyrics) })
        Component.onCompleted: Qt.callLater(place)
    }
    property string section: ""
    property string detail: ""
    property bool selectingTracks: false
    property var selectedTrackIds: []
    property string searchKind: "songs"
    property bool showingOnlinePlaylist: false
    property string onlinePlaylistSource: "search"
    readonly property var activeList: {
        for (var i = 0; i < player.playlists.length; ++i)
            if (player.playlists[i].id === player.activePlaylist) {
                var list = player.playlists[i]
                return list.id === "favorites" ? Object.assign({}, list, {name: root.t("收藏夹")}) : list
            }
        return { name: root.t("歌单"), description: "", trackCount: 0 }
    }
    readonly property var playbackModes: [
        { key: "sequential", name: root.t("顺序播放"), icon: "sequential" },
        { key: "loop", name: root.t("列表循环"), icon: "loop" },
        { key: "single", name: root.t("单曲循环"), icon: "single" },
        { key: "shuffle", name: root.t("随机播放"), icon: "shuffle" }
    ]
    readonly property string playbackModeName: playbackModes.filter(function(mode) { return mode.key === player.playbackMode })[0].name
    readonly property int baseWidth: 380
    property rect workArea: Qt.rect(0, 0, 1280, 720)
    readonly property real contentScale: Math.min(Math.max(0.9, Math.min(1.4, appearance.windowScale)), Math.max(0.4, (workArea.width - 16) / baseWidth))
    readonly property bool darkMode: appearance.mode === 2 || (appearance.mode === 0 && Qt.styleHints.colorScheme === Qt.Dark)
    readonly property color backdrop: darkMode ? "#131728" : "#F1F1F8"
    readonly property color surface: darkMode ? "#191C2C" : "#F5F5FA"
    readonly property color elevated: darkMode ? "#252C43" : "#F9F9FC"
    readonly property color control: darkMode ? "#2A2D49" : "#EEEDF8"
    readonly property color ink: darkMode ? "#F2F1FB" : "#18213D"
    readonly property color muted: darkMode ? "#ADB1C8" : "#68718D"
    readonly property color accent: darkMode ? "#B79BFF" : "#703AFF"
    readonly property color accentInk: darkMode ? "#191C2C" : "#FFFFFF"
    readonly property color primaryFill: darkMode ? "#8554EE" : accent
    readonly property color primaryInk: darkMode ? "#FBF9FF" : accentInk
    readonly property color selection: darkMode ? "#3E2D65" : "#EBE5FA"
    readonly property color line: darkMode ? "#343950" : "#DFDFEB"
    readonly property color fieldBorder: darkMode ? "#778AB0" : "#9E9DB6"
    readonly property color danger: darkMode ? "#FF9B93" : "#B42318"
    readonly property int lyricDisplayMode: Math.max(0, Math.min(2, lyricPreferences.displayMode))
    readonly property int lyricFontSize: Math.max(10, Math.min(30, lyricPreferences.fontSize))
    readonly property real lyricLineSpacing: normalizedLineSpacing(lyricPreferences.lineSpacing)
    function normalizedLineSpacing(value) {
        return isFinite(value) && value >= 0.8 && value <= 3.0 ? Math.round(value * 10) / 10 : 1.0
    }
    Settings {
        id: appearance; category: "appearance"
        property int mode: 0
        property real windowScale: 1.0
        property real backgroundOpacity: 1.0
        property bool animationsEnabled: true
        property int iconX: 48
        property int iconY: 120
    }
    Settings {
        id: lyricPreferences; category: "lyrics"
        property int displayMode: 0
        property int fontSize: 18
        property real lineSpacing: 1.0
        Component.onCompleted: {
            displayMode = root.lyricDisplayMode
            fontSize = root.lyricFontSize
            lineSpacing = root.lyricLineSpacing
            // An invalid stored type can leave the default property unchanged.
            setValue("lineSpacing", lineSpacing)
            sync()
        }
    }
    visible: false
    width: Math.ceil(baseWidth * contentScale)
    height: Math.min(Math.max(160, workArea.height - 16), Math.ceil(shell.implicitHeight * contentScale))
    title: root.t("浮音 1.4.0-preview · 桌面预览")
    color: "transparent"
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
    font.family: "Microsoft YaHei UI"; font.pixelSize: 14
    palette.window: surface; palette.base: elevated; palette.text: ink
    palette.windowText: ink; palette.buttonText: ink; palette.button: surface
    palette.highlight: accent; palette.highlightedText: accentInk
    palette.placeholderText: muted; palette.light: selection; palette.midlight: line
    palette.mid: selection; palette.dark: accent
    onClosing: function(close) { quitting = true; close.accepted = true; player.quit() }
    onXChanged: if (visible) fitTimer.restart()
    onYChanged: if (visible) fitTimer.restart()
    onHeightChanged: if (visible) fitTimer.restart()
    onWidthChanged: if (visible) fitTimer.restart()
    function fitWindow(window) {
        var area = player.desktopWorkArea(window.x + 32, window.y + 32)
        if (window === root) workArea = area
        window.x = Math.round(Math.max(area.x + 8, Math.min(window.x, area.x + area.width - window.width - 8)))
        window.y = Math.round(Math.max(area.y + 8, Math.min(window.y, area.y + area.height - window.height - 8)))
    }
    function activateWindow() {
        if (!root.visible) { root.x = floating.x; root.y = floating.y }
        fitWindow(root); root.showNormal(); root.raise(); root.requestActivate()
    }
    function collapse() {
        playbackMenu.close(); volumeMenu.close(); lyricTiming.close()
        libraryMenu.close(); selectionMenu.close(); targetSheet.close(); importSheet.close(); exportSheet.close(); descriptionSheet.close(); playlistDialog.close(); deleteDialog.close(); removeDialog.close(); trackMenu.close()
        queueMenu.close(); historyMenu.close(); playbackRemoval.close(); shortcutCapture.close()
        floating.x = root.x; floating.y = root.y
        section = ""; detail = ""; showingOnlinePlaylist = false; root.hide(); fitWindow(floating); saveIconPosition()
    }
    function saveIconPosition() { appearance.iconX = floating.x; appearance.iconY = floating.y }
    function resetAppearance() {
        appearance.windowScale = 1
        appearance.backgroundOpacity = 1
        floating.x = 48; floating.y = 120
        fitWindow(floating); saveIconPosition()
        root.x = floating.x; root.y = floating.y
        fitWindow(root); appearance.sync()
    }
    function toggleSection(name) { section = section === name ? "" : name; detail = ""; showingOnlinePlaylist = false }
    function openDetail(name) {
        showingOnlinePlaylist = false
        if (name === "favorites") { player.selectPlaylist("favorites"); section = "playlist"; detail = ""; return }
        section = "more"; detail = name
        if (name === "rankings" && !player.rankingsLoading && player.rankings.length === 0 && !root.t(player.rankingsMessage).length)
            player.libraryAction("loadRankings", {})
    }
    function openOnlinePlaylist(id, source) {
        onlinePlaylistSource = source
        showingOnlinePlaylist = true
        player.libraryAction("openOnline", { id: id })
        Qt.callLater(function() { onlineTracks.positionViewAtBeginning() })
    }
    function closeOnlinePlaylist() {
        showingOnlinePlaylist = false; detail = onlinePlaylistSource
        Qt.callLater(function() { (root.onlinePlaylistSource === "rankings" ? rankingResults : playlistResults).forceActiveFocus() })
    }
    function isFeaturedRanking(name) { return /热歌|新歌|飙升|原创/.test(name || "") }
    function rankingGlyph(name) {
        if (/飙升/.test(name || "")) return "trending"
        if (/新歌/.test(name || "")) return "spark"
        if (/原创/.test(name || "")) return "music"
        return "ranking"
    }
    function hasSelected(id) { return selectedTrackIds.indexOf(id) >= 0 }
    function toggleTrack(id) {
        var ids = selectedTrackIds.slice(), at = ids.indexOf(id)
        if (at >= 0) ids.splice(at, 1); else ids.push(id)
        selectedTrackIds = ids
    }
    function clearTrackSelection() { selectedTrackIds = []; selectingTracks = false }
    function playlistTargets(includeNew, excludeCurrent) {
        var list = []
        for (var i = 0; i < player.playlists.length; ++i) {
            var p = player.playlists[i]
            if (!excludeCurrent || p.id !== player.activePlaylist)
                list.push({ id: p.id, name: (p.id === "favorites" ? root.t("收藏夹") : p.name) + (root.languageCode === "en" ? " (" : "（") + p.trackCount + root.t(" 首）") })
        }
        if (includeNew) list.push({ id: "new", name: root.t("新建歌单") })
        return list
    }
    function usePlaylist(id) {
        if (player.activePlaylist !== id) player.selectPlaylist(id)
        return player.activePlaylist === id
    }
    function startSearch() {
        if (player.searchSources.length === 0 || (searchKind === "songs" ? player.searching : player.playlistSearching)) return
        showingOnlinePlaylist = false
        if (searchKind === "playlists") player.libraryAction("searchPlaylists", { query: keywords.text })
        else player.search(keywords.text)
        Qt.callLater(function() { (root.searchKind === "playlists" ? playlistResults : results).positionViewAtBeginning() })
    }
    function toggleSearchSource(source) {
        var sources = player.searchSources.slice(), at = sources.indexOf(source)
        if (at >= 0) sources.splice(at, 1); else sources.push(source)
        player.searchSources = sources
        Qt.callLater(function() { (root.searchKind === "playlists" ? playlistResults : results).positionViewAtBeginning() })
    }
    function musicSourceName(source) {
        if (source === "netease") return root.t("网易云")
        if (source === "tencent") return root.t("QQ音乐")
        if (source === "kuwo") return root.t("酷我音乐")
        return root.t("在线音乐")
    }
    function onlineSourceName(resource) {
        return root.t(resource.sourceName) || musicSourceName(resource.source || "netease")
    }
    function trackSourceName(track) {
        if (track.sourceName) return root.t(track.sourceName)
        if (track.source === "netease") return root.t("网易云")
        if (track.source === "tencent") return root.t("QQ音乐")
        if (track.source === "kuwo") return root.t("酷我音乐")
        return root.t("本地")
    }
    function openTrackMenu(track, sourceId, index) {
        trackMenu.track = track
        trackMenu.sourceId = sourceId || ""
        trackMenu.trackId = track.id
        trackMenu.trackName = track.name
        trackMenu.trackIndex = index === undefined ? -1 : index
        trackMenu.open()
    }
    function queueIndex(entryId) {
        for (var i = 0; i < player.queueEntries.length; ++i)
            if (player.queueEntries[i].queueId === entryId) return i
        return -1
    }
    function confirmPlaybackRemoval(kind, entryId, name) {
        playbackRemoval.kind = kind
        playbackRemoval.entryId = entryId || ""
        playbackRemoval.trackName = name || ""
        playbackRemoval.open()
    }
    function historyTime(timestamp) {
        var date = new Date(Number(timestamp))
        if (!isFinite(date.getTime())) return ""
        return Qt.formatDateTime(date, "MM-dd hh:mm")
    }
    function captureShortcut(binding) {
        shortcutCapture.action = binding.action
        shortcutCapture.label = binding.label
        shortcutCapture.previous = binding.sequence
        shortcutCapture.candidate = ""
        shortcutCapture.failure = ""
        shortcutCapture.open()
    }
    function showTransfer(move) {
        targetSheet.sourceId = player.activePlaylist
        targetSheet.ids = selectedTrackIds.slice()
        targetSheet.mode = move ? "move" : "copy"
        targetSheet.targets = playlistTargets(false, true)
        transferTarget.currentIndex = targetSheet.targets.length ? 0 : -1
        targetSheet.open()
    }
    function showOnlineImport() {
        targetSheet.sourceId = player.activePlaylist
        targetSheet.ids = []
        targetSheet.mode = "online"
        targetSheet.targets = playlistTargets(true, false)
        transferTarget.currentIndex = Math.max(0, transferTarget.indexOfValue(player.activePlaylist))
        targetSheet.open()
    }
    function showImport() {
        importSheet.targets = playlistTargets(true, false)
        importTarget.currentIndex = Math.max(0, importTarget.indexOfValue(player.activePlaylist))
        importText.text = ""
        importSheet.open()
    }
    function showExport() {
        exportSheet.sourceId = player.activePlaylist
        exportSheet.ids = selectingTracks ? selectedTrackIds.slice() : []
        exportSheet.listName = activeList.name
        exportSheet.open()
    }
    function openExportFile() {
        playlistExportFile.sourceId = exportSheet.sourceId
        playlistExportFile.ids = exportSheet.ids.slice()
        var name = exportSheet.listName.trim().replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_").slice(0, 120).replace(/[. ]+$/g, "")
        if (!name.length) name = root.t("浮音歌单")
        if (/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)/i.test(name)) name = "_" + name
        if (!/\.json$/i.test(name)) name += ".json"
        var folder = playlistExportFile.currentFolder.toString()
        if (!folder.length) folder = StandardPaths.writableLocation(StandardPaths.DocumentsLocation).toString()
        playlistExportFile.selectedFile = folder.replace(/\/+$/g, "") + "/" + encodeURIComponent(name)
        exportSheet.close()
        playlistExportFile.open()
    }
    function confirmRemove(ids, sourceId) {
        removeDialog.sourceId = sourceId || player.activePlaylist
        removeDialog.ids = ids.slice()
        removeDialog.open()
    }
    Connections {
        target: player
        property string lastPlaylist: ""
        function onToggleWindowRequested() { root.visible ? root.collapse() : root.activateWindow() }
        function onLibraryChanged() {
            if (lastPlaylist !== player.activePlaylist) {
                lastPlaylist = player.activePlaylist
                root.clearTrackSelection()
                filter.text = ""
                Qt.callLater(function() { songs.positionViewAtBeginning() })
                // Async imports may select a newly created list while an old menu is open.
                selectionMenu.close(); trackMenu.close(); targetSheet.close(); descriptionSheet.close()
                playlistDialog.close(); deleteDialog.close(); removeDialog.close(); exportSheet.close()
            } else {
                var available = player.tracks.map(function(t) { return t.id })
                root.selectedTrackIds = root.selectedTrackIds.filter(function(id) { return available.indexOf(id) >= 0 })
            }
            lists.updateSelection()
        }
    }
    function clock(ms) { var s = Math.floor(ms / 1000); return Math.floor(s / 60) + ":" + (s % 60 < 10 ? "0" : "") + s % 60 }
    function lyricText(text) { return text.replace(/\[(?:\d+:\d+(?:[.:]\d+)?|(?:ar|ti|al|by|offset):[^\]]*)\]/g, "").trim() }
    function chooseMusic() { picker.open() }
    function quitApp() { quitting = true; player.quit() }
    Component.onCompleted: {
        floating.x = appearance.iconX; floating.y = appearance.iconY
        fitWindow(floating); workArea = player.desktopWorkArea(floating.x + 32, floating.y + 32)
    }
    Timer { id: fitTimer; interval: 150; onTriggered: root.fitWindow(root) }
    Timer {
        interval: 1500; repeat: true; running: true
        onTriggered: {
            var window = root.visible ? root : floating
            var area = player.desktopWorkArea(window.x + 32, window.y + 32)
            if (root.workArea.x !== area.x || root.workArea.y !== area.y || root.workArea.width !== area.width || root.workArea.height !== area.height) { root.workArea = area; root.fitWindow(window) }
            if (desktopLyrics.visible) { desktopLyrics.refreshArea(); root.fitWindow(desktopLyrics) }
        }
    }
    Shortcut { sequence: "Ctrl+F"; enabled: root.visible && !root.sheetOpen; onActivated: {root.showingOnlinePlaylist=false;root.openDetail("search");Qt.callLater(function(){(root.searchKind === "playlists" ? playlistResults : results).positionViewAtBeginning();keywords.forceActiveFocus()})} }
    Shortcut { sequence: "Ctrl+O"; enabled: root.visible && !root.sheetOpen; onActivated: root.chooseMusic() }
    readonly property bool sheetOpen: playbackMenu.opened || lyricTiming.opened || libraryMenu.opened || selectionMenu.opened || targetSheet.opened || importSheet.opened || exportSheet.opened || descriptionSheet.opened || playlistDialog.opened || deleteDialog.opened || removeDialog.opened || trackMenu.opened || queueMenu.opened || historyMenu.opened || playbackRemoval.opened || shortcutCapture.opened
    Shortcut { sequence: "Escape"; enabled: root.visible && !root.sheetOpen; onActivated: {if(root.selectingTracks)root.clearTrackSelection();else if(root.showingOnlinePlaylist)root.closeOnlinePlaylist();else if(root.detail.length)root.detail="";else if(root.section.length)root.section="";else root.collapse()} }
    component Glyph: Canvas {
        id: glyph
        property string kind: "music"
        property color tint: root.ink
        implicitWidth: 20; implicitHeight: 20
        Accessible.ignored: true
        onTintChanged: requestPaint()
        onKindChanged: requestPaint()
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        onPaint: {
            var c = getContext("2d"); c.reset(); c.scale(width / 24, height / 24)
            c.strokeStyle = tint; c.fillStyle = tint; c.lineWidth = 1.7; c.lineCap = "round"; c.lineJoin = "round"
            function path(points, close, fill) { c.beginPath(); c.moveTo(points[0][0], points[0][1]); for (var i=1;i<points.length;i++) c.lineTo(points[i][0],points[i][1]); if(close)c.closePath(); if(fill)c.fill(); else c.stroke() }
            if (kind === "search") { c.beginPath(); c.arc(10,10,6,0,Math.PI*2); c.stroke(); path([[15,15],[21,21]]) }
            else if (kind === "play") path([[8,5],[19,12],[8,19]],true,true)
            else if (kind === "pause") { c.fillRect(7,5,3,14); c.fillRect(14,5,3,14) }
            else if (kind === "previous" || kind === "next") { if(kind==="previous"){c.translate(24,0);c.scale(-1,1)} path([[6,6],[15,12],[6,18]],true,true); c.fillRect(17,6,2,12) }
            else if (kind === "minus") path([[5,12],[19,12]])
            else if (kind === "more") { for(var k=5;k<=19;k+=7){c.beginPath();c.arc(k,12,1.7,0,Math.PI*2);c.fill()} }
            else if (kind === "close") { path([[6,6],[18,18]]); path([[18,6],[6,18]]) }
            else if (kind === "back") path([[15,5],[8,12],[15,19]])
            else if (kind === "forward") path([[9,5],[16,12],[9,19]])
            else if (kind === "chevron") path([[7,10],[12,15],[17,10]])
            else if (kind === "add") { path([[12,5],[12,19]]); path([[5,12],[19,12]]) }
            else if (kind === "refresh") { c.beginPath();c.arc(12,12,8,0.6,5.3);c.stroke();path([[16,3],[17,8],[22,7]]) }
            else if (kind === "ranking") { path([[4,20],[4,13],[9,13],[9,20]]);path([[9,20],[9,5],[15,5],[15,20]]);path([[15,20],[15,10],[20,10],[20,20]]);path([[3,20],[21,20]]) }
            else if (kind === "trending") { path([[3,18],[9,12],[13,15],[21,5]]);path([[15,5],[21,5],[21,11]]) }
            else if (kind === "spark") { path([[12,3],[14.5,9.5],[21,12],[14.5,14.5],[12,21],[9.5,14.5],[3,12],[9.5,9.5]],true) }
            else if (kind === "lyrics") { path([[6,3],[18,3],[18,21],[6,21]],true);path([[9,8],[15,8]]);path([[9,12],[15,12]]);path([[9,16],[13,16]]) }
            else if (kind === "import") { path([[4,15],[4,20],[20,20],[20,15]]);path([[12,3],[12,15]]);path([[7,10],[12,15],[17,10]]) }
            else if (kind === "exit") { path([[10,4],[4,4],[4,20],[10,20]]);path([[9,12],[21,12]]);path([[16,7],[21,12],[16,17]]) }
            else if (kind === "check") path([[5,12],[10,17],[19,7]])
            else if (kind === "up" || kind === "down") { if(kind === "down"){c.translate(0,24);c.scale(1,-1)} path([[12,20],[12,4]]);path([[6,10],[12,4],[18,10]]) }
            else if (kind === "sequential") {path([[4,6],[20,6]]);path([[4,12],[20,12]]);path([[4,18],[20,18],[16,14]]);path([[20,18],[16,22]])}
            else if (kind === "loop" || kind === "single") {
                path([[5,10],[5,6],[20,6],[17,3]]);path([[20,6],[17,9]]);
                path([[19,14],[19,18],[4,18],[7,21]]);path([[4,18],[7,15]]);
                if(kind === "single")path([[10,11],[12,9],[12,15]])
            }
            else if (kind === "shuffle") {path([[3,6],[7,6],[17,18],[21,18],[18,15]]);path([[21,18],[18,21]]);path([[3,18],[7,18],[17,6],[21,6],[18,3]]);path([[21,6],[18,9]])}
            else if (kind === "folder") path([[3,7],[10,7],[12,9],[21,9],[21,20],[3,20],[3,7],[3,4],[10,4],[12,7]])
            else if (kind === "list") { for(var y=6;y<=18;y+=6){path([[9,y],[21,y]]);c.fillRect(3,y-1,2,2)} }
            else if (kind === "drag") { for(var dy=6;dy<=18;dy+=6){c.fillRect(8,dy-1,2,2);c.fillRect(14,dy-1,2,2)} }
            else if (kind === "history") { c.beginPath();c.arc(12,12,8,3.5,9.3);c.stroke();path([[3,3],[3,9],[9,9]]);path([[12,7],[12,12],[16,14]]) }
            else if (kind === "timer") { c.beginPath();c.arc(12,13,8,0,Math.PI*2);c.stroke();path([[9,2],[15,2]]);path([[12,5],[12,13],[16,13]]) }
            else if (kind === "keyboard") { path([[3,6],[21,6],[21,18],[3,18]],true);for(var ky=9;ky<=12;ky+=3)for(var kx=6;kx<=18;kx+=4)c.fillRect(kx,ky,1.5,1);path([[8,15],[16,15]]) }
            else if (kind === "heart") { c.beginPath();c.moveTo(12,20);c.bezierCurveTo(-4,10,5,-1,12,7);c.bezierCurveTo(19,-1,28,10,12,20);c.stroke() }
            else if (kind === "settings") { for(var j=0;j<3;j++){var yy=6+j*6;path([[3,yy],[21,yy]]);c.clearRect(7+j*3,yy-2,4,4);c.strokeRect(7+j*3,yy-2,4,4)} }
            else if (kind === "volume") { path([[3,9],[7,9],[12,5],[12,19],[7,15],[3,15]],true);c.beginPath();c.arc(12,12,6,-0.8,0.8);c.stroke();c.beginPath();c.arc(12,12,10,-0.8,0.8);c.stroke() }
            else if (kind === "info") { c.beginPath();c.arc(12,12,9,0,Math.PI*2);c.stroke();path([[12,11],[12,17]]);c.beginPath();c.arc(12,7,0.8,0,Math.PI*2);c.fill() }
            else { path([[9,17],[9,5],[19,3],[19,15]]);c.beginPath();c.ellipse(3,16,6,4);c.fill();c.beginPath();c.ellipse(13,14,6,4);c.fill() }
        }
    }
    component Copy: Text { color: root.ink; textFormat: Text.PlainText; font: root.font; elide: Text.ElideRight }
    component Action: Button {
        id: action
        property bool primary: false
        property bool quiet: false
        property bool selected: false
        property string glyph: ""
        property real cornerRadius: 8
        readonly property real visualScale: appearance.animationsEnabled && down ? 0.965 : 1
        implicitHeight: 34; implicitWidth: text.length ? Math.max(48, contentItem.implicitWidth + 20) : 34
        padding: 8; spacing: 6; hoverEnabled: true; font.pixelSize: 13
        Accessible.name: text
        contentItem: Item {
            implicitWidth: buttonContents.implicitWidth; implicitHeight: buttonContents.implicitHeight
            scale: action.visualScale
            Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: action.down ? 80 : 150; easing.type: Easing.OutCubic } }
            RowLayout {
                id: buttonContents; anchors.centerIn: parent; spacing: 6
                Glyph { visible: action.glyph.length > 0; kind: action.glyph; tint: action.primary && action.enabled ? root.primaryInk : action.selected && action.enabled ? root.accent : (action.enabled ? root.ink : root.muted); Layout.preferredWidth: 18; Layout.preferredHeight: 18 }
                Text { visible: action.text.length > 0; text: action.text; font: action.font; color: action.primary && action.enabled ? root.primaryInk : action.selected && action.enabled ? root.accent : action.enabled ? root.ink : root.muted; horizontalAlignment: Text.AlignHCenter }
            }
        }
        background: Rectangle {
            radius: action.cornerRadius
            scale: action.visualScale
            Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: action.down ? 80 : 150; easing.type: Easing.OutCubic } }
            color: !action.enabled ? (action.quiet ? "transparent" : root.backdrop) : action.primary ? (action.down ? Qt.darker(root.primaryFill, 1.12) : root.primaryFill) : action.selected || action.down || action.hovered ? root.selection : action.quiet ? "transparent" : root.control
            border.width: action.activeFocus ? 2 : 0
            border.color: root.accent
            gradient: action.primary && action.enabled ? pearlFinish : null
            Gradient {
                id: pearlFinish
                GradientStop { position: 0; color: root.darkMode ? (action.down ? Qt.lighter(root.primaryFill, 1.04) : "#B097FF") : action.down ? root.accent : Qt.lighter(root.accent, 1.35) }
                GradientStop { position: 0.38; color: action.down ? Qt.darker(root.primaryFill, 1.08) : root.primaryFill }
                GradientStop { position: 1; color: root.darkMode ? (action.down ? Qt.darker(root.primaryFill, 1.24) : "#683CE0") : Qt.darker(root.accent, action.down ? 1.18 : 1.10) }
            }
            Rectangle {
                anchors.fill: parent; anchors.margins: 2
                radius: Math.max(0, parent.radius - 2); color: "transparent"
                visible: action.primary && action.enabled
                border.width: 0.8; border.color: root.darkMode ? "#44E7DEFF" : "#BBFFFFFF"
            }
        }
        ToolTip.visible: hovered && ToolTip.text.length > 0
        ToolTip.delay: 600
    }
    component SourceChoice: Action {
        id: sourceChoice
        required property string sourceId
        implicitWidth: 90; implicitHeight: 30; padding: 6
        quiet: true; checkable: true
        checked: player.searchSources.indexOf(sourceId) >= 0
        selected: checked
        Accessible.role: Accessible.CheckBox
        Accessible.name: text + root.t("曲库")
        Accessible.checkable: true; Accessible.checked: checked
        contentItem: Item {
            implicitWidth: sourceContents.implicitWidth; implicitHeight: sourceContents.implicitHeight
            scale: sourceChoice.visualScale
            Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: sourceChoice.down ? 80 : 150; easing.type: Easing.OutCubic } }
            RowLayout {
                id: sourceContents; anchors.centerIn: parent; spacing: 4
                Rectangle {
                    Layout.preferredWidth: 14; Layout.preferredHeight: 14; radius: 3
                    color: sourceChoice.checked ? root.accent : "transparent"
                    border.width: 1; border.color: sourceChoice.checked ? root.accent : root.fieldBorder
                    Glyph { anchors.fill: parent; visible: sourceChoice.checked; kind: "check"; tint: root.accentInk }
                }
                Text { text: sourceChoice.text; font.family: root.font.family; font.pixelSize: 12; color: sourceChoice.checked ? root.accent : root.ink }
            }
        }
        onClicked: root.toggleSearchSource(sourceId)
    }
    component TrackMetadata: RowLayout {
        id: metadata
        required property var track
        spacing: 6
        Copy { objectName: "trackSourceName"; text: root.trackSourceName(metadata.track); color: root.muted; font.pixelSize: 12; Layout.minimumWidth: implicitWidth; Layout.preferredWidth: implicitWidth }
        Copy { text: "·"; color: root.muted; font.pixelSize: 12; Accessible.ignored: true }
        Copy { text: metadata.track.artist || root.t("本地音频"); Layout.fillWidth: true; Layout.minimumWidth: 0; color: root.muted; font.pixelSize: 12 }
    }
    component MenuEntry: Action {
        id: entry
        property bool navigates: true
        quiet: true; implicitHeight: 40
        contentItem: Item {
            implicitWidth: menuContent.implicitWidth; implicitHeight: menuContent.implicitHeight
            scale: entry.visualScale
            Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: entry.down ? 80 : 150; easing.type: Easing.OutCubic } }
            RowLayout {
                id: menuContent; anchors.fill: parent; spacing: 12
                Glyph { kind: entry.glyph; tint: entry.enabled ? root.accent : root.muted; Layout.preferredWidth: 18; Layout.preferredHeight: 18 }
                Copy { text: entry.text; font.pixelSize: 13; Layout.fillWidth: true; color: entry.enabled ? root.ink : root.muted }
                Glyph { visible: entry.navigates; kind: "forward"; tint: root.muted; Layout.preferredWidth: 16; Layout.preferredHeight: 16 }
            }
        }
    }
    component PressRow: ItemDelegate {
        id: pressRow
        hoverEnabled: true
        property bool emphasized: false
        readonly property real visualScale: appearance.animationsEnabled && down ? 0.97 : 1
        padding: 10
        background: Rectangle {
            radius: 10; color: pressRow.hovered || pressRow.down ? root.selection : pressRow.emphasized ? root.control : "transparent"
            border.width: pressRow.activeFocus ? 2 : 0; border.color: root.accent
            scale: pressRow.visualScale
            Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: pressRow.down ? 80 : 150; easing.type: Easing.OutCubic } }
        }
    }
    component Field: TextField {
        id: field
        implicitHeight: 36; leftPadding: 11; rightPadding: 11; font.pixelSize: 13
        color: root.ink; placeholderTextColor: root.muted; selectionColor: root.accent; selectedTextColor: root.accentInk; selectByMouse: true
        background: Rectangle { radius: 8; color: root.elevated; border.color: field.activeFocus ? root.accent : root.fieldBorder; border.width: field.activeFocus ? 2 : 1 }
    }
    component Choice: ComboBox {
        id: choice
        property bool localizedOptions: false
        // Replacing translated model labels resets ComboBox.currentIndex.
        // Keep the selected value separately and restore it after model updates.
        property int selectedIndex: -1
        function syncSelectedIndex() { if (selectedIndex >= 0) currentIndex = selectedIndex }
        onSelectedIndexChanged: syncSelectedIndex()
        onModelChanged: Qt.callLater(syncSelectedIndex)
        implicitHeight: 36; implicitWidth: 210; leftPadding: 11; rightPadding: 34; hoverEnabled: true; font.pixelSize: 13
        contentItem: Text {
            text: choice.localizedOptions ? root.t(choice.displayText) : choice.displayText; font: choice.font; color: choice.enabled ? root.ink : root.muted; verticalAlignment: Text.AlignVCenter; elide: Text.ElideRight
            scale: appearance.animationsEnabled && choice.down ? 0.97 : 1
            Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: choice.down ? 80 : 150; easing.type: Easing.OutCubic } }
        }
        indicator: Glyph { kind: "chevron"; tint: root.muted; x: choice.width - width - 12; y: (choice.height-height)/2 }
        background: Rectangle {
            radius: 8; color: choice.hovered || choice.down ? root.selection : root.elevated; border.color: choice.activeFocus ? root.accent : root.fieldBorder; border.width: choice.activeFocus ? 2 : 1
            scale: appearance.animationsEnabled && choice.down ? 0.97 : 1
            Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: choice.down ? 80 : 150; easing.type: Easing.OutCubic } }
        }
        delegate: ItemDelegate {
            id: option
            required property int index
            text: choice.localizedOptions ? root.t(choice.textAt(index)) : choice.textAt(index)
            Accessible.name: text
            width: choice.width; height: 36; highlighted: choice.highlightedIndex === index
            contentItem: RowLayout {
                scale: appearance.animationsEnabled && option.down ? 0.97 : 1
                Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: option.down ? 80 : 150; easing.type: Easing.OutCubic } }
                Text { text: choice.localizedOptions ? root.t(choice.textAt(option.index)) : choice.textAt(option.index); color: choice.currentIndex === option.index ? root.accent : root.ink; font: choice.font; elide: Text.ElideRight; Layout.fillWidth: true }
                Glyph { kind: "check"; tint: root.accent; visible: choice.currentIndex === option.index }
            }
            background: Rectangle { color: option.highlighted || choice.currentIndex === option.index ? root.selection : root.elevated; radius: 4 }
        }
        popup: Popup {
            parent: Overlay.overlay
            popupType: Popup.Item; z: 1100; dim: false
            property point origin: Qt.point(0, 0)
            onAboutToShow: origin = choice.mapToItem(Overlay.overlay, 0, 0)
            x: Math.max(8, Math.min(origin.x, root.width - width * root.contentScale - 8))
            y: Math.max(8, Math.min(origin.y + (choice.height + 4) * root.contentScale, root.height - height * root.contentScale - 8))
            width: choice.width; padding: 6; scale: root.contentScale; transformOrigin: Popup.TopLeft
            implicitHeight: Math.min(260, root.height / root.contentScale - 24, contentItem.implicitHeight + 12)
            contentItem: ListView { clip: true; implicitHeight: contentHeight; model: choice.popup.visible ? choice.delegateModel : null; currentIndex: choice.highlightedIndex; ScrollIndicator.vertical: ScrollIndicator {} }
            background: Rectangle { color: root.elevated; radius: 10; border.color: root.fieldBorder }
            enter: Transition {
                enabled: appearance.animationsEnabled
                NumberAnimation { target: choice.popup.contentItem; property: "opacity"; from: 0; to: 1; duration: 130; easing.type: Easing.OutCubic }
            }
        }
    }
    component TrackSlider: Slider {
        id: slider
        implicitWidth: vertical ? 32 : 200
        implicitHeight: vertical ? 110 : 32
        leftPadding: 8; rightPadding: 8; topPadding: vertical ? 8 : 0; bottomPadding: vertical ? 8 : 0
        background: Rectangle {
            objectName: "sliderTrack"
            x: slider.vertical ? (slider.width - width) / 2 : slider.leftPadding
            y: slider.vertical ? slider.topPadding + slider.handle.height / 2 : (slider.height - height) / 2
            width: slider.vertical ? 4 : slider.availableWidth
            height: slider.vertical ? slider.availableHeight - slider.handle.height : 4
            radius: 2; color: root.line
            Rectangle {
                objectName: "playedFill"
                width: slider.vertical ? parent.width : slider.position * parent.width
                height: slider.vertical ? slider.position * parent.height : parent.height
                y: slider.vertical ? parent.height - height : 0
                radius: 2; color: slider.enabled ? root.accent : root.muted
            }
        }
        handle: Rectangle {
            x: slider.vertical ? (slider.width - width) / 2 : slider.leftPadding + slider.visualPosition * slider.availableWidth - width / 2
            y: slider.vertical ? slider.topPadding + slider.visualPosition * (slider.availableHeight - height) : (slider.height - height) / 2
            width: 14; height: 14; radius: 7; color: slider.enabled ? root.accent : root.muted
            border.width: slider.activeFocus ? 3 : 0; border.color: root.ink
        }
    }
    component SettingsHeading: RowLayout {
        required property string label
        property string glyph: "settings"
        Layout.fillWidth: true; Layout.topMargin: 12; Layout.bottomMargin: 2; spacing: 8
        Glyph { kind: parent.glyph; tint: root.accent; Layout.preferredWidth: 16; Layout.preferredHeight: 16 }
        Copy { text: parent.label; font.pixelSize: 13; font.weight: Font.DemiBold }
        Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: root.line; Layout.leftMargin: 4 }
    }
    component QualityChoice: Choice {
        id: qualityChoice
        required property string sourceId
        Layout.fillWidth: true; Layout.minimumWidth: 0; implicitHeight: 34
        property var levels: sourceId === "netease" ? ["standard", "higher", "exhigh", "lossless", "hires"]
            : sourceId === "tencent" ? ["standard", "exhigh", "lossless", "master"] : ["standard", "exhigh", "lossless"]
        model: sourceId === "netease" ? [root.t("标准"), root.t("较高"), root.t("极高"), root.t("无损 FLAC"), "Hi-Res"]
            : sourceId === "tencent" ? [root.t("标准"), root.t("高音质 320"), root.t("无损 FLAC"), root.t("实验母带")] : [root.t("标准"), root.t("高音质 320"), root.t("无损 FLAC")]
        selectedIndex: Math.max(0, qualityChoice.levels.indexOf(player.sourceQualities[qualityChoice.sourceId]))
        onActivated: function(index) { player.setSourceQuality(sourceId, levels[index]) }
    }

    component SectionTab: Action {
        id: tab
        property string sectionName
        Layout.fillWidth: true
        selected: root.section === sectionName
        quiet: true; font.weight: selected ? Font.DemiBold : Font.Normal
        onClicked: root.toggleSection(sectionName)
        Accessible.description: selected ? root.t("已展开，再次点击收起") : root.t("点击展开")
    }
    // Keep popups in the card's overlay: native popup windows can fall behind
    // the always-on-top Windows card after focus/activation changes.
    component UpPopup: Popup {
        id: upPopup
        required property Item anchorItem
        parent: Overlay.overlay
        popupType: Popup.Item
        z: 1000
        property point origin: Qt.point(0, 0)
        onAboutToShow: origin = anchorItem.mapToItem(Overlay.overlay, anchorItem.width, 0)
        x: Math.max(8, Math.min(origin.x - width * root.contentScale, root.width - width * root.contentScale - 8))
        y: Math.max(8, origin.y - (height + 8) * root.contentScale)
        scale: root.contentScale; transformOrigin: Popup.TopLeft
        margins: 8; padding: 8; focus: true; modal: true; dim: false
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        background: PrismaticSurface { radius: 12; darkMode: root.darkMode; elevated: true }
        enter: Transition {
            enabled: appearance.animationsEnabled
            NumberAnimation { targets: [upPopup.contentItem, upPopup.background]; property: "opacity"; from: 0; to: 1; duration: 130; easing.type: Easing.OutCubic }
        }
        onClosed: if (root.visible) anchorItem.forceActiveFocus()
    }
    UpPopup {
        id: playbackMenu; objectName: "playbackModePopup"; anchorItem: modeButton
        width: 176; height: 160
        contentItem: Column {
            Repeater {
                model: root.playbackModes
                delegate: ItemDelegate {
                    id: modeOption
                    required property var modelData
                    width: 160; height: 36
                    Accessible.name: modelData.name
                    Accessible.role: Accessible.RadioButton
                    Accessible.checked: player.playbackMode === modelData.key
                    onClicked: { player.setPlaybackMode(modelData.key); playbackMenu.close() }
                    contentItem: RowLayout {
                        spacing: 10
                        scale: appearance.animationsEnabled && modeOption.down ? 0.97 : 1
                        Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: modeOption.down ? 80 : 150; easing.type: Easing.OutCubic } }
                        Glyph { kind: modeOption.modelData.icon; tint: root.accent; implicitWidth: 18; implicitHeight: 18 }
                        Copy { text: modeOption.modelData.name; Layout.fillWidth: true }
                        Glyph { kind: "check"; visible: player.playbackMode === modeOption.modelData.key; tint: root.accent; implicitWidth: 16; implicitHeight: 16 }
                    }
                    background: Rectangle {
                        radius: 8; color: modeOption.hovered || modeOption.down || player.playbackMode === modeOption.modelData.key ? root.selection : "transparent"
                        border.width: modeOption.activeFocus ? 2 : 0; border.color: root.accent
                    }
                }
            }
        }
    }
    UpPopup {
        id: volumeMenu; objectName: "volumePopup"; anchorItem: volumeButton
        width: 68; height: 160
        onOpened: volumeSlider.forceActiveFocus()
        contentItem: ColumnLayout {
            spacing: 4
            Copy { text: player.volume + "%"; font.pixelSize: 12; Layout.alignment: Qt.AlignHCenter; color: root.muted }
            TrackSlider {
                id: volumeSlider; objectName: "volumeSlider"
                orientation: Qt.Vertical; Layout.fillHeight: true; Layout.alignment: Qt.AlignHCenter
                from: 0; to: 100; stepSize: 1; wheelEnabled: true
                Accessible.name: root.t("音量，") + player.volume + "%"
                onMoved: player.setVolume(Math.round(value))
                Binding { target: volumeSlider; property: "value"; value: player.volume; when: !volumeSlider.pressed }
            }
            Glyph { kind: "volume"; tint: root.muted; Layout.preferredWidth: 16; Layout.preferredHeight: 16; Layout.alignment: Qt.AlignHCenter; Accessible.ignored: true }
        }
    }
    UpPopup {
        id: lyricTiming; anchorItem: timingButton; width: 264; height: 146
        contentItem: ColumnLayout {
            spacing: 6
            Copy { text: root.t("歌词时间微调"); font.weight: Font.DemiBold }
            Hint { text: root.t("仅此歌曲 · 正值让歌词提前") }
            RowLayout {
                Action { text: "−0.5s"; Accessible.name: root.t("歌词延后半秒"); enabled: player.lyricOffset > -10000; onClicked: player.setLyricOffset(player.lyricOffset - 500) }
                Action { text: (player.lyricOffset > 0 ? "+" : "") + (player.lyricOffset / 1000).toFixed(1) + "s"; Layout.fillWidth: true; ToolTip.text: root.t("点击归零"); Accessible.name: root.t("歌词偏移，点击归零"); onClicked: player.setLyricOffset(0) }
                Action { text: "+0.5s"; Accessible.name: root.t("歌词提前半秒"); enabled: player.lyricOffset < 10000; onClicked: player.setLyricOffset(player.lyricOffset + 500) }
            }
        }
    }
    component Hint: Copy {
        Layout.fillWidth: true; color: root.muted; font.pixelSize: 12
        wrapMode: Text.Wrap; elide: Text.ElideNone
    }
    component SongRow: Rectangle {
        id: song
        required property var track
        property string extraText: ""
        property string playObjectName: ""
        property string menuObjectName: ""
        property string note: ""
        property int rank: 0
        signal playRequested()
        signal extraRequested()
        signal removeRequested()
        signal menuRequested()
        property bool removable: false
        width: ListView.view ? ListView.view.width : 320
        height: note.length ? 76 : 58; radius: 8
        color: player.currentTrack === track.id ? root.selection : songHover.hovered ? root.backdrop : "transparent"
        HoverHandler { id: songHover }
        RowLayout {
            anchors.fill: parent; anchors.margins: 6; spacing: 8
            Copy { visible: song.rank > 0; text: song.rank; font.pixelSize: 13; font.weight: song.rank <= 3 ? Font.DemiBold : Font.Normal; color: song.rank <= 3 ? root.accent : root.muted; elide: Text.ElideNone; Layout.preferredWidth: Math.max(24, implicitWidth); Layout.minimumWidth: implicitWidth; horizontalAlignment: Text.AlignHCenter }
            ColumnLayout {
                Layout.fillWidth: true; Layout.minimumWidth: 0; spacing: 4
                Copy { Layout.fillWidth: true; text: song.track.name; font.weight: Font.DemiBold }
                TrackMetadata { Layout.fillWidth: true; Layout.minimumWidth: 0; track: song.track }
                Copy { visible: song.note.length > 0; Layout.fillWidth: true; text: song.note; font.pixelSize: 12; color: root.muted }
            }
            Action { objectName: song.playObjectName; glyph: "play"; quiet: true; enabled: !player.busy; Accessible.name: root.t("播放 ") + song.track.name + "，" + root.trackSourceName(song.track); ToolTip.text: Accessible.name; onClicked: song.playRequested() }
            Action { visible: song.extraText.length > 0; glyph: "add"; quiet: true; Accessible.name: root.t("加入当前歌单"); ToolTip.text: root.t("加入当前歌单"); onClicked: song.extraRequested() }
            Action { visible: song.removable; glyph: "close"; quiet: true; Accessible.name: root.t("移除 ") + song.track.name; ToolTip.text: Accessible.name; onClicked: song.removeRequested() }
            Action { objectName: song.menuObjectName; glyph: "more"; quiet: true; Accessible.name: root.t("操作 ") + song.track.name; ToolTip.text: Accessible.name; onClicked: song.menuRequested() }
        }
    }
    FileDialog {
        id: picker; title: root.t("导入音频 · 最大 30 MiB")
        nameFilters: [root.t("音频文件 (*.mp3 *.wav *.flac *.ogg *.m4a *.aac)")]
        onAccepted: player.importFile(selectedFile)
    }
    Window {
        id: floating; objectName: "floatingIcon"; transientParent: null
        visible: !root.visible && !root.quitting
        width: 64; height: 64; color: "transparent"
        flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        onClosing: function(event) { event.accepted = true; root.quitApp() }
        Rectangle {
            anchors.fill: parent; anchors.margins: 2; radius: 30; color: "transparent"
            Image {
                objectName: "floatingIconArtwork"
                anchors.fill: parent
                source: "qrc:/icons/floatmusic-floating.png"
                fillMode: Image.PreserveAspectFit; smooth: true; mipmap: true
            }
            Rectangle {
                anchors.fill: parent; radius: 30; color: "transparent"
                border.color: root.accent; border.width: iconMouse.containsMouse ? 2 : 0
            }
            MouseArea {
                id: iconMouse
                anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                acceptedButtons: Qt.LeftButton | Qt.RightButton
                Accessible.role: Accessible.Button; Accessible.name: root.t("浮音，点击展开，拖动移动")
                Accessible.onPressAction: root.activateWindow()
                property real startX; property real startY; property real originX; property real originY
                property bool moved: false
                onPressed: function(mouse) { originX=floating.x; originY=floating.y; startX=floating.x+mouse.x; startY=floating.y+mouse.y; moved=false }
                onPositionChanged: function(mouse) {
                    if (!pressed || !(pressedButtons & Qt.LeftButton)) return
                    var dx=floating.x+mouse.x-startX, dy=floating.y+mouse.y-startY
                    if (Math.abs(dx)+Math.abs(dy)>Qt.styleHints.startDragDistance) moved=true
                    if(moved){floating.x=originX+dx;floating.y=originY+dy}
                }
                onReleased: function(mouse) {
                    root.fitWindow(floating); root.saveIconPosition()
                    if(mouse.button===Qt.RightButton)iconMenu.popup()
                    else if(!moved)root.activateWindow()
                }
                Menu {
                    id: iconMenu
                    MenuItem { text: root.t("打开播放器"); onTriggered: root.activateWindow() }
                    MenuItem { objectName: "desktopLyricsMenuToggle"; text: desktopPreferences.enabled ? root.t("隐藏桌面歌词") : root.t("显示桌面歌词"); onTriggered: root.toggleDesktopLyrics() }
                    MenuItem { text: root.t("退出浮音"); onTriggered: root.quitApp() }
                }
            }
        }
        DropArea {
            anchors.fill: parent
            onDropped: function(event) {
                root.activateWindow()
                if(event.hasUrls && event.urls.length===1){player.importFile(event.urls[0]);event.acceptProposedAction()}
                else player.rejectDrop()
            }
        }
    }
    // Opacity belongs to the painted background, never to the native window or controls.
    PrismaticSurface {
        anchors.fill: parent; radius: 20 * root.contentScale; darkMode: root.darkMode
        backgroundOpacity: Math.max(0.2, Math.min(1, appearance.backgroundOpacity))
    }
    DropArea {
        id: drop; anchors.fill: parent
        onDropped: function(event) {if(event.hasUrls && event.urls.length===1){player.importFile(event.urls[0]);event.acceptProposedAction()}else player.rejectDrop()}
    }
    Item {
        id: stage
        width: root.baseWidth; height: root.height / root.contentScale
        scale: root.contentScale; transformOrigin: Item.TopLeft
        clip: true
        ScrollView {
            id: outerScroll; objectName: "outerScroll"; anchors.fill: parent; clip: true
            contentWidth: availableWidth; contentHeight: shell.implicitHeight
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
            ColumnLayout {
                id: shell; width: outerScroll.availableWidth; spacing: 0
                ColumnLayout {
                    id: playerCard; objectName: "playerBar"
                    Layout.fillWidth: true; Layout.margins: 20; Layout.topMargin: 8; Layout.bottomMargin: 16
                    spacing: 8
                    RowLayout {
                        Layout.fillWidth: true; spacing: 8
                        Item {
                            Layout.fillWidth: true; implicitHeight: 34
                            RowLayout {
                                anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; spacing: 8
                                Glyph { tint: root.accent }
                                Copy { text: root.t("浮音"); font.weight: Font.DemiBold; color: root.muted }
                            }
                            MouseArea { anchors.fill: parent; cursorShape: Qt.SizeAllCursor; onPressed: root.startSystemMove() }
                            ToolTip.text: root.t("拖动这里移动窗口"); ToolTip.visible: headerHover.hovered
                            HoverHandler { id: headerHover }
                        }
                        Action { objectName: "sleepStatusButton"; visible: player.sleepMode !== "off"; glyph: "timer"; quiet: true; selected: true; Accessible.name: root.t(player.sleepText); ToolTip.text: root.t(player.sleepText); onClicked: root.openDetail("sleep") }
                        Action { objectName: "collapseButton"; glyph: "minus"; quiet: true; Accessible.name: root.t("收为图标"); ToolTip.text: root.t("收为图标"); onClicked: root.collapse() }
                    }
                    Copy {
                        Layout.fillWidth: true; text: player.currentTrack.length ? player.title : root.t(player.title); font.pixelSize: 22; font.weight: Font.DemiBold
                        wrapMode: Text.Wrap; maximumLineCount: 2
                    }
                    RowLayout {
                        Layout.fillWidth: true; spacing: 6
                        Copy { objectName: "currentSourceName"; visible: player.currentSourceName.length > 0; text: root.t(player.currentSourceName); color: root.muted; font.pixelSize: 12; Layout.minimumWidth: implicitWidth }
                        Copy { visible: player.currentSourceName.length > 0; text: "·"; color: root.muted; font.pixelSize: 12; Accessible.ignored: true }
                        Copy { Layout.fillWidth: true; Layout.minimumWidth: 0; text: player.artist || (player.ready ? root.t("本地音频") : root.t("在“更多”中搜索或导入音乐")); color: root.muted; font.pixelSize: 13 }
                    }
                    TrackSlider {
                        id: progress; objectName: "progress"; Layout.fillWidth: true
                        from: 0; to: Math.max(1,player.duration); enabled: player.seekable; Accessible.name: root.t("播放进度")
                        onPressedChanged: if(!pressed&&enabled)player.seek(value)
                        onMoved: if(!pressed&&enabled)player.seek(value)
                        Binding { target: progress; property: "value"; value: player.position; when: !progress.pressed }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        Copy { text: root.clock(progress.pressed ? progress.value : player.position); color: root.muted; font.pixelSize: 12 }
                        Item { Layout.fillWidth: true }
                        Copy { text: root.clock(player.duration); color: root.muted; font.pixelSize: 12 }
                    }
                    RowLayout {
                        Layout.fillWidth: true; spacing: 10
                        Item { Layout.fillWidth: true }
                        Action { objectName: "previousButton"; glyph: "previous"; quiet: true; Accessible.name: root.t("上一首"); ToolTip.text: root.t("上一首"); enabled: !player.busy&&player.queueEntries.length>0; onClicked: player.previous() }
                        Action { objectName: "playPauseButton"; glyph: player.playing ? "pause" : "play"; primary: true; implicitWidth: 46; implicitHeight: 46; cornerRadius: 23; Accessible.name: player.playing ? root.t("暂停") : root.t("播放"); ToolTip.text: Accessible.name; enabled: player.ready&&!player.busy; onClicked: player.toggle() }
                        Action { objectName: "nextButton"; glyph: "next"; quiet: true; Accessible.name: root.t("下一首"); ToolTip.text: root.t("下一首"); enabled: !player.busy&&player.queueEntries.length>0; onClicked: player.next() }
                        Action {
                            id: modeButton; objectName: "playbackModeButton"; glyph: player.playbackMode; quiet: true
                            Accessible.name: root.t("播放模式：") + root.playbackModeName
                            ToolTip.text: Accessible.name
                            onClicked: playbackMenu.opened ? playbackMenu.close() : playbackMenu.open()
                        }
                        Action {
                            id: volumeButton; objectName: "volumeButton"; glyph: "volume"; quiet: true; selected: volumeMenu.opened
                            Accessible.name: root.t("音量：") + player.volume + "%"; ToolTip.text: Accessible.name
                            onClicked: volumeMenu.opened ? volumeMenu.close() : volumeMenu.open()
                        }
                        Item { Layout.fillWidth: true }
                    }
                    Hint { visible: player.busy; text: root.t(player.status) }
                    RowLayout {
                        visible: root.t(player.error).length>0; Layout.fillWidth: true
                        Hint { objectName: "playbackError"; text: root.t(player.error); color: root.danger; maximumLineCount: 4; elide: Text.ElideRight }
                        Action { objectName: "retryPlayback"; text: root.t("重试"); enabled: !player.busy; onClicked: player.retryPlayback() }
                    }
                    Rectangle {
                        Layout.fillWidth: true; implicitHeight: 40; radius: 10; color: root.control
                        RowLayout {
                            anchors.fill: parent; anchors.margins: 3; spacing: 3
                            SectionTab { objectName: "lyricsTab"; text: root.t("歌词"); glyph: "lyrics"; sectionName: "lyrics" }
                            SectionTab { objectName: "playlistTab"; text: root.t("歌单"); glyph: "list"; sectionName: "playlist" }
                            SectionTab { objectName: "moreTab"; text: root.t("更多"); glyph: "more"; sectionName: "more" }
                        }
                    }
                }
                Rectangle { visible: root.section.length>0; Layout.fillWidth: true; implicitHeight: 1; color: root.line }
                Item {
                    id: drawer; objectName: "sharedDrawer"
                    visible: root.section.length>0
                    Layout.fillWidth: true
                    readonly property bool tallList: root.section === "playlist" || ["search", "rankings", "queue", "history"].indexOf(root.detail) >= 0
                    Layout.preferredHeight: visible ? (root.section==="more" && root.detail==="" ? moreMenu.implicitHeight+32 : Math.max(tallList ? 280 : 180, Math.min(tallList ? 520 : 350, (workArea.height - 16) / contentScale - playerCard.implicitHeight - 48))) : 0
                    StackLayout {
                        anchors.fill: parent; anchors.margins: 16
                        currentIndex: root.section==="lyrics" ? 0 : root.section==="playlist" ? 1 : 2
                        ColumnLayout {
                            spacing: 12
                            RowLayout {
                                Layout.fillWidth: true
                                Copy { text: player.lyricLines.length > 0 ? root.t("逐句歌词") : root.t("歌词"); font.weight: Font.DemiBold; Layout.fillWidth: true }
                                Action { objectName: "retryLyrics"; text: root.t("刷新"); quiet: true; Accessible.name: root.t("重新获取歌词"); enabled: player.online&&!player.lyricsLoading; onClicked: player.retryLyrics() }
                                Action { id: timingButton; glyph: "settings"; quiet: true; enabled: player.lyricLines.length > 0; Accessible.name: root.t("歌词时间微调"); ToolTip.text: Accessible.name; onClicked: lyricTiming.open() }
                            }
                            Hint { text: root.t(player.lyricsMessage); color: player.lyricsFailed ? root.danger : root.muted }
                            Item {
                                Layout.fillWidth: true; Layout.fillHeight: true
                                SmoothLyricList {
                                    motionEnabled: appearance.animationsEnabled
                                    id: lyricView; objectName: "timedLyrics"
                                    anchors.fill: parent; clip: true
                                    visible: count > 0
                                    model: player.lyricLines
                                    currentIndex: player.currentLyricIndex
                                    // The current item remains instantiated even when scrolled out of view.
                                    readonly property bool currentAbove: currentItem ? currentItem.y + currentItem.height / 2 < contentY + height / 2 : currentIndex < 0
                                    highlightFollowsCurrentItem: false
                                    boundsBehavior: Flickable.StopAtBounds
                                    spacing: 0; cacheBuffer: height
                                    header: Item { width: 1; height: lyricView.height * 0.3 }
                                    footer: Item { width: 1; height: lyricView.height * 0.5 }
                                    Keys.onPressed: function(event) {
                                        if (event.key === Qt.Key_Up || event.key === Qt.Key_Down || event.key === Qt.Key_PageUp || event.key === Qt.Key_PageDown) {
                                            manualBrowsing = true
                                            var delta = event.key === Qt.Key_Up ? -48 : event.key === Qt.Key_Down ? 48 : event.key === Qt.Key_PageUp ? -height : height
                                            contentY = Math.max(originY, Math.min(originY + Math.max(0, contentHeight - height), contentY + delta))
                                            event.accepted = true
                                        }
                                    }
                                    activeFocusOnTab: true
                                    Accessible.name: root.t("逐句歌词，使用上下方向键翻看")
                                    ScrollBar.vertical: ScrollBar { onPressedChanged: if (pressed) lyricView.manualBrowsing = true }
                                    MouseArea {
                                        anchors.fill: parent; acceptedButtons: Qt.NoButton
                                        onWheel: function(wheel) { lyricView.manualBrowsing = true; wheel.accepted = false }
                                    }
                                    delegate: Item {
                                        id: lyricRow
                                        required property var modelData
                                        required property int index
                                        readonly property bool active: index === player.currentLyricIndex
                                        width: lyricView.width - 14
                                        height: lyricWords.implicitHeight
                                        FontMetrics { id: lyricMetrics; font: lyricOriginalText.font }
                                        Rectangle {
                                            objectName: "lyricMarker-" + lyricRow.index
                                            anchors.left: parent.left; width: 3; height: Math.min(20, lyricMetrics.height, parent.height)
                                            y: Math.max(0, Math.min(parent.height - height, lyricWords.y + lyricOriginalText.baselineOffset - lyricMetrics.ascent + (lyricMetrics.height - height) / 2))
                                            radius: 1.5; color: root.accent; visible: lyricRow.active
                                        }
                                        Column {
                                            id: lyricWords; anchors.left: parent.left; anchors.right: parent.right; anchors.margins: 14; anchors.verticalCenter: parent.verticalCenter; spacing: 0
                                            Text {
                                                id: lyricOriginalText
                                                objectName: "lyricOriginal-" + lyricRow.index
                                                width: parent.width; textFormat: Text.PlainText; wrapMode: Text.Wrap
                                                lineHeight: root.lyricLineSpacing; lineHeightMode: Text.ProportionalHeight
                                                text: root.lyricDisplayMode === 1 ? (lyricRow.modelData.translation || (lyricRow.modelData.original ? root.t("暂无该句译文") : "♪")) : (lyricRow.modelData.original || "♪")
                                                font.family: root.font.family; font.pixelSize: root.lyricFontSize + (lyricRow.active ? 2 : 0); font.weight: lyricRow.active ? Font.DemiBold : Font.Normal
                                                color: lyricRow.active ? root.accent : root.muted
                                            }
                                            Text {
                                                objectName: "lyricTranslation-" + lyricRow.index
                                                width: parent.width; visible: root.lyricDisplayMode === 2 && text.length > 0
                                                text: lyricRow.modelData.translation; textFormat: Text.PlainText; wrapMode: Text.Wrap
                                                lineHeight: root.lyricLineSpacing; lineHeightMode: Text.ProportionalHeight
                                                font.family: root.font.family; font.pixelSize: root.lyricFontSize + (lyricRow.active ? 2 : 0); color: lyricRow.active ? root.ink : root.muted
                                            }
                                        }
                                    }
                                    Connections {
                                        target: root
                                        function onLyricFontSizeChanged() { Qt.callLater(lyricView.followCurrent) }
                                        function onLyricDisplayModeChanged() { Qt.callLater(lyricView.followCurrent) }
                                        function onLyricLineSpacingChanged() { Qt.callLater(lyricView.followCurrent) }
                                    }
                                    Connections {
                                        target: player
                                        property string lastTrack: ""
                                        function onChanged() {
                                            if (lastTrack !== player.currentTrack) {
                                                lastTrack = player.currentTrack
                                                lyricView.manualBrowsing = false
                                                Qt.callLater(lyricView.followCurrent)
                                            }
                                        }
                                    }
                                    Connections {
                                        target: lyricPreferences
                                        function onDisplayModeChanged() { Qt.callLater(lyricView.followCurrent) }
                                        function onFontSizeChanged() { Qt.callLater(lyricView.followCurrent) }
                                    }
                                }
                                ScrollView {
                                    anchors.fill: parent; visible: player.lyricLines.length === 0; clip: true; contentWidth: availableWidth
                                    TextArea {
                                        id: plainLyrics
                                        objectName: "lyricsText"; readOnly: true; selectByMouse: true; wrapMode: TextEdit.Wrap
                                        textFormat: TextEdit.PlainText; font.pixelSize: root.lyricFontSize; color: root.ink
                                        selectionColor: root.accent; selectedTextColor: root.accentInk; background: null; padding: 8
                                        text: root.lyricDisplayMode===0 ? root.lyricText(player.lyrics) : root.lyricDisplayMode===1 ? (root.lyricText(player.translation)||root.t("暂无译文")) : root.lyricText(player.lyrics)+(player.translation.length>0 ? root.t("\n\n—— 译文 ——\n\n")+root.lyricText(player.translation) : "")
                                        function applyLineSpacing() { player.applyLyricLineSpacing(textDocument, root.lyricLineSpacing) }
                                        Component.onCompleted: applyLineSpacing()
                                        onTextChanged: applyLineSpacing()
                                        onFontChanged: applyLineSpacing()
                                        Connections {
                                            target: root
                                            function onLyricLineSpacingChanged() { plainLyrics.applyLineSpacing() }
                                        }
                                    }
                                }
                                Action {
                                    objectName: "returnToCurrentLyric"
                                    anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: 8
                                    visible: lyricView.visible && lyricView.manualBrowsing && player.currentLyricIndex >= 0
                                    selected: true; glyph: lyricView.currentAbove ? "up" : "down"
                                    Accessible.name: root.t("回到当前歌词并恢复跟随"); ToolTip.text: Accessible.name
                                    onClicked: lyricView.returnToCurrent()
                                }
                            }
                        }
                        ColumnLayout {
                            spacing: 0
                            ColumnLayout {
                                id: playlistTools
                                parent: songs.headerItem
                                width: songs.width - 12; spacing: 6
                                RowLayout {
                                    Layout.fillWidth: true; spacing: 6
                                    Choice {
                                        id: lists; objectName: "playlistSelector"; Layout.fillWidth: true; Layout.minimumWidth: 0
                                        model: root.playlistLabels; textRole: "name"; valueRole: "id"; Accessible.name: root.t("当前歌单")
                                        function updateSelection() { currentIndex = indexOfValue(player.activePlaylist) }
                                        Component.onCompleted: updateSelection()
                                        onModelChanged: Qt.callLater(updateSelection)
                                        onActivated: { root.clearTrackSelection(); player.selectPlaylist(currentValue) }
                                    }
                                    Action { glyph: "more"; quiet: true; Accessible.name: root.t("歌单管理"); ToolTip.text: Accessible.name; onClicked: libraryMenu.open() }
                                }
                                Button {
                                    id: descriptionButton
                                    Layout.fillWidth: true; implicitHeight: 30; padding: 0
                                    Accessible.name: root.t("歌单简介，") + (root.activeList.description || root.t("点击添加简介"))
                                    contentItem: RowLayout {
                                        scale: appearance.animationsEnabled && descriptionButton.down ? 0.97 : 1
                                        Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: descriptionButton.down ? 80 : 150; easing.type: Easing.OutCubic } }
                                        Copy { text: player.tracks.length + root.t(" 首"); color: root.accent; font.pixelSize: 12 }
                                        Copy { text: root.activeList.description || root.t("点击添加歌单简介"); Layout.fillWidth: true; Layout.minimumWidth: 0; color: root.muted; font.pixelSize: 12 }
                                    }
                                    background: Rectangle { radius: 6; color: parent.hovered ? root.selection : "transparent"; border.color: root.accent; border.width: parent.activeFocus ? 2 : 0 }
                                    onClicked: { descriptionSheet.sourceId = player.activePlaylist; descriptionText.text = root.activeList.description || ""; descriptionSheet.open() }
                                }
                                RowLayout {
                                    visible: !root.selectingTracks; Layout.fillWidth: true; spacing: 6
                                    Field { id: filter; Layout.fillWidth: true; Layout.minimumWidth: 0; placeholderText: root.t("筛选歌名或歌手"); Accessible.name: root.t("筛选当前歌单") }
                                    Action { text: root.t("多选"); quiet: true; enabled: player.tracks.length > 0; onClicked: { filter.text = ""; root.selectingTracks = true } }
                                }
                                RowLayout {
                                    visible: root.selectingTracks; Layout.fillWidth: true; spacing: 4
                                    Action {
                                        text: root.t("全选"); quiet: true; enabled: root.selectedTrackIds.length < player.tracks.length
                                        onClicked: root.selectedTrackIds = player.tracks.map(function(t) { return t.id })
                                    }
                                    Action { text: root.t("清空"); quiet: true; enabled: root.selectedTrackIds.length > 0; onClicked: root.selectedTrackIds = [] }
                                    Copy { text: root.t("已选 ") + root.selectedTrackIds.length; Layout.fillWidth: true; color: root.muted; font.pixelSize: 12 }
                                    Action { text: root.t("操作"); selected: true; enabled: root.selectedTrackIds.length > 0; onClicked: selectionMenu.open() }
                                    Action { glyph: "close"; quiet: true; Accessible.name: root.t("退出多选"); ToolTip.text: Accessible.name; onClicked: root.clearTrackSelection() }
                                }
                                Hint { text: root.t(player.libraryMessage); visible: text.length > 0; maximumLineCount: 2; elide: Text.ElideRight }
                                Hint { text: root.t(player.playbackMessage); visible: text.length > 0; maximumLineCount: 2; elide: Text.ElideRight }
                            }
                            ListView {
                                id: songs; objectName: "playlistView"; Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 58; clip: true
                                headerPositioning: ListView.InlineHeader
                                header: Item { width: songs.width - 12; height: playlistTools.implicitHeight + 8 }
                                model: player.tracks.filter(function(t) { return (t.name + " " + (t.artist || "")).toLowerCase().indexOf(filter.text.trim().toLowerCase()) >= 0 })
                                property int dragFrom: -1
                                property int dragTo: -1
                                property real dragPointerY: 0
                                readonly property bool reorderEnabled: !root.selectingTracks && filter.text.trim().length === 0
                                boundsBehavior: Flickable.StopAtBounds
                                // Keep the pressed delegate alive while edge-scrolling a long playlist.
                                currentIndex: dragFrom
                                highlightFollowsCurrentItem: false
                                function updateDropTarget() {
                                    var firstRowY = headerItem ? headerItem.y + headerItem.height : originY
                                    dragTo = Math.max(0, Math.min(count - 1, Math.floor((contentY + dragPointerY - firstRowY) / 58)))
                                }
                                function endReorder(commit) {
                                    var from = dragFrom, to = dragTo
                                    dragFrom = -1; dragTo = -1
                                    if (commit && from >= 0 && to >= 0 && from !== to)
                                        player.libraryAction("moveTrack", { from: from, to: to })
                                }
                                onModelChanged: endReorder(false)
                                onVisibleChanged: if (!visible) endReorder(false)
                                ScrollBar.vertical: ScrollBar {}
                                Timer {
                                    interval: 45; repeat: true; running: songs.dragFrom >= 0
                                    onTriggered: {
                                        var delta = songs.dragPointerY < 28 ? -12 : songs.dragPointerY > songs.height - 28 ? 12 : 0
                                        if (delta) {
                                            songs.contentY = Math.max(songs.originY, Math.min(songs.originY + Math.max(0, songs.contentHeight - songs.height), songs.contentY + delta))
                                            songs.updateDropTarget()
                                        }
                                    }
                                }
                                delegate: Rectangle {
                                    id: libraryTrack
                                    required property var modelData
                                    required property int index
                                    readonly property bool selected: root.hasSelected(modelData.id)
                                    width: songs.width - 10; height: 58; radius: 8
                                    color: selected || player.currentTrack === modelData.id ? root.selection : trackHover.hovered ? root.backdrop : "transparent"
                                    opacity: songs.dragFrom === index ? 0.6 : 1
                                    HoverHandler { id: trackHover }
                                    Rectangle {
                                        anchors.left: parent.left; anchors.right: parent.right; height: 3; radius: 1.5
                                        y: songs.dragTo > songs.dragFrom ? parent.height - height : 0
                                        color: root.accent; visible: songs.dragFrom >= 0 && songs.dragTo === libraryTrack.index && songs.dragTo !== songs.dragFrom
                                    }
                                    RowLayout {
                                        anchors.fill: parent; spacing: 4
                                        CheckBox {
                                            visible: root.selectingTracks; checked: libraryTrack.selected
                                            Layout.preferredWidth: 34; implicitHeight: 34
                                            Accessible.name: root.t("选择 ") + libraryTrack.modelData.name
                                            onClicked: root.toggleTrack(libraryTrack.modelData.id)
                                        }
                                        Item {
                                            visible: !root.selectingTracks; Layout.preferredWidth: 30; Layout.fillHeight: true
                                            Glyph { anchors.centerIn: parent; kind: "drag"; tint: songs.reorderEnabled ? root.muted : root.line }
                                            MouseArea {
                                                id: dragHandle; anchors.fill: parent; enabled: songs.reorderEnabled
                                                preventStealing: true; cursorShape: pressed ? Qt.ClosedHandCursor : Qt.OpenHandCursor
                                                property real pressY: 0
                                                property bool moved: false
                                                onPressed: function(mouse) { pressY = mouse.y; moved = false }
                                                onPositionChanged: function(mouse) {
                                                    if (!pressed) return
                                                    if (!moved && Math.abs(mouse.y - pressY) < Qt.styleHints.startDragDistance) return
                                                    if (!moved) { moved = true; songs.dragFrom = libraryTrack.index }
                                                    songs.dragPointerY = mapToItem(songs, mouse.x, mouse.y).y
                                                    songs.updateDropTarget()
                                                }
                                                onReleased: songs.endReorder(true)
                                                onCanceled: songs.endReorder(false)
                                            }
                                            ToolTip.visible: handleHover.hovered
                                            ToolTip.text: songs.reorderEnabled ? root.t("拖动排序，也可在歌曲菜单中上移、下移") : root.t("清除筛选后可拖动排序")
                                            HoverHandler { id: handleHover }
                                        }
                                        Button {
                                            id: trackTap
                                            Layout.fillWidth: true; Layout.minimumWidth: 0; Layout.fillHeight: true; padding: 4
                                            Accessible.name: (root.selectingTracks ? root.t("选择 ") : root.t("播放 ")) + libraryTrack.modelData.name + "，" + root.trackSourceName(libraryTrack.modelData)
                                            contentItem: ColumnLayout {
                                                spacing: 4
                                                scale: appearance.animationsEnabled && trackTap.down ? 0.97 : 1
                                                Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: trackTap.down ? 80 : 150; easing.type: Easing.OutCubic } }
                                                Copy { text: libraryTrack.modelData.name; Layout.fillWidth: true; font.weight: Font.DemiBold }
                                                TrackMetadata { track: libraryTrack.modelData; Layout.fillWidth: true; Layout.minimumWidth: 0 }
                                            }
                                            background: Rectangle { color: "transparent"; radius: 8; border.width: parent.activeFocus ? 2 : 0; border.color: root.accent }
                                            onClicked: { if (root.selectingTracks) root.toggleTrack(libraryTrack.modelData.id); else if (!player.busy) player.playTrack(libraryTrack.modelData.id) }
                                        }
                                        Action {
                                            objectName: "libraryMenu_" + libraryTrack.modelData.id
                                            visible: !root.selectingTracks; glyph: "more"; quiet: true
                                            Accessible.name: root.t("管理 ") + libraryTrack.modelData.name; ToolTip.text: Accessible.name
                                            onClicked: {
                                                root.openTrackMenu(libraryTrack.modelData, player.activePlaylist,
                                                    player.tracks.map(function(t) { return t.id }).indexOf(libraryTrack.modelData.id))
                                            }
                                        }
                                    }
                                }
                                footer: Item {
                                    width: songs.width; height: songs.count === 0 ? 80 : 0
                                    Hint { anchors.centerIn: parent; width: parent.width - 16; horizontalAlignment: Text.AlignHCenter; visible: songs.count === 0; text: player.tracks.length ? root.t("没有匹配的歌曲") : root.t("暂无歌曲，去“更多”搜索或导入") }
                                }
                            }
                        }
                        ColumnLayout {
                            spacing: 10
                            RowLayout {
                                visible: root.detail === "settings" && !root.showingOnlinePlaylist; Layout.fillWidth: true
                                Action { glyph: "back"; text: root.t("更多"); quiet: true; onClicked: root.detail="" }
                                Item { Layout.fillWidth: true }
                                Copy { text: root.detail==="search" ? root.t("搜索音乐") : root.detail==="favorites" ? root.t("我的收藏") : root.t("设置"); font.weight: Font.DemiBold }
                            }
                            StackLayout {
                                Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
                                currentIndex: root.showingOnlinePlaylist ? 4 : root.detail==="search" ? 1 : root.detail==="settings" ? 2 : root.detail==="rankings" ? 3 : root.detail==="queue" ? 5 : root.detail==="history" ? 6 : root.detail==="sleep" ? 7 : 0
                                ColumnLayout {
                                    id: moreMenu; spacing: 4
                                    MenuEntry { text: root.t("搜索音乐"); glyph: "search"; Layout.fillWidth: true; onClicked: root.openDetail("search") }
                                    MenuEntry { objectName: "rankingsEntry"; text: root.t("排行榜"); glyph: "ranking"; Layout.fillWidth: true; onClicked: root.openDetail("rankings") }
                                    MenuEntry { text: root.t("我的收藏"); glyph: "heart"; Layout.fillWidth: true; onClicked: root.openDetail("favorites") }
                                    MenuEntry { objectName: "queueEntry"; text: root.t("播放队列 · ") + player.queueEntries.length + root.t(" 首"); glyph: "list"; Layout.fillWidth: true; onClicked: root.openDetail("queue") }
                                    MenuEntry { objectName: "historyEntry"; text: root.t("最近播放"); glyph: "history"; Layout.fillWidth: true; onClicked: root.openDetail("history") }
                                    MenuEntry { objectName: "sleepEntry"; text: player.sleepMode === "off" ? root.t("睡眠定时") : root.t(player.sleepText); glyph: "timer"; Layout.fillWidth: true; onClicked: root.openDetail("sleep") }
                                    Rectangle { Layout.fillWidth: true; Layout.topMargin: 4; Layout.bottomMargin: 4; implicitHeight: 1; color: root.line }
                                    MenuEntry { text: root.t("导入音乐"); glyph: "import"; Layout.fillWidth: true; enabled: !player.busy; onClicked: root.chooseMusic() }
                                    MenuEntry { text: player.currentFavorite ? root.t("取消收藏") : root.t("收藏当前"); glyph: "heart"; navigates: false; Layout.fillWidth: true; enabled: player.ready; onClicked: player.toggleFavorite() }
                                    MenuEntry { text: root.t("设置"); glyph: "settings"; Layout.fillWidth: true; onClicked: root.openDetail("settings") }
                                    MenuEntry { objectName: "exitButton"; text: root.t("退出浮音"); glyph: "exit"; navigates: false; Layout.fillWidth: true; Accessible.name: root.t("退出并停止播放"); onClicked: root.quitApp() }
                                    Hint { text: root.t(player.favoriteMessage); visible: text.length>0 }
                                    Hint { text: root.t(player.playbackMessage); visible: text.length>0; maximumLineCount: 3; elide: Text.ElideRight }
                                    Hint { Layout.topMargin: 8; text: root.t("也可将音频拖入窗口或图标\nMP3 / WAV / FLAC / OGG / M4A / AAC · 单首 ≤ 30 MiB") }
                                }
                                Item {
                                    ColumnLayout {
                                        id: searchTools
                                        parent: root.searchKind === "playlists" ? playlistResults.headerItem : results.headerItem
                                        width: parent ? parent.width : 0; spacing: 6
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Action { glyph: "back"; text: root.t("更多"); quiet: true; onClicked: root.detail = "" }
                                            Item { Layout.fillWidth: true }
                                            Copy { text: root.searchKind === "songs" ? root.t("搜索音乐") : root.t("搜索歌单"); font.weight: Font.DemiBold }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 6
                                            Action { text: root.t("歌曲"); selected: root.searchKind === "songs"; quiet: true; Layout.fillWidth: true; onClicked: root.searchKind = "songs" }
                                            Action { text: root.t("歌单"); selected: root.searchKind === "playlists"; quiet: true; Layout.fillWidth: true; onClicked: root.searchKind = "playlists" }
                                        }
                                        Flow {
                                            objectName: "searchSourceChoices"; Layout.fillWidth: true; spacing: 6
                                            SourceChoice { objectName: "searchSourceNetease"; sourceId: "netease"; text: root.t("网易云") }
                                            SourceChoice { objectName: "searchSourceTencent"; sourceId: "tencent"; text: root.t("QQ音乐") }
                                            SourceChoice { objectName: "searchSourceKuwo"; sourceId: "kuwo"; text: root.t("酷我音乐") }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 6
                                            Field { id: keywords; objectName: "searchInput"; Layout.fillWidth: true; Layout.minimumWidth: 0; placeholderText: root.searchKind === "songs" ? root.t("输入歌名或歌手") : root.t("输入歌单名称"); Accessible.name: placeholderText; onAccepted: root.startSearch() }
                                            Action { objectName: "searchButton"; glyph: "search"; text: (root.searchKind === "songs" ? player.searching : player.playlistSearching) ? root.t("搜索中") : root.t("搜索"); enabled: player.searchSources.length > 0 && !(root.searchKind === "songs" ? player.searching : player.playlistSearching); onClicked: root.startSearch() }
                                        }
                                        Hint { text: root.searchKind === "songs" ? root.t(player.searchMessage) : root.t(player.playlistSearchMessage); visible: text.length > 0; maximumLineCount: 2; elide: Text.ElideRight }
                                        Hint { text: root.t(player.playbackMessage); visible: text.length > 0; maximumLineCount: 2; elide: Text.ElideRight }
                                    }
                                    StackLayout {
                                        anchors.fill: parent
                                        currentIndex: root.searchKind === "playlists" ? 1 : 0
                                        ListView {
                                            id: results; objectName: "searchResults"; clip: true
                                            boundsBehavior: Flickable.StopAtBounds
                                            headerPositioning: ListView.InlineHeader
                                            header: Item { width: results.width - 12; height: root.searchKind === "songs" ? searchTools.implicitHeight + 8 : 0 }
                                            model: player.searchResults; ScrollBar.vertical: ScrollBar {}
                                            delegate: SongRow {
                                                required property var modelData; required property int index
                                                track: modelData; extraText: "+"
                                                menuObjectName: "searchMenu_" + index
                                                onPlayRequested: player.playSearchResult(index)
                                                onExtraRequested: player.addSearchResult(index)
                                                onMenuRequested: root.openTrackMenu(modelData)
                                            }
                                            footer: Item {
                                                width: results.width; height: results.count === 0 ? 80 : 0
                                                Hint { anchors.centerIn: parent; width: parent.width; horizontalAlignment: Text.AlignHCenter; visible: results.count === 0; text: player.searching ? root.t("正在查找歌曲…") : player.searchSources.length === 0 ? root.t("勾选曲库后即可搜索歌曲") : root.t("输入歌名，找到想听的音乐") }
                                            }
                                        }
                                        ListView {
                                            id: playlistResults; objectName: "playlistSearchResults"; clip: true
                                            boundsBehavior: Flickable.StopAtBounds
                                            headerPositioning: ListView.InlineHeader
                                            header: Item { width: playlistResults.width - 12; height: root.searchKind === "playlists" ? searchTools.implicitHeight + 8 : 0 }
                                            model: player.playlistResults; ScrollBar.vertical: ScrollBar {}
                                            delegate: PressRow {
                                                id: onlineResult
                                                required property var modelData
                                                width: playlistResults.width - 10; height: Math.max(78, contentItem.implicitHeight + 20)
                                                Accessible.name: root.onlineSourceName(modelData) + "，" + modelData.name + "，" + modelData.trackCount + root.t(" 首，查看歌单")
                                                contentItem: ColumnLayout {
                                                    spacing: 4
                                                    scale: onlineResult.visualScale
                                                    Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: onlineResult.down ? 80 : 150; easing.type: Easing.OutCubic } }
                                                    Copy { text: onlineResult.modelData.name; Layout.fillWidth: true; Layout.minimumWidth: 0; font.weight: Font.DemiBold; wrapMode: Text.Wrap; maximumLineCount: 2 }
                                                    Copy { objectName: "playlistResultMetadata"; text: root.onlineSourceName(onlineResult.modelData) + " · " + onlineResult.modelData.trackCount + root.t(" 首") + (onlineResult.modelData.creator ? " · " + onlineResult.modelData.creator : ""); Layout.fillWidth: true; Layout.minimumWidth: 0; wrapMode: Text.Wrap; maximumLineCount: 2; color: root.muted; font.pixelSize: 12 }
                                                    Copy { text: onlineResult.modelData.description || root.t("暂无简介"); Layout.fillWidth: true; color: root.muted; font.pixelSize: 12 }
                                                }
                                                onClicked: root.openOnlinePlaylist(modelData.id, "search")
                                            }
                                            footer: Item {
                                                width: playlistResults.width; height: playlistResults.count === 0 ? 80 : 0
                                                Hint { anchors.centerIn: parent; width: parent.width; horizontalAlignment: Text.AlignHCenter; visible: playlistResults.count === 0; text: player.playlistSearching ? root.t("正在查找歌单…") : player.searchSources.length === 0 ? root.t("勾选曲库后即可搜索歌单") : root.t("输入歌单名称，发现想听的音乐") }
                                            }
                                        }
                                    }
                                }
                                ScrollView {
                                    id: settingsScroll; objectName: "settingsScroll"; clip: true; contentWidth: availableWidth
                                    property bool customServiceExpanded: player.apiBase.length > 0
                                    ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                                    ColumnLayout {
                                        width: settingsScroll.availableWidth; spacing: 8
                                        SettingsHeading { label: root.t("语言与桌面歌词"); glyph: "lyrics"; Layout.topMargin: 0 }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 10
                                            Copy { text: root.t("界面语言"); Layout.preferredWidth: 90; font.pixelSize: 13 }
                                            Choice {
                                                objectName: "languageSelector"; Layout.fillWidth: true; Accessible.name: root.t("界面语言")
                                                model: ["简体中文", "English"]; selectedIndex: root.languageCode === "en" ? 1 : 0
                                                onActivated: function(index) { root.setUiLanguage(index === 1 ? "en" : "zh_CN") }
                                            }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: root.t("桌面歌词"); Layout.fillWidth: true; font.pixelSize: 13 }
                                            Switch {
                                                objectName: "desktopLyricsSwitch"; checked: desktopPreferences.enabled
                                                Accessible.name: root.t("显示桌面歌词")
                                                onToggled: { desktopPreferences.enabled = checked; desktopPreferences.sync() }
                                            }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 10
                                            Copy { text: root.t("歌词行数"); Layout.preferredWidth: 90; font.pixelSize: 13 }
                                            Choice {
                                                objectName: "desktopLyricRowSelector"; Layout.fillWidth: true; Accessible.name: root.t("桌面歌词行数")
                                                model: [root.t("1 行"), root.t("3 行"), root.t("5 行"), root.t("7 行"), root.t("9 行")]
                                                selectedIndex: (desktopLyrics.lineCount - 1) / 2
                                                onActivated: function(index) { desktopPreferences.rowCount = index * 2 + 1; desktopPreferences.sync() }
                                            }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 10
                                            Copy { text: root.t("歌词主题"); Layout.preferredWidth: 90; font.pixelSize: 13 }
                                            Choice {
                                                objectName: "desktopLyricThemeSelector"; Layout.fillWidth: true; Accessible.name: root.t("歌词主题")
                                                model: [root.t("跟随播放器"), root.t("浅色"), root.t("深色")]
                                                selectedIndex: desktopPreferences.themeMode
                                                onActivated: function(index) { desktopPreferences.themeMode = index; desktopPreferences.sync() }
                                            }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: root.t("桌面歌词字号"); Layout.fillWidth: true; font.pixelSize: 13 }
                                            Copy { text: desktopLyrics.textSize; color: root.muted; font.pixelSize: 12 }
                                        }
                                        TrackSlider {
                                            id: desktopTextSize; objectName: "desktopLyricFontSlider"; Layout.fillWidth: true
                                            from: 18; to: 48; stepSize: 1; Accessible.name: root.t("桌面歌词字号")
                                            onMoved: { desktopPreferences.fontSize = Math.round(value); desktopPreferences.sync() }
                                            Binding { target: desktopTextSize; property: "value"; value: desktopLyrics.textSize; when: !desktopTextSize.pressed }
                                        }
                                        Hint { text: root.t("拖动歌词文字移动，文字外点击穿透。右键可切换主题、锁定位置或隐藏；空间不足时自动缩小文字。") }
                                        SettingsHeading { label: root.t("播放音质"); glyph: "volume"; Layout.topMargin: 0 }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 10
                                            Copy { text: root.t("网易云"); Layout.preferredWidth: root.languageCode === "en" ? 106 : 70; font.pixelSize: 13 }
                                            QualityChoice { objectName: "qualitySelector"; sourceId: "netease"; Accessible.name: root.t("网易云音质") }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 10
                                            Copy { text: root.t("QQ 音乐"); Layout.preferredWidth: root.languageCode === "en" ? 106 : 70; font.pixelSize: 13 }
                                            QualityChoice { objectName: "qqQualitySelector"; sourceId: "tencent"; Accessible.name: root.t("QQ音乐音质") }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 10
                                            Copy { text: root.t("酷我音乐"); Layout.preferredWidth: root.languageCode === "en" ? 106 : 70; font.pixelSize: 13 }
                                            QualityChoice { objectName: "kuwoQualitySelector"; sourceId: "kuwo"; Accessible.name: root.t("酷我音乐音质") }
                                        }
                                        Hint { text: root.t("各曲库单独保存；切换当前歌曲的音质会保留播放进度。高档不可用时自动尝试较低档。") }
                                        Hint { visible: player.sourceQualities.tencent === "master"; text: root.t("实验母带文件较大，可能增加加载时间；采样规格不代表原始母带品质。") }
                                        Hint { objectName: "actualQualityInfo"; visible: player.online; text: root.t(player.qualityInfo) }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 8
                                            Copy { text: root.t("输出设备"); font.pixelSize: 13; Layout.preferredWidth: 70 }
                                            Choice {
                                                id: outputs; objectName: "outputSelector"; Layout.fillWidth: true; Layout.minimumWidth: 0; localizedOptions: true
                                                model: player.audioOutputs; textRole: "name"; valueRole: "id"; Accessible.name: root.t("音频输出设备")
                                                function syncSelection() { currentIndex = indexOfValue(player.selectedOutput) }
                                                Component.onCompleted: syncSelection()
                                                onModelChanged: Qt.callLater(syncSelection)
                                                onActivated: player.selectOutput(currentValue)
                                                Connections { target: player; function onAudioSettingsChanged() { outputs.syncSelection() } }
                                            }
                                            Action { glyph: "refresh"; quiet: true; Accessible.name: root.t("刷新输出设备"); ToolTip.text: Accessible.name; onClicked: player.refreshOutputs() }
                                        }
                                        SettingsHeading { label: root.t("歌词"); glyph: "lyrics" }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 10
                                            Copy { text: root.t("显示方式"); Layout.preferredWidth: root.languageCode === "en" ? 106 : 70; font.pixelSize: 13 }
                                            Choice {
                                                objectName: "lyricModeSelector"; Layout.fillWidth: true; Accessible.name: root.t("歌词显示方式")
                                                model: [root.t("原文"), root.t("译文"), root.t("双语")]; selectedIndex: root.lyricDisplayMode
                                                onActivated: function(index) { lyricPreferences.displayMode = index; lyricPreferences.sync() }
                                            }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: root.t("字号"); font.pixelSize: 13; Layout.fillWidth: true }
                                            Copy { objectName: "lyricFontSizeValue"; text: root.lyricFontSize; color: root.muted; font.pixelSize: 12 }
                                        }
                                        TrackSlider {
                                            id: lyricSizeSlider; objectName: "lyricFontSizeSlider"; Layout.fillWidth: true
                                            from: 10; to: 30; stepSize: 1; Accessible.name: root.t("歌词字号，10 至 30")
                                            onMoved: { lyricPreferences.fontSize = Math.round(value); lyricPreferences.sync() }
                                            Binding { target: lyricSizeSlider; property: "value"; value: root.lyricFontSize; when: !lyricSizeSlider.pressed }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: root.t("行距"); font.pixelSize: 13; Layout.fillWidth: true }
                                            Copy { objectName: "lyricLineSpacingValue"; text: root.lyricLineSpacing.toFixed(1) + "×"; color: root.muted; font.pixelSize: 12 }
                                        }
                                        TrackSlider {
                                            id: lyricSpacingSlider; objectName: "lyricLineSpacingSlider"; Layout.fillWidth: true
                                            from: 0.8; to: 3.0; stepSize: 0.1; Accessible.name: root.t("歌词行距，0.8 至 3 倍")
                                            onMoved: { lyricPreferences.lineSpacing = root.normalizedLineSpacing(value); lyricPreferences.sync() }
                                            Binding { target: lyricSpacingSlider; property: "value"; value: root.lyricLineSpacing; when: !lyricSpacingSlider.pressed }
                                        }
                                        Copy {
                                            objectName: "lyricFontPreview"; text: root.t("让音乐留在手边\n陪你走过每个清晨"); Layout.fillWidth: true
                                            lineHeight: root.lyricLineSpacing; lineHeightMode: Text.ProportionalHeight
                                            font.pixelSize: root.lyricFontSize + 2; font.weight: Font.DemiBold; color: root.accent; wrapMode: Text.Wrap
                                        }
                                        SettingsHeading { label: root.t("外观"); glyph: "spark" }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 10
                                            Copy { text: root.t("主题"); Layout.preferredWidth: root.languageCode === "en" ? 106 : 70; font.pixelSize: 13 }
                                            Choice {
                                                objectName: "themeSelector"; Layout.fillWidth: true; Accessible.name: root.t("界面主题")
                                                model: [root.t("跟随系统"), root.t("浅色"), root.t("深色")]; selectedIndex: appearance.mode
                                                onActivated: function(index) { appearance.mode = index; appearance.sync() }
                                            }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: root.t("整体大小"); font.pixelSize: 13; Layout.fillWidth: true }
                                            Copy { text: Math.round(root.contentScale * 100) + "%"; color: root.muted; font.pixelSize: 12 }
                                        }
                                        TrackSlider {
                                            id: sizeSlider; objectName: "windowScaleSlider"; Layout.fillWidth: true
                                            from: 90; to: 140; stepSize: 5; Accessible.name: root.t("悬浮窗整体缩放")
                                            onMoved: appearance.windowScale = value / 100
                                            Binding { target: sizeSlider; property: "value"; value: appearance.windowScale * 100; when: !sizeSlider.pressed }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: root.t("背景不透明度"); font.pixelSize: 13; Layout.fillWidth: true }
                                            Copy { text: Math.round(appearance.backgroundOpacity * 100) + "%"; color: root.muted; font.pixelSize: 12 }
                                        }
                                        TrackSlider {
                                            id: opacitySlider; objectName: "backgroundOpacitySlider"; Layout.fillWidth: true
                                            from: 20; to: 100; stepSize: 5; Accessible.name: root.t("背景不透明度")
                                            onMoved: appearance.backgroundOpacity = value / 100
                                            Binding { target: opacitySlider; property: "value"; value: appearance.backgroundOpacity * 100; when: !opacitySlider.pressed }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: root.t("界面动效"); font.pixelSize: 13; Layout.fillWidth: true }
                                            Switch {
                                                id: motionSwitch; objectName: "animationsSwitch"
                                                checked: appearance.animationsEnabled; implicitHeight: 30; padding: 0
                                                Accessible.name: root.t("界面动效")
                                                onToggled: { appearance.animationsEnabled = checked; appearance.sync() }
                                                indicator: Rectangle {
                                                    x: motionSwitch.leftPadding; y: (motionSwitch.height - height) / 2
                                                    implicitWidth: 38; implicitHeight: 22; radius: 11
                                                    color: motionSwitch.checked ? root.accent : root.control
                                                    border.width: motionSwitch.activeFocus ? 2 : 1; border.color: motionSwitch.activeFocus ? root.accent : root.fieldBorder
                                                    Rectangle { x: motionSwitch.checked ? 19 : 3; y: 3; width: 16; height: 16; radius: 8; color: motionSwitch.checked ? root.accentInk : root.muted }
                                                }
                                            }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Hint { text: root.t("透明度只影响背景。") }
                                            Action { objectName: "resetAppearanceButton"; text: root.t("重置外观"); quiet: true; Accessible.name: root.t("恢复窗口大小、背景和位置"); onClicked: root.resetAppearance() }
                                        }
                                        SettingsHeading { label: root.t("全局快捷键"); glyph: "keyboard" }
                                        Hint { text: player.shortcuts.nativeEnabled ? root.t("其他程序在前台时也可使用。点击组合键录入新设置。") : root.t("当前环境不启用全局按键；仍可保存快捷键设置。") }
                                        Repeater {
                                            model: player.shortcuts.bindings
                                            delegate: ColumnLayout {
                                                id: shortcutRow
                                                required property var modelData
                                                Layout.fillWidth: true; spacing: 4
                                                Copy { text: root.t(shortcutRow.modelData.label); Layout.fillWidth: true; font.pixelSize: 13 }
                                                RowLayout {
                                                    Layout.fillWidth: true; spacing: 6
                                                    Action {
                                                        objectName: "shortcutEdit_" + shortcutRow.modelData.action
                                                        text: shortcutRow.modelData.sequence || root.t("已禁用")
                                                        Layout.fillWidth: true; Layout.minimumWidth: 0
                                                        Accessible.name: root.t("修改") + root.t(shortcutRow.modelData.label) + root.t("快捷键，") + text
                                                        ToolTip.text: text
                                                        contentItem: Copy { text: parent.text; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                                        onClicked: root.captureShortcut(shortcutRow.modelData)
                                                    }
                                                    Action {
                                                        objectName: "shortcutDisable_" + shortcutRow.modelData.action
                                                        text: root.t("禁用"); quiet: true; enabled: shortcutRow.modelData.sequence.length > 0
                                                        Accessible.name: root.t("禁用") + root.t(shortcutRow.modelData.label) + root.t("快捷键")
                                                        onClicked: player.shortcuts.setBinding(shortcutRow.modelData.action, "")
                                                    }
                                                    Action {
                                                        objectName: "shortcutDefault_" + shortcutRow.modelData.action
                                                        text: root.t("默认"); quiet: true
                                                        enabled: shortcutRow.modelData.sequence !== shortcutRow.modelData.defaultSequence || shortcutRow.modelData.error.length > 0
                                                        Accessible.name: root.t("恢复") + root.t(shortcutRow.modelData.label) + root.t("默认快捷键")
                                                        onClicked: player.shortcuts.setBinding(shortcutRow.modelData.action, shortcutRow.modelData.defaultSequence)
                                                    }
                                                }
                                                Hint { objectName: "shortcutError_" + shortcutRow.modelData.action; visible: shortcutRow.modelData.error.length > 0; text: root.t(shortcutRow.modelData.error); color: root.danger }
                                            }
                                        }
                                        Hint { objectName: "shortcutSettingsMessage"; visible: player.shortcuts.message.length > 0; text: root.t(player.shortcuts.message); color: root.danger }
                                        Action { objectName: "resetShortcutDefaults"; text: root.t("恢复全部默认快捷键"); quiet: true; Layout.fillWidth: true; onClicked: player.shortcuts.resetDefaults() }
                                        SettingsHeading { label: root.t("搜索与服务"); glyph: "search" }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 8
                                            Copy { text: root.t("结果数量"); Layout.fillWidth: true; font.pixelSize: 13 }
                                            Choice {
                                                id: searchLimitSelector; objectName: "searchResultLimitSelector"
                                                Layout.preferredWidth: root.languageCode === "en" ? 132 : 108; implicitHeight: 34; Accessible.name: root.t("搜索显示数量")
                                                property var limits: [10, 20, 30, 50, 100]
                                                model: [root.t("10 条"), root.t("20 条"), root.t("30 条"), root.t("50 条"), root.t("100 条")]
                                                selectedIndex: searchLimitSelector.limits.indexOf(player.searchResultLimit)
                                                onActivated: function(index) { player.searchResultLimit = limits[index] }
                                            }
                                        }
                                        Hint { objectName: "searchResultLimitHint"; text: root.t("所选曲库合计最多 ") + player.searchResultLimit + root.t(" 条，歌曲与歌单共用；下次搜索生效。") }
                                        Action {
                                            objectName: "customServiceToggle"; text: root.t("自定义网易云服务"); glyph: settingsScroll.customServiceExpanded ? "up" : "chevron"
                                            quiet: true; Layout.fillWidth: true; selected: settingsScroll.customServiceExpanded
                                            Accessible.description: settingsScroll.customServiceExpanded ? root.t("已展开") : root.t("点击展开地址设置")
                                            onClicked: settingsScroll.customServiceExpanded = !settingsScroll.customServiceExpanded
                                        }
                                        ColumnLayout {
                                            visible: settingsScroll.customServiceExpanded; Layout.fillWidth: true; spacing: 8
                                            Hint { text: root.t("仅用于网易云。留空使用内置接口。") }
                                            Field {
                                                id: apiAddress; objectName: "apiAddress"; Layout.fillWidth: true
                                                text: player.apiBase; placeholderText: root.t("兼容 API 地址（可选）"); Accessible.name: root.t("自定义网易云服务地址")
                                            }
                                            RowLayout {
                                                Layout.fillWidth: true; spacing: 8
                                                Item { Layout.fillWidth: true }
                                                Action { text: root.t("恢复内置"); quiet: true; enabled: !player.busy; onClicked: { player.setApiBase(""); apiAddress.text = "" } }
                                                Action { text: root.t("保存"); primary: true; enabled: !player.busy; onClicked: player.setApiBase(apiAddress.text) }
                                            }
                                            Hint { text: root.t(player.searchMessage); visible: text.length > 0 }
                                        }
                                        SettingsHeading { label: root.t("浮音 1.4.0-preview"); glyph: "info" }
                                        Hint { text: root.t("Windows 桌面预览 · 拖动顶部移动窗口\nCtrl+F 搜索 · Ctrl+O 导入 · Esc 返回或收起") }
                                    }
                                }
                                Item {
                                    ColumnLayout {
                                        id: rankingsTools; parent: rankingResults.headerItem
                                        width: rankingResults.width - 12; spacing: 8
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 6
                                            Action { glyph: "back"; text: root.t("更多"); quiet: true; onClicked: root.detail = "" }
                                            Copy { text: root.t("排行榜"); font.pixelSize: 16; font.weight: Font.DemiBold; Layout.fillWidth: true }
                                            Action { objectName: "refreshRankings"; glyph: "refresh"; text: player.rankingsLoading ? root.t("刷新中") : root.t("刷新"); quiet: true; enabled: !player.rankingsLoading; Accessible.name: root.t("刷新排行榜"); onClicked: player.libraryAction("loadRankings", {}) }
                                        }
                                        Flow {
                                            objectName: "rankingSourceChoices"; Layout.fillWidth: true; spacing: 6
                                            Repeater {
                                                model: ["netease", "tencent", "kuwo"]
                                                Action {
                                                    required property string modelData
                                                    objectName: "rankingSource_" + modelData
                                                    implicitWidth: 90; implicitHeight: 30; padding: 6; quiet: true
                                                    text: root.musicSourceName(modelData)
                                                    selected: player.rankingSource === modelData
                                                    glyph: selected ? "check" : ""
                                                    Accessible.name: text + root.t("排行榜")
                                                    Accessible.role: Accessible.RadioButton
                                                    Accessible.checkable: true; Accessible.checked: selected
                                                    onClicked: {
                                                        if (player.rankingSource !== modelData) {
                                                            player.libraryAction("loadRankings", { source: modelData })
                                                            rankingResults.positionViewAtBeginning()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Copy { objectName: "rankingSourceLabel"; text: root.musicSourceName(player.rankingSource) + root.t("排行榜"); color: root.accent; font.pixelSize: 12; Layout.fillWidth: true }
                                        Hint { text: root.t("选择榜单，听听此刻的热门与新声。") }
                                        RowLayout {
                                            visible: root.t(player.rankingsMessage).length > 0; Layout.fillWidth: true; spacing: 8
                                            Hint { text: root.t(player.rankingsMessage); maximumLineCount: 3; elide: Text.ElideRight; color: player.rankings.length > 0 ? root.danger : root.muted }
                                            Action { text: root.t("重试"); visible: player.rankings.length > 0; quiet: true; enabled: !player.rankingsLoading; onClicked: player.libraryAction("loadRankings", {}) }
                                        }
                                        Hint { text: root.t("正在读取排行榜…"); visible: player.rankingsLoading }
                                        Item { width: 1; height: 2 }
                                    }
                                    ListView {
                                        id: rankingResults; objectName: "rankingsView"; anchors.fill: parent; clip: true
                                        model: player.rankings; spacing: 6
                                        boundsBehavior: Flickable.StopAtBounds
                                        headerPositioning: ListView.InlineHeader
                                        header: Item { width: rankingResults.width - 12; height: rankingsTools.implicitHeight + 8 }
                                        ScrollBar.vertical: ScrollBar {}
                                        delegate: PressRow {
                                            id: rankingResult
                                            required property var modelData
                                            width: rankingResults.width - 12; height: Math.max(emphasized ? 82 : 76, contentItem.implicitHeight + 20)
                                            emphasized: root.isFeaturedRanking(modelData.name)
                                            Accessible.name: root.onlineSourceName(modelData) + "，" + modelData.name + (modelData.updateFrequency ? "，" + modelData.updateFrequency : "") + (modelData.trackCount >= 0 ? "，" + modelData.trackCount + root.t(" 首") : "") + root.t("，查看榜单")
                                            contentItem: RowLayout {
                                                spacing: 12; scale: rankingResult.visualScale
                                                Behavior on scale { enabled: appearance.animationsEnabled; NumberAnimation { duration: rankingResult.down ? 80 : 150; easing.type: Easing.OutCubic } }
                                                Rectangle {
                                                    Layout.preferredWidth: 34; Layout.preferredHeight: 34; radius: 9
                                                    color: rankingResult.emphasized ? root.selection : root.control
                                                    Glyph { anchors.centerIn: parent; kind: root.rankingGlyph(rankingResult.modelData.name); tint: root.accent; width: 20; height: 20 }
                                                }
                                                ColumnLayout {
                                                    Layout.fillWidth: true; Layout.minimumWidth: 0; spacing: 4
                                                    Copy { text: rankingResult.modelData.name; Layout.fillWidth: true; Layout.minimumWidth: 0; font.pixelSize: 14; font.weight: Font.DemiBold; wrapMode: Text.Wrap; maximumLineCount: 2 }
                                                    Copy { objectName: "rankingResultMetadata"; text: root.onlineSourceName(rankingResult.modelData) + (rankingResult.modelData.updateFrequency ? " · " + rankingResult.modelData.updateFrequency : "") + (rankingResult.modelData.trackCount >= 0 ? " · " + rankingResult.modelData.trackCount + root.t(" 首") : ""); Layout.fillWidth: true; Layout.minimumWidth: 0; wrapMode: Text.Wrap; maximumLineCount: 2; color: rankingResult.emphasized ? root.accent : root.muted; font.pixelSize: 12 }
                                                    Copy { text: rankingResult.modelData.description || rankingResult.modelData.creator || root.t("点击查看榜单歌曲"); Layout.fillWidth: true; color: root.muted; font.pixelSize: 12 }
                                                }
                                                Glyph { kind: "forward"; tint: root.muted; Layout.preferredWidth: 16; Layout.preferredHeight: 16 }
                                            }
                                            onClicked: root.openOnlinePlaylist(modelData.id, "rankings")
                                        }
                                        footer: ColumnLayout {
                                            width: rankingResults.width - 12; spacing: 10
                                            visible: rankingResults.count === 0 && !player.rankingsLoading
                                            height: visible ? implicitHeight + 28 : 0
                                            Hint { Layout.topMargin: 16; text: root.t("暂时没有可用榜单，可以重新加载。"); horizontalAlignment: Text.AlignHCenter }
                                            Action { text: root.t("重新加载"); glyph: "refresh"; Layout.alignment: Qt.AlignHCenter; enabled: !player.rankingsLoading; onClicked: player.libraryAction("loadRankings", {}) }
                                        }
                                    }
                                }
                                ColumnLayout {
                                    spacing: 0
                                    ListView {
                                        id: onlineTracks; objectName: "onlinePlaylistTracks"; Layout.fillWidth: true; Layout.fillHeight: true; clip: true
                                        model: player.onlinePlaylistLoading ? [] : (player.onlinePlaylist.tracks || [])
                                        ScrollBar.vertical: ScrollBar {}
                                        headerPositioning: ListView.InlineHeader
                                        boundsBehavior: Flickable.StopAtBounds
                                        header: ColumnLayout {
                                            width: onlineTracks.width - 12; spacing: 8
                                            RowLayout {
                                                Layout.fillWidth: true; spacing: 6
                                                Action { glyph: "back"; quiet: true; Accessible.name: root.onlinePlaylistSource === "rankings" ? root.t("返回排行榜") : root.t("返回歌单搜索结果"); ToolTip.text: Accessible.name; onClicked: root.closeOnlinePlaylist() }
                                                Copy { objectName: "onlinePlaylistTitle"; text: player.onlinePlaylistLoading ? root.t("读取歌单…") : (player.onlinePlaylist.name || root.t("歌单详情")); Layout.fillWidth: true; Layout.minimumWidth: 0; font.weight: Font.DemiBold; wrapMode: Text.Wrap; maximumLineCount: 3 }
                                                Action { glyph: "import"; text: root.t("导入"); enabled: !player.onlinePlaylistLoading && !!player.onlinePlaylist.tracks && player.onlinePlaylist.tracks.length > 0; onClicked: root.showOnlineImport() }
                                            }
                                            Copy { objectName: "onlinePlaylistMetadata"; text: root.onlineSourceName(player.onlinePlaylist) + (player.onlinePlaylist.kind === "ranking" || root.onlinePlaylistSource === "rankings" ? root.t(" · 榜单") : root.t(" · 歌单")) + " · " + (player.onlinePlaylist.trackCount || 0) + root.t(" 首") + (onlineTracks.count !== (player.onlinePlaylist.trackCount || 0) ? root.t(" · 已读取 ") + onlineTracks.count + root.t(" 首") : ""); Layout.fillWidth: true; wrapMode: Text.Wrap; color: root.accent; font.pixelSize: 12 }
                                            Hint { objectName: "onlinePlaylistCreator"; text: (player.onlinePlaylist.creator || "") + (player.onlinePlaylist.updateFrequency ? (player.onlinePlaylist.creator ? " · " : "") + player.onlinePlaylist.updateFrequency : ""); visible: text.length > 0 }
                                            Hint { text: player.onlinePlaylist.description || root.t("暂无简介"); maximumLineCount: 5; elide: Text.ElideRight }
                                            Hint { text: root.t(player.onlinePlaylist.warning || ""); visible: text.length > 0; color: root.danger }
                                            Hint { text: player.onlinePlaylistLoading ? root.t("正在读取歌单与歌曲…") : root.t(player.libraryMessage); visible: text.length > 0 }
                                            Hint { text: root.t(player.playbackMessage); visible: text.length > 0; maximumLineCount: 2; elide: Text.ElideRight }
                                            Item { height: 4; width: 1 }
                                        }
                                        delegate: SongRow {
                                            required property var modelData; required property int index
                                            track: modelData; rank: root.onlinePlaylistSource === "rankings" ? (modelData.playlistPosition !== undefined ? modelData.playlistPosition : index + 1) : 0
                                            menuObjectName: "onlineMenu_" + index
                                            onPlayRequested: player.libraryAction("playOnline", { index: index })
                                            onMenuRequested: root.openTrackMenu(modelData)
                                        }
                                    }
                                }
                                Item {
                                    ColumnLayout {
                                        id: queueTools; parent: queueView.headerItem
                                        width: queueView.width - 12; spacing: 6
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 6
                                            Action { glyph: "back"; text: root.t("更多"); quiet: true; onClicked: root.detail = "" }
                                            Copy { text: root.t("播放队列"); font.weight: Font.DemiBold; Layout.fillWidth: true }
                                            Action { objectName: "clearQueueButton"; text: root.t("清空"); quiet: true; enabled: player.queueEntries.length > 0; onClicked: root.confirmPlaybackRemoval("queue") }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 6
                                            Copy { objectName: "queueCount"; text: player.queueEntries.length + root.t(" 首"); color: root.accent; font.pixelSize: 12 }
                                            Copy { text: root.t("拖动手柄排序，也可使用歌曲菜单"); Layout.fillWidth: true; font.pixelSize: 12; color: root.muted }
                                        }
                                        Hint { visible: player.ready && player.currentQueueEntry.length === 0 && player.queueEntries.length > 0; text: root.t("当前歌曲已移出队列，仍会继续播放。") }
                                        Hint { visible: root.t(player.playbackMessage).length > 0; text: root.t(player.playbackMessage); maximumLineCount: 3; elide: Text.ElideRight }
                                    }
                                    ListView {
                                        id: queueView; objectName: "queueView"; anchors.fill: parent; clip: true
                                        model: player.queueEntries
                                        headerPositioning: ListView.InlineHeader
                                        header: Item { width: queueView.width - 12; height: queueTools.implicitHeight + 8 }
                                        boundsBehavior: Flickable.StopAtBounds
                                        property string dragEntryId: ""
                                        property int dragFrom: -1
                                        property int dragTo: -1
                                        property real dragPointerY: 0
                                        // Hold the pressed occurrence while delegates scroll past it.
                                        currentIndex: dragFrom
                                        highlightFollowsCurrentItem: false
                                        function updateDropTarget() {
                                            var firstRowY = headerItem ? headerItem.y + headerItem.height : originY
                                            dragTo = Math.max(0, Math.min(count - 1, Math.floor((contentY + dragPointerY - firstRowY) / 58)))
                                        }
                                        function endReorder(commit) {
                                            var entryId = dragEntryId, to = dragTo, from = dragFrom
                                            dragEntryId = ""; dragFrom = -1; dragTo = -1
                                            if (commit && entryId.length && from >= 0 && to >= 0 && from !== to)
                                                player.moveQueueEntry(entryId, to)
                                        }
                                        onModelChanged: endReorder(false)
                                        onVisibleChanged: if (!visible) endReorder(false)
                                        ScrollBar.vertical: ScrollBar {}
                                        Timer {
                                            interval: 45; repeat: true; running: queueView.dragFrom >= 0
                                            onTriggered: {
                                                var delta = queueView.dragPointerY < 28 ? -12 : queueView.dragPointerY > queueView.height - 28 ? 12 : 0
                                                if (delta) {
                                                    queueView.contentY = Math.max(queueView.originY, Math.min(queueView.originY + Math.max(0, queueView.contentHeight - queueView.height), queueView.contentY + delta))
                                                    queueView.updateDropTarget()
                                                }
                                            }
                                        }
                                        delegate: Rectangle {
                                            id: queuedTrack
                                            required property var modelData
                                            required property int index
                                            objectName: "queueRow_" + modelData.queueId
                                            readonly property bool current: player.currentQueueEntry === modelData.queueId
                                            width: queueView.width - 10; height: 58; radius: 8
                                            color: current ? root.selection : queueHover.hovered ? root.backdrop : "transparent"
                                            opacity: queueView.dragEntryId === modelData.queueId ? 0.6 : 1
                                            HoverHandler { id: queueHover }
                                            Rectangle {
                                                anchors.left: parent.left; anchors.right: parent.right; height: 3; radius: 1.5
                                                y: queueView.dragTo > queueView.dragFrom ? parent.height - height : 0
                                                color: root.accent; visible: queueView.dragFrom >= 0 && queueView.dragTo === queuedTrack.index && queueView.dragTo !== queueView.dragFrom
                                            }
                                            RowLayout {
                                                anchors.fill: parent; spacing: 4
                                                Item {
                                                    Layout.preferredWidth: 30; Layout.fillHeight: true
                                                    Glyph { anchors.centerIn: parent; kind: "drag"; tint: root.muted }
                                                    MouseArea {
                                                        objectName: "queueDrag_" + queuedTrack.modelData.queueId
                                                        anchors.fill: parent; preventStealing: true
                                                        cursorShape: pressed ? Qt.ClosedHandCursor : Qt.OpenHandCursor
                                                        property real pressY: 0
                                                        property bool moved: false
                                                        Accessible.name: root.t("拖动排序 ") + queuedTrack.modelData.name
                                                        onPressed: function(mouse) { pressY = mouse.y; moved = false }
                                                        onPositionChanged: function(mouse) {
                                                            if (!pressed) return
                                                            if (!moved && Math.abs(mouse.y - pressY) < Qt.styleHints.startDragDistance) return
                                                            if (!moved) { moved = true; queueView.dragFrom = queuedTrack.index; queueView.dragEntryId = queuedTrack.modelData.queueId }
                                                            queueView.dragPointerY = mapToItem(queueView, mouse.x, mouse.y).y
                                                            queueView.updateDropTarget()
                                                        }
                                                        onReleased: queueView.endReorder(true)
                                                        onCanceled: queueView.endReorder(false)
                                                    }
                                                    ToolTip.visible: queueHandleHover.hovered && queueView.dragEntryId.length === 0; ToolTip.text: root.t("拖动排序，也可在菜单中上移、下移")
                                                    HoverHandler { id: queueHandleHover }
                                                }
                                                Button {
                                                    id: queueTap
                                                    objectName: "queuePlay_" + queuedTrack.modelData.queueId
                                                    Layout.fillWidth: true; Layout.minimumWidth: 0; Layout.fillHeight: true; padding: 4
                                                    enabled: !player.busy
                                                    Accessible.name: (queuedTrack.current ? root.t("当前歌曲，") : root.t("播放 ")) + queuedTrack.modelData.name + "，" + root.trackSourceName(queuedTrack.modelData)
                                                    contentItem: ColumnLayout {
                                                        spacing: 4
                                                        Copy { text: queuedTrack.modelData.name; Layout.fillWidth: true; font.weight: Font.DemiBold; color: queuedTrack.current ? root.accent : root.ink }
                                                        TrackMetadata { track: queuedTrack.modelData; Layout.fillWidth: true; Layout.minimumWidth: 0 }
                                                    }
                                                    background: Rectangle { color: "transparent"; radius: 8; border.width: parent.activeFocus ? 2 : 0; border.color: root.accent }
                                                    onClicked: player.playQueueEntry(queuedTrack.modelData.queueId)
                                                }
                                                Glyph { visible: queuedTrack.current; kind: player.playing ? "play" : "pause"; tint: root.accent; Layout.preferredWidth: 16; Layout.preferredHeight: 16 }
                                                Action {
                                                    objectName: "queueMenu_" + queuedTrack.modelData.queueId
                                                    glyph: "more"; quiet: true; Accessible.name: root.t("队列操作 ") + queuedTrack.modelData.name; ToolTip.text: Accessible.name
                                                    onClicked: { queueMenu.entryId = queuedTrack.modelData.queueId; queueMenu.track = queuedTrack.modelData; queueMenu.open() }
                                                }
                                            }
                                        }
                                        footer: Item {
                                            width: queueView.width; height: queueView.count === 0 ? 90 : 0
                                            Hint { objectName: "queueEmptyState"; anchors.centerIn: parent; width: parent.width - 16; horizontalAlignment: Text.AlignHCenter; visible: queueView.count === 0; text: root.t("队列为空\n在歌曲菜单中选择“下一首播放”或“加入队尾”") }
                                        }
                                    }
                                }
                                Item {
                                    ColumnLayout {
                                        id: historyTools; parent: historyView.headerItem
                                        width: historyView.width - 12; spacing: 6
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 6
                                            Action { glyph: "back"; text: root.t("更多"); quiet: true; onClicked: root.detail = "" }
                                            Copy { text: root.t("最近播放"); font.weight: Font.DemiBold; Layout.fillWidth: true }
                                            Action { objectName: "clearHistoryButton"; text: root.t("清空"); quiet: true; enabled: player.historyEntries.length > 0; onClicked: root.confirmPlaybackRemoval("history") }
                                        }
                                        Hint { text: player.historyEntries.length + root.t(" 首 · 最近听过的歌曲，最多保留 200 首") }
                                        Hint { visible: root.t(player.playbackMessage).length > 0; text: root.t(player.playbackMessage); maximumLineCount: 3; elide: Text.ElideRight }
                                    }
                                    ListView {
                                        id: historyView; objectName: "historyView"; anchors.fill: parent; clip: true
                                        model: player.historyEntries
                                        headerPositioning: ListView.InlineHeader
                                        header: Item { width: historyView.width - 12; height: historyTools.implicitHeight + 8 }
                                        boundsBehavior: Flickable.StopAtBounds
                                        ScrollBar.vertical: ScrollBar {}
                                        delegate: SongRow {
                                            required property var modelData
                                            required property int index
                                            track: modelData; note: root.historyTime(modelData.playedAtMs)
                                            playObjectName: "historyPlay_" + index
                                            menuObjectName: "historyMenu_" + index
                                            onPlayRequested: player.playHistoryTrack(modelData.id)
                                            onMenuRequested: { historyMenu.track = modelData; historyMenu.open() }
                                        }
                                        footer: Item {
                                            width: historyView.width; height: historyView.count === 0 ? 90 : 0
                                            Hint { objectName: "historyEmptyState"; anchors.centerIn: parent; width: parent.width - 16; horizontalAlignment: Text.AlignHCenter; visible: historyView.count === 0; text: root.t("还没有最近播放\n歌曲开始播放后会出现在这里") }
                                        }
                                    }
                                }
                                ScrollView {
                                    id: sleepScroll; objectName: "sleepScroll"; clip: true; contentWidth: availableWidth
                                    ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                                    ColumnLayout {
                                        width: sleepScroll.availableWidth; spacing: 10
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Action { glyph: "back"; text: root.t("更多"); quiet: true; onClicked: root.detail = "" }
                                            Copy { text: root.t("睡眠定时"); font.weight: Font.DemiBold; Layout.fillWidth: true }
                                        }
                                        Hint { objectName: "sleepStatus"; text: player.sleepMode === "off" ? root.t("定时结束时暂停音乐") : root.t(player.sleepText); color: player.sleepMode === "off" ? root.muted : root.accent }
                                        GridLayout {
                                            Layout.fillWidth: true; columns: 3; columnSpacing: 6
                                            Action { objectName: "sleepPreset15"; text: root.t("15 分钟"); Layout.fillWidth: true; onClicked: player.startSleepTimer(15) }
                                            Action { objectName: "sleepPreset30"; text: root.t("30 分钟"); Layout.fillWidth: true; onClicked: player.startSleepTimer(30) }
                                            Action { objectName: "sleepPreset60"; text: root.t("60 分钟"); Layout.fillWidth: true; onClicked: player.startSleepTimer(60) }
                                        }
                                        Copy { text: root.t("自定义 · 1–180 分钟"); font.pixelSize: 13 }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 8
                                            Field { id: sleepMinutes; objectName: "sleepMinutes"; Layout.fillWidth: true; Layout.minimumWidth: 0; text: "45"; maximumLength: 3; inputMethodHints: Qt.ImhDigitsOnly; validator: IntValidator { bottom: 1; top: 180 } Accessible.name: root.t("定时分钟数，1 至 180"); onAccepted: if (startCustomSleep.enabled) player.startSleepTimer(Number(text)) }
                                            Action { id: startCustomSleep; objectName: "startCustomSleep"; text: root.t("开始"); selected: true; enabled: /^\d{1,3}$/.test(sleepMinutes.text) && Number(sleepMinutes.text) >= 1 && Number(sleepMinutes.text) <= 180; onClicked: player.startSleepTimer(Number(sleepMinutes.text)) }
                                        }
                                        Action { objectName: "sleepAfterCurrent"; text: root.t("播完当前歌曲"); glyph: "music"; Layout.fillWidth: true; enabled: player.ready || player.busy; onClicked: player.sleepAfterCurrentTrack() }
                                        Action { objectName: "cancelSleepButton"; text: root.t("取消定时"); quiet: true; Layout.fillWidth: true; enabled: player.sleepMode !== "off"; onClicked: player.cancelSleepTimer() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Sheets stay within the always-on-top card and scroll when the desktop is short.
    component LibrarySheet: Popup {
        id: sheet
        default property alias body: sheetBody.data
        property string heading: ""
        parent: Overlay.overlay; popupType: Popup.Item; z: 1000
        modal: true; dim: false; focus: true; padding: 12
        width: Math.min(344, root.width / root.contentScale - 24)
        height: Math.min(sheetContents.implicitHeight + padding * 2, Math.max(80, root.height / root.contentScale - 24))
        x: (root.width - width * root.contentScale) / 2
        y: Math.max(8, (root.height - height * root.contentScale) / 2)
        scale: root.contentScale; transformOrigin: Popup.TopLeft
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        background: PrismaticSurface { radius: 14; darkMode: root.darkMode; elevated: true }
        enter: Transition {
            enabled: appearance.animationsEnabled
            NumberAnimation { targets: [sheet.contentItem, sheet.background]; property: "opacity"; from: 0; to: 1; duration: 140; easing.type: Easing.OutCubic }
        }
        contentItem: ScrollView {
            id: sheetScroll; clip: true; contentWidth: availableWidth
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
            ColumnLayout {
                id: sheetContents; width: sheetScroll.availableWidth; spacing: 8
                RowLayout {
                    Layout.fillWidth: true
                    Copy { text: sheet.heading; font.weight: Font.DemiBold; Layout.fillWidth: true; Layout.minimumWidth: 0 }
                    Action { glyph: "close"; quiet: true; Accessible.name: root.t("关闭") + sheet.heading; ToolTip.text: root.t("关闭"); onClicked: sheet.close() }
                }
                ColumnLayout { id: sheetBody; Layout.fillWidth: true; spacing: 8 }
            }
        }
    }
    LibrarySheet {
        id: libraryMenu; heading: root.t("歌单管理")
        Hint { text: root.activeList.name + " · " + player.tracks.length + root.t(" 首") }
        GridLayout {
            Layout.fillWidth: true; columns: 2; rowSpacing: 8; columnSpacing: 8
            Action { text: root.t("新建歌单"); Layout.fillWidth: true; onClicked: { libraryMenu.close(); playlistDialog.renaming = false; playlistName.text = ""; playlistDialog.open() } }
            Action { text: root.t("修改名称"); Layout.fillWidth: true; enabled: player.activePlaylist !== "favorites"; onClicked: { libraryMenu.close(); playlistDialog.sourceId = player.activePlaylist; playlistDialog.renaming = true; playlistName.text = root.activeList.name; playlistDialog.open() } }
            Action { text: root.t("编辑简介"); Layout.fillWidth: true; onClicked: { libraryMenu.close(); descriptionSheet.sourceId = player.activePlaylist; descriptionText.text = root.activeList.description || ""; descriptionSheet.open() } }
            Action { text: root.t("复制整单"); Layout.fillWidth: true; onClicked: { libraryMenu.close(); player.libraryAction("duplicate", {}) } }
            Action { text: root.t("导入歌单"); Layout.fillWidth: true; onClicked: { libraryMenu.close(); root.showImport() } }
            Action { text: root.t("导出整单"); Layout.fillWidth: true; enabled: player.tracks.length > 0; onClicked: { libraryMenu.close(); exportSheet.sourceId = player.activePlaylist; exportSheet.ids = []; exportSheet.listName = root.activeList.name; exportSheet.open() } }
        }
        Action {
            text: root.t("删除歌单"); Layout.fillWidth: true; enabled: player.activePlaylist !== "favorites" && player.playlists.length > 1
            onClicked: { libraryMenu.close(); deleteDialog.sourceId = player.activePlaylist; deleteDialog.listName = root.activeList.name; deleteDialog.open() }
        }
        Hint { visible: player.activePlaylist === "favorites"; text: root.t("收藏夹与普通歌单使用相同的歌曲管理方式；名称固定，始终保留。") }
    }
    LibrarySheet {
        id: selectionMenu; heading: root.t("已选 ") + root.selectedTrackIds.length + root.t(" 首")
        Action { text: root.t("复制到歌单"); Layout.fillWidth: true; onClicked: { selectionMenu.close(); root.showTransfer(false) } }
        Action { text: root.t("移动到歌单"); Layout.fillWidth: true; onClicked: { selectionMenu.close(); root.showTransfer(true) } }
        Action { text: root.t("导出所选"); Layout.fillWidth: true; onClicked: { selectionMenu.close(); root.showExport() } }
        Action { text: root.t("从歌单移除"); Layout.fillWidth: true; onClicked: { selectionMenu.close(); root.confirmRemove(root.selectedTrackIds) } }
    }
    LibrarySheet {
        id: trackMenu; heading: root.t("歌曲操作")
        property var track: ({})
        property string sourceId: ""
        property string trackId: ""
        property string trackName: ""
        property int trackIndex: -1
        Hint { text: trackMenu.trackName }
        Action { objectName: "trackEnqueueNext"; text: root.t("下一首播放"); glyph: "next"; Layout.fillWidth: true; onClicked: { player.enqueueTrack(trackMenu.track, "next"); trackMenu.close() } }
        Action { objectName: "trackEnqueueTail"; text: root.t("加入队尾"); glyph: "list"; Layout.fillWidth: true; onClicked: { player.enqueueTrack(trackMenu.track, "tail"); trackMenu.close() } }
        RowLayout {
            visible: trackMenu.sourceId.length > 0; Layout.fillWidth: true; spacing: 8
            Action { text: root.t("上移"); glyph: "up"; Layout.fillWidth: true; enabled: trackMenu.trackIndex > 0; onClicked: { if (root.usePlaylist(trackMenu.sourceId)) player.libraryAction("moveTrack", { from: trackMenu.trackIndex, to: trackMenu.trackIndex - 1 }); trackMenu.close() } }
            Action { text: root.t("下移"); glyph: "down"; Layout.fillWidth: true; enabled: trackMenu.trackIndex >= 0 && trackMenu.trackIndex < player.tracks.length - 1; onClicked: { if (root.usePlaylist(trackMenu.sourceId)) player.libraryAction("moveTrack", { from: trackMenu.trackIndex, to: trackMenu.trackIndex + 1 }); trackMenu.close() } }
        }
        Action { visible: trackMenu.sourceId.length > 0; text: root.t("选择这首歌"); Layout.fillWidth: true; onClicked: { if (root.usePlaylist(trackMenu.sourceId)) { filter.text = ""; root.selectingTracks = true; root.selectedTrackIds = [trackMenu.trackId] }; trackMenu.close() } }
        Action { visible: trackMenu.sourceId.length > 0; text: root.t("从歌单移除"); Layout.fillWidth: true; onClicked: { trackMenu.close(); root.confirmRemove([trackMenu.trackId], trackMenu.sourceId) } }
    }
    LibrarySheet {
        id: queueMenu; heading: root.t("队列操作")
        property string entryId: ""
        property var track: ({})
        readonly property int entryIndex: root.queueIndex(entryId)
        Hint { text: queueMenu.track.name || "" }
        Action { objectName: "queuePlayNow"; text: root.t("现在播放"); glyph: "play"; Layout.fillWidth: true; enabled: queueMenu.entryIndex >= 0 && !player.busy; onClicked: { player.playQueueEntry(queueMenu.entryId); queueMenu.close() } }
        RowLayout {
            Layout.fillWidth: true; spacing: 8
            Action { objectName: "queueMoveUp"; text: root.t("上移"); glyph: "up"; Layout.fillWidth: true; enabled: queueMenu.entryIndex > 0; onClicked: { player.moveQueueEntry(queueMenu.entryId, queueMenu.entryIndex - 1); queueMenu.close() } }
            Action { objectName: "queueMoveDown"; text: root.t("下移"); glyph: "down"; Layout.fillWidth: true; enabled: queueMenu.entryIndex >= 0 && queueMenu.entryIndex < player.queueEntries.length - 1; onClicked: { player.moveQueueEntry(queueMenu.entryId, queueMenu.entryIndex + 1); queueMenu.close() } }
        }
        Action { objectName: "queueRemove"; text: root.t("从队列移除"); Layout.fillWidth: true; enabled: queueMenu.entryIndex >= 0; onClicked: { queueMenu.close(); root.confirmPlaybackRemoval("entry", queueMenu.entryId, queueMenu.track.name) } }
    }
    LibrarySheet {
        id: historyMenu; heading: root.t("最近播放操作")
        property var track: ({})
        Hint { text: historyMenu.track.name || "" }
        Action { objectName: "historyReplay"; text: root.t("再次播放"); glyph: "play"; Layout.fillWidth: true; enabled: !player.busy; onClicked: { player.playHistoryTrack(historyMenu.track.id); historyMenu.close() } }
        Action { objectName: "historyEnqueueNext"; text: root.t("下一首播放"); glyph: "next"; Layout.fillWidth: true; onClicked: { player.enqueueTrack(historyMenu.track, "next"); historyMenu.close() } }
        Action { objectName: "historyEnqueueTail"; text: root.t("加入队尾"); glyph: "list"; Layout.fillWidth: true; onClicked: { player.enqueueTrack(historyMenu.track, "tail"); historyMenu.close() } }
    }
    LibrarySheet {
        id: playbackRemoval
        property string kind: "queue"
        property string entryId: ""
        property string trackName: ""
        heading: kind === "history" ? root.t("清空最近播放？") : kind === "entry" ? root.t("移除队列歌曲？") : root.t("清空播放队列？")
        Hint { text: playbackRemoval.kind === "history" ? root.t("清空最近播放记录？正在播放的歌曲会继续。") : playbackRemoval.kind === "entry" ? root.t("从队列移除“") + playbackRemoval.trackName + root.t("”？若是当前歌曲，本首仍会继续播放。") : root.t("清空 ") + player.queueEntries.length + root.t(" 首队列歌曲？当前歌曲会继续，播完后停止。") }
        RowLayout {
            Layout.fillWidth: true; spacing: 8
            Action { objectName: "cancelPlaybackRemoval"; text: root.t("取消"); Layout.fillWidth: true; onClicked: playbackRemoval.close() }
            Action {
                objectName: "confirmPlaybackRemoval"; text: playbackRemoval.kind === "entry" ? root.t("移除") : root.t("清空"); Layout.fillWidth: true
                onClicked: {
                    if (playbackRemoval.kind === "history") player.clearPlaybackHistory()
                    else if (playbackRemoval.kind === "entry") player.removeQueueEntry(playbackRemoval.entryId)
                    else player.clearPlaybackQueue()
                    playbackRemoval.close()
                }
            }
        }
    }
    LibrarySheet {
        id: shortcutCapture; heading: root.t("修改快捷键")
        property string action: ""
        property string label: ""
        property string previous: ""
        property string candidate: ""
        property string failure: ""
        onOpened: { player.shortcuts.setCapturing(true); shortcutCaptureField.forceActiveFocus() }
        onClosed: player.shortcuts.setCapturing(false)
        Hint { text: root.t(shortcutCapture.label) + root.t(" · 当前：") + (shortcutCapture.previous || root.t("已禁用")) }
        Hint { text: root.t("按下新的组合键，包含 Ctrl、Alt 或 Windows 键。") }
        Item {
            id: shortcutCaptureField; objectName: "shortcutCaptureField"
            Layout.fillWidth: true; implicitHeight: 52; activeFocusOnTab: true
            Accessible.name: root.t("录入") + shortcutCapture.label + root.t("快捷键")
            Accessible.description: shortcutCapture.candidate || root.t("等待按下组合键")
            Rectangle { anchors.fill: parent; radius: 8; color: root.control; border.color: shortcutCaptureField.activeFocus ? root.accent : root.fieldBorder; border.width: shortcutCaptureField.activeFocus ? 2 : 1 }
            Copy { anchors.fill: parent; anchors.margins: 10; text: shortcutCapture.candidate || root.t("按下组合键…"); horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter; color: shortcutCapture.candidate.length ? root.accent : root.muted }
            MouseArea { anchors.fill: parent; onClicked: shortcutCaptureField.forceActiveFocus() }
            Keys.onShortcutOverride: function(event) { event.accepted = true }
            Keys.onPressed: function(event) {
                if (event.key === Qt.Key_Escape && event.modifiers === Qt.NoModifier) { shortcutCapture.close(); event.accepted = true; return }
                if (event.isAutoRepeat) { event.accepted = true; return }
                var sequence = player.shortcuts.captureSequence(event.key, event.modifiers)
                if (sequence.length) { shortcutCapture.candidate = sequence; shortcutCapture.failure = "" }
                else if ([Qt.Key_Control, Qt.Key_Alt, Qt.Key_Shift, Qt.Key_Meta].indexOf(event.key) < 0)
                    shortcutCapture.failure = root.t("请包含 Ctrl、Alt 或 Windows 键。")
                event.accepted = true
            }
        }
        Hint { objectName: "shortcutCaptureError"; visible: shortcutCapture.failure.length > 0; text: root.t(shortcutCapture.failure); color: root.danger }
        RowLayout {
            Layout.fillWidth: true; spacing: 8
            Action { objectName: "cancelShortcutBinding"; text: root.t("取消"); Layout.fillWidth: true; onClicked: shortcutCapture.close() }
            Action {
                objectName: "saveShortcutBinding"; text: root.t("保存"); selected: true; Layout.fillWidth: true; enabled: shortcutCapture.candidate.length > 0
                onClicked: {
                    if (player.shortcuts.setBinding(shortcutCapture.action, shortcutCapture.candidate)) shortcutCapture.close()
                    else { shortcutCapture.failure = player.shortcuts.message; shortcutCaptureField.forceActiveFocus() }
                }
            }
        }
    }
    LibrarySheet {
        id: targetSheet; heading: mode === "online" ? root.t("导入在线歌单") : mode === "move" ? root.t("移动歌曲") : root.t("复制歌曲")
        property string sourceId: ""
        property string mode: "copy"
        property var ids: []
        property var targets: []
        Hint { text: targetSheet.mode === "online" ? root.t("选择导入位置。已有歌曲会自动跳过，新建歌单会保留原名称和简介。") : root.t("将 ") + targetSheet.ids.length + root.t(" 首歌曲") + (targetSheet.mode === "move" ? root.t("移动") : root.t("复制")) + root.t("到目标歌单，自动跳过已有歌曲。") }
        Copy { text: root.t("目标歌单") }
        Choice { id: transferTarget; Layout.fillWidth: true; model: targetSheet.targets; textRole: "name"; valueRole: "id"; Accessible.name: root.t("目标歌单") }
        Hint { visible: targetSheet.targets.length === 0; text: root.t("请先在歌单管理中新建一个歌单。") }
        Action {
            text: targetSheet.mode === "online" ? root.t("导入歌单") : targetSheet.mode === "move" ? root.t("移动") : root.t("复制")
            selected: true; Layout.fillWidth: true; enabled: transferTarget.currentIndex >= 0
            onClicked: {
                var target = transferTarget.currentValue
                if (targetSheet.mode === "online") player.libraryAction("addOnline", { target: target })
                else if (root.usePlaylist(targetSheet.sourceId)) {
                    player.libraryAction("transfer", { ids: targetSheet.ids, target: target, move: targetSheet.mode === "move" })
                    root.clearTrackSelection()
                }
                targetSheet.close()
            }
        }
    }
    LibrarySheet {
        id: importSheet; heading: root.t("导入歌单")
        property var targets: []
        Copy { text: root.t("导入到") }
        Choice { id: importTarget; Layout.fillWidth: true; model: importSheet.targets; textRole: "name"; valueRole: "id"; Accessible.name: root.t("导入目标歌单") }
        Hint { text: root.t("支持歌单 JSON，也可粘贴网易云、QQ 或酷我歌单链接；数字 ID 默认按网易云读取。已有歌曲会自动跳过。") }
        RowLayout {
            Layout.fillWidth: true; spacing: 8
            Action { text: root.t("选择文件"); Layout.fillWidth: true; enabled: importTarget.currentIndex >= 0; onClicked: { playlistImportFile.targetId = importTarget.currentValue; importSheet.close(); playlistImportFile.open() } }
            Action { text: root.t("读取剪贴板"); Layout.fillWidth: true; enabled: importTarget.currentIndex >= 0; onClicked: { var target = importTarget.currentValue; importSheet.close(); player.libraryAction("paste", { target: target }) } }
        }
        Copy { text: root.t("或在下方粘贴内容") }
        ScrollView {
            Layout.fillWidth: true; Layout.preferredHeight: 110; clip: true; contentWidth: availableWidth
            TextArea {
                id: importText; selectByMouse: true; wrapMode: TextEdit.Wrap; textFormat: TextEdit.PlainText
                color: root.ink; placeholderTextColor: root.muted; selectionColor: root.accent; selectedTextColor: root.accentInk
                placeholderText: root.t("粘贴 JSON 或网易云 / QQ / 酷我歌单链接"); Accessible.name: root.t("歌单导入内容"); padding: 10
                background: Rectangle { color: root.backdrop; radius: 8; border.color: importText.activeFocus ? root.accent : root.fieldBorder; border.width: importText.activeFocus ? 2 : 1 }
            }
        }
        Action { text: root.t("导入内容"); selected: true; Layout.fillWidth: true; enabled: importText.text.trim().length > 0 && importTarget.currentIndex >= 0; onClicked: { var args = { text: importText.text, target: importTarget.currentValue }; importSheet.close(); player.libraryAction("importText", args) } }
    }
    LibrarySheet {
        id: exportSheet; heading: ids.length ? root.t("导出所选歌曲") : root.t("导出整张歌单")
        property string sourceId: ""
        property string listName: ""
        property var ids: []
        Hint { text: exportSheet.listName + (exportSheet.ids.length ? " · " + exportSheet.ids.length + root.t(" 首") : root.t(" · 全部歌曲")) }
        Hint { text: root.t("导出歌单 JSON，保留歌曲原顺序。文件中包含歌曲信息和引用，不包含音频文件。") }
        Action { text: root.t("复制到剪贴板"); selected: true; Layout.fillWidth: true; onClicked: { if (root.usePlaylist(exportSheet.sourceId)) player.libraryAction("copyExport", { ids: exportSheet.ids }); exportSheet.close() } }
        Action { text: root.t("保存 JSON 文件"); Layout.fillWidth: true; onClicked: root.openExportFile() }
    }
    LibrarySheet {
        id: descriptionSheet; heading: root.t("歌单简介")
        property string sourceId: ""
        Copy { text: root.t("简介") }
        ScrollView {
            Layout.fillWidth: true; Layout.preferredHeight: 150; clip: true; contentWidth: availableWidth
            TextArea {
                id: descriptionText; selectByMouse: true; wrapMode: TextEdit.Wrap; textFormat: TextEdit.PlainText
                color: root.ink; placeholderTextColor: root.muted; selectionColor: root.accent; selectedTextColor: root.accentInk
                placeholderText: root.t("写下这个歌单的风格、心情或用途"); Accessible.name: root.t("歌单简介"); padding: 10
                background: Rectangle { color: root.backdrop; radius: 8; border.color: descriptionText.activeFocus ? root.accent : root.fieldBorder; border.width: descriptionText.activeFocus ? 2 : 1 }
            }
        }
        Action { text: root.t("保存简介"); selected: true; Layout.fillWidth: true; onClicked: { if (root.usePlaylist(descriptionSheet.sourceId)) player.libraryAction("describe", { description: descriptionText.text }); descriptionSheet.close() } }
        onOpened: descriptionText.forceActiveFocus()
    }
    LibrarySheet {
        id: playlistDialog; heading: renaming ? root.t("修改歌单名称") : root.t("新建歌单")
        property bool renaming: false
        property string sourceId: ""
        function save() {
            if (!playlistName.text.trim().length) return
            if (renaming) { if (root.usePlaylist(sourceId)) player.renamePlaylist(playlistName.text) }
            else player.createPlaylist(playlistName.text)
            close()
        }
        Copy { text: root.t("歌单名称") }
        Field { id: playlistName; Layout.fillWidth: true; placeholderText: root.t("1–60 字"); maximumLength: 60; Accessible.name: root.t("歌单名称"); onAccepted: playlistDialog.save() }
        Action { text: root.t("保存"); selected: true; Layout.fillWidth: true; enabled: playlistName.text.trim().length > 0; onClicked: playlistDialog.save() }
        onOpened: playlistName.forceActiveFocus()
    }
    LibrarySheet {
        id: deleteDialog; heading: root.t("删除歌单？")
        property string sourceId: ""
        property string listName: ""
        Hint { text: root.t("删除“") + deleteDialog.listName + root.t("”？本地音频文件会保留。") }
        RowLayout {
            Layout.fillWidth: true; spacing: 8
            Action { text: root.t("取消"); Layout.fillWidth: true; onClicked: deleteDialog.close() }
            Action { text: root.t("删除歌单"); Layout.fillWidth: true; onClicked: { if (deleteDialog.sourceId !== "favorites" && root.usePlaylist(deleteDialog.sourceId)) player.deletePlaylist(); deleteDialog.close() } }
        }
    }
    LibrarySheet {
        id: removeDialog; heading: root.t("移除歌曲？")
        property string sourceId: ""
        property var ids: []
        Hint { text: root.t("从当前歌单移除 ") + removeDialog.ids.length + root.t(" 首歌曲？本地音频文件和其他歌单中的歌曲会保留。") }
        RowLayout {
            Layout.fillWidth: true; spacing: 8
            Action { text: root.t("取消"); Layout.fillWidth: true; onClicked: removeDialog.close() }
            Action { text: root.t("移除"); Layout.fillWidth: true; onClicked: { if (root.usePlaylist(removeDialog.sourceId)) player.libraryAction("removeTracks", { ids: removeDialog.ids }); root.clearTrackSelection(); removeDialog.close() } }
        }
    }
    FileDialog {
        id: playlistImportFile; title: root.t("导入歌单 JSON")
        property string targetId: ""
        fileMode: FileDialog.OpenFile; nameFilters: [root.t("歌单 JSON (*.json)"), root.t("所有文件 (*)")]
        onAccepted: player.libraryAction("importFile", { url: selectedFile.toString(), target: targetId })
    }
    FileDialog {
        id: playlistExportFile; title: root.t("保存歌单 JSON")
        property string sourceId: ""
        property var ids: []
        currentFolder: StandardPaths.writableLocation(StandardPaths.DocumentsLocation)
        fileMode: FileDialog.SaveFile; nameFilters: [root.t("歌单 JSON (*.json)")]; defaultSuffix: "json"
        onAccepted: if (root.usePlaylist(sourceId)) player.libraryAction("exportFile", { url: selectedFile.toString(), ids: ids })
    }
    Rectangle {
        anchors.fill: parent; radius: 20 * root.contentScale; color: "transparent"
        border.color: drop.containsDrag ? root.accent : root.line; border.width: drop.containsDrag ? 2 : 1
    }
    MouseArea {
        anchors.right: parent.right; anchors.bottom: parent.bottom
        width: 22; height: 22; cursorShape: Qt.SizeFDiagCursor
        property real originWidth
        property real originHeight
        property real originX
        property real originY
        onPressed: function(mouse) {
            originWidth=root.width;originHeight=root.height
            var point=mapToGlobal(mouse.x,mouse.y);originX=point.x;originY=point.y
        }
        onPositionChanged: function(mouse) {
            if(!pressed)return
            var point=mapToGlobal(mouse.x,mouse.y)
            var dx=(point.x-originX)/originWidth, dy=(point.y-originY)/originHeight
            var factor=1+(Math.abs(dx)>Math.abs(dy)?dx:dy)
            appearance.windowScale=Math.max(0.9,Math.min(1.4,originWidth*factor/root.baseWidth))
        }
        Rectangle { x: 10; y: 7; width: 1; height: 8; rotation: 45; color: root.muted }
        Rectangle { x: 13; y: 10; width: 1; height: 5; rotation: 45; color: root.muted }
    }
}
