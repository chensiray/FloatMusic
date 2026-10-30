#include <QtTest>
#include <QDataStream>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QProcess>
#include <QProcessEnvironment>
#include <QSettings>
#include <QStandardPaths>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUuid>
#include "offlinecache.h"
#include "playercontroller.h"

static QVariantMap cachedTrack(const QString &id) {
    return {{"id", "netease:" + id}, {"source", "netease"}, {"songId", id},
            {"name", "Offline " + id}, {"artist", "Fixture artist"}, {"duration", 6}};
}

static MusicApi::Endpoints localEndpoints(const QString &base) {
    MusicApi::Endpoints endpoints{base, base, 300};
    endpoints.qqMusicu = base;
    return endpoints;
}

class OfflinePlaybackFixture : public QTcpServer {
public:
    QByteArray audio;
    int requests = 0;
    bool deny = false, denyLyrics = false;
    OfflinePlaybackFixture() {
        QDataStream stream(&audio, QIODevice::WriteOnly);
        stream.setByteOrder(QDataStream::LittleEndian);
        const quint32 bytes = 32000 * 6;
        stream.writeRawData("RIFF", 4); stream << quint32(36 + bytes);
        stream.writeRawData("WAVEfmt ", 8); stream << quint32(16) << quint16(1) << quint16(1)
            << quint32(16000) << quint32(32000) << quint16(2) << quint16(16);
        stream.writeRawData("data", 4); stream << bytes;
        audio.append(QByteArray(bytes, '\0'));
        listen(QHostAddress::LocalHost);
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    const auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true); ++requests;
                    const auto path = request.split(' ').value(1);
                    QByteArray body = audio;
                    if (path.contains("lyric")) {
                        body = R"({"code":200,"lrc":{"lyric":"[00:00.00]Original start\n[00:02.00]Original second"},"tlyric":{"lyric":"[00:00.00]Translated start\n[00:02.00]Translated second"}})";
                    } else if (path.startsWith("/song/url")) {
                        body = QJsonDocument(QJsonObject{{"code", 200}, {"data", QJsonArray{QJsonObject{
                            {"url", base() + "/audio.wav"}, {"level", "standard"}, {"type", "wav"}}}}}).toJson();
                    }
                    const bool rejected=deny||(denyLyrics&&path.contains("lyric"));
                    if (rejected) body = "offline fixture denies network access";
                    socket->write(QByteArray(rejected ? "HTTP/1.1 503 Offline\r\n" : "HTTP/1.1 200 OK\r\n")
                        + "Connection: close\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\n\r\n" + body);
                    socket->disconnectFromHost();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
    MusicApi::Audio resolved() const {
        MusicApi::Audio result; result.url = QUrl(base() + "/audio.wav");
        result.quality = "lossless"; result.format = "wav"; result.expectedDurationSeconds = 6;
        return result;
    }
};

