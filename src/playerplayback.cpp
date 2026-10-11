#include "playercontroller.h"
#include <QJsonDocument>
#include <QJsonObject>

QVariantList PlayerController::queueEntries() const {
#ifdef Q_OS_ANDROID
    return m_androidQueueEntries;
#else
    return m_playbackStore.entries();
#endif
}
QString PlayerController::currentQueueEntry() const {
#ifdef Q_OS_ANDROID
    return m_androidQueueEntry;
#else
    return m_playbackStore.currentEntryId();
#endif
}
QVariantList PlayerController::historyEntries() const {
#ifdef Q_OS_ANDROID
    return m_androidHistoryEntries;
#else
    return m_playbackStore.history();
#endif
}
QString PlayerController::sleepMode() const {
#ifdef Q_OS_ANDROID
    return m_androidSleepMode;
#else
    return m_sleepTimer.mode();
#endif
}
int PlayerController::sleepRemaining() const {
#ifdef Q_OS_ANDROID
    return m_androidSleepRemaining;
#else
    return m_sleepTimer.remainingSeconds();
#endif
}
QString PlayerController::sleepText() const {
#ifdef Q_OS_ANDROID
    return m_androidSleepText;
#else
    return m_sleepTimer.text();
#endif
}

void PlayerController::initializePlaybackTools() {
    m_sleepTimer.setObjectName("sleepTimer");
    connect(&m_playbackStore, &PlaybackStore::queueChanged, this, &PlayerController::queueChanged);
    connect(&m_playbackStore, &PlaybackStore::historyChanged, this, &PlayerController::historyChanged);
    connect(&m_playbackStore, &PlaybackStore::errorChanged, this, [this] {
        if (!m_playbackStore.error().isEmpty()) setPlaybackMessage(m_playbackStore.error());
    });
    if (!m_playbackStore.error().isEmpty()) setPlaybackMessage(m_playbackStore.error());
    connect(&m_sleepTimer, &SleepTimer::changed, this, &PlayerController::sleepChanged);
    connect(&m_sleepTimer, &SleepTimer::expired, this, &PlayerController::pauseForSleep);
    connect(&m_shortcuts, &GlobalShortcuts::activated, this, [this](const QString &action) {
        if (action == "togglePlay") toggle();
        else if (action == "previous") previous();
        else if (action == "next") next();
        else if (action == "volumeUp") setVolume(volume() + 5);
        else if (action == "volumeDown") setVolume(volume() - 5);
        else if (action == "toggleWindow") emit toggleWindowRequested();
    });
}
void PlayerController::setPlaybackMessage(const QString &message) {
    if (message == m_playbackMessage) return;
    m_playbackMessage = message; emit playbackMessageChanged();
}
void PlayerController::enqueueTrack(const QVariantMap &track, const QString &placement) {
    if (placement != "next" && placement != "tail") return;
#ifdef Q_OS_ANDROID
    androidCommand("enqueueTrack", QString::fromUtf8(QJsonDocument(QJsonObject::fromVariantMap({{"track", track}, {"placement", placement}})).toJson(QJsonDocument::Compact)));
#else
    const auto id = m_playbackStore.enqueue(track, placement == "next");
    setPlaybackMessage(id.isEmpty() ? m_playbackStore.error() : placement == "next"
        ? QStringLiteral("已设为下一首播放") : QStringLiteral("已加入队尾"));
#endif
}
void PlayerController::playQueueEntry(const QString &entryId) {
    if (m_busy) return;
#ifdef Q_OS_ANDROID
    androidCommand("playQueueEntry", entryId);
#else
    for (const auto &item : queueEntries()) {
        const auto track = item.toMap();
        if (track.value("queueId").toString() != entryId) continue;
        saveSession();
        m_pendingQueueSnapshot.clear(); m_pendingQueueIndex = -1;
        m_requestedQueueEntry = entryId;
        m_historyPending = true;
        loadTrack(track, true);
        return;
    }
#endif
}
void PlayerController::playNowTrack(const QVariantMap &track) {
#ifdef Q_OS_ANDROID
    if (m_busy) return;
    syncQueue();
    androidCommand("play", QString::fromUtf8(QJsonDocument(QJsonObject::fromVariantMap({{"track", track}, {"placement", "now"}, {"autoplay", true}})).toJson(QJsonDocument::Compact)));
#else
    if (m_busy) return;
    const auto entry = m_playbackStore.enqueue(track, true);
    if (entry.isEmpty()) { setPlaybackMessage(m_playbackStore.error()); return; }
    playQueueEntry(entry);
#endif
}
void PlayerController::moveQueueEntry(const QString &entryId, int to) {
#ifdef Q_OS_ANDROID
    androidCommand("moveQueueEntry", QString::fromUtf8(QJsonDocument(QJsonObject{{"entryId", entryId}, {"to", to}}).toJson(QJsonDocument::Compact)));
#else
    setPlaybackMessage(m_playbackStore.move(entryId, to) ? QStringLiteral("队列顺序已保存") : m_playbackStore.error());
#endif
}
void PlayerController::removeQueueEntry(const QString &entryId) {
#ifdef Q_OS_ANDROID
    androidCommand("removeQueueEntry", entryId);
#else
    setPlaybackMessage(m_playbackStore.remove(entryId) ? QStringLiteral("已从队列移除") : m_playbackStore.error());
#endif
}
void PlayerController::clearPlaybackQueue() {
#ifdef Q_OS_ANDROID
    androidCommand("clearPlaybackQueue");
#else
    setPlaybackMessage(m_playbackStore.clearQueue() ? QStringLiteral("队列已清空，正在播放的歌曲继续") : m_playbackStore.error());
#endif
}
void PlayerController::playHistoryTrack(const QString &id) {
    if (m_busy) return;
#ifdef Q_OS_ANDROID
    androidCommand("playHistoryTrack", id);
#else
    for (const auto &item : historyEntries()) {
        const auto track = item.toMap();
        if (track.value("id").toString() == id) { playNowTrack(track); return; }
    }
#endif
}
void PlayerController::clearPlaybackHistory() {
#ifdef Q_OS_ANDROID
    androidCommand("clearPlaybackHistory");
#else
    setPlaybackMessage(m_playbackStore.clearHistory() ? QStringLiteral("最近播放已清空") : m_playbackStore.error());
#endif
}
void PlayerController::recordStartedTrack() {
#ifndef Q_OS_ANDROID
    if (m_shuttingDown || !m_historyPending || !m_playing || m_busy || m_resolving || m_importing || m_sessionDeferred
        || m_loadedTrack.isEmpty() || m_loadedTrack.value("id") != m_requestedTrack.value("id")
        || m_loadedQueueEntry != m_requestedQueueEntry) return;
    m_historyPending = false;
    m_playbackStore.recordPlayed(m_loadedTrack);
#endif
}
void PlayerController::startSleepTimer(int minutes) {
#ifdef Q_OS_ANDROID
    androidCommand("startSleepTimer", QString::number(minutes));
#else
    if (!m_sleepTimer.start(minutes)) setPlaybackMessage(QStringLiteral("定时范围为 1–180 分钟"));
#endif
}
void PlayerController::sleepAfterCurrentTrack() {
#ifdef Q_OS_ANDROID
    androidCommand("sleepAfterCurrentTrack");
#else
    if (m_loadedTrack.isEmpty() && m_requestedTrack.isEmpty()) {
        setPlaybackMessage(QStringLiteral("先选择一首歌曲")); return;
    }
    m_sleepTimer.afterCurrentTrack();
#endif
}
void PlayerController::cancelSleepTimer() {
#ifdef Q_OS_ANDROID
    androidCommand("cancelSleepTimer");
#else
    m_sleepTimer.cancel();
#endif
}
void PlayerController::pauseForSleep() {
#ifndef Q_OS_ANDROID
    m_playIntent = false; m_autoplay = false;
    if (m_player.playbackState() == QMediaPlayer::PlayingState) m_player.pause();
    setPlaybackMessage(QStringLiteral("睡眠定时已结束，播放已暂停"));
    saveSession(); sync();
#endif
}
