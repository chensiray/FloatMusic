import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs

ApplicationWindow {
    id: root
    visible: player.android
    width: 420; height: 620
    minimumWidth: 350; minimumHeight: 560
    title: "浮音 · 本地音乐"
    color: "#11151d"
    flags: player.android ? Qt.Window : Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
    font.family: Qt.platform.os === "windows" ? "Microsoft YaHei UI" : "sans-serif"
    font.pixelSize: 14
    palette.windowText: "#edf2fa"; palette.text: "#edf2fa"; palette.buttonText: "#edf2fa"
    palette.button: "#293142"; palette.highlight: "#98e4c2"
    onClosing: function(close) { if (!player.android) { close.accepted = false; root.hide() } }
    function clock(ms) { var s = Math.floor(ms / 1000); return Math.floor(s / 60) + ":" + (s % 60 < 10 ? "0" : "") + s % 60 }
    function chooseMusic() { if (player.android) player.chooseAndroidFile(); else picker.open() }
    FileDialog {
        id: picker; title: "导入音频 · 最大 30 MiB"
        nameFilters: ["音频文件 (*.mp3 *.wav *.flac *.ogg *.m4a *.aac)"]
        onAccepted: player.importFile(selectedFile)
    }
    Window {
        id: floating; objectName: "floatingIcon"
        transientParent: null
        visible: !player.android && !root.visible
        width: 64; height: 64; color: "transparent"
        flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        Rectangle {
            anchors.fill: parent; anchors.margins: 2; radius: 30; color: "#98e4c2"
            border.color: "#34584c"; border.width: 1
            Text { anchors.centerIn: parent; text: "♪"; font.pixelSize: 34; color: "#182c27" }
            MouseArea {
                anchors.fill: parent; cursorShape: Qt.PointingHandCursor
                property real startX; property real startY; property real originX; property real originY
                property bool moved: false
                onPressed: function(mouse) { originX = floating.x; originY = floating.y; startX = floating.x + mouse.x; startY = floating.y + mouse.y; moved = false }
                onPositionChanged: function(mouse) {
                    if (!pressed) return
                    var dx = floating.x + mouse.x - startX, dy = floating.y + mouse.y - startY
                    if (Math.abs(dx) + Math.abs(dy) > Qt.styleHints.startDragDistance) moved = true
                    if (moved) { floating.x = originX + dx; floating.y = originY + dy }
                }
                onReleased: {
                    if (!moved) {
                        root.x = Math.max(floating.screen.virtualX, Math.min(floating.x, floating.screen.virtualX + floating.screen.width - root.width))
                        root.y = Math.max(floating.screen.virtualY, Math.min(floating.y, floating.screen.virtualY + floating.screen.height - root.height))
                        root.show(); root.requestActivate()
                    }
                }
            }
        }
        DropArea { anchors.fill: parent; onDropped: function(event) { if (event.hasUrls && event.urls.length === 1) { player.importFile(event.urls[0]); root.show(); root.requestActivate(); event.acceptProposedAction() } else player.rejectDrop() } }
    }
    DropArea { id: drop; anchors.fill: parent; onDropped: function(event) { if (event.hasUrls && event.urls.length === 1) { player.importFile(event.urls[0]); event.acceptProposedAction() } else player.rejectDrop() } }
    Rectangle { anchors.fill: parent; color: "transparent"; border.color: drop.containsDrag ? "#98e4c2" : "#303847"; radius: 16 }
    ScrollView {
        anchors.fill: parent; anchors.margins: 20; clip: true
        contentWidth: availableWidth
        ColumnLayout {
            width: parent.width; spacing: 14
            RowLayout {
                Layout.fillWidth: true
                Text { text: "浮音 / FLOAT MUSIC"; color: "#98e4c2"; font.pixelSize: 12; Layout.fillWidth: true; Layout.preferredHeight: 40
                    verticalAlignment: Text.AlignVCenter
                    MouseArea { anchors.fill: parent; onPressed: if (!player.android) root.startSystemMove() }
                }
                ToolButton { text: "收为图标"; onClicked: { if (player.android) player.showFloating(); else root.hide() } }
                ToolButton { text: "×"; Accessible.name: "退出并停止播放"; onClicked: player.quit() }
            }
            Text { text: "你的音乐，轻轻悬浮。"; color: "#edf2fa"; font.pixelSize: 23 }
            Text { Layout.fillWidth: true; text: player.title; color: "#edf2fa"; font.pixelSize: 17; elide: Text.ElideMiddle }
            Text { Layout.fillWidth: true; text: player.status; color: "#97a4b7"; wrapMode: Text.Wrap; font.pixelSize: 12 }
            Slider {
                id: progress; objectName: "progress"; Layout.fillWidth: true
                from: 0; to: Math.max(1, player.duration); enabled: player.seekable
                onPressedChanged: if (!pressed && enabled) player.seek(value)
                onMoved: if (!pressed && enabled) player.seek(value)
                Binding { target: progress; property: "value"; value: player.position; when: !progress.pressed }
            }
            RowLayout { Layout.fillWidth: true
                Text { text: root.clock(progress.pressed ? progress.value : player.position); color: "#97a4b7" }
                Item { Layout.fillWidth: true }
                Text { text: root.clock(player.duration); color: "#97a4b7" }
            }
            RowLayout { Layout.fillWidth: true
                Button { text: player.busy ? "导入中…" : "导入音乐"; enabled: !player.busy; onClicked: root.chooseMusic(); Layout.fillWidth: true }
                Button { text: player.playing ? "暂停" : "播放"; enabled: player.ready; onClicked: player.toggle(); Layout.fillWidth: true }
            }
            Rectangle { Layout.fillWidth: true; height: 1; color: "#303847" }
            RowLayout { Layout.fillWidth: true
                Text { text: "音频输出"; color: "#edf2fa"; Layout.fillWidth: true }
                ToolButton { text: "刷新"; onClicked: player.refreshOutputs() }
            }
            ComboBox {
                id: outputs; objectName: "outputSelector"; Layout.fillWidth: true
                model: player.audioOutputs; textRole: "name"; valueRole: "id"
                function syncSelection() { currentIndex = indexOfValue(player.selectedOutput) }
                Component.onCompleted: syncSelection()
                onModelChanged: Qt.callLater(syncSelection)
                onActivated: player.selectOutput(currentValue)
                Connections { target: player; function onAudioSettingsChanged() { outputs.syncSelection() } }
            }
            Text { Layout.fillWidth: true; text: "当前输出：" + player.outputName; color: "#97a4b7"; wrapMode: Text.Wrap; font.pixelSize: 12 }
            RowLayout { Layout.fillWidth: true
                Text { text: player.volume === 0 ? "静音" : "音量"; color: "#edf2fa" }
                Slider { id: volumeSlider; objectName: "volumeSlider"; Layout.fillWidth: true; from: 0; to: 100; stepSize: 1
                    onMoved: player.setVolume(Math.round(value))
                    Binding { target: volumeSlider; property: "value"; value: player.volume; when: !volumeSlider.pressed }
                }
                Text { text: player.volume + "%"; color: "#edf2fa"; Layout.preferredWidth: 45 }
            }
            Text { Layout.fillWidth: true; text: player.error; visible: text.length > 0; color: "#ffb5aa"; wrapMode: Text.Wrap; font.pixelSize: 12 }
            Text { Layout.fillWidth: true; text: "MP3 / WAV / FLAC / OGG / M4A / AAC\n单首 ≤ 30 MiB · 仅存本机"; color: "#617086"; font.pixelSize: 12 }
        }
    }
}
