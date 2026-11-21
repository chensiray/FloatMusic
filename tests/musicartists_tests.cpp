#include "musicartists.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTest>
#include <QTimer>
#include <QUrlQuery>

namespace {
class ArtistServer : public QTcpServer {
public:
    bool failQqSearch = false;
    bool failNeteaseProfile = false;
    bool wrongQqArtist = false;
    bool qqMultiplePages = false;
    bool pagedWorks = false;
    bool failNextWorksPage = false;
    bool repeatWorksPage = false;
    int worksRequests = 0;
    int slowReplies = 0;

    ArtistServer() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    const auto raw = socket->property("raw").toByteArray() + socket->readAll();
                    socket->setProperty("raw", raw);
                    const auto bodyOffset = raw.indexOf("\r\n\r\n");
                    if (bodyOffset < 0 || socket->property("sent").toBool()) return;
                    int length = 0;
                    for (const auto &line : raw.left(bodyOffset).split('\n')) {
                        if (line.toLower().startsWith("content-length:")) length = line.mid(15).trimmed().toInt();
                    }
                    if (raw.size() < bodyOffset + 4 + length) return;
                    socket->setProperty("sent", true);
                    const QUrl url(QString::fromUtf8(raw.split(' ').value(1)));
                    const QUrlQuery query(url);
                    const auto request = QJsonDocument::fromJson(raw.mid(bodyOffset + 4, length)).object();
                    if (url.path() == "/api/v1/artist/songs") ++worksRequests;
                    const auto response = fixture(url.path(), query, request);
                    const bool slowArtist = url.path() == "/api/v1/artist/songs" && query.queryItemValue("id") == "41" && !pagedWorks;
                    const int delay = query.queryItemValue("s") == "Slow" || slowArtist ? 130 : 0;
                    if (delay) ++slowReplies;
                    const auto bytes = QByteArray(response.isEmpty() ? "HTTP/1.1 503 Unavailable\r\n" : "HTTP/1.1 200 OK\r\n")
                        + "Content-Type: application/json\r\nConnection: close\r\nContent-Length: "
                        + QByteArray::number(response.size()) + "\r\n\r\n" + response;
                    QTimer::singleShot(delay, socket, [socket, bytes] {
                        socket->write(bytes);
                        socket->disconnectFromHost();
                    });
                });
            }
        });
        listen(QHostAddress::LocalHost);
    }

    MusicApi::Endpoints endpoints() const {
        MusicApi::Endpoints e;
        e.metadata = QString("http://127.0.0.1:%1").arg(serverPort());
        e.qqMusicu = e.metadata + "/qq";
        e.kuwoPlaylistSearch = e.metadata + "/kuwo";
        e.timeoutMs = 1000;
        return e;
    }

