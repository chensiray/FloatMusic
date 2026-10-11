#include "playbackstore.h"
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QStandardPaths>
#include <QUuid>
#include <cmath>

namespace {
constexpr int QueueLimit = 10000;
constexpr int HistoryLimit = 200;
constexpr qint64 FileLimit = 8 * 1024 * 1024;

int entryIndex(const QVariantList &entries, const QString &id) {
    if (id.isEmpty()) return -1;
    for (int i = 0; i < entries.size(); ++i)
        if (entries.at(i).toMap().value("queueId").toString() == id) return i;
    return -1;
}

bool normalizeTrack(const QVariantMap &input, QVariantMap &track) {
    for (const auto &key : {"id", "source", "name"})
        if (input.value(key).metaType().id() != QMetaType::QString) return false;
    const QString source = input.value("source").toString().trimmed();
    const QString id = input.value("id").toString().trimmed();
    const QString name = input.value("name").toString().trimmed();
    if (name.isEmpty() || !id.startsWith(source + ':')) return false;
    const QString identity = id.mid(source.size() + 1);
    if (identity.isEmpty()) return false;
    if (source == "local") {
        track = {{"id", id}, {"source", source}, {"name", name}};
        const QString path = input.value("path").toString();
        // Keep missing local files as queue entries; the player reports availability later.
        if (!path.isEmpty() && QFileInfo(path).isAbsolute()) track["path"] = path;
    } else if (source == "netease" || source == "tencent" || source == "kuwo") {
        QString songId = input.value("songId").toString().trimmed();
        if (songId.isEmpty()) songId = identity;
        static const QRegularExpression number("^[1-9][0-9]{0,18}$"), mid("^[A-Za-z0-9]{1,64}$");
        if (id != source + ':' + songId
            || !(source == "tencent" ? mid : number).match(songId).hasMatch()) return false;
        track = {{"id", id}, {"source", source}, {"songId", songId}, {"name", name}};
    } else {
        return false;
    }
    for (const auto &key : {"artist", "sourceName"})
        if (input.value(key).metaType().id() == QMetaType::QString)
            track[key] = input.value(key).toString().trimmed();
    bool durationValid = false;
    const double seconds = input.value("duration").toDouble(&durationValid);
    if (durationValid && std::isfinite(seconds) && seconds > 0 && seconds <= 86400)
        track["duration"] = seconds;
    return true;
}

QString newEntryId(QSet<QUuid> &known) {
    QUuid occurrence;
    do { occurrence = QUuid::createUuid(); }
    while (known.contains(occurrence));
    known.insert(occurrence);
    return occurrence.toString(QUuid::WithoutBraces);
}
}

PlaybackStore::PlaybackStore(const QString &path, QObject *parent) : QObject(parent) {
    if (path.isEmpty()) {
        const QString directory = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
        if (directory.isEmpty()) {
            m_storageBlocked = true;
            m_error = QStringLiteral("无法找到播放数据目录，已停止保存播放队列和历史。");
            return;
        }
        m_path = QDir(directory).filePath("playback.json");
    } else {
        m_path = path;
    }
    QFile file(m_path);
    if (!file.exists()) return;
    const auto damaged = [this] {
        m_storageBlocked = true;
        m_error = QStringLiteral("播放数据无法安全读取，已保留原文件并停止写入。");
    };
    if (!file.open(QIODevice::ReadOnly) || file.size() > FileLimit) {
        damaged();
        return;
    }
    const QByteArray bytes = file.read(FileLimit + 1);
    if (file.error() != QFileDevice::NoError || bytes.size() > FileLimit) {
        damaged();
        return;
    }
    QJsonParseError parse;
    const auto document = QJsonDocument::fromJson(bytes, &parse);
    const auto root = document.object();
    if (parse.error != QJsonParseError::NoError || !document.isObject()
        || root.value("version").toInteger(-1) != 1 || !root.value("initialized").isBool()
        || !root.value("entries").isArray() || !root.value("history").isArray()
        || !root.value("currentEntryId").isString()
        || (root.contains("priorityEntries") && !root.value("priorityEntries").isArray())) {
        damaged();
        return;
    }
    const auto entries = root.value("entries").toArray();
    const auto history = root.value("history").toArray();
    const auto priority = root.value("priorityEntries").toArray();
    const qint64 gap = root.value("gapIndex").toInteger(-1);
    if (entries.size() > QueueLimit || history.size() > HistoryLimit || gap < 0 || gap > entries.size()
        || priority.size() > entries.size()) {
        damaged();
        return;
    }
    State restored;
    restored.initialized = root.value("initialized").toBool();
    restored.currentEntryId = root.value("currentEntryId").toString();
    restored.gapIndex = int(gap);
    QSet<QUuid> occurrences;
    QSet<QString> entryIds;
    QSet<QString> historyIds;
    for (const auto &value : entries) {
        QVariantMap track;
        const auto raw = value.toObject();
        const QString queueId = raw.value("queueId").toString();
        const QUuid occurrence(queueId);
        if (!value.isObject() || occurrence.isNull() || occurrences.contains(occurrence)
            || !normalizeTrack(raw.toVariantMap(), track)) {
            damaged();
            return;
        }
        occurrences.insert(occurrence);
        entryIds.insert(queueId);
        track["queueId"] = queueId;
        restored.entries.append(track);
    }
    const int current = entryIndex(restored.entries, restored.currentEntryId);
    if ((!restored.currentEntryId.isEmpty() && (current < 0 || restored.gapIndex != current + 1))
        || (!restored.initialized && (!restored.entries.isEmpty() || !restored.currentEntryId.isEmpty() || gap != 0))) {
        damaged();
        return;
    }
    QSet<QString> priorityIds;
    for (const auto &value : priority) {
        const QString id = value.toString();
        if (!value.isString() || !entryIds.contains(id) || priorityIds.contains(id)) {
            damaged();
            return;
        }
        priorityIds.insert(id);
        restored.priorityEntries.append(id);
    }
    for (const auto &value : history) {
        QVariantMap track;
        const auto raw = value.toObject();
        const qint64 playedAt = raw.value("playedAtMs").toInteger(-1);
        if (!value.isObject() || playedAt < 0 || !normalizeTrack(raw.toVariantMap(), track)
            || historyIds.contains(track.value("id").toString())) {
            damaged();
            return;
        }
        historyIds.insert(track.value("id").toString());
        track["playedAtMs"] = playedAt;
        restored.history.append(track);
    }
    m_state = restored;
}

