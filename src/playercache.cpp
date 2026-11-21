#include "playercontroller.h"
#include "audiofilepolicy.h"
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
static QString jsonMap(const QVariantMap &map){return QString::fromUtf8(QJsonDocument(QJsonObject::fromVariantMap(map)).toJson(QJsonDocument::Compact));}
QStringList PlayerController::cachedOnlineTrackIds() const {
    return m_offlineCache.usableSongIds().values();
}
QStringList PlayerController::offlineTrackIds() const {
    const auto cached = m_offlineCache.usableSongIds();
    QStringList result;
    for (const auto &value : tracks()) {
        const auto track = value.toMap();
        const auto source = track.value("source").toString();
        bool available = cached.contains(source + ':' + track.value("songId").toString());
        if (source == "local") {
            const auto path = track.value("path").toString();
            QFile file(path);
            available = QFileInfo(path).isFile() && file.open(QIODevice::ReadOnly)
                && AudioFilePolicy::validate(QFileInfo(path).fileName(), file.size(), file.peek(16)).isEmpty();
        }
        if (available) result.append(track.value("id").toString());
    }
    return result;
}
QVariantList PlayerController::cacheEntries()const{
#ifdef Q_OS_ANDROID
 QVariantList out;for(auto v:m_androidCacheEntries){auto x=v.toMap();const auto track=x.value("track").toMap();x["title"]=track.value("name");x["artist"]=track.value("artist");x["source"]=track.value("source");x["pendingDelete"]=x.value("deletePending");auto state=x.value("status").toString();x["state"]=state=="ready"?"complete":state;x["total"]=x.value("totalBytes");x["lyricState"]=x.value("originalState");x["progress"]=x.value("totalBytes").toLongLong()>0?x.value("bytes").toDouble()/x.value("totalBytes").toDouble():-1.;out.append(x);}return out;
#else
 return m_offlineCache.entries();
#endif
}
QVariantMap PlayerController::cacheSummary()const{
#ifdef Q_OS_ANDROID
 auto x=m_androidCacheSummary;x["totalBytes"]=x.value("bytes");return x;
#else
 return m_offlineCache.summary();
#endif
}
QVariantMap PlayerController::cacheSettings()const{
#ifdef Q_OS_ANDROID
 auto x=m_androidCacheSettings;x["automaticEnabled"]=x.value("automatic",true);x["automaticLimitBytes"]=x.value("limitBytes",qint64(1073741824));x["wifiOnly"]=x.value("onlyWifi",true);return x;
#else
 return m_offlineCache.settings();
#endif
}
void PlayerController::cacheTrack(const QVariantMap &track,bool manual){
#ifdef Q_OS_ANDROID
 androidCommand("cacheTrack",jsonMap({{"track",track},{"manual",manual}}));
#else
 const auto key=m_offlineCache.enqueue(track,qualityForSource(track.value("source").toString()),manual);
 if(track.value("id")==m_loadedTrack.value("id")&&m_ready&&!m_sessionDeferred&&m_currentCacheKey.isEmpty())m_offlineCache.setInUse(key);
#endif
}
void PlayerController::cacheCurrentTrack(){cacheTrack(m_loadedTrack.isEmpty()?m_requestedTrack:m_loadedTrack,true);}
int PlayerController::cacheTracks(const QVariantList &tracks) {
#ifdef Q_OS_ANDROID
    androidCommand("cacheTracks", QString::fromUtf8(QJsonDocument(QJsonArray::fromVariantList(tracks)).toJson(QJsonDocument::Compact)));
    return tracks.size();
#else
    const auto playingId = m_ready && !m_sessionDeferred && m_currentCacheKey.isEmpty() ? m_currentTrack : QString();
    const int count = m_offlineCache.enqueueBatch(tracks, sourceQualities(), playingId);
    if (count < 0) {
        m_error = QStringLiteral("缓存索引保存失败");
        emit changed();
    }
    return qMax(0, count);
#endif
}
void PlayerController::playCached(const QString &key){
#ifdef Q_OS_ANDROID
 androidCommand("cachePlay",key);
#else
 if(m_busy)return;
 const auto entry=m_offlineCache.lookupKey(key);
 if(!entry.isEmpty()){
   m_preferredCacheKey=entry.value("key").toString();
   playNowTrack(entry.value("track").toMap());
   m_preferredCacheKey.clear();
 }
#endif
}
void PlayerController::promoteCached(const QString &key){
#ifdef Q_OS_ANDROID
 androidCommand("cachePromote",key);
#else
 m_offlineCache.promote(key);
#endif
}
void PlayerController::retryCached(const QString &key){
#ifdef Q_OS_ANDROID
 androidCommand("cacheRetry",key);
#else
 m_offlineCache.retry(key);
#endif
}
void PlayerController::cancelCache(const QString &key){
#ifdef Q_OS_ANDROID
 androidCommand("cacheCancel",key);
#else
 m_offlineCache.cancel(key);
#endif
}
void PlayerController::removeCache(const QString &key){
#ifdef Q_OS_ANDROID
 androidCommand("cacheRemove",key);
#else
 m_offlineCache.remove(key);
#endif
}
void PlayerController::clearCache(bool all){
#ifdef Q_OS_ANDROID
 androidCommand("cacheClear",all?"all":"automatic");
#else
 m_offlineCache.clear(all);
#endif
}
void PlayerController::setCacheSettings(const QVariantMap &settings){
#ifdef Q_OS_ANDROID
 auto x=settings;if(x.contains("automaticEnabled"))x["automatic"]=x.take("automaticEnabled");if(x.contains("automaticLimitBytes"))x["limitBytes"]=x.take("automaticLimitBytes");if(x.contains("wifiOnly"))x["onlyWifi"]=x.take("wifiOnly");androidCommand("cacheSettings",jsonMap(x));
#else
 m_offlineCache.setSettings(settings);
#endif
}
void PlayerController::retryCachedLyrics(const QString &key){
#ifdef Q_OS_ANDROID
 androidCommand("cacheRetryLyrics",key);
#else
 m_offlineCache.retryLyrics(key);
#endif
}
