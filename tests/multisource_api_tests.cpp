#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QPointer>
#include <QSettings>
#include <QStandardPaths>
#include <QProcess>
#include "musicapi.h"

class MultiSourceFixture : public QTcpServer {
public:
    QList<QUrl> requests;
    QList<QByteArray> requestHeaders;
    int qqSearchStatus = 200, qqAudioStatus = 200;
    bool denyQqAudio = false, emptyQqLyrics = false, denyKuwo = false;
    MultiSourceFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const QUrl url(QString::fromUtf8(request.split(' ').value(1)));
                    const QUrlQuery query(url); requests.append(url); requestHeaders.append(request.toLower());
                    QByteArray body; int status = 200, delay = 0;
                    const QString keyword = query.queryItemValue(query.hasQueryItem("keyword") ? "keyword" : query.hasQueryItem("s") ? "s" : "keywords", QUrl::FullyDecoded);
                    if (keyword == "slow") delay = 250;
                    if (url.path() == "/music/tencent/search/song") {
                        status = qqSearchStatus;
                        body = QJsonDocument(QJsonObject{{"code", 0}, {"data", QJsonObject{{"list", QJsonArray{
                            QJsonObject{{"songMID", "004MpJjW07rAPl"}, {"title", keyword}, {"singer", "QQ singer"}},
                            QJsonObject{{"songMID", "004MpJjW07rAPl"}, {"title", "duplicate"}},
                            QJsonObject{{"songMID", "../../bad"}, {"title", "invalid"}}
                        }}}}}).toJson();
                    } else if (url.path() == "/music/tencent/song/link") {
                        status = qqAudioStatus;
                        body = QJsonDocument(QJsonObject{{"code", denyQqAudio ? 500 : 0}, {"data", QJsonObject{{"url", base() + "/audio?token=keep%2Bthis"}, {"kbps", "192kbps"}}}}).toJson();
                    } else if (url.path() == "/v2/music/tencent/lyric") {
                        body = QJsonDocument(QJsonObject{{"code", 200}, {"data", QJsonObject{
                            {"lrc", ""}, {"trans", emptyQqLyrics ? "" : "[00:01.00]translation"},
                            {"yrc", emptyQqLyrics ? "" : "[1000,900](1000,300,0)Hello (1300,600,0)world\n[2000,500](2000,500,0)Next"}
                        }}}).toJson();
                    } else if (url.path() == "/ourcraft") {
                        const QString type = query.queryItemValue("type");
                        if (type == "search") body = QJsonDocument(QJsonObject{{"ok", true}, {"songs", QJsonArray{
                            QJsonObject{{"id", "42"}, {"name", query.queryItemValue("id", QUrl::FullyDecoded)}, {"singer", "Kuwo singer"}}
                        }}}).toJson();
                        else if (type == "url") body = QJsonDocument(QJsonObject{{"ok", !denyKuwo}, {"url", base() + "/proxy?url=" + QString::fromLatin1(QUrl::toPercentEncoding(base() + "/audio?token=origin%2Bvalue"))}}).toJson();
                        else if (type == "lrc") body = "[00:00.00]Kuwo original\n[00:01.50]Second line";
                    } else if (url.path() == "/meting") {
                        if (query.queryItemValue("type") == "lrc") body = "[00:00.00]Meting original";
                        else body = QJsonDocument(QJsonArray{QJsonObject{{"url", base() + "/audio?from=meting"}, {"lrc", base() + "/meting?type=lrc"}}}).toJson();
                    } else if (url.path() == "/audio" || url.path() == "/proxy") {
                        body = "RIFF0000WAVEfmt 0000000000000000000000000000000000000000";
                    } else {
                        body = QJsonDocument(QJsonObject{{"code", 200}, {"result", QJsonObject{{"songs", QJsonArray{
                            QJsonObject{{"id", 42}, {"name", keyword}, {"ar", QJsonArray{QJsonObject{{"name", "Netease singer"}}}}}
                        }}}}}).toJson();
                    }
                    QPointer<QTcpSocket> safe(socket);
                    auto send = [safe, body, status] {
                        if (!safe) return;
                        safe->write("HTTP/1.1 " + QByteArray::number(status) + " Fixture\r\nContent-Type: application/json\r\nSet-Cookie: anonymous-session=fixture; Path=/\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                        safe->disconnectFromHost();
                    };
                    if (delay) QTimer::singleShot(delay, socket, send); else send();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
    MusicApi::Endpoints endpoints() const {
        MusicApi::Endpoints e{base(), base() + "/legacy", 1000};
        e.vkeys = base(); e.ourcraft = base() + "/ourcraft"; e.injahow = base() + "/meting";
        return e;
    }
};

class MultiSourceApiTests : public QObject {
    Q_OBJECT
    void checkLivePlatform(const QString &source) {
        MusicApi api; QVERIFY(api.setBaseUrl("")); QSignalSpy spy(&api, &MusicApi::results);
        api.search(QStringLiteral("两只老虎"), {source}); QTRY_COMPARE_WITH_TIMEOUT(spy.size(), 1, 20000);
        const auto event = spy.takeFirst(); QVERIFY2(event[1].toString().isEmpty(), qPrintable(event[1].toString()));
        const auto tracks = event[0].toList(); QVERIFY(tracks.size() >= 2);
        for (int index = 0; index < 2; ++index) {
            const QString id = tracks[index].toMap().value("songId").toString();
            bool done = false; MusicApi::Audio audio; QString error;
            api.resolveAudio(source, id, "standard", [&](MusicApi::Audio value, QString failure) { audio = value; error = failure; done = true; });
            QTRY_VERIFY_WITH_TIMEOUT(done, 30000); QVERIFY2(error.isEmpty(), qPrintable(error)); QVERIFY(!audio.url.isEmpty());
            qInfo().noquote() << "Anonymous sample:" << source << id << "via" << audio.sourceId << "bitrate" << audio.bitrate;
            done = false; MusicApi::Lyrics lyrics;
            api.fetchLyrics(source, id, [&](MusicApi::Lyrics value, QString failure) { lyrics = value; error = failure; done = true; });
            QTRY_VERIFY_WITH_TIMEOUT(done, 45000); QVERIFY2(error.isEmpty(), qPrintable(error)); QVERIFY(!lyrics.original.trimmed().isEmpty());
            qInfo() << "Lyrics characters:" << lyrics.original.size();
            const QString helper = qEnvironmentVariable("FLOATMUSIC_QT_PROBE");
            if (helper.isEmpty()) continue;
            QProcess decoder; decoder.setProcessChannelMode(QProcess::SeparateChannels); decoder.start(helper, {});
            QVERIFY(decoder.waitForStarted(5000));
            decoder.write(QJsonDocument(QJsonObject{{"url", audio.url.toString(QUrl::FullyEncoded)}, {"timeout_ms", 15000}}).toJson(QJsonDocument::Compact) + '\n');
            decoder.closeWriteChannel(); QVERIFY(decoder.waitForFinished(20000));
            const auto decoded = QJsonDocument::fromJson(decoder.readAllStandardOutput()).object();
            // Signed URL and FFmpeg diagnostics remain inside the child process; never log them.
            qInfo().noquote() << "Qt outcome:" << decoded.value("outcome").toString();
            QVERIFY(decoded.value("decoded").toBool()); QVERIFY(decoded.value("paused").toBool());
            QVERIFY(decoded.value("resumed").toBool()); QVERIFY(decoded.value("seek_ok").toBool());
        }
    }
private slots:
    void initTestCase() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QCoreApplication::setApplicationName("multisource-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
    }
    void combinedSearchKeepsPlatformIdentityAndSelectionOrder() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); QSignalSpy spy(&api, &MusicApi::results);
        api.search(QStringLiteral("同名 & +"), {"kuwo", "tencent", "netease", "tencent"});
        QTRY_COMPARE(spy.size(), 1); const auto event = spy.takeFirst(); const auto tracks = event[0].toList();
        QCOMPARE(tracks.size(), 3); QVERIFY(event[1].toString().isEmpty());
        QCOMPARE(tracks[0].toMap().value("id").toString(), QString("kuwo:42"));
        QCOMPARE(tracks[1].toMap().value("id").toString(), QString("tencent:004MpJjW07rAPl"));
        QCOMPARE(tracks[2].toMap().value("id").toString(), QString("netease:42"));
        for (const auto &track : tracks) { QCOMPARE(track.toMap().value("name").toString(), QStringLiteral("同名 & +")); QVERIFY(!track.toMap().value("sourceName").toString().isEmpty()); }
        QCOMPARE(fixture.requests.size(), 3);
    }
    void partialFailureAndStaleResultsAreHandled() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.qqSearchStatus = 503;
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); QSignalSpy spy(&api, &MusicApi::results);
        api.search("partial", {"netease", "tencent", "kuwo"}); QTRY_COMPARE(spy.size(), 1);
        auto event = spy.takeFirst(); QCOMPARE(event[0].toList().size(), 2); QVERIFY(event[1].toString().contains("QQ"));
        fixture.qqSearchStatus = 200;
        api.search("slow", {"tencent", "netease"}); QTest::qWait(20); api.search("latest", {"kuwo"});
        QTRY_COMPARE(spy.size(), 1); QCOMPARE(spy.takeFirst()[0].toList()[0].toMap().value("name").toString(), QString("latest"));
        QTest::qWait(300); QVERIFY(spy.isEmpty());
        api.search("unused", {}); QCOMPARE(spy.size(), 1); QVERIFY(spy.takeFirst()[0].toList().isEmpty());
    }
    void qqUsesOrdinaryQualityAndAnonymousBackup() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "hires", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY(error.isEmpty()); QCOMPARE(audio.sourceId, QString("qq-vkeys")); QCOMPARE(audio.bitrate, 192);
        QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("quality"), QString("4"));
        QCOMPARE(QUrlQuery(audio.url).queryItemValue("token", QUrl::FullyDecoded), QString("keep+this"));
        done = false; fixture.denyQqAudio = true;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY(error.isEmpty()); QCOMPARE(audio.sourceId, QString("qq-injahow"));
        QCOMPARE(QUrlQuery(audio.url).queryItemValue("from"), QString("meting"));
    }
    void kuwoPrefersOriginalCdnAndCanRetryProxy() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        auto receive = [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; };
        api.resolveAudio("kuwo", "42", "lossless", receive); QTRY_VERIFY(done); QVERIFY(error.isEmpty());
        QCOMPARE(audio.sourceId, QString("kuwo-origin")); QCOMPARE(audio.url.path(), QString("/audio"));
        QCOMPARE(QUrlQuery(audio.url).queryItemValue("token", QUrl::FullyDecoded), QString("origin+value"));
        done = false; api.resolveAudio("kuwo", "42", "standard", receive, {"kuwo-origin"}); QTRY_VERIFY(done);
        QVERIFY(error.isEmpty()); QCOMPARE(audio.sourceId, QString("kuwo-proxy")); QCOMPARE(audio.url.path(), QString("/proxy"));
    }
    void audioFailureSuggestsAvailableRecovery_data() {
        QTest::addColumn<QString>("platform");
        QTest::addColumn<QString>("songId");
        QTest::addColumn<bool>("qualitySelectable");
        QTest::newRow("qq-ordinary-quality") << QString("tencent") << QString("004MpJjW07rAPl") << false;
        QTest::newRow("kuwo-ordinary-quality") << QString("kuwo") << QString("42") << false;
        QTest::newRow("netease-selectable-quality") << QString("netease") << QString("42") << true;
    }
    void audioFailureSuggestsAvailableRecovery() {
        QFETCH(QString, platform); QFETCH(QString, songId); QFETCH(bool, qualitySelectable);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.denyQqAudio = true; fixture.denyKuwo = true;
        auto endpoints = fixture.endpoints(); endpoints.injahow.clear();
        MusicApi api(endpoints);
        // The Netease fixture returns no playable address from the custom API.
        QVERIFY(api.setBaseUrl(platform == "netease" ? fixture.base() : QString()));
        int calls = 0; MusicApi::Audio audio; QString error;
        api.resolveAudio(platform, songId, "standard", [&](MusicApi::Audio value, QString failure) {
            audio = value; error = failure; ++calls;
        });
        QTRY_COMPARE_WITH_TIMEOUT(calls, 1, 3000); QVERIFY(audio.url.isEmpty());
        QVERIFY2(error.contains(QStringLiteral("重试")), qPrintable(error));
        if (qualitySelectable) {
            QVERIFY2(error.endsWith(QStringLiteral("请检查网络后重试或更换音质。")), qPrintable(error));
        } else {
            QVERIFY2(error.contains(QStringLiteral("其他歌曲")), qPrintable(error));
            QVERIFY2(!error.contains(QStringLiteral("更换音质")), qPrintable(error));
        }
    }
    void platformLyricsAndQqWordTimedConversion() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Lyrics lyrics; QString error;
        auto receive = [&](MusicApi::Lyrics l, QString e) { lyrics = l; error = e; done = true; };
        api.fetchLyrics("tencent", "004MpJjW07rAPl", receive); QTRY_VERIFY(done); QVERIFY(error.isEmpty());
        QCOMPARE(lyrics.original, QString("[00:01.000]Hello world\n[00:02.000]Next")); QCOMPARE(lyrics.translation, QString("[00:01.00]translation"));
        fixture.emptyQqLyrics = true; done = false; api.fetchLyrics("tencent", "004MpJjW07rAPl", receive); QTRY_VERIFY(done);
        QVERIFY(error.isEmpty()); QVERIFY(lyrics.original.contains("Meting original"));
        done = false; api.fetchLyrics("kuwo", "42", receive); QTRY_VERIFY(done); QVERIFY(error.isEmpty()); QVERIFY(lyrics.original.contains("Kuwo original"));
    }
    void invalidSourcesAndIdsNeverIssueRequests() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false;
        api.resolveAudio("tencent", "../bad", "standard", [&](MusicApi::Audio a, QString e) { QVERIFY(a.url.isEmpty()); QVERIFY(!e.isEmpty()); done = true; });
        QVERIFY(done); QVERIFY(fixture.requests.isEmpty()); QVERIFY(api.hasAudioBackups("tencent")); QVERIFY(api.hasAudioBackups("kuwo")); QVERIFY(!api.hasAudioBackups("invalid"));
    }
    void qqIdentityMatchesPlaylistAndSessionContract_data() {
        QTest::addColumn<QString>("mid");
        QTest::addColumn<bool>("valid");
        QTest::newRow("one-character") << QString("A") << true;
        QTest::newRow("short-mixed-case") << QString("AbCd123") << true;
        QTest::newRow("maximum-length") << QString(64, QChar('a')) << true;
        QTest::newRow("empty") << QString() << false;
        QTest::newRow("too-long") << QString(65, QChar('a')) << false;
        QTest::newRow("punctuation") << QString("abc/def") << false;
    }
    void qqIdentityMatchesPlaylistAndSessionContract() {
        QFETCH(QString, mid); QFETCH(bool, valid);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl(""));
        bool done = false; QString error; MusicApi::Audio audio;
        api.resolveAudio("tencent", mid, "standard", [&](MusicApi::Audio value, QString failure) {
            audio = value; error = failure; done = true;
        });
        QTRY_VERIFY(done);
        QCOMPARE(error.isEmpty(), valid);
        QCOMPARE(!audio.url.isEmpty(), valid);
        if (valid) {
            QVERIFY(!fixture.requests.isEmpty());
            QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("mid"), mid);
        } else QVERIFY(fixture.requests.isEmpty());
    }
    void requestsStayAnonymousAfterServerSetsCookies() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); QSignalSpy spy(&api, &MusicApi::results);
        api.search("first", {"tencent"}); QTRY_COMPARE(spy.size(), 1); spy.clear();
        api.search("second", {"tencent", "kuwo"}); QTRY_COMPARE(spy.size(), 1);
        bool done = false;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio, QString) { done = true; });
        QTRY_VERIFY(done);
        for (const auto &header : fixture.requestHeaders) { QVERIFY(!header.contains("\r\ncookie:")); QVERIFY(!header.contains("\r\nauthorization:")); }
    }
    void liveAnonymousQq() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_MULTISOURCE")) QSKIP("Opt-in network test; set FLOATMUSIC_LIVE_MULTISOURCE=1.");
        checkLivePlatform("tencent");
    }
    void liveAnonymousKuwo() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_MULTISOURCE")) QSKIP("Opt-in network test; set FLOATMUSIC_LIVE_MULTISOURCE=1.");
        checkLivePlatform("kuwo");
    }
    void liveAnonymousQqBackup() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_MULTISOURCE")) QSKIP("Opt-in network test; set FLOATMUSIC_LIVE_MULTISOURCE=1.");
        MusicApi api; QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "001RGrEX3ija5X", "standard", [&](MusicApi::Audio value, QString failure) { audio = value; error = failure; done = true; }, {"qq-vkeys"});
        QTRY_VERIFY_WITH_TIMEOUT(done, 30000); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(audio.sourceId, QString("qq-injahow")); QCOMPARE(audio.url.scheme(), QString("https"));
        qInfo().noquote() << "Anonymous QQ backup validated via" << audio.sourceId << "over HTTPS";
    }
};
QTEST_GUILESS_MAIN(MultiSourceApiTests)
#include "multisource_api_tests.moc"