QVariantList PlaybackStore::entries() const { return m_state.entries; }
QVariantList PlaybackStore::history() const { return m_state.history; }
QString PlaybackStore::currentEntryId() const { return m_state.currentEntryId; }
int PlaybackStore::currentIndex() const { return entryIndex(m_state.entries, m_state.currentEntryId); }
bool PlaybackStore::initialized() const { return m_state.initialized; }
QString PlaybackStore::error() const { return m_error; }

void PlaybackStore::setError(const QString &message) {
    if (m_error == message) return;
    m_error = message;
    emit errorChanged();
}

bool PlaybackStore::reject(const QString &message) {
    setError(message);
    return false;
}

bool PlaybackStore::commit(const State &state) {
    if (m_storageBlocked)
        return reject(QStringLiteral("原播放数据无法安全读取，当前修改未保存，请先检查 playback.json。"));
    const bool queueUpdated = state.entries != m_state.entries || state.currentEntryId != m_state.currentEntryId
        || state.gapIndex != m_state.gapIndex || state.initialized != m_state.initialized
        || state.priorityEntries != m_state.priorityEntries;
    const bool historyUpdated = state.history != m_state.history;
    if (!queueUpdated && !historyUpdated) {
        setError({});
        return true;
    }
    const QByteArray bytes = QJsonDocument(QJsonObject::fromVariantMap({
        {"version", 1}, {"initialized", state.initialized}, {"entries", state.entries},
        {"history", state.history}, {"currentEntryId", state.currentEntryId}, {"gapIndex", state.gapIndex},
        {"priorityEntries", state.priorityEntries}
    })).toJson(QJsonDocument::Compact);
    if (bytes.size() > FileLimit)
        return reject(QStringLiteral("播放数据超过 8 MiB，本次修改未保存。"));
    if (!QDir().mkpath(QFileInfo(m_path).absolutePath()))
        return reject(QStringLiteral("无法创建播放数据目录，本次修改未保存。"));
    QSaveFile file(m_path);
    file.setDirectWriteFallback(false);
    if (!file.open(QIODevice::WriteOnly) || file.write(bytes) != bytes.size() || !file.commit())
        return reject(QStringLiteral("播放数据保存失败，请检查磁盘空间或写入权限。本次修改已撤销。"));
    // Nothing becomes visible to the controller until the atomic replacement succeeds.
    m_state = state;
    setError({});
    if (queueUpdated) emit queueChanged();
    if (historyUpdated) emit historyChanged();
    return true;
}

bool PlaybackStore::replaceQueue(const QVariantList &tracks, int current) {
    if (tracks.size() > QueueLimit)
        return reject(QStringLiteral("播放队列最多保留 10000 项，本次修改未保存。"));
    if (current < -1 || current >= tracks.size())
        return reject(QStringLiteral("播放队列的当前项位置无效。"));
    State state = m_state;
    state.entries.clear();
    state.currentEntryId.clear();
    state.priorityEntries.clear();
    state.gapIndex = 0;
    state.initialized = true;
    QSet<QUuid> occurrences;
    for (const auto &value : tracks) {
        QVariantMap track;
        if (!normalizeTrack(value.toMap(), track))
            return reject(QStringLiteral("歌曲信息或来源编号无效，播放队列未修改。"));
        track["queueId"] = newEntryId(occurrences);
        state.entries.append(track);
    }
    if (current >= 0) {
        state.currentEntryId = state.entries.at(current).toMap().value("queueId").toString();
        state.gapIndex = current + 1;
    }
    return commit(state);
}

