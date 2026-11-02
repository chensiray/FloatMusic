import QtQuick

// One unchanged text layout, with identical text clipped over each laid-out line.
// Only the active lyric instantiates the overlay. Clipping never changes wrapping.
Text {
    id: words
    property bool progressEnabled: false
    property real progress: 0
    property color progressColor: color
    property var measuredLines: []
    property var lineBoxes: []
    readonly property real totalLineWidth: lineBoxes.reduce(function(sum, line) { return sum + line.width }, 0)
    textFormat: Text.PlainText
    onLineLaidOut: function(line) {
        if (line.number === 0) measuredLines = []
        measuredLines.push({y:line.y, height:line.height, width:line.implicitWidth})
        if (line.isLast) Qt.callLater(commitLines)
    }
    function commitLines() { lineBoxes = measuredLines.slice() }
    function coveredWidth(index) {
        if (index < 0 || index >= lineBoxes.length) return 0
        var before = 0
        for (var i = 0; i < index; ++i) before += lineBoxes[i].width
        return Math.max(0, Math.min(lineBoxes[index].width, progress * totalLineWidth - before))
    }
    Repeater {
        model: words.progressEnabled ? words.lineBoxes : []
        delegate: Item {
            required property var modelData
            required property int index
            readonly property var bounds: modelData || ({width:0,height:0,y:0})
            readonly property real available: words.width - words.leftPadding - words.rightPadding
            x: words.leftPadding + (words.horizontalAlignment === Text.AlignHCenter ? (available - bounds.width) / 2 : words.horizontalAlignment === Text.AlignRight ? available - bounds.width : 0)
            y: bounds.y + (words.verticalAlignment === Text.AlignVCenter ? (words.height - words.contentHeight) / 2 : words.verticalAlignment === Text.AlignBottom ? words.height - words.contentHeight : 0)
            width: words.coveredWidth(index)
            height: bounds.height
            clip: true
            Text {
                x: -parent.x; y: -parent.y
                width: words.width; height: words.height
                text: words.text; textFormat: Text.PlainText
                font: words.font; fontSizeMode: words.fontSizeMode
                minimumPixelSize: words.minimumPixelSize; minimumPointSize: words.minimumPointSize
                horizontalAlignment: words.horizontalAlignment; verticalAlignment: words.verticalAlignment
                wrapMode: words.wrapMode; lineHeight: words.lineHeight; lineHeightMode: words.lineHeightMode
                leftPadding: words.leftPadding; rightPadding: words.rightPadding; topPadding: words.topPadding; bottomPadding: words.bottomPadding
                style: words.style; styleColor: words.styleColor; renderType: words.renderType
                color: words.progressColor
            }
        }
    }
}
