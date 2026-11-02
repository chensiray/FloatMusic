#pragma once
#include "musicapi.h"
#include <QTimer>
#include <QQueue>
#include <QSet>
class SongArtwork:public QObject {
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY changed)
    Q_PROPERTY(int revision READ revision NOTIFY changed)
    Q_PROPERTY(QVariantMap urls READ urls NOTIFY changed)
public:
    explicit SongArtwork(const MusicApi::Endpoints &endpoints,QObject *parent=nullptr,const QString &cachePath={});
    ~SongArtwork() override {if(m_saveTimer.isActive())save();}
    bool enabled() const {return m_enabled;}
    void setEnabled(bool enabled);
    int revision() const {return m_revision;}
    QVariantMap urls() const;
    Q_INVOKABLE QString cover(const QVariantMap &track) const;
    Q_INVOKABLE void request(const QVariantMap &track);
signals:
    void changed();
private:
    void drain();
    void store(const QString &key,const QString &url);
    void save();
    MusicApi m_api;
    QString m_path;
    QVariantMap m_rows;
    QVariantMap m_urls;
    QQueue<QVariantMap> m_queue;
    QSet<QString> m_pending;
    QHash<QString,qint64> m_attempted;
    QTimer m_saveTimer;
    bool m_enabled=true;
    int m_running=0,m_revision=0;
};
