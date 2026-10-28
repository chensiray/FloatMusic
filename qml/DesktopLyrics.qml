import QtQuick
import QtQuick.Controls

Window {
    id: window
    objectName: "desktopLyricsWindow"
    property var timeline: []
    property int activeIndex: -1
    property int lineCount: 3
    property int textSize: 30
    property int highlightOpacity: 100
    property int edgeOpacity: 35
    property bool motionEnabled: true
    property bool darkStyle: false
    property int themeMode: 0
    property int availableHeight: 10000
    readonly property int fittedTextSize: Math.max(10, Math.min(textSize, Math.floor(rowHeight / 1.75)))
    readonly property color lyricInk: darkStyle ? "#F4F0FC" : "#293047"
    readonly property color lyricHighlight: darkStyle ? "#CDAAFF" : "#703AFF"
    readonly property color lyricOutline: darkStyle ? "#C40C1123" : "#B8FFFFFF"
    property string themeText: ""
    property string followText: ""
    property string lightText: ""
    property string darkText: ""
    property var inputRects: []
    property string emptyText: ""
    property string hideText: ""
    property string settingsText: ""
    property string lockText: ""
    property string recenterText: ""
    property bool locked: false
    signal moved(real left, real top)
    signal hideRequested()
    signal settingsRequested()
    signal lockRequested()
    signal recenterRequested()
    signal themeRequested(int mode)
    transientParent: null
    title: "FloatMusic · Desktop lyrics"
    color: "transparent"
    flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
    width: 760
    height: lineCount * rowHeight
    readonly property int rowHeight: Math.max(20, Math.min(Math.ceil(textSize * 1.75), Math.floor(availableHeight / lineCount)))
    onClosing: function(event) { event.accepted = false; hideRequested() }
    onWidthChanged: Qt.callLater(lines.followCurrent)
    function fadeAt(distance) {
        var peak = Math.max(10, Math.min(100, highlightOpacity)) / 100
        var edge = Math.max(0, Math.min(peak, edgeOpacity / 100))
        var outer = Math.floor(lineCount / 2), at = Math.abs(distance)
        if (outer === 0) return peak * Math.max(0, 1 - at)
        if (at <= outer) return peak + (edge - peak) * at / outer
        return edge * Math.max(0, 1 - (at - outer))
    }
    function scheduleInputUpdate() { if (visible && !inputUpdate.running) inputUpdate.start() }
    function textRect(label) {
        var left = (label.width - label.contentWidth) / 2
        var top = (label.height - label.contentHeight) / 2
        var a = label.mapToItem(window.contentItem, left, top)
        var b = label.mapToItem(window.contentItem, left + label.contentWidth, top + label.contentHeight)
        return Qt.rect(a.x - 2, a.y - 2, b.x - a.x + 4, b.y - a.y + 4)
    }
    function updateInputRects() {
        var rectangles = []
        if (lines.count === 0) {
            if (emptyWords.text.length) rectangles.push(textRect(emptyWords))
        } else {
            var children = lines.contentItem.children
            for (var i = 0; i < children.length; ++i) {
                var label = children[i].label
                if (!label || label.opacity < 0.03 || !label.text.length) continue
                var bounds = textRect(label)
                var left = Math.max(0, bounds.x), top = Math.max(0, bounds.y)
                var right = Math.min(width, bounds.x + bounds.width), bottom = Math.min(height, bounds.y + bounds.height)
                if (right > left && bottom > top) rectangles.push(Qt.rect(left, top, right - left, bottom - top))
            }
        }
        inputRects = rectangles
    }
    function isOverText(x, y) {
        for (var i = 0; i < inputRects.length; ++i) {
            var r = inputRects[i]
            if (x >= r.x && x <= r.x + r.width && y >= r.y && y <= r.y + r.height) return true
        }
        return false
    }
    Timer { id: inputUpdate; interval: 16; onTriggered: window.updateInputRects() }
    Connections {
        target: window
        function onVisibleChanged() { window.scheduleInputUpdate() }
        function onWidthChanged() { window.scheduleInputUpdate() }
        function onHeightChanged() { window.scheduleInputUpdate() }
    }
    SmoothLyricList {
        id: lines; objectName: "desktopLyricLines"
        anchors.fill: parent
        interactive: false
        model: window.timeline
        currentIndex: Math.max(0, window.activeIndex)
        motionEnabled: window.motionEnabled
        header: Item { height: Math.floor(window.lineCount / 2) * window.rowHeight; width: 1 }
        footer: Item { height: Math.floor((window.lineCount - 1) / 2) * window.rowHeight; width: 1 }
        delegate: Item {
            id: lyricRow
            objectName: "desktopLyricRow-" + index
            required property var modelData
            required property int index
            property alias label: words
            readonly property bool isCurrent: index === window.activeIndex
            readonly property real centerDistance: Math.abs(y + height / 2 - lines.contentY - lines.height / 2) / window.rowHeight
            width: lines.width; height: window.rowHeight
            Text {
                id: words
                objectName: "desktopLyricText-" + index
                anchors.centerIn: parent
                width: parent.width / 1.12 - 24; height: parent.height
                text: String(parent.modelData.original || "♪").replace(/\n/g, " · ")
                textFormat: Text.PlainText
                font.family: "Microsoft YaHei UI"
                font.pixelSize: window.fittedTextSize
                font.weight: lyricRow.isCurrent ? Font.DemiBold : Font.Normal
                fontSizeMode: Text.HorizontalFit; minimumPixelSize: 10
                horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                color: lyricRow.isCurrent ? window.lyricHighlight : window.lyricInk
                opacity: window.fadeAt(lyricRow.centerDistance)
                property real currentBoost: lyricRow.isCurrent ? 1.12 : 1.0
                scale: currentBoost * Math.max(0.76, 1.0 - lyricRow.centerDistance * 0.055)
                style: Text.Outline; styleColor: window.lyricOutline
                Behavior on currentBoost { enabled: window.motionEnabled; NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                Behavior on color { enabled: window.motionEnabled; ColorAnimation { duration: 180 } }
                onContentWidthChanged: window.scheduleInputUpdate()
                onContentHeightChanged: window.scheduleInputUpdate()
                onScaleChanged: window.scheduleInputUpdate()
                onOpacityChanged: window.scheduleInputUpdate()
            }
            Component.onCompleted: window.scheduleInputUpdate()
        }
        onContentYChanged: window.scheduleInputUpdate()
        Connections { target: lines; function onCountChanged() { window.scheduleInputUpdate() } }
    }
    Text {
        id: emptyWords
        objectName: "desktopLyricEmptyState"
        anchors.fill: parent; anchors.margins: 12
        visible: lines.count === 0
        text: window.emptyText
        textFormat: Text.PlainText
        opacity: window.fadeAt(0)
        font.family: "Microsoft YaHei UI"; font.pixelSize: window.fittedTextSize * 0.7
        wrapMode: Text.Wrap; fontSizeMode: Text.Fit; minimumPixelSize: 12
        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
        color: window.lyricInk; style: Text.Outline; styleColor: window.lyricOutline
        onContentWidthChanged: window.scheduleInputUpdate()
        onContentHeightChanged: window.scheduleInputUpdate()
        onVisibleChanged: window.scheduleInputUpdate()
        onOpacityChanged: window.scheduleInputUpdate()
    }
    MouseArea {
        id: dragArea; objectName: "desktopLyricDragArea"
        anchors.fill: parent; acceptedButtons: Qt.LeftButton | Qt.RightButton
        preventStealing: true
        cursorShape: window.locked ? Qt.ArrowCursor : Qt.SizeAllCursor
        property point pressedAt
        property point startPosition
        property bool moving: false
        onPressed: function(mouse) {
            if (!window.isOverText(mouse.x, mouse.y)) { mouse.accepted = false; return }
            pressedAt = Qt.point(mouse.x, mouse.y)
            startPosition = Qt.point(window.x, window.y)
            moving = mouse.button === Qt.LeftButton && !window.locked
        }
        onPositionChanged: function(mouse) {
            if (!moving) return
            window.x += mouse.x - pressedAt.x
            window.y += mouse.y - pressedAt.y
        }
        onReleased: function(mouse) {
            if (moving) window.moved(window.x, window.y)
            moving = false
            if (mouse.button === Qt.RightButton) contextMenu.popup()
        }
        onCanceled: moving = false
    }
    Menu {
        id: contextMenu
        objectName: "desktopLyricContextMenu"
        popupType: Popup.Window
        Menu {
            title: window.themeText
            MenuItem { text: window.followText; checkable: true; checked: window.themeMode === 0; onTriggered: window.themeRequested(0) }
            MenuItem { text: window.lightText; checkable: true; checked: window.themeMode === 1; onTriggered: window.themeRequested(1) }
            MenuItem { text: window.darkText; checkable: true; checked: window.themeMode === 2; onTriggered: window.themeRequested(2) }
        }
        MenuItem { text: window.lockText; onTriggered: window.lockRequested() }
        MenuItem { text: window.recenterText; onTriggered: window.recenterRequested() }
        MenuItem { text: window.settingsText; onTriggered: window.settingsRequested() }
        MenuItem { text: window.hideText; onTriggered: window.hideRequested() }
    }
}
