import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: page
    required property var host
    required property var controller
    property string filterMode: "all"
    property string settingsKey: "cache"
    readonly property bool modalOpen: confirm.visible
    readonly property var entries: controller.cacheEntries || []
    readonly property var summary: controller.cacheSummary || ({})
    readonly property var options: controller.cacheSettings || ({})
    readonly property var shown: entries.filter(function(e) { return filterMode === "all" || (filterMode === "manual" ? e.manual : !e.manual) })
    spacing: 10
    function tr(text) { return host.t(text) }
    function bytes(value) {
        var n = Math.max(0, Number(value || 0))
        return n >= 1073741824 ? (n / 1073741824).toFixed(2) + " GB" : n >= 1048576 ? (n / 1048576).toFixed(1) + " MB" : (n / 1024).toFixed(0) + " KB"
    }
    function stateText(entry) {
        if (entry.pendingDelete) return tr("播放结束后删除")
        var names = { queued: "等待下载", resolving: "正在解析音源", downloading: "正在缓存", complete: "可离线播放", ready: "可离线播放", failed: "缓存失败", cancelled: "已取消", paused: "已暂停", waiting: "等待网络", waitingWifi: "等待 Wi-Fi" }
        return tr(names[entry.state] || entry.state || "等待下载")
    }
    function lyricText(state) {
        var names = { ready: "已缓存", available: "已缓存", missing: "暂无内容", absent: "暂无内容", none: "暂无内容", instrumental: "纯音乐", failed: "获取失败", error: "获取失败", unknown: "待获取", pending: "待获取" }
        return tr(names[state] || "待获取")
    }
    function qualityText(quality) {
        var names = { standard: "标准", higher: "较高", exhigh: "极高", lossless: "无损 FLAC", hires: "Hi-Res", master: "实验母带" }
        return tr(names[quality] || quality || "标准")
    }
    function errorText(error) {
        var names = {"Audio HTTP response failed":"音频下载请求失败", "Audio response is incomplete or invalid":"音频不完整或格式无效", "Automatic cache capacity exceeded":"自动缓存容量不足", "Cached audio cannot be decoded":"缓存音频无法解码", "Cached audio missing or damaged":"缓存文件丢失或损坏", "Cached file pending deletion":"播放结束后删除", "Cannot commit cache index":"缓存索引保存失败", "Cannot commit cached file":"无法保存缓存文件", "Cannot commit lyrics cache index":"歌词缓存索引保存失败", "Cannot remove cached file":"无法删除缓存文件", "Insufficient disk space":"存储空间不足"}
        return tr(names[error] || error || "")
    }
    function askClear(all) {
        confirm.itemKey = ""; confirm.all = all
        var selected = entries.filter(function(e) { return all || !e.manual })
        var size = selected.reduce(function(sum, e) { return sum + Number(e.bytes || 0) }, 0)
        confirm.message = (all ? tr("清空全部缓存？") : tr("清理自动缓存？")) + "\n" + selected.length + tr(" 首") + " · " + bytes(size) + "\n" + tr("只删除缓存文件，保留歌单。正在播放的文件稍后删除。")
        confirm.open()
    }
    function closeDialogs() { confirm.close() }
    component Words: Label {
        color: page.host.ink; textFormat: Text.PlainText; font.pixelSize: 13
        wrapMode: Text.Wrap; Layout.fillWidth: true
    }
    component CacheButton: Button {
        id: button
        implicitHeight: 36; padding: 8; font.pixelSize: 12
        Accessible.name: text
        contentItem: Text { text: button.text; font: button.font; color: button.enabled ? page.host.ink : page.host.muted; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter; textFormat: Text.PlainText }
        background: Rectangle { radius: 8; color: button.down || button.hovered ? page.host.selection : page.host.control; border.color: button.activeFocus ? page.host.accent : "transparent"; border.width: 2 }
    }
    RowLayout {
        Layout.fillWidth: true
        Words { text: page.tr("自动缓存播放过的歌曲"); font.weight: Font.DemiBold }
        Switch {
            objectName: "cacheAutomaticSwitch"; checked: !!page.options.automaticEnabled
            Accessible.name: page.tr("自动缓存播放过的歌曲")
            onToggled: page.controller.setCacheSettings({ automaticEnabled: checked })
        }
    }
    Words { text: page.tr("完整下载后才可离线播放；手动保存不会被自动清理。"); color: page.host.muted; font.pixelSize: 12 }
    RowLayout {
        Layout.fillWidth: true
        Words { text: page.tr("自动缓存容量") }
        ComboBox {
            id: capacity; objectName: "cacheCapacity"; Layout.preferredWidth: 130
            property var sizes: [268435456, 536870912, 1073741824, 2147483648, 5368709120]
            model: ["256 MB", "512 MB", "1 GB", "2 GB", "5 GB"]
            currentIndex: Math.max(0, sizes.indexOf(Number(page.options.automaticLimitBytes || 1073741824)))
            Accessible.name: page.tr("自动缓存容量")
            onActivated: function(index) { page.controller.setCacheSettings({ automaticLimitBytes: sizes[index] }) }
        }
    }
    Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: page.host.muted; opacity: .2 }
    Words { text: page.tr("缓存管理"); font.pixelSize: 15; font.weight: Font.DemiBold; Layout.topMargin: 5 }
    Words { objectName: "cacheSummary"; text: page.tr("总占用：") + page.bytes(page.summary.totalBytes) + "\n" + page.tr("手动保存：") + page.bytes(page.summary.manualBytes) + " · " + page.tr("自动缓存：") + page.bytes(page.summary.automaticBytes); color: page.host.muted; font.pixelSize: 12 }
    Words { visible: !!page.summary.message; text: page.tr(page.summary.message || ""); color: page.host.accent; font.pixelSize: 12 }
    RowLayout {
        Layout.fillWidth: true; spacing: 6
        Repeater {
            model: [{ key: "all", name: "全部" }, { key: "manual", name: "手动保存" }, { key: "automatic", name: "自动缓存" }]
            delegate: CacheButton {
                required property var modelData
                Layout.fillWidth: true; text: page.tr(modelData.name); checkable: true; checked: page.filterMode === modelData.key
                onClicked: page.filterMode = modelData.key
                background: Rectangle { radius: 8; color: parent.checked ? page.host.selection : page.host.control; border.color: parent.checked || parent.activeFocus ? page.host.accent : "transparent" }
            }
        }
    }
    Words { visible: page.shown.length === 0; text: page.tr("这里还没有缓存。在歌曲菜单选择“保存到本地”，或播放在线歌曲后等待自动缓存完成。"); color: page.host.muted; Layout.topMargin: 12; Layout.bottomMargin: 12 }
    ListView {
        id: cacheList; objectName: "cacheEntryList"
        Layout.fillWidth: true; Layout.preferredHeight: count ? Math.min(400, contentHeight) : 0
        visible: count > 0; clip: true; spacing: 8; model: page.shown
        boundsBehavior: Flickable.StopAtBounds; ScrollBar.vertical: ScrollBar {}
        delegate: Item {
            id: entryRow
            required property var modelData
            readonly property bool working: ["queued", "resolving", "downloading", "waiting", "waitingWifi", "paused"].indexOf(modelData.state) >= 0
            width: cacheList.width - 12; height: entryContent.implicitHeight + 18
            ColumnLayout {
                id: entryContent; anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top; anchors.topMargin: 8; spacing: 5
                Words { text: entryRow.modelData.title || (entryRow.modelData.track || {}).name || page.tr("未知歌曲"); font.weight: Font.DemiBold; maximumLineCount: 2; elide: Text.ElideRight }
                Words { text: (entryRow.modelData.artist || "") + " · " + page.qualityText(entryRow.modelData.quality) + " · " + page.bytes(entryRow.modelData.bytes); color: page.host.muted; font.pixelSize: 12 }
                Words { text: page.stateText(entryRow.modelData) + (entryRow.modelData.state === "downloading" && entryRow.modelData.progress >= 0 ? " · " + Math.round(entryRow.modelData.progress * 100) + "%" : ""); color: entryRow.modelData.state === "failed" ? page.host.danger : page.host.accent; font.pixelSize: 12 }
                ProgressBar { Layout.fillWidth: true; visible: entryRow.working; indeterminate: entryRow.modelData.progress < 0; value: Math.max(0, entryRow.modelData.progress || 0); Accessible.name: page.tr("缓存进度") }
                Words { text: page.tr("原文：") + page.lyricText(entryRow.modelData.lyricState) + " · " + page.tr("译文：") + page.lyricText(entryRow.modelData.translationState); font.pixelSize: 12; color: page.host.muted }
                Words { visible: !!entryRow.modelData.error; text: page.errorText(entryRow.modelData.error); color: page.host.danger; font.pixelSize: 12 }
                Flow {
                    Layout.fillWidth: true; spacing: 5
                    CacheButton { text: page.tr("播放"); enabled: entryRow.modelData.state === "complete" && !entryRow.modelData.pendingDelete; onClicked: page.controller.playCached(entryRow.modelData.key) }
                    CacheButton { text: page.tr("长期保留"); visible: !entryRow.modelData.manual; enabled: !entryRow.modelData.pendingDelete; onClicked: page.controller.promoteCached(entryRow.modelData.key) }
                    CacheButton { text: page.tr("取消"); visible: entryRow.working; onClicked: page.controller.cancelCache(entryRow.modelData.key) }
                    CacheButton { text: page.tr("重试"); visible: !entryRow.working && entryRow.modelData.state !== "complete"; enabled: !entryRow.modelData.pendingDelete; onClicked: page.controller.retryCached(entryRow.modelData.key) }
                    CacheButton { text: page.tr("补取歌词"); visible: entryRow.modelData.state === "complete"; enabled: !entryRow.modelData.pendingDelete; onClicked: page.controller.retryCachedLyrics(entryRow.modelData.key) }
                    CacheButton {
                        text: page.tr("删除"); enabled: !entryRow.modelData.pendingDelete
                        onClicked: { confirm.itemKey = entryRow.modelData.key; confirm.message = page.tr("删除这首歌的本地缓存？") + "\n" + (entryRow.modelData.title || "") + "\n" + page.tr("只删除缓存文件，保留歌单。正在播放的文件稍后删除。"); confirm.open() }
                    }
                }
                Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: page.host.muted; opacity: .15 }
            }
        }
    }
    RowLayout {
        Layout.fillWidth: true; spacing: 6
        CacheButton { objectName: "cacheClearAutomatic"; Layout.fillWidth: true; text: page.tr("清理自动缓存"); enabled: page.entries.some(function(e) { return !e.manual }); onClicked: page.askClear(false) }
        CacheButton { objectName: "cacheClearAll"; Layout.fillWidth: true; text: page.tr("清空全部缓存"); enabled: page.entries.length > 0; onClicked: page.askClear(true) }
    }
    Dialog {
        id: confirm; objectName: "cacheDeleteConfirmation"
        property string itemKey: ""
        property bool all: false
        property string message: ""
        parent: Overlay.overlay; modal: true; dim: false; focus: true
        width: Math.min(340, parent ? parent.width - 24 : 340)
        x: parent ? (parent.width - width) / 2 : 0
        y: parent ? Math.max(8, (parent.height - height) / 2) : 0
        title: page.tr("确认清理")
        standardButtons: Dialog.Ok | Dialog.Cancel
        background: Rectangle { radius: 12; color: page.host.backdrop; border.color: page.host.muted }
        contentItem: Label { text: confirm.message; color: page.host.ink; textFormat: Text.PlainText; wrapMode: Text.Wrap }
        onOpened: { standardButton(Dialog.Ok).text = page.tr("删除"); standardButton(Dialog.Cancel).text = page.tr("取消") }
        onAccepted: { if (itemKey) page.controller.removeCache(itemKey); else page.controller.clearCache(all) }
    }
}