QString PlaybackStore::enqueue(const QVariantMap &input, bool playNext) {
    if (m_state.entries.size() >= QueueLimit) {
        reject(QStringLiteral("播放队列已达到 10000 项，无法继续添加。"));
        return {};
    }
    QVariantMap track;
    if (!normalizeTrack(input, track)) {
        reject(QStringLiteral("歌曲信息或来源编号无效，无法加入播放队列。"));
        return {};
    }
    State state = m_state;
    QSet<QUuid> occurrences;
    for (const auto &entry : state.entries) occurrences.insert(QUuid(entry.toMap().value("queueId").toString()));
    const QString id = newEntryId(occurrences);
    track["queueId"] = id;
    const int current = currentIndex();
    const int insertion = playNext ? (current >= 0 ? current + 1 : state.gapIndex) : int(state.entries.size());
    state.entries.insert(insertion, track);
    if (playNext) state.priorityEntries.prepend(id);
    state.initialized = true;
    if (current >= 0) state.gapIndex = current + 1;
    return commit(state) ? id : QString();
}

bool PlaybackStore::setCurrent(const QString &entryId) {
    const int index = entryIndex(m_state.entries, entryId);
    if (index < 0) return reject(QStringLiteral("该播放队列项已不存在。"));
    State state = m_state;
    state.currentEntryId = entryId;
    state.gapIndex = index + 1;
    state.priorityEntries.removeAll(entryId);
    return commit(state);
}

bool PlaybackStore::remove(const QString &entryId) {
    const int index = entryIndex(m_state.entries, entryId);
    if (index < 0) return reject(QStringLiteral("该播放队列项已不存在。"));
    State state = m_state;
    state.entries.removeAt(index);
    state.priorityEntries.removeAll(entryId);
    if (entryId == state.currentEntryId) {
        state.currentEntryId.clear();
        state.gapIndex = index;
    } else if (!state.currentEntryId.isEmpty()) {
        state.gapIndex = entryIndex(state.entries, state.currentEntryId) + 1;
    } else if (index < state.gapIndex) {
        --state.gapIndex;
    }
    return commit(state);
}

bool PlaybackStore::move(const QString &entryId, int to) {
    const int from = entryIndex(m_state.entries, entryId);
    if (from < 0 || to < 0 || to >= m_state.entries.size())
        return reject(QStringLiteral("播放队列项或目标位置无效。"));
    State state = m_state;
    const auto entry = state.entries.takeAt(from);
    state.entries.insert(to, entry);
    if (state.currentEntryId.isEmpty()) {
        // Only crossing the original gap moves it; reordering either side preserves it.
        if (from < state.gapIndex && to >= state.gapIndex) --state.gapIndex;
        else if (from >= state.gapIndex && to < state.gapIndex) ++state.gapIndex;
    } else {
        state.gapIndex = entryIndex(state.entries, state.currentEntryId) + 1;
    }
    return commit(state);
}

bool PlaybackStore::clearQueue() {
    State state = m_state;
    state.entries.clear();
    state.currentEntryId.clear();
    state.priorityEntries.clear();
    state.gapIndex = 0;
    state.initialized = true;
    return commit(state);
}

bool PlaybackStore::recordPlayed(const QVariantMap &input, qint64 playedAtMs) {
    QVariantMap track;
    if (!normalizeTrack(input, track) || playedAtMs < -1)
        return reject(QStringLiteral("歌曲信息或播放时间无效，历史未修改。"));
    if (playedAtMs == -1) playedAtMs = QDateTime::currentMSecsSinceEpoch();
    track["playedAtMs"] = playedAtMs;
    State state = m_state;
    const QString id = track.value("id").toString();
    for (int i = 0; i < state.history.size(); ++i)
        if (state.history.at(i).toMap().value("id").toString() == id) {
            state.history.removeAt(i);
            break;
        }
    state.history.prepend(track);
    while (state.history.size() > HistoryLimit) state.history.removeLast();
    return commit(state);
}

bool PlaybackStore::clearHistory() {
    State state = m_state;
    state.history.clear();
    return commit(state);
}

QString PlaybackStore::nextEntry(int direction, const QString &mode, bool automatic) const {
    const int count = int(m_state.entries.size());
    if (count == 0 || direction == 0
        || (mode != "sequential" && mode != "loop" && mode != "single" && mode != "shuffle")) return {};
    // Looking ahead keeps the request pending until setCurrent successfully persists selection.
    if (direction > 0 && !m_state.priorityEntries.isEmpty()) return m_state.priorityEntries.first();
    const int current = currentIndex();
    int next;
    if (mode == "shuffle") {
        if (count == 1) next = 0;
        else if (current >= 0) {
            next = QRandomGenerator::global()->bounded(count - 1);
            if (next >= current) ++next;
        } else {
            next = QRandomGenerator::global()->bounded(count);
        }
    } else if (mode == "single" && automatic && current >= 0) {
        next = current;
    } else {
        next = current >= 0 ? current + (direction > 0 ? 1 : -1)
                            : m_state.gapIndex + (direction > 0 ? 0 : -1);
        if (next < 0 || next >= count) {
            if (mode == "sequential" && automatic) return {};
            next = next < 0 ? count - 1 : 0;
        }
    }
    return m_state.entries.at(next).toMap().value("queueId").toString();
}
