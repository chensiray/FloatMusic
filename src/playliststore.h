#pragma once
#include <QObject>
#include <QVariantList>
#include <QVariantMap>
#include <QStringList>

class PlaylistStore : public QObject {
    Q_OBJECT
public:
    explicit PlaylistStore(QObject *parent = nullptr);
    QVariantList playlists() const;
    QVariantList tracks() const;
    QVariantMap playlist(const QString &id) const;
    QVariantList tracks(const QString &id) const;
    QString activeId() const { return m_active; }
    bool select(const QString &id);
    bool create(const QString &name);
    bool createWithTracks(const QString &name, const QString &description, const QVariantList &tracks);
    bool rename(const QString &name);
    bool removePlaylist();
    bool add(const QVariantMap &track);
    bool removeTrack(const QString &id);
    bool describe(const QString &description);
    bool duplicate();
    int addTracks(const QString &target, const QVariantList &tracks);
    bool removeTracks(const QString &source, const QStringList &ids);
    bool transfer(const QString &source, const QString &target, const QStringList &ids, bool move);
    bool moveTrack(const QString &source, int from, int to);
    bool migrateFavorites(const QVariantList &tracks);
    QString error() const { return m_error; }
signals:
    void changed();
private:
    bool commit(const QVariantList &lists, const QString &active, bool favoritesMigrated);
    bool reject(const QString &message);
    bool unchanged();
    int index(const QString &id) const;
    QVariantList m_lists;
    QString m_active, m_path, m_error;
    bool m_favoritesMigrated = false;
    bool m_storageBlocked = false;
};