private:
    static QByteArray json(const QJsonObject &data) {
        return QJsonDocument(data).toJson(QJsonDocument::Compact);
    }

    QByteArray fixture(const QString &path, const QUrlQuery &q, const QJsonObject &request) const {
        if (path == "/api/search/get" && q.queryItemValue("type") == "100") {
            if (q.queryItemValue("s") == "Empty")
                return R"({"code":200,"result":{"artistCount":0}})";
            const auto name = q.queryItemValue("s");
            return json({{"code", 200}, {"result", QJsonObject{{"artistCount", 2}, {"artists", QJsonArray{
                QJsonObject{{"id", 41}, {"name", name}}, QJsonObject{{"id", 42}, {"name", "Second"}}}}}}});
        }
        if (path == "/api/artist/introduction" && (q.queryItemValue("id") == "41" || q.queryItemValue("id") == "42")) {
            if (failNeteaseProfile) return {};
            return R"({"code":200,"briefDesc":"<p>A &amp; B</p><img src='https://example.com/pixel'>","introduction":[]})";
        }
        if (path == "/api/v1/artist/songs") {
            const QJsonObject song{{"id", 993}, {"name", "NetEase work"}, {"duration", 230500},
                {"artists", QJsonArray{QJsonObject{{"name", "Collaborator"}}, QJsonObject{{"name", "Artist"}}}},
                {"album", QJsonObject{{"name", "Album"}, {"picUrl", "https://p1.music.126.net/a.jpg"}}}};
            const auto offset = q.queryItemValue("offset").toInt();
            if (repeatWorksPage && offset < 150) {
                auto updated = song;
                updated["irrelevantRevision"] = offset;
                return json({{"code", 200}, {"more", true}, {"songs", QJsonArray{updated}}});
            }
            if (offset > 0 && failNextWorksPage) return {};
            if (pagedWorks) {
                QJsonArray page;
                const int end = qMin(offset + 50, 103);
                for (int i = offset; i < end; ++i) {
                    auto row = song;
                    row["id"] = 1000 + (i == 50 ? 49 : i);
                    row["name"] = i == 102 ? "Distant Encore" : "Early work";
                    page.append(row);
                }
                return json({{"code", 200}, {"total", 103}, {"more", end < 103}, {"songs", page}});
            }
            return json({{"code", 200}, {"more", false}, {"songs", QJsonArray{song, song, QJsonObject{{"id", "bad/id"}, {"name", "Unplayable identity"}}}}});
        }
        if (path == "/qq") {
            const QString search = "music.search.SearchCgiService";
            const auto req = request.value("req_0").toObject();
            const auto params = req.value("param").toObject();
            const auto searchParams = request.value(search).toObject().value("param").toObject();
            if (searchParams.value("search_type").toInt() == 1) {
                if (failQqSearch) return {};
                if (qqMultiplePages) {
                    QJsonArray rows;
                    const int page = searchParams.value("page_num").toInt();
                    if (searchParams.value("num_per_page").toInt() <= 30) {
                        const int end = page == 1 ? 30 : 36;
                        for (int i = page == 1 ? 1 : 30; i <= end; ++i) {
                            rows.append(QJsonObject{{"singerMID", QString("Mid%1").arg(i)},
                                                    {"singerName", QString("QQ Artist %1").arg(i)}});
                        }
                    }
                    const QJsonObject singer{{"list", rows}};
                    const QJsonObject data{{"body", QJsonObject{{"singer", singer}}}};
                    return json({{"code", 0}, {search, QJsonObject{{"code", 0}, {"data", data}}}});
                }
                return R"({"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"singer":{"list":[{"singerMID":"Abc123","singerName":"QQ Artist"}]}}}}})";
            }
            const auto mids = params.value("singer_mids").toArray();
            if (req.value("method") == "GetSingerDetail" && !mids.isEmpty() && mids.at(0) == "Abc123") {
                return R"({"code":0,"req_0":{"code":0,"data":{"singer_list":[{"basic_info":{"singer_mid":"Abc123"},"ex_info":{"desc":""}}]}}})";
            }
            if (req.value("module") == "musichall.song_list_server" && req.value("method") == "GetSingerSongList"
                && params.value("singerMid") == "Abc123") {
                if (pagedWorks) {
                    const auto begin = params.value("begin").toInt();
                    if (begin != 0 && begin != 1) return {};
                    const QJsonObject song{{"mid", QString("Song%1").arg(begin)}, {"title", "QQ work"}};
                    const QJsonObject data{{"singerMid", "Abc123"}, {"totalNum", 2},
                        {"songList", QJsonArray{QJsonObject{{"songInfo", song}}}}};
                    return json({{"code", 0}, {"req_0", QJsonObject{{"code", 0}, {"data", data}}}});
                }
                return wrongQqArtist
                    ? R"({"code":0,"req_0":{"code":0,"data":{"singerMid":"Other123","songList":[]}}})"
                    : R"({"code":0,"req_0":{"code":0,"data":{"singerMid":"Abc123","songList":[{"songInfo":{"mid":"Song123","title":"QQ work","interval":154,"singer":[{"name":"QQ Artist"}],"album":{"mid":"Album1"}}}]}}})";
            }
        }
        if (path == "/kuwo") {
            if (q.queryItemValue("ft") == "artist" && !q.queryItemValue("all").isEmpty()) {
                return "{'TOTAL':'1','abslist':[{'ARTISTID':'63','ARTIST':'Kuwo&nbsp;Artist','desc':'Known intro'}]}";
            }
            if (q.queryItemValue("artistid") == "63" && q.queryItemValue("stype") == "artistinfo") {
                return "{'id':'63','name':'Kuwo&nbsp;Artist','desc':'A&nbsp;short<br>profile'}";
            }
            if (q.queryItemValue("artistid") == "63" && q.queryItemValue("stype") == "artist2music") {
                if (pagedWorks) {
                    const auto page = q.queryItemValue("pn").toInt();
                    if (page != 0 && page != 1) return {};
                    QJsonArray songs;
                    for (int i = page * 50; i < (page == 0 ? 50 : 51); ++i)
                        songs.append(QJsonObject{{"musicrid", 2000 + i}, {"name", "Kuwo work"}});
                    return json({{"total", 51}, {"musiclist", songs}});
                }
                return "{'artist':'Kuwo&nbsp;Artist','total':'1','musiclist':[{'musicrid':'921','name':'Kuwo&nbsp;work','artist':'Kuwo&nbsp;Artist','album':'Album','duration':'242'}]}";
            }
        }
        return {};
    }
};

