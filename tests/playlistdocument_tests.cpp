#include <QtTest>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QUuid>
#include "playlistdocument.h"
#include "playliststore.h"

namespace {
QVariantMap song(const QString &source, const QString &songId, const QString &name) {
    return {{"id", source + ":" + songId}, {"source", source}, {"songId", songId},
            {"name", name}, {"artist", QStringLiteral("歌手")}};
}
QByteArray document(const QVariantList &tracks) {
    return QJsonDocument(QJsonObject::fromVariantMap({{"version", 1}, {"name", QStringLiteral("混合歌单")},
        {"description", QStringLiteral("来自不同曲库的歌曲")}, {"tracks", tracks}})).toJson();
}
QStringList trackIds(const QVariantList &tracks) {
    QStringList result;
    for (const auto &entry : tracks) result.append(entry.toMap().value("id").toString());
    return result;
}
}

class PlaylistDocumentTests : public QObject {
    Q_OBJECT
private slots:
    void initTestCase() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QCoreApplication::setApplicationName("mixed-playlists-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
    }
    void mixedSourcesRoundTripKeepsStableIdentity() {
        const QVariantList incoming{song("netease", "42", "网易云歌曲"),
            song("tencent", "004MpJjW07rAPl", "QQ 歌曲"), song("kuwo", "42", "酷我歌曲")};
        const auto parsed = PlaylistDocument::parse(QString::fromUtf8(document(incoming)));
        QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
        QCOMPARE(parsed.skipped, 0);
        QCOMPARE(parsed.playlist.value("tracks").toList(), incoming);
        QString error;
        const auto encoded = PlaylistDocument::encode(parsed.playlist, {}, error);
        QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(QJsonDocument::fromJson(encoded).object().value("version").toInt(), 1);
        const auto reopened = PlaylistDocument::parse(QString::fromUtf8(encoded));
        QVERIFY2(reopened.error.isEmpty(), qPrintable(reopened.error));
        QCOMPARE(reopened.playlist, parsed.playlist);
    }
    void idsCanBeReadFromNamespaceWithoutSongId() {
        auto qq = song("tencent", "004MpJjW07rAPl", "QQ 歌曲"); qq.remove("songId");
        auto kuwo = song("kuwo", "397242799", "酷我歌曲"); kuwo.remove("songId");
        const auto parsed = PlaylistDocument::parse(QString::fromUtf8(document({qq, kuwo})));
        QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
        const auto tracks = parsed.playlist.value("tracks").toList();
        QCOMPARE(tracks.size(), 2);
        QCOMPARE(tracks[0].toMap().value("songId").toString(), QString("004MpJjW07rAPl"));
        QCOMPARE(tracks[1].toMap().value("songId").toString(), QString("397242799"));
    }
    void roundTripPreservesOnlyValidOnlineDurations() {
        auto shortSong = song("kuwo", "42", "Short song"); shortSong["duration"] = 11;
        auto qqSong = song("tencent", "MID42", "QQ song"); qqSong["duration"] = 254.47;
        auto invalid = song("kuwo", "43", "Invalid duration"); invalid["duration"] = -5;
        auto oversized = song("kuwo", "44", "Invalid duration"); oversized["duration"] = 86401;
        const auto parsed = PlaylistDocument::parse(QString::fromUtf8(document({shortSong, qqSong, invalid, oversized})));
        QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
        const auto tracks = parsed.playlist.value("tracks").toList();
        QCOMPARE(tracks[0].toMap().value("duration").toDouble(), 11.0);
        QCOMPARE(tracks[1].toMap().value("duration").toDouble(), 254.47);
        QVERIFY(!tracks[2].toMap().contains("duration")); QVERIFY(!tracks[3].toMap().contains("duration"));
        QString error;
        const auto encoded = PlaylistDocument::encode(parsed.playlist, {}, error);
        QVERIFY2(error.isEmpty(), qPrintable(error));
        const auto reopened = PlaylistDocument::parse(QString::fromUtf8(encoded));
        QCOMPARE(reopened.playlist, parsed.playlist);
    }
    void sourceNamespacesDeduplicateOnlyWithinTheirOwnCatalog() {
        const auto netease = song("netease", "42", "网易云歌曲");
        const auto kuwo = song("kuwo", "42", "酷我歌曲");
        const auto qq = song("tencent", "004MpJjW07rAPl", "QQ 歌曲");
        const auto parsed = PlaylistDocument::parse(QString::fromUtf8(document({netease, kuwo, qq, kuwo, qq})));
        QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
        QCOMPARE(parsed.skipped, 2);
        QCOMPARE(trackIds(parsed.playlist.value("tracks").toList()), QStringList({"netease:42", "kuwo:42", "tencent:004MpJjW07rAPl"}));
    }
    void unknownSourcesAndMalformedIdsAreSkippedWithoutLosingValidSongs() {
        const QVariantList tracks{song("netease", "42", "已有歌曲"),
            song("kuwo", "-1", "无效酷我 ID"), song("kuwo", "42&token=x", "非法酷我 ID"),
            song("tencent", "MID/another", "非法 QQ ID"), song("tencent", "https://example.com", "URL 不能充当 ID"),
            song("unsupported", "42", "未知曲库")};
        const auto parsed = PlaylistDocument::parse(QString::fromUtf8(document(tracks)));
        QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
        QCOMPARE(parsed.skipped, 5);
        QCOMPARE(trackIds(parsed.playlist.value("tracks").toList()), QStringList({"netease:42"}));
    }
    void exportsSelectedSongsWithoutTransientPlaybackUrls() {
        auto qq = song("tencent", "004MpJjW07rAPl", "QQ 歌曲");
        qq["url"] = "https://cdn.example/qq.m4a?secret=signature";
        qq["mediaUrl"] = qq.value("url");
        qq["position"] = 23000;
        const QVariantMap playlist{{"name", "导出歌单"}, {"tracks", QVariantList{song("netease", "42", "已有歌曲"), qq}}};
        QString error;
        const auto bytes = PlaylistDocument::encode(playlist, {"tencent:004MpJjW07rAPl"}, error);
        QVERIFY2(error.isEmpty(), qPrintable(error));
        QVERIFY(!bytes.contains("signature")); QVERIFY(!bytes.contains("mediaUrl")); QVERIFY(!bytes.contains("position"));
        const auto parsed = PlaylistDocument::parse(QString::fromUtf8(bytes));
        QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
        QCOMPARE(trackIds(parsed.playlist.value("tracks").toList()), QStringList({"tencent:004MpJjW07rAPl"}));
    }
    void previousExportsStillReadWithAndWithoutVersion() {
        const auto track = song("netease", "42", "旧歌单歌曲");
        auto root = QJsonDocument::fromJson(document({track})).object();
        for (const bool includeVersion : {true, false}) {
            if (!includeVersion) root.remove("version");
            const auto parsed = PlaylistDocument::parse(QString::fromUtf8(QJsonDocument(root).toJson()));
            QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
            QCOMPARE(parsed.playlist.value("tracks").toList(), QVariantList{track});
        }
        QCOMPARE(PlaylistDocument::parse("https://music.163.com/#/playlist?id=42").onlineId, QString("42"));
    }
    void publicPlaylistLinksKeepTheirPlatform_data() {
        QTest::addColumn<QString>("text");
        QTest::addColumn<QString>("id");
        QTest::newRow("netease-numeric") << QString("42") << QString("42");
        QTest::newRow("netease-share") << QStringLiteral("推荐：https://music.163.com/#/playlist?id=42&uid=100") << QString("42");
        QTest::newRow("qq-desktop") << QString("https://y.qq.com/n/ryqq/playlist/7593082970") << QString("tencent:playlist:7593082970");
        QTest::newRow("qq-mobile") << QString("https://y.qq.com/n/m/detail/taoge/index.html?id=7593082970") << QString("tencent:playlist:7593082970");
        QTest::newRow("qq-mobile-share") << QString("https://i.y.qq.com/n2/m/share/details/taoge.html?id=7593082970&ADTAG=copy") << QString("tencent:playlist:7593082970");
        QTest::newRow("kuwo-desktop") << QString("https://www.kuwo.cn/playlist_detail/3677150229") << QString("kuwo:playlist:3677150229");
        QTest::newRow("kuwo-mobile") << QString("https://m.kuwo.cn/h5app/playlist/3677150229") << QString("kuwo:playlist:3677150229");
    }
    void publicPlaylistLinksKeepTheirPlatform() {
        QFETCH(QString, text); QFETCH(QString, id);
        const auto parsed = PlaylistDocument::parse(text);
        QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
        QCOMPARE(parsed.onlineId, id);
        QVERIFY(parsed.playlist.isEmpty());
    }
    void rejectsUnrelatedLinksAndInvalidContainerIds() {
        for (const auto &link : {"https://y.qq.com.example/n/ryqq/playlist/42",
             "https://example.com/https://music.163.com/playlist?id=42",
             "https://y.qq.com/n/ryqq/songDetail/42", "https://www.kuwo.cn/play_detail/42",
             "https://y.qq.com/n/ryqq/playlist/0", "https://www.kuwo.cn/playlist_detail/-1"}) {
            const auto parsed = PlaylistDocument::parse(link);
            QVERIFY2(!parsed.error.isEmpty(), link);
            QVERIFY(parsed.onlineId.isEmpty());
        }
    }
    void localAudioAndOnlineSongsCanRoundTripTogether() {
        QTemporaryDir files; QVERIFY(files.isValid());
        QFile audio(files.filePath("sample.wav")); QVERIFY(audio.open(QIODevice::WriteOnly));
        audio.write("RIFF1234WAVE1234"); audio.close();
        const QVariantMap local{{"id", "local:sample"}, {"source", "local"}, {"name", "本地音频"},
            {"artist", "本地文件"}, {"path", audio.fileName()}};
        const QVariantList tracks{local, song("tencent", "004MpJjW07rAPl", "QQ 歌曲"), song("kuwo", "42", "酷我歌曲")};
        const auto parsed = PlaylistDocument::parse(QString::fromUtf8(document(tracks)));
        QVERIFY2(parsed.error.isEmpty(), qPrintable(parsed.error));
        QCOMPARE(parsed.skipped, 0);
        QCOMPARE(parsed.playlist.value("tracks").toList(), tracks);
    }
    void mixedPlaylistCopyMoveOrderAndRestart() {
        const QVariantList songs{song("netease", "42", "网易云歌曲"),
            song("tencent", "004MpJjW07rAPl", "QQ 歌曲"), song("kuwo", "42", "酷我歌曲")};
        QString sourceId, targetId;
        {
            PlaylistStore store;
            QVERIFY(store.createWithTracks("源歌单", "混合来源", songs)); sourceId = store.activeId();
            QVERIFY(store.createWithTracks("目标歌单", {}, {songs[0]})); targetId = store.activeId();
            QVERIFY(store.transfer(sourceId, targetId, {"netease:42", "tencent:004MpJjW07rAPl"}, false));
            QCOMPARE(trackIds(store.tracks(targetId)), QStringList({"netease:42", "tencent:004MpJjW07rAPl"}));
            QCOMPARE(store.tracks(sourceId), songs);
            QVERIFY(store.transfer(sourceId, targetId, {"kuwo:42"}, true));
            QVERIFY(store.moveTrack(targetId, 2, 0));
            QCOMPARE(trackIds(store.tracks(targetId)), QStringList({"kuwo:42", "netease:42", "tencent:004MpJjW07rAPl"}));
            QCOMPARE(trackIds(store.tracks(sourceId)), QStringList({"netease:42", "tencent:004MpJjW07rAPl"}));
            QCOMPARE(store.addTracks(targetId, {songs[2], songs[1]}), 0);
        }
        PlaylistStore reopened;
        QCOMPARE(reopened.activeId(), targetId);
        QCOMPARE(trackIds(reopened.tracks(targetId)), QStringList({"kuwo:42", "netease:42", "tencent:004MpJjW07rAPl"}));
        QCOMPARE(reopened.tracks(targetId).last().toMap().value("songId").toString(), QString("004MpJjW07rAPl"));
        QCOMPARE(reopened.tracks(sourceId).size(), 2);
    }
};
QTEST_GUILESS_MAIN(PlaylistDocumentTests)
#include "playlistdocument_tests.moc"
