#include "musicapi.h"
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonArray>
#include <QSettings>
#include <QTimer>
#include <QRegularExpression>
#include <QHash>
#include <QSet>
#include <QElapsedTimer>
#include <QDateTime>
#include <QNetworkProxyFactory>
#include <QNetworkProxyQuery>
#include <memory>
#include <algorithm>

namespace {
constexpr qsizetype playlistTrackLimit = 2000;
constexpr qsizetype playlistBatchSize = 100;
bool validHttpUrl(const QUrl &url) {
    return url.isValid() && !url.host().isEmpty() && url.userInfo().isEmpty()
        && (url.scheme() == "https" || url.scheme() == "http");
}
bool validId(const QString &id) {
    static const QRegularExpression pattern("^[1-9][0-9]{0,18}$");
    return pattern.match(id).hasMatch();
}
QString jsonId(const QJsonValue &value) {
    const QString id = value.isString() ? value.toString() : QString::number(value.toInteger(-1));
    return validId(id) ? id : QString();
}
QVariantMap songTrack(const QJsonObject &song) {
    const QString id = jsonId(song.value("id"));
    const QString name = song.value("name").toString().trimmed();
    if (id.isEmpty() || name.isEmpty()) return {};
    QStringList artists;
    const auto array = song.contains("ar") ? song.value("ar").toArray() : song.value("artists").toArray();
    for (const auto &artist : array) {
        const QString artistName = artist.toObject().value("name").toString();
        if (!artistName.isEmpty()) artists.append(artistName);
    }
    return {{"id", "netease:" + id}, {"songId", id}, {"source", "netease"},
            {"name", name}, {"artist", artists.join(" / ")}};
}
QUrl withQuery(QUrl url, const QList<QPair<QString, QString>> &query) {
    QUrlQuery params;
    for (const auto &pair : query) params.addQueryItem(pair.first, pair.second);
    // Form-style servers treat a literal '+' as a space; preserve song titles containing '+'.
    url.setQuery(params.query(QUrl::FullyEncoded).replace("+", "%2B")); return url;
}
bool audioHeader(const QByteArray &b) {
    return b.startsWith("fLaC") || b.startsWith("ID3") || b.startsWith("OggS")
        || ((b.startsWith("RIFF") || b.startsWith("RF64")) && b.mid(8, 4) == "WAVE")
        || (b.size() >= 8 && b.mid(4, 4) == "ftyp")
        || (b.size() >= 2 && quint8(b[0]) == 0xff && (quint8(b[1]) & 0xe0) == 0xe0);
}
class PlatformProxy : public QNetworkProxyFactory {
public:
    QList<QNetworkProxy> queryProxy(const QNetworkProxyQuery &query) override {
        if (query.peerHostName() == "127.0.0.1" || query.peerHostName() == "localhost" || query.peerHostName() == "::1")
            return {QNetworkProxy::NoProxy};
        return systemProxyForQuery(query);
    }
};
}
struct MusicApi::PlaylistFetch {
    QVariantMap playlist;
    QStringList orderedIds, pendingIds, warnings;
    QHash<QString, QVariantMap> knownTracks;
    QHash<QString, int> originalPositions;
    QString base;
    bool builtin = true;
    int sourceGeneration = 0;
    qsizetype offset = 0;
    qint64 expectedCount = 0;
    std::function<void(QVariantMap, QString)> done;
};

