import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The activity stays on this page until the user asks to show the overlay.
ApplicationWindow {
    id: welcomeWindow
    visible: true
    width: 390; height: 780
    title: "浮音"
    font.pixelSize: 15
    property int themeMode: player.androidThemeMode()
    readonly property bool dark: themeMode === 2 || (themeMode === 0 && Qt.styleHints.colorScheme === Qt.Dark)
    readonly property color ink: dark ? "#EDF2FA" : "#182338"
    readonly property color muted: dark ? "#ABB8CC" : "#56657A"
    readonly property color accent: dark ? "#83ABFF" : "#2458D3"
    readonly property color accentInk: dark ? "#101722" : "#FFFFFF"
    readonly property color surface: dark ? "#192333" : "#FFFFFF"
    readonly property color line: dark ? "#3A465A" : "#D5DEEB"
    color: dark ? "#101722" : "#F3F5F8"
    Component.onCompleted: player.initializeAndroidUi()
    onClosing: function(event) { event.accepted = false }
    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive)
                welcomeWindow.themeMode = player.androidThemeMode()
        }
    }

    Item {
        anchors.fill: parent
        clip: true
        Rectangle {
            width: Math.min(parent.width * 1.4, 620); height: width; radius: width / 2
            x: parent.width - width * 0.65; y: -width * 0.52
            color: welcomeWindow.dark ? "#172741" : "#E5EDFB"
        }
        Rectangle {
            width: 260; height: width; radius: width / 2
            x: -190; y: parent.height * 0.6
            color: welcomeWindow.dark ? "#152033" : "#E9EEF6"
        }
    }

    ScrollView {
        id: pageScroll
        anchors.fill: parent
        contentWidth: availableWidth
        contentHeight: page.height
        clip: true
        ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
        Item {
            id: page
            width: pageScroll.availableWidth
            height: Math.max(pageScroll.availableHeight, content.implicitHeight + 48)
            ColumnLayout {
                id: content
                width: Math.min(parent.width - 48, 420)
                anchors.horizontalCenter: parent.horizontalCenter
                y: Math.max(24, (page.height - implicitHeight) / 2)
                spacing: 0

                RowLayout {
                    Layout.fillWidth: true
                    Layout.bottomMargin: 28
                    Label { text: "浮音"; font.pixelSize: 20; font.bold: true; color: welcomeWindow.ink }
                    Item { Layout.fillWidth: true }
                    Label { text: "FLOATMUSIC"; font.pixelSize: 11; font.letterSpacing: 2; color: welcomeWindow.muted }
                }

                Item {
                    Layout.alignment: Qt.AlignHCenter
                    Layout.preferredWidth: 200
                    Layout.preferredHeight: pageScroll.availableHeight < 700 ? 112 : 148
                    Layout.bottomMargin: 26
                    Accessible.ignored: true
                    Rectangle {
                        anchors.centerIn: parent
                        width: parent.height; height: width; radius: width / 2
                        color: "transparent"; border.width: 1; border.color: welcomeWindow.line
                    }
                    Rectangle {
                        anchors.centerIn: parent
                        width: parent.height - 22; height: width; radius: width / 2
                        color: welcomeWindow.surface
                        border.width: 1; border.color: welcomeWindow.line
                    }
                    Rectangle {
                        anchors.centerIn: parent
                        width: parent.height - 48; height: width; radius: width / 2
                        color: welcomeWindow.accent
                        Canvas {
                            anchors.centerIn: parent
                            width: 46; height: 46
                            property color ink: welcomeWindow.accentInk
                            onInkChanged: requestPaint()
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = ink; ctx.fillStyle = ink
                                ctx.lineWidth = 3; ctx.lineCap = "round"; ctx.lineJoin = "round"
                                ctx.beginPath()
                                ctx.moveTo(17, 33); ctx.lineTo(17, 12)
                                ctx.lineTo(35, 8); ctx.lineTo(35, 29)
                                ctx.moveTo(17, 19); ctx.lineTo(35, 15); ctx.stroke()
                                ctx.beginPath(); ctx.ellipse(6, 29, 11, 8); ctx.fill()
                                ctx.beginPath(); ctx.ellipse(24, 25, 11, 8); ctx.fill()
                            }
                        }
                    }
                }

                Label {
                    Layout.fillWidth: true
                    text: "让音乐留在手边"
                    color: welcomeWindow.ink
                    font.pixelSize: content.width < 300 ? 25 : 29
                    font.bold: true
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                }
                Label {
                    Layout.fillWidth: true
                    Layout.topMargin: 12; Layout.bottomMargin: 28
                    text: "一个小窗口，陪你听歌、看歌词。\n切换应用，音乐也不必停下。"
                    color: welcomeWindow.muted
                    font.pixelSize: 15; lineHeight: 1.45
                    horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
                }

                Button {
                    id: showOverlay
                    Layout.fillWidth: true
                    implicitHeight: 56
                    text: "显示悬浮窗"
                    Accessible.name: text
                    onClicked: player.showFloating()
                    contentItem: Label {
                        text: showOverlay.text; font.pixelSize: 17; font.bold: true
                        color: welcomeWindow.accentInk
                        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        radius: 16
                        color: showOverlay.down ? Qt.darker(welcomeWindow.accent, 1.15) : welcomeWindow.accent
                        border.width: showOverlay.visualFocus ? 2 : 0
                        border.color: welcomeWindow.ink
                    }
                }
                Label {
                    Layout.fillWidth: true; Layout.topMargin: 12
                    text: player.overlayAllowed ? "已允许悬浮显示 · 点击后收起此页面" : "首次使用，需允许浮音显示在其他应用上方"
                    font.pixelSize: 12; color: welcomeWindow.muted
                    horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
                }
                Label {
                    Layout.fillWidth: true; Layout.topMargin: visible ? 12 : 0
                    visible: text.length > 0
                    text: player.error
                    color: welcomeWindow.dark ? "#FF9B93" : "#B42318"
                    font.pixelSize: 13; wrapMode: Text.Wrap
                    horizontalAlignment: Text.AlignHCenter
                }

                Rectangle {
                    Layout.fillWidth: true; Layout.topMargin: 28
                    implicitHeight: tips.implicitHeight + 36
                    radius: 20; color: welcomeWindow.surface
                    RowLayout {
                        id: tips
                        anchors { left: parent.left; right: parent.right; top: parent.top; margins: 18 }
                        spacing: 12
                        Repeater {
                            model: [
                                { heading: "轻点展开", detail: "图标打开播放器" },
                                { heading: "拖动移动", detail: "放在顺手的位置" },
                                { heading: "随时收起", detail: "减号收回小图标" }
                            ]
                            ColumnLayout {
                                required property var modelData
                                Layout.fillWidth: true; Layout.preferredWidth: 1
                                Layout.alignment: Qt.AlignTop
                                spacing: 8
                                Rectangle {
                                    Layout.alignment: Qt.AlignHCenter
                                    width: 18; height: 3; radius: 1.5; color: welcomeWindow.accent
                                }
                                Label {
                                    Layout.fillWidth: true
                                    text: modelData.heading; color: welcomeWindow.ink
                                    font.pixelSize: 14; font.bold: true
                                    horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
                                }
                                Label {
                                    Layout.fillWidth: true
                                    text: modelData.detail; color: welcomeWindow.muted
                                    font.pixelSize: 12; lineHeight: 1.3
                                    horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
                                }
                            }
                        }
                    }
                }

                Button {
                    id: exitButton
                    Layout.alignment: Qt.AlignHCenter; Layout.topMargin: 16
                    implicitWidth: 140; implicitHeight: 48
                    text: "退出浮音"
                    onClicked: player.quit()
                    contentItem: Label {
                        text: exitButton.text; color: welcomeWindow.muted; font.pixelSize: 14
                        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        radius: 12
                        color: exitButton.down ? welcomeWindow.surface : "transparent"
                        border.width: exitButton.visualFocus ? 1 : 0; border.color: welcomeWindow.accent
                    }
                }
            }
        }
    }
}
