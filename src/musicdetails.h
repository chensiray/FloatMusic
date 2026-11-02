#pragma once
#include "musicapi.h"
#include <QObject>

// Read-only browsing state. Its API objects never touch playback or the player's search results.
class MusicDetails : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY changed)
public:
    explicit MusicDetails(QObject *parent=nullptr);
    MusicDetails(const MusicApi::Endpoints &endpoints,const QString &cachePath,QObject *parent=nullptr);
    QVariantMap state() const;
    Q_INVOKABLE void action(const QString &action,const QVariantMap &args = {});
signals:
    void changed();
private:
    void open(const QVariantMap &track);
    void load(int page);
    QString cacheFile() const;
    void save();
    void restore();
    MusicApi m_api,m_search;
    QString m_cachePath,m_order="hot",m_source,m_error,m_detailsError;
    QVariantMap m_original,m_track,m_details;
    QVariantList m_comments,m_candidates;
    int m_generation=0,m_page=0;
    bool m_loading=false,m_matching=false,m_stale=false,m_more=false,m_open=false,m_choosing=false;
};