MusicApi::Endpoints MusicApi::builtinEndpoints() {
    Endpoints endpoints;
    endpoints.gdStudio = "https://music-api.gdstudio.xyz/api.php";
    endpoints.injahow = "https://api.injahow.cn/meting/";
    return endpoints;
}
MusicApi::MusicApi(QObject *parent) : MusicApi(builtinEndpoints(), parent) {}
MusicApi::MusicApi(const Endpoints &endpoints, QObject *parent) : QObject(parent), m_endpoints(endpoints) {
    m_network.setProxyFactory(new PlatformProxy);
    m_base = QSettings().value("netease/apiBase").toString();
}
bool MusicApi::setBaseUrl(const QString &value) {
    const QUrl url(value.trimmed());
    if (!value.trimmed().isEmpty() && (!validHttpUrl(url) || url.hasQuery() || url.hasFragment())) return false;
    m_base = value.trimmed();
    while (m_base.endsWith('/')) m_base.chop(1);
    ++m_searchGeneration;
    ++m_playlistSearchGeneration;
    ++m_rankingsGeneration;
    ++m_sourceGeneration;
    ++m_audioGeneration;
    m_audioCooldown.clear();
    if (m_audioReply) m_audioReply->abort();
    if (m_searchReply) m_searchReply->abort();
    if (m_playlistSearchReply) m_playlistSearchReply->abort();
    if (m_rankingsReply) m_rankingsReply->abort();
    QSettings().setValue("netease/apiBase", m_base);
    return true;
}
bool MusicApi::validQuality(const QString &quality) {
    return QStringList{"standard", "higher", "exhigh", "lossless", "hires"}.contains(quality);
}
QUrl MusicApi::endpoint(const QString &path, const QList<QPair<QString, QString>> &query) const {
    return withQuery(QUrl((m_base.isEmpty() ? m_endpoints.metadata : m_base) + path), query);
}
QNetworkReply *MusicApi::get(const QUrl &url, const QString &operation, std::function<void(QByteArray, QString)> done) {
    QNetworkRequest req(url);
    req.setTransferTimeout(m_endpoints.timeoutMs);
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setRawHeader("User-Agent", "FloatMusic/0.8");
    auto *reply = m_network.get(req);
    reply->setReadBufferSize(64 * 1024);
    struct Response { QByteArray body; bool oversized = false, timedOut = false; };
    const auto data = std::make_shared<Response>();
    auto *timer = new QTimer(reply); timer->setSingleShot(true);
    connect(timer, &QTimer::timeout, reply, [reply, data] { data->timedOut = true; reply->abort(); });
    timer->start(m_endpoints.timeoutMs);
    connect(reply, &QIODevice::readyRead, reply, [reply, data] {
        data->body += reply->readAll();
        if (data->body.size() > 2 * 1024 * 1024) { data->oversized = true; reply->abort(); }
    });
    connect(reply, &QNetworkReply::finished, this, [reply, timer, data, done, operation] {
        timer->stop();
        if (reply->isOpen()) data->body += reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const auto code = reply->error(); QString error;
        if (data->oversized) error = QStringLiteral("响应超过 2 MiB，已停止读取。");
        else if (data->timedOut || code == QNetworkReply::TimeoutError) error = QStringLiteral("连接超时，请检查网络后重试。");
        else if (status >= 400) error = QStringLiteral("服务器返回 HTTP %1，请稍后重试。").arg(status);
        else if (code == QNetworkReply::SslHandshakeFailedError) error = QStringLiteral("TLS 安全连接失败，请检查系统时间、代理或证书。");
        else if (code == QNetworkReply::HostNotFoundError) error = QStringLiteral("无法解析服务器域名，请检查网络或 DNS。");
        else if (code != QNetworkReply::NoError) error = QStringLiteral("连接失败：%1。请检查网络后重试。").arg(reply->errorString());
        else if (status < 200 || status >= 300) error = QStringLiteral("服务器返回非成功状态 HTTP %1。").arg(status);
        reply->deleteLater();
        done(data->body, error.isEmpty() ? QString() : operation + QStringLiteral("失败：") + error);
    });
    return reply;
}
QJsonObject MusicApi::parseJson(const QByteArray &bytes, QString &error) {
    QJsonParseError parse; const auto doc = QJsonDocument::fromJson(bytes, &parse);
    if (parse.error != QJsonParseError::NoError || !doc.isObject()) {
        error = QStringLiteral("API 返回格式错误，应为 JSON 对象。"); return {};
    }
    const auto object = doc.object();
    if (object.value("code").toInt() != 200) {
        const QString message = object.value("message").toString(object.value("msg").toString()).left(160);
        error = QStringLiteral("服务请求未成功（业务代码 %1）%2").arg(object.value("code").toInt())
            .arg(message.isEmpty() ? QStringLiteral("，可能需要登录或暂不可用。") : QStringLiteral("：") + message);
    }
    return object;
}
void MusicApi::search(const QString &keywords) {
    const int ticket = ++m_searchGeneration;
    if (m_searchReply) m_searchReply->abort();
    if (keywords.trimmed().isEmpty()) { emit results({}, {}); return; }
    const bool builtin = m_base.isEmpty();
    m_searchReply = get(endpoint(builtin ? "/api/search/get" : "/cloudsearch",
        {{builtin ? "s" : "keywords", keywords.trimmed()}, {"type", "1"}, {"offset", "0"}, {"limit", "30"}}),
        QStringLiteral("搜索"), [this, ticket](QByteArray bytes, QString error) {
        if (ticket != m_searchGeneration) return;
        QJsonObject json;
        if (error.isEmpty()) json = parseJson(bytes, error);
        QVariantList tracks;
        if (error.isEmpty()) {
            const auto result = json.value("result").toObject();
            if (!result.value("songs").isArray() && result.value("songCount").toInt(-1) != 0)
                error = QStringLiteral("搜索响应缺少歌曲列表，请重试或检查 API 服务。");
            for (const auto &value : result.value("songs").toArray()) {
                const auto song = value.toObject(); const QString id = QString::number(song.value("id").toInteger());
                if (!validId(id) || song.value("name").toString().isEmpty()) continue;
                QStringList artists;
                const auto array = song.contains("ar") ? song.value("ar").toArray() : song.value("artists").toArray();
                for (const auto &artist : array) artists << artist.toObject().value("name").toString();
                tracks << QVariantMap{{"id", "netease:" + id}, {"songId", id}, {"source", "netease"},
                    {"name", song.value("name").toString()}, {"artist", artists.join(" / ")}};
            }
        }
        emit results(tracks, error);
    });
}
void MusicApi::searchPlaylists(const QString &query) {
    const int ticket = ++m_playlistSearchGeneration;
    if (m_playlistSearchReply) m_playlistSearchReply->abort();
    if (query.trimmed().isEmpty()) { emit playlistResults({}, {}); return; }
    const bool builtin = m_base.isEmpty();
    m_playlistSearchReply = get(endpoint(builtin ? "/api/search/get" : "/search",
        {{builtin ? "s" : "keywords", query.trimmed()}, {"type", "1000"}, {"offset", "0"}, {"limit", "30"}}),
        QStringLiteral("搜索歌单"), [this, ticket](QByteArray bytes, QString error) {
        if (ticket != m_playlistSearchGeneration) return;
        QVariantList playlists;
        QJsonObject json;
        if (error.isEmpty()) json = parseJson(bytes, error);
        if (error.isEmpty()) {
            const auto result = json.value("result").toObject();
            if (!result.value("playlists").isArray() && result.value("playlistCount").toInt(-1) != 0)
                error = QStringLiteral("搜索响应缺少歌单列表，请重试或检查 API 服务。");
            QSet<QString> seen;
            for (const auto &value : result.value("playlists").toArray()) {
                const auto list = value.toObject();
                const QString id = jsonId(list.value("id"));
                const QString name = list.value("name").toString().trimmed();
                if (id.isEmpty() || name.isEmpty() || seen.contains(id)) continue;
                seen.insert(id);
                playlists.append(QVariantMap{{"id", id}, {"name", name},
                    {"description", list.value("description").toString()},
                    {"trackCount", qMax<qint64>(0, list.value("trackCount").toInteger())},
                    {"creator", list.value("creator").toObject().value("nickname").toString()}});
            }
        }
        emit playlistResults(playlists, error);
    });
}

