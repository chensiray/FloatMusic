#include <QtTest>
#include <QDataStream>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QDir>
#include <QSettings>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QFile>
#include <QTcpServer>
#include <QTcpSocket>
#include <QPointer>
#include <QFutureWatcher>
#include <QUrlQuery>
#include "playercontroller.h"
#include "playliststore.h"

static bool enqueue(PlayerController &player, const QVariantMap &track, const QString &placement = "tail") {
    return QMetaObject::invokeMethod(&player, "enqueueTrack", Q_ARG(QVariantMap, track), Q_ARG(QString, placement));
}
static bool playEntry(PlayerController &player, const QString &id) {
    return QMetaObject::invokeMethod(&player, "playQueueEntry", Q_ARG(QString, id));
}
static QVariantMap onlineTrack(const QString &source, const QString &songId) {
    return {{"id", source + ":" + songId}, {"source", source}, {"songId", songId},
            {"name", "Test " + songId}, {"artist", "Tester"}, {"duration", 120}};
}
static QByteArray wave(int seconds = 5) {
    QByteArray result; QDataStream stream(&result, QIODevice::WriteOnly);
    stream.setByteOrder(QDataStream::LittleEndian);
    const quint32 bytes = 16000 * 2 * seconds;
    stream.writeRawData("RIFF", 4); stream << quint32(36 + bytes);
    stream.writeRawData("WAVEfmt ", 8); stream << quint32(16) << quint16(1) << quint16(1)
        << quint32(16000) << quint32(32000) << quint16(2) << quint16(16);
    stream.writeRawData("data", 4); stream << bytes;
    stream.writeRawData(QByteArray(bytes, '\0').constData(), bytes);
    return result;
}

class PlaylistBrowsingFixture : public QTcpServer {
public:
    QString delayStage = "detail";
    int oldRequests = 0;
    bool delayedResponseSent = false;
    PlaylistBrowsingFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    const auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const QUrl url(QString::fromUtf8(request.split(' ').value(1)));
                    const QUrlQuery query(url);
                    QJsonObject response; bool delayed = false;
                    if (url.path() == "/playlist/detail") {
                        const QString id = query.queryItemValue("id");
                        const bool old = id == "100";
                        if (old) ++oldRequests;
                        delayed = old && delayStage == "detail";
                        QJsonArray ids, songs;
                        if (old) for (int i = 1001; i <= 1201; ++i) ids.append(QJsonObject{{"id", i}});
                        else songs.append(QJsonObject{{"id", 2001}, {"name", "Latest"}});
                        response = {{"code", 200}, {"playlist", QJsonObject{{"id", id}, {"name", "Browse " + id},
                            {"trackCount", old ? 201 : 1}, {"trackIds", ids}, {"tracks", songs}}}};
                    } else if (url.path() == "/song/detail") {
                        ++oldRequests;
                        const QString ids = query.queryItemValue("ids");
                        delayed = delayStage == "continuation" && ids.startsWith("1001,");
                        QJsonArray songs;
                        for (const auto &id : ids.split(',')) songs.append(QJsonObject{{"id", id}, {"name", "Song " + id}});
                        response = {{"code", 200}, {"songs", songs}};
                    } else if (url.path() == "/kw-detail") {
                        const QString id = query.queryItemValue("pid");
                        const int page = query.queryItemValue("pn").toInt();
                        const bool old = id == "100";
                        if (old) ++oldRequests;
                        delayed = old && page == (delayStage == "detail" ? 0 : 1);
                        QJsonArray songs;
                        for (int i = 0; i < (old ? 50 : 1); ++i)
                            songs.append(QJsonObject{{"id", 1001 + page * 50 + i}, {"name", "Song " + QString::number(i)}});
                        response = {{"result", "ok"}, {"name", "Browse " + id}, {"total", old ? 150 : 1}, {"musiclist", songs}};
                    }
                    const auto body = QJsonDocument(response).toJson();
                    QPointer<QTcpSocket> safe(socket);
                    const auto send = [this, safe, body, delayed] {
                        if (delayed) delayedResponseSent = true;
                        if (!safe) return;
                        safe->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                        safe->disconnectFromHost();
                    };
                    if (delayed) QTimer::singleShot(250, this, send); else send();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
    MusicApi::Endpoints endpoints() const {
        MusicApi::Endpoints endpoints{base(), base() + "/unused-audio", 2000};
        endpoints.kuwoPlaylistDetail = base() + "/kw-detail";
        return endpoints;
    }
};