QVariantMap detail(const MusicArtists &artists) { return artists.state().value("detail").toMap(); }
QVariantList results(const MusicArtists &artists) { return artists.state().value("results").toList(); }
QVariantList works(const MusicArtists &artists) { return detail(artists).value("tracks").toList(); }
void search(MusicArtists &artists, const QString &query) { artists.action("search", {{"query", query}}); }
void open(MusicArtists &artists, const QString &source, const QString &id) {
    artists.action("open", {{"source", source}, {"id", id}});
}
}

class MusicArtistsTests : public QObject {
    Q_OBJECT
private slots:
    void searchesAndNormalizesProviderWorks() {
        ArtistServer server;
        QVERIFY(server.isListening());
        MusicArtists artists(server.endpoints());
        search(artists, "  Artist  ");
        QVERIFY(artists.state().value("searching").toBool());
        QTRY_COMPARE(results(artists).size(), 4);
        QVERIFY(artists.state().value("searchError").toString().isEmpty());
        QCOMPARE(results(artists).at(0).toMap().value("source").toString(), "netease");
        QCOMPARE(results(artists).at(1).toMap().value("source").toString(), "tencent");
        QCOMPARE(results(artists).at(2).toMap().value("name").toString(), "Kuwo Artist");

        open(artists, "netease", "41");
        QVERIFY(artists.state().value("loading").toBool());
        QTRY_VERIFY(!artists.state().value("loading").toBool());
        QCOMPARE(detail(artists).value("description").toString(), "A & B");
        QCOMPARE(works(artists).size(), 1);
        const auto netease = works(artists).first().toMap();
        QCOMPARE(netease.value("id").toString(), "netease:993");
        QCOMPARE(netease.value("duration").toDouble(), 230.5);
        QCOMPARE(netease.value("artist").toString(), "Collaborator / Artist");

        open(artists, "tencent", "Abc123");
        QTRY_VERIFY(!artists.state().value("loading").toBool());
        QCOMPARE(detail(artists).value("description").toString(), "");
        QVERIFY(artists.state().value("detailError").toString().isEmpty());
        QCOMPARE(works(artists).size(), 1);
        QCOMPARE(works(artists).first().toMap().value("songId").toString(), "Song123");

        open(artists, "kuwo", "63");
        QTRY_VERIFY(!artists.state().value("loading").toBool());
        QCOMPARE(detail(artists).value("description").toString(), "A short\nprofile");
        QCOMPARE(works(artists).size(), 1);
        const auto kuwo = works(artists).first().toMap();
        QCOMPARE(kuwo.value("id").toString(), "kuwo:921");
        QCOMPARE(kuwo.value("name").toString(), "Kuwo work");
        QCOMPARE(kuwo.value("duration").toDouble(), 242.0);
    }

