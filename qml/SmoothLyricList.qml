import QtQuick

ListView {
    id: view
    property bool motionEnabled: true
    property bool manualBrowsing: false
    property real centerOffset: 0
    readonly property bool followingAnimation: scrollMotion.running
    highlightFollowsCurrentItem: false
    boundsBehavior: Flickable.StopAtBounds
    clip: true
    cacheBuffer: height * 2
    function followCurrent() {
        if (manualBrowsing || !visible || count <= 0 || currentIndex < 0 || height <= 0) return
        forceLayout()
        // positionViewAtIndex instantiates distant delegates after seeking. Restore
        // the old viewport before animating, so the jump is never rendered.
        var previous = contentY
        positionViewAtIndex(currentIndex, ListView.Center)
        forceLayout()
        // Compute from the actual delegate. The built-in centering operation
        // clamps at the first/last row, so subtracting an offset from its result
        // would displace the last lyric in an even-sized desktop viewport.
        var target = currentItem ? currentItem.y + currentItem.height / 2 - height / 2 - centerOffset : contentY
        target = Math.max(originY, Math.min(originY + Math.max(0, contentHeight - height), target))
        contentY = previous
        if (Math.abs(target - previous) < 0.5) { scrollMotion.stop(); contentY = target; return }
        if (scrollMotion.running && Math.abs(scrollMotion.to - target) < 0.5) return
        scrollMotion.stop()
        if (!motionEnabled) { contentY = target; return }
        scrollMotion.from = previous
        scrollMotion.to = target
        scrollMotion.start()
    }
    function returnToCurrent() { cancelFlick(); manualBrowsing = false; followCurrent() }
    onCurrentIndexChanged: Qt.callLater(followCurrent)
    onModelChanged: { scrollMotion.stop(); Qt.callLater(followCurrent) }
    onCountChanged: Qt.callLater(followCurrent)
    onHeightChanged: Qt.callLater(followCurrent)
    onVisibleChanged: { if (visible) Qt.callLater(followCurrent); else scrollMotion.stop() }
    onDraggingChanged: if (dragging) manualBrowsing = true
    onManualBrowsingChanged: if (manualBrowsing) scrollMotion.stop()
    onMotionEnabledChanged: if (!motionEnabled) { scrollMotion.stop(); followCurrent() }
    NumberAnimation {
        id: scrollMotion; objectName: "lyricScrollAnimation"
        target: view; property: "contentY"; duration: 360; easing.type: Easing.OutCubic
    }
}
