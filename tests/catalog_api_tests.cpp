#include <QtTest>
#include "catalogcodec.h"
#include "musicapi.h"
#include <QTcpServer>
#include <QTcpSocket>
#include <QUrlQuery>
#include <QTimer>
#include <QSettings>
#include <QCryptographicHash>
class CatalogFixture : public QTcpServer {
  public:
    QList<QByteArray> requests;
    bool failSecondPage = false, repeatPage = false, badCode = false, hugePage = false, wrongId = false;
    int rankDelay = 0, songCount = 15, qqSecondDelay = 0, playlistCount = 100;
    bool qqPlaylistBounded = false, qqPlaylistSecondFailure = false, qqPlaylistDuplicate = false,
         qqPlaylistDropFirst = false;
    bool qqBounded = false, qqSecondFailure = false, qqDuplicate = false, qqInvalid = false;
    bool oversized = false;
    QString description;
    CatalogFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *s = nextPendingConnection()) {
                connect(s, &QTcpSocket::disconnected, s, &QObject::deleteLater);
                connect(s, &QTcpSocket::readyRead, s, [this, s] {
                    auto req = s->property("req").toByteArray() + s->readAll();
                    s->setProperty("req", req);
                    const auto split = req.indexOf("\r\n\r\n");
                    if (split < 0 || s->property("sent").toBool())
                        return;
                    int len = 0;
                    for (auto line : req.left(split).split('\n'))
                        if (line.toLower().startsWith("content-length:"))
                            len = line.mid(15).trimmed().toInt();
                    if (req.size() < split + 4 + len)
                        return;
                    s->setProperty("sent", true);
                    requests.append(req);
                    const QUrl url(QString::fromUtf8(req.split(' ').value(1)));
                    const QUrlQuery query(url);
                    const auto json = QJsonDocument::fromJson(req.mid(split + 4)).object();
                    QByteArray bytes;
                    int status = 200, delay = 0;
                    if (url.path() == "/qq") {
                        const QString key =
                            json.contains("req_0") ? QStringLiteral("req_0") : json.keys().first();
                        const auto call = json.value(key).toObject();
                        const auto p = call.value("param").toObject();
                        const auto method = call.value("method").toString();
                        QJsonObject data;
                        if (p.value("query").toString() == "slow")
                            delay = 150;
                        if (method == "DoSearchForQQMusicDesktop") {
                            QJsonArray lists;
                            const int perPage = p.value("num_per_page").toInt(),
                                      page = p.value("page_num").toInt();
                            const int start = qqPlaylistBounded ? (page - 1) * perPage : 0;
                            const int end =
                                qqPlaylistBounded
                                    ? (perPage > 50 ? start : qMin(playlistCount, start + perPage))
                                    : 15;
                            for (int i = start; i < end; ++i) {
                                if (qqPlaylistDropFirst && i == 29)
                                    continue;
                                lists.append(QJsonObject{
                                    {"dissid",
                                     QString::number(100 + (qqPlaylistDuplicate && i == 30 ? 29 : i))},
                                    {"dissname", "Q" + QString::number(i)},
                                    {"introduction", description},
                                    {"song_count", 2},
                                    {"creator", QJsonObject{{"name", "maker"}}}});
                            }
                            data = {{"body", QJsonObject{{"songlist", QJsonObject{{"list", lists}}}}}};
                            if (qqPlaylistBounded)
                                data.insert("meta",
                                            QJsonObject{{"nextpage", end >= playlistCount ? -1 : page + 1},
                                                        {"sum", playlistCount}});
                            if (page == 2) {
                                if (qqPlaylistSecondFailure)
                                    status = 503;
                                delay = qqSecondDelay;
                            }
                        } else if (method == "GetAll") {
                            delay = rankDelay;
                            data = {{"group",
                                     QJsonArray{QJsonObject{
                                         {"toplist",
                                          QJsonArray{
                                              QJsonObject{{"topId", 26}, {"title", "Hot"}, {"totalNum", 3}, {"intro", description}},
                                              QJsonObject{{"topId", 62}, {"title", "Rise"}}}}}}}};
                        } else {
                            const int offset = method == "GetDetail" ? p.value("offset").toInt()
                                                                     : p.value("song_begin").toInt();
                            QJsonArray songs;
                            if (offset == 0 || repeatPage)
                                songs = {QJsonObject{{"mid", "AbC1"},
                                                     {"name", "First"},
                                                     {"singer", QJsonArray{QJsonObject{{"name", "Artist"}}}}},
                                         QJsonObject{{"mid", "bad/id"}, {"name", "Invalid"}}};
                            else
                                songs = {QJsonObject{{"mid", "AbC1"}, {"name", "duplicate"}},
                                         QJsonObject{{"mid", "aBc2"}, {"name", "Second"}}};
                            if (offset > 0 && failSecondPage)
                                status = 503;
                            if (hugePage) {
                                songs = QJsonArray{};
                                for (int i = 0; i < 2500; ++i)
                                    songs.append(
                                        QJsonObject{{"mid", "M" + QString::number(i)}, {"name", "Huge"}});
                            }
                            if (method == "GetDetail")
                                data = {{"data", QJsonObject{{"title", "Rank"}, {"totalNum", 4}, {"intro", description}}},
                                        {"songInfoList", songs}};
                            else
                                data = {{"dirinfo", QJsonObject{{"id", wrongId ? 101 : 100},
                                                                {"title", "Playlist"},
                                                                {"desc", description.isEmpty() ? QString("Details") : description}}},
                                        {"total_song_num", hugePage ? 2500 : 4},
                                        {"hasmore", offset == 0 ? 1 : 0},
                                        {"songlist", songs}};
                        }
                        bytes =
                            QJsonDocument(
                                QJsonObject{{"code", 0},
                                            {key, QJsonObject{{"code", badCode ? 1 : 0}, {"data", data}}}})
                                .toJson(QJsonDocument::Compact);
                    } else if (url.path() == "/music/tencent/search/song") {
                        QJsonArray songs;
                        const int limit = query.queryItemValue("limit").toInt(),
                                  page = query.queryItemValue("page").toInt();
                        const int start = qqBounded ? (page - 1) * limit : 0;
                        const int end =
                            qqBounded ? (limit > 50 ? start : qMin(songCount, start + limit)) : songCount;
                        for (int i = start; i < end; ++i) {
                            const auto id = qqInvalid && i == 0
                                                ? QString("bad/id")
                                                : "MID" + QString::number(qqDuplicate && i == 50 ? 49 : i);
                            songs.append(QJsonObject{{"songMID", id}, {"title", "Q" + QString::number(i)}});
                        }
                        if (page == 2) {
                            delay = qqSecondDelay;
                            if (qqSecondFailure)
                                status = 503;
                        }
                        bytes =
                            QJsonDocument(QJsonObject{{"code", 0}, {"data", QJsonObject{{"list", songs}}}})
                                .toJson();
                    } else if (url.path() == "/ourcraft") {
                        QJsonArray songs;
                        for (int i = 0; i < songCount; ++i)
                            songs.append(QJsonObject{{"id", i + 1}, {"name", "K" + QString::number(i)}});
                        bytes = QJsonDocument(QJsonObject{{"ok", true}, {"songs", songs}}).toJson();
                    } else if (url.path() == "/kw-search") {
                        const auto encoded = QJsonDocument(QJsonArray{description}).toJson(QJsonDocument::Compact);
                        const auto descriptionValue = encoded.mid(1, encoded.size() - 2);
                        bytes = "{'TOTAL':'15','abslist':[";
                        for (int i = 0; i < 15; ++i) {
                            if (i)
                                bytes += ',';
                            bytes += "{'playlistid':'" + QByteArray::number(200 + i) + "','name':\"John's " +
                                     QByteArray::number(i) + "\",'nickname':'KW','songnum':'2','intro':" + descriptionValue + "}";
                        }
                        bytes += "]}";
                    } else if (url.path() == "/kw-detail") {
                        const int pn = query.queryItemValue("pn").toInt();
                        QJsonArray songs =
                            pn == 0
                                ? QJsonArray{QJsonObject{{"id", "42"}, {"name", "Song"}, {"artist", "KW"}},
                                             QJsonObject{{"id", "bad"}, {"name", "Invalid"}}}
                                : QJsonArray{QJsonObject{{"id", "43"}, {"name", "Next"}}};
                        bytes = QJsonDocument(QJsonObject{{"result", "ok"},
                                                          {"title", "KW playlist"},
                                                          {"intro", description},
                                                          {"total", 3},
                                                          {"musiclist", songs}})
                                    .toJson();
                    } else if (url.path() == "/kw-rank") {
                        const auto cipher =
                            CatalogCodec::aesEncrypt(
                                QJsonDocument(
                                    QJsonObject{
                                        {"code", 200},
                                        {"data", QJsonObject{{"name", "KW rank"},
                                                             {"intro", description},
                                                             {"total", 1},
                                                             {"musiclist",
                                                              QJsonArray{QJsonObject{
                                                                  {"id", "72"}, {"name", "Rank track"}}}}}}})
                                    .toJson(QJsonDocument::Compact),
                                CatalogCodec::rankingKey())
                                .toBase64();
                        bytes = cipher.toPercentEncoding();
                    } else {
                        QJsonArray songs;
                        for (int i = 0; i < songCount; ++i)
                            songs.append(QJsonObject{{"id", i + 1}, {"name", "N" + QString::number(i)}});
                        bytes = QJsonDocument(
                                    QJsonObject{{"code", 200}, {"result", QJsonObject{{"songs", songs}}}})
                                    .toJson();
                    }
                    if (oversized)
                        bytes = QByteArray(2 * 1024 * 1024 + 1, 'x');
                    QPointer<QTcpSocket> safe(s);
                    auto send = [safe, bytes, status] {
                        if (!safe)
                            return;
                        safe->write("HTTP/1.1 " + QByteArray::number(status) +
                                    " Fixture\r\nSet-Cookie: private=value\r\nContent-Length: " +
                                    QByteArray::number(bytes.size()) + "\r\nConnection: close\r\n\r\n" +
                                    bytes);
                        safe->disconnectFromHost();
                    };
                    if (delay)
                        QTimer::singleShot(delay, s, send);
                    else
                        send();
                });
            }
        });
    }
    QString base() const {
        return "http://127.0.0.1:" + QString::number(serverPort());
    }
    MusicApi::Endpoints endpoints() const {
        MusicApi::Endpoints e;
        e.metadata = base();
        e.qqMusicu = base() + "/qq";
        e.kuwoPlaylistSearch = base() + "/kw-search";
        e.kuwoPlaylistDetail = base() + "/kw-detail";
        e.kuwoRanking = base() + "/kw-rank";
        e.timeoutMs = 1000;
        return e;
    }
};
class CatalogApiTests : public QObject {
    Q_OBJECT
  private slots:
    void initTestCase() {
        QCoreApplication::setOrganizationName("FloatMusicCatalogTests");
        QCoreApplication::setApplicationName("catalog-" + QUuid::createUuid().toString());
    }
    void combinedSongLimitDefaultsToThirty() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        auto e = f.endpoints();
        e.vkeys = f.base();
        e.ourcraft = f.base();
        MusicApi api(e);
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::results);
        api.search("many", {"netease"}, 10);
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.first()[0].toList().size(), 10);
    }
    void playlistMergeAndQqPostEnvelope() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::playlistResults);
        api.searchPlaylists("mix", {"kuwo", "tencent"}, 10);
        QTRY_COMPARE(spy.size(), 1);
        const auto rows = spy.first()[0].toList();
        QCOMPARE(rows.size(), 10);
        QVERIFY(spy.first()[1].toString().isEmpty());
        QCOMPARE(rows[0].toMap().value("id").toString(), QString("kuwo:playlist:200"));
        QCOMPARE(rows[1].toMap().value("id").toString(), QString("tencent:playlist:100"));
        QCOMPARE(rows[1].toMap().value("creator").toString(), QString("maker"));
        for (const auto &r : f.requests) {
            QVERIFY(!r.toLower().contains("\ncookie:"));
            if (r.startsWith("POST")) {
                const auto d = QJsonDocument::fromJson(r.mid(r.indexOf("\r\n\r\n") + 4)).object();
                QVERIFY(!d.contains("comm"));
                QVERIFY(d.contains("music.search.SearchCgiService"));
                QCOMPARE(d.value("music.search.SearchCgiService")
                             .toObject()
                             .value("param")
                             .toObject()
                             .value("num_per_page")
                             .toInt(),
                         10);
                QVERIFY(r.toLower().contains("referer: https://y.qq.com/"));
            }
        }
    }
    void descriptionsArePlainText_data() {
        QTest::addColumn<QString>("operation");
        QTest::addColumn<QString>("source");
        QTest::addColumn<int>("limit");
        QTest::newRow("qq-search") << QString("search") << QString("tencent") << 10;
        QTest::newRow("qq-search-paged") << QString("search") << QString("tencent") << 50;
        QTest::newRow("kw-search") << QString("search") << QString("kuwo") << 10;
        QTest::newRow("qq-ranking-catalog") << QString("rankings") << QString("tencent") << 10;
        QTest::newRow("qq-playlist") << QString("tencent:playlist:100") << QString("tencent") << 10;
        QTest::newRow("qq-ranking") << QString("tencent:ranking:26") << QString("tencent") << 10;
        QTest::newRow("kw-playlist") << QString("kuwo:playlist:200") << QString("kuwo") << 10;
        QTest::newRow("kw-ranking") << QString("kuwo:ranking:16") << QString("kuwo") << 10;
    }
    void descriptionsArePlainText() {
        QFETCH(QString, operation);
        QFETCH(QString, source);
        QFETCH(int, limit);
        CatalogFixture f;
        f.description = QStringLiteral(" <p>A &amp; B</p><p>Second&nbsp;line &#20013; &#x1F3B5; &lt;keep&gt;</p><img src='https://invalid.example/pixel'>Love < 3 &unknown; &#0;");
        f.qqPlaylistBounded = true;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        QString actual;
        if (operation == "search") {
            QSignalSpy spy(&api, &MusicApi::playlistResults);
            api.searchPlaylists("plain", {source}, limit);
            QTRY_COMPARE(spy.size(), 1);
            QVERIFY(spy.first()[1].toString().isEmpty());
            const auto rows = spy.first()[0].toList();
            QVERIFY(!rows.isEmpty());
            actual = rows.first().toMap().value("description").toString();
        } else if (operation == "rankings") {
            bool done = false;
            QString error;
            api.fetchRankings(source, [&](QVariantList rows, QString e) {
                error = e;
                if (!rows.isEmpty()) actual = rows.first().toMap().value("description").toString();
                done = true;
            });
            QTRY_VERIFY(done);
            QVERIFY2(error.isEmpty(), qPrintable(error));
        } else {
            bool done = false;
            QString error;
            api.fetchPlaylist(operation, [&](QVariantMap playlist, QString e) {
                actual = playlist.value("description").toString();
                error = e;
                done = true;
            });
            QTRY_VERIFY(done);
            QVERIFY2(error.isEmpty(), qPrintable(error));
        }
        QCOMPARE(actual, QStringLiteral("A & B\nSecond line 中 🎵 <keep>\nLove < 3 &unknown; &#0;"));
        for (const auto &request : f.requests)
            QVERIFY(!request.contains("invalid.example"));
    }
    void rankingsUseCorrectKindAndRoute() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        bool done = false;
        QVariantList rows;
        api.fetchRankings("tencent", [&](QVariantList v, QString e) {
            rows = v;
            done = true;
            QVERIFY(e.isEmpty());
        });
        QTRY_VERIFY(done);
        QCOMPARE(rows.size(), 2);
        QCOMPARE(rows[0].toMap().value("id").toString(), QString("tencent:ranking:26"));
        done = false;
        api.fetchRankings("kuwo", [&](QVariantList v, QString) {
            rows = v;
            done = true;
        });
        QTRY_VERIFY(done);
        QCOMPARE(rows.size(), 3);
        QCOMPARE(rows[2].toMap().value("resourceId").toString(), QString("93"));
        done = false;
        QVariantMap list;
        QString error;
        api.fetchPlaylist("kuwo:ranking:16", [&](QVariantMap v, QString e) {
            list = v;
            error = e;
            done = true;
        });
        QTRY_VERIFY(done);
        QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(list.value("tracks").toList()[0].toMap().value("id").toString(), QString("kuwo:72"));
    }
    void pagingUsesRawOffsetAndKeepsCase() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        bool done = false;
        QVariantMap list;
        QString error;
        api.fetchPlaylist("tencent:playlist:100", [&](QVariantMap v, QString e) {
            list = v;
            error = e;
            done = true;
        });
        QTRY_VERIFY(done);
        QVERIFY2(error.isEmpty(), qPrintable(error));
        const auto tracks = list.value("tracks").toList();
        QCOMPARE(tracks.size(), 2);
        QCOMPARE(tracks[0].toMap().value("songId").toString(), QString("AbC1"));
        QCOMPARE(tracks[1].toMap().value("songId").toString(), QString("aBc2"));
        QCOMPARE(tracks[1].toMap().value("playlistPosition").toInt(), 4);
        QVERIFY(!list.value("warning").toString().isEmpty());
        QCOMPARE(f.requests.size(), 2);
        const auto body =
            QJsonDocument::fromJson(f.requests[1].mid(f.requests[1].indexOf("\r\n\r\n") + 4)).object();
        QCOMPARE(body.value("req_0").toObject().value("param").toObject().value("song_begin").toInt(), 2);
    }
    void partialPageFailureAndStaleSearch() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.failSecondPage = true;
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        bool done = false;
        QVariantMap list;
        api.fetchPlaylist("tencent:playlist:100", [&](QVariantMap v, QString e) {
            list = v;
            done = true;
            QVERIFY(e.isEmpty());
        });
        QTRY_VERIFY(done);
        QCOMPARE(list.value("tracks").toList().size(), 1);
        QVERIFY(list.value("warning").toString().contains("503"));
        QSignalSpy spy(&api, &MusicApi::playlistResults);
        api.searchPlaylists("slow", {"tencent"}, 10);
        QTest::qWait(15);
        api.searchPlaylists("latest", {"kuwo"}, 10);
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.first()[0].toList()[0].toMap().value("source").toString(), QString("kuwo"));
        QTest::qWait(200);
        QCOMPARE(spy.size(), 1);
    }
    void businessErrorsRepeatPagesAndTrackCap() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        bool done = false;
        QString error;
        QVariantMap list;
        auto fetch = [&] {
            done = false;
            error.clear();
            api.fetchPlaylist("tencent:playlist:100", [&](QVariantMap v, QString e) {
                list = v;
                error = e;
                done = true;
            });
        };
        f.badCode = true;
        fetch();
        QTRY_VERIFY(done);
        QVERIFY(!error.isEmpty());
        QVERIFY(list.isEmpty());
        f.badCode = false;
        f.repeatPage = true;
        fetch();
        QTRY_VERIFY(done);
        QVERIFY(error.isEmpty());
        QCOMPARE(list.value("tracks").toList().size(), 1);
        QVERIFY(list.value("warning").toString().contains(QStringLiteral("重复")));
        f.repeatPage = false;
        f.hugePage = true;
        fetch();
        QTRY_VERIFY(done);
        QVERIFY(error.isEmpty());
        QCOMPARE(list.value("tracks").toList().size(), 2000);
        QVERIFY(!list.value("warning").toString().isEmpty());
        f.hugePage = false;
        f.wrongId = true;
        fetch();
        QTRY_VERIFY(done);
        QVERIFY(!error.isEmpty());
        QVERIFY(list.isEmpty());
    }
    void staleRankingsAndTimeout() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        f.rankDelay = 150;
        int callbacks = 0;
        api.fetchRankings("tencent", [&](QVariantList, QString) { ++callbacks; });
        QTest::qWait(15);
        api.fetchRankings("kuwo", [&](QVariantList v, QString e) {
            ++callbacks;
            QVERIFY(e.isEmpty());
            QCOMPARE(v.size(), 3);
        });
        QTest::qWait(220);
        QCOMPARE(callbacks, 1);
        auto e = f.endpoints();
        e.timeoutMs = 20;
        MusicApi timeoutApi(e);
        QSignalSpy spy(&timeoutApi, &MusicApi::playlistResults);
        timeoutApi.searchPlaylists("slow", {"tencent"}, 10);
        QTRY_COMPARE(spy.size(), 1);
        QVERIFY(!spy.first()[1].toString().isEmpty());
    }
    void kuwoPlaylistDetailHasRidAndLiveRankingWire() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        bool done = false;
        QVariantMap list;
        api.fetchPlaylist("kuwo:playlist:200", [&](QVariantMap v, QString e) {
            list = v;
            done = true;
            QVERIFY(e.isEmpty());
        });
        QTRY_VERIFY(done);
        QCOMPARE(list.value("tracks").toList().size(), 2);
        QCOMPARE(list.value("tracks").toList()[1].toMap().value("songId").toString(), QString("43"));
        done = false;
        api.fetchPlaylist("kuwo:ranking:16", [&](QVariantMap v, QString e) {
            list = v;
            done = true;
            QVERIFY(e.isEmpty());
        });
        QTRY_VERIFY(done);
        const QUrl u(QString::fromUtf8(f.requests.last().split(' ').value(1)));
        const QUrlQuery q(u);
        const auto data = q.queryItemValue("data", QUrl::FullyDecoded);
        const auto tm = q.queryItemValue("time");
        QCOMPARE(q.queryItemValue("appId"), QString("y67sprxhhpws"));
        QCOMPARE(q.queryItemValue("sign"),
                 QString::fromLatin1(
                     QCryptographicHash::hash(("y67sprxhhpws" + data + tm).toUtf8(), QCryptographicHash::Md5)
                         .toHex()
                         .toUpper()));
        QString error;
        const auto body =
            QJsonDocument::fromJson(CatalogCodec::aesDecrypt(QByteArray::fromBase64(data.toLatin1()),
                                                             CatalogCodec::rankingKey(), error))
                .object();
        QVERIFY(error.isEmpty());
        QCOMPARE(body.value("id").toString(), QString("16"));
        QCOMPARE(body.value("rn").toInt(), 100);
        QCOMPARE(body.value("pn").toInt(), 0);
        QCOMPARE(body.value("uid").toString(), QString());
    }
    void encryptedResponsePreservesLiteralPlus() {
        QString error;
        const QByteArray fixture =
            "QsrPX+VuDR5X4TJ7Rh3hrsEyFrJuuQrnk7g03n87l5bg9FHttMj+wvSep+SYMBiBFaZ5BE8G8K8aAkHCCHQ0rw==";
        const auto d = CatalogCodec::decodeRanking(fixture, error);
        QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(d.object().value("data").toObject().value("name").toString(),
                 QString("fixture + apostrophe's"));
        error.clear();
        QVERIFY(CatalogCodec::decodeRanking("%%%%", error).isNull());
        QVERIFY(!error.isEmpty());
    }
    void literalAllowsInertPythonValues() {
        QString error;
        const auto d = CatalogCodec::parseLiteral("{'a':True,'b':False,'c':None}", error);
        QVERIFY2(!d.isNull() && error.isEmpty(), qPrintable(error));
        QVERIFY(d.object().value("a").toBool());
        QVERIFY(!d.object().value("b").toBool());
        QVERIFY(d.object().value("c").isNull());
    }
    void songRoundRobinCountChoicesAndDefault() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.songCount = 120;
        auto e = f.endpoints();
        e.vkeys = f.base();
        e.ourcraft = f.base() + "/ourcraft";
        MusicApi api(e);
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::results);
        for (int limit : {10, 20, 30, 50, 100, -1}) {
            api.search("many", {"tencent", "kuwo", "netease"}, limit);
            QTRY_COMPARE(spy.size(), 1);
            const auto event = spy.takeFirst();
            QVERIFY(event[1].toString().isEmpty());
            const auto rows = event[0].toList();
            QCOMPARE(rows.size(), limit < 0 ? 30 : limit);
            QCOMPARE(rows[0].toMap().value("id").toString(), QString("tencent:MID0"));
            QCOMPARE(rows[1].toMap().value("id").toString(), QString("kuwo:1"));
            QCOMPARE(rows[2].toMap().value("id").toString(), QString("netease:1"));
        }
        api.search("many", {"netease", "kuwo"});
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.first()[0].toList().size(), 30);
        for (const auto &r : f.requests)
            QVERIFY(!r.toLower().contains("\ncookie:"));
    }
    void qqHundredUsesTwoBoundedPages() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.qqBounded = true;
        f.songCount = 100;
        auto e = f.endpoints();
        e.vkeys = f.base();
        MusicApi api(e);
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::results);
        api.search("many", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        const auto event = spy.takeFirst();
        QVERIFY(event[1].toString().isEmpty());
        const auto rows = event[0].toList();
        QCOMPARE(rows.size(), 100);
        QCOMPARE(rows[49].toMap().value("songId").toString(), QString("MID49"));
        QCOMPARE(rows[50].toMap().value("songId").toString(), QString("MID50"));
        QCOMPARE(rows[99].toMap().value("songId").toString(), QString("MID99"));
        QCOMPARE(f.requests.size(), 2);
        for (int i = 0; i < 2; ++i) {
            const QUrlQuery q(QUrl(QString::fromUtf8(f.requests[i].split(' ').value(1))));
            QCOMPARE(q.queryItemValue("limit"), QString("50"));
            QCOMPARE(q.queryItemValue("page"), QString::number(i + 1));
        }
        f.requests.clear();
        api.search("small", {"tencent"}, 30);
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.takeFirst()[0].toList().size(), 30);
        QCOMPARE(f.requests.size(), 1);
    }
    void qqHundredPartialFailureDedupeAndShortPage() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.qqBounded = true;
        f.songCount = 100;
        auto e = f.endpoints();
        e.vkeys = f.base();
        MusicApi api(e);
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::results);
        f.qqSecondFailure = true;
        api.search("partial", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        auto event = spy.takeFirst();
        QCOMPARE(event[0].toList().size(), 50);
        QVERIFY(event[1].toString().contains("503"));
        f.qqSecondFailure = false;
        f.qqDuplicate = true;
        f.qqInvalid = true;
        api.search("filtered", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        event = spy.takeFirst();
        QCOMPARE(event[0].toList().size(), 98);
        QVERIFY(event[1].toString().isEmpty());
        QSet<QString> seen;
        for (const auto &v : event[0].toList()) {
            const auto id = v.toMap().value("id").toString();
            QVERIFY(!seen.contains(id));
            seen.insert(id);
        }
        QCOMPARE(event[0].toList().last().toMap().value("songId").toString(), QString("MID99"));
        f.qqDuplicate = false;
        f.qqInvalid = false;
        f.songCount = 20;
        f.requests.clear();
        api.search("short", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.takeFirst()[0].toList().size(), 20);
        QCOMPARE(f.requests.size(), 1);
    }
    void qqHundredStaleSecondPageIsCancelled() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.qqBounded = true;
        f.songCount = 100;
        f.qqSecondDelay = 150;
        auto e = f.endpoints();
        e.vkeys = f.base();
        MusicApi api(e);
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::results);
        api.search("old", {"tencent"}, 100);
        QTRY_COMPARE(f.requests.size(), 2);
        api.search("new", {"netease"}, 10);
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.first()[0].toList().size(), 10);
        QCOMPARE(spy.first()[0].toList()[0].toMap().value("source").toString(), QString("netease"));
        QTest::qWait(200);
        QCOMPARE(spy.size(), 1);
    }
    void qqPlaylistHundredUsesBoundedPages() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.qqPlaylistBounded = true;
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::playlistResults);
        api.searchPlaylists("many", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        const auto event = spy.takeFirst();
        QVERIFY(event[1].toString().isEmpty());
        const auto rows = event[0].toList();
        QCOMPARE(rows.size(), 100);
        QCOMPARE(rows[0].toMap().value("id").toString(), QString("tencent:playlist:100"));
        QCOMPARE(rows[99].toMap().value("id").toString(), QString("tencent:playlist:199"));
        QCOMPARE(f.requests.size(), 4);
        for (int i = 0; i < 4; ++i) {
            const auto body =
                QJsonDocument::fromJson(f.requests[i].mid(f.requests[i].indexOf("\r\n\r\n") + 4)).object();
            const auto p = body.value("music.search.SearchCgiService").toObject().value("param").toObject();
            QCOMPARE(p.value("num_per_page").toInt(), 30);
            QCOMPARE(p.value("page_num").toInt(), i + 1);
        }
        f.requests.clear();
        api.searchPlaylists("fifty", {"tencent"}, 50);
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.takeFirst()[0].toList().size(), 50);
        QCOMPARE(f.requests.size(), 2);
        f.requests.clear();
        api.searchPlaylists("default", {"tencent"});
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.takeFirst()[0].toList().size(), 30);
        QCOMPARE(f.requests.size(), 1);
    }
    void qqPlaylistPartialFilteredPageAndEndMetadata() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.qqPlaylistBounded = true;
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::playlistResults);
        f.qqPlaylistSecondFailure = true;
        api.searchPlaylists("partial", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        auto event = spy.takeFirst();
        QCOMPARE(event[0].toList().size(), 30);
        QVERIFY(event[1].toString().contains("503"));
        f.qqPlaylistSecondFailure = false;
        f.qqPlaylistDropFirst = true;
        api.searchPlaylists("filtered", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        event = spy.takeFirst();
        QCOMPARE(event[0].toList().size(), 99);
        QCOMPARE(event[0].toList().last().toMap().value("resourceId").toString(), QString("199"));
        QVERIFY(event[1].toString().isEmpty());
        f.qqPlaylistDropFirst = false;
        f.qqPlaylistDuplicate = true;
        api.searchPlaylists("duplicates", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        event = spy.takeFirst();
        QCOMPARE(event[0].toList().size(), 99);
        QSet<QString> seen;
        for (const auto &v : event[0].toList()) {
            const auto id = v.toMap().value("id").toString();
            QVERIFY(!seen.contains(id));
            seen.insert(id);
        }
        f.qqPlaylistDuplicate = false;
        f.playlistCount = 20;
        f.requests.clear();
        api.searchPlaylists("short", {"tencent"}, 100);
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.takeFirst()[0].toList().size(), 20);
        QCOMPARE(f.requests.size(), 1);
    }
    void qqPlaylistStaleSecondPage() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.qqPlaylistBounded = true;
        f.qqSecondDelay = 150;
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::playlistResults);
        api.searchPlaylists("old", {"tencent"}, 100);
        QTRY_COMPARE(f.requests.size(), 2);
        api.searchPlaylists("latest", {"kuwo"}, 10);
        QTRY_COMPARE(spy.size(), 1);
        QCOMPARE(spy.first()[0].toList()[0].toMap().value("source").toString(), QString("kuwo"));
        QTest::qWait(220);
        QCOMPARE(spy.size(), 1);
    }
    void responseCapAndInvalidCanonicalIds() {
        CatalogFixture f;
        QVERIFY(f.listen(QHostAddress::LocalHost));
        f.oversized = true;
        MusicApi api(f.endpoints());
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::playlistResults);
        api.searchPlaylists("huge", {"tencent"}, 10);
        QTRY_COMPARE(spy.size(), 1);
        QVERIFY(spy.first()[0].toList().isEmpty());
        QVERIFY(spy.first()[1].toString().contains("2 MiB"));
        const auto before = f.requests.size();
        for (const auto &id : {"tencent:ranking:9999999999999999999", "kuwo:playlist:0",
                               "kuwo:unsupported:16", "tencent:playlist:1:2", "unknown:ranking:1"}) {
            bool done = false;
            api.fetchPlaylist(id, [&](QVariantMap v, QString e) {
                done = true;
                QVERIFY(v.isEmpty());
                QVERIFY(!e.isEmpty());
            });
            QVERIFY(done);
        }
        QCOMPARE(f.requests.size(), before);
    }
    void liveCatalogSmoke() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_CATALOGS"))
            QSKIP("Enable FLOATMUSIC_LIVE_CATALOGS for anonymous metadata smoke; normal suite is offline.");
        MusicApi api;
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy spy(&api, &MusicApi::playlistResults);
        api.searchPlaylists(QStringLiteral("纯音乐"), {"tencent", "kuwo"}, 10);
        QTRY_COMPARE_WITH_TIMEOUT(spy.size(), 1, 45000);
        const auto event = spy.takeFirst();
        QVERIFY2(event[1].toString().isEmpty(), qPrintable(event[1].toString()));
        const auto found = event[0].toList();
        QVERIFY(!found.isEmpty());
        QVERIFY(found.size() <= 10);
        QSet<QString> platforms;
        for (const auto &v : found)
            platforms.insert(v.toMap().value("source").toString());
        QVERIFY(platforms.contains("tencent"));
        QVERIFY(platforms.contains("kuwo"));
        qInfo() << "Anonymous playlist search count" << found.size();
        bool done = false;
        QVariantList ranks;
        QString error;
        api.fetchRankings("tencent", [&](QVariantList v, QString e) {
            ranks = v;
            error = e;
            done = true;
        });
        QTRY_VERIFY_WITH_TIMEOUT(done, 30000);
        QVERIFY2(error.isEmpty(), qPrintable(error));
        QVERIFY(!ranks.isEmpty());
        qInfo() << "Anonymous QQ ranking catalog count" << ranks.size();
        for (const auto &id : {"tencent:playlist:7593082970", "kuwo:playlist:3677150229", "kuwo:ranking:16",
                               "tencent:ranking:26"}) {
            done = false;
            error.clear();
            QVariantMap list;
            api.fetchPlaylist(id, [&](QVariantMap v, QString e) {
                list = v;
                error = e;
                done = true;
            });
            QTRY_VERIFY_WITH_TIMEOUT(done, 60000);
            QVERIFY2(error.isEmpty(), qPrintable(error));
            const auto tracks = list.value("tracks").toList();
            QVERIFY(!tracks.isEmpty());
            QVERIFY(tracks.size() <= 2000);
            for (const auto &v : tracks) {
                const auto t = v.toMap();
                QCOMPARE(t.value("id").toString(),
                         t.value("source").toString() + ":" + t.value("songId").toString());
            }
            qInfo() << "Anonymous catalog" << id << "tracks" << tracks.size() << "reported total"
                    << list.value("trackCount").toInt() << "partial"
                    << !list.value("warning").toString().isEmpty();
        }
    }
    void aesKnownVectorAndPadding() {
        // NIST FIPS 197 Appendix C.1: first ECB block is independent of PKCS7 suffix.
        const auto key = QByteArray::fromHex("000102030405060708090a0b0c0d0e0f");
        const auto plain = QByteArray::fromHex("00112233445566778899aabbccddeeff");
        const auto cipher = CatalogCodec::aesEncrypt(plain, key);
        QCOMPARE(cipher.left(16).toHex(), QByteArray("69c4e0d86a7b0430d8cdb78070b4c55a"));
        QCOMPARE(cipher.size(), 32);
        QString error;
        QCOMPARE(CatalogCodec::aesDecrypt(cipher, key, error), plain);
        QVERIFY(error.isEmpty());
        CatalogCodec::aesDecrypt(cipher.left(15), key, error);
        QVERIFY(!error.isEmpty());
        error.clear();
        CatalogCodec::aesDecrypt(QByteArray::fromHex("69c4e0d86a7b0430d8cdb78070b4c55a"), key, error);
        QVERIFY(!error.isEmpty());
        QVERIFY(CatalogCodec::aesEncrypt(plain, "bad").isEmpty());
    }
    void liveLargeQqSearch() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_CATALOGS"))
            QSKIP("Explicit anonymous metadata check for the QQ 100-result pagination path.");
        MusicApi api;
        QVERIFY(api.setBaseUrl(""));
        QSignalSpy songs(&api, &MusicApi::results);
        api.search(QStringLiteral("周杰伦"), {"tencent"}, 100);
        QTRY_COMPARE_WITH_TIMEOUT(songs.size(), 1, 45000);
        auto event = songs.takeFirst();
        QVERIFY2(event[1].toString().isEmpty(), qPrintable(event[1].toString()));
        QCOMPARE(event[0].toList().size(), 100);
        QSet<QString> seen;
        for (const auto &v : event[0].toList()) {
            const auto row = v.toMap();
            QCOMPARE(row.value("source").toString(), QString("tencent"));
            QVERIFY(!seen.contains(row.value("id").toString()));
            seen.insert(row.value("id").toString());
        }
        qInfo() << "Anonymous QQ song search at configured100 returned" << seen.size() << "unique songs";
        QSignalSpy lists(&api, &MusicApi::playlistResults);
        api.searchPlaylists(QStringLiteral("纯音乐"), {"tencent"}, 100);
        QTRY_COMPARE_WITH_TIMEOUT(lists.size(), 1, 60000);
        event = lists.takeFirst();
        QVERIFY2(event[1].toString().isEmpty(), qPrintable(event[1].toString()));
        QCOMPARE(event[0].toList().size(), 100);
        seen.clear();
        for (const auto &v : event[0].toList()) {
            const auto row = v.toMap();
            QVERIFY(row.value("id").toString().startsWith("tencent:playlist:"));
            QVERIFY(!seen.contains(row.value("id").toString()));
            seen.insert(row.value("id").toString());
        }
        qInfo() << "Anonymous QQ playlist search at configured100 returned" << seen.size() << "unique playlists";
    }
    void singleQuotedLiteralDoesNotExecuteOrCorruptStrings() {
        QString error;
        const auto doc = CatalogCodec::parseLiteral(
            "{'abslist':[{'name':\"John's mix\",'intro':'it\\'s \\\"fine\\\"','songnum':2}], 'TOTAL':'1'}",
            error);
        QVERIFY2(!doc.isNull() && error.isEmpty(), qPrintable(error));
        QCOMPARE(doc.object().value("abslist").toArray()[0].toObject().value("name").toString(),
                 QString("John's mix"));
        QCOMPARE(doc.object().value("abslist").toArray()[0].toObject().value("intro").toString(),
                 QString("it's \"fine\""));
        for (const auto &bad : {QByteArray("{'x':__import__('os')}"), QByteArray("{'x':1} trailing"),
                                QByteArray(100, '['), QByteArray("{'x':'unterminated}")}) {
            error.clear();
            QVERIFY(CatalogCodec::parseLiteral(bad, error).isNull());
            QVERIFY(!error.isEmpty());
        }
    }
};
QTEST_GUILESS_MAIN(CatalogApiTests)
#include "catalog_api_tests.moc"