void MusicApi::fetchRankings(std::function<void(QVariantList, QString)> done) {
    const int ticket = ++m_rankingsGeneration;
    if (m_rankingsReply) m_rankingsReply->abort();
    const bool builtin = m_base.isEmpty();
    // Lua's toplist endpoint returns playlist IDs: details use the same playlist flow.
    m_rankingsReply = get(endpoint(builtin ? "/api/toplist" : "/toplist", {}),
        QStringLiteral("读取排行榜"), [this, ticket, done](QByteArray bytes, QString error) {
        if (ticket != m_rankingsGeneration) return;
        QVariantList rankings;
        QJsonObject json;
        if (error.isEmpty()) json = parseJson(bytes, error);
        if (error.isEmpty() && !json.value("list").isArray())
            error = QStringLiteral("排行榜响应缺少榜单列表，请重试或检查音乐服务。");
        if (error.isEmpty()) {
            QSet<QString> seen;
            for (const auto &entry : json.value("list").toArray()) {
                const auto list = entry.toObject();
                const QString id = jsonId(list.value("id"));
                const QString name = list.value("name").toString().trimmed().left(200);
                if (id.isEmpty() || name.isEmpty() || seen.contains(id)) continue;
                seen.insert(id);
                rankings.append(QVariantMap{{"id", id}, {"name", name},
                    {"description", list.value("description").toString().left(4000)},
                    {"trackCount", qMax<qint64>(-1, list.value("trackCount").toInteger(-1))},
                    {"updateFrequency", list.value("updateFrequency").toString().left(100)},
                    {"updateTime", qMax<qint64>(0, list.value("updateTime").toInteger())},
                    {"creator", list.value("creator").toObject().value("nickname").toString().left(200)}});
            }
            const auto priority = [](const QVariant &entry) {
                QString name = entry.toMap().value("name").toString();
                if (name.startsWith(QStringLiteral("网易云"))) name.remove(0, 3);
                const int index = QStringList{QStringLiteral("热歌榜"), QStringLiteral("新歌榜"),
                    QStringLiteral("飙升榜"), QStringLiteral("原创榜")}.indexOf(name);
                return index < 0 ? 4 : index;
            };
            std::stable_sort(rankings.begin(), rankings.end(), [&priority](const QVariant &a, const QVariant &b) {
                return priority(a) < priority(b);
            });
            if (!json.value("list").toArray().isEmpty() && rankings.isEmpty())
                error = QStringLiteral("服务没有返回可用榜单，请刷新或检查音乐服务。");
        }
        done(rankings, error);
    });
}