    void partialFailuresKeepAvailableResultsAndWorks() {
        ArtistServer server;
        server.failQqSearch = true;
        server.failNeteaseProfile = true;
        MusicArtists artists(server.endpoints());
        search(artists, "Artist");
        QTRY_COMPARE(results(artists).size(), 3);
        QVERIFY(!artists.state().value("searchError").toString().isEmpty());
        open(artists, "netease", "41");
        QTRY_VERIFY(!artists.state().value("loading").toBool());
        QCOMPARE(works(artists).size(), 1);
        QCOMPARE(detail(artists).value("description").toString(), "");
        QVERIFY(!artists.state().value("detailError").toString().isEmpty());
    }

    void loadsAllWorksAndResumesAfterFailedPage() {
        ArtistServer server;
        server.pagedWorks = true;
        MusicArtists artists(server.endpoints());
        artists.setSearchOptions({"netease"}, 30);
        search(artists, "Artist");
        QTRY_COMPARE(results(artists).size(), 2);
        open(artists, "netease", "41");
        QTRY_VERIFY(!artists.state().value("loading").toBool());
        QCOMPARE(works(artists).size(), 50);
        QVERIFY(detail(artists).value("hasMore").toBool());
        server.failNextWorksPage = true;
        artists.action("loadAll");
        QTRY_VERIFY(!artists.state().value("worksError").toString().isEmpty());
        QCOMPARE(works(artists).size(), 50);
        server.failNextWorksPage = false;
        artists.action("loadAll");
        QTRY_COMPARE(works(artists).size(), 102);
        QVERIFY(!detail(artists).value("hasMore").toBool());
        QVERIFY(artists.state().value("worksError").toString().isEmpty());
        QCOMPARE(artists.work("netease:1102").value("name").toString(), "Distant Encore");
    }

    void stopsWhenProviderRepeatsTheSameTrackIds() {
        ArtistServer server;
        server.repeatWorksPage = true;
        MusicArtists artists(server.endpoints());
        artists.setSearchOptions({"netease"}, 30);
        search(artists, "Artist");
        QTRY_COMPARE(results(artists).size(), 2);
        open(artists, "netease", "41");
        QTRY_VERIFY(!artists.state().value("loading").toBool());
        artists.action("loadAll");
        QTRY_VERIFY(!artists.state().value("worksLoading").toBool());
        QCOMPARE(server.worksRequests, 2);
        QCOMPARE(works(artists).size(), 1);
        QVERIFY(!artists.state().value("worksError").toString().isEmpty());
    }

    void rejectsWrongArtistWorksAndKeepsProfileSelection() {
        ArtistServer server;
        server.wrongQqArtist = true;
        MusicArtists artists(server.endpoints());
        artists.setSearchOptions({"tencent"}, 30);
        search(artists, "Artist");
        QTRY_COMPARE(results(artists).size(), 1);
        open(artists, "tencent", "Abc123");
        QTRY_VERIFY(!artists.state().value("loading").toBool());
        QCOMPARE(detail(artists).value("id").toString(), "Abc123");
        QVERIFY(works(artists).isEmpty());
        QVERIFY(!artists.state().value("worksError").toString().isEmpty());
    }

    void readsQqOffsetsAndKuwoPageNumbers() {
        ArtistServer server;
        server.pagedWorks = true;
        MusicArtists artists(server.endpoints());
        artists.setSearchOptions({"tencent", "kuwo"}, 30);
        search(artists, "Artist");
        QTRY_COMPARE(results(artists).size(), 2);
        open(artists, "tencent", "Abc123");
        QTRY_COMPARE(works(artists).size(), 1);
        artists.action("loadAll");
        QTRY_COMPARE(works(artists).size(), 2);
        QCOMPARE(works(artists).last().toMap().value("id").toString(), "tencent:Song1");
        QVERIFY(!detail(artists).value("hasMore").toBool());
        open(artists, "kuwo", "63");
        QTRY_COMPARE(works(artists).size(), 50);
        artists.action("loadAll");
        QTRY_COMPARE(works(artists).size(), 51);
        QCOMPARE(works(artists).last().toMap().value("id").toString(), "kuwo:2050");
        QVERIFY(!detail(artists).value("hasMore").toBool());
    }

