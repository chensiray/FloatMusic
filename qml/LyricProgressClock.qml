import QtQuick

QtObject {
    id: clock
    property var timeline: []
    property real position: 0
    property real duration: 0
    property int offset: 0
    property bool playing: false
    property bool enabled: false
    property int activeIndex: -1
    property real sampledPosition: position
    property real anchorPosition: position
    property real anchoredAt: Date.now()
    property bool anchorPlaying: false
    readonly property bool ticking: ticker.running
    function sample() {
        var value = anchorPosition + (anchorPlaying ? Math.max(0, Date.now() - anchoredAt) : 0)
        sampledPosition = duration > 0 ? Math.min(duration, value) : value
    }
    function rebase(preserve) {
        if (preserve) sample()
        anchorPosition = preserve ? sampledPosition : Math.max(0, position)
        anchoredAt = Date.now()
        anchorPlaying = playing
        sample()
    }
    function progressFor(index) {
        if (index < 0 || index >= timeline.length) return 0
        var start = Number(timeline[index].time)
        var end = index + 1 < timeline.length ? Number(timeline[index + 1].time) : duration
        if (!isFinite(start) || !isFinite(end) || end <= start) return 1
        return Math.max(0, Math.min(1, (sampledPosition + offset - start) / (end - start)))
    }
    onPositionChanged: rebase(false)
    onPlayingChanged: rebase(true)
    onEnabledChanged: sample()
    onDurationChanged: sample()
    property Timer ticker: Timer { interval: 16; repeat: true; running: clock.enabled && clock.playing && clock.activeIndex >= 0 && clock.progressFor(clock.activeIndex) < 1; onTriggered: clock.sample() }
}
