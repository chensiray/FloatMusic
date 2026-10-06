#include "musicapi.h"
#include "catalogcodec.h"
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonParseError>
#include <QUrlQuery>
#include <QSet>
#include <QDateTime>
#include <QCryptographicHash>
#include <QRegularExpression>
#include <algorithm>

namespace {
constexpr int pageSize = 100, trackLimit = 2000, maxPages = 40;
QString sourceName(const QString &s) {
    return s == "tencent" ? QStringLiteral("QQ 音乐")
           : s == "kuwo"  ? QStringLiteral("酷我音乐")
                          : QStringLiteral("网易云音乐");
}
QString numberText(const QJsonValue &v) {
    return v.isString() ? v.toString() : v.isDouble() ? QString::number(v.toInteger(-1)) : QString();
}
qint64 count(const QJsonValue &v, qint64 fallback = -1) {
    bool ok = false;
    const auto n = numberText(v).toLongLong(&ok);
    return ok && n >= 0 ? n : fallback;
}
QString numericId(const QJsonValue &v) {
    static const QRegularExpression re("^[1-9][0-9]{0,18}$");
    const auto id = numberText(v);
    return re.match(id).hasMatch() ? id : QString();
}
QStringList selectedSources(const QStringList &sources) {
    QStringList result;
    for (const auto &s : sources)
        if (QStringList{"netease", "tencent", "kuwo"}.contains(s) && !result.contains(s))
            result.append(s);
    return result;
}
QUrl queryUrl(const QString &base, const QList<QPair<QString, QString>> &query) {
    QUrl url(base);
    QUrlQuery q(url);
    for (const auto &p : query)
        q.addQueryItem(p.first, p.second);
    url.setQuery(q.query(QUrl::FullyEncoded).replace("+", "%2B"));
    return url;
}
QVariantMap summary(const QString &s, const QString &kind, const QString &id, const QString &name) {
    return {{"id", s == "netease" ? id : s + ':' + kind + ':' + id},
            {"resourceId", id},
            {"source", s},
            {"sourceName", sourceName(s)},
            {"kind", kind},
            {"name", name.trimmed().left(1000)}};
}
QVariantList merge(const QStringList &order, const QHash<QString, QVariantList> &lists, int limit) {
    QVariantList result;
    for (int i = 0; result.size() < limit; ++i) {
        bool any = false;
        for (const auto &s : order) {
            const auto list = lists.value(s);
            if (i < list.size() && result.size() < limit) {
                result.append(list[i]);
                any = true;
            }
        }
        if (!any)
            break;
    }
    return result;
}
QJsonObject qqData(const QByteArray &bytes, const QString &key, QString &error) {
    QJsonParseError p;
    const auto doc = QJsonDocument::fromJson(bytes, &p);
    if (p.error != QJsonParseError::NoError || !doc.isObject()) {
        error = QStringLiteral("QQ 音乐响应格式无效。");
        return {};
    }
    const auto obj = doc.object();
    const auto req = obj.value(key).toObject();
    if (obj.value("code").toInt(-1) != 0 || req.value("code").toInt(-1) != 0) {
        error = QStringLiteral("QQ 音乐服务请求未成功，公开内容可能暂不可用。");
        return {};
    }
    if (!req.value("data").isObject()) {
        error = QStringLiteral("QQ 音乐响应缺少数据。");
        return {};
    }
    const auto data = req.value("data").toObject();
    if (data.contains("code") && data.value("code").toInt(-1) != 0) {
        error = QStringLiteral("QQ 音乐详情请求未成功。");
        return {};
    }
    return data;
}
QString names(const QJsonValue &v) {
    if (v.isString())
        return v.toString().left(1000);
    QStringList result;
    for (const auto &n : v.toArray()) {
        const auto text = n.toObject().value("name").toString();
        if (!text.isEmpty())
            result.append(text);
    }
    return result.join(" / ").left(1000);
}
QVariantMap track(const QString &s, const QJsonObject &song) {
    QString id = s == "tencent" ? song.value("mid").toString(song.value("songmid").toString())
                                : numericId(song.value("id"));
    static const QRegularExpression mid("^[A-Za-z0-9]{1,64}$");
    if (s == "tencent" && !mid.match(id).hasMatch())
        id.clear();
    const auto name = song.value("name")
                          .toString(song.value("title").toString(song.value("songname").toString()))
                          .trimmed()
                          .left(1000);
    if (id.isEmpty() || name.isEmpty())
        return {};
    const auto album = song.value("album");
    return {{"id", s + ':' + id},
            {"songId", id},
            {"source", s},
            {"sourceName", sourceName(s)},
            {"name", name},
            {"artist", names(song.value(s == "tencent" ? "singer" : "artist"))},
            {"album", album.isObject() ? album.toObject().value("name").toString() : album.toString()},
            {"duration", count(song.value(s == "tencent" ? "interval" : "duration"), 0)}};
}
QString firstText(const QJsonObject &o, const QStringList &keys) {
    for (const auto &k : keys) {
        const auto s = o.value(k).toString();
        if (!s.isEmpty())
            return s;
    }
    return {};
}
QUrl rankingUrl(const QString &endpoint, const QString &id, int page) {
    const QJsonObject payload{{"uid", ""},
                              {"devId", ""},
                              {"sFrom", "kuwo_sdk"},
                              {"user_type", "AP"},
                              {"carSource", "kwplayercar_ar_6.0.1.0_apk_keluze.apk"},
                              {"id", id},
                              {"pn", page},
                              {"rn", pageSize}};
    const auto data =
        QString::fromLatin1(CatalogCodec::aesEncrypt(QJsonDocument(payload).toJson(QJsonDocument::Compact),
                                                     CatalogCodec::rankingKey())
                                .toBase64());
    const auto time = QString::number(QDateTime::currentMSecsSinceEpoch());
    const QString appId = "y67sprxhhpws";
    const auto sign = QString::fromLatin1(
        QCryptographicHash::hash((appId + data + time).toUtf8(), QCryptographicHash::Md5).toHex().toUpper());
    return queryUrl(endpoint, {{"data", data}, {"time", time}, {"appId", appId}, {"sign", sign}});
}
} // namespace