class OfflineControllerTests : public QObject {
    Q_OBJECT
private:
    QString dataDirectory;
private slots:
    void init() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
        const auto childApplication = qEnvironmentVariable("FLOATMUSIC_OFFLINE_TEST_APP");
        QCoreApplication::setApplicationName(childApplication.isEmpty()
            ? "offline-controller-" + QUuid::createUuid().toString(QUuid::WithoutBraces) : childApplication);
        dataDirectory = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    }
    void cleanup() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_OFFLINE_TEST_APP")) {
            QSettings().clear();
            QVERIFY(dataDirectory.contains("offline-controller-"));
            if (QDir(dataDirectory).exists()) QVERIFY(QDir(dataDirectory).removeRecursively());
        }
    }
    void restartProcessPlaysSeeksAndChangesQueueWithoutNetwork() {
        OfflinePlaybackFixture fixture;
        {
            OfflineCache cache(localEndpoints(fixture.base()));
            for (const auto &id : {QString("101"), QString("102")}) {
                const auto key = cache.enqueue(cachedTrack(id), "hires", true, fixture.resolved());
                QTRY_COMPARE(cache.entry(key).value("state").toString(), QString("complete"));
                QTRY_COMPARE(cache.entry(key).value("translationState").toString(), QString("available"));
            }
        }
        const auto before = fixture.requests;
        fixture.deny = true;
        QProcess child;
        auto environment = QProcessEnvironment::systemEnvironment();
        environment.insert("FLOATMUSIC_OFFLINE_TEST_APP", QCoreApplication::applicationName());
        environment.insert("FLOATMUSIC_OFFLINE_TEST_BASE", fixture.base());
        child.setProcessEnvironment(environment);
        const auto report = dataDirectory + "/restart-probe.txt";
        child.start(QCoreApplication::applicationFilePath(), {"offlineRestartProbe", "-platform", "offscreen", "-o", report + ",txt"});
        QVERIFY(child.waitForStarted());
        QTRY_COMPARE_WITH_TIMEOUT(child.state(), QProcess::NotRunning, 30000);
        QFile result(report); QVERIFY(result.open(QIODevice::ReadOnly));
        const auto details = result.readAll();
        QVERIFY2(child.exitStatus() == QProcess::NormalExit && child.exitCode() == 0, details.constData());
        QCOMPARE(fixture.requests, before);
    }
    void offlineRestartProbe() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_OFFLINE_TEST_APP")) QSKIP("Invoked in the restart child process only");
        PlayerController player(localEndpoints(qEnvironmentVariable("FLOATMUSIC_OFFLINE_TEST_BASE")));
        player.setVolume(0); player.setSourceQuality("netease", "hires");
        for (const auto &id : {QString("101"), QString("102")}) player.enqueueTrack(cachedTrack(id), "tail");
        QCOMPARE(player.queueEntries().size(), 2);
        player.playQueueEntry(player.queueEntries().first().toMap().value("queueId").toString());
        QTRY_VERIFY_WITH_TIMEOUT(player.ready() && player.playing() && !player.busy(), 15000);
        QTRY_VERIFY(player.position() > 0);
        QCOMPARE(player.currentTrack(), QString("netease:101"));
        QVERIFY(player.qualityInfo().contains(QStringLiteral("无损")));
        QCOMPARE(player.duration(), qint64(6000));
        QVERIFY(player.lyrics().contains("Original second"));
        QVERIFY(player.translation().contains("Translated second"));
        player.toggle(); QTRY_VERIFY(!player.playing());
        player.seek(2500); QTRY_VERIFY(player.position() >= 2400);
        QTRY_COMPARE(player.currentLyricIndex(), 1);
        player.next(); QTRY_COMPARE(player.currentTrack(), QString("netease:102"));
        QTRY_VERIFY(player.ready() && !player.busy());
        player.previous(); QTRY_COMPARE(player.currentTrack(), QString("netease:101"));
        QTRY_VERIFY(player.ready() && !player.busy());
        QString firstKey;
        for (const auto &value : player.cacheEntries()) {
            const auto entry = value.toMap();
            if (entry.value("track").toMap().value("songId") == "101") firstKey = entry.value("key").toString();
        }
        QVERIFY(!firstKey.isEmpty()); player.removeCache(firstKey);
        bool deferred = false;
        for (const auto &value : player.cacheEntries())
            if (value.toMap().value("key") == firstKey) deferred = value.toMap().value("pendingDelete").toBool();
        QVERIFY(deferred);
        player.next(); QTRY_COMPARE(player.currentTrack(), QString("netease:102"));
        QTRY_COMPARE(player.cacheEntries().size(), 1);
        QTRY_VERIFY(player.ready() && !player.busy());
        QVERIFY(player.lyrics().contains("Original second"));
    }
    void undecodableCacheIsInvalidatedWithoutLooping() {
        OfflinePlaybackFixture fixture;
        fixture.audio = "RIFF0000WAVE" + QByteArray(64, '\0');
        QString key;
        {
            OfflineCache cache(localEndpoints(fixture.base()));
            key = cache.enqueue(cachedTrack("103"), "lossless", true, fixture.resolved());
            QTRY_COMPARE(cache.entry(key).value("state").toString(), QString("complete"));
            QTRY_COMPARE(cache.entry(key).value("translationState").toString(), QString("available"));
        }
        fixture.deny = true;
        PlayerController player(localEndpoints(fixture.base())); player.setVolume(0);
        player.playCached(key);
        QTRY_VERIFY_WITH_TIMEOUT(!player.busy() && !player.error().isEmpty(), 15000);
        QCOMPARE(player.cacheEntries().first().toMap().value("state").toString(), QString("failed"));
        const auto requests = fixture.requests;
        QTest::qWait(500);
        QCOMPARE(fixture.requests, requests);
        QVERIFY(!player.ready());
    }
    void automaticCacheOfAudibleTrackRemainsProtected() {
        OfflinePlaybackFixture fixture;
        PlayerController player(localEndpoints(fixture.base())); player.setVolume(0);
        player.setApiBase(fixture.base());
        player.enqueueTrack(cachedTrack("104"), "tail");
        player.playQueueEntry(player.queueEntries().first().toMap().value("queueId").toString());
        QTRY_VERIFY_WITH_TIMEOUT(player.ready() && player.playing() && !player.busy(), 15000);
        QTRY_COMPARE(player.cacheEntries().size(), 1);
        QTRY_COMPARE(player.cacheEntries().first().toMap().value("state").toString(), QString("complete"));
        const auto key = player.cacheEntries().first().toMap().value("key").toString();
        player.setCacheSettings({{"automaticLimitBytes", 1}});
        QCOMPARE(player.cacheEntries().size(), 1);
        player.removeCache(key);
        QCOMPARE(player.cacheEntries().size(), 1);
        QVERIFY(player.cacheEntries().first().toMap().value("pendingDelete").toBool());
        QVERIFY(player.playing());
    }
    void refreshedCacheLyricsUpdateTheAudibleTrack() {
        OfflinePlaybackFixture fixture; fixture.denyLyrics=true;
        QString key;
        {
            OfflineCache cache(localEndpoints(fixture.base()));
            key=cache.enqueue(cachedTrack("106"),"lossless",true,fixture.resolved());
            QTRY_COMPARE(cache.entry(key).value("lyricState").toString(),QString("failed"));
        }
        PlayerController player(localEndpoints(fixture.base()));player.setVolume(0);player.playCached(key);
        QTRY_VERIFY_WITH_TIMEOUT(player.ready()&&!player.busy(),15000);
        QVERIFY(player.lyrics().isEmpty());player.setLyricOffset(1000);
        fixture.denyLyrics=false;player.retryCachedLyrics(key);
        QTRY_VERIFY(player.lyrics().contains("Original second"));
        QVERIFY(player.translation().contains("Translated second"));QCOMPARE(player.lyricOffset(),1000);
    }
    void cachePagePlaysTheSelectedQuality() {
        OfflinePlaybackFixture fixture;
        QString selected;
        {
            OfflineCache cache(localEndpoints(fixture.base()));
            auto audio = fixture.resolved(); audio.quality = "standard";
            const auto standard = cache.enqueue(cachedTrack("105"), "standard", true, audio);
            QTRY_COMPARE(cache.entry(standard).value("state").toString(), QString("complete"));
            selected = cache.enqueue(cachedTrack("105"), "lossless", true, fixture.resolved());
            QTRY_COMPARE(cache.entry(selected).value("state").toString(), QString("complete"));
            QTRY_COMPARE(cache.entry(selected).value("lyricState").toString(), QString("available"));
        }
        fixture.deny = true;
        PlayerController player(localEndpoints(fixture.base())); player.setVolume(0);
        player.playCached(selected);
        QTRY_VERIFY_WITH_TIMEOUT(player.ready() && player.playing() && !player.busy(), 15000);
        QVERIFY(player.qualityInfo().contains(QStringLiteral("实际：无损")));
    }
};
QTEST_MAIN(OfflineControllerTests)
#include "offlinecache_controller_tests.moc"