void MusicApi::fetchPlaylist(const QString &id, std::function<void(QVariantMap, QString)> done) {
    if (!validId(id)) { done({}, QStringLiteral("歌单 ID 无效。")); return; }
    const auto state = std::make_shared<PlaylistFetch>();
    state->builtin = m_base.isEmpty();
    state->base = state->builtin ? m_endpoints.metadata : m_base;
    state->sourceGeneration = m_sourceGeneration;
    state->done = std::move(done);
    // Keep every batch on the same API source even if settings change while loading.
    const QUrl url = withQuery(QUrl(state->base + (state->builtin ? "/api/v3/playlist/detail" : "/playlist/detail")),
                              {{"id", id}});
    get(url, QStringLiteral("读取歌单"), [this, state, id](QByteArray bytes, QString error) {
        if (state->sourceGeneration != m_sourceGeneration) {
            state->done({}, QStringLiteral("API 来源已更改，请重新打开歌单。")); return;
        }
        if (!error.isEmpty()) { state->done({}, error); return; }
        const auto json = parseJson(bytes, error);
        if (!error.isEmpty()) { state->done({}, error); return; }
        const auto list = json.value("playlist").isObject() ? json.value("playlist").toObject()
                                                               : json.value("result").toObject();
        const QString name = list.value("name").toString().trimmed();
        if (name.isEmpty() || jsonId(list.value("id")) != id
            || (!list.value("tracks").isArray() && !list.value("trackIds").isArray())) {
            state->done({}, QStringLiteral("歌单详情格式不完整或无权访问，未取得有效歌单。")); return;
        }
        const auto rawTracks = list.value("tracks").toArray();
        const auto rawIds = list.value("trackIds").toArray();
        const qint64 reportedCount = list.value("trackCount").toInteger(-1);
        state->expectedCount = qMax<qint64>(qMax<qint64>(0, reportedCount), qMax(rawIds.size(), rawTracks.size()));
        state->playlist = {{"id", id}, {"name", name}, {"description", list.value("description").toString()},
            {"trackCount", state->expectedCount},
            {"creator", list.value("creator").toObject().value("nickname").toString()}};

        QSet<QString> seen;
        qsizetype invalidIds = 0, duplicateIds = 0;
        int sourcePosition = 0;
        const auto appendId = [&state, &seen, &invalidIds, &duplicateIds, &sourcePosition](const QJsonValue &value) {
            ++sourcePosition;
            const QString songId = jsonId(value);
            if (songId.isEmpty()) { ++invalidIds; return; }
            if (seen.contains(songId)) { ++duplicateIds; return; }
            seen.insert(songId);
            if (state->orderedIds.size() < playlistTrackLimit) {
                state->orderedIds.append(songId);
                state->originalPositions.insert(songId, sourcePosition);
            }
        };
        if (!rawIds.isEmpty()) {
            for (const auto &entry : rawIds)
                appendId(entry.isObject() ? entry.toObject().value("id") : entry);
        } else {
            for (const auto &entry : rawTracks) appendId(entry.toObject().value("id"));
            if (reportedCount < 0)
                state->warnings.append(QStringLiteral("服务未提供完整歌曲 ID 列表及总数，无法确认是否取得整张歌单。"));
        }
        if (invalidIds)
            state->warnings.append(QStringLiteral("服务返回的 %1 条歌曲 ID 无效，无法读取这些歌曲。 ").arg(invalidIds).trimmed());
        if (duplicateIds)
            state->warnings.append(QStringLiteral("服务返回 %1 条重复歌曲 ID，已按首次出现的位置去重。 ").arg(duplicateIds).trimmed());
        if (seen.size() > playlistTrackLimit)
            state->warnings.append(QStringLiteral("歌单较大，本次最多读取前 %1 首有效歌曲。 ").arg(playlistTrackLimit).trimmed());

        const QSet<QString> wanted(state->orderedIds.cbegin(), state->orderedIds.cend());
        for (const auto &entry : rawTracks) {
            const auto track = songTrack(entry.toObject());
            const QString songId = track.value("songId").toString();
            if (!track.isEmpty() && wanted.contains(songId)) state->knownTracks.insert(songId, track);
        }
        for (const auto &songId : state->orderedIds)
            if (!state->knownTracks.contains(songId)) state->pendingIds.append(songId);
        fetchPlaylistBatch(state);
    });
}