class V12ControllerTests : public QObject {
    Q_OBJECT
private slots:
    void initTestCase() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
    }
    void init() {
        QCoreApplication::setApplicationName("v12-controller-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
    }
    void independentMixedQueuePersistsDuplicates() {
        QString first, second;
        {
            PlayerController player;
            const auto netease = onlineTrack("netease", "42"), tencent = onlineTrack("tencent", "42");
            QVERIFY(enqueue(player, netease)); QVERIFY(enqueue(player, tencent)); QVERIFY(enqueue(player, netease));
            auto queue = player.property("queueEntries").toList();
            QVERIFY2(queue.size() == 3, qPrintable(player.property("playbackMessage").toString()
                + " | " + QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation)));
            first = queue[0].toMap().value("queueId").toString(); second = queue[1].toMap().value("queueId").toString();
            QVERIFY(first != queue[2].toMap().value("queueId").toString());
            player.createPlaylist("Other playlist");
            QCOMPARE(player.property("queueEntries").toList(), queue);
            QVERIFY(QMetaObject::invokeMethod(&player, "moveQueueEntry", Q_ARG(QString, second), Q_ARG(int, 0)));
            QCOMPARE(player.property("queueEntries").toList()[0].toMap().value("queueId").toString(), second);
        }
        PlayerController restored;
        QCOMPARE(restored.property("queueEntries").toList().size(), 3);
        QCOMPARE(restored.property("queueEntries").toList()[0].toMap().value("queueId").toString(), second);
        QCOMPARE(restored.property("sleepMode").toString(), QString("off"));
    }
    void actualHistoryAndClearDoesNotInterruptCurrentAudio() {
        QTemporaryDir dir; QVERIFY(dir.isValid());
        QFile audio(dir.filePath("heard.wav")); QVERIFY(audio.open(QIODevice::WriteOnly));
        audio.write(wave(20)); audio.close();
        QString current;
        {
            PlayerController player;
            player.importFile(QUrl::fromLocalFile(audio.fileName()));
            QTRY_VERIFY_WITH_TIMEOUT(player.ready() && !player.busy(), 15000);
            QCOMPARE(player.property("historyEntries").toList().size(), 0);
            QCOMPARE(player.property("queueEntries").toList().size(), 1);
            player.toggle(); QTRY_VERIFY(player.playing());
            QTRY_COMPARE(player.property("historyEntries").toList().size(), 1);
            const auto at = player.property("historyEntries").toList()[0].toMap().value("playedAtMs").toLongLong();
            player.toggle(); QTRY_VERIFY(!player.playing()); player.toggle(); QTRY_VERIFY(player.playing());
            QCOMPARE(player.property("historyEntries").toList()[0].toMap().value("playedAtMs").toLongLong(), at);
            current = player.currentTrack();
            QVERIFY(QMetaObject::invokeMethod(&player, "clearPlaybackHistory"));
            QCOMPARE(player.property("historyEntries").toList().size(), 0);
            QVERIFY(QMetaObject::invokeMethod(&player, "clearPlaybackQueue"));
            QVERIFY(player.playing()); QCOMPARE(player.currentTrack(), current);
            QCOMPARE(player.property("currentQueueEntry").toString(), QString());
        }
        PlayerController restored;
        QTRY_COMPARE(restored.currentTrack(), current);
        QVERIFY(!restored.playing());
        QCOMPARE(restored.property("queueEntries").toList().size(), 0);
        QCOMPARE(restored.property("historyEntries").toList().size(), 0);
    }
    void missingAudioDoesNotEnterHistory() {
        PlayerController player;
        const QVariantMap missing{{"id", "local:missing"}, {"source", "local"}, {"name", "Missing"}, {"path", "C:/missing/floatmusic.wav"}};
        QVERIFY(enqueue(player, missing));
        const auto entry = player.property("queueEntries").toList().first().toMap().value("queueId").toString();
        QVERIFY(playEntry(player, entry));
        QTRY_VERIFY(!player.busy());
        QVERIFY(!player.error().isEmpty());
        QCOMPARE(player.property("historyEntries").toList().size(), 0);
    }
    void removedCurrentContinuesAtGapAcrossRestart() {
        QTemporaryDir dir; QVERIFY(dir.isValid());
        QFile audio(dir.filePath("queue.wav")); QVERIFY(audio.open(QIODevice::WriteOnly)); audio.write(wave(20)); audio.close();
        QString secondTrack;
        {
            PlayerController player;
            for (const QString &id : {QString("local:a"), QString("local:b"), QString("local:c")})
                QVERIFY(enqueue(player, {{"id", id}, {"source", "local"}, {"name", id}, {"path", audio.fileName()}}));
            const auto items = player.property("queueEntries").toList();
            const auto selected = items[1].toMap().value("queueId").toString();
            QVERIFY(playEntry(player, selected)); QTRY_VERIFY(player.playing() && !player.busy());
            secondTrack = player.currentTrack(); QCOMPARE(secondTrack, QString("local:b"));
            QVERIFY(QMetaObject::invokeMethod(&player, "removeQueueEntry", Q_ARG(QString, selected)));
            QVERIFY(player.playing()); QCOMPARE(player.property("currentQueueEntry").toString(), QString());
            player.createPlaylist("Browse unrelated playlist");
            QCOMPARE(player.property("queueEntries").toList().size(), 2);
        }
        PlayerController restored;
        QTRY_COMPARE(restored.currentTrack(), secondTrack);
        QVERIFY(!restored.playing());
        QCOMPARE(restored.property("currentQueueEntry").toString(), QString());
        restored.next(); QTRY_COMPARE(restored.currentTrack(), QString("local:c")); QTRY_VERIFY(restored.playing());
    }
    void failedSelectionKeepsTheActuallyPlayingCursor() {
        QTemporaryDir dir; QVERIFY(dir.isValid());
        QFile audio(dir.filePath("valid.wav")); QVERIFY(audio.open(QIODevice::WriteOnly)); audio.write(wave(20)); audio.close();
        const QVariantList tracks{
            QVariantMap{{"id", "local:a"}, {"source", "local"}, {"name", "A"}, {"path", audio.fileName()}},
            QVariantMap{{"id", "local:b"}, {"source", "local"}, {"name", "B"}, {"path", dir.filePath("missing.wav")}},
            QVariantMap{{"id", "local:c"}, {"source", "local"}, {"name", "C"}, {"path", audio.fileName()}}};
        PlaylistStore library; QVERIFY(library.createWithTracks("Selection", "", tracks));
        PlayerController player;
        player.playTrack("local:a"); QTRY_VERIFY(player.playing() && !player.busy());
        const auto queue = player.queueEntries();
        const QString current = player.currentQueueEntry();
        QVERIFY(playEntry(player, queue[1].toMap().value("queueId").toString()));
        QTRY_VERIFY(!player.busy());
        QVERIFY(player.playing()); QCOMPARE(player.currentTrack(), QString("local:a"));
        QCOMPARE(player.currentQueueEntry(), current);
        QCOMPARE(player.historyEntries().size(), 1);
        player.playTrack("local:b"); QTRY_VERIFY(!player.busy());
        QCOMPARE(player.queueEntries(), queue);
        QCOMPARE(player.currentQueueEntry(), current);
    }
    void failedSelectionKeepsUnplayedRetainedAudioHistory() {
        QTemporaryDir dir; QVERIFY(dir.isValid());
        QFile audio(dir.filePath("unplayed.wav")); QVERIFY(audio.open(QIODevice::WriteOnly));
        audio.write(wave(20)); audio.close();
        PlayerController player;
        player.importFile(QUrl::fromLocalFile(audio.fileName()));
        QTRY_VERIFY(player.ready() && !player.busy());
        QVERIFY(!player.playing()); QVERIFY(player.historyEntries().isEmpty());
        const QString retained = player.currentTrack();
        QVERIFY(enqueue(player, {{"id", "local:missing"}, {"source", "local"}, {"name", "Missing"},
            {"path", dir.filePath("missing.wav")}}));
        QVERIFY(playEntry(player, player.queueEntries().last().toMap().value("queueId").toString()));
        QTRY_VERIFY(!player.busy()); QVERIFY(!player.error().isEmpty());
        QCOMPARE(player.currentTrack(), retained); QVERIFY(player.ready());
        player.toggle(); QTRY_VERIFY(player.playing());
        QTRY_COMPARE(player.historyEntries().size(), 1);
        QCOMPARE(player.historyEntries().first().toMap().value("id").toString(), retained);
        const auto playedAt = player.historyEntries().first().toMap().value("playedAtMs");
        player.toggle(); QTRY_VERIFY(!player.playing()); player.toggle(); QTRY_VERIFY(player.playing());
        QCOMPARE(player.historyEntries().first().toMap().value("playedAtMs"), playedAt);
    }
    void clearQueueCancelsPendingPlaylistSelection() {
        QTemporaryDir dir; QVERIFY(dir.isValid());
        QFile audio(dir.filePath("retained.wav")); QVERIFY(audio.open(QIODevice::WriteOnly));
        audio.write(wave(20)); audio.close();
        const QVariantMap retained{{"id", "local:retained"}, {"source", "local"}, {"name", "Retained"}, {"path", audio.fileName()}};
        PlaylistStore library; QVERIFY(library.createWithTracks("Delayed selection", "", {retained, onlineTrack("netease", "42")}));
        QTcpServer server; QVERIFY(server.listen(QHostAddress::LocalHost));
        const QString base = "http://127.0.0.1:" + QString::number(server.serverPort());
        bool requested = false, responseSent = false;
        connect(&server, &QTcpServer::newConnection, &server, [&] {
            while (auto *socket = server.nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
                    const auto data = socket->property("data").toByteArray() + socket->readAll(); socket->setProperty("data", data);
                    if (!data.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const auto target = data.split(' ').value(1);
                    const bool resolve = target.startsWith("/song/url/v1");
                    if (resolve) requested = true;
                    const QByteArray body = resolve ? QJsonDocument(QJsonObject{{"code", 200}, {"data", QJsonArray{QJsonObject{{"url", base + "/audio.wav"}}}}}).toJson()
                        : target.startsWith("/audio.wav") ? wave() : QByteArray("{\"code\":200,\"nolyric\":true}");
                    QPointer<QTcpSocket> safe(socket);
                    auto send = [&, safe, body, resolve] {
                        if (resolve) responseSent = true;
                        if (!safe) return;
                        safe->write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                        safe->disconnectFromHost();
                    };
                    if (resolve) QTimer::singleShot(250, &server, send); else send();
                });
            }
        });
        PlayerController player; player.setApiBase(base);
        player.playTrack("local:retained"); QTRY_VERIFY(player.playing() && !player.busy());
        player.playTrack("netease:42"); QTRY_VERIFY(requested); QVERIFY(player.busy());
        player.clearPlaybackQueue(); QVERIFY(player.queueEntries().isEmpty());
        QTRY_VERIFY(responseSent); QTest::qWait(400);
        QTRY_VERIFY(!player.busy());
        QVERIFY(player.queueEntries().isEmpty());
        QCOMPARE(player.currentTrack(), QString("local:retained")); QVERIFY(player.playing());
    }
    void clearQueueCancelsPendingLocalImport() {
        QTemporaryDir dir; QVERIFY(dir.isValid());
        QFile audio(dir.filePath("retained.wav")); QVERIFY(audio.open(QIODevice::WriteOnly));
        audio.write(wave(20)); audio.close();
        const QVariantMap retained{{"id", "local:retained"}, {"source", "local"}, {"name", "Retained"}, {"path", audio.fileName()}};
        PlaylistStore library; QVERIFY(library.createWithTracks("Import cancellation", "", {retained}));
        PlayerController player;
        player.playTrack("local:retained"); QTRY_VERIFY(player.playing() && !player.busy());
        player.importFile(QUrl::fromLocalFile(audio.fileName())); QVERIFY(player.busy());
        player.clearPlaybackQueue(); QVERIFY(player.queueEntries().isEmpty());
        QTRY_VERIFY(player.findChildren<QFutureWatcherBase *>().isEmpty());
        QTRY_VERIFY(!player.busy());
        QVERIFY(player.queueEntries().isEmpty());
        QCOMPARE(player.currentTrack(), QString("local:retained")); QVERIFY(player.playing());
        QCOMPARE(player.tracks().size(), 1);
        const QDir imports(QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation) + "/imports");
        QVERIFY(imports.entryList(QDir::Files).isEmpty());
    }
    void obsoletePlaylistBrowsingStopsFurtherRequests_data() {
        QTest::addColumn<QString>("source"); QTest::addColumn<QString>("stage");
        for (const QString &source : {QString("netease"), QString("kuwo")})
            for (const QString &stage : {QString("detail"), QString("continuation")})
                QTest::newRow(qPrintable(source + '-' + stage)) << source << stage;
    }
    void obsoletePlaylistBrowsingStopsFurtherRequests() {
        QFETCH(QString, source); QFETCH(QString, stage);
        PlaylistBrowsingFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.delayStage = stage;
        PlayerController player(fixture.endpoints()); player.setApiBase(fixture.base());
        const QString prefix = source == "netease" ? QString() : source + ":playlist:";
        const int expectedOldRequests = stage == "detail" ? 1 : 2;
        player.libraryAction("openOnline", {{"id", prefix + "100"}});
        QTRY_COMPARE(fixture.oldRequests, expectedOldRequests);
        player.libraryAction("openOnline", {{"id", prefix + "200"}});
        QTRY_VERIFY(!player.onlinePlaylistLoading());
        QCOMPARE(player.onlinePlaylist().value("resourceId").toString(), QString("200"));
        QTRY_VERIFY(fixture.delayedResponseSent); QTest::qWait(150);
        QCOMPARE(fixture.oldRequests, expectedOldRequests);
        QCOMPARE(player.onlinePlaylist().value("resourceId").toString(), QString("200"));
    }
    void playlistImportContinuesWhenBrowsingChanges() {
        PlaylistBrowsingFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.delayStage = "continuation";
        PlayerController player(fixture.endpoints()); player.setApiBase(fixture.base());
        player.libraryAction("importText", {{"text", "100"}, {"target", "new"}});
        QTRY_COMPARE(fixture.oldRequests, 2);
        player.libraryAction("openOnline", {{"id", "200"}});
        QTRY_VERIFY(!player.onlinePlaylistLoading());
        QTRY_COMPARE(player.tracks().size(), 201);
        QCOMPARE(fixture.oldRequests, 4);
        QCOMPARE(player.onlinePlaylist().value("resourceId").toString(), QString("200"));
    }
    void naturalEndRespectsSleepAndClearedSingleRepeat() {
        QTemporaryDir dir; QVERIFY(dir.isValid());
        QFile audio(dir.filePath("short.wav")); QVERIFY(audio.open(QIODevice::WriteOnly)); audio.write(wave(1)); audio.close();
        PlayerController player;
        for (const QString &id : {QString("local:a"), QString("local:b")})
            QVERIFY(enqueue(player, {{"id", id}, {"source", "local"}, {"name", id}, {"path", audio.fileName()}}));
        const auto first = player.property("queueEntries").toList().first().toMap().value("queueId").toString();
        QVERIFY(playEntry(player, first)); QTRY_VERIFY(player.playing() && !player.busy());
        QVERIFY(QMetaObject::invokeMethod(&player, "sleepAfterCurrentTrack"));
        QTRY_VERIFY_WITH_TIMEOUT(!player.playing(), 5000);
        QCOMPARE(player.currentTrack(), QString("local:a"));
        QCOMPARE(player.property("sleepMode").toString(), QString("off"));
        QCOMPARE(player.property("queueEntries").toList().size(), 2);
        player.setPlaybackMode("single");
        QVERIFY(playEntry(player, first)); QTRY_VERIFY(player.playing() && !player.busy());
        QVERIFY(QMetaObject::invokeMethod(&player, "clearPlaybackQueue"));
        QTRY_VERIFY_WITH_TIMEOUT(!player.playing(), 5000);
        QTest::qWait(150);
        QCOMPARE(player.currentTrack(), QString("local:a"));
        QVERIFY(!player.playing());
    }
    void legacyMigrationKeepsPausedStateAndCorruptFileBytes() {
        const QVariantMap track = onlineTrack("netease", "42");
        QSettings().setValue("playback/session", QJsonDocument(QJsonObject::fromVariantMap(
            {{"track", track}, {"position", 3500}, {"duration", 120000}})).toJson());
        {
            PlayerController player;
            QTRY_COMPARE(player.currentTrack(), QString("netease:42"));
            QCOMPARE(player.property("queueEntries").toList().size(), 1);
            QVERIFY(!player.property("currentQueueEntry").toString().isEmpty());
            QCOMPARE(player.position(), 3500); QVERIFY(!player.playing());
        }
        const QString path = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation) + "/playback.json";
        QFile file(path); QVERIFY(file.open(QIODevice::WriteOnly)); file.write("{broken but important}"); file.close();
        PlayerController player;
        QTRY_COMPARE(player.currentTrack(), QString("netease:42"));
        QVERIFY(enqueue(player, onlineTrack("tencent", "ABC")));
        QCOMPARE(player.property("queueEntries").toList().size(), 0);
        QVERIFY(!player.property("playbackMessage").toString().isEmpty());
        QVERIFY(file.open(QIODevice::ReadOnly)); QCOMPARE(file.readAll(), QByteArray("{broken but important}"));
    }
    void sleepExpirySuppressesDelayedResolverAutoplay() {
        QTcpServer server; QVERIFY(server.listen(QHostAddress::LocalHost));
        const QString base = "http://127.0.0.1:" + QString::number(server.serverPort());
        bool requested = false;
        connect(&server, &QTcpServer::newConnection, &server, [&] {
            while (auto *socket = server.nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
                    auto data = socket->property("data").toByteArray() + socket->readAll(); socket->setProperty("data", data);
                    if (!data.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const auto target = data.split(' ').value(1);
                    const bool resolve = target.startsWith("/song/url/v1");
                    if (resolve) requested = true;
                    const QByteArray body = resolve ? QJsonDocument(QJsonObject{{"code", 200}, {"data", QJsonArray{QJsonObject{{"url", base + "/audio.wav"}}}}}).toJson()
                        : target.startsWith("/audio.wav") ? wave() : QByteArray("{\"code\":200,\"nolyric\":true}");
                    QPointer<QTcpSocket> safe(socket);
                    auto send = [safe, body] {
                        if (!safe) return;
                        safe->write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                        safe->disconnectFromHost();
                    };
                    if (resolve) QTimer::singleShot(250, socket, send); else send();
                });
            }
        });
        PlayerController player; QVERIFY(enqueue(player, onlineTrack("netease", "42")));
        player.setApiBase(base);
        QVERIFY(playEntry(player, player.property("queueEntries").toList().first().toMap().value("queueId").toString()));
        QTRY_VERIFY(requested);
        auto *timer = player.findChild<QObject *>("sleepTimer"); QVERIFY(timer);
        QVERIFY(QMetaObject::invokeMethod(timer, "expired"));
        QTRY_VERIFY_WITH_TIMEOUT(player.ready() && !player.busy(), 15000);
        QTest::qWait(300);
        QVERIFY(!player.playing());
        QCOMPARE(player.property("historyEntries").toList().size(), 0);
        player.toggle(); QTRY_VERIFY(player.playing());
        QTRY_COMPARE(player.property("historyEntries").toList().size(), 1);
    }
};
QTEST_MAIN(V12ControllerTests)
#include "v12_controller_tests.moc"
