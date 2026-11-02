#include "musicdetails.h"
#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QSaveFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QCryptographicHash>
#include <QSet>

MusicDetails::MusicDetails(QObject *parent):MusicDetails(MusicApi::builtinEndpoints(),QStandardPaths::writableLocation(QStandardPaths::CacheLocation)+"/music-details",parent){}
MusicDetails::MusicDetails(const MusicApi::Endpoints &e,const QString &path,QObject *parent):QObject(parent),m_api(e),m_search(e),m_cachePath(path) {
    connect(&m_search,&MusicApi::results,this,[this](QVariantList tracks,QString error){
        if(!m_open||!m_matching)return;
        m_matching=false;m_error=error;
        m_candidates.clear();for(const auto &v:tracks)if(v.toMap().value("source").toString()==m_source)m_candidates.append(v);
        if(error.isEmpty()&&m_candidates.isEmpty())m_error=QStringLiteral("没有找到候选歌曲，请切换来源。");
        emit changed();
    });
}
QVariantMap MusicDetails::state() const {
    return {{"track",m_track},{"original",m_original},{"details",m_details},{"comments",m_comments},{"candidates",m_candidates},
        {"source",m_source},{"order",m_order},{"loading",m_loading},{"matching",m_matching},{"error",m_error},
        {"detailsError",m_detailsError},{"stale",m_stale},{"hasMore",m_more},{"choosing",m_choosing}};
}
void MusicDetails::action(const QString &action,const QVariantMap &args) {
    if(action=="close"){++m_generation;m_open=false;m_loading=m_matching=m_choosing=false;emit changed();return;}
    if(action=="open"){m_original=args.value("track").toMap();m_order="hot";open(m_original);return;}
    if(!m_open)return;
    if(action=="source") {
        const auto source=args.value("source").toString();
        if(!QStringList{"netease","tencent","kuwo"}.contains(source))return;
        if(source==m_original.value("source").toString()){open(m_original);return;}
        ++m_generation;m_source=source;m_loading=false;m_matching=m_choosing=true;m_candidates.clear();m_comments.clear();m_details.clear();m_more=m_stale=false;m_error.clear();m_detailsError.clear();emit changed();
        m_search.search(m_original.value("name").toString()+" "+m_original.value("artist").toString(),{source},10);return;
    }
    if(action=="select") {
        const auto id=args.value("id").toString();for(const auto &v:m_candidates){const auto t=v.toMap();if(t.value("id").toString()==id){open(t);return;}}return;
    }
    if(m_choosing)return;
    if(action=="order") {
        const auto order=args.value("order").toString();if(order!="hot"&&order!="new")return;
        if(order==m_order)return;m_order=order;open(m_track);
    } else if(action=="retry")open(m_track);
    else if(action=="more"&&!m_loading&&m_more)load(m_page+1);
}
void MusicDetails::open(const QVariantMap &track) {
    ++m_generation;m_open=true;m_loading=m_matching=m_choosing=false;m_track=track;m_source=track.value("source").toString();
    m_details.clear();m_comments.clear();m_candidates.clear();m_error.clear();m_detailsError.clear();m_stale=m_more=false;m_page=0;
    if(!QStringList{"netease","tencent","kuwo"}.contains(m_source)||track.value("songId").toString().isEmpty()){
        m_error=QStringLiteral("本地歌曲暂无在线资料。");emit changed();return;
    }
    restore();m_loading=true;emit changed();
    const int generation=m_generation;
    m_api.fetchSongDetails(track,[this,generation](QVariantMap details,QString error){
        if(generation!=m_generation||!m_open)return;
        m_detailsError=error;
        if(error.isEmpty()){for(auto it=details.cbegin();it!=details.cend();++it)m_details[it.key()]=it.value();}
        emit changed();
        load(0);
    });
}
void MusicDetails::load(int page) {
    m_loading=true;m_error.clear();emit changed();const int generation=m_generation;
    auto track=m_track;if(m_details.contains("commentSongId"))track["commentSongId"]=m_details.value("commentSongId");
    m_api.fetchComments(track,m_order,page,[this,generation,page](QVariantMap result,QString error){
        if(generation!=m_generation||!m_open)return;
        m_loading=false;m_error=error;
        if(error.isEmpty()) {
            if(page==0)m_comments.clear();QSet<QString> seen;
            for(const auto &v:m_comments)seen.insert(v.toMap().value("id").toString());
            int added=0;for(const auto &v:result.value("comments").toList()){
                const auto id=v.toMap().value("id").toString();if(!seen.contains(id)){seen.insert(id);m_comments.append(v);++added;}
            }
            m_page=page;m_more=result.value("hasMore").toBool()&&added>0&&m_comments.size()<1000;
            if(result.contains("commentCount"))m_details["commentCount"]=result.value("commentCount");
            m_stale=false;save();
        }
        emit changed();
    });
}
QString MusicDetails::cacheFile() const {
    const auto key=(m_source+":"+m_track.value("songId").toString()+":"+m_order).toUtf8();
    return m_cachePath+"/"+QString::fromLatin1(QCryptographicHash::hash(key,QCryptographicHash::Sha256).toHex())+".json";
}
void MusicDetails::restore(){
    QFile f(cacheFile());if(!f.open(QIODevice::ReadOnly)||f.size()>2*1024*1024)return;
    const auto o=QJsonDocument::fromJson(f.readAll()).object();
    if(o.value("version").toInt()!=1)return;
    m_details=o.value("details").toObject().toVariantMap();m_comments=o.value("comments").toArray().toVariantList();m_stale=true;
}
void MusicDetails::save(){
    QDir().mkpath(m_cachePath);QSaveFile f(cacheFile());
    const auto bytes=QJsonDocument(QJsonObject::fromVariantMap({{"version",1},{"details",m_details},{"comments",m_comments}})).toJson(QJsonDocument::Compact);
    if(bytes.size()>2*1024*1024||!f.open(QIODevice::WriteOnly))return;
    if(f.write(bytes)!=bytes.size()||!f.commit())return;
    QDir dir(m_cachePath);auto files=dir.entryInfoList({"*.json"},QDir::Files,QDir::Time);qint64 size=0;
    for(int i=0;i<files.size();++i){size+=files[i].size();if(i>=100||size>16*1024*1024)QFile::remove(files[i].absoluteFilePath());}
}