QNetworkReply *MusicApi::qqRequest(const QString &module, const QString &method, const QJsonObject &params,
                                   std::function<void(QByteArray, QString)> done) {
    const bool search = method == "DoSearchForQQMusicDesktop";
    const QString key = search ? module : QStringLiteral("req_0");
    QJsonObject envelope{{key, QJsonObject{{"module", module}, {"method", method}, {"param", params}}}};
    if (!search)
        envelope.insert("comm", QJsonObject{{"uin", "0"},
                                            {"g_tk", 5381},
                                            {"g_tk_new_20200303", 5381},
                                            {"ct", 24},
                                            {"cv", 4747474},
                                            {"format", "json"},
                                            {"inCharset", "utf-8"},
                                            {"outCharset", "utf-8"},
                                            {"notice", 0},
                                            {"need_new_code", 1},
                                            {"platform", "yqq.json"}});
    return request(QUrl(m_endpoints.qqMusicu), QJsonDocument(envelope).toJson(QJsonDocument::Compact), true,
                   QStringLiteral("读取 QQ 音乐目录"), std::move(done));
}
struct MusicApi::TencentPlaylistSearch {
    QString query;
    int limit = 30, pageSize = 30, page = 1, pageLimit = 1, generation = 0;
    QVariantList playlists;
    QSet<QString> seen;
    std::function<void(QVariantList, QString)> done;
};
void MusicApi::fetchTencentPlaylistSearchPage(const std::shared_ptr<TencentPlaylistSearch> &state) {
    if (state->generation != m_playlistSearchGeneration)
        return;
    auto *reply = qqRequest(
        "music.search.SearchCgiService", "DoSearchForQQMusicDesktop",
        {{"search_type", 3},
         {"query", state->query},
         {"page_num", state->page},
         {"num_per_page", state->pageSize}},
        [this, state](QByteArray bytes, QString error) {
            if (state->generation != m_playlistSearchGeneration)
                return;
            QJsonObject data;
            QJsonArray lists;
            if (error.isEmpty()) {
                data = qqData(bytes, "music.search.SearchCgiService", error);
                const auto raw = data.value("body").toObject().value("songlist").toObject().value("list");
                if (error.isEmpty() && !raw.isArray())
                    error = QStringLiteral("搜索响应缺少歌单列表。");
                lists = raw.toArray();
            }
            if (!error.isEmpty()) {
                state->done(state->playlists, error);
                return;
            }
            for (const auto &v : lists) {
                const auto o = v.toObject();
                const auto id = numericId(o.value("dissid"));
                const auto name = o.value("dissname").toString().trimmed();
                if (id.isEmpty() || name.isEmpty() || state->seen.contains(id))
                    continue;
                state->seen.insert(id);
                auto row = summary("tencent", "playlist", id, name);
                row["description"] = CatalogCodec::descriptionText(o.value("introduction").toString());
                row["trackCount"] = count(o.value("song_count"), 0);
                row["creator"] = o.value("creator").toObject().value("name").toString();
                row["coverUrl"] = o.value("imgurl").toString();
                state->playlists.append(row);
                if (state->playlists.size() >= state->limit)
                    break;
            }
            const auto meta = data.value("meta").toObject();
            bool nextValid = false;
            const auto next = numberText(meta.value("nextpage")).toLongLong(&nextValid);
            const bool end = nextValid ? next <= state->page
                                       : count(meta.value("sum")) >= 0 &&
                                             state->page * state->pageSize >= count(meta.value("sum"));
            // Desktop filters some rows after paging: a short array does not establish the end.
            if (!lists.isEmpty() && !end && state->playlists.size() < state->limit &&
                state->page < state->pageLimit) {
                ++state->page;
                fetchTencentPlaylistSearchPage(state);
            } else
                state->done(state->playlists, {});
        });
    if (reply)
        m_playlistSearchReplies.append(reply);
}
void MusicApi::searchPlaylists(const QString &query) {
    searchPlaylists(query, {"netease"}, 30);
}
void MusicApi::searchPlaylists(const QString &query, const QStringList &sources) {
    searchPlaylists(query, sources, 30);
}
void MusicApi::searchPlaylists(const QString &query, const QStringList &sources, int limit) {
    if (!QList<int>{10, 20, 30, 50, 100}.contains(limit))
        limit = 30;
    const int generation = ++m_playlistSearchGeneration;
    if (m_playlistSearchReply)
        m_playlistSearchReply->abort();
    for (const auto &r : std::as_const(m_playlistSearchReplies))
        if (r)
            r->abort();
    m_playlistSearchReplies.clear();
    const auto selected = selectedSources(sources);
    if (query.trimmed().isEmpty() || selected.isEmpty()) {
        emit playlistResults({}, {});
        return;
    }
    struct Batch {
        int remaining;
        QStringList order;
        QHash<QString, QVariantList> lists;
        QStringList errors;
    };
    const auto batch = std::make_shared<Batch>();
    batch->remaining = selected.size();
    batch->order = selected;
    const auto complete = [this, batch, generation, limit](const QString &s, QVariantList rows,
                                                           QString error) {
        if (generation != m_playlistSearchGeneration)
            return;
        batch->lists[s] = rows;
        if (!error.isEmpty())
            batch->errors.append(sourceName(s) + QStringLiteral("：") + error);
        if (--batch->remaining)
            return;
        m_playlistSearchReplies.clear();
        emit playlistResults(merge(batch->order, batch->lists, limit), batch->errors.join('\n'));
    };
    for (const auto &s : selected) {
        if (s == "tencent" && limit > 30) {
            const auto state = std::make_shared<TencentPlaylistSearch>();
            state->query = query.trimmed();
            state->limit = limit;
            state->pageLimit = (limit + state->pageSize - 1) / state->pageSize;
            state->generation = generation;
            state->done = [complete](QVariantList rows, QString error) {
                complete("tencent", std::move(rows), std::move(error));
            };
            fetchTencentPlaylistSearchPage(state);
            continue;
        }
        const auto callback = [this, s, complete, limit](QByteArray bytes, QString error) {
            QJsonArray lists;
            bool valid = false;
            if (error.isEmpty()) {
                if (s == "tencent") {
                    const auto data = qqData(bytes, "music.search.SearchCgiService", error);
                    const auto rows =
                        data.value("body").toObject().value("songlist").toObject().value("list");
                    valid = rows.isArray();
                    lists = rows.toArray();
                } else if (s == "kuwo") {
                    const auto doc = CatalogCodec::parseLiteral(bytes, error);
                    const auto rows = doc.object().value("abslist");
                    valid = rows.isArray();
                    lists = rows.toArray();
                    if (!valid && count(doc.object().value("TOTAL")) == 0)
                        valid = true;
                } else {
                    const auto json = parseJson(bytes, error);
                    const auto r = json.value("result").toObject();
                    const auto rows = r.value("playlists");
                    valid = rows.isArray() || r.value("playlistCount").toInt(-1) == 0;
                    lists = rows.toArray();
                }
                if (error.isEmpty() && !valid)
                    error = QStringLiteral("搜索响应缺少歌单列表。");
            }
            QVariantList rows;
            QSet<QString> seen;
            if (error.isEmpty())
                for (const auto &v : lists) {
                    const auto o = v.toObject();
                    const auto id = numericId(o.value(s == "tencent" ? "dissid"
                                                      : s == "kuwo"  ? "playlistid"
                                                                     : "id"));
                    const auto name = o.value(s == "tencent" ? "dissname" : "name").toString().trimmed();
                    if (id.isEmpty() || name.isEmpty() || seen.contains(id))
                        continue;
                    seen.insert(id);
                    auto row = summary(s, "playlist", id, name);
                    row["description"] = CatalogCodec::descriptionText(o.value(s == "tencent" ? "introduction"
                                                 : s == "kuwo"  ? "intro"
                                                                : "description")
                                             .toString());
                    row["trackCount"] = count(o.value(s == "tencent" ? "song_count"
                                                      : s == "kuwo"  ? "songnum"
                                                                     : "trackCount"),
                                              0);
                    row["creator"] = s == "kuwo" ? o.value("nickname").toString()
                                                 : o.value("creator")
                                                       .toObject()
                                                       .value(s == "tencent" ? "name" : "nickname")
                                                       .toString();
                    row["coverUrl"] = o.value(s == "kuwo"      ? "pic"
                                              : s == "tencent" ? "imgurl"
                                                               : "coverImgUrl")
                                          .toString();
                    rows.append(row);
                    if (rows.size() >= limit)
                        break;
                }
            complete(s, rows, error);
        };
        QNetworkReply *reply = nullptr;
        if (s == "tencent")
            reply = qqRequest(
                "music.search.SearchCgiService", "DoSearchForQQMusicDesktop",
                {{"search_type", 3}, {"query", query.trimmed()}, {"page_num", 1}, {"num_per_page", limit}},
                callback);
        else if (s == "kuwo")
            reply = get(queryUrl(m_endpoints.kuwoPlaylistSearch, {{"pn", "0"},
                                                                  {"rn", QString::number(limit)},
                                                                  {"all", query.trimmed()},
                                                                  {"ft", "playlist"},
                                                                  {"rformat", "json"},
                                                                  {"encoding", "utf8"},
                                                                  {"ver", "mbox"},
                                                                  {"vipver", "MUSIC_8.7.7.0_BCS37"},
                                                                  {"plat", "pc"},
                                                                  {"devid", "28156413"},
                                                                  {"pay", "0"},
                                                                  {"needliveshow", "0"}}),
                        QStringLiteral("搜索酷我歌单"), callback);
        else {
            const bool builtin = m_base.isEmpty();
            reply = get(endpoint(builtin ? "/api/search/get" : "/search",
                                 {{builtin ? "s" : "keywords", query.trimmed()},
                                  {"type", "1000"},
                                  {"offset", "0"},
                                  {"limit", QString::number(limit)}}),
                        QStringLiteral("搜索歌单"), callback);
        }
        if (reply)
            m_playlistSearchReplies.append(reply);
    }
}
void MusicApi::fetchRankings(std::function<void(QVariantList, QString)> done) {
    fetchRankings("netease", std::move(done));
}
void MusicApi::fetchRankings(const QString &s, std::function<void(QVariantList, QString)> done) {
    if (s == "netease") {
        fetchNeteaseRankings(std::move(done));
        return;
    }
    const int generation = ++m_rankingsGeneration;
    if (m_rankingsReply)
        m_rankingsReply->abort();
    if (s == "kuwo") {
        QVariantList rows;
        for (const auto &p : QList<QPair<QString, QString>>{{"16", QStringLiteral("热歌榜")},
                                                            {"17", QStringLiteral("新歌榜")},
                                                            {"93", QStringLiteral("飙升榜")}}) {
            auto row = summary(s, "ranking", p.first, p.second);
            row["description"] = p.first == "16"   ? QStringLiteral("近期热门音乐")
                                 : p.first == "17" ? QStringLiteral("发现最新上线的歌曲")
                                                   : QStringLiteral("关注热度上升的歌曲");
            row["trackCount"] = -1;
            rows.append(row);
        }
        done(rows, {});
        return;
    }
    if (s != "tencent") {
        done({}, QStringLiteral("榜单平台无效。"));
        return;
    }
    m_rankingsReply =
        qqRequest("music.musicToplist.Toplist", "GetAll", {},
                  [this, generation, done](QByteArray bytes, QString error) {
                      if (generation != m_rankingsGeneration)
                          return;
                      QVariantList rows;
                      QSet<QString> seen;
                      if (error.isEmpty()) {
                          const auto data = qqData(bytes, "req_0", error);
                          if (error.isEmpty() && !data.value("group").isArray())
                              error = QStringLiteral("QQ 榜单响应缺少分组。");
                          if (error.isEmpty())
                              for (const auto &g : data.value("group").toArray())
                                  for (const auto &v : g.toObject().value("toplist").toArray()) {
                                      const auto o = v.toObject();
                                      const auto id = numericId(o.value("topId"));
                                      const auto name = o.value("title").toString();
                                      if (id.isEmpty() || name.trimmed().isEmpty() || seen.contains(id))
                                          continue;
                                      seen.insert(id);
                                      auto row = summary("tencent", "ranking", id, name);
                                      row["description"] = CatalogCodec::descriptionText(o.value("intro").toString());
                                      row["trackCount"] = count(o.value("totalNum"));
                                      row["coverUrl"] = o.value("frontPicUrl").toString();
                                      row["updateTime"] = o.value("updateTime").toVariant();
                                      row["updateFrequency"] = o.value("period").toString();
                                      rows.append(row);
                                  }
                      }
                      done(rows, error);
                  });
}
struct MusicApi::CatalogFetch {
    QString source, kind, id;
    int generation = 0, page = 0, offset = 0;
    qint64 total = -1;
    QVariantMap playlist;
    QVariantList tracks;
    QSet<QString> seen;
    QSet<QByteArray> pages;
    QStringList warnings;
    std::function<void(QVariantMap, QString)> done;
};
void MusicApi::fetchCatalogPlaylist(const QString &s, const QString &kind, const QString &id,
                                    std::function<void(QVariantMap, QString)> done) {
    bool valid = false;
    const auto resource = id.toLongLong(&valid);
    if (!valid || resource <= 0) {
        done({}, QStringLiteral("歌单 ID 无效。"));
        return;
    }
    const auto state = std::make_shared<CatalogFetch>();
    state->source = s;
    state->kind = kind;
    state->id = id;
    state->generation = m_sourceGeneration;
    state->done = std::move(done);
    fetchCatalogPage(state);
}
void MusicApi::fetchCatalogPage(const std::shared_ptr<CatalogFetch> &state) {
    if (state->generation != m_sourceGeneration) {
        state->done({}, QStringLiteral("API 来源已更改，请重新打开歌单。"));
        return;
    }
    const auto finish = [state](QString failure) {
        if (state->playlist.isEmpty()) {
            state->done({}, failure.isEmpty() ? QStringLiteral("未取得有效歌单详情。") : failure);
            return;
        }
        if (!failure.isEmpty())
            state->warnings.append(failure);
        if (state->total < 0)
            state->warnings.append(QStringLiteral("服务未提供总数，无法确认是否读取完整。"));
        else if (state->tracks.size() < state->total)
            state->warnings.append(QStringLiteral("歌单共 %1 首，已读取 %2 首；当前内容不是完整歌单。")
                                       .arg(state->total)
                                       .arg(state->tracks.size()));
        state->playlist["trackCount"] = state->total < 0 ? state->tracks.size() : state->total;
        state->playlist["tracks"] = state->tracks;
        state->playlist["warning"] = state->warnings.join('\n');
        state->done(state->playlist, {});
    };
    const auto callback = [this, state, finish](QByteArray bytes, QString error) {
        if (state->generation != m_sourceGeneration) {
            state->done({}, QStringLiteral("API 来源已更改，请重新打开歌单。"));
            return;
        }
        QJsonObject data, meta;
        QJsonValue raw;
        bool hasMore = true;
        if (error.isEmpty()) {
            if (state->source == "tencent") {
                data = qqData(bytes, "req_0", error);
                if (state->kind == "ranking") {
                    meta = data.value("data").toObject();
                    raw = data.value("songInfoList");
                    state->total = count(meta.value("totalNum"), state->total);
                } else {
                    meta = data.value("dirinfo").toObject();
                    raw = data.value("songlist");
                    state->total = count(data.value("total_song_num"), state->total);
                    if (data.contains("hasmore"))
                        hasMore = data.value("hasmore").isBool() ? data.value("hasmore").toBool()
                                                                 : count(data.value("hasmore"), 0) != 0;
                }
            } else {
                if (state->kind == "ranking") {
                    const auto doc = CatalogCodec::decodeRanking(bytes, error);
                    if (error.isEmpty() && doc.object().value("code").toInt(-1) != 200)
                        error = QStringLiteral("酷我榜单请求未成功。");
                    data = doc.object().value("data").toObject();
                } else {
                    QJsonParseError p;
                    const auto doc = QJsonDocument::fromJson(bytes, &p);
                    if (p.error != QJsonParseError::NoError || !doc.isObject() ||
                        doc.object().value("result").toString() != "ok")
                        error = QStringLiteral("酷我歌单请求未成功或格式无效。");
                    data = doc.object();
                }
                meta = data;
                raw = data.value("musiclist");
                state->total = count(data.value("total"), state->total);
            }
            if (error.isEmpty() && !raw.isArray())
                error = QStringLiteral("歌单详情缺少歌曲列表。");
            const QString identityField =
                state->kind == "ranking" ? QStringLiteral("topId") : QStringLiteral("id");
            if (error.isEmpty() && state->source == "tencent" && meta.contains(identityField) &&
                numericId(meta.value(identityField)) != state->id)
                error = QStringLiteral("服务返回的资源身份与请求不一致。");
        }
        if (!error.isEmpty()) {
            finish(error);
            return;
        }
        if (state->playlist.isEmpty()) {
            const auto name = firstText(meta, {"title", "name", "dissname", "dirName"});
            if (name.trimmed().isEmpty()) {
                finish(QStringLiteral("歌单详情缺少名称。"));
                return;
            }
            state->playlist = summary(state->source, state->kind, state->id, name);
            state->playlist["description"] =
                CatalogCodec::descriptionText(firstText(meta, {"desc", "intro", "info", "description"}));
            state->playlist["coverUrl"] = firstText(meta, {"picurl", "frontPicUrl", "pic"});
            state->playlist["creator"] = state->source == "tencent"
                                             ? meta.value("creator").toObject().value("nick").toString(
                                                   meta.value("host_nick").toString())
                                             : meta.value("uname").toString();
            state->playlist["updateTime"] = firstText(meta, {"updateTime", "releaseDate"});
        }
        const auto songs = raw.toArray();
        const auto fingerprint = QCryptographicHash::hash(QJsonDocument(songs).toJson(QJsonDocument::Compact),
                                                          QCryptographicHash::Sha256);
        if (!songs.isEmpty() && state->pages.contains(fingerprint)) {
            finish(QStringLiteral("服务重复返回同一页，已停止读取。"));
            return;
        }
        state->pages.insert(fingerprint);
        int invalid = 0, duplicates = 0;
        for (int i = 0; i < songs.size(); ++i) {
            auto item = track(state->source, songs[i].toObject());
            const auto id = item.value("id").toString();
            if (id.isEmpty()) {
                ++invalid;
                continue;
            }
            if (state->seen.contains(id)) {
                ++duplicates;
                continue;
            }
            state->seen.insert(id);
            if (state->tracks.size() < trackLimit) {
                item["playlistPosition"] = state->offset + i + 1;
                state->tracks.append(item);
            }
        }
        if (invalid)
            state->warnings.append(QStringLiteral("本页 %1 首歌曲身份无效，已跳过。").arg(invalid));
        if (duplicates)
            state->warnings.append(QStringLiteral("本页 %1 首重复歌曲，已保留首次位置。").arg(duplicates));
        state->offset += songs.size();
        ++state->page;
        if (songs.isEmpty() || !hasMore || (state->total >= 0 && state->offset >= state->total)) {
            finish({});
            return;
        }
        if (state->tracks.size() >= trackLimit || state->offset >= trackLimit || state->page >= maxPages) {
            finish(QStringLiteral("达到单次读取上限（最多 2000 首或 40 页）。"));
            return;
        }
        if (state->total < 0 && songs.size() < pageSize) {
            finish({});
            return;
        }
        fetchCatalogPage(state);
    };
    if (state->source == "tencent") {
        if (state->kind == "ranking")
            qqRequest("music.musicToplist.Toplist", "GetDetail",
                      {{"topId", state->id.toLongLong()},
                       {"offset", state->offset},
                       {"num", pageSize},
                       {"withTags", true}},
                      callback);
        else
            qqRequest("music.srfDissInfo.DissInfo", "CgiGetDiss",
                      {{"disstid", state->id.toLongLong()},
                       {"dirid", 0},
                       {"tag", true},
                       {"song_begin", state->offset},
                       {"song_num", pageSize},
                       {"userinfo", true},
                       {"orderlist", true},
                       {"onlysonglist", false}},
                      callback);
    } else if (state->kind == "ranking")
        get(rankingUrl(m_endpoints.kuwoRanking, state->id, state->page), QStringLiteral("读取酷我榜单"),
            callback);
    else
        get(queryUrl(m_endpoints.kuwoPlaylistDetail, {{"op", "getlistinfo"},
                                                      {"pid", state->id},
                                                      {"pn", QString::number(state->page)},
                                                      {"rn", QString::number(pageSize)},
                                                      {"encode", "utf8"},
                                                      {"keyset", "pl2012"},
                                                      {"identity", "kuwo"},
                                                      {"pcmp4", "1"},
                                                      {"vipver", "MUSIC_9.0.5.0_W1"},
                                                      {"newver", "1"}}),
            QStringLiteral("读取酷我歌单"), callback);
}
