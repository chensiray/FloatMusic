// Opt-in live integration probe. Never used by the app or automatic unit tests.
#include <QCoreApplication>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QUrlQuery>
#include <QEventLoop>
#include <QTimer>
#include <QElapsedTimer>
#include <QDateTime>
#include <QFile>
#include <QSaveFile>
#include <QTextStream>
#include <QMediaPlayer>
#include <QAudioOutput>
#include <QAudioBufferOutput>
#include <QAudioBuffer>
#include <QMediaMetaData>

static QUrl endpoint(const QString &base, const QList<QPair<QString, QString>> &params = {}) {
    QUrl u(base); QUrlQuery q;
    for (const auto &p : params) q.addQueryItem(p.first, p.second);
    u.setQuery(q); return u;
}
static QString safeUrl(QUrl u) { u.setQuery(QString()); u.setFragment(QString()); u.setUserInfo(QString()); return u.toString(); }
struct Result { QJsonObject row, json; QByteArray body; QUrl finalUrl; };

class Probe {
public:
    QNetworkAccessManager network;
    QJsonArray rows;
    QString output;
    QString mode;
    void save() {
        QSaveFile f(output);
        if (!f.open(QIODevice::WriteOnly)) qFatal("Cannot open result file");
        f.write(QJsonDocument(QJsonObject{{"timestamp", QDateTime::currentDateTimeUtc().toString(Qt::ISODate)},
            {"qtVersion", QT_VERSION_STR}, {"networkMode", mode}, {"results", rows}}).toJson());
        if (!f.commit()) qFatal("Cannot save result file");
    }
    void add(const QJsonObject &r) {
        rows.append(r); save();
        QTextStream(stdout) << QJsonDocument(r).toJson(QJsonDocument::Compact) << Qt::endl;
    }
    Result get(const QString &name, const QUrl &url, const QString &kind, bool head = false, bool range = false) {
        QNetworkRequest req(url);
        req.setTransferTimeout(12000);
        req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
        req.setRawHeader("User-Agent", "FloatMusic-API-Probe/0.1");
        if (name.contains("mobile-ua")) req.setRawHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
        if (range) req.setRawHeader("Range", "bytes=0-65535");
        auto *reply = head ? network.head(req) : network.get(req);
        QEventLoop loop; QTimer timeout; timeout.setSingleShot(true);
        QElapsedTimer elapsed; elapsed.start();
        QByteArray body; bool capped = false, timedOut = false;
        const qint64 cap = range ? 65536 : 2 * 1024 * 1024;
        QObject::connect(reply, &QIODevice::readyRead, &loop, [&] {
            body += reply->readAll();
            if (body.size() > cap) { body.truncate(cap); capped = true; reply->abort(); }
        });
        QObject::connect(&timeout, &QTimer::timeout, &loop, [&] { timedOut = true; reply->abort(); loop.quit(); });
        QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
        timeout.start(15000); loop.exec(); timeout.stop(); body += reply->readAll();
        Result r; r.body = body; r.finalUrl = reply->url();
        r.row = {{"name", name}, {"endpoint", safeUrl(url)}, {"method", head ? "HEAD" : "GET"},
            {"httpStatus", reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt()},
            {"elapsedMs", elapsed.elapsed()}, {"finalEndpoint", safeUrl(reply->url())},
            {"contentType", QString::fromLatin1(reply->rawHeader("Content-Type"))},
            {"contentLength", QString::fromLatin1(reply->rawHeader("Content-Length"))},
            {"contentRange", QString::fromLatin1(reply->rawHeader("Content-Range"))},
            {"bodyBytes", body.size()}, {"sampleCapped", capped}, {"timedOut", timedOut},
            {"networkError", reply->error() == QNetworkReply::NoError || (capped && range) ? QString() : reply->errorString()}};
        QJsonParseError error;
        const auto doc = QJsonDocument::fromJson(body, &error);
        r.json = doc.object();
        if (kind != "audio" && kind != "html") r.row["jsonObject"] = doc.isObject();
        if (r.json.contains("code")) r.row["businessCode"] = r.json["code"];
        if (r.json.contains("message")) r.row["message"] = r.json["message"].toString().left(200);
        if (r.json.contains("msg")) r.row["message"] = r.json["msg"].toString().left(200);
        if (kind == "songs" || kind == "playlists") {
            const auto result = r.json["result"].toObject();
            const auto items = result[kind].toArray(); QJsonArray samples;
            for (const auto &v : items) {
                const auto item = v.toObject();
                samples.append(QJsonObject{{"id", item["id"]}, {"name", item["name"]}});
            }
            r.row["count"] = items.size(); r.row["samples"] = samples;
            r.row["total"] = result[kind == "songs" ? "songCount" : "playlistCount"];
            r.row["schemaValid"] = r.json["code"].toInt() == 200 && !items.isEmpty();
        } else if (kind == "lyric") {
            auto lyric = r.json["lrc"].toObject()["lyric"].toString();
            r.row["lyricChars"] = lyric.size(); r.row["hasTimestamps"] = lyric.contains(QChar('['));
            r.row["translationChars"] = r.json["tlyric"].toObject()["lyric"].toString().size();
            r.row["schemaValid"] = r.json["code"].toInt() == 200 && (r.json.contains("lrc") || r.json.contains("nolyric") || r.json.contains("uncollected"));
        } else if (kind == "detail") {
            auto songs = r.json["songs"].toArray(); r.row["count"] = songs.size();
            if (!songs.isEmpty()) {
                auto s = songs.first().toObject(); r.row["songId"] = s["id"]; r.row["songName"] = s["name"];
                r.row["durationMs"] = s.contains("dt") ? s["dt"] : s["duration"];
                r.row["hasArtists"] = s.contains("ar") || s.contains("artists");
            }
            r.row["schemaValid"] = r.json["code"].toInt() == 200 && !songs.isEmpty();
        } else if (kind == "charts") {
            auto list = r.json["list"].toArray(); r.row["count"] = list.size();
            r.row["schemaValid"] = r.json["code"].toInt() == 200 && !list.isEmpty();
        } else if (kind == "playlist") {
            auto p = r.json["playlist"].toObject();
            r.row["playlistId"] = p["id"]; r.row["playlistName"] = p["name"]; r.row["trackCount"] = p["trackCount"];
            r.row["trackIdsCount"] = p["trackIds"].toArray().size(); r.row["tracksCount"] = p["tracks"].toArray().size();
            r.row["schemaValid"] = r.json["code"].toInt() == 200 && p.contains("id") && !p["trackIds"].toArray().isEmpty();
        } else if (kind == "html") {
            const auto marker = body.indexOf("window.REDUX_STATE");
            r.row["reduxMarkerFound"] = marker >= 0;
            // QJsonDocument only: never evaluate downloaded JavaScript/Lua.
            bool extracted = false;
            if (marker >= 0) {
                int start = body.indexOf('{', marker);
                for (int end = start + 1; start >= 0 && end < body.size(); ++end) {
                    if (body[end] != '}') continue;
                    const auto state = QJsonDocument::fromJson(body.mid(start, end - start + 1));
                    if (!state.isObject()) continue;
                    const auto home = state.object()["Home"].toObject();
                    r.row["homeCode"] = home["code"]; r.row["recommendationCount"] = home["result"].toArray().size();
                    extracted = home["code"].toInt() == 200 && !home["result"].toArray().isEmpty(); break;
                }
            }
            r.row["schemaValid"] = extracted;
        } else if (kind == "url") {
            const QUrl plain(QString::fromUtf8(body).trimmed());
            const bool valid = plain.isValid() && !plain.host().isEmpty() && (plain.scheme() == "https" || plain.scheme() == "http");
            r.row["plainUrlValid"] = valid;
            if (valid) r.row["returnedUrlHost"] = plain.host();
            else if (!doc.isObject()) r.row["responsePreview"] = QString::fromUtf8(body).left(160);
        } else if (kind == "audio" && !head) {
            r.row["signatureHex"] = QString::fromLatin1(body.left(16).toHex());
            if (body.startsWith("fLaC") && body.size() >= 26) {
                quint64 packed = 0;
                for (int i = 18; i < 26; ++i) packed = (packed << 8) | quint8(body[i]);
                r.row["flacSampleRate"] = int((packed >> 44) & 0xfffff);
                r.row["flacChannels"] = int((packed >> 41) & 7) + 1;
                r.row["flacBitsPerSample"] = int((packed >> 36) & 31) + 1;
                r.row["flacTotalSamples"] = qint64(packed & 0xfffffffffULL);
            }
        }
        reply->deleteLater(); add(r.row); return r;
    }
    void playback(const QString &name, const QUrl &url) {
        QMediaPlayer player; QAudioOutput audio; QAudioBufferOutput buffers;
        audio.setMuted(true); player.setAudioOutput(&audio); player.setAudioBufferOutput(&buffers);
        QEventLoop loop; QTimer timeout; timeout.setSingleShot(true);
        QJsonObject row{{"name", name}, {"kind", "qt-playback"}, {"muted", true}, {"sourceHost", url.host()}};
        int decoded = 0, phase = 0; qint64 before = 0, seekTarget = 0, maxPosition = 0;
        bool pauseOk = false, seekOk = false, resumed = false;
        QObject::connect(&buffers, &QAudioBufferOutput::audioBufferReceived, &loop, [&](const QAudioBuffer &b) {
            if (!b.isValid()) return;
            ++decoded; row["sampleRate"] = b.format().sampleRate(); row["channels"] = b.format().channelCount();
        });
        QObject::connect(&player, &QMediaPlayer::errorOccurred, &loop, [&](QMediaPlayer::Error, const QString &s) { row["error"] = s; loop.quit(); });
        QObject::connect(&player, &QMediaPlayer::positionChanged, &loop, [&](qint64 pos) {
            maxPosition = qMax(maxPosition, pos);
            if (phase == 0 && pos >= 800 && player.duration() > 3000 && decoded > 0) {
                phase = 1; player.pause(); before = player.position();
                QTimer::singleShot(400, &loop, [&] {
                    pauseOk = player.playbackState() == QMediaPlayer::PausedState && qAbs(player.position() - before) < 120;
                    seekTarget = qMin<qint64>(10000, player.duration() / 2);
                    phase = 2; player.setPosition(seekTarget); player.play();
                });
            } else if (phase == 2 && pos >= seekTarget + 400) {
                seekOk = true; resumed = player.playbackState() == QMediaPlayer::PlayingState; loop.quit();
            }
        });
        QObject::connect(&timeout, &QTimer::timeout, &loop, [&] { row["timedOut"] = true; loop.quit(); });
        timeout.start(22000); player.setSource(url); player.play(); loop.exec(); timeout.stop();
        row["decodedBuffers"] = decoded; row["durationMs"] = player.duration(); row["maxPositionMs"] = maxPosition;
        row["seekable"] = player.isSeekable(); row["pausePassed"] = pauseOk; row["seekPassed"] = seekOk; row["resumePassed"] = resumed;
        row["passed"] = decoded > 0 && pauseOk && seekOk && resumed;
        row["audioBitRate"] = player.metaData().value(QMediaMetaData::AudioBitRate).toInt();
        row["audioCodec"] = player.metaData().stringValue(QMediaMetaData::AudioCodec);
        player.stop(); add(row);
    }
};

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    const auto args = app.arguments();
    auto arg = [&](const QString &key, const QString &fallback) { int i = args.indexOf(key); return i >= 0 && i + 1 < args.size() ? args[i + 1] : fallback; };
    Probe p; p.output = arg("--out", "live-api-results.json"); p.mode = arg("--proxy", "direct");
    if (p.mode == "direct") p.network.setProxy(QNetworkProxy::NoProxy);
    else { const QUrl u(p.mode); p.network.setProxy(QNetworkProxy(QNetworkProxy::HttpProxy, u.host(), u.port(7897))); }
    const QString keyword = arg("--keyword", QString::fromUtf8("纯音乐"));
    const QString seed = arg("--song", "347230");
    if (args.contains("--diagnostics")) {
        p.get("playlist-search-https", endpoint("https://music.163.com/api/search/get", {{"s", keyword}, {"type", "1000"}, {"limit", "3"}}), "playlists");
        p.get("toplist-https", QUrl("https://music.163.com/api/toplist"), "charts");
        p.get("recommended-mobile-ua", QUrl("https://y.music.163.com/m/"), "html");
        p.get("detail-v1-batch", endpoint("https://music.163.com/api/v1/song/detail", {{"ids", "[347230,139774]"}}), "detail");
        p.get("invalid-song-id", endpoint("https://music.163.com/api/v1/song/detail", {{"ids", "[0]"}}), "detail");
        p.get("invalid-playlist-id", endpoint("https://music.163.com/api/v3/playlist/detail", {{"id", "0"}}), "playlist");
        auto english = p.get("english-search", endpoint("https://music.163.com/api/search/get", {{"s", "Yesterday"}, {"type", "1"}, {"limit", "1"}}), "songs");
        auto results = english.json["result"].toObject()["songs"].toArray();
        if (!results.isEmpty()) p.get("translated-lyric", endpoint("https://music.163.com/api/song/lyric", {{"id", QString::number(results.first().toObject()["id"].toInteger())}, {"tv", "-1"}, {"lv", "-1"}}), "lyric");
        return 0;
    }
    if (!args.contains("--quality-only")) {
    const auto searchParams = QList<QPair<QString, QString>>{{"s", keyword}, {"type", "1"}, {"offset", "0"}, {"total", "true"}, {"limit", "3"}};
    auto search = p.get("song-search-http", endpoint("http://music.163.com/api/search/get", searchParams), "songs");
    p.get("song-search-https", endpoint("https://music.163.com/api/search/get", searchParams), "songs");
    p.get("playlist-search-http", endpoint("http://music.163.com/api/search/get", {{"s", keyword}, {"type", "1000"}, {"total", "true"}, {"limit", "3"}}), "playlists");
    auto charts = p.get("toplist-http", QUrl("http://music.163.com/api/toplist"), "charts");
    auto chartList = charts.json["list"].toArray();
    QString playlistId = chartList.isEmpty() ? "19723756" : QString::number(chartList.first().toObject()["id"].toInteger());
    p.get("playlist-detail-v3", endpoint("https://music.163.com/api/v3/playlist/detail", {{"id", playlistId}}), "playlist");
    p.get("recommended-html", QUrl("https://y.music.163.com/m/"), "html");
    QStringList ids{seed};
    auto songs = search.json["result"].toObject()["songs"].toArray();
    if (!songs.isEmpty()) { QString found = QString::number(songs.first().toObject()["id"].toInteger()); if (found != seed) ids << found; }
    for (const auto &id : ids) {
        p.get("song-detail-" + id, endpoint("https://music.163.com/api/song/detail", {{"ids", "[" + id + "]"}}), "detail");
        p.get("song-detail-v1-" + id, endpoint("https://music.163.com/api/v1/song/detail", {{"ids", "[" + id + "]"}}), "detail");
        p.get("lyric-" + id, endpoint("https://music.163.com/api/song/lyric", {{"id", id}, {"tv", "-1"}, {"lv", "-1"}}), "lyric");
        auto url = endpoint("http://music.163.com/song/media/outer/url", {{"id", id + ".mp3"}});
        p.get("outer-head-" + id, url, "audio", true);
        auto sample = p.get("outer-range-" + id, url, "audio", false, true);
        if (sample.row["httpStatus"].toInt() / 100 == 2 && sample.row["contentType"].toString().startsWith("audio/"))
            p.playback("outer-playback-" + id, url);
    }
    }
    for (const auto &level : {"standard", "higher", "exhigh", "lossless", "hires"}) {
        auto r = p.get(QString("byfuns-") + level, endpoint("https://www.byfuns.top/api/1/", {{"id", seed}, {"level", level}}), "url");
        if (r.row["plainUrlValid"].toBool()) {
            QUrl u(QString::fromUtf8(r.body).trimmed());
            p.get(QString("byfuns-range-") + level, u, "audio", false, true);
            p.playback(QString("byfuns-playback-") + level, u);
            if (QString(level) == "standard") {
                p.get("lua-appended-mp3", QUrl(u.toString() + ".mp3"), "audio", false, true);
            }
        }
    }
    return 0; // Probe completion, not a claim that every remote endpoint passed.
}
