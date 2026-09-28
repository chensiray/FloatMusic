#include "playliststore.h"
#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QSaveFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QSet>
#include <QUuid>

namespace {
QVariantMap newPlaylist(const QString &id, const QString &name) {
    return {{"id", id}, {"name", name}, {"description", QString()}, {"tracks", QVariantList{}}};
}
bool appendUnique(QVariantList &target, const QVariantList &incoming, int &added) {
    QSet<QString> known;
    for (const auto &entry : target) known.insert(entry.toMap().value("id").toString());
    added = 0;
    for (const auto &entry : incoming) {
        auto track = entry.toMap();
        const QString id = track.value("id").toString().trimmed();
        const QString name = track.value("name").toString().trimmed();
        if (id.isEmpty() || name.isEmpty()) return false;
        if (known.contains(id)) continue;
        track["id"] = id;
        track["name"] = name;
        target.append(track);
        known.insert(id);
        ++added;
    }
    return true;
}
bool selectTracks(const QVariantList &tracks, const QStringList &ids, QVariantList &selected,
                  QVariantList &remaining) {
    const QSet<QString> requested(ids.cbegin(), ids.cend());
    if (requested.isEmpty() || requested.contains(QString())) return false;
    QSet<QString> found;
    for (const auto &entry : tracks) {
        const QString id = entry.toMap().value("id").toString();
        if (requested.contains(id)) { selected.append(entry); found.insert(id); }
        else remaining.append(entry);
    }
    return found == requested;
}
}

PlaylistStore::PlaylistStore(QObject *parent) : QObject(parent) {
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    QDir().mkpath(dir);
    m_path = dir + "/playlists.json";
    QFile file(m_path);
    if (file.open(QIODevice::ReadOnly)) {
        QJsonParseError parse;
        const auto document = QJsonDocument::fromJson(file.readAll(), &parse);
        const auto root = document.object();
        bool valid = parse.error == QJsonParseError::NoError && document.isObject()
            && root.value("playlists").isArray() && !root.value("playlists").toArray().isEmpty();
        QSet<QString> listIds;
        for (const auto &entry : root.value("playlists").toArray()) {
            const auto list = entry.toObject();
            const QString id = list.value("id").toString();
            if (id.isEmpty() || listIds.contains(id) || list.value("name").toString().trimmed().isEmpty()
                || !list.value("tracks").isArray()) valid = false;
            listIds.insert(id);
        }
        file.close();
        if (valid) {
            m_lists = root.value("playlists").toArray().toVariantList();
            m_active = root.value("active").toString();
            m_favoritesMigrated = root.value("favoritesMigrated").toBool();
        } else {
            // Never replace a damaged original unless a complete backup could be retained.
            const QString backup = m_path + ".invalid-" + QUuid::createUuid().toString(QUuid::WithoutBraces);
            m_storageBlocked = !QFile::copy(m_path, backup);
            m_error = m_storageBlocked ? QStringLiteral("歌单文件损坏且无法备份，已停止写入以保护原文件。")
                                       : QStringLiteral("歌单文件无法读取，已保留备份。");
        }
    } else if (file.exists()) {
        m_storageBlocked = true;
        m_error = QStringLiteral("无法读取原歌单文件，已停止写入以保护原文件。");
    }
    if (m_lists.isEmpty()) m_lists.append(newPlaylist("default", QStringLiteral("我的歌单")));
    for (auto &entry : m_lists) {
        auto list = entry.toMap();
        if (!list.contains("description")) list["description"] = QString();
        list.remove("trackCount"); // Counts always come from the ordered track list.
        entry = list;
    }
    if (index("favorites") < 0) m_lists.append(newPlaylist("favorites", QStringLiteral("收藏夹")));
    if (index(m_active) < 0) m_active = m_lists.first().toMap().value("id").toString();
}

int PlaylistStore::index(const QString &id) const {
    for (int i = 0; i < m_lists.size(); ++i)
        if (m_lists[i].toMap().value("id").toString() == id) return i;
    return -1;
}
QVariantMap PlaylistStore::playlist(const QString &id) const {
    const int i = index(id);
    if (i < 0) return {};
    auto list = m_lists[i].toMap();
    list["trackCount"] = list.value("tracks").toList().size();
    return list;
}
QVariantList PlaylistStore::playlists() const {
    QVariantList result;
    for (const auto &entry : m_lists) {
        auto list = playlist(entry.toMap().value("id").toString());
        list.remove("tracks");
        result.append(list);
    }
    return result;
}
QVariantList PlaylistStore::tracks() const { return tracks(m_active); }
QVariantList PlaylistStore::tracks(const QString &id) const { return playlist(id).value("tracks").toList(); }

