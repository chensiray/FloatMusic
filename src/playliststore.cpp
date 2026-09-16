#include "playliststore.h"
#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QSaveFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QUuid>

PlaylistStore::PlaylistStore(QObject *parent) : QObject(parent) {
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    QDir().mkpath(dir); m_path = dir + "/playlists.json";
    QFile file(m_path);
    if (file.open(QIODevice::ReadOnly)) {
        const auto doc = QJsonDocument::fromJson(file.readAll()).object();
        m_lists = doc.value("playlists").toVariant().toList(); m_active = doc.value("active").toString();
        if (m_lists.isEmpty()) {
            // Preserve a damaged file instead of silently destroying it on the next edit.
            file.copy(m_path + ".invalid-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
            m_error = QStringLiteral("歌单文件无法读取，已保留备份。");
        }
    }
    if (m_lists.isEmpty()) m_lists << QVariantMap{{"id", "default"}, {"name", QStringLiteral("我的歌单")}, {"tracks", QVariantList{}}};
    if (index() < 0) m_active = m_lists.first().toMap().value("id").toString();
}
int PlaylistStore::index() const {
    for (int i = 0; i < m_lists.size(); ++i) if (m_lists[i].toMap().value("id").toString() == m_active) return i;
    return -1;
}
QVariantList PlaylistStore::playlists() const {
    QVariantList result;
    for (const auto &entry : m_lists) { auto list = entry.toMap(); list.remove("tracks"); result << list; }
    return result;
}
QVariantList PlaylistStore::tracks() const { const int i = index(); return i < 0 ? QVariantList{} : m_lists[i].toMap().value("tracks").toList(); }
bool PlaylistStore::save() {
    QSaveFile file(m_path);
    const QByteArray data = QJsonDocument(QJsonObject::fromVariantMap({{"version", 1}, {"active", m_active}, {"playlists", m_lists}})).toJson();
    const bool ok = file.open(QIODevice::WriteOnly) && file.write(data) == data.size() && file.commit();
    m_error = ok ? QString() : QStringLiteral("歌单保存失败，请检查磁盘空间。当前修改尚未保存。");
    emit changed(); return ok;
}
bool PlaylistStore::select(const QString &id) {
    for (const auto &entry : m_lists) if (entry.toMap().value("id").toString() == id) { m_active = id; return save(); }
    return false;
}
bool PlaylistStore::create(const QString &name) {
    if (name.trimmed().isEmpty() || name.trimmed().size() > 60) return false;
    m_active = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_lists << QVariantMap{{"id", m_active}, {"name", name.trimmed()}, {"tracks", QVariantList{}}}; return save();
}
bool PlaylistStore::rename(const QString &name) {
    if (name.trimmed().isEmpty() || name.trimmed().size() > 60) return false;
    auto list = m_lists[index()].toMap(); list["name"] = name.trimmed(); m_lists[index()] = list; return save();
}
bool PlaylistStore::removePlaylist() {
    if (m_lists.size() < 2) return false;
    m_lists.removeAt(index()); m_active = m_lists.first().toMap().value("id").toString(); return save();
}
bool PlaylistStore::add(const QVariantMap &track) {
    if (track.value("id").toString().isEmpty() || track.value("name").toString().isEmpty()) return false;
    auto songs = tracks(); for (const auto &song : songs) if (song.toMap().value("id") == track.value("id")) return true;
    songs << track; auto list = m_lists[index()].toMap(); list["tracks"] = songs; m_lists[index()] = list; return save();
}
bool PlaylistStore::removeTrack(const QString &id) {
    auto songs = tracks(); for (int i = 0; i < songs.size(); ++i) if (songs[i].toMap().value("id").toString() == id) {
        songs.removeAt(i); auto list = m_lists[index()].toMap(); list["tracks"] = songs; m_lists[index()] = list; return save();
    }
    return false;
}
