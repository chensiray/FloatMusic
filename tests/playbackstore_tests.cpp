#include <QtTest>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSet>
#include <QTemporaryDir>
#include <QUuid>
#include "playbackstore.h"

namespace {
QVariantMap song(const QString &source, const QString &id, const QString &name = "Song") {
    return {{"id", source + ':' + id}, {"source", source}, {"songId", id},
            {"name", name}, {"artist", "Artist"}};
}
QString queueId(const PlaybackStore &store, int index) {
    return store.entries().at(index).toMap().value("queueId").toString();
}
QStringList songIds(const QVariantList &entries) {
    QStringList result;
    for (const auto &entry : entries) result.append(entry.toMap().value("id").toString());
    return result;
}
QByteArray readFile(const QString &path) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return {};
    return file.readAll();
}
bool writeFile(const QString &path, const QByteArray &bytes) {
    QFile file(path);
    return file.open(QIODevice::WriteOnly) && file.write(bytes) == bytes.size();
}
}

class PlaybackStoreTests : public QObject {
    Q_OBJECT
private slots:
    void duplicateSongsHavePersistentIndependentOccurrences() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(!store.initialized());
        const auto a = song("netease", "42");
        QVERIFY(store.replaceQueue({a, a, song("kuwo", "42")}, 1));
        const QString first = queueId(store, 0), current = queueId(store, 1);
        QVERIFY(!QUuid(first).isNull());
        QVERIFY(first != current);
        QCOMPARE(store.currentEntryId(), current);
        QVERIFY(store.move(first, 2));
        QCOMPARE(store.currentIndex(), 0);
        QVERIFY(store.remove(first));
        QCOMPARE(store.entries().size(), 2);
        PlaybackStore restored(path);
        QVERIFY(restored.error().isEmpty());
        QVERIFY(restored.initialized());
        QCOMPARE(restored.entries(), store.entries());
        QCOMPARE(restored.currentEntryId(), current);
        QCOMPARE(restored.currentIndex(), 0);
    }
    void insertionAndReorderingPreserveCurrentOccurrence() {
        QTemporaryDir dir;
        PlaybackStore store(dir.filePath("playback.json"));
        QVERIFY(store.replaceQueue({song("netease", "1"), song("netease", "2")}, 0));
        const QString current = store.currentEntryId();
        QSignalSpy queueChanged(&store, &PlaybackStore::queueChanged);
        QSignalSpy historyChanged(&store, &PlaybackStore::historyChanged);
        const QString next = store.enqueue(song("kuwo", "3"), true);
        QVERIFY(!next.isEmpty());
        QVERIFY(!store.enqueue(song("tencent", "MID4"), false).isEmpty());
        QCOMPARE(songIds(store.entries()), QStringList({"netease:1", "kuwo:3", "netease:2", "tencent:MID4"}));
        QCOMPARE(store.nextEntry(1, "sequential", true), next);
        QCOMPARE(store.currentEntryId(), current);
        QVERIFY(store.move(next, 0));
        QCOMPARE(store.currentIndex(), 1);
        QCOMPARE(store.currentEntryId(), current);
        QCOMPARE(queueChanged.count(), 3);
        QCOMPARE(historyChanged.count(), 0);
    }
    void removingCurrentPreservesNextPositionAcrossRestart() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(store.replaceQueue({song("netease", "1"), song("netease", "2"), song("netease", "3")}, 1));
        const QString first = queueId(store, 0), third = queueId(store, 2);
        QVERIFY(store.remove(store.currentEntryId()));
        QVERIFY(store.currentEntryId().isEmpty());
        QCOMPARE(store.currentIndex(), -1);
        QCOMPARE(store.nextEntry(1, "sequential", true), third);
        QCOMPARE(store.nextEntry(-1, "sequential", false), first);
        PlaybackStore restored(path);
        QCOMPARE(restored.nextEntry(1, "single", true), third);
        QCOMPARE(restored.nextEntry(-1, "loop", false), first);
        QVERIFY(restored.remove(first));
        QCOMPARE(restored.nextEntry(1, "sequential", true), third);
        QVERIFY(restored.setCurrent(third));
        QVERIFY(restored.remove(third));
        QVERIFY(restored.nextEntry(1, "loop", false).isEmpty());
    }
    void movingAcrossGapAndPlayNextKeepLogicalPosition() {
        QTemporaryDir dir;
        PlaybackStore store(dir.filePath("playback.json"));
        QVERIFY(store.replaceQueue({song("netease", "1"), song("netease", "2"),
                                    song("netease", "3"), song("netease", "4")}, 1));
        const QString first = queueId(store, 0), third = queueId(store, 2), fourth = queueId(store, 3);
        QVERIFY(store.remove(store.currentEntryId()));
        QVERIFY(store.move(fourth, 0));
        QCOMPARE(store.nextEntry(1, "sequential", true), third);
        QVERIFY(store.move(first, 2));
        QCOMPARE(store.nextEntry(1, "sequential", true), third);
        const QString inserted = store.enqueue(song("kuwo", "5"), true);
        QVERIFY(!inserted.isEmpty());
        QCOMPARE(store.nextEntry(1, "sequential", true), inserted);
        QVERIFY(store.setCurrent(inserted)); // The explicit-next request is consumed on selection.
        QVERIFY(store.setCurrent(first));
        QVERIFY(store.remove(first));
        QVERIFY(store.nextEntry(1, "sequential", true).isEmpty());
        const QString appended = store.enqueue(song("kuwo", "6"), false);
        QCOMPARE(store.nextEntry(1, "sequential", true), appended);
    }
    void reorderingBeforeRemovalGapDoesNotChangeNext_data() {
        QTest::addColumn<int>("from");
        QTest::addColumn<int>("to");
        QTest::newRow("move-within-before-gap") << 0 << 1;
        QTest::newRow("same-position-before-gap") << 1 << 1;
        QTest::newRow("move-from-after-gap") << 3 << 0;
    }
    void reorderingBeforeRemovalGapDoesNotChangeNext() {
        QFETCH(int, from);
        QFETCH(int, to);
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(store.replaceQueue({song("netease", "1"), song("netease", "2"),
                                    song("netease", "3"), song("netease", "4"),
                                    song("netease", "5")}, 2));
        const QString next = queueId(store, 3);
        QVERIFY(store.remove(store.currentEntryId()));
        QVERIFY(store.move(queueId(store, from), to));
        QCOMPARE(store.nextEntry(1, "sequential", true), next);
        PlaybackStore restored(path);
        QCOMPARE(restored.nextEntry(1, "sequential", true), next);
    }
    void sequentialLoopAndSingleRespectAutomaticBoundary_data() {
        QTest::addColumn<QString>("mode");
        QTest::addColumn<bool>("automatic");
        QTest::addColumn<int>("current");
        QTest::addColumn<int>("direction");
        QTest::addColumn<int>("expected");
        QTest::newRow("sequential-auto-end") << "sequential" << true << 2 << 1 << -1;
        QTest::newRow("sequential-manual-wrap") << "sequential" << false << 2 << 1 << 0;
        QTest::newRow("sequential-back-wrap") << "sequential" << false << 0 << -1 << 2;
        QTest::newRow("sequential-middle") << "sequential" << true << 0 << 1 << 1;
        QTest::newRow("loop-auto-wrap") << "loop" << true << 2 << 1 << 0;
        QTest::newRow("single-auto-repeat") << "single" << true << 1 << 1 << 1;
        QTest::newRow("single-manual-next") << "single" << false << 1 << 1 << 2;
        QTest::newRow("single-manual-wrap") << "single" << false << 2 << 1 << 0;
        QTest::newRow("unselected-start") << "sequential" << true << -1 << 1 << 0;
        QTest::newRow("unselected-back") << "loop" << false << -1 << -1 << 2;
    }
    void sequentialLoopAndSingleRespectAutomaticBoundary() {
        QFETCH(QString, mode);
        QFETCH(bool, automatic);
        QFETCH(int, current);
        QFETCH(int, direction);
        QFETCH(int, expected);
        QTemporaryDir dir;
        PlaybackStore store(dir.filePath("playback.json"));
        QVERIFY(store.replaceQueue({song("netease", "1"), song("netease", "2"), song("netease", "3")}, current));
        QCOMPARE(store.nextEntry(direction, mode, automatic), expected < 0 ? QString() : queueId(store, expected));
        QCOMPARE(store.currentIndex(), current); // Looking ahead never starts or selects a song.
    }
    void shuffleExcludesCurrentOccurrenceButAllowsSameSong() {
        QTemporaryDir dir;
        PlaybackStore store(dir.filePath("playback.json"));
        const auto a = song("netease", "1");
        QVERIFY(store.replaceQueue({a, a}, 0));
        const QString other = queueId(store, 1);
        for (int i = 0; i < 20; ++i) QCOMPARE(store.nextEntry(1, "shuffle", true), other);
        QVERIFY(store.remove(other));
        QCOMPARE(store.nextEntry(1, "shuffle", true), store.currentEntryId());
        QVERIFY(store.clearQueue());
        QVERIFY(store.nextEntry(1, "shuffle", true).isEmpty());
    }
    void explicitNextOverridesModesAndSurvivesReorderAndRestart_data() {
        QTest::addColumn<QString>("mode");
        QTest::addColumn<bool>("automatic");
        for (const auto &mode : {"sequential", "loop", "single", "shuffle"}) {
            QTest::newRow(qPrintable(QString(mode) + "-automatic")) << QString(mode) << true;
            QTest::newRow(qPrintable(QString(mode) + "-manual")) << QString(mode) << false;
        }
    }
    void explicitNextOverridesModesAndSurvivesReorderAndRestart() {
        QFETCH(QString, mode);
        QFETCH(bool, automatic);
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        const auto duplicate = song("netease", "1");
        QVERIFY(store.replaceQueue({duplicate, song("netease", "2")}, 0));
        const QString current = store.currentEntryId();
        const QString older = store.enqueue(duplicate, true);
        const QString newest = store.enqueue(duplicate, true);
        const QString tail = store.enqueue(duplicate, false);
        QVERIFY(!older.isEmpty() && !newest.isEmpty() && !tail.isEmpty());
        QVERIFY(newest != older);
        QVERIFY(store.move(newest, store.entries().size() - 1));
        const QByteArray saved = readFile(path);
        for (int i = 0; i < 5; ++i) QCOMPARE(store.nextEntry(1, mode, automatic), newest);
        QCOMPARE(store.currentEntryId(), current);
        QCOMPARE(readFile(path), saved);
        PlaybackStore restored(path);
        QVERIFY(restored.error().isEmpty());
        QCOMPARE(restored.nextEntry(1, mode, automatic), newest);
        QVERIFY(!restored.setCurrent("missing"));
        QCOMPARE(restored.nextEntry(1, mode, automatic), newest);
        QVERIFY(restored.setCurrent(current));
        QCOMPARE(restored.nextEntry(1, mode, automatic), newest);
        QVERIFY(restored.setCurrent(newest));
        QCOMPARE(restored.nextEntry(1, mode, automatic), older);
        QVERIFY(restored.remove(older));
        QCOMPARE(restored.nextEntry(1, "single", true), newest);
        QVERIFY(restored.nextEntry(1, "sequential", true).isEmpty());
    }
    void previousNavigationDoesNotUseOrConsumeExplicitNext() {
        QTemporaryDir dir;
        PlaybackStore store(dir.filePath("playback.json"));
        QVERIFY(store.replaceQueue({song("netease", "1"), song("netease", "2"),
                                    song("netease", "3")}, 1));
        const QString previous = queueId(store, 0), current = store.currentEntryId();
        const QString pending = store.enqueue(song("kuwo", "4"), true);
        QVERIFY(!pending.isEmpty());
        QCOMPARE(store.nextEntry(-1, "sequential", false), previous);
        QCOMPARE(store.nextEntry(-1, "single", true), current);
        QCOMPARE(store.nextEntry(1, "single", true), pending);
    }
    void removingOccurrencesPreservesOnlyRemainingExplicitNextRequests() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(store.replaceQueue({song("netease", "1"), song("netease", "2")}, 0));
        const QString ordinary = queueId(store, 1);
        const QString older = store.enqueue(song("kuwo", "3"), true);
        const QString newest = store.enqueue(song("kuwo", "4"), true);
        QVERIFY(!older.isEmpty() && !newest.isEmpty());
        QVERIFY(store.remove(newest));
        QCOMPARE(store.nextEntry(1, "single", true), older);
        QVERIFY(store.remove(store.currentEntryId()));
        QCOMPARE(store.nextEntry(1, "single", true), older);
        PlaybackStore restored(path);
        QVERIFY(restored.error().isEmpty());
        QCOMPARE(restored.nextEntry(1, "shuffle", true), older);
        QVERIFY(restored.remove(older));
        QCOMPARE(restored.nextEntry(1, "single", true), ordinary);
    }
    void replacingAndClearingQueueDiscardExplicitNextRequests() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(store.replaceQueue({song("netease", "1")}, 0));
        QVERIFY(!store.enqueue(song("kuwo", "2"), true).isEmpty());
        QVERIFY(store.replaceQueue({song("netease", "3"), song("netease", "4")}, 0));
        QCOMPARE(store.nextEntry(1, "single", true), store.currentEntryId());
        QVERIFY(!store.enqueue(song("kuwo", "5"), true).isEmpty());
        QVERIFY(store.clearQueue());
        const QString tail = store.enqueue(song("kuwo", "6"), false);
        QVERIFY(!tail.isEmpty());
        QCOMPARE(store.nextEntry(1, "single", true), tail);
        PlaybackStore restored(path);
        QVERIFY(restored.error().isEmpty());
        QCOMPARE(restored.nextEntry(1, "single", true), tail);
    }
    void failedSelectionSaveDoesNotConsumeExplicitNext() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json"), backup = dir.filePath("kept.json");
        PlaybackStore store(path);
        QVERIFY(store.replaceQueue({song("netease", "1")}, 0));
        const QString current = store.currentEntryId();
        const QString older = store.enqueue(song("kuwo", "2"), true);
        const QString newest = store.enqueue(song("kuwo", "3"), true);
        QVERIFY(!older.isEmpty() && !newest.isEmpty());
        QCOMPARE(store.nextEntry(1, "single", true), newest);
        const QByteArray saved = readFile(path);
        QVERIFY(QFile::rename(path, backup));
        QVERIFY(QDir().mkpath(path));
        QVERIFY(!store.setCurrent(newest));
        QCOMPARE(store.currentEntryId(), current);
        QCOMPARE(store.nextEntry(1, "single", true), newest);
        QCOMPARE(readFile(backup), saved);
        QVERIFY(QDir().rmdir(path));
        QVERIFY(QFile::rename(backup, path));
        QVERIFY(store.setCurrent(newest));
        QCOMPARE(store.nextEntry(1, "single", true), older);
    }
    void versionOneWithoutPriorityFieldRemainsReadable() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(store.replaceQueue({song("netease", "1"), song("netease", "2")}, 0));
        QVERIFY(store.recordPlayed(song("kuwo", "3"), 10));
        auto root = QJsonDocument::fromJson(readFile(path)).object();
        root.remove("priorityEntries");
        QVERIFY(writeFile(path, QJsonDocument(root).toJson()));
        PlaybackStore restored(path);
        QVERIFY(restored.error().isEmpty());
        QCOMPARE(restored.entries(), store.entries());
        QCOMPARE(restored.history(), store.history());
        QCOMPARE(restored.nextEntry(1, "single", true), store.currentEntryId());
        const QString pending = restored.enqueue(song("kuwo", "4"), true);
        QVERIFY(!pending.isEmpty());
        const auto priority = QJsonDocument::fromJson(readFile(path)).object().value("priorityEntries").toArray();
        QCOMPARE(priority.size(), 1);
        QCOMPARE(priority.first().toString(), pending);
    }
    void intentionalEmptyQueueIsNotConfusedWithUnmigratedState() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(store.clearHistory());
        QVERIFY(!store.initialized());
        QVERIFY(store.recordPlayed(song("netease", "1"), 10));
        PlaybackStore withOnlyHistory(path);
        QVERIFY(!withOnlyHistory.initialized());
        QCOMPARE(withOnlyHistory.history().size(), 1);
        QVERIFY(withOnlyHistory.clearQueue());
        QVERIFY(withOnlyHistory.initialized());
        QVERIFY(withOnlyHistory.clearHistory());
        PlaybackStore restored(path);
        QVERIFY(restored.initialized());
        QVERIFY(restored.entries().isEmpty());
        QVERIFY(restored.currentEntryId().isEmpty());
        QVERIFY(restored.nextEntry(1, "single", true).isEmpty());
    }
    void onlyStableMetadataSurvivesAndMissingLocalFilesStayQueued() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        auto online = song("tencent", "MID1", "  Named song  ");
        online.remove("songId");
        online["url"] = "https://temporary.invalid/audio?secret=token";
        online["mediaUrl"] = online["url"];
        online["path"] = online["url"];
        online["queueId"] = "untrusted-occurrence";
        online["duration"] = 254.47;
        online["sourceName"] = "QQ 音乐";
        auto local = song("local", "missing");
        local["path"] = dir.filePath("missing.mp3");
        local["duration"] = -1;
        QVERIFY(store.replaceQueue({online, local}, 1));
        QVERIFY(store.recordPlayed(online, 1234));
        const auto bytes = readFile(path);
        QVERIFY(!bytes.contains("temporary.invalid"));
        QVERIFY(!bytes.contains("mediaUrl"));
        QVERIFY(!bytes.contains("untrusted-occurrence"));
        PlaybackStore restored(path);
        QCOMPARE(restored.entries().size(), 2);
        const auto clean = restored.entries().first().toMap();
        QCOMPARE(clean.value("songId").toString(), QString("MID1"));
        QCOMPARE(clean.value("name").toString(), QString("Named song"));
        QCOMPARE(clean.value("duration").toDouble(), 254.47);
        QCOMPARE(clean.value("sourceName").toString(), QString("QQ 音乐"));
        QCOMPARE(restored.entries().last().toMap().value("path"), local.value("path"));
        QVERIFY(!restored.entries().last().toMap().contains("duration"));
        QVERIFY(!restored.history().first().toMap().contains("queueId"));
    }
    void historyUsesFullIdentityIsNewestFirstAndCapsAt200() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(store.replaceQueue({song("local", "kept")}, 0));
        const auto queue = store.entries();
        const QString current = store.currentEntryId();
        QSignalSpy queueChanged(&store, &PlaybackStore::queueChanged);
        QSignalSpy historyChanged(&store, &PlaybackStore::historyChanged);
        QVERIFY(store.recordPlayed(song("netease", "42"), 1));
        QVERIFY(store.recordPlayed(song("kuwo", "42"), 2));
        QVERIFY(store.recordPlayed(song("netease", "42", "Updated title"), 3));
        QCOMPARE(songIds(store.history()), QStringList({"netease:42", "kuwo:42"}));
        QCOMPARE(store.history().first().toMap().value("playedAtMs").toLongLong(), 3);
        QCOMPARE(store.history().first().toMap().value("name").toString(), QString("Updated title"));
        for (int i = 1; i <= 201; ++i) QVERIFY(store.recordPlayed(song("tencent", QString::number(i)), 100 + i));
        QCOMPARE(store.history().size(), 200);
        QCOMPARE(store.history().first().toMap().value("id").toString(), QString("tencent:201"));
        QCOMPARE(store.history().last().toMap().value("id").toString(), QString("tencent:2"));
        PlaybackStore restored(path);
        QCOMPARE(restored.history(), store.history());
        QCOMPARE(store.entries(), queue);
        QCOMPARE(store.currentEntryId(), current);
        QCOMPARE(queueChanged.count(), 0);
        QCOMPARE(historyChanged.count(), 204);
        QVERIFY(store.clearHistory());
        QCOMPARE(store.entries(), queue);
        QVERIFY(store.initialized());
    }
    void failedWritesDoNotPublishUnpersistedState() {
        QTemporaryDir dir;
        const QString path = dir.filePath("blocked/playback.json");
        PlaybackStore store(path);
        QVERIFY(QDir().mkpath(path)); // Make the destination a directory after construction.
        QSignalSpy queueChanged(&store, &PlaybackStore::queueChanged);
        QSignalSpy errors(&store, &PlaybackStore::errorChanged);
        QVERIFY(!store.replaceQueue({song("netease", "1")}, 0));
        QVERIFY(!store.error().isEmpty());
        QVERIFY(store.entries().isEmpty());
        QVERIFY(!store.initialized());
        QCOMPARE(queueChanged.count(), 0);
        QCOMPARE(errors.count(), 1);
    }
    void invalidOperationsLeaveQueueAndDiskUnchanged() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVERIFY(store.replaceQueue({song("netease", "1")}, 0));
        const QByteArray original = readFile(path);
        const auto originalQueue = store.entries();
        auto bad = song("netease", "2"); bad["id"] = "kuwo:2";
        QVERIFY(!store.replaceQueue({song("netease", "3"), bad}, 0));
        QVERIFY(!store.replaceQueue({song("netease", "3")}, 1));
        QVERIFY(!store.replaceQueue({}, -2));
        QVERIFY(!store.setCurrent("missing"));
        QVERIFY(!store.remove("missing"));
        QVERIFY(!store.move(store.currentEntryId(), 1));
        QVERIFY(store.enqueue({}, false).isEmpty());
        QVERIFY(!store.recordPlayed(bad));
        QCOMPARE(store.entries(), originalQueue);
        QCOMPARE(readFile(path), original);
        QVERIFY(!store.error().isEmpty());
        QVERIFY(store.setCurrent(store.currentEntryId()));
        QVERIFY(store.error().isEmpty());
    }
    void queueCountAndSerializedByteLimitsRejectWholeUpdate() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        const auto a = song("netease", "1");
        QVERIFY(store.replaceQueue({a}, 0));
        const QByteArray original = readFile(path);
        QVariantList excessive;
        for (int i = 0; i < 10001; ++i) excessive.append(a);
        QVERIFY(!store.replaceQueue(excessive));
        excessive.clear();
        const auto large = song("netease", "2", QString(32000, QLatin1Char('x')));
        for (int i = 0; i < 270; ++i) excessive.append(large);
        QVERIFY(!store.replaceQueue(excessive));
        QCOMPARE(store.entries().size(), 1);
        QCOMPARE(readFile(path), original);
    }
    void maximumQueueRemainsUsableAfterRestart() {
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        PlaybackStore store(path);
        QVariantList tracks;
        for (int i = 0; i < 10000; ++i) tracks.append(song("netease", "1"));
        QVERIFY(store.replaceQueue(tracks, 9999));
        QSet<QString> occurrences;
        for (const auto &entry : store.entries()) occurrences.insert(entry.toMap().value("queueId").toString());
        QCOMPARE(occurrences.size(), 10000);
        const auto saved = readFile(path);
        QVERIFY(store.enqueue(song("kuwo", "2"), true).isEmpty());
        QVERIFY(store.enqueue(song("kuwo", "2"), false).isEmpty());
        QCOMPARE(readFile(path), saved);
        PlaybackStore restored(path);
        QVERIFY(restored.error().isEmpty());
        QCOMPARE(restored.entries().size(), 10000);
        QCOMPARE(restored.currentIndex(), 9999);
        QVERIFY(restored.nextEntry(1, "sequential", true).isEmpty());
        QCOMPARE(restored.nextEntry(1, "sequential", false), queueId(restored, 0));
    }
    void malformedOrOversizedFilesArePreserved_data() {
        QTest::addColumn<QString>("damage");
        for (const auto &name : {"syntax", "version", "entries-type", "duplicate-queue-id", "track-identity",
                                 "missing-current", "negative-gap", "history-duplicate", "history-limit",
                                 "queue-limit", "byte-limit", "initialized-type", "priority-type",
                                 "priority-duplicate", "priority-orphan", "priority-id-type", "priority-invalid-id"})
            QTest::newRow(name) << QString::fromLatin1(name);
    }
    void malformedOrOversizedFilesArePreserved() {
        QFETCH(QString, damage);
        QTemporaryDir dir;
        const QString path = dir.filePath("playback.json");
        {
            PlaybackStore valid(path);
            QVariantList tracks{song("netease", "1")};
            if (damage == "priority-duplicate") tracks.append(song("netease", "2"));
            QVERIFY(valid.replaceQueue(tracks, 0));
            QVERIFY(valid.recordPlayed(song("kuwo", "2"), 10));
        }
        auto root = QJsonDocument::fromJson(readFile(path)).object();
        auto entries = root.value("entries").toArray();
        auto history = root.value("history").toArray();
        if (damage == "version") root["version"] = 99;
        if (damage == "entries-type") root["entries"] = "invalid";
        if (damage == "duplicate-queue-id") { entries.append(entries.first()); root["entries"] = entries; }
        if (damage == "track-identity") { auto track = entries.first().toObject(); track["id"] = "kuwo:1"; entries[0] = track; root["entries"] = entries; }
        if (damage == "missing-current") root["currentEntryId"] = QUuid::createUuid().toString(QUuid::WithoutBraces);
        if (damage == "negative-gap") root["gapIndex"] = -1;
        if (damage == "history-duplicate") { history.append(history.first()); root["history"] = history; }
        if (damage == "history-limit") { for (int i = 0; i < 201; ++i) history.append(history.first()); root["history"] = history; }
        if (damage == "queue-limit") { for (int i = 0; i < 10001; ++i) entries.append(entries.first()); root["entries"] = entries; }
        if (damage == "initialized-type") root["initialized"] = "false";
        if (damage == "priority-type") root["priorityEntries"] = "invalid";
        if (damage == "priority-duplicate") {
            const auto id = entries.first().toObject().value("queueId");
            root["priorityEntries"] = QJsonArray{id, id};
        }
        if (damage == "priority-orphan") root["priorityEntries"] = QJsonArray{QUuid::createUuid().toString(QUuid::WithoutBraces)};
        if (damage == "priority-id-type") root["priorityEntries"] = QJsonArray{42};
        if (damage == "priority-invalid-id") root["priorityEntries"] = QJsonArray{"not-an-occurrence"};
        QByteArray bytes = QJsonDocument(root).toJson();
        if (damage == "syntax") bytes = "{broken";
        if (damage == "byte-limit") bytes = QByteArray(8 * 1024 * 1024 + 1, ' ');
        QVERIFY(writeFile(path, bytes));
        PlaybackStore damaged(path);
        QVERIFY(!damaged.error().isEmpty());
        QVERIFY(damaged.entries().isEmpty());
        QVERIFY(!damaged.replaceQueue({song("netease", "3")}));
        QVERIFY(!damaged.clearQueue());
        QVERIFY(!damaged.clearHistory());
        QCOMPARE(readFile(path), bytes);
    }
};

QTEST_GUILESS_MAIN(PlaybackStoreTests)
#include "playbackstore_tests.moc"
