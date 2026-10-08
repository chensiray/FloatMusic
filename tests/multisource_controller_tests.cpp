#include <QtTest>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>
#include <QStandardPaths>
#include <QUuid>
#include <QUrlQuery>
#include <QDataStream>
#include "playlistapi_fixture.h"
#include "playercontroller.h"

class SearchLimitFixture : public QTcpServer {
public:
    QList<QUrl> requests;
    SearchLimitFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    const auto bytes = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", bytes);
                    if (!bytes.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const QUrl url(QString::fromUtf8(bytes.split(' ').value(1)));
                    requests.append(url);
                    const bool playlists = QUrlQuery(url).queryItemValue("type") == "1000";
                    QJsonArray rows;
                    for (int i = 1; i <= 100; ++i) rows.append(QJsonObject{{"id", i},
                        {"name", QString("Result %1").arg(i)}, {"trackCount", 10}});
                    const auto body = QJsonDocument(QJsonObject{{"code", 200},
                        {"result", QJsonObject{{playlists ? "playlists" : "songs", rows}}}}).toJson();
                    socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                    socket->disconnectFromHost();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
};
class KuwoDurationFixture : public QTcpServer {
public:
    KuwoDurationFixture(int audioSeconds = 12, int targetSeconds = 226) : targetDuration(targetSeconds) {
        QDataStream stream(&wave, QIODevice::WriteOnly); stream.setByteOrder(QDataStream::LittleEndian);
        const quint32 bytes = 16000 * 2 * audioSeconds;
        stream.writeRawData("RIFF", 4); stream << quint32(36 + bytes); stream.writeRawData("WAVEfmt ", 8);
        stream << quint32(16) << quint16(1) << quint16(1) << quint32(16000) << quint32(32000) << quint16(2) << quint16(16);
        stream.writeRawData("data", 4); stream << bytes; stream.writeRawData(QByteArray(bytes, '\0').constData(), bytes);
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    const auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const QUrl url(QString::fromUtf8(request.split(' ').value(1)));
                    QByteArray body;
                    if (url.path() == "/audio.wav") body = wave;
                    else if (url.path() == "/ourcraft" && QUrlQuery(url).queryItemValue("type") == "search")
                        body = QJsonDocument(QJsonObject{{"ok", true}, {"songs", QJsonArray{
                            QJsonObject{{"id", "42"}, {"name", "Short song"}, {"duration", targetDuration}}}}}).toJson();
                    else body = QJsonDocument(QJsonObject{{"code", 200},
                        {"data", QJsonObject{{"rid", "42"}, {"type", 0}, {"duration", targetDuration}, {"url", base() + "/audio.wav"}}}}).toJson();
                    socket->write("HTTP/1.1 200 OK\r\nContent-Length: " + QByteArray::number(body.size())
                        + "\r\nConnection: close\r\n\r\n" + body);
                    socket->disconnectFromHost();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
private:
    QByteArray wave;
    int targetDuration;
};

class MultisourceControllerTests : public QObject {
    Q_OBJECT
private:
    MusicApi::Endpoints isolatedEndpoints() const {
        MusicApi::Endpoints endpoints;
        endpoints.metadata = "http://127.0.0.1:1";
        endpoints.playback = endpoints.metadata;
        endpoints.timeoutMs = 100;
        return endpoints;
    }
private slots:
    void init() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QCoreApplication::setApplicationName("multi-controller-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
    }
    void selectedLibrariesPersistAndEmptySelectionDoesNotSearch() {
        PlayerController controller(isolatedEndpoints());
        QVERIFY(controller.metaObject()->indexOfProperty("searchSources") >= 0);
        QCOMPARE(controller.property("searchSources").toStringList(), QStringList({"netease", "tencent", "kuwo"}));
        QVERIFY(controller.setProperty("searchSources", QStringList({"kuwo", "tencent", "kuwo", "unknown"})));
        QCOMPARE(controller.property("searchSources").toStringList(), QStringList({"tencent", "kuwo"}));
        PlayerController restored(isolatedEndpoints());
        QCOMPARE(restored.property("searchSources").toStringList(), QStringList({"tencent", "kuwo"}));
        QVERIFY(controller.setProperty("searchSources", QStringList{}));
        controller.search(QStringLiteral("两只老虎"));
        QVERIFY(!controller.searching());
        QVERIFY(controller.searchResults().isEmpty());
        QVERIFY(controller.searchMessage().contains(QStringLiteral("选择")));
    }
    void qualityPreferencesAreIndependentAndPersistWithoutPlayback() {
        QSettings().setValue("netease/quality", "hires");
        PlayerController controller(isolatedEndpoints());
        QVERIFY(controller.metaObject()->indexOfProperty("sourceQualities") >= 0);
        auto qualities = controller.property("sourceQualities").toMap();
        QCOMPARE(qualities.value("netease").toString(), QString("hires"));
        QCOMPARE(qualities.value("tencent").toString(), QString("standard"));
        QCOMPARE(qualities.value("kuwo").toString(), QString("standard"));
        QVERIFY(QMetaObject::invokeMethod(&controller, "setSourceQuality", Qt::DirectConnection,
            Q_ARG(QString, QString("tencent")), Q_ARG(QString, QString("master"))));
        QVERIFY(QMetaObject::invokeMethod(&controller, "setSourceQuality", Qt::DirectConnection,
            Q_ARG(QString, QString("kuwo")), Q_ARG(QString, QString("lossless"))));
        QCOMPARE(controller.quality(), QString("hires"));
        QVERIFY(!controller.busy());
        QVERIFY(controller.currentTrack().isEmpty());
        PlayerController restored(isolatedEndpoints());
        qualities = restored.property("sourceQualities").toMap();
        QCOMPARE(qualities.value("netease").toString(), QString("hires"));
        QCOMPARE(qualities.value("tencent").toString(), QString("master"));
        QCOMPARE(qualities.value("kuwo").toString(), QString("lossless"));
        controller.setQuality("exhigh");
        qualities = controller.property("sourceQualities").toMap();
        QCOMPARE(qualities.value("netease").toString(), QString("exhigh"));
        QCOMPARE(qualities.value("tencent").toString(), QString("master"));
        QCOMPARE(qualities.value("kuwo").toString(), QString("lossless"));
    }
    void qualityPreferencesRejectCrossPlatformAndCorruptValues() {
        QSettings().setValue("netease/quality", "master");
        QSettings().setValue("audio/quality/tencent", "hires");
        QSettings().setValue("audio/quality/kuwo", "master");
        PlayerController controller(isolatedEndpoints());
        QVERIFY(controller.metaObject()->indexOfProperty("sourceQualities") >= 0);
        const QVariantMap standard{{"netease", "standard"}, {"tencent", "standard"}, {"kuwo", "standard"}};
        QCOMPARE(controller.property("sourceQualities").toMap(), standard);
        for (const auto &pair : QList<QPair<QString, QString>>{{"netease", "master"},
             {"tencent", "hires"}, {"kuwo", "higher"}, {"local", "lossless"}, {"unknown", "standard"}}) {
            QVERIFY(QMetaObject::invokeMethod(&controller, "setSourceQuality", Qt::DirectConnection,
                Q_ARG(QString, pair.first), Q_ARG(QString, pair.second)));
        }
        QCOMPARE(controller.property("sourceQualities").toMap(), standard);
    }
    void restoredQqSongUsesItsOwnQualityRatherThanNeteasePreference() {
        SearchLimitFixture service; QVERIFY(service.listen(QHostAddress::LocalHost));
        MusicApi::Endpoints endpoints{service.base(), service.base(), 1000};
        endpoints.vkeys = service.base();
        const QVariantMap track{{"id", "tencent:001RGrEX3ija5X"}, {"source", "tencent"},
            {"songId", "001RGrEX3ija5X"}, {"name", "Restored QQ song"}};
        QSettings().setValue("netease/quality", "hires");
        QSettings().setValue("audio/quality/tencent", "lossless");
        QSettings().setValue("playback/session", QJsonDocument(QJsonObject::fromVariantMap(
            {{"track", track}, {"position", 3250}, {"duration", 8000}})).toJson());
        PlayerController controller(endpoints);
        QTRY_COMPARE(controller.currentTrack(), track.value("id").toString());
        controller.setSourceQuality("netease", "exhigh");
        QTest::qWait(30);
        QVERIFY(service.requests.isEmpty());
        controller.retryPlayback();
        QTRY_VERIFY(!service.requests.isEmpty());
        QCOMPARE(QUrlQuery(service.requests.first()).queryItemValue("quality"), QString("10"));
        QTRY_VERIFY(!controller.busy());
        // This fixture returns no audio URL; it verifies the request, not playback.
        QVERIFY(!controller.error().isEmpty());
        QCOMPARE(controller.quality(), QString("exhigh"));
        QCOMPARE(controller.sourceQualities().value("tencent").toString(), QString("lossless"));
    }
    void decodedKuwoDurationRejectsPromptBeforeAutoplay_data() {
        QTest::addColumn<int>("actualSeconds"); QTest::addColumn<bool>("knownTarget");
        QTest::newRow("known-target-short-prompt") << 12 << true;
        QTest::newRow("unknown-target-provider-duration") << 30 << false;
    }
    void decodedKuwoDurationRejectsPromptBeforeAutoplay() {
        QFETCH(int, actualSeconds); QFETCH(bool, knownTarget);
        KuwoDurationFixture service(actualSeconds); QVERIFY(service.listen(QHostAddress::LocalHost));
        auto endpoints = isolatedEndpoints(); endpoints.kuwoMobi = service.base() + "/mobi.s"; endpoints.timeoutMs = 1000;
        QVariantMap track{{"id", "kuwo:42"}, {"source", "kuwo"}, {"songId", "42"}, {"name", "Full song"}};
        if (knownTarget) track["duration"] = 226;
        QSettings().setValue("playback/session", QJsonDocument(QJsonObject::fromVariantMap(
            {{"track", track}, {"position", 0}, {"duration", 226000}})).toJson());
        PlayerController controller(endpoints); controller.setVolume(0);
        QTRY_COMPARE(controller.currentTrack(), QString("kuwo:42"));
        QSignalSpy changes(&controller, &PlayerController::changed);
        bool everPlayed = false;
        connect(&controller, &PlayerController::changed, this, [&] { everPlayed |= controller.playing(); });
        controller.retryPlayback();
        QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 5000);
        QVERIFY2(!controller.ready(), qPrintable(controller.error() + " | " + controller.status()));
        QVERIFY(!controller.playing()); QVERIFY(!everPlayed);
        QVERIFY(controller.error().contains(QStringLiteral("时长")));
        QVERIFY(changes.size() > 0);
    }
    void genuineShortKuwoSearchSongSurvivesSessionRestore() {
        KuwoDurationFixture service(11, 11); QVERIFY(service.listen(QHostAddress::LocalHost));
        auto endpoints = isolatedEndpoints(); endpoints.kuwoMobi = service.base() + "/mobi.s";
        endpoints.ourcraft = service.base() + "/ourcraft"; endpoints.timeoutMs = 1000;
        {
            PlayerController controller(endpoints); controller.setVolume(0);
            controller.setSearchSources({"kuwo"}); controller.search("Short song");
            QTRY_VERIFY(!controller.searching()); QCOMPARE(controller.searchResults().size(), 1);
            controller.playSearchResult(0);
            QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 5000);
            QVERIFY2(controller.error().isEmpty(), qPrintable(controller.error()));
            QVERIFY(controller.ready()); QCOMPARE(controller.duration(), qint64(11000));
            controller.toggle(); QVERIFY(!controller.playing());
        }
        const auto saved = QJsonDocument::fromJson(QSettings().value("playback/session").toByteArray()).object();
        QCOMPARE(saved.value("track").toObject().value("duration").toDouble(), 11.0);
        PlayerController restored(endpoints); restored.setVolume(0);
        QTRY_COMPARE(restored.currentTrack(), QString("kuwo:42"));
        restored.retryPlayback(); QTRY_VERIFY_WITH_TIMEOUT(!restored.busy(), 5000);
        QVERIFY2(restored.error().isEmpty(), qPrintable(restored.error()));
        QVERIFY(restored.ready()); QVERIFY(restored.playing()); QCOMPARE(restored.duration(), qint64(11000));
    }
    void resultLimitPersistsAndRejectsUnsupportedValues() {
        PlayerController controller(isolatedEndpoints());
        QVERIFY(controller.metaObject()->indexOfProperty("searchResultLimit") >= 0);
        QCOMPARE(controller.property("searchResultLimit").toInt(), 30);
        const int signal = controller.metaObject()->indexOfSignal("searchSettingsChanged()");
        QVERIFY(signal >= 0);
        QSignalSpy changed(&controller, controller.metaObject()->method(signal));
        for (const int limit : {10, 20, 30, 50, 100}) {
            QVERIFY(controller.setProperty("searchResultLimit", limit));
            QCOMPARE(controller.property("searchResultLimit").toInt(), limit);
            PlayerController restored(isolatedEndpoints());
            QCOMPARE(restored.property("searchResultLimit").toInt(), limit);
        }
        QCOMPARE(changed.size(), 5);
        for (const int invalid : {-1, 0, 7, 101, 100000}) {
            controller.setProperty("searchResultLimit", invalid);
            QCOMPARE(controller.property("searchResultLimit").toInt(), 100);
        }
        QCOMPARE(changed.size(), 5);
        controller.setSearchSources({});
        controller.libraryAction("searchPlaylists", {{"query", "sample"}});
        QVERIFY(!controller.playlistSearching());
        QVERIFY(controller.playlistResults().isEmpty());
        QVERIFY(controller.playlistSearchMessage().contains(QStringLiteral("选择")));
    }
    void corruptSearchSettingsFallBackToDefault() {
        QSettings().setValue("search/resultLimit", 999);
        QSettings().setValue("rankings/source", "unknown");
        PlayerController controller(isolatedEndpoints());
        QVERIFY(controller.metaObject()->indexOfProperty("searchResultLimit") >= 0);
        QVERIFY(controller.metaObject()->indexOfProperty("rankingSource") >= 0);
        QCOMPARE(controller.property("searchResultLimit").toInt(), 30);
        QCOMPARE(controller.property("rankingSource").toString(), QString("netease"));
    }
    void settingsLimitReachesSongAndPlaylistSearch() {
        SearchLimitFixture service; QVERIFY(service.listen(QHostAddress::LocalHost));
        MusicApi::Endpoints endpoints{service.base(), service.base(), 1000};
        PlayerController controller(endpoints);
        controller.setApiBase(""); controller.setSearchSources({"netease"});
        QVERIFY(controller.setProperty("searchResultLimit", 50));
        controller.search("sample");
        QTRY_VERIFY(!controller.searching());
        QCOMPARE(controller.searchResults().size(), 50);
        QCOMPARE(QUrlQuery(service.requests.last()).queryItemValue("limit"), QString("50"));
        controller.libraryAction("searchPlaylists", {{"query", "sample"}});
        QTRY_VERIFY(!controller.playlistSearching());
        QCOMPARE(controller.playlistResults().size(), 50);
        QCOMPARE(QUrlQuery(service.requests.last()).queryItemValue("limit"), QString("50"));
        // The setting applies to the next search and does not erase visible results.
        QVERIFY(controller.setProperty("searchResultLimit", 10));
        QCOMPARE(controller.playlistResults().size(), 50);
        controller.libraryAction("searchPlaylists", {{"query", "sample"}});
        QTRY_VERIFY(!controller.playlistSearching());
        QCOMPARE(controller.playlistResults().size(), 10);
    }
    void rankingPlatformSwitchSupersedesOldRequestAndPersists() {
        PlaylistApiFixture service; QVERIFY(service.listen(QHostAddress::LocalHost));
        service.rankingsDelay = 250;
        MusicApi::Endpoints endpoints{service.base(), service.base(), 1000};
        PlayerController controller(endpoints); controller.setApiBase(service.base());
        controller.libraryAction("loadRankings", {{"source", "netease"}});
        QTRY_COMPARE(service.requests.size(), 1);
        QVERIFY(controller.rankingsLoading());
        controller.libraryAction("loadRankings", {{"source", "kuwo"}});
        QTRY_VERIFY(!controller.rankingsLoading());
        QCOMPARE(controller.property("rankingSource").toString(), QString("kuwo"));
        QCOMPARE(controller.rankings().size(), 3);
        for (const auto &rank : controller.rankings()) {
            QCOMPARE(rank.toMap().value("source").toString(), QString("kuwo"));
            QVERIFY(rank.toMap().value("id").toString().startsWith("kuwo:ranking:"));
        }
        QTest::qWait(300);
        QCOMPARE(controller.rankings().size(), 3);
        QCOMPARE(controller.rankings().first().toMap().value("source").toString(), QString("kuwo"));
        PlayerController restored(endpoints);
        QCOMPARE(restored.property("rankingSource").toString(), QString("kuwo"));
        controller.libraryAction("loadRankings", {{"source", "unknown"}});
        QCOMPARE(controller.property("rankingSource").toString(), QString("kuwo"));
        QVERIFY(!controller.rankingsMessage().isEmpty());
    }
    void restoresThePlatformOfAnOnlineSong_data() {
        QTest::addColumn<QString>("source");
        QTest::addColumn<QString>("songId");
        QTest::addColumn<QString>("label");
        QTest::newRow("netease") << QString("netease") << QString("42") << QStringLiteral("网易云");
        QTest::newRow("qq-mid") << QString("tencent") << QString("004MpJjW07rAPl") << QStringLiteral("QQ音乐");
        QTest::newRow("kuwo-rid") << QString("kuwo") << QString("42") << QStringLiteral("酷我音乐");
    }
    void restoresThePlatformOfAnOnlineSong() {
        QFETCH(QString, source);
        QFETCH(QString, songId);
        QFETCH(QString, label);
        const QString id = source + ':' + songId;
        const QVariantMap track{{"id", id}, {"source", source}, {"songId", songId},
                                {"name", "Restored song"}, {"artist", "Artist"}};
        QSettings().setValue("playback/session", QJsonDocument(QJsonObject::fromVariantMap(
            {{"track", track}, {"position", 3250}, {"duration", 8000}})).toJson());
        PlayerController controller(isolatedEndpoints());
        QTRY_COMPARE(controller.currentTrack(), id);
        QVERIFY(controller.online());
        QVERIFY(controller.ready());
        QVERIFY(!controller.playing());
        QCOMPARE(controller.position(), qint64(3250));
        QCOMPARE(controller.property("currentSourceName").toString(), label);
        QVERIFY(controller.property("qualitySelectable").toBool());
        QVERIFY(controller.qualityInfo().contains(QStringLiteral("标准")));
    }
    void rejectsAMismatchedPlatformIdentityInSession() {
        const QVariantMap track{{"id", "netease:42"}, {"source", "kuwo"}, {"songId", "42"}, {"name", "Forged"}};
        QSettings().setValue("playback/session", QJsonDocument(QJsonObject::fromVariantMap(
            {{"track", track}, {"position", 1000}, {"duration", 8000}})).toJson());
        PlayerController controller(isolatedEndpoints());
        QTest::qWait(20);
        QVERIFY(controller.currentTrack().isEmpty());
        QVERIFY(!controller.ready());
    }
    void liveMixedPlaylistPlayback() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_MULTISOURCE")) QSKIP("Explicit anonymous network playback test");
        QStringList ids;
        {
            PlayerController controller;
            controller.setApiBase(""); controller.setVolume(0); controller.setQuality("hires");
            for (const QString &source : {QString("tencent"), QString("kuwo")}) {
                controller.setSearchSources({source});
                controller.search(source == "tencent" ? QStringLiteral("星 杨宗纬") : QStringLiteral("两只老虎"));
                QTRY_VERIFY_WITH_TIMEOUT(!controller.searching(), 20000);
                QVERIFY2(!controller.searchResults().isEmpty(), qPrintable(controller.searchMessage()));
                const auto track = controller.searchResults().first().toMap();
                QCOMPARE(track.value("source").toString(), source);
                qInfo().noquote() << "Mixed playlist sample:" << source << track.value("songId").toString()
                    << track.value("name").toString();
                ids.append(track.value("id").toString());
                controller.addSearchResult(0);
                controller.playSearchResult(0);
                QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 30000);
                QVERIFY2(controller.ready(), qPrintable(controller.error()));
                QCOMPARE(controller.currentTrack(), ids.last());
                QVERIFY(controller.qualitySelectable());
                QVERIFY(controller.qualityInfo().contains(QStringLiteral("实际")));
                QTRY_VERIFY_WITH_TIMEOUT(controller.playing(), 5000);
                QTRY_VERIFY_WITH_TIMEOUT(controller.position() > 200, 5000);
                QTRY_VERIFY_WITH_TIMEOUT(!controller.lyricsLoading(), 15000);
                QVERIFY2(!controller.lyricLines().isEmpty(), qPrintable(controller.lyricsMessage()));
                controller.toggle(); QTRY_VERIFY(!controller.playing());
                controller.seek(5000);
                QTRY_VERIFY_WITH_TIMEOUT(qAbs(controller.position() - 5000) < 500, 5000);
            }
            QCOMPARE(controller.tracks().size(), 2);
            controller.previous();
            QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 30000);
            QVERIFY2(controller.ready(), qPrintable(controller.error()));
            QCOMPARE(controller.currentTrack(), ids.first());
            controller.next();
            QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 30000);
            QVERIFY2(controller.ready(), qPrintable(controller.error()));
            QCOMPARE(controller.currentTrack(), ids.last());
            if (controller.playing()) controller.toggle();
            QTRY_VERIFY(!controller.playing());
        }
        PlayerController restored;
        QTRY_COMPARE(restored.currentTrack(), ids.last());
        QVERIFY(restored.ready());
        QVERIFY(!restored.playing());
        QCOMPARE(restored.tracks().size(), 2);
        QCOMPARE(restored.currentSourceName(), QStringLiteral("酷我音乐"));
        QVERIFY2(!QSettings().value("playback/session").toByteArray().contains("url"), "Session must not persist temporary audio URLs");
    }
};

QTEST_GUILESS_MAIN(MultisourceControllerTests)
#include "multisource_controller_tests.moc"
