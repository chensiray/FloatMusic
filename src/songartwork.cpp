#include "songartwork.h"
#include "artworkpolicy.h"
#include <QSettings>
#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QSaveFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QDateTime>
SongArtwork::SongArtwork(const MusicApi::Endpoints &e,QObject *parent,const QString &path):QObject(parent),m_api(e),m_path(path.isEmpty()?QStandardPaths::writableLocation(QStandardPaths::CacheLocation)+"/artwork-index.json":path){
    m_enabled=QSettings().value("appearance/songCovers",true).toBool();
    QFile f(m_path);if(f.open(QIODevice::ReadOnly)&&f.size()<=1024*1024){
        const auto rows=QJsonDocument::fromJson(f.readAll()).object().toVariantMap();
        for(auto it=rows.cbegin();it!=rows.cend()&&m_rows.size()<1000;++it){const auto row=it.value().toMap();const auto url=ArtworkPolicy::url(row.value("url").toString());if(!url.isEmpty()){m_rows[it.key()]=row;m_urls[it.key()]=url;}}
    }
    m_saveTimer.setSingleShot(true);m_saveTimer.setInterval(250);connect(&m_saveTimer,&QTimer::timeout,this,&SongArtwork::save);
}
void SongArtwork::setEnabled(bool value){
    if(m_enabled==value)return;m_enabled=value;QSettings().setValue("appearance/songCovers",value);
    if(!value){while(!m_queue.isEmpty())m_pending.remove(ArtworkPolicy::key(m_queue.dequeue()));}
    ++m_revision;emit changed();if(value)drain();
}
QVariantMap SongArtwork::urls() const {return m_urls;}
QString SongArtwork::cover(const QVariantMap &track) const {
    if(!m_enabled)return {};const auto direct=ArtworkPolicy::fromSong(track.value("source").toString(),track);
    return direct.isEmpty()?m_rows.value(ArtworkPolicy::key(track)).toMap().value("url").toString():direct;
}
void SongArtwork::request(const QVariantMap &track){
    if(!m_enabled)return;const auto key=ArtworkPolicy::key(track);if(key.isEmpty())return;
    const auto direct=ArtworkPolicy::fromSong(track.value("source").toString(),track);
    if(!direct.isEmpty()){store(key,direct);return;}
    const auto now=QDateTime::currentMSecsSinceEpoch();
    if(now-m_rows.value(key).toMap().value("at").toLongLong()<7LL*86400000||m_pending.contains(key)||now-m_attempted.value(key)<300000||m_queue.size()>=128)return;
    m_pending.insert(key);m_queue.enqueue(track);drain();
}
void SongArtwork::drain(){
    while(m_enabled&&m_running<2&&!m_queue.isEmpty()){
        const auto track=m_queue.dequeue();const auto key=ArtworkPolicy::key(track);++m_running;
        m_attempted[key]=QDateTime::currentMSecsSinceEpoch();if(m_attempted.size()>4096)m_attempted.clear();
        m_api.fetchSongDetails(track,[this,key](QVariantMap details,QString){
            --m_running;m_pending.remove(key);const auto art=ArtworkPolicy::url(details.value("coverUrl").toString());
            if(!art.isEmpty())store(key,art);drain();
        });
    }
}
void SongArtwork::store(const QString &key,const QString &url){
    if(key.isEmpty())return;const auto old=m_rows.value(key).toMap().value("url").toString();
    m_rows[key]=QVariantMap{{"url",url},{"at",QDateTime::currentMSecsSinceEpoch()}};
    m_urls[key]=url;
    while(m_rows.size()>1000){auto oldest=m_rows.begin();for(auto it=m_rows.begin();it!=m_rows.end();++it)if(it.value().toMap().value("at").toLongLong()<oldest.value().toMap().value("at").toLongLong())oldest=it;m_urls.remove(oldest.key());m_rows.erase(oldest);}
    m_saveTimer.start();if(old!=url){++m_revision;emit changed();}
}
void SongArtwork::save(){QDir().mkpath(QFileInfo(m_path).absolutePath());QSaveFile f(m_path);const auto bytes=QJsonDocument(QJsonObject::fromVariantMap(m_rows)).toJson(QJsonDocument::Compact);if(bytes.size()<=1024*1024&&f.open(QIODevice::WriteOnly)&&f.write(bytes)==bytes.size())f.commit();}
