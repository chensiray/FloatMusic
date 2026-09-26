import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import QtCore

ApplicationWindow {
    id: root
    property bool quitting: false
    property string section: ""
    property string detail: ""
    readonly property var playbackModes: [
        { key: "sequential", name: "顺序播放", icon: "sequential" },
        { key: "loop", name: "列表循环", icon: "loop" },
        { key: "single", name: "单曲循环", icon: "single" },
        { key: "shuffle", name: "随机播放", icon: "shuffle" }
    ]
    readonly property string playbackModeName: playbackModes.filter(function(mode) { return mode.key === player.playbackMode })[0].name
    readonly property int baseWidth: 380
    property rect workArea: Qt.rect(0, 0, 1280, 720)
    readonly property real contentScale: Math.min(Math.max(0.9, Math.min(1.4, appearance.windowScale)), Math.max(0.4, (workArea.width - 16) / baseWidth))
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
    Settings {
        id: appearance; category: "appearance"
        property int mode: 0
        property real windowScale: 1.0
        property real backgroundOpacity: 1.0
        property int iconX: 48
        property int iconY: 120
    }
    visible: false
    width: Math.ceil(baseWidth * contentScale)
    height: Math.min(Math.max(160, workArea.height - 16), Math.ceil(shell.implicitHeight * contentScale))
    title: "浮音 0.6 · 桌面预览"
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
        playbackMenu.close(); lyricTiming.close()
        floating.x = root.x; floating.y = root.y
        section = ""; detail = ""; root.hide(); fitWindow(floating); saveIconPosition()
    }
    function saveIconPosition() { appearance.iconX = floating.x; appearance.iconY = floating.y }
    function toggleSection(name) { section = section === name ? "" : name; detail = "" }
    function openDetail(name) { section = "more"; detail = name }
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
        }
    }
    Shortcut { sequence: "Ctrl+F"; enabled: root.visible; onActivated: {root.openDetail("search");keywords.forceActiveFocus()} }
    Shortcut { sequence: "Ctrl+O"; enabled: root.visible; onActivated: root.chooseMusic() }
    Shortcut { sequence: "Escape"; enabled: root.visible && !playbackMenu.opened && !lyricTiming.opened; onActivated: {if(root.detail.length)root.detail="";else if(root.section.length)root.section="";else root.collapse()} }
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
            else if (kind === "minus") path([[5,12],[19,12]])
            else if (kind === "more") { for(var k=5;k<=19;k+=7){c.beginPath();c.arc(k,12,1.7,0,Math.PI*2);c.fill()} }
            else if (kind === "close") { path([[6,6],[18,18]]); path([[18,6],[6,18]]) }
            else if (kind === "back") path([[15,5],[8,12],[15,19]])
            else if (kind === "chevron") path([[7,10],[12,15],[17,10]])
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
        implicitHeight: 44; implicitWidth: Math.max(44, contentItem.implicitWidth + 24)
        padding: 10; spacing: 8; hoverEnabled: true
        Accessible.name: text
        contentItem: Item {
            implicitWidth: buttonContents.implicitWidth; implicitHeight: buttonContents.implicitHeight
            RowLayout {
                id: buttonContents; anchors.centerIn: parent; spacing: 8
                Glyph { visible: action.glyph.length > 0; kind: action.glyph; tint: action.primary && action.enabled ? root.accentInk : (action.enabled ? root.ink : root.muted); Layout.preferredWidth: 18; Layout.preferredHeight: 18 }
                Text { visible: action.text.length > 0; text: action.text; font: action.font; color: action.primary && action.enabled ? root.accentInk : action.enabled ? root.ink : root.muted; horizontalAlignment: Text.AlignHCenter }
            }
        }
        background: Rectangle {
            radius: 12
            color: !action.enabled ? root.backdrop : action.primary ? (action.down ? Qt.darker(root.accent, 1.12) : root.accent) : action.down || action.hovered ? root.selection : action.quiet ? "transparent" : root.surface
            border.width: action.activeFocus ? 2 : action.quiet || action.primary ? 0 : 1
            border.color: action.activeFocus ? root.accent : root.line
        }
        ToolTip.visible: hovered && ToolTip.text.length > 0
        ToolTip.delay: 600
    }
    component Field: TextField {
        id: field
        implicitHeight: 44; leftPadding: 14; rightPadding: 14
        color: root.ink; placeholderTextColor: root.muted; selectionColor: root.accent; selectedTextColor: root.accentInk; selectByMouse: true
        background: Rectangle { radius: 8; color: root.elevated; border.color: field.activeFocus ? root.accent : root.fieldBorder; border.width: field.activeFocus ? 2 : 1 }
    }
    component Choice: ComboBox {
        id: choice
        implicitHeight: 44; implicitWidth: 210; leftPadding: 14; rightPadding: 36; hoverEnabled: true
        contentItem: Text { text: choice.displayText; font: choice.font; color: choice.enabled ? root.ink : root.muted; verticalAlignment: Text.AlignVCenter; elide: Text.ElideRight }
        indicator: Glyph { kind: "chevron"; tint: root.muted; x: choice.width - width - 12; y: (choice.height-height)/2 }
        background: Rectangle { radius: 8; color: choice.hovered ? root.selection : root.elevated; border.color: choice.activeFocus ? root.accent : root.fieldBorder; border.width: choice.activeFocus ? 2 : 1 }
        delegate: ItemDelegate {
            id: option
            required property int index
            text: choice.textAt(index)
            Accessible.name: text
            width: choice.width; height: 44; highlighted: choice.highlightedIndex === index
            contentItem: RowLayout {
                Text { text: choice.textAt(option.index); color: choice.currentIndex === option.index ? root.accent : root.ink; font: choice.font; elide: Text.ElideRight; Layout.fillWidth: true }
                Glyph { kind: "check"; tint: root.accent; visible: choice.currentIndex === option.index }
            }
            background: Rectangle { color: option.highlighted || choice.currentIndex === option.index ? root.selection : root.elevated; radius: 4 }
        }
        popup: Popup {
            parent: Overlay.overlay
            property point origin: Qt.point(0, 0)
            onAboutToShow: origin = choice.mapToItem(Overlay.overlay, 0, 0)
            x: Math.max(8, Math.min(origin.x, root.width - width * root.contentScale - 8))
            y: Math.max(8, Math.min(origin.y + (choice.height + 4) * root.contentScale, root.height - height * root.contentScale - 8))
            width: choice.width; padding: 6; scale: root.contentScale; transformOrigin: Popup.TopLeft
            implicitHeight: Math.min(260, root.height / root.contentScale - 24, contentItem.implicitHeight + 12)
            contentItem: ListView { clip: true; implicitHeight: contentHeight; model: choice.popup.visible ? choice.delegateModel : null; currentIndex: choice.highlightedIndex; ScrollIndicator.vertical: ScrollIndicator {} }
            background: Rectangle { color: root.elevated; radius: 10; border.color: root.fieldBorder }
        }
    }
    component TrackSlider: Slider {
        id: slider
        implicitHeight: 32; leftPadding: 8; rightPadding: 8
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

    component SectionTab: Action {
        id: tab
        property string sectionName
        Layout.fillWidth: true
        primary: root.section === sectionName
        onClicked: root.toggleSection(sectionName)
        Accessible.description: primary ? "已展开，再次点击收起" : "点击展开"
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
        background: Rectangle { radius: 12; color: root.elevated; border.color: root.fieldBorder }
        onClosed: if (root.visible) anchorItem.forceActiveFocus()
    }
    UpPopup {
        id: playbackMenu; objectName: "playbackModePopup"; anchorItem: modeButton
        width: 176; height: 192
        contentItem: Column {
            Repeater {
                model: root.playbackModes
                delegate: ItemDelegate {
                    id: modeOption
                    required property var modelData
                    width: 160; height: 44
                    Accessible.name: modelData.name
                    Accessible.role: Accessible.RadioButton
                    Accessible.checked: player.playbackMode === modelData.key
                    onClicked: { player.setPlaybackMode(modelData.key); playbackMenu.close() }
                    contentItem: RowLayout {
                        spacing: 10
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
        id: lyricTiming; anchorItem: timingButton; width: 264; height: 146
        contentItem: ColumnLayout {
            spacing: 6
            Copy { text: "歌词时间微调"; font.weight: Font.DemiBold }
            Hint { text: "仅此歌曲 · 正值让歌词提前" }
            RowLayout {
                Action { text: "−0.5s"; Accessible.name: "歌词延后半秒"; enabled: player.lyricOffset > -10000; onClicked: player.setLyricOffset(player.lyricOffset - 500) }
                Action { text: (player.lyricOffset > 0 ? "+" : "") + (player.lyricOffset / 1000).toFixed(1) + "s"; Layout.fillWidth: true; ToolTip.text: "点击归零"; Accessible.name: "歌词偏移，点击归零"; onClicked: player.setLyricOffset(0) }
                Action { text: "+0.5s"; Accessible.name: "歌词提前半秒"; enabled: player.lyricOffset < 10000; onClicked: player.setLyricOffset(player.lyricOffset + 500) }
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
        signal playRequested()
        signal extraRequested()
        signal removeRequested()
        property bool removable: false
        width: ListView.view ? ListView.view.width : 320
        height: 70; radius: 10
        color: player.currentTrack === track.id ? root.selection : songHover.hovered ? root.backdrop : "transparent"
        HoverHandler { id: songHover }
        RowLayout {
            anchors.fill: parent; anchors.margins: 6; spacing: 8
            ColumnLayout {
                Layout.fillWidth: true; Layout.minimumWidth: 0; spacing: 4
                Copy { Layout.fillWidth: true; text: song.track.name; font.weight: Font.DemiBold }
                Copy { Layout.fillWidth: true; text: song.track.artist || "本地音频"; color: root.muted; font.pixelSize: 12 }
            }
            Action { glyph: "play"; quiet: true; enabled: !player.busy; Accessible.name: "播放 " + song.track.name; ToolTip.text: Accessible.name; onClicked: song.playRequested() }
            Action { visible: song.extraText.length > 0; text: song.extraText; quiet: true; Accessible.name: "加入当前歌单"; ToolTip.text: "加入当前歌单"; onClicked: song.extraRequested() }
            Action { visible: song.removable; glyph: "close"; quiet: true; Accessible.name: "移除 " + song.track.name; ToolTip.text: Accessible.name; onClicked: song.removeRequested() }
        }
    }
    FileDialog {
        id: picker; title: "导入音频 · 最大 30 MiB"
        nameFilters: ["音频文件 (*.mp3 *.wav *.flac *.ogg *.m4a *.aac)"]
        onAccepted: player.importFile(selectedFile)
    }
    Window {
        id: floating; objectName: "floatingIcon"; transientParent: null
        visible: !root.visible && !root.quitting
        width: 64; height: 64; color: "transparent"
        flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        onClosing: function(event) { event.accepted = true; root.quitApp() }
        Rectangle {
            anchors.fill: parent; anchors.margins: 2; radius: 30; color: root.accent
            border.color: root.surface; border.width: iconMouse.containsMouse ? 2 : 1
            Glyph { anchors.centerIn: parent; width: 30; height: 30; tint: root.accentInk }
            MouseArea {
                id: iconMouse
                anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                acceptedButtons: Qt.LeftButton | Qt.RightButton
                Accessible.role: Accessible.Button; Accessible.name: "浮音，点击展开，拖动移动"
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
                    MenuItem { text: "打开播放器"; onTriggered: root.activateWindow() }
                    MenuItem { text: "退出浮音"; onTriggered: root.quitApp() }
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
    Rectangle {
        anchors.fill: parent; radius: 20 * root.contentScale; color: root.surface
        opacity: Math.max(0.2, Math.min(1, appearance.backgroundOpacity))
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
            id: outerScroll; anchors.fill: parent; clip: true
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
                            Layout.fillWidth: true; implicitHeight: 44
                            RowLayout {
                                anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; spacing: 8
                                Glyph { tint: root.accent }
                                Copy { text: "浮音"; font.weight: Font.DemiBold; color: root.muted }
                            }
                            MouseArea { anchors.fill: parent; cursorShape: Qt.SizeAllCursor; onPressed: root.startSystemMove() }
                            ToolTip.text: "拖动这里移动窗口"; ToolTip.visible: headerHover.hovered
                            HoverHandler { id: headerHover }
                        }
                        Action { objectName: "collapseButton"; glyph: "minus"; quiet: true; Accessible.name: "收为图标"; ToolTip.text: "收为图标"; onClicked: root.collapse() }
                    }
                    Copy {
                        Layout.fillWidth: true; text: player.title; font.pixelSize: 24; font.weight: Font.DemiBold
                        wrapMode: Text.Wrap; maximumLineCount: 2
                    }
                    Copy {
                        Layout.fillWidth: true; text: player.artist || (player.ready ? "本地音频" : "在“更多”中搜索或导入音乐")
                        color: root.muted; font.pixelSize: 13
                    }
                    TrackSlider {
                        id: progress; objectName: "progress"; Layout.fillWidth: true
                        from: 0; to: Math.max(1,player.duration); enabled: player.seekable; Accessible.name: "播放进度"
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
                        Layout.fillWidth: true; spacing: 12
                        Item { Layout.fillWidth: true }
                        Action { objectName: "previousButton"; glyph: "previous"; quiet: true; Accessible.name: "上一首"; ToolTip.text: "上一首"; enabled: !player.busy&&player.tracks.length>0; onClicked: player.previous() }
                        Action { objectName: "playPauseButton"; glyph: player.playing ? "pause" : "play"; primary: true; implicitWidth: 72; implicitHeight: 48; Accessible.name: player.playing ? "暂停" : "播放"; ToolTip.text: Accessible.name; enabled: player.ready&&!player.busy; onClicked: player.toggle() }
                        Action { objectName: "nextButton"; glyph: "next"; quiet: true; Accessible.name: "下一首"; ToolTip.text: "下一首"; enabled: !player.busy&&player.tracks.length>0; onClicked: player.next() }
                        Action {
                            id: modeButton; objectName: "playbackModeButton"; glyph: player.playbackMode; quiet: true
                            Accessible.name: "播放模式：" + root.playbackModeName
                            ToolTip.text: Accessible.name
                            onClicked: playbackMenu.opened ? playbackMenu.close() : playbackMenu.open()
                        }
                        Item { Layout.fillWidth: true }
                    }
                    RowLayout {
                        Layout.fillWidth: true; spacing: 8
                        Glyph { kind: "volume"; tint: root.muted }
                        TrackSlider {
                            id: volumeSlider; objectName: "volumeSlider"; Layout.fillWidth: true
                            from: 0; to: 100; stepSize: 1; Accessible.name: "音量"
                            onMoved: player.setVolume(Math.round(value))
                            Binding { target: volumeSlider; property: "value"; value: player.volume; when: !volumeSlider.pressed }
                        }
                        Copy { text: player.volume+"%"; color: root.muted; font.pixelSize: 12; Layout.preferredWidth: 36; horizontalAlignment: Text.AlignRight }
                    }
                    Hint { visible: player.busy; text: player.status }
                    RowLayout {
                        visible: player.error.length>0; Layout.fillWidth: true
                        Hint { objectName: "playbackError"; text: player.error; color: root.danger; maximumLineCount: 4; elide: Text.ElideRight }
                        Action { objectName: "retryPlayback"; text: "重试"; enabled: !player.busy; onClicked: player.retryPlayback() }
                    }
                    RowLayout {
                        Layout.fillWidth: true; spacing: 8
                        SectionTab { objectName: "lyricsTab"; text: "歌词"; sectionName: "lyrics" }
                        SectionTab { objectName: "playlistTab"; text: "歌单"; sectionName: "playlist" }
                        SectionTab { objectName: "moreTab"; text: "更多"; sectionName: "more" }
                    }
                }
                Rectangle { visible: root.section.length>0; Layout.fillWidth: true; implicitHeight: 1; color: root.line }
                Item {
                    id: drawer; objectName: "sharedDrawer"
                    visible: root.section.length>0
                    Layout.fillWidth: true
                    Layout.preferredHeight: visible ? (root.section==="more" && root.detail==="" ? moreMenu.implicitHeight+32 : Math.max(180, Math.min(350, (workArea.height - 16) / contentScale - playerCard.implicitHeight - 48))) : 0
                    StackLayout {
                        anchors.fill: parent; anchors.margins: 16
                        currentIndex: root.section==="lyrics" ? 0 : root.section==="playlist" ? 1 : 2
                        ColumnLayout {
                            spacing: 12
                            RowLayout {
                                Layout.fillWidth: true
                                Choice { id: lyricMode; Layout.fillWidth: true; implicitWidth: 150; model: ["原文", "译文", "原文与译文"]; Accessible.name: "歌词显示方式"; onCurrentIndexChanged: Qt.callLater(lyricView.followCurrent) }
                                Action { objectName: "retryLyrics"; text: "刷新"; quiet: true; Accessible.name: "重新获取歌词"; enabled: player.online&&!player.lyricsLoading; onClicked: player.retryLyrics() }
                                Action { id: timingButton; glyph: "settings"; quiet: true; enabled: player.lyricLines.length > 0; Accessible.name: "歌词时间微调"; ToolTip.text: Accessible.name; onClicked: lyricTiming.open() }
                            }
                            Hint { text: player.lyricsMessage; color: player.lyricsFailed ? root.danger : root.muted }
                            Item {
                                Layout.fillWidth: true; Layout.fillHeight: true
                                ListView {
                                    id: lyricView; objectName: "timedLyrics"
                                    anchors.fill: parent; clip: true
                                    visible: count > 0
                                    model: player.lyricLines
                                    currentIndex: player.currentLyricIndex
                                    property bool manualBrowsing: false
                                    // The current item remains instantiated even when scrolled out of view.
                                    readonly property bool currentAbove: currentItem ? currentItem.y + currentItem.height / 2 < contentY + height / 2 : currentIndex < 0
                                    highlightFollowsCurrentItem: false
                                    boundsBehavior: Flickable.StopAtBounds
                                    spacing: 10; cacheBuffer: height
                                    header: Item { width: 1; height: lyricView.height * 0.3 }
                                    footer: Item { width: 1; height: lyricView.height * 0.5 }
                                    function followCurrent() {
                                        if (!manualBrowsing && visible && count > 0) {
                                            // Apply freshly delivered delegates before computing their positions.
                                            forceLayout()
                                            positionViewAtIndex(Math.max(0, player.currentLyricIndex), ListView.Center)
                                        }
                                    }
                                    function returnToCurrent() { cancelFlick(); manualBrowsing = false; followCurrent() }
                                    onCurrentIndexChanged: Qt.callLater(followCurrent)
                                    onModelChanged: Qt.callLater(followCurrent)
                                    onCountChanged: Qt.callLater(followCurrent)
                                    onHeightChanged: Qt.callLater(followCurrent)
                                    onVisibleChanged: if (visible) Qt.callLater(followCurrent)
                                    onDraggingChanged: if (dragging) manualBrowsing = true
                                    Keys.onPressed: function(event) {
                                        if (event.key === Qt.Key_Up || event.key === Qt.Key_Down || event.key === Qt.Key_PageUp || event.key === Qt.Key_PageDown) {
                                            manualBrowsing = true
                                            var delta = event.key === Qt.Key_Up ? -48 : event.key === Qt.Key_Down ? 48 : event.key === Qt.Key_PageUp ? -height : height
                                            contentY = Math.max(originY, Math.min(originY + Math.max(0, contentHeight - height), contentY + delta))
                                            event.accepted = true
                                        }
                                    }
                                    activeFocusOnTab: true
                                    Accessible.name: "逐句歌词，使用上下方向键翻看"
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
                                        height: Math.max(36, lyricWords.implicitHeight + 16)
                                        Rectangle { anchors.left: parent.left; anchors.verticalCenter: parent.verticalCenter; width: 3; height: 20; radius: 1.5; color: root.accent; visible: lyricRow.active }
                                        Column {
                                            id: lyricWords; anchors.left: parent.left; anchors.right: parent.right; anchors.margins: 14; anchors.verticalCenter: parent.verticalCenter; spacing: 6
                                            Text {
                                                width: parent.width; textFormat: Text.PlainText; wrapMode: Text.Wrap
                                                text: lyricMode.currentIndex === 1 ? (lyricRow.modelData.translation || (lyricRow.modelData.original ? "暂无该句译文" : "♪")) : (lyricRow.modelData.original || "♪")
                                                font.family: root.font.family; font.pixelSize: 20; font.weight: lyricRow.active ? Font.DemiBold : Font.Normal
                                                // Stable line layout; enlarging the active line does not move adjacent rows.
                                                scale: lyricRow.active ? 1 : 0.9; transformOrigin: Item.Left
                                                color: lyricRow.active ? root.accent : root.muted
                                            }
                                            Text {
                                                width: parent.width; visible: lyricMode.currentIndex === 2 && text.length > 0
                                                text: lyricRow.modelData.translation; textFormat: Text.PlainText; wrapMode: Text.Wrap
                                                font.family: root.font.family; font.pixelSize: 14; color: lyricRow.active ? root.ink : root.muted
                                            }
                                        }
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
                                }
                                ScrollView {
                                    anchors.fill: parent; visible: player.lyricLines.length === 0; clip: true; contentWidth: availableWidth
                                    TextArea {
                                        objectName: "lyricsText"; readOnly: true; selectByMouse: true; wrapMode: TextEdit.Wrap
                                        textFormat: TextEdit.PlainText; font.pixelSize: 18; color: root.ink
                                        selectionColor: root.accent; selectedTextColor: root.accentInk; background: null; padding: 8
                                        text: lyricMode.currentIndex===0 ? root.lyricText(player.lyrics) : lyricMode.currentIndex===1 ? (root.lyricText(player.translation)||"暂无译文") : root.lyricText(player.lyrics)+(player.translation.length>0 ? "\n\n—— 译文 ——\n\n"+root.lyricText(player.translation) : "")
                                    }
                                }
                                Action {
                                    objectName: "returnToCurrentLyric"
                                    anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: 8
                                    visible: lyricView.visible && lyricView.manualBrowsing && player.currentLyricIndex >= 0
                                    primary: true; glyph: lyricView.currentAbove ? "up" : "down"
                                    Accessible.name: "回到当前歌词并恢复跟随"; ToolTip.text: Accessible.name
                                    onClicked: lyricView.returnToCurrent()
                                }
                            }
                        }
                        ColumnLayout {
                            spacing: 8
                            Choice {
                                id: lists; objectName: "playlistSelector"; Layout.fillWidth: true
                                model: player.playlists; textRole: "name"; valueRole: "id"; Accessible.name: "当前歌单"
                                function updateSelection() { currentIndex=indexOfValue(player.activePlaylist) }
                                Component.onCompleted: updateSelection()
                                onModelChanged: Qt.callLater(updateSelection)
                                onActivated: player.selectPlaylist(currentValue)
                            }
                            RowLayout {
                                Layout.fillWidth: true; spacing: 8
                                Action { text: "新建"; Layout.fillWidth: true; onClicked: {playlistDialog.renaming=false;playlistName.text="";playlistDialog.open()} }
                                Action { text: "改名"; Layout.fillWidth: true; onClicked: {playlistDialog.renaming=true;playlistName.text=lists.currentText;playlistDialog.open()} }
                                Action { text: "删除"; Layout.fillWidth: true; enabled: player.playlists.length>1; onClicked: deleteDialog.open() }
                            }
                            Field { id: filter; Layout.fillWidth: true; placeholderText: "筛选歌名或歌手"; Accessible.name: "筛选当前歌单" }
                            ListView {
                                id: songs; objectName: "playlistView"; Layout.fillWidth: true; Layout.fillHeight: true; clip: true
                                model: player.tracks.filter(function(t){return (t.name+" "+(t.artist||"")).toLowerCase().indexOf(filter.text.trim().toLowerCase())>=0})
                                ScrollBar.vertical: ScrollBar {}
                                delegate: SongRow {
                                    required property var modelData
                                    track: modelData; removable: true
                                    onPlayRequested: player.playTrack(track.id)
                                    onRemoveRequested: player.removeTrack(track.id)
                                }
                                Hint { anchors.centerIn: parent; width: parent.width; horizontalAlignment: Text.AlignHCenter; visible: songs.count===0; text: "暂无歌曲，去“更多”搜索或导入" }
                            }
                            Hint { text: player.tracks.length+" 首 · " + root.playbackModeName }
                        }
                        ColumnLayout {
                            spacing: 10
                            RowLayout {
                                visible: root.detail.length>0; Layout.fillWidth: true
                                Action { glyph: "back"; text: "更多"; quiet: true; onClicked: root.detail="" }
                                Item { Layout.fillWidth: true }
                                Copy { text: root.detail==="search" ? "搜索音乐" : root.detail==="favorites" ? "我的收藏" : "设置"; font.weight: Font.DemiBold }
                            }
                            StackLayout {
                                Layout.fillWidth: true; Layout.fillHeight: true
                                currentIndex: root.detail==="search" ? 1 : root.detail==="favorites" ? 2 : root.detail==="settings" ? 3 : 0
                                ColumnLayout {
                                    id: moreMenu; spacing: 12
                                    GridLayout {
                                        Layout.fillWidth: true; columns: 2; columnSpacing: 8; rowSpacing: 8
                                        Action { text: "搜索音乐"; glyph: "search"; Layout.fillWidth: true; onClicked: root.openDetail("search") }
                                        Action { text: "我的收藏"; glyph: "heart"; Layout.fillWidth: true; onClicked: root.openDetail("favorites") }
                                        Action { text: "导入音乐"; glyph: "folder"; Layout.fillWidth: true; enabled: !player.busy; onClicked: root.chooseMusic() }
                                        Action { text: "设置"; glyph: "settings"; Layout.fillWidth: true; onClicked: root.openDetail("settings") }
                                        Action { text: player.currentFavorite ? "取消收藏" : "收藏当前"; glyph: "heart"; Layout.fillWidth: true; enabled: player.ready; onClicked: player.toggleFavorite() }
                                        Action { objectName: "exitButton"; text: "退出浮音"; glyph: "close"; Layout.fillWidth: true; Accessible.name: "退出并停止播放"; onClicked: root.quitApp() }
                                    }
                                    Hint { text: player.favoriteMessage; visible: text.length>0 }
                                    Hint { text: "也可将音频拖入窗口或图标\nMP3 / WAV / FLAC / OGG / M4A / AAC · 单首 ≤ 30 MiB" }
                                }
                                ColumnLayout {
                                    spacing: 8
                                    RowLayout {
                                        Layout.fillWidth: true; spacing: 8
                                        Field { id: keywords; objectName: "searchInput"; Layout.fillWidth: true; placeholderText: "输入歌名"; Accessible.name: "按歌名搜索音乐"; onAccepted: player.search(text) }
                                        Action { text: player.searching ? "搜索中" : "搜索"; primary: true; enabled: !player.searching; onClicked: player.search(keywords.text) }
                                    }
                                    Hint { text: player.searchMessage; visible: text.length>0 }
                                    ListView {
                                        id: results; objectName: "searchResults"; Layout.fillWidth: true; Layout.fillHeight: true; clip: true
                                        model: player.searchResults; ScrollBar.vertical: ScrollBar {}
                                        delegate: SongRow {
                                            required property var modelData; required property int index
                                            track: modelData; extraText: "+"
                                            onPlayRequested: player.playSearchResult(index)
                                            onExtraRequested: player.addSearchResult(index)
                                        }
                                        Hint { anchors.centerIn: parent; width: parent.width; horizontalAlignment: Text.AlignHCenter; visible: results.count===0; text: player.searching ? "正在查找歌曲…" : "输入歌名，找到想听的音乐" }
                                    }
                                }
                                ColumnLayout {
                                    spacing: 8
                                    Hint { text: player.favoriteMessage; visible: text.length>0 }
                                    ListView {
                                        id: favoriteList; objectName: "favoritesView"; Layout.fillWidth: true; Layout.fillHeight: true; clip: true
                                        model: player.favorites; ScrollBar.vertical: ScrollBar {}
                                        delegate: SongRow {
                                            required property var modelData
                                            track: modelData; extraText: "+"; removable: true
                                            onPlayRequested: player.playFavorite(track.id)
                                            onExtraRequested: player.addFavorite(track.id)
                                            onRemoveRequested: player.removeFavorite(track.id)
                                        }
                                        Hint { anchors.centerIn: parent; width: parent.width; horizontalAlignment: Text.AlignHCenter; visible: favoriteList.count===0; text: "还没有收藏\n播放歌曲后，在更多中点击“收藏当前”" }
                                    }
                                }
                                ScrollView {
                                    id: settingsScroll; clip: true; contentWidth: availableWidth
                                    ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                                    ColumnLayout {
                                        width: settingsScroll.availableWidth; spacing: 12
                                        Copy { text: "外观"; font.weight: Font.DemiBold }
                                        Choice {
                                            objectName: "themeSelector"; Layout.fillWidth: true; Accessible.name: "界面主题"
                                            model: ["跟随系统", "浅色", "深色"]; currentIndex: appearance.mode
                                            onActivated: function(index) {appearance.mode=index;appearance.sync()}
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: "整体大小"; Layout.fillWidth: true }
                                            Copy { text: Math.round(root.contentScale*100)+"%"; color: root.muted }
                                            Action { text: "重置"; quiet: true; onClicked: {appearance.windowScale=1;appearance.backgroundOpacity=1} }
                                        }
                                        TrackSlider {
                                            id: sizeSlider; objectName: "windowScaleSlider"; Layout.fillWidth: true
                                            from: 90; to: 140; stepSize: 5; Accessible.name: "悬浮窗整体缩放"
                                            onMoved: appearance.windowScale=value/100
                                            Binding { target: sizeSlider; property: "value"; value: appearance.windowScale*100; when: !sizeSlider.pressed }
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: "背景不透明度"; Layout.fillWidth: true }
                                            Copy { text: Math.round(appearance.backgroundOpacity*100)+"%"; color: root.muted }
                                        }
                                        TrackSlider {
                                            id: opacitySlider; objectName: "backgroundOpacitySlider"; Layout.fillWidth: true
                                            from: 20; to: 100; stepSize: 5; Accessible.name: "背景不透明度"
                                            onMoved: appearance.backgroundOpacity=value/100
                                            Binding { target: opacitySlider; property: "value"; value: appearance.backgroundOpacity*100; when: !opacitySlider.pressed }
                                        }
                                        Hint { text: "可拖动右下角等比缩放；透明度只影响背景。" }
                                        Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: root.line }
                                        Copy { text: "在线音质"; font.weight: Font.DemiBold }
                                        Choice {
                                            id: qualitySelector; objectName: "qualitySelector"; Layout.fillWidth: true; enabled: !player.busy; Accessible.name: "在线音质"
                                            model: ["标准", "较高", "极高", "无损 FLAC", "Hi-Res"]
                                            property var levels: ["standard","higher","exhigh","lossless","hires"]
                                            currentIndex: levels.indexOf(player.quality)
                                            onActivated: function(index) {player.setQuality(levels[index])}
                                            Connections { target: player; function onChanged(){qualitySelector.currentIndex=qualitySelector.levels.indexOf(player.quality)} }
                                        }
                                        Hint { text: player.qualityInfo + (player.quality==="hires" ? "\nHi-Res 为请求档位，实际音源可能回落。" : "") }
                                        RowLayout {
                                            Layout.fillWidth: true
                                            Copy { text: "音频输出"; font.weight: Font.DemiBold; Layout.fillWidth: true }
                                            Action { text: "刷新"; quiet: true; onClicked: player.refreshOutputs() }
                                        }
                                        Choice {
                                            id: outputs; objectName: "outputSelector"; Layout.fillWidth: true
                                            model: player.audioOutputs; textRole: "name"; valueRole: "id"; Accessible.name: "音频输出设备"
                                            function syncSelection(){currentIndex=indexOfValue(player.selectedOutput)}
                                            Component.onCompleted: syncSelection()
                                            onModelChanged: Qt.callLater(syncSelection)
                                            onActivated: player.selectOutput(currentValue)
                                            Connections { target: player; function onAudioSettingsChanged(){outputs.syncSelection()} }
                                        }
                                        Hint { text: "当前："+player.outputName }
                                        Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: root.line }
                                        Copy { text: "音乐服务"; font.weight: Font.DemiBold }
                                        Hint { text: player.apiBase.length===0 ? "正在使用内置网易云接口，可直接搜索。" : "正在使用自定义音乐服务。" }
                                        Field {
                                            id: apiAddress; objectName: "apiAddress"; Layout.fillWidth: true
                                            text: player.apiBase; placeholderText: "兼容 API 地址（可选）"; Accessible.name: "自定义音乐服务地址"
                                        }
                                        RowLayout {
                                            Layout.fillWidth: true; spacing: 8
                                            Action { text: "保存地址"; primary: true; Layout.fillWidth: true; enabled: !player.busy; onClicked: player.setApiBase(apiAddress.text) }
                                            Action { text: "恢复内置"; Layout.fillWidth: true; enabled: !player.busy; onClicked: {player.setApiBase("");apiAddress.text=""} }
                                        }
                                        Hint { text: player.searchMessage; visible: text.length>0 }
                                        Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: root.line }
                                        Copy { text: "浮音 0.5 · Windows"; font.weight: Font.DemiBold }
                                        Hint { text: "拖动顶部移动窗口，减号收为图标。\n更多中的“退出浮音”会停止播放并退出。\nCtrl+F 搜索 · Ctrl+O 导入 · Esc 返回或收起" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Dialog {
        id: playlistDialog; parent: Overlay.overlay
        property bool renaming: false
        width: 330; scale: root.contentScale; anchors.centerIn: parent
        title: renaming ? "重命名歌单" : "新建歌单"; modal: true; focus: true
        standardButtons: Dialog.Ok|Dialog.Cancel
        background: Rectangle { color: root.elevated; radius: 16; border.color: root.fieldBorder }
        Field { id: playlistName; width: parent.width; placeholderText: "歌单名称（1–60 字）"; maximumLength: 60; Accessible.name: "歌单名称"; onAccepted: playlistDialog.accept() }
        onOpened: playlistName.forceActiveFocus()
        onAccepted: {if(renaming)player.renamePlaylist(playlistName.text);else player.createPlaylist(playlistName.text)}
    }
    Dialog {
        id: deleteDialog; parent: Overlay.overlay
        width: 330; scale: root.contentScale; anchors.centerIn: parent
        title: "删除歌单？"; modal: true; focus: true; standardButtons: Dialog.Ok|Dialog.Cancel
        background: Rectangle { color: root.elevated; radius: 16; border.color: root.fieldBorder }
        Label { width: parent.width; text: "删除“"+lists.currentText+"”，保留本地音频副本。"; wrapMode: Text.Wrap; textFormat: Text.PlainText; color: root.ink }
        onAccepted: player.deletePlaylist()
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
