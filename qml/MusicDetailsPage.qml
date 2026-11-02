import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: page
    required property var api
    required property var translate
    property color ink: "#252437"
    property color muted: "#646176"
    property color accent: "#7057c8"
    property color surface: "#f7f6fc"
    property color selected: "#eae5fa"
    readonly property var state: api.state
    readonly property var details: state.details || ({})
    signal closeRequested()
    function t(s) { return translate(s) }
    function command(action,args) { api.action(action,args || ({})) }
    component Words: Text {
        color: page.ink; textFormat: Text.PlainText; wrapMode: Text.Wrap
        font.pixelSize: 14
    }
    component ChoiceButton: Button {
        id: b
        property bool chosen: false
        implicitHeight: 40
        contentItem: Text { text: b.text; color: b.chosen ? page.accent : page.ink; font.pixelSize: 13; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter; elide: Text.ElideRight }
        background: Rectangle { radius: 8; color: b.chosen || b.down ? page.selected : page.surface; border.width: b.activeFocus ? 2 : 0; border.color: page.accent }
    }
    ColumnLayout {
        anchors.fill: parent; spacing: 8
        RowLayout {
            Layout.fillWidth: true
            Words { text: page.t("音乐资料与评论"); font.pixelSize: 18; font.weight: Font.DemiBold; Layout.fillWidth: true }
            ChoiceButton { objectName: "closeMusicDetails"; text: page.t("返回"); onClicked: page.closeRequested() }
        }
        RowLayout {
            Layout.fillWidth: true
            Repeater {
                model: [{key:"netease",name:"网易云音乐"},{key:"tencent",name:"QQ 音乐"},{key:"kuwo",name:"酷我音乐"}]
                ChoiceButton {
                    required property var modelData
                    Layout.fillWidth: true; Layout.minimumWidth: 0
                    text: page.t(modelData.name); chosen: page.state.source === modelData.key
                    onClicked: page.command("source",{source:modelData.key})
                }
            }
        }
        ListView {
            id: comments; objectName: "musicCommentsList"
            Layout.fillWidth: true; Layout.fillHeight: true; clip: true; spacing: 12
            boundsBehavior: Flickable.StopAtBounds
            model: page.state.choosing ? page.state.candidates : page.state.comments
            ScrollBar.vertical: ScrollBar {}
            property real savedY: 0
            property bool restoring: false
            property bool initialPositionPending: true
            property string browseKey: (page.state.source || "") + ":" + ((page.state.track || {}).id || (page.state.track || {}).songId || "") + ":" + page.state.order + ":" + page.state.choosing
            function beginReading() { forceLayout(); positionViewAtBeginning() }
            onBrowseKeyChanged: { initialPositionPending=true; Qt.callLater(beginReading) }
            onWidthChanged: if (headerItem && contentY < originY + headerItem.height) Qt.callLater(beginReading)
            Connections {
                target: page.api
                function onChanged() {
                    if (comments.initialPositionPending && !page.state.loading && !page.state.matching) {
                        comments.initialPositionPending=false
                        Qt.callLater(comments.beginReading)
                    }
                    if (comments.restoring && !page.state.loading) {
                        Qt.callLater(function(){ comments.contentY = Math.max(comments.originY, Math.min(comments.savedY, comments.originY + Math.max(0,comments.contentHeight-comments.height))); comments.restoring=false })
                    }
                }
            }
            header: ColumnLayout {
                width: comments.width - 12; spacing: 8
                RowLayout {
                    Layout.fillWidth: true; visible: !page.state.choosing
                    Rectangle {
                        Layout.preferredWidth: 64; Layout.preferredHeight: 64; radius: 8; color: page.surface
                        Words { anchors.centerIn: parent; text: page.t("封面"); font.pixelSize: 12; color: page.muted }
                        Image { anchors.fill: parent; source: page.details.coverUrl || ""; asynchronous: true; sourceSize: Qt.size(128,128); fillMode: Image.PreserveAspectFit; visible: status === Image.Ready }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true; Layout.minimumWidth: 0; spacing: 3
                        Words { text: page.state.track.name || ""; font.pixelSize: 17; font.weight: Font.DemiBold; Layout.fillWidth: true }
                        Words { text: page.state.track.artist || ""; color: page.muted; Layout.fillWidth: true }
                    }
                }
                Words { visible: !page.state.choosing && text.length > 0; text: [page.details.album || "",page.details.publishDate || ""].filter(function(v){return v.length>0}).join(" · "); color: page.muted; Layout.fillWidth: true }
                Words { text: page.details.commentCount !== undefined ? page.t("评论数：") + Number(page.details.commentCount).toLocaleString(Qt.locale(),"f",0) : ""; visible: text.length>0 && !page.state.choosing; color: page.accent; Layout.fillWidth: true }
                Words { visible: page.state.choosing; text: page.t("请选择同一版本的歌曲；不会改变正在播放的音乐。"); color: page.muted; Layout.fillWidth: true }
                Words { visible: page.state.stale; text: page.t("正在显示缓存内容，可能不是最新。"); color: page.accent; Layout.fillWidth: true }
                Words { text: page.t(page.state.detailsError || ""); visible: text.length>0; color: page.muted; Layout.fillWidth: true }
                RowLayout {
                    visible: !page.state.choosing; Layout.fillWidth: true
                    ChoiceButton { text: page.t("热门评论"); chosen: page.state.order === "hot"; Layout.fillWidth: true; onClicked: { comments.contentY=comments.originY; page.command("order",{order:"hot"}) } }
                    ChoiceButton { text: page.t("最新评论"); chosen: page.state.order === "new"; Layout.fillWidth: true; onClicked: { comments.contentY=comments.originY; page.command("order",{order:"new"}) } }
                }
                Item { height: 4; Layout.fillWidth: true }
            }
            delegate: ColumnLayout {
                id: entry
                required property var modelData
                property bool expanded: false
                width: comments.width - 12; spacing: 6
                Words { text: page.state.choosing ? entry.modelData.name : entry.modelData.author; font.weight: Font.DemiBold; Layout.fillWidth: true }
                Words { text: page.state.choosing ? (entry.modelData.artist || "") + " · " + (entry.modelData.album || "") : (entry.modelData.date || "") + (entry.modelData.likes !== undefined ? " · " + page.t("赞 ") + entry.modelData.likes : ""); color: page.muted; font.pixelSize: 12; Layout.fillWidth: true }
                Words { id: body; visible: !page.state.choosing; text: entry.modelData.content || ""; maximumLineCount: entry.expanded ? 10000 : 6; elide: Text.ElideRight; Layout.fillWidth: true; lineHeight: 1.4 }
                ChoiceButton { visible: page.state.choosing || body.truncated || entry.expanded; text: page.state.choosing ? page.t("查看此版本") : entry.expanded ? page.t("收起") : page.t("展开"); onClicked: page.state.choosing ? page.command("select",{id:entry.modelData.id}) : entry.expanded=!entry.expanded }
                Rectangle { height: 1; color: page.muted; opacity: 0.18; Layout.fillWidth: true }
            }
            footer: ColumnLayout {
                width: comments.width - 12; spacing: 8
                Words { text: page.t(page.state.error || ""); visible: text.length>0; Layout.fillWidth: true; color: page.accent }
                Words { text: page.t("正在加载…"); visible: page.state.loading || page.state.matching; color: page.muted; Layout.fillWidth: true }
                Words { text: page.t("暂无评论"); visible: !page.state.loading && !page.state.matching && !page.state.choosing && !page.state.error && comments.count===0; color: page.muted; Layout.fillWidth: true }
                ChoiceButton { text: page.t("重试"); visible: !!page.state.error && !page.state.choosing; enabled: !page.state.loading; onClicked: page.command("retry") }
                ChoiceButton {
                    text: page.t("加载更多"); visible: page.state.hasMore; enabled: !page.state.loading; Layout.fillWidth: true
                    onClicked: { comments.savedY=comments.contentY; comments.restoring=true; page.command("more") }
                }
                Item { height: 12; Layout.fillWidth: true }
            }
        }
    }
}
