import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import QtCore

ApplicationWindow {
    id: root
    property bool mobile: player.android
    readonly property bool shortScreen: mobile && height < 500
    property bool quitting: false
    property int currentPage: 0
    property int previousPage: 0
    readonly property bool darkMode: appearance.mode === 2 || (appearance.mode === 0 && Qt.styleHints.colorScheme === Qt.Dark)
    readonly property color backdrop: darkMode ? "#101722" : "#F3F5F8"
    readonly property color surface: darkMode ? "#192333" : "#FFFFFF"
    readonly property color elevated: darkMode ? "#233044" : "#FFFFFF"
    readonly property color ink: darkMode ? "#EDF2FA" : "#182338"
    readonly property color muted: darkMode ? "#ABB8CC" : "#56657A"
    readonly property color accent: darkMode ? "#83ABFF" : "#2458D3"
    readonly property color accentInk: darkMode ? "#101722" : "#FFFFFF"
    readonly property color selection: darkMode ? "#243B60" : "#E8EFFF"
    readonly property color line: darkMode ? "#3A465A" : "#D5DEEB"
    readonly property color fieldBorder: darkMode ? "#718198" : "#7B899D"
    readonly property color danger: darkMode ? "#FF9B93" : "#B42318"
    Settings { id: appearance; category: "appearance"; property int mode: 0 }
    visible: mobile
    width: mobile ? 390 : 1040; height: mobile ? 844 : 740
    minimumWidth: mobile ? 320 : 820; minimumHeight: mobile ? 320 : 620
    title: "浮音 0.4"
    color: backdrop
    flags: mobile ? Qt.Window : Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
    font.family: mobile ? "sans-serif" : "Microsoft YaHei UI"; font.pixelSize: 14
    palette.window: surface; palette.base: elevated; palette.text: ink
    palette.windowText: ink; palette.buttonText: ink; palette.button: surface
    palette.highlight: accent; palette.highlightedText: accentInk
    palette.placeholderText: muted; palette.light: selection; palette.midlight: line
    palette.mid: selection; palette.dark: accent
    onClosing: function(close) { quitting = true; close.accepted = true; player.quit() }
    function activateWindow() { root.showNormal(); root.raise(); root.requestActivate() }
    function clock(ms) { var s = Math.floor(ms / 1000); return Math.floor(s / 60) + ":" + (s % 60 < 10 ? "0" : "") + s % 60 }
    function lyricText(text) { return text.replace(/\[(?:\d+:\d+(?:[.:]\d+)?|(?:ar|ti|al|by|offset):[^\]]*)\]/g, "").trim() }
    function chooseMusic() { if (player.android) player.chooseAndroidFile(); else picker.open() }
    onDarkModeChanged: if (player.android) player.setDarkTheme(darkMode)
    Component.onCompleted: if (player.android) player.setDarkTheme(darkMode)
    function showNowPlaying() { if (currentPage !== 3) previousPage = currentPage; currentPage = 3 }
    component Glyph: Canvas {
        id: glyph
        property string kind: "music"
        property color tint: root.ink
        implicitWidth: 20; implicitHeight: 20
        onTintChanged: requestPaint()
        onKindChanged: requestPaint()
        onPaint: {
            var c = getContext("2d"); c.reset(); c.scale(width / 24, height / 24)
            c.strokeStyle = tint; c.fillStyle = tint; c.lineWidth = 1.7; c.lineCap = "round"; c.lineJoin = "round"
            function path(points, close, fill) { c.beginPath(); c.moveTo(points[0][0], points[0][1]); for (var i=1;i<points.length;i++) c.lineTo(points[i][0],points[i][1]); if(close)c.closePath(); if(fill)c.fill(); else c.stroke() }
            if (kind === "search") { c.beginPath(); c.arc(10,10,6,0,Math.PI*2); c.stroke(); path([[15,15],[21,21]]) }
            else if (kind === "play") path([[8,5],[19,12],[8,19]],true,true)
            else if (kind === "pause") { c.fillRect(7,5,3,14); c.fillRect(14,5,3,14) }
            else if (kind === "previous" || kind === "next") { if(kind==="previous"){c.translate(24,0);c.scale(-1,1)} path([[6,6],[15,12],[6,18]],true,true); c.fillRect(17,6,2,12) }
            else if (kind === "close") { path([[6,6],[18,18]]); path([[18,6],[6,18]]) }
            else if (kind === "back") path([[15,5],[8,12],[15,19]])
            else if (kind === "chevron") path([[7,10],[12,15],[17,10]])
            else if (kind === "check") path([[5,12],[10,17],[19,7]])
            else if (kind === "folder") path([[3,7],[10,7],[12,9],[21,9],[21,20],[3,20],[3,7],[3,4],[10,4],[12,7]])
            else if (kind === "list") { for(var y=6;y<=18;y+=6){path([[9,y],[21,y]]);c.fillRect(3,y-1,2,2)} }
            else if (kind === "heart") { c.beginPath();c.moveTo(12,20);c.bezierCurveTo(-4,10,5,-1,12,7);c.bezierCurveTo(19,-1,28,10,12,20);c.stroke() }
            else if (kind === "settings") { for(var j=0;j<3;j++){var yy=6+j*6;path([[3,yy],[21,yy]]);c.clearRect(7+j*3,yy-2,4,4);c.strokeRect(7+j*3,yy-2,4,4)} }
            else if (kind === "volume") { path([[3,9],[7,9],[12,5],[12,19],[7,15],[3,15]],true);c.beginPath();c.arc(12,12,6,-0.8,0.8);c.stroke();c.beginPath();c.arc(12,12,10,-0.8,0.8);c.stroke() }
            else { path([[9,17],[9,5],[19,3],[19,15]]);c.beginPath();c.ellipse(3,16,6,4);c.fill();c.beginPath();c.ellipse(13,14,6,4);c.fill() }
        }
    }
    component Copy: Text { color: root.ink; textFormat: Text.PlainText; font: root.font; elide: Text.ElideRight }
    component Action: Button {
        id: action
        property bool primary: false
        property bool quiet: false
        property string glyph: ""
        implicitHeight: root.mobile ? 48 : 40; implicitWidth: Math.max(root.mobile ? 48 : 40, contentItem.implicitWidth + 24)
        padding: 10; spacing: 8; hoverEnabled: true
        Accessible.name: text
        contentItem: RowLayout {
            spacing: 8
            Glyph { visible: action.glyph.length > 0; kind: action.glyph; tint: action.primary && action.enabled ? root.accentInk : (action.enabled ? root.ink : root.muted); Layout.preferredWidth: 18; Layout.preferredHeight: 18 }
            Text { visible: action.text.length > 0; text: action.text; font: action.font; color: action.primary && action.enabled ? root.accentInk : action.enabled ? root.ink : root.muted; horizontalAlignment: Text.AlignHCenter; Layout.fillWidth: true }
        }
        background: Rectangle {
            radius: action.height / 2
            color: !action.enabled ? root.backdrop : action.primary ? (action.down ? Qt.darker(root.accent, 1.12) : root.accent) : action.down || action.hovered ? root.selection : action.quiet ? "transparent" : root.surface
            border.width: action.activeFocus ? 2 : action.quiet || action.primary ? 0 : 1
            border.color: action.activeFocus ? root.accent : root.line
        }
        ToolTip.visible: hovered && ToolTip.text.length > 0
        ToolTip.delay: 600
    }
    component Field: TextField {
        id: field
        implicitHeight: root.mobile ? 48 : 44; leftPadding: 14; rightPadding: 14
        color: root.ink; placeholderTextColor: root.muted; selectionColor: root.accent; selectedTextColor: root.accentInk; selectByMouse: true
        background: Rectangle { radius: 8; color: root.elevated; border.color: field.activeFocus ? root.accent : root.fieldBorder; border.width: field.activeFocus ? 2 : 1 }
    }
    component Choice: ComboBox {
        id: choice
        implicitHeight: root.mobile ? 48 : 42; implicitWidth: 210; leftPadding: 14; rightPadding: 36; hoverEnabled: true
        contentItem: Text { text: choice.displayText; font: choice.font; color: choice.enabled ? root.ink : root.muted; verticalAlignment: Text.AlignVCenter; elide: Text.ElideRight }
        indicator: Glyph { kind: "chevron"; tint: root.muted; x: choice.width - width - 12; y: (choice.height-height)/2 }
        background: Rectangle { radius: 8; color: choice.hovered ? root.selection : root.elevated; border.color: choice.activeFocus ? root.accent : root.fieldBorder; border.width: choice.activeFocus ? 2 : 1 }
        delegate: ItemDelegate {
            id: option
            required property int index
            text: choice.textAt(index)
            Accessible.name: text
            width: choice.width; height: root.mobile ? 48 : 44; highlighted: choice.highlightedIndex === index
            contentItem: RowLayout {
                Text { text: choice.textAt(option.index); color: choice.currentIndex === option.index ? root.accent : root.ink; font: choice.font; elide: Text.ElideRight; Layout.fillWidth: true }
                Glyph { kind: "check"; tint: root.accent; visible: choice.currentIndex === option.index }
            }
            background: Rectangle { color: option.highlighted || choice.currentIndex === option.index ? root.selection : root.elevated; radius: 4 }
        }
        popup: Popup {
            y: choice.height + 6; width: choice.width; padding: 6; implicitHeight: Math.min(300, contentItem.implicitHeight + 12)
            contentItem: ListView { clip: true; implicitHeight: contentHeight; model: choice.popup.visible ? choice.delegateModel : null; currentIndex: choice.highlightedIndex; ScrollIndicator.vertical: ScrollIndicator {} }
            background: Rectangle { color: root.elevated; radius: 10; border.color: root.fieldBorder }
        }
    }
    component TrackSlider: Slider {
        id: slider
        implicitHeight: root.mobile ? 40 : 26; leftPadding: 8; rightPadding: 8
        background: Rectangle {
            objectName: "sliderTrack"; x: slider.leftPadding; y: (slider.height-height)/2
            width: slider.availableWidth; height: 4; radius: 2; color: root.line
            Rectangle { objectName: "playedFill"; width: slider.position * parent.width; height: parent.height; radius: 2; color: slider.enabled ? root.accent : root.muted }
        }
        handle: Rectangle {
            x: slider.leftPadding + slider.visualPosition * slider.availableWidth - width/2
            y: (slider.height-height)/2; width: 14; height: 14; radius: 7; color: slider.enabled ? root.accent : root.muted
            border.width: slider.activeFocus ? 3 : 0; border.color: root.ink
        }
    }
    component Navigation: Action {
        id: nav
        property int page: 0
        Layout.fillWidth: true; implicitHeight: 46; quiet: true
        onClicked: root.currentPage = page
        background: Rectangle { radius: 8; color: root.currentPage === nav.page ? root.selection : nav.hovered ? root.surface : "transparent"; border.width: nav.activeFocus ? 2 : 0; border.color: root.accent }
        contentItem: RowLayout {
            spacing: 12
            Glyph { kind: nav.glyph; tint: root.currentPage === nav.page ? root.accent : root.muted }
            Copy { text: nav.text; color: root.currentPage === nav.page ? root.accent : root.ink; font.weight: root.currentPage === nav.page ? Font.DemiBold : Font.Normal; Layout.fillWidth: true }
        }
    }
    FileDialog { id: picker; title: "导入音频 · 最大 30 MiB"; nameFilters: ["音频文件 (*.mp3 *.wav *.flac *.ogg *.m4a *.aac)"]; onAccepted: player.importFile(selectedFile) }
    Window {
        id: floating; objectName: "floatingIcon"; transientParent: null; visible: !root.mobile && !root.visible && !root.quitting
        width: 64; height: 64; color: "transparent"; flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        Rectangle { anchors.fill: parent; anchors.margins: 2; radius: 30; color: root.accent; border.color: root.surface
            Glyph { anchors.centerIn: parent; width: 30; height: 30; tint: root.accentInk }
            MouseArea {
                anchors.fill: parent; cursorShape: Qt.PointingHandCursor
                property real startX; property real startY; property real originX; property real originY; property bool moved: false
                onPressed: function(mouse) { originX=floating.x; originY=floating.y; startX=floating.x+mouse.x; startY=floating.y+mouse.y; moved=false }
                onPositionChanged: function(mouse) { if(!pressed)return; var dx=floating.x+mouse.x-startX, dy=floating.y+mouse.y-startY; if(Math.abs(dx)+Math.abs(dy)>Qt.styleHints.startDragDistance)moved=true; if(moved){floating.x=originX+dx;floating.y=originY+dy} }
                onReleased: if(!moved) { root.x=Math.max(floating.screen.virtualX,Math.min(floating.x,floating.screen.virtualX+floating.screen.width-root.width));root.y=Math.max(floating.screen.virtualY,Math.min(floating.y,floating.screen.virtualY+floating.screen.height-root.height));root.activateWindow() }
            }
        }
        DropArea { anchors.fill: parent; onDropped: function(event) { if(event.hasUrls && event.urls.length===1){player.importFile(event.urls[0]);root.activateWindow();event.acceptProposedAction()}else player.rejectDrop() } }
    }
    DropArea { id: drop; anchors.fill: parent; onDropped: function(event) { if(event.hasUrls && event.urls.length===1){player.importFile(event.urls[0]);event.acceptProposedAction()}else player.rejectDrop() } }
    ColumnLayout {
        anchors.fill: parent; anchors.bottomMargin: root.mobile ? mobileNavigation.height : 0; spacing: 0
        Item {
            visible: !root.shortScreen; Layout.fillWidth: true; Layout.preferredHeight: root.mobile ? 56 : 58
            MouseArea { anchors.fill: parent; onPressed: if (!root.mobile) root.startSystemMove() }
            RowLayout { anchors.fill: parent; anchors.leftMargin: root.mobile ? 16 : 24; anchors.rightMargin: 14; spacing: 12
                Glyph { tint: root.accent; width: 24; height: 24 }
                Copy { text: "浮音"; font.pixelSize: 20; font.weight: Font.DemiBold }
                Copy { text: "0.4"; color: root.muted; font.pixelSize: 12 }
                Item { Layout.fillWidth: true }
                Action { quiet: true; text: root.mobile ? "悬浮窗" : "收为图标"; onClicked: { if (player.android) player.showFloating(); else root.hide() } }
                Action { objectName: "exitButton"; quiet: true; glyph: "close"; text: "退出"; Accessible.name: "退出并停止播放"; onClicked: player.quit() }
            }
        }
        RowLayout {
            Layout.fillWidth: true; Layout.fillHeight: true; spacing: 0
            ColumnLayout {
                visible: !root.mobile; Layout.minimumWidth: 164; Layout.maximumWidth: 164; Layout.preferredWidth: 164; Layout.fillHeight: true; Layout.leftMargin: 16; Layout.rightMargin: 16; Layout.topMargin: 14; Layout.bottomMargin: 20; spacing: 6
                Navigation { text: "搜索"; glyph: "search"; page: 0 }
                Navigation { text: "本地音乐"; glyph: "folder"; page: 1 }
                Navigation { text: "歌单"; glyph: "list"; page: 2 }
                Action { Layout.fillWidth: true; glyph: "heart"; text: "收藏 · 待开放"; enabled: false; quiet: true }
                Item { Layout.fillHeight: true }
                Action { Layout.fillWidth: true; glyph: "music"; text: "正在播放"; quiet: true; onClicked: root.showNowPlaying() }
                Navigation { text: "设置"; glyph: "settings"; page: 4 }
            }
            Rectangle {
                Layout.fillWidth: true; Layout.fillHeight: true; Layout.leftMargin: root.mobile ? 8 : 0; Layout.rightMargin: root.mobile ? 8 : 16; Layout.bottomMargin: root.mobile ? 8 : 16; radius: 14; color: root.surface
                StackLayout {
                    anchors.fill: parent; anchors.margins: root.mobile ? 16 : 28; currentIndex: root.currentPage
                    ColumnLayout {
                        spacing: 16
                        Copy { text: "搜索音乐"; font.pixelSize: root.mobile ? 24 : 28; font.weight: Font.DemiBold }
                        Copy { visible: !root.mobile; text: "从一首想听的歌开始"; color: root.muted }
                        RowLayout { Layout.fillWidth: true; spacing: 10
                            Field { id: keywords; objectName: "searchInput"; Layout.fillWidth: true; placeholderText: "输入歌名"; Accessible.name: "按歌名搜索音乐"; onAccepted: {player.search(text); if(root.mobile)Qt.inputMethod.hide()} }
                            Action { text: player.searching ? "搜索中…" : "搜索"; glyph: "search"; primary: true; onClicked: {player.search(keywords.text); if(root.mobile)Qt.inputMethod.hide()} }
                        }
                        Copy { objectName: "searchMessage"; Layout.fillWidth: true; text: player.searchMessage; color: root.muted; wrapMode: Text.Wrap; elide: Text.ElideNone; visible: text.length>0 }
                        ListView {
                            id: results; objectName: "searchResults"; Layout.fillWidth: true; Layout.fillHeight: true; clip: true
                            model: player.searchResults; ScrollBar.vertical: ScrollBar {}
                            delegate: Rectangle {
                                required property var modelData; required property int index
                                width: results.width; height: 72; color: !root.mobile && rowHover.hovered ? root.backdrop : root.surface
                                HoverHandler { id: rowHover }
                                RowLayout { anchors.fill: parent; anchors.leftMargin: 8; anchors.rightMargin: 8; spacing: 14
                                    Copy { text: String(index+1).padStart(2,"0"); color: root.muted; font.pixelSize: 12; Layout.preferredWidth: 26; visible: !root.mobile }
                                    ColumnLayout { Layout.fillWidth: true; spacing: 4
                                        Copy { Layout.fillWidth: true; text: modelData.name; font.weight: Font.Medium }
                                        Copy { Layout.fillWidth: true; text: modelData.artist; color: root.muted; font.pixelSize: 12 }
                                    }
                                    Action { objectName: "playSearchResult"; glyph: "play"; text: root.mobile ? "" : "播放"; Accessible.name: "播放歌曲"; quiet: true; enabled: !player.busy; onClicked: player.playSearchResult(index) }
                                    Action { text: root.mobile ? "+" : "+ 歌单"; Accessible.name: "加入当前歌单"; quiet: true; ToolTip.text: "加入当前歌单：" + lists.currentText; onClicked: player.addSearchResult(index) }
                                }
                                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: root.line; opacity: 0.5 }
                            }
                            Column { anchors.centerIn: parent; spacing: 14; visible: results.count===0
                                Glyph { anchors.horizontalCenter: parent.horizontalCenter; width: 42; height: 42; kind: "search"; tint: root.muted }
                                Copy { anchors.horizontalCenter: parent.horizontalCenter; text: player.searching ? "正在查找歌曲…" : "输入歌名，找到想听的音乐"; color: root.muted }
                                Copy { anchors.horizontalCenter: parent.horizontalCenter; text: root.mobile ? "也可以在本地页导入音频" : "也可以将本地音频拖入窗口"; color: root.muted; font.pixelSize: 12 }
                            }
                        }
                    }
                    ColumnLayout {
                        spacing: 16
                        RowLayout { Layout.fillWidth: true
                            Copy { text: "本地音乐"; font.pixelSize: root.mobile ? 24 : 28; font.weight: Font.DemiBold; Layout.fillWidth: true }
                            Action { text: "导入音乐"; primary: true; enabled: !player.busy; onClicked: root.chooseMusic() }
                        }
                        Copy { Layout.fillWidth: true; text: "当前歌单："+lists.currentText+" · 导入的音频会加入此歌单"; color: root.muted; wrapMode: Text.Wrap; elide: Text.ElideNone }
                        Copy { Layout.fillWidth: true; wrapMode: Text.Wrap; elide: Text.ElideNone; text: "MP3 / WAV / FLAC / OGG / M4A / AAC · 单首 ≤ 30 MiB"; color: root.muted; font.pixelSize: 12 }
                        ListView { id: localSongs; objectName: "localSongs"; Layout.fillWidth: true; Layout.fillHeight: true; clip: true
                            model: player.tracks.filter(function(t){return t.source === "local"})
                            ScrollBar.vertical: ScrollBar {}
                            delegate: ItemDelegate {
                                required property var modelData
                                width: localSongs.width; height: 60
                                contentItem: Copy { text: modelData.name; verticalAlignment: Text.AlignVCenter }
                                background: Rectangle { color: parent.hovered ? root.selection : "transparent" }
                                enabled: !player.busy; onClicked: player.playTrack(modelData.id); Accessible.name: "播放 " + modelData.name
                            }
                            Copy { anchors.centerIn: parent; visible: localSongs.count===0; text: "点击导入，或拖入一首本地音乐"; color: root.muted }
                        }
                    }
                    ColumnLayout {
                        spacing: 16
                        Copy { text: "我的歌单"; font.pixelSize: root.mobile ? 24 : 28; font.weight: Font.DemiBold }
                        GridLayout { Layout.fillWidth: true; columns: root.mobile ? 3 : 4
                            Choice { id: lists; Layout.columnSpan: root.mobile ? 3 : 1; objectName: "playlistSelector"; Layout.fillWidth: true; model: player.playlists; textRole: "name"; valueRole: "id"; Accessible.name: "当前歌单"
                                function updateSelection() { currentIndex=indexOfValue(player.activePlaylist) }
                                Component.onCompleted: updateSelection()
                                onModelChanged: Qt.callLater(updateSelection)
                                onActivated: player.selectPlaylist(currentValue)
                            }
                            Action { text: "新建"; onClicked: {playlistDialog.renaming=false;playlistName.text="";playlistDialog.open()} }
                            Action { text: "改名"; onClicked: {playlistDialog.renaming=true;playlistName.text=lists.currentText;playlistDialog.open()} }
                            Action { text: "删除"; enabled: player.playlists.length>1; onClicked: deleteDialog.open() }
                        }
                        Field { id: filter; Layout.fillWidth: true; placeholderText: "筛选歌名或歌手"; Accessible.name: "筛选当前歌单" }
                        ListView { id: songs; objectName: "playlistView"; Layout.fillWidth: true; Layout.fillHeight: true; clip: true; model: player.tracks; ScrollBar.vertical: ScrollBar {}
                            delegate: Rectangle {
                                required property var modelData
                                width: songs.width; height: visible ? 64 : 0
                                visible: (modelData.name+" "+(modelData.artist||"")).toLowerCase().indexOf(filter.text.trim().toLowerCase())>=0
                                color: player.currentTrack===modelData.id ? root.selection : "transparent"; radius: 6
                                RowLayout { anchors.fill: parent; anchors.margins: 8; spacing: 12
                                    ColumnLayout { Layout.fillWidth: true; spacing: 3
                                        Copy { Layout.fillWidth: true; text: modelData.name }
                                        Copy { Layout.fillWidth: true; text: modelData.artist||"本地文件"; color: root.muted; font.pixelSize: 12 }
                                    }
                                    Action { glyph: "play"; text: root.mobile ? "" : "播放"; Accessible.name: "播放歌曲"; quiet: true; enabled: !player.busy; onClicked: player.playTrack(modelData.id) }
                                    Action { text: "移除"; quiet: true; onClicked: player.removeTrack(modelData.id) }
                                }
                            }
                            Copy { anchors.centerIn: parent; visible: songs.count===0; text: "导入音乐，或将搜索结果加入歌单"; color: root.muted }
                        }
                        Copy { text: player.tracks.length+" 首 · 自动播放到末尾停止"; color: root.muted; font.pixelSize: 12 }
                    }
                    ColumnLayout {
                        spacing: 16
                        RowLayout { Layout.fillWidth: true
                            Action { glyph: "back"; text: "返回"; quiet: true; onClicked: root.currentPage=root.previousPage }
                            Item { Layout.fillWidth: true }
                            Choice { id: lyricMode; model: ["原文", "译文", "原文与译文"]; implicitWidth: 160; Accessible.name: "歌词显示方式" }
                        }
                        Copy { Layout.fillWidth: true; text: player.title; font.pixelSize: 26; font.weight: Font.DemiBold }
                        Copy { Layout.fillWidth: true; text: player.qualityInfo + (player.quality === "hires" ? "\nHi-Res 为请求档位，实际音源可能回落为普通 FLAC。" : ""); color: root.muted; wrapMode: Text.Wrap; elide: Text.ElideNone; font.pixelSize: 12 }
                        RowLayout { Layout.fillWidth: true
                            Copy { Layout.fillWidth: true; text: player.lyricsMessage; color: player.lyricsFailed ? root.danger : root.muted; wrapMode: Text.Wrap; elide: Text.ElideNone; font.pixelSize: 12 }
                            Action { objectName: "retryLyrics"; text: "重新获取"; quiet: true; enabled: player.online&&!player.lyricsLoading; onClicked: player.retryLyrics() }
                        }
                        ScrollView { Layout.fillWidth: true; Layout.fillHeight: true; clip: true; contentWidth: availableWidth
                            TextArea { objectName: "lyricsText"; readOnly: true; selectByMouse: true; wrapMode: TextEdit.Wrap; textFormat: TextEdit.PlainText; font.pixelSize: 19; color: root.ink; selectionColor: root.accent; selectedTextColor: root.accentInk; background: null; padding: 16
                                text: lyricMode.currentIndex===0 ? root.lyricText(player.lyrics) : lyricMode.currentIndex===1 ? (root.lyricText(player.translation)||"暂无译文") : root.lyricText(player.lyrics)+(player.translation.length>0 ? "\n\n—— 译文 ——\n\n"+root.lyricText(player.translation) : "\n\n暂无译文")
                            }
                        }
                    }
                    ScrollView {
                        clip: true; contentWidth: availableWidth
                        ColumnLayout { width: parent.width; spacing: 20
                            Copy { text: "设置"; font.pixelSize: root.mobile ? 24 : 28; font.weight: Font.DemiBold }
                            Copy { text: "外观"; font.pixelSize: 17; font.weight: Font.DemiBold }
                            RowLayout { Layout.fillWidth: true
                                Copy { text: "界面主题"; Layout.fillWidth: true }
                                Choice { objectName: "themeSelector"; model: ["跟随系统", "浅色", "深色"]; currentIndex: appearance.mode; onActivated: function(index) {appearance.mode=index;appearance.setValue("mode",index);appearance.sync()}; Accessible.name: "界面主题" }
                            }
                            Rectangle { Layout.fillWidth: true; height: 1; color: root.line }
                            Copy { text: "音乐服务"; font.pixelSize: 17; font.weight: Font.DemiBold }
                            Copy { Layout.fillWidth: true; text: player.apiBase.length===0 ? "正在使用内置网易云搜索。自定义地址留空即可恢复内置服务。" : "正在使用自定义音乐服务。"; wrapMode: Text.Wrap; elide: Text.ElideNone; color: root.muted }
                            Field { id: apiAddress; objectName: "apiAddress"; Layout.fillWidth: true; text: player.apiBase; placeholderText: "网易云兼容 API 地址（可选）"; Accessible.name: "自定义音乐服务地址" }
                            RowLayout {
                                Action { text: "保存地址"; primary: true; enabled: !player.busy; onClicked: player.setApiBase(apiAddress.text) }
                                Action { text: "恢复内置"; enabled: !player.busy; onClicked: {player.setApiBase("");apiAddress.text=""} }
                            }
                            Rectangle { Layout.fillWidth: true; height: 1; color: root.line }
                            Copy { text: "关于浮音"; font.pixelSize: 17; font.weight: Font.DemiBold }
                            Copy { text: root.mobile ? "版本 0.4 · Android" : "版本 0.4 · Windows"; color: root.muted }
                            Copy { Layout.fillWidth: true; text: root.mobile ? "返回或切换应用可继续播放。悬浮窗需系统授权；点击“退出”会停止播放。收藏功能将在后续版本开放。" : "关闭窗口会退出并停止播放。使用顶部“收为图标”可继续后台播放。"; color: root.muted; wrapMode: Text.Wrap; elide: Text.ElideNone }
                        }
                    }
                }
            }
        }
        Rectangle {
            id: playerBar; objectName: "playerBar"; Layout.fillWidth: true
            Layout.preferredHeight: (root.mobile ? (root.shortScreen ? 154 : 190) : 148) + (errorBox.visible ? errorBox.implicitHeight+12 : 0); color: root.surface
            Rectangle { width: parent.width; height: 1; color: root.line }
            ColumnLayout { anchors.fill: parent; anchors.leftMargin: root.mobile ? 16 : 24; anchors.rightMargin: root.mobile ? 16 : 24; anchors.topMargin: 12; anchors.bottomMargin: 12; spacing: 6
                RowLayout { Layout.fillWidth: true; spacing: 16
                    Rectangle { visible: !root.mobile; width: 44; height: 44; radius: 10; color: root.selection
                        Glyph { anchors.centerIn: parent; tint: root.accent; width: 24; height: 24 }
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.showNowPlaying() }
                    }
                    ColumnLayout { Layout.fillWidth: true; spacing: 3
                        Copy { Layout.fillWidth: true; text: player.title; font.weight: Font.DemiBold
                            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: root.showNowPlaying() }
                        }
                        Copy { Layout.fillWidth: true; text: player.status; color: root.muted; font.pixelSize: 12 }
                    }
                    Action { visible: root.mobile; text: "歌词"; quiet: true; onClicked: root.showNowPlaying() }
                    Action { objectName: "previousButton"; visible: !root.mobile; glyph: "previous"; quiet: true; Accessible.name: "上一首"; ToolTip.text: "上一首"; enabled: !player.busy&&player.tracks.length>0; onClicked: player.previous() }
                    Action { objectName: "playPauseButton"; glyph: player.playing ? "pause" : "play"; primary: true; implicitWidth: 48; implicitHeight: 48; Accessible.name: player.playing ? "暂停" : "播放"; ToolTip.text: player.playing ? "暂停" : "播放"; enabled: player.ready&&!player.busy; onClicked: player.toggle() }
                    Action { objectName: "nextButton"; visible: !root.mobile; glyph: "next"; quiet: true; Accessible.name: "下一首"; ToolTip.text: "下一首"; enabled: !player.busy&&player.tracks.length>0; onClicked: player.next() }
                    Choice { id: qualitySelector; visible: !root.mobile; objectName: "qualitySelector"; implicitWidth: 134; enabled: !player.busy; Accessible.name: "在线音质"
                        model: ["标准", "较高", "极高", "无损 FLAC", "Hi-Res"]
                        property var levels: ["standard","higher","exhigh","lossless","hires"]
                        currentIndex: levels.indexOf(player.quality)
                        onActivated: function(index) {player.setQuality(levels[index])}
                        Connections { target: player; function onChanged(){qualitySelector.currentIndex=qualitySelector.levels.indexOf(player.quality)} }
                    }
                }
                RowLayout { visible: root.mobile && !root.shortScreen; Layout.fillWidth: true; spacing: 8
                    Action { glyph: "previous"; quiet: true; Accessible.name: "上一首"; enabled: !player.busy && player.tracks.length>0; onClicked: player.previous() }
                    Action { glyph: "next"; quiet: true; Accessible.name: "下一首"; enabled: !player.busy && player.tracks.length>0; onClicked: player.next() }
                    Choice { objectName: "mobileQuality"; Layout.fillWidth: true; model: qualitySelector.model; currentIndex: qualitySelector.currentIndex; enabled: !player.busy; Accessible.name: "在线音质"; onActivated: function(index) {player.setQuality(qualitySelector.levels[index])} }
                    Action { glyph: "volume"; quiet: true; Accessible.name: "音量与音频输出"; onClicked: soundPopup.open() }
                }
                RowLayout { Layout.fillWidth: true; spacing: 10
                    Copy { text: root.clock(progress.pressed ? progress.value : player.position); color: root.muted; font.pixelSize: 12; Layout.preferredWidth: 44 }
                    TrackSlider { id: progress; objectName: "progress"; Layout.fillWidth: true; from: 0; to: Math.max(1,player.duration); enabled: player.seekable; Accessible.name: "播放进度"
                        onPressedChanged: if(!pressed&&enabled)player.seek(value)
                        onMoved: if(!pressed&&enabled)player.seek(value)
                        Binding { target: progress; property: "value"; value: player.position; when: !progress.pressed }
                    }
                    Copy { text: root.clock(player.duration); color: root.muted; font.pixelSize: 12; Layout.preferredWidth: 44 }
                    Action { id: soundButton; visible: !root.mobile || root.shortScreen; objectName: "soundButton"; glyph: "volume"; quiet: true; implicitHeight: 30; Accessible.name: "选择音频输出"; ToolTip.text: "音频输出："+player.outputName; onClicked: soundPopup.open() }
                    TrackSlider { id: volumeSlider; visible: !root.mobile; objectName: "volumeSlider"; Layout.preferredWidth: 100; from: 0; to: 100; stepSize: 1; Accessible.name: "音量"
                        onMoved: player.setVolume(Math.round(value))
                        Binding { target: volumeSlider; property: "value"; value: player.volume; when: !volumeSlider.pressed }
                    }
                    Copy { visible: !root.mobile; text: player.volume+"%"; color: root.muted; font.pixelSize: 12; Layout.preferredWidth: 34 }
                }
                RowLayout { visible: !root.mobile; Layout.fillWidth: true
                    Copy { Layout.fillWidth: true; text: player.quality==="hires" ? "Hi-Res 为请求档位，实际音源可能回落为普通 FLAC。" : player.qualityInfo; color: root.muted; font.pixelSize: 11 }
                    Action { text: "歌词"; quiet: true; implicitHeight: root.mobile ? 44 : 26; onClicked: root.showNowPlaying() }
                }
                RowLayout { id: errorBox; visible: player.error.length>0; Layout.fillWidth: true
                    Copy { objectName: "playbackError"; Layout.fillWidth: true; text: player.error; color: root.danger; wrapMode: Text.Wrap; elide: Text.ElideNone; font.pixelSize: 12 }
                    Action { objectName: "retryPlayback"; text: "重试播放"; implicitHeight: 30; enabled: !player.busy; onClicked: player.retryPlayback() }
                }
            }
        }
    }
    RowLayout {
        id: mobileNavigation; objectName: "mobileNavigation"; visible: root.mobile
        anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
        height: root.mobile ? 56 : 0; spacing: 4
        Repeater { model: [{name:"搜索",page:0},{name:"本地",page:1},{name:"歌单",page:2},{name:"设置",page:4}]
            Action { required property var modelData; Layout.fillWidth: true; text: modelData.name; primary: root.currentPage===modelData.page; quiet: true; onClicked: root.currentPage=modelData.page }
        }
    }
    Popup {
        id: soundPopup; objectName: "soundPopup"; parent: Overlay.overlay
        x: Math.max(8,root.width-width-16); y: Math.max(8,root.height-playerBar.height-height-(root.mobile ? 64 : 8))
        width: Math.min(350,root.width-24); padding: 18; modal: false
        background: Rectangle { color: root.elevated; radius: 12; border.color: root.fieldBorder }
        contentItem: ColumnLayout { spacing: 12
            RowLayout { Layout.fillWidth: true
                Copy { text: "音频输出"; font.weight: Font.DemiBold; Layout.fillWidth: true }
                Action { text: "刷新"; quiet: true; onClicked: player.refreshOutputs() }
            }
            Choice { id: outputs; objectName: "outputSelector"; Layout.fillWidth: true; model: player.audioOutputs; textRole: "name"; valueRole: "id"; Accessible.name: "音频输出设备"
                function syncSelection(){currentIndex=indexOfValue(player.selectedOutput)}
                Component.onCompleted: syncSelection()
                onModelChanged: Qt.callLater(syncSelection)
                onActivated: player.selectOutput(currentValue)
                Connections { target: player; function onAudioSettingsChanged(){outputs.syncSelection()} }
            }
            Copy { visible: root.mobile; text: "音量 " + player.volume + "%" }
            TrackSlider { visible: root.mobile; Layout.fillWidth: true; from: 0; to: 100; stepSize: 1; value: player.volume; Accessible.name: "音量"; onMoved: player.setVolume(Math.round(value)) }
            Copy { Layout.fillWidth: true; text: "当前："+player.outputName; color: root.muted; wrapMode: Text.Wrap; elide: Text.ElideNone; font.pixelSize: 12 }
        }
    }
    Dialog { id: playlistDialog; width: Math.min(340,root.width-32); property bool renaming: false; anchors.centerIn: parent; title: renaming ? "重命名歌单" : "新建歌单"; modal: true
        standardButtons: Dialog.Ok|Dialog.Cancel
        background: Rectangle { color: root.elevated; radius: 12; border.color: root.fieldBorder }
        Field { id: playlistName; width: parent.width; placeholderText: "歌单名称（1–60 字）"; maximumLength: 60 }
        onAccepted: {if(renaming)player.renamePlaylist(playlistName.text);else player.createPlaylist(playlistName.text)}
    }
    Dialog { id: deleteDialog; width: Math.min(340,root.width-32); anchors.centerIn: parent; title: "删除歌单？"; modal: true; standardButtons: Dialog.Ok|Dialog.Cancel
        background: Rectangle { color: root.elevated; radius: 12; border.color: root.fieldBorder }
        Label { width: parent.width; text: "删除“"+lists.currentText+"”，保留本地音频副本。"; wrapMode: Text.Wrap; textFormat: Text.PlainText }
        onAccepted: player.deletePlaylist()
    }
    Rectangle { anchors.fill: parent; color: "transparent"; border.color: drop.containsDrag ? root.accent : root.line; border.width: drop.containsDrag ? 2 : 1 }
    MouseArea {
        visible: !root.mobile; anchors.right: parent.right; anchors.bottom: parent.bottom; width: 16; height: 16
        cursorShape: Qt.SizeFDiagCursor
        onPressed: root.startSystemResize(Qt.RightEdge | Qt.BottomEdge)
        Rectangle { x: 8; y: 7; width: 1; height: 8; rotation: 45; color: root.muted }
        Rectangle { x: 11; y: 10; width: 1; height: 5; rotation: 45; color: root.muted }
    }
}