void MusicApi::fetchPlaylistBatch(const std::shared_ptr<PlaylistFetch> &state) {
    if (state->sourceGeneration != m_sourceGeneration) {
        state->done({}, QStringLiteral("API 来源已更改，请重新打开歌单。")); return;
    }
    if (state->offset >= state->pendingIds.size()) { finishPlaylist(state); return; }
    const QStringList batch = state->pendingIds.mid(state->offset, playlistBatchSize);
    state->offset += batch.size();
    const QString ids = batch.join(',');
    const QUrl url = withQuery(QUrl(state->base + (state->builtin ? "/api/v1/song/detail" : "/song/detail")),
                              {{"ids", state->builtin ? "[" + ids + "]" : ids}});
    get(url, QStringLiteral("补全歌单歌曲"), [this, state, batch](QByteArray bytes, QString error) {
        if (state->sourceGeneration != m_sourceGeneration) {
            state->done({}, QStringLiteral("API 来源已更改，请重新打开歌单。")); return;
        }
        QJsonObject json;
        if (error.isEmpty()) json = parseJson(bytes, error);
        if (error.isEmpty() && !json.value("songs").isArray())
            error = QStringLiteral("歌曲详情响应缺少 songs 列表。");
        if (!error.isEmpty()) {
            state->warnings.append(error);
            finishPlaylist(state);
            return;
        }
        const QSet<QString> requested(batch.cbegin(), batch.cend());
        for (const auto &entry : json.value("songs").toArray()) {
            const auto track = songTrack(entry.toObject());
            const QString songId = track.value("songId").toString();
            if (!track.isEmpty() && requested.contains(songId)) state->knownTracks.insert(songId, track);
        }
        fetchPlaylistBatch(state);
    });
}

