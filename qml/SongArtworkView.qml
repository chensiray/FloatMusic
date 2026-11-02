import QtQuick
Rectangle {
    id: art
    required property var artwork
    required property var track
    property color placeholderColor: "#efebf8"
    property color lineColor: "#68718d"
    implicitWidth: 40; implicitHeight: 40; radius: 8; color: placeholderColor
    objectName: "songCover-" + (track.id || "")
    visible: !artwork || artwork.enabled
    readonly property string url: { if(!artwork || !track)return ""; var revision=artwork.revision; return artwork.cover(track) }
    function requestCover() { if (visible && artwork && track) artwork.request(track) }
    Component.onCompleted: requestCover()
    onTrackChanged: requestCover()
    onVisibleChanged: requestCover()
    Rectangle {
        anchors.centerIn: parent; width: parent.width*.58; height: width; radius: width/2
        color: "transparent"; border.color: art.lineColor; opacity: .45
        Rectangle { anchors.centerIn: parent; width: parent.width*.24; height: width; radius: width/2; color: "transparent"; border.color: art.lineColor }
    }
    Image { anchors.fill: parent; source: art.url; sourceSize: Qt.size(128,128); asynchronous: true; fillMode: Image.PreserveAspectFit; visible: status === Image.Ready }
}
