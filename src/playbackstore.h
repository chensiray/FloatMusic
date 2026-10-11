#pragma once
#include <QObject>
#include <QString>
#include <QStringList>
#include <QVariantList>
#include <QVariantMap>

class PlaybackStore : public QObject {
    Q_OBJECT
public:
    explicit PlaybackStore(const QString &path = {}, QObject *parent = nullptr);
    QVariantList entries() const;
    QVariantList history() const;
    QString currentEntryId() const;
    int currentIndex() const;
    bool initialized() const;
    QString error() const;
    bool replaceQueue(const QVariantList &tracks, int currentIndex = -1);
    QString enqueue(const QVariantMap &track, bool playNext);
    bool setCurrent(const QString &entryId);
    bool remove(const QString &entryId);
    bool move(const QString &entryId, int to);
    bool clearQueue();
    bool recordPlayed(const QVariantMap &track, qint64 playedAtMs = -1);
    bool clearHistory();
    QString nextEntry(int direction, const QString &mode, bool automatic) const;
signals:
    void queueChanged();
    void historyChanged();
    void errorChanged();
private:
    struct State {
        QVariantList entries;
        QVariantList history;
        QString currentEntryId;
        QStringList priorityEntries;
        // With no selected occurrence, the gap points to the next item after a removal.
        int gapIndex = 0;
        bool initialized = false;
    };
    bool commit(const State &state);
    bool reject(const QString &message);
    void setError(const QString &message);
    QString m_path;
    QString m_error;
    State m_state;
    bool m_storageBlocked = false;
};