void MusicApi::finishPlaylist(const std::shared_ptr<PlaylistFetch> &state) {
    QVariantList tracks;
    for (const auto &songId : state->orderedIds) {
        if (!state->knownTracks.contains(songId)) continue;
        auto track = state->knownTracks.value(songId);
        track["playlistPosition"] = state->originalPositions.value(songId);
        tracks.append(track);
    }
    if (tracks.size() < state->expectedCount)
        state->warnings.append(QStringLiteral("歌单共 %1 首，已读取 %2 首，另有 %3 首未取得（可能受权限、失效歌曲或读取上限影响）；当前内容不是完整歌单。")
            .arg(state->expectedCount).arg(tracks.size()).arg(state->expectedCount - tracks.size()));
    state->playlist["tracks"] = tracks;
    state->playlist["warning"] = state->warnings.join('\n');
    state->done(state->playlist, {});
}

void MusicApi::resolve(const QString &songId, std::function<void(QUrl, QString)> done) {
    resolve(songId, "standard", std::move(done));
}
void MusicApi::resolve(const QString &songId, const QString &quality, std::function<void(QUrl, QString)> done) {
    if (hasAudioBackups()) {
        resolveAudio(songId, quality, [done](Audio audio, QString error) { done(audio.url, error); });
        return;
    }
    if (!validId(songId) || !validQuality(quality)) { done({}, QStringLiteral("歌曲 ID 或音质参数无效。")); return; }
    const bool builtin = m_base.isEmpty();
    const auto query = QList<QPair<QString, QString>>{{"id", songId}, {"level", quality}};
    const auto url = builtin ? withQuery(QUrl(m_endpoints.playback), query) : endpoint("/song/url/v1", query);
    get(url, QStringLiteral("获取播放地址"), [builtin, done](QByteArray bytes, QString error) {
        if (!error.isEmpty()) { done({}, error); return; }
        QUrl media;
        if (builtin) {
            if (bytes.size() <= 8192) media = QUrl(QString::fromUtf8(bytes).trimmed(), QUrl::StrictMode);
        } else {
            const auto json = parseJson(bytes, error);
            const auto data = json.value("data").toArray();
            if (!data.isEmpty()) media = QUrl(data.first().toObject().value("url").toString(), QUrl::StrictMode);
        }
        if (error.isEmpty() && !validHttpUrl(media))
            error = QStringLiteral("该音质未返回有效播放地址，歌曲可能不可用；请换音质或重试。");
        done(error.isEmpty() ? media : QUrl(), error);
    });
}
struct MusicApi::AudioFetch {
    struct Source { QString id, name, format; QUrl request; };
    QList<Source> sources;
    int index = 0, generation = 0;
    QElapsedTimer elapsed;
    QStringList errors;
    std::function<void(Audio, QString)> done;
};