bool PlaylistStore::reject(const QString &message) { m_error = message; emit changed(); return false; }
bool PlaylistStore::unchanged() { m_error.clear(); emit changed(); return true; }
bool PlaylistStore::commit(const QVariantList &lists, const QString &active, bool favoritesMigrated) {
    if (m_storageBlocked) return reject(QStringLiteral("原歌单文件无法安全读取或备份，当前修改未保存。"));
    QSaveFile file(m_path);
    const QByteArray data = QJsonDocument(QJsonObject::fromVariantMap({{"version", 2}, {"active", active},
        {"favoritesMigrated", favoritesMigrated}, {"playlists", lists}})).toJson();
    if (!file.open(QIODevice::WriteOnly) || file.write(data) != data.size() || !file.commit())
        return reject(QStringLiteral("歌单保存失败，请检查磁盘空间或写入权限。本次修改已撤销。"));
    // Publish only after the atomic replacement succeeds, including migration state.
    m_lists = lists;
    m_active = active;
    m_favoritesMigrated = favoritesMigrated;
    m_error.clear();
    emit changed();
    return true;
}
bool PlaylistStore::select(const QString &id) {
    if (index(id) < 0) return reject(QStringLiteral("歌单不存在，可能已被删除。"));
    return id == m_active ? unchanged() : commit(m_lists, id, m_favoritesMigrated);
}
bool PlaylistStore::create(const QString &name) { return createWithTracks(name, {}, {}); }
bool PlaylistStore::createWithTracks(const QString &name, const QString &description, const QVariantList &tracks) {
    const QString clean = name.trimmed();
    if (clean.isEmpty() || clean.size() > 60) return reject(QStringLiteral("歌单名称须为 1 至 60 个字符。"));
    if (description.size() > 4000) return reject(QStringLiteral("歌单简介最多 4000 个字符。"));
    QVariantList songs;
    int added = 0;
    if (!appendUnique(songs, tracks, added))
        return reject(QStringLiteral("歌曲数据缺少 ID 或名称，本次未创建歌单。"));
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    auto list = newPlaylist(id, clean);
    list["description"] = description.trimmed();
    list["tracks"] = songs;
    auto lists = m_lists;
    lists.append(list);
    return commit(lists, id, m_favoritesMigrated);
}
bool PlaylistStore::rename(const QString &name) {
    if (m_active == "favorites") return reject(QStringLiteral("收藏夹名称固定，可以修改简介。"));
    const QString clean = name.trimmed();
    if (clean.isEmpty() || clean.size() > 60) return reject(QStringLiteral("歌单名称须为 1 至 60 个字符。"));
    const int i = index(m_active);
    if (i < 0) return reject(QStringLiteral("当前歌单不存在。"));
    auto lists = m_lists;
    auto list = lists[i].toMap();
    list["name"] = clean;
    lists[i] = list;
    return commit(lists, m_active, m_favoritesMigrated);
}
bool PlaylistStore::describe(const QString &description) {
    if (description.size() > 4000) return reject(QStringLiteral("歌单简介最多 4000 个字符。"));
    const int i = index(m_active);
    if (i < 0) return reject(QStringLiteral("当前歌单不存在。"));
    auto lists = m_lists;
    auto list = lists[i].toMap();
    list["description"] = description.trimmed();
    lists[i] = list;
    return commit(lists, m_active, m_favoritesMigrated);
}
bool PlaylistStore::duplicate() {
    const int i = index(m_active);
    if (i < 0) return reject(QStringLiteral("当前歌单不存在。"));
    auto lists = m_lists;
    auto copy = lists[i].toMap();
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    copy["id"] = id;
    copy["name"] = copy.value("name").toString().left(57) + QStringLiteral(" 副本");
    lists.append(copy);
    return commit(lists, id, m_favoritesMigrated);
}
bool PlaylistStore::removePlaylist() {
    const int i = index(m_active);
    if (i < 0) return reject(QStringLiteral("当前歌单不存在。"));
    if (m_active == "favorites") return reject(QStringLiteral("收藏夹不能删除，可以移除其中的歌曲。"));
    if (m_lists.size() < 2) return reject(QStringLiteral("至少需要保留一个歌单。"));
    auto lists = m_lists;
    lists.removeAt(i);
    return commit(lists, lists.first().toMap().value("id").toString(), m_favoritesMigrated);
}
bool PlaylistStore::add(const QVariantMap &track) { return addTracks(m_active, {track}) >= 0; }
int PlaylistStore::addTracks(const QString &target, const QVariantList &incoming) {
    const int i = index(target);
    if (i < 0) { reject(QStringLiteral("目标歌单不存在，可能已被删除。")); return -1; }
    auto list = m_lists[i].toMap();
    auto songs = list.value("tracks").toList();
    int added = 0;
    if (!appendUnique(songs, incoming, added)) {
        reject(QStringLiteral("歌曲数据缺少 ID 或名称，本次未加入任何歌曲。")); return -1;
    }
    if (!added) { unchanged(); return 0; }
    list["tracks"] = songs;
    auto lists = m_lists;
    lists[i] = list;
    return commit(lists, m_active, m_favoritesMigrated) ? added : -1;
}
bool PlaylistStore::removeTrack(const QString &id) { return removeTracks(m_active, {id}); }
bool PlaylistStore::removeTracks(const QString &source, const QStringList &ids) {
    const int i = index(source);
    if (i < 0) return reject(QStringLiteral("源歌单不存在，可能已被删除。"));
    auto list = m_lists[i].toMap();
    QVariantList selected, remaining;
    if (!selectTracks(list.value("tracks").toList(), ids, selected, remaining))
        return reject(QStringLiteral("请选择有效歌曲；部分歌曲可能已被移除。"));
    list["tracks"] = remaining;
    auto lists = m_lists;
    lists[i] = list;
    return commit(lists, m_active, m_favoritesMigrated);
}
bool PlaylistStore::transfer(const QString &source, const QString &target, const QStringList &ids, bool move) {
    const int from = index(source), to = index(target);
    if (from < 0 || to < 0) return reject(QStringLiteral("源歌单或目标歌单不存在。"));
    if (from == to) return reject(QStringLiteral("请选择另一个目标歌单。"));
    auto sourceList = m_lists[from].toMap();
    auto targetList = m_lists[to].toMap();
    QVariantList selected, remaining;
    if (!selectTracks(sourceList.value("tracks").toList(), ids, selected, remaining))
        return reject(QStringLiteral("请选择有效歌曲；部分歌曲可能已被移除。"));
    auto songs = targetList.value("tracks").toList();
    int added = 0;
    if (!appendUnique(songs, selected, added)) return reject(QStringLiteral("选中歌曲的数据不完整，本次操作已取消。"));
    if (!move && !added) return unchanged();
    auto lists = m_lists;
    targetList["tracks"] = songs;
    lists[to] = targetList;
    if (move) { sourceList["tracks"] = remaining; lists[from] = sourceList; }
    return commit(lists, m_active, m_favoritesMigrated);
}
bool PlaylistStore::moveTrack(const QString &source, int from, int to) {
    const int i = index(source);
    if (i < 0) return reject(QStringLiteral("歌单不存在，可能已被删除。"));
    auto list = m_lists[i].toMap();
    auto songs = list.value("tracks").toList();
    if (from < 0 || to < 0 || from >= songs.size() || to >= songs.size())
        return reject(QStringLiteral("歌曲位置已变化，请刷新后重试。"));
    if (from == to) return unchanged();
    songs.move(from, to);
    list["tracks"] = songs;
    auto lists = m_lists;
    lists[i] = list;
    return commit(lists, m_active, m_favoritesMigrated);
}
bool PlaylistStore::migrateFavorites(const QVariantList &legacy) {
    if (m_favoritesMigrated) return unchanged();
    const int i = index("favorites");
    if (i < 0) return reject(QStringLiteral("收藏夹不存在，无法迁移旧收藏。"));
    auto list = m_lists[i].toMap();
    auto songs = list.value("tracks").toList();
    int added = 0;
    if (!appendUnique(songs, legacy, added))
        return reject(QStringLiteral("旧收藏含有不完整的歌曲数据，已保留原收藏备份，尚未完成迁移。"));
    list["tracks"] = songs;
    auto lists = m_lists;
    lists[i] = list;
    return commit(lists, m_active, true);
}
