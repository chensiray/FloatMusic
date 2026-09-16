#pragma once
#include <QObject>
#include <QVariantList>
#include <QVariantMap>

class PlaylistStore : public QObject {
    Q_OBJECT
public:
    explicit PlaylistStore(QObject *parent = nullptr);
    QVariantList playlists() const;
    QVariantList tracks() const;
    QString activeId() const { return m_active; }
    bool select(const QString &id);
    bool create(const QString &name);
    bool rename(const QString &name);
    bool removePlaylist();
    bool add(const QVariantMap &track);
    bool removeTrack(const QString &id);
    QString error() const { return m_error; }
signals:
    void changed();
private:
    bool save();
    int index() const;
    QVariantList m_lists;
    QString m_active, m_path, m_error;
};