void MusicApi::audioRequest(const QUrl &url, bool sample, int timeoutMs, std::function<void(QByteArray, QUrl, QString)> done) {
    QNetworkRequest request(url);
    request.setTransferTimeout(timeoutMs);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    request.setRawHeader("User-Agent", "FloatMusic/0.8");
    if (sample) request.setRawHeader("Range", "bytes=0-63");
    auto *reply = m_network.get(request); m_audioReply = reply;
    reply->setReadBufferSize(16 * 1024);
    struct Response { QByteArray bytes; bool sampled = false, oversized = false, timedOut = false; };
    auto data = std::make_shared<Response>();
    auto *timer = new QTimer(reply); timer->setSingleShot(true);
    connect(timer, &QTimer::timeout, reply, [reply, data] { data->timedOut = true; reply->abort(); });
    timer->start(timeoutMs);
    connect(reply, &QIODevice::readyRead, reply, [reply, data, sample] {
        const qsizetype limit = sample ? 64 : 64 * 1024;
        data->bytes += reply->read(limit + 1 - data->bytes.size());
        if (sample && data->bytes.size() >= 16) { data->sampled = true; data->bytes.truncate(64); reply->abort(); }
        else if (data->bytes.size() > limit) { data->oversized = true; reply->abort(); }
    });
    connect(reply, &QNetworkReply::finished, this, [reply, timer, data, sample, done] {
        timer->stop();
        if (reply->isOpen() && !data->sampled && !data->oversized) data->bytes += reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QString error;
        if (data->timedOut || reply->error() == QNetworkReply::TimeoutError) error = QStringLiteral("连接超时");
        else if (status < 200 || status >= 300) error = status ? QStringLiteral("HTTP %1").arg(status) : QStringLiteral("连接失败，请检查网络或系统代理");
        else if (data->oversized || data->bytes.size() > (sample ? 64 : 64 * 1024)) error = QStringLiteral("响应过大");
        else if (reply->error() != QNetworkReply::NoError && !(sample && data->sampled)) error = QStringLiteral("连接失败，请检查网络或系统代理");
        else if (sample && !audioHeader(data->bytes)) error = QStringLiteral("地址已失效或返回的不是音频");
        const QUrl finalUrl = reply->url(); reply->deleteLater();
        done(data->bytes, finalUrl, error);
    });
}

void MusicApi::resolveAudio(const QString &songId, const QString &quality, std::function<void(Audio, QString)> done, const QStringList &excluded) {
    if (!validId(songId) || !validQuality(quality)) { done({}, QStringLiteral("歌曲 ID 或音质参数无效。")); return; }
    const auto state = std::make_shared<AudioFetch>();
    state->generation = ++m_audioGeneration;
    if (m_audioReply) m_audioReply->abort();
    state->done = std::move(done); state->elapsed.start();
    const auto add = [&](QString id, QString name, QString format, QUrl request) {
        if (!excluded.contains(id)) state->sources.append({id, name, format, request});
    };
    const QList<QPair<QString, QString>> query{{"id", songId}, {"level", quality}};
    if (!m_base.isEmpty()) add("custom", QStringLiteral("自定义服务"), "custom", endpoint("/song/url/v1", query));
    else {
        const QHash<QString, QString> bitrates{{"standard", "128"}, {"higher", "192"}, {"exhigh", "320"}, {"lossless", "740"}, {"hires", "999"}};
        if (!m_endpoints.gdStudio.isEmpty()) add("gd", QStringLiteral("GD 音乐台"), "gd", withQuery(QUrl(m_endpoints.gdStudio),
            {{"types", "url"}, {"source", "netease"}, {"id", songId}, {"br", bitrates.value(quality)}}));
        add("byfuns", QStringLiteral("原接口"), "plain", withQuery(QUrl(m_endpoints.playback), query));
        if (!m_endpoints.injahow.isEmpty()) add("injahow", "INJAHOW", "meting", withQuery(QUrl(m_endpoints.injahow),
            {{"server", "netease"}, {"type", "song"}, {"id", songId}}));
    }
    tryAudioSource(state);
}

