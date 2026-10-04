#include <QtTest>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>
#include <QStandardPaths>
#include <QUuid>
#include "playercontroller.h"

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
        QCOMPARE(controller.property("qualitySelectable").toBool(), source == "netease");
        if (source != "netease") QVERIFY(controller.qualityInfo().contains(QStringLiteral("普通")));
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
                QVERIFY(!controller.qualitySelectable());
                QVERIFY(controller.qualityInfo().contains(QStringLiteral("普通")));
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
