#include "playercontroller.h"
#include "playlistdocument.h"
#include <QClipboard>
#include <QGuiApplication>
#include <QFile>
#include <QSaveFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QUuid>
#include <QSettings>

void PlayerController::setLibraryMessage(const QString &message) {
    m_libraryMessage = message;
#ifdef Q_OS_ANDROID
    m_overlayMessage = message;
#endif
    emit libraryMessageChanged();
}
void PlayerController::mergePlaylist(const QVariantMap &playlist, const QString &target, int skipped) {
    const auto incoming = playlist.value("tracks").toList();
    int added = -1;
    if (target == "new") {
        if (m_library.createWithTracks(playlist.value("name", QStringLiteral("导入的歌单")).toString().left(60),
                                      playlist.value("description").toString().left(4000), incoming)) added = incoming.size();
    } else added = m_library.addTracks(target, incoming);
    if (added < 0) { setLibraryMessage(m_library.error()); return; }
    QString message = QStringLiteral("已加入 %1 首，跳过 %2 首重复或不可用歌曲。").arg(added).arg(skipped + incoming.size() - added);
    const auto warning = playlist.value("warning").toString();
    if (!warning.isEmpty()) message += "\n" + warning;
    setLibraryMessage(message);
}
void PlayerController::importPlaylistText(const QString &text, const QString &target) {
    if (target != "new" && m_library.playlist(target).isEmpty()) { setLibraryMessage(QStringLiteral("目标歌单已不存在，请重新选择。")); return; }
    const auto parsed = PlaylistDocument::parse(text);
    if (!parsed.error.isEmpty()) { setLibraryMessage(parsed.error); return; }
    if (!parsed.onlineId.isEmpty()) {
        const auto label = parsed.onlineId.startsWith("tencent:") ? QStringLiteral("QQ音乐")
            : parsed.onlineId.startsWith("kuwo:") ? QStringLiteral("酷我音乐") : QStringLiteral("网易云");
        setLibraryMessage(QStringLiteral("正在读取%1歌单…").arg(label));
        const auto api = apiBase();
        m_api.fetchPlaylist(parsed.onlineId, [this, target, api](QVariantMap playlist, QString error) {
            if (api != apiBase()) { setLibraryMessage(QStringLiteral("服务地址已改变，请重新导入。")); return; }
            if (!error.isEmpty()) { setLibraryMessage(error); return; }
            mergePlaylist(playlist, target);
        });
    } else mergePlaylist(parsed.playlist, target, parsed.skipped);
}
void PlayerController::libraryAction(const QString &action, const QVariantMap &args) {
    const auto ids = args.value("ids").toStringList();
    const QString target = args.value("target", activePlaylist()).toString();
    if (action == "loadRankings") {
        const QString source = args.value("source", m_rankingSource).toString();
        if (source != "netease" && source != "tencent" && source != "kuwo") {
            m_rankingsMessage = QStringLiteral("请选择网易云、QQ音乐或酷我音乐的排行榜。");
            emit rankingsChanged(); return;
        }
        if (m_rankingsLoading && source == m_rankingSource) return;
        if (source != m_rankingSource) m_rankings.clear();
        m_rankingSource = source;
        QSettings().setValue("rankings/source", source);
        const int ticket = ++m_rankingsRequest;
        m_rankingsLoading = true; m_rankingsMessage.clear(); emit rankingsChanged();
        m_api.fetchRankings(source, [this, ticket](QVariantList lists, QString error) {
            if (ticket != m_rankingsRequest) return;
            m_rankingsLoading = false;
            if (error.isEmpty()) m_rankings = lists;
            m_rankingsMessage = error.isEmpty()
                ? (lists.isEmpty() ? QStringLiteral("音乐服务暂未提供排行榜，可刷新重试。")
                                   : QString())
                : error;
            emit rankingsChanged();
        });
    } else if (action == "searchPlaylists") {
        const auto query = args.value("query").toString().trimmed().left(200);
        m_playlistSearching = !query.isEmpty() && !m_searchSources.isEmpty(); m_playlistResults.clear(); m_playlistSearchMessage.clear();
        emit playlistSearchChanged(); m_api.searchPlaylists(query, m_searchSources, m_searchResultLimit);
        if (m_searchSources.isEmpty()) {
            m_playlistSearchMessage = QStringLiteral("请至少选择一个曲库后搜索。"); emit playlistSearchChanged();
        }
    } else if (action == "openOnline") {
        const int ticket = ++m_playlistGeneration;
        m_onlinePlaylist.clear(); m_onlinePlaylistLoading = true; setLibraryMessage({}); emit onlinePlaylistChanged();
        m_api.fetchPlaylist(args.value("id").toString(), [this, ticket](QVariantMap playlist, QString error) {
            if (ticket != m_playlistGeneration) return;
            m_onlinePlaylistLoading = false; m_onlinePlaylist = playlist;
            if (!error.isEmpty()) setLibraryMessage(error);
            emit onlinePlaylistChanged();
        });
    } else if (action == "playOnline") {
        const auto list = m_onlinePlaylist.value("tracks").toList(); const int index = args.value("index", -1).toInt();
        if (!m_busy && index >= 0 && index < list.size()) loadTrack(list[index].toMap(), true);
    } else if (action == "addOnline") {
        if (m_onlinePlaylistLoading || m_onlinePlaylist.isEmpty()) { setLibraryMessage(QStringLiteral("请先打开并等待歌单加载完成。")); return; }
        mergePlaylist(m_onlinePlaylist, target);
    } else if (action == "describe") {
        setLibraryMessage(m_library.describe(args.value("description").toString()) ? QStringLiteral("歌单简介已保存") : m_library.error());
    } else if (action == "duplicate") {
        setLibraryMessage(m_library.duplicate() ? QStringLiteral("已创建歌单副本") : m_library.error());
    } else if (action == "moveTrack") {
        setLibraryMessage(m_library.moveTrack(activePlaylist(), args.value("from", -1).toInt(), args.value("to", -1).toInt())
            ? QStringLiteral("排序已保存") : m_library.error());
    } else if (action == "transfer") {
        const bool move = args.value("move").toBool();
        setLibraryMessage(m_library.transfer(activePlaylist(), target, ids, move)
            ? (move ? QStringLiteral("所选歌曲已移动，重复歌曲已合并") : QStringLiteral("所选歌曲已复制，重复歌曲已跳过")) : m_library.error());
    } else if (action == "removeTracks") {
        setLibraryMessage(m_library.removeTracks(activePlaylist(), ids) ? QStringLiteral("已从歌单移除所选歌曲") : m_library.error());
    } else if (action == "importText") {
        importPlaylistText(args.value("text").toString(), target);
    } else if (action == "importFile") {
        const QUrl url(args.value("url").toString()); QFile file(url.toLocalFile());
        if (!url.isLocalFile() || !file.open(QIODevice::ReadOnly)) { setLibraryMessage(QStringLiteral("无法读取所选歌单文件。")); return; }
        if (file.size() > PlaylistDocument::MaxBytes) { setLibraryMessage(QStringLiteral("歌单文件不能超过 8 MiB。")); return; }
        const auto bytes = file.read(PlaylistDocument::MaxBytes + 1);
        if (file.error() != QFileDevice::NoError || bytes.size() > PlaylistDocument::MaxBytes) { setLibraryMessage(QStringLiteral("读取歌单文件失败，或文件超过 8 MiB。")); return; }
        importPlaylistText(QString::fromUtf8(bytes), target);
    } else if (action == "paste" || action == "chooseImport") {
#ifdef Q_OS_ANDROID
        if (!m_pendingImportToken.isEmpty()) { setLibraryMessage(QStringLiteral("请先完成当前导入操作。")); return; }
        m_pendingImportTarget = target; m_pendingImportToken = QUuid::createUuid().toString(QUuid::WithoutBraces);
        androidCommand("libraryDocument", QString::fromUtf8(QJsonDocument(QJsonObject{{"operation", action == "paste" ? "paste" : "import"},
            {"token", m_pendingImportToken}}).toJson(QJsonDocument::Compact)));
#else
        if (action == "paste") importPlaylistText(QGuiApplication::clipboard()->text(), target);
#endif
    } else if (action == "copyExport" || action == "exportFile" || action == "chooseExport") {
        QString error; const auto bytes = PlaylistDocument::encode(m_library.playlist(activePlaylist()), ids, error);
        if (!error.isEmpty()) { setLibraryMessage(error); return; }
        if (action == "copyExport") {
#ifdef Q_OS_ANDROID
            androidCommand("libraryClipboard", QString::fromUtf8(bytes));
#else
            QGuiApplication::clipboard()->setText(QString::fromUtf8(bytes));
            setLibraryMessage(QStringLiteral("歌单 JSON 已复制。本地歌曲只包含文件引用。"));
#endif
        } else if (action == "exportFile") {
            const QUrl url(args.value("url").toString()); QSaveFile file(url.toLocalFile());
            const bool ok = url.isLocalFile() && file.open(QIODevice::WriteOnly) && file.write(bytes) == bytes.size() && file.commit();
            setLibraryMessage(ok ? QStringLiteral("歌单 JSON 已导出。本地歌曲只包含文件引用。") : QStringLiteral("导出失败，请检查文件位置和磁盘空间。"));
        } else {
#ifdef Q_OS_ANDROID
            if (!m_pendingExportToken.isEmpty()) { setLibraryMessage(QStringLiteral("请先完成当前导出操作。")); return; }
            m_pendingExport = bytes; m_pendingExportToken = QUuid::createUuid().toString(QUuid::WithoutBraces);
            androidCommand("libraryDocument", QString::fromUtf8(QJsonDocument(QJsonObject{{"operation", "export"}, {"token", m_pendingExportToken},
                {"name", m_library.playlist(activePlaylist()).value("name").toString()}, {"text", QString::fromUtf8(bytes)}}).toJson(QJsonDocument::Compact)));
#endif
        }
    } else if (action == "documentResult") {
        const auto token = args.value("token").toString();
        const auto error = args.value("error").toString();
        if (!m_pendingImportToken.isEmpty() && token == m_pendingImportToken) {
            const auto destination = m_pendingImportTarget; m_pendingImportToken.clear(); m_pendingImportTarget.clear();
            if (args.value("cancelled").toBool()) { setLibraryMessage(QStringLiteral("已取消导入")); return; }
            if (!error.isEmpty()) { setLibraryMessage(error); return; }
            importPlaylistText(args.value("text").toString(), destination);
        } else if (!m_pendingExportToken.isEmpty() && token == m_pendingExportToken) {
            m_pendingExportToken.clear(); m_pendingExport.clear();
            setLibraryMessage(args.value("cancelled").toBool() ? QStringLiteral("已取消导出") : !error.isEmpty() ? error
                : QStringLiteral("歌单 JSON 已导出。本地歌曲只包含文件引用。"));
        }
    } else if (action == "clipboardResult") {
        setLibraryMessage(args.value("error").toString().isEmpty() ? QStringLiteral("歌单 JSON 已复制。本地歌曲只包含文件引用。") : args.value("error").toString());
    }
}
