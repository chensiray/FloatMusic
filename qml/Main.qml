import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs

ApplicationWindow {
    id: root
    property bool quitting: false
    visible: player.android
    width: 540; height: 820
    minimumWidth: 380; minimumHeight: 560
    title: "浮音 0.3 · 音乐与歌单"
    color: "#11151d"
    flags: player.android ? Qt.Window : Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
    font.family: Qt.platform.os === "windows" ? "Microsoft YaHei UI" : "sans-serif"
    font.pixelSize: 14
    palette.windowText: "#edf2fa"; palette.text: "#edf2fa"; palette.buttonText: "#edf2fa"
    palette.button: "#293142"; palette.highlight: "#98e4c2"
    palette.base: "#1c2330"; palette.light: "#34584c"; palette.dark: "#1c2330"
    palette.highlightedText: "#182c27"; palette.placeholderText: "#97a4b7"
    onClosing: function(close) { quitting = true; close.accepted = true; player.quit() }
    function activateWindow() { root.showNormal(); root.raise(); root.requestActivate() }
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
        visible: !player.android && !root.visible && !root.quitting
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
                ToolButton { objectName: "exitButton"; text: "退出应用"; Accessible.name: "退出并停止播放"; onClicked: player.quit() }
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
                Button { objectName: "previousButton"; text: "上一首"; enabled: !player.busy && player.tracks.length > 0; onClicked: player.previous() }
                Button { text: player.playing ? "暂停" : "播放"; enabled: player.ready; onClicked: player.toggle(); Layout.fillWidth: true }
                Button { objectName: "nextButton"; text: "下一首"; enabled: !player.busy && player.tracks.length > 0; onClicked: player.next() }
            }
            TabBar { id: tabs; Layout.fillWidth: true
                TabButton { id: libraryTab; text: "我的歌单"
                    contentItem: Text { text: libraryTab.text; color: libraryTab.checked ? "#182c27" : "#edf2fa"; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                    background: Rectangle { implicitHeight: 40; color: libraryTab.checked ? "#98e4c2" : "#293142"; radius: 4 }
                }
                TabButton { id: searchTab; text: "网易云搜索"
                    contentItem: Text { text: searchTab.text; color: searchTab.checked ? "#182c27" : "#edf2fa"; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                    background: Rectangle { implicitHeight: 40; color: searchTab.checked ? "#98e4c2" : "#293142"; radius: 4 }
                }
            }
            ColumnLayout { visible: tabs.currentIndex === 0; Layout.fillWidth: true
                RowLayout { Layout.fillWidth: true
                    ComboBox { id: lists; objectName: "playlistSelector"; Layout.fillWidth: true
                        model: player.playlists; textRole: "name"; valueRole: "id"
                        function updateSelection() { currentIndex = indexOfValue(player.activePlaylist) }
                        Component.onCompleted: updateSelection()
                        onModelChanged: Qt.callLater(updateSelection)
                        onActivated: player.selectPlaylist(currentValue)
                    }
                    Button { text: "新建"; onClicked: { playlistDialog.renaming = false; playlistName.text = ""; playlistDialog.open() } }
                    Button { text: "改名"; onClicked: { playlistDialog.renaming = true; playlistName.text = lists.currentText; playlistDialog.open() } }
                    Button { text: "删除"; enabled: player.playlists.length > 1; onClicked: deleteDialog.open() }
                }
                TextField { id: filter; Layout.fillWidth: true; placeholderText: "筛选当前歌单的歌名或歌手" }
                ListView { id: songs; objectName: "playlistView"; Layout.fillWidth: true; Layout.preferredHeight: 180; clip: true
                    model: player.tracks; spacing: 4; ScrollBar.vertical: ScrollBar {}
                    delegate: Rectangle {
                        required property var modelData
                        width: songs.width; height: visible ? 52 : 0
                        visible: (modelData.name + " " + (modelData.artist || "")).toLowerCase().indexOf(filter.text.trim().toLowerCase()) >= 0
                        radius: 6; color: player.currentTrack === modelData.id ? "#29463e" : "#1c2330"
                        RowLayout { anchors.fill: parent; anchors.margins: 6
                            ColumnLayout { Layout.fillWidth: true; spacing: 0
                                Text { Layout.fillWidth: true; text: modelData.name; color: "#edf2fa"; elide: Text.ElideRight }
                                Text { Layout.fillWidth: true; text: modelData.artist || "本地文件"; color: "#97a4b7"; font.pixelSize: 11; elide: Text.ElideRight }
                            }
                            Button { text: "播放"; enabled: !player.busy; onClicked: player.playTrack(modelData.id) }
                            ToolButton { text: "移除"; onClicked: player.removeTrack(modelData.id) }
                        }
                    }
                    Text { anchors.centerIn: parent; visible: player.tracks.length === 0; text: "导入音乐，或把搜索结果加入歌单"; color: "#97a4b7" }
                }
                Text { text: player.tracks.length + " 首 · 手动切歌循环，自动播放到歌单末尾停止"; color: "#97a4b7"; font.pixelSize: 11 }
            }
            ColumnLayout { visible: tabs.currentIndex === 1; Layout.fillWidth: true
                RowLayout { Layout.fillWidth: true
                    TextField { id: apiAddress; objectName: "apiAddress"; Layout.fillWidth: true; text: player.apiBase; placeholderText: "网易云兼容 API 地址，例如 https://你的服务"; selectByMouse: true }
                    Button { text: "保存地址"; onClicked: player.setApiBase(apiAddress.text) }
                }
                RowLayout { Layout.fillWidth: true
                    TextField { id: keywords; objectName: "searchInput"; Layout.fillWidth: true; placeholderText: "输入歌名或歌手"; onAccepted: player.search(text) }
                    Button { text: player.searching ? "搜索中…" : "搜索"; onClicked: player.search(keywords.text) }
                }
                Text { Layout.fillWidth: true; text: player.searchMessage; color: "#97a4b7"; wrapMode: Text.Wrap; font.pixelSize: 12 }
                ListView { id: results; objectName: "searchResults"; Layout.fillWidth: true; Layout.preferredHeight: 180; clip: true
                    model: player.searchResults; spacing: 4; ScrollBar.vertical: ScrollBar {}
                    delegate: Rectangle { required property var modelData; required property int index
                        width: results.width; height: 54; color: "#1c2330"; radius: 6
                        RowLayout { anchors.fill: parent; anchors.margins: 6
                            ColumnLayout { Layout.fillWidth: true; spacing: 0
                                Text { Layout.fillWidth: true; text: modelData.name; elide: Text.ElideRight; color: "#edf2fa" }
                                Text { Layout.fillWidth: true; text: modelData.artist; elide: Text.ElideRight; color: "#97a4b7"; font.pixelSize: 11 }
                            }
                            Button { text: "+ 歌单"; onClicked: player.addSearchResult(index) }
                        }
                    }
                }
                Text { Layout.fillWidth: true; text: "加入当前歌单：" + lists.currentText + "。播放范围由 API 和账号权限决定。"; color: "#97a4b7"; wrapMode: Text.Wrap; font.pixelSize: 11 }
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
    Dialog { id: playlistDialog; width: 310; property bool renaming: false; anchors.centerIn: parent; title: renaming ? "重命名歌单" : "新建歌单"; modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel
        TextField { id: playlistName; width: 260; placeholderText: "歌单名称（1–60 字）"; maximumLength: 60 }
        onAccepted: { if (renaming) player.renamePlaylist(playlistName.text); else player.createPlaylist(playlistName.text) }
    }
    Dialog { id: deleteDialog; width: 330; anchors.centerIn: parent; title: "删除歌单？"; modal: true; standardButtons: Dialog.Ok | Dialog.Cancel
        Label { text: "删除“" + lists.currentText + "”，保留本地音频副本。" }
        onAccepted: player.deletePlaylist()
    }
}
