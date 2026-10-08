#include "playlistdocument.h"
#include "audiofilepolicy.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QRegularExpression>
#include <QFileInfo>
#include <QFile>
#include <QSet>
#include <QUrl>
#include <QUrlQuery>
#include <cmath>

namespace PlaylistDocument {
Parsed parse(const QString &text) {
    Parsed result;
    QString cleaned = text.trimmed();
    if (cleaned.startsWith(QChar(0xfeff))) cleaned.remove(0, 1);
    const auto bytes = cleaned.trimmed().toUtf8();
    if (bytes.isEmpty() || bytes.size() > MaxBytes) {
        result.error = QStringLiteral("请输入歌单 JSON、网易云 / QQ / 酷我歌单链接或网易云 ID，内容不能超过 8 MiB。"); return result;
    }
    static const QRegularExpression idPattern("^[1-9][0-9]{0,18}$");
    if (idPattern.match(QString::fromUtf8(bytes)).hasMatch()) { result.onlineId = QString::fromUtf8(bytes); return result; }
    if (!bytes.startsWith('{') && !bytes.startsWith('[')) {
        // Check the URL host and path instead of a platform URL nested in another URL.
        static const QRegularExpression links(R"(https?://[^\s<>"`]+)", QRegularExpression::CaseInsensitiveOption);
        static const QRegularExpression qqPath(R"(^/n/ryqq/playlist/([1-9][0-9]{0,18})/?$)");
        static const QRegularExpression kuwoPath(R"(^/(?:playlist_detail|h5app/playlist)/([1-9][0-9]{0,18})/?$)");
        auto matches = links.globalMatch(QString::fromUtf8(bytes));
        while (matches.hasNext()) {
            QString token = matches.next().captured();
            const QString trailing = QStringLiteral(",.)]，。！：）】");
            while (!token.isEmpty() && trailing.contains(token.back())) token.chop(1);
            const QUrl url(token, QUrl::StrictMode);
            if (!url.isValid() || !url.userInfo().isEmpty()) continue;
            const auto host = url.host().toLower();
            QString path = url.path();
            QUrlQuery query(url);
            QString id, source;
            if (host == "music.163.com" || host == "y.music.163.com" || host == "www.music.163.com") {
                if (url.fragment().startsWith("/playlist?")) {
                    const QUrl section(url.fragment()); path = section.path(); query = QUrlQuery(section);
                }
                if (path == "/playlist" || path == "/m/playlist") id = query.queryItemValue("id");
                source = "netease";
            } else if (host == "y.qq.com" || host == "i.y.qq.com") {
                const auto match = qqPath.match(path);
                if (match.hasMatch()) id = match.captured(1);
                else if (path == "/n/m/detail/taoge/index.html" || path == "/n2/m/share/details/taoge.html")
                    id = query.queryItemValue("id");
                source = "tencent";
            } else if (host == "www.kuwo.cn" || host == "kuwo.cn" || host == "m.kuwo.cn") {
                const auto match = kuwoPath.match(path);
                if (match.hasMatch()) id = match.captured(1);
                source = "kuwo";
            }
            if (!idPattern.match(id).hasMatch()) continue;
            result.onlineId = source == "netease" ? id : source + ":playlist:" + id;
            return result;
        }
        result.error = QStringLiteral("无法识别内容。支持浮音 JSON、网易云 / QQ / 酷我歌单链接；数字 ID 默认按网易云读取。"); return result;
    }
    QJsonParseError parseError;
    const auto document = QJsonDocument::fromJson(bytes, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        result.error = QStringLiteral("歌单 JSON 格式有误，请选择浮音导出的 JSON 文件。"); return result;
    }
    const auto root = document.object();
    if (!root.value("tracks").isArray() || (root.contains("version") && root.value("version").toInt() != 1)) {
        result.error = QStringLiteral("不支持的歌单格式或版本，应包含 tracks 数组。"); return result;
    }
    const auto songs = root.value("tracks").toArray();
    if (songs.size() > 10000) { result.error = QStringLiteral("每次最多导入 10000 首歌曲，请分拆文件。"); return result; }
    static const QRegularExpression midPattern("^[A-Za-z0-9]{1,64}$");
    QVariantList tracks; QSet<QString> seen;
    for (const auto &value : songs) {
        const auto raw = value.toObject();
        QVariantMap track;
        QString name = raw.value("name").toString().trimmed().left(300);
        const QString source = raw.value("source").toString();
        if (name.isEmpty()) { ++result.skipped; continue; }
        if (source == "netease" || source == "tencent" || source == "kuwo") {
            QString id = raw.value("songId").toVariant().toString();
            const QString prefix = source + ':';
            if (id.isEmpty() && raw.value("id").toString().startsWith(prefix)) id = raw.value("id").toString().mid(prefix.size());
            const bool validId = source == "tencent" ? midPattern.match(id).hasMatch() : idPattern.match(id).hasMatch();
            if (!validId) { ++result.skipped; continue; }
            track = {{"id", prefix + id}, {"source", source}, {"songId", id}};
        } else if (source == "local") {
            const QString path = raw.value("path").toString();
            QFile file(path); const QFileInfo info(path);
            // Exports reference local audio; they never embed files or trust arbitrary URLs.
            if (!info.isAbsolute() || !info.isFile() || !file.open(QIODevice::ReadOnly)
                || !AudioFilePolicy::validate(info.fileName(), file.size(), file.peek(16)).isEmpty()) {
                ++result.skipped; continue;
            }
            const QString id = raw.value("id").toString();
            if (!id.startsWith("local:") || id.size() > 300) { ++result.skipped; continue; }
            track = {{"id", id}, {"source", source}, {"path", info.absoluteFilePath()}};
        } else { ++result.skipped; continue; }
        const auto id = track.value("id").toString();
        if (seen.contains(id)) { ++result.skipped; continue; }
        seen.insert(id); track["name"] = name; track["artist"] = raw.value("artist").toString().left(500);
        const double seconds = raw.value("duration").toVariant().toDouble();
        if ((source == "tencent" || source == "kuwo") && std::isfinite(seconds) && seconds > 0 && seconds <= 86400)
            track["duration"] = seconds;
        tracks << track;
    }
    if (!songs.isEmpty() && tracks.isEmpty()) {
        result.error = QStringLiteral("没有可导入的歌曲。本地音乐需要在本机仍可访问，文件不包含音频本身。"); return result;
    }
    QString name = root.value("name").toString().trimmed().left(60);
    result.playlist = {{"name", name.isEmpty() ? QStringLiteral("导入的歌单") : name},
        {"description", root.value("description").toString().left(4000)}, {"tracks", tracks}};
    return result;
}
QByteArray encode(const QVariantMap &playlist, const QStringList &ids, QString &error) {
    const QSet<QString> selected(ids.cbegin(), ids.cend());
    QVariantList tracks;
    for (const auto &entry : playlist.value("tracks").toList()) {
        const auto song = entry.toMap();
        if (!selected.isEmpty() && !selected.contains(song.value("id").toString())) continue;
        // Do not export playback URLs, session positions, or unrelated transient fields.
        QVariantMap clean;
        for (const auto &key : {"id", "source", "songId", "name", "artist", "path"})
            if (song.contains(key)) clean[key] = song.value(key);
        const double seconds = song.value("duration").toDouble();
        const auto source = song.value("source").toString();
        if ((source == "tencent" || source == "kuwo") && std::isfinite(seconds) && seconds > 0 && seconds <= 86400)
            clean["duration"] = seconds;
        tracks << clean;
    }
    if (!selected.isEmpty() && tracks.isEmpty()) { error = QStringLiteral("所选歌曲已不在歌单中，请重新选择。"); return {}; }
    if (tracks.size() > 10000) { error = QStringLiteral("每个 JSON 最多保存 10000 首，请多选歌曲分批导出。"); return {}; }
    const auto bytes = QJsonDocument(QJsonObject::fromVariantMap({{"format", "floatmusic-playlist"}, {"version", 1},
        {"name", playlist.value("name")}, {"description", playlist.value("description")}, {"tracks", tracks}})).toJson(QJsonDocument::Indented);
    if (bytes.size() > MaxBytes) { error = QStringLiteral("导出内容超过 8 MiB，请分批选择歌曲。"); return {}; }
    return bytes;
}
}