    void largeQqSearchPreservesPageOrderAndRemovesDuplicates() {
        ArtistServer server;
        server.qqMultiplePages = true;
        MusicArtists artists(server.endpoints());
        artists.setSearchOptions({"tencent"}, 50);
        search(artists, "Artist");
        QTRY_VERIFY(!artists.state().value("searching").toBool());
        QCOMPARE(results(artists).size(), 36);
        QCOMPARE(results(artists).at(29).toMap().value("id").toString(), "Mid30");
        QCOMPARE(results(artists).at(30).toMap().value("id").toString(), "Mid31");
    }

    void staleSearchAndDetailRepliesCannotReopenState() {
        ArtistServer server;
        MusicArtists artists(server.endpoints());
        artists.setSearchOptions({"netease"}, 30);
        search(artists, "Slow");
        QTRY_COMPARE(server.slowReplies, 1);
        search(artists, "Fast");
        QTRY_COMPARE(results(artists).size(), 2);
        QCOMPARE(results(artists).first().toMap().value("name").toString(), "Fast");

        open(artists, "netease", "41");
        QTRY_COMPARE(server.slowReplies, 2);
        artists.action("back");
        open(artists, "netease", "42");
        QTRY_VERIFY(!artists.state().value("loading").toBool());
        QCOMPARE(detail(artists).value("id").toString(), "42");
        artists.action("close");
        QTest::qWait(170);
        QVERIFY(detail(artists).isEmpty());
        QVERIFY(results(artists).isEmpty());
        QVERIFY(!artists.state().value("searching").toBool());
    }

    void distinguishesEmptyResultsFromUnavailableSources() {
        ArtistServer server;
        MusicArtists artists(server.endpoints());
        artists.setSearchOptions({"netease"}, 30);
        search(artists, "Empty");
        QTRY_VERIFY(!artists.state().value("searching").toBool());
        QVERIFY(results(artists).isEmpty());
        QVERIFY(artists.state().value("searchError").toString().isEmpty());

        artists.setSearchOptions({}, 30);
        search(artists, "Artist");
        QVERIFY(!artists.state().value("searching").toBool());
        QVERIFY(!artists.state().value("searchError").toString().isEmpty());
    }

    void livePublicSources() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_ARTISTS")) QSKIP("Opt-in public network smoke test");
        MusicArtists artists;
        for (const auto &source : QStringList{"netease", "tencent", "kuwo"}) {
            artists.setSearchOptions({source}, 30);
            search(artists, "Taylor Swift");
            QTRY_VERIFY_WITH_TIMEOUT(!artists.state().value("searching").toBool(), 25000);
            QVERIFY2(!results(artists).isEmpty(), qPrintable(artists.state().value("searchError").toString()));
            const auto selected = results(artists).first().toMap();
            QCOMPARE(selected.value("name").toString(), "Taylor Swift");
            open(artists, source, selected.value("id").toString());
            QTRY_VERIFY_WITH_TIMEOUT(!artists.state().value("loading").toBool(), 25000);
            QVERIFY2(!works(artists).isEmpty(), qPrintable(artists.state().value("detailError").toString()));
            QVERIFY(!detail(artists).value("description").toString().isEmpty());
            const auto firstPage = works(artists).size();
            artists.action("loadAll");
            QTRY_VERIFY_WITH_TIMEOUT(!artists.state().value("worksLoading").toBool(), 120000);
            QVERIFY2(!detail(artists).value("hasMore").toBool(), qPrintable(artists.state().value("worksError").toString()));
            QVERIFY(works(artists).size() >= firstPage);
            qInfo() << source << "artist works" << firstPage << "->" << works(artists).size();
        }
    }
};

QTEST_GUILESS_MAIN(MusicArtistsTests)
#include "musicartists_tests.moc"