void MusicApi::tryAudioSource(const std::shared_ptr<AudioFetch> &state) {
    if (state->generation != m_audioGeneration) { state->done({}, QStringLiteral("音源请求已取消。")); return; }
    while (state->index < state->sources.size() && m_audioCooldown.value(state->sources[state->index].id) > QDateTime::currentMSecsSinceEpoch()) {
        state->errors.append(state->sources[state->index].name + QStringLiteral("：暂不可用，稍后重试")); ++state->index;
    }
    if (state->index >= state->sources.size() || state->elapsed.elapsed() >= 24000) {
        state->done({}, QStringLiteral("暂未取得可播放音频。%1。请检查网络后重试或更换音质。")
            .arg(state->errors.isEmpty() ? QStringLiteral("没有更多可用来源") : state->errors.join(QStringLiteral("；")))); return;
    }
    const auto source = state->sources[state->index++];
    if (source.id == "gd") {
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        while (!m_gdRequests.isEmpty() && m_gdRequests.first() <= now - 300000) m_gdRequests.removeFirst();
        // GD Studio documents at most 50 API requests in five minutes; leave a small margin.
        if (m_gdRequests.size() >= 45) { state->errors.append(source.name + QStringLiteral("：访问较频繁，稍后重试")); tryAudioSource(state); return; }
        m_gdRequests.append(now);
    }
    const int timeout = qMax(1, std::min({m_endpoints.timeoutMs, 6000, int(24000 - state->elapsed.elapsed())}));
    audioRequest(source.request, false, timeout, [this, state, source](QByteArray bytes, QUrl, QString error) {
        if (state->generation != m_audioGeneration) { state->done({}, QStringLiteral("音源请求已取消。")); return; }
        if (!error.isEmpty()) {
            m_audioCooldown[source.id] = QDateTime::currentMSecsSinceEpoch() + 30000;
            state->errors.append(source.name + "：" + error); tryAudioSource(state); return;
        }
        QString address; int bitrate = 0;
        if (source.format == "plain") { if (bytes.size() <= 8192) address = QString::fromUtf8(bytes).trimmed(); }
        else {
            QJsonParseError parse; const auto doc = QJsonDocument::fromJson(bytes, &parse);
            if (parse.error == QJsonParseError::NoError) {
                if (source.format == "gd" && doc.isObject()) { address = doc.object().value("url").toString(); bitrate = doc.object().value("br").toInt(); }
                else if (source.format == "meting" && doc.isArray() && !doc.array().isEmpty()) address = doc.array().first().toObject().value("url").toString();
                else if (source.format == "custom" && doc.object().value("code").toInt() == 200) {
                    const auto array = doc.object().value("data").toArray();
                    if (!array.isEmpty()) address = array.first().toObject().value("url").toString();
                }
            }
        }
        const QUrl media(address, QUrl::StrictMode);
        if (address.size() > 8192 || !validHttpUrl(media)) {
            state->errors.append(source.name + QStringLiteral("：该歌曲或音质未返回有效地址")); tryAudioSource(state); return;
        }
        const int remaining = int(24000 - state->elapsed.elapsed());
        if (remaining <= 0) { tryAudioSource(state); return; }
        audioRequest(media, true, qMax(1, std::min({m_endpoints.timeoutMs, 6000, remaining})),
            [this, state, source, bitrate](QByteArray, QUrl finalUrl, QString failure) {
            if (state->generation != m_audioGeneration) { state->done({}, QStringLiteral("音源请求已取消。")); return; }
            if (failure.isEmpty() && validHttpUrl(finalUrl)) state->done({finalUrl, source.id, source.name, bitrate}, {});
            else { state->errors.append(source.name + "：" + failure); tryAudioSource(state); }
        });
    });
}
void MusicApi::fetchLyrics(const QString &songId, std::function<void(Lyrics, QString)> done) {
    if (!validId(songId)) { done({}, QStringLiteral("歌曲 ID 无效。")); return; }
    get(endpoint(m_base.isEmpty() ? "/api/song/lyric" : "/lyric", {{"id", songId}, {"tv", "-1"}, {"lv", "-1"}}),
        QStringLiteral("获取歌词"), [done](QByteArray bytes, QString error) {
        if (!error.isEmpty()) { done({}, error); return; }
        const auto json = parseJson(bytes, error);
        if (error.isEmpty() && !json.contains("lrc") && !json.contains("nolyric") && !json.contains("uncollected"))
            error = QStringLiteral("歌词响应格式错误，请重试。");
        done({json.value("lrc").toObject().value("lyric").toString(),
              json.value("tlyric").toObject().value("lyric").toString(), json.value("nolyric").toBool()}, error);
    });
}
