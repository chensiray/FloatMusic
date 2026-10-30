#pragma once
#include "musicapi.h"
#include <QFile>
#include <QMap>
#include <QCryptographicHash>
class OfflineCache : public QObject {
 Q_OBJECT
public:
 explicit OfflineCache(const MusicApi::Endpoints &endpoints, const QString &directory = {}, QObject *parent=nullptr);
 ~OfflineCache();
 QVariantList entries() const;
 QVariantMap summary() const;
 QVariantMap settings() const {return m_settings;}
 void setSettings(const QVariantMap &settings);
 QString enqueue(const QVariantMap &track,const QString &quality,bool manual,const MusicApi::Audio &audio = {});
 QVariantMap lookupKey(const QString &key);
 QVariantMap lookup(const QVariantMap &track,const QString &quality);
 QVariantMap entry(const QString &key) const {return m_entries.value(canonicalKey(key));}
 void promote(const QString &key); void retry(const QString &key); void cancel(const QString &key); void remove(const QString &key); void clear(bool all); void setInUse(const QString &key);
 void invalidate(const QString &key);
 void retryLyrics(const QString &key);
 void setBaseUrl(const QString &url){m_api.setBaseUrl(url);}
 static QString identity(const QVariantMap &track,const QString &quality);
 signals: void changed();
private:
 QString canonicalKey(const QString &key) const;
 bool save(); void pump(); void download(const QString &key,const MusicApi::Audio &audio); void finish(const QString &key,const QString &error={}); bool reserve(const QString &key,qint64 bytes); bool validFile(const QString &path,qint64 expected=-1) const; QString path(const QString &key,const QString &suffix)const;
 QCryptographicHash m_downloadHash{QCryptographicHash::Sha256};
 QMap<QString,QString> m_aliases; QMap<QString,quint64> m_lyricTickets; quint64 m_lyricSerial=0;
 QMap<QString,QVariantMap> m_entries; QMap<QString,MusicApi::Audio> m_audio;
 QVariantMap m_settings{{"automaticEnabled",true},{"automaticLimitBytes",qint64(1073741824)},{"wifiOnly",true}};
 QString m_directory,m_active,m_inUse; quint64 m_generation=0; MusicApi m_api; QNetworkAccessManager m_network; QPointer<QNetworkReply> m_reply; QFile m_file; bool m_stopping=false, m_enabled=true;
};
