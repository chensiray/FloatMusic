import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The activity stays on this page until the user asks to show the overlay.
ApplicationWindow {
    id: welcomeWindow
    visible: true
    width: 390; height: 780
    title: welcomeWindow.t("浮音")
    font.pixelSize: 14
    property int themeMode: player.androidThemeMode()
    property bool animationsEnabled: player.androidAnimationsEnabled()
    property bool quitting: false
    readonly property string languageCode: player.uiLanguage.code
    function t(source) { var dependency=languageCode; return player.uiLanguage.text(source) }
    readonly property bool dark: themeMode === 2 || (themeMode === 0 && Qt.styleHints.colorScheme === Qt.Dark)
    readonly property color ink: dark ? "#F2F1FB" : "#18213D"
    readonly property color muted: dark ? "#ADB1C8" : "#68718D"
    readonly property color accent: dark ? "#B79BFF" : "#703AFF"
    readonly property color surface: dark ? "#191C2C" : "#F5F5FA"
    readonly property color line: dark ? "#343950" : "#DFDFEB"
    color: dark ? "#131728" : "#F1F1F8"
    background: PrismaticSurface { radius: 0; darkMode: welcomeWindow.dark }
    Component.onCompleted: player.initializeAndroidUi()
    function animatePress(control, animation, pressed) {
        animation.stop()
        if (!animationsEnabled) { control.feedbackScale = 1; return }
        animation.to = pressed ? 0.96 : 1
        animation.duration = pressed ? 80 : 160
        animation.start()
    }
    onAnimationsEnabledChanged: {
        if (!animationsEnabled) {
            if (overlayPress) overlayPress.stop()
            if (showOverlay) showOverlay.feedbackScale = 1
            if (exitPress) exitPress.stop()
            if (exitButton) exitButton.feedbackScale = 1
        }
    }
    onClosing: function(event) { event.accepted = quitting }
    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive) {
                welcomeWindow.themeMode = player.androidThemeMode()
                welcomeWindow.animationsEnabled = player.androidAnimationsEnabled()
                player.uiLanguage.refresh()
            }
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
            // Use the fixed viewport: ScrollView's implicit height derives from this page.
            height: Math.max(welcomeWindow.contentItem.height, content.implicitHeight + 48)
            ColumnLayout {
                id: content
                width: Math.min(parent.width - 48, 420)
                anchors.horizontalCenter: parent.horizontalCenter
                y: Math.max(24, (page.height - implicitHeight) / 2)
                spacing: 0

                RowLayout {
                    Layout.fillWidth: true
                    Layout.bottomMargin: 24
                    Label { text: welcomeWindow.t("浮音"); font.pixelSize: 20; font.bold: true; color: welcomeWindow.ink }
                    Item { Layout.fillWidth: true }
                    Label { text: "FloatMusic 1.5"; font.pixelSize: 12; color: welcomeWindow.muted }
                }

                Item {
                    Layout.alignment: Qt.AlignHCenter
                    Layout.preferredWidth: 200
                    Layout.preferredHeight: pageScroll.availableHeight < 700 ? 112 : 148
                    Layout.bottomMargin: 24
                    Accessible.ignored: true
                    Image {
                        objectName: "welcomeAppIcon"
                        anchors.centerIn: parent
                        width: parent.height; height: width
                        source: "qrc:/icons/floatmusic-floating.png"
                        fillMode: Image.PreserveAspectFit; smooth: true; mipmap: true
                    }
                }

                ComboBox {
                    id: languageSelector
                    objectName: "androidLanguageSelector"
                    Layout.alignment: Qt.AlignHCenter; Layout.bottomMargin: 18
                    Layout.preferredWidth: 176; implicitHeight: 40
                    model: ["简体中文", "English"]
                    currentIndex: welcomeWindow.languageCode === "en" ? 1 : 0
                    Accessible.name: welcomeWindow.t("界面语言")
                    onActivated: function(index) { player.uiLanguage.code = index === 1 ? "en" : "zh_CN" }
                    leftPadding: 16; rightPadding: 36
                    contentItem: Label {
                        text: languageSelector.displayText; color: welcomeWindow.ink
                        font.pixelSize: 14; verticalAlignment: Text.AlignVCenter
                    }
                    indicator: Label {
                        x: languageSelector.width - width - 14
                        y: (languageSelector.height - height) / 2
                        text: "▾"; color: welcomeWindow.accent; font.pixelSize: 16
                    }
                    background: Rectangle {
                        radius: 10; color: welcomeWindow.dark ? "#25273D" : "#F6F3FC"
                        border.color: languageSelector.visualFocus ? welcomeWindow.accent : welcomeWindow.line
                        border.width: languageSelector.visualFocus ? 2 : 1
                    }
                    delegate: ItemDelegate {
                        required property string modelData
                        required property int index
                        width: languageSelector.width; height: 44
                        highlighted: languageSelector.highlightedIndex === index
                        contentItem: Label { text: modelData; color: welcomeWindow.ink; verticalAlignment: Text.AlignVCenter }
                        background: Rectangle { color: highlighted ? (welcomeWindow.dark ? "#3E2D65" : "#EBE5FA") : welcomeWindow.surface }
                    }
                }

                Label {
                    Layout.fillWidth: true
                    text: welcomeWindow.t("让音乐留在手边")
                    color: welcomeWindow.ink
                    font.pixelSize: content.width < 300 ? 24 : 26
                    font.bold: true
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                }
                Label {
                    Layout.fillWidth: true
                    Layout.topMargin: 12; Layout.bottomMargin: 24
                    text: welcomeWindow.t("一个小窗口，陪你听歌、看歌词。\n切换应用，音乐也不必停下。")
                    color: welcomeWindow.muted
                    font.pixelSize: 14; lineHeight: 1.45
                    horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
                }

                Button {
                    id: showOverlay
                    Layout.alignment: Qt.AlignHCenter
                    implicitWidth: 192; implicitHeight: 48
                    property real feedbackScale: 1
                    text: welcomeWindow.t("显示悬浮窗")
                    Accessible.name: text
                    enabled: !welcomeWindow.quitting
                    onPressed: welcomeWindow.animatePress(showOverlay, overlayPress, true)
                    onReleased: welcomeWindow.animatePress(showOverlay, overlayPress, false)
                    onCanceled: { overlayPress.stop(); feedbackScale = 1 }
                    onClicked: player.showFloating()
                    NumberAnimation { id: overlayPress; target: showOverlay; property: "feedbackScale"; easing.type: Easing.OutCubic }
                    contentItem: Label {
                        scale: showOverlay.feedbackScale
                        text: showOverlay.text; font.pixelSize: 14; font.bold: true
                        color: welcomeWindow.accent
                        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        scale: showOverlay.feedbackScale
                        anchors.centerIn: parent
                        width: parent.width; height: 40; radius: 10
                        color: welcomeWindow.dark ? (showOverlay.down ? "#4A3576" : "#3E2D65") : (showOverlay.down ? "#DED3F6" : "#EBE5FA")
                        border.width: showOverlay.visualFocus ? 2 : 0
                        border.color: welcomeWindow.ink
                    }
                }
                Label {
                    Layout.fillWidth: true; Layout.topMargin: 12
                    text: player.overlayAllowed ? welcomeWindow.t("已允许悬浮显示 · 点击后收起此页面") : welcomeWindow.t("首次使用，需允许浮音显示在其他应用上方")
                    font.pixelSize: 12; color: welcomeWindow.muted
                    horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
                }
                Label {
                    Layout.fillWidth: true; Layout.topMargin: visible ? 12 : 0
                    visible: text.length > 0
                    text: welcomeWindow.t(player.error)
                    color: welcomeWindow.dark ? "#FF9B93" : "#B42318"
                    font.pixelSize: 13; wrapMode: Text.Wrap
                    horizontalAlignment: Text.AlignHCenter
                }

                Rectangle {
                    Layout.fillWidth: true; Layout.topMargin: 24
                    implicitHeight: tips.implicitHeight + 32
                    radius: 16; color: "transparent"
                    PrismaticSurface { anchors.fill: parent; radius: 16; elevated: true; darkMode: welcomeWindow.dark }
                    RowLayout {
                        id: tips
                        anchors { left: parent.left; right: parent.right; top: parent.top; margins: 16 }
                        spacing: 8
                        Repeater {
                            model: [
                                { heading: welcomeWindow.t("轻点展开"), detail: welcomeWindow.t("图标打开播放器") },
                                { heading: welcomeWindow.t("拖动移动"), detail: welcomeWindow.t("放在顺手的位置") },
                                { heading: welcomeWindow.t("随时收起"), detail: welcomeWindow.t("减号收回小图标") }
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
                                    font.pixelSize: 13; font.bold: true
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
                    objectName: "androidExitButton"
                    Layout.alignment: Qt.AlignHCenter; Layout.topMargin: 16
                    implicitWidth: 140; implicitHeight: 48
                    property real feedbackScale: 1
                    text: welcomeWindow.t("退出浮音")
                    Accessible.name: text
                    enabled: !welcomeWindow.quitting
                    onPressed: welcomeWindow.animatePress(exitButton, exitPress, true)
                    onReleased: welcomeWindow.animatePress(exitButton, exitPress, false)
                    onCanceled: { exitPress.stop(); feedbackScale = 1 }
                    onClicked: { welcomeWindow.quitting = true; player.quit() }
                    NumberAnimation { id: exitPress; target: exitButton; property: "feedbackScale"; easing.type: Easing.OutCubic }
                    contentItem: Label {
                        scale: exitButton.feedbackScale
                        text: exitButton.text; color: welcomeWindow.muted; font.pixelSize: 14
                        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        scale: exitButton.feedbackScale
                        anchors.centerIn: parent
                        width: parent.width; height: 36; radius: 10
                        color: exitButton.down ? welcomeWindow.surface : "transparent"
                        border.width: exitButton.visualFocus ? 1 : 0; border.color: welcomeWindow.accent
                    }
                }
            }
        }
    }
}
