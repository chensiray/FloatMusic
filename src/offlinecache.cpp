#include "offlinecache.h"
#include <QStandardPaths>
#include <QDir>
#include <QSaveFile>
#include <QJsonDocument>
#include <QJsonArray>
#include <QCryptographicHash>
#include <QDateTime>
#include <QStorageInfo>
#include <QTimer>
#include <algorithm>
#include <QRegularExpression>
#include <QFileInfo>
#include <climits>
#include <QSet>
#include <filesystem>
// Preserve the filesystem clock's native precision; JSON stores this as text.
// A cheap fingerprint avoids hashing every saved album on the UI thread at launch.
static QString modifiedTime(const QString &path) {
    std::error_code error;
    const auto nativePath = std::filesystem::u8path(path.toUtf8().constData());
    const auto time = std::filesystem::last_write_time(nativePath, error);
    return error ? QString() : QString::number(qint64(time.time_since_epoch().count()));
}
static bool unchangedFile(const QVariantMap &entry, const QString &path) {
    const auto expected = entry.value("modifiedTime").toString();
    return expected.isEmpty() || expected == modifiedTime(path);
}
QString OfflineCache::identity(const QVariantMap &t,const QString &q){return QString::fromLatin1(QCryptographicHash::hash((t.value("source").toString()+"\n"+t.value("songId").toString()+"\n"+q).toUtf8(),QCryptographicHash::Sha256).toHex());}
QString OfflineCache::canonicalKey(const QString &input) const {
    QString key = input;
    QSet<QString> visited;
    while (!m_entries.contains(key) && m_aliases.contains(key) && !visited.contains(key)) {
        visited.insert(key); key = m_aliases.value(key);
    }
    return key;
}
QString OfflineCache::path(const QString &k,const QString &s)const{return m_directory+"/"+k+s;}
OfflineCache::OfflineCache(const MusicApi::Endpoints &e,const QString &d,QObject *p):QObject(p),m_directory(d.isEmpty()?QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)+"/offline-cache":d),m_api(e){
#ifdef Q_OS_ANDROID
 if(d.isEmpty()){m_enabled=false;return;} // Java owns Android storage exclusively.
#endif
 QDir().mkpath(m_directory); QFile file(m_directory+"/index.json");
 if(file.open(QIODevice::ReadOnly)){auto root=QJsonDocument::fromJson(file.readAll()).object(); auto aliases=root.value("aliases").toObject();for(auto i=aliases.begin();i!=aliases.end();++i)m_aliases[i.key()]=i.value().toString();auto settings=root.value("settings").toObject().toVariantMap();for(auto i=settings.begin();i!=settings.end();++i)if(m_settings.contains(i.key()))m_settings[i.key()]=i.value();
 for(auto v:root.value("entries").toArray()){auto x=v.toObject().toVariantMap();auto k=x.value("key").toString();if(k.size()!=64||k.contains(QRegularExpression("[^a-f0-9]")))continue;
 x["path"]=path(k,".audio");if(x.value("pendingDelete").toBool()){if(!QFileInfo::exists(path(k,".audio"))||QFile::remove(path(k,".audio")))continue;x["error"]="Cannot remove cached file";}auto state=x.value("state").toString();x["lyricsLoading"]=false;for(const auto &field:{QString("lyricState"),QString("translationState")})if(x.value(field)=="loading")x[field]="unknown";if(state=="complete"&&(!validFile(path(k,".audio"),x.value("bytes").toLongLong())||!unchangedFile(x,path(k,".audio")))){x["state"]="failed";x["error"]="Cached audio missing or damaged";}else if(state=="downloading"||state=="resolving")x["state"]="queued";if(x.value("state")=="complete"&&!x.contains("modifiedTime"))x["modifiedTime"]=modifiedTime(path(k,".audio"));m_entries[k]=x;}}
 for(const auto &name:QDir(m_directory).entryList({"*.audio"},QDir::Files)){
   const auto key=name.chopped(6);
   if(key.size()!=64||key.contains(QRegularExpression("[^a-f0-9]"))||m_entries.contains(key))continue;
   if(!QFile::remove(path(key,".audio")))m_entries[key]={{"key",key},{"path",path(key,".audio")},{"state","failed"},{"pendingDelete",true},{"bytes",QFileInfo(path(key,".audio")).size()},{"error","Cannot remove cached file"}};
 }
 for(auto name:QDir(m_directory).entryList({"*.part"},QDir::Files))QFile::remove(m_directory+"/"+name);
 QTimer::singleShot(0,this,&OfflineCache::pump);
}
OfflineCache::~OfflineCache(){m_stopping=true;++m_generation;if(m_reply){disconnect(m_reply,nullptr,this,nullptr);m_reply->abort();}m_file.close();if(!m_active.isEmpty()){QFile::remove(path(m_active,".part"));if(m_entries.contains(m_active))m_entries[m_active]["state"]="queued";}save();}
bool OfflineCache::save(){if(!m_enabled)return false;QJsonObject aliases;for(auto i=m_aliases.begin();i!=m_aliases.end();++i)aliases[i.key()]=i.value();if(!QDir(m_directory).exists())return false;QSaveFile f(m_directory+"/index.json");if(!f.open(QIODevice::WriteOnly))return false;auto bytes=QJsonDocument(QJsonObject{{"version",1},{"aliases",aliases},{"settings",QJsonObject::fromVariantMap(m_settings)},{"entries",QJsonArray::fromVariantList([this]{QVariantList out;for(const auto &value:m_entries)out.append(value);return out;}())}}).toJson();return f.write(bytes)==bytes.size()&&f.commit();}
QVariantList OfflineCache::entries()const{QVariantList out;for(auto x:m_entries){x.remove("lyrics");x.remove("translation");out.append(x);}return out;}
QVariantMap OfflineCache::summary()const{qint64 a=0,m=0;for(auto x:m_entries){const QFileInfo file(path(x.value("key").toString(),".audio"));if(file.exists()){if(x.value("manual").toBool())m+=file.size();else a+=file.size();}}return {{"totalBytes",a+m},{"manualBytes",m},{"automaticBytes",a},{"count",m_entries.size()}};}
bool OfflineCache::validFile(const QString &p,qint64 expected)const{QFile f(p);if(!f.open(QIODevice::ReadOnly)||f.size()<12||(expected>=0&&f.size()!=expected))return false;auto h=f.read(64);return h.startsWith("ID3")||h.startsWith("fLaC")||h.startsWith("OggS")||(h.startsWith("RIFF")&&h.mid(8,4)=="WAVE")||h.mid(4,4)=="ftyp"||(uchar(h[0])==255&&(uchar(h[1])&0xe0)==0xe0);}
QString OfflineCache::enqueue(const QVariantMap &t,const QString &q,bool manual,const MusicApi::Audio &audio){if(t.value("songId").toString().isEmpty()||(!manual&&!m_settings.value("automaticEnabled").toBool()))return {};
 for(auto i=m_entries.begin();i!=m_entries.end();++i){auto x=i.value();if(x.value("track").toMap().value("source")==t.value("source")&&x.value("track").toMap().value("songId")==t.value("songId")&&(x.value("requestedQuality")==q||x.value("requestedQualities").toStringList().contains(q)||x.value("quality")==q)&&!x.value("pendingDelete").toBool()){auto key=i.key();if(manual)promote(key);if(x.value("state")=="failed"||x.value("state")=="cancelled"){if(audio.url.isValid())m_audio[key]=audio;retry(key);}return key;}}
 auto k=identity(t,audio.quality.isEmpty()?q:audio.quality);
 if(m_entries.contains(k)){
   auto &existing=m_entries[k];
   auto requests=existing.value("requestedQualities").toStringList();
   requests.append(existing.value("requestedQuality").toString());requests.append(q);requests.removeDuplicates();
   existing["requestedQualities"]=requests;
   if(manual){existing["manual"]=true;existing["pendingDelete"]=false;existing.remove("removeAttempts");}
   if(existing.value("state")=="failed"||existing.value("state")=="cancelled"){
     if(audio.url.isValid())m_audio[k]=audio;retry(k);
   }
   save();emit changed();return k;
 }
 QVariantMap x{{"key",k},{"track",t},{"title",t.value("name")},{"artist",t.value("artist")},{"source",t.value("source")},{"requestedQuality",q},{"requestedQualities",QStringList{q}},{"quality",audio.quality.isEmpty()?q:audio.quality},{"manual",manual},{"state","queued"},{"bytes",0},{"total",-1},{"progress",-1},{"lyricState","unknown"},{"translationState","unknown"},{"lastAccess",QDateTime::currentMSecsSinceEpoch()},{"path",path(k,".audio")}};m_entries[k]=x;if(audio.url.isValid())m_audio[k]=audio;save();emit changed();QTimer::singleShot(0,this,&OfflineCache::pump);return k;}
void OfflineCache::pump(){if(!m_enabled||m_stopping||!m_active.isEmpty())return;for(auto i=m_entries.begin();i!=m_entries.end();++i){if(i.value().value("state")!="queued")continue;if(!i.value().value("manual").toBool()&&!m_settings.value("automaticEnabled").toBool())continue;auto k=i.key();m_active=k;auto generation=++m_generation;m_entries[k]["state"]="resolving";save();emit changed();if(m_audio.contains(k)){download(k,m_audio.take(k));return;}auto t=i.value().value("track").toMap();m_api.resolveAudio(t.value("source").toString(),t.value("songId").toString(),i.value().value("requestedQuality").toString(),[this,k,generation](MusicApi::Audio a,QString error){if(generation!=m_generation||m_active!=k)return;if(!error.isEmpty()){finish(k,error);return;}download(k,a);},{},t.value("duration").toDouble());return;}}
bool OfflineCache::reserve(const QString &k,qint64 bytes){auto limit=m_settings.value("automaticLimitBytes").toLongLong();if(m_entries.value(k).value("manual").toBool())return true;if(bytes>limit)return false;auto used=[&]{qint64 n=0;for(auto i=m_entries.begin();i!=m_entries.end();++i)if(i.key()!=k&&!i.value().value("manual").toBool()&&(i.value().value("state")=="complete"||i.value().value("pendingDelete").toBool()))n+=QFileInfo(path(i.key(),".audio")).size();return n;};while(used()+bytes>limit){QString oldest;qint64 time=LLONG_MAX;for(auto i=m_entries.begin();i!=m_entries.end();++i)if(i.key()!=k&&i.key()!=m_inUse&&!i.value().value("manual").toBool()&&(i.value().value("state")=="complete"||i.value().value("pendingDelete").toBool())&&i.value().value("lastAccess").toLongLong()<time){oldest=i.key();time=i.value().value("lastAccess").toLongLong();}if(oldest.isEmpty())return false;remove(oldest);if(m_entries.contains(oldest))return false;}return true;}
void OfflineCache::download(const QString &requestedKey,const MusicApi::Audio &a){
 QString k=requestedKey; const auto actualQuality=a.quality.isEmpty()?QString("standard"):a.quality;
 auto current=m_entries.value(k); const auto canonical=identity(current.value("track").toMap(),actualQuality);
 if(canonical!=k){
   m_aliases[k]=canonical; if(m_inUse==k)m_inUse=canonical; auto existing=m_entries.value(canonical);
   if(existing.value("state")=="complete"&&(!existing.value("pendingDelete").toBool()||current.value("manual").toBool())&&validFile(path(canonical,".audio"),existing.value("bytes").toLongLong())&&unchangedFile(existing,path(canonical,".audio"))){
     auto requests=existing.value("requestedQualities").toStringList();requests.append(existing.value("requestedQuality").toString());requests.append(current.value("requestedQuality").toString());requests.append(current.value("requestedQualities").toStringList());requests.removeDuplicates();
     m_entries[canonical]["requestedQualities"]=requests;
     if(current.value("manual").toBool()){m_entries[canonical]["manual"]=true;m_entries[canonical]["pendingDelete"]=false;m_entries[canonical].remove("removeAttempts");}
     m_entries.remove(k);m_active.clear();save();emit changed();QTimer::singleShot(0,this,&OfflineCache::pump);return;
   }
   if(existing.value("pendingDelete").toBool()){finish(k,"Cached file pending deletion");return;}
   auto requests=current.value("requestedQualities").toStringList();
   requests.append(current.value("requestedQuality").toString());
   requests.append(existing.value("requestedQuality").toString());
   requests.append(existing.value("requestedQualities").toStringList());requests.removeAll(QString());requests.removeDuplicates();
   current["requestedQualities"]=requests;
   current["manual"]=current.value("manual").toBool()||existing.value("manual").toBool();
   for(const auto &field:{QString("lyrics"),QString("translation"),QString("lyricState"),QString("translationState"),QString("instrumental")})
     if(existing.contains(field))current[field]=existing.value(field);
   m_audio.remove(canonical);m_lyricTickets.remove(canonical);
   current["key"]=canonical;current["path"]=path(canonical,".audio");
   m_entries.remove(k);m_entries[canonical]=current;k=canonical;m_active=k;
 }
 auto &x=m_entries[k];x["expectedDurationSeconds"]=a.expectedDurationSeconds;x["quality"]=a.quality.isEmpty()?QString("standard"):a.quality;x["format"]=a.format;x["bitrate"]=a.bitrate;x["sampleRate"]=a.sampleRate;x["bitsPerSample"]=a.bitsPerSample;x["state"]="downloading";x["bytes"]=0;x["total"]=-1;x["error"]="";
 m_downloadHash.reset();m_file.setFileName(path(k,".part"));if(!m_file.open(QIODevice::WriteOnly)){finish(k,m_file.errorString());return;}QNetworkRequest request(a.url);request.setTransferTimeout(30000);m_reply=m_network.get(request);auto reply=m_reply.data();const auto gen=m_generation;
 connect(reply,&QNetworkReply::readyRead,this,[this,k,reply,gen]{if(gen!=m_generation)return;auto data=reply->readAll();auto bytes=m_file.size()+data.size();auto total=reply->header(QNetworkRequest::ContentLengthHeader).toLongLong();if(!reserve(k,qMax(bytes,total))){finish(k,"Automatic cache capacity exceeded");return;}QStorageInfo disk(m_directory);if(disk.isValid()&&disk.bytesAvailable()<data.size()+1048576){finish(k,"Insufficient disk space");return;}if(m_file.write(data)!=data.size()){finish(k,m_file.errorString());return;}m_downloadHash.addData(data);auto &x=m_entries[k];x["bytes"]=bytes;x["total"]=total>0?total:-1;x["progress"]=total>0?double(bytes)/total:-1;emit changed();});
 connect(reply,&QNetworkReply::finished,this,[this,k,reply,gen]{if(gen!=m_generation)return;const int status=reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();QString error;if(reply->error()!=QNetworkReply::NoError)error=reply->errorString();else if(status!=200)error="Audio HTTP response failed";m_file.close();auto total=m_entries[k].value("total").toLongLong();if(error.isEmpty()&&!validFile(path(k,".part"),total>0?total:-1))error="Audio response is incomplete or invalid";finish(k,error);});save();emit changed();}
void OfflineCache::finish(const QString &k,const QString &error){++m_generation;if(m_reply){disconnect(m_reply,nullptr,this,nullptr);m_reply->abort();m_reply->deleteLater();m_reply=nullptr;}m_file.close();if(m_entries.contains(k)){auto &x=m_entries[k];QString failure=error;if(failure.isEmpty()){QFile::remove(path(k,".audio"));if(!QFile::rename(path(k,".part"),path(k,".audio")))failure="Cannot commit cached file";}if(failure.isEmpty()){x["state"]="complete";x["bytes"]=QFileInfo(path(k,".audio")).size();x["progress"]=1.;x["sha256"]=QString::fromLatin1(m_downloadHash.result().toHex());x["modifiedTime"]=modifiedTime(path(k,".audio"));}else{x["state"]="failed";x["error"]=failure;QFile::remove(path(k,".part"));}if(!save()&&failure.isEmpty()){x["state"]="failed";x["error"]="Cannot commit cache index";QFile::remove(path(k,".audio"));}if(failure.isEmpty()&&x.value("state")=="complete")retryLyrics(k);}m_active.clear();emit changed();QTimer::singleShot(0,this,&OfflineCache::pump);}
void OfflineCache::retryLyrics(const QString &inputKey) {
    const auto key = canonicalKey(inputKey);
    if (!m_entries.contains(key)) return;
    const auto ticket = ++m_lyricSerial;
    m_lyricTickets[key] = ticket;
    auto &entry = m_entries[key];
    const auto track = entry.value("track").toMap();
    entry["lyricsLoading"] = true;
    for (const auto &field : {QString("lyricState"), QString("translationState")})
        if (entry.value(field) != "available" && entry.value(field) != "none") entry[field] = "loading";
    m_api.fetchLyrics(track.value("source").toString(), track.value("songId").toString(),
        [this, key, ticket](MusicApi::Lyrics lyrics, QString error) {
            if (!m_entries.contains(key) || m_lyricTickets.value(key) != ticket) return;
            auto &entry = m_entries[key];
            const auto previous = entry;
            const auto update = [&](const QString &textField, const QString &stateField, const QString &text) {
                const auto oldState = entry.value(stateField).toString();
                const bool known = oldState == "available" || oldState == "none";
                if (!error.isEmpty()) {
                    if (!known) entry[stateField] = "failed";
                } else if (!text.isEmpty()) {
                    entry[textField] = text; entry[stateField] = "available";
                } else if (lyrics.instrumental) {
                    entry[textField] = QString(); entry[stateField] = "none";
                } else if (!known) entry[stateField] = "unknown";
            };
            update("lyrics", "lyricState", lyrics.original);
            update("translation", "translationState", lyrics.translation);
            if (error.isEmpty() && lyrics.instrumental) entry["instrumental"] = true;
            else if (!lyrics.original.isEmpty()) entry["instrumental"] = false;
            entry["lyricsLoading"] = false; entry["lyricError"] = error;
            if (!save()) {
                entry = previous; entry["lyricsLoading"] = false;
                for (const auto &field : {QString("lyricState"), QString("translationState")})
                    if (entry.value(field) == "loading") entry[field] = "failed";
                entry["lyricError"] = "Cannot commit lyrics cache index";
            }
            emit changed();
        });
    emit changed();
}
QVariantMap OfflineCache::lookupKey(const QString &inputKey) {
    const auto key = canonicalKey(inputKey);
    auto entry = m_entries.value(key);
    if (entry.value("state") != "complete" || entry.value("pendingDelete").toBool()) return {};
    if (!validFile(path(key, ".audio"), entry.value("bytes").toLongLong()) || !unchangedFile(entry, path(key, ".audio"))) {
        m_entries[key]["state"] = "failed";
        m_entries[key]["error"] = "Cached audio missing or damaged";
        save(); emit changed(); return {};
    }
    m_entries[key]["lastAccess"] = QDateTime::currentMSecsSinceEpoch(); save();
    return m_entries[key];
}
QVariantMap OfflineCache::lookup(const QVariantMap &track, const QString &quality) {
    QStringList preferred, fallback;
    for (auto i = m_entries.cbegin(); i != m_entries.cend(); ++i) {
        const auto entry = i.value(); const auto identity = entry.value("track").toMap();
        if (entry.value("state") != "complete" || entry.value("pendingDelete").toBool()
            || identity.value("source") != track.value("source") || identity.value("songId") != track.value("songId")) continue;
        if (entry.value("quality") == quality || entry.value("requestedQuality") == quality
            || entry.value("requestedQualities").toStringList().contains(quality)) preferred.append(i.key());
        else fallback.append(i.key());
    }
    preferred.append(fallback);
    for (const auto &key : preferred) { const auto entry = lookupKey(key); if (!entry.isEmpty()) return entry; }
    return {};
}
void OfflineCache::promote(const QString &inputKey) {
    const auto key = canonicalKey(inputKey);
    if (!m_entries.contains(key)) return;
    const bool wasManual = m_entries[key].value("manual").toBool();
    m_entries[key]["manual"] = true;
    const auto wasPending=m_entries[key].value("pendingDelete");
    m_entries[key]["pendingDelete"]=false;
    if (!save()) {
        m_entries[key]["manual"] = wasManual;
        m_entries[key]["pendingDelete"]=wasPending;
        m_entries[key]["error"] = "Cannot commit cache index";
    }
    emit changed();
}
void OfflineCache::retry(const QString &inputKey) {
    const auto key = canonicalKey(inputKey);
    if (!m_entries.contains(key) || key == m_active) return;
    if (m_entries[key].value("state") == "complete") { retryLyrics(key); return; }
    if (m_entries[key].value("pendingDelete").toBool()) return;
    m_entries[key]["state"] = "queued"; m_entries[key]["error"] = "";
    reserve({}, 0); save(); emit changed(); pump();
}
void OfflineCache::cancel(const QString &inputKey){const auto k=canonicalKey(inputKey);if(!m_entries.contains(k)||m_entries[k].value("state")=="complete")return;if(k==m_active){finish(k,"Cancelled");}m_entries[k]["state"]="cancelled";save();emit changed();}
void OfflineCache::remove(const QString &inputKey) {
    const auto key = canonicalKey(inputKey);
    if (!m_entries.contains(key)) return;
    if (key == m_inUse) { m_entries[key]["pendingDelete"] = true; save(); emit changed(); return; }
    if (key == m_active) cancel(key);
    bool removed = true;
    for (const auto &suffix : {QString(".part"), QString(".audio")}) {
        const auto file = path(key, suffix);
        if (QFileInfo::exists(file) && !QFile::remove(file)) removed = false;
    }
    if (!removed) {
        auto &entry = m_entries[key]; entry["pendingDelete"] = true;
        entry["error"] = "Cannot remove cached file";
        const auto attempts = entry.value("removeAttempts").toInt() + 1; entry["removeAttempts"] = attempts;
        if (attempts < 3) QTimer::singleShot(200, this, [this, key] { if(m_entries.value(key).value("pendingDelete").toBool())remove(key); });
    } else {
        m_entries.remove(key); m_lyricTickets.remove(key); m_audio.remove(key);
    }
    save(); emit changed();
}
void OfflineCache::clear(bool all){for(auto k:m_entries.keys())if(all||!m_entries[k].value("manual").toBool())remove(k);}
void OfflineCache::setInUse(const QString &inputKey){const auto k=canonicalKey(inputKey);auto previous=m_inUse;m_inUse=k;if(previous!=k&&m_entries.value(previous).value("pendingDelete").toBool())remove(previous);reserve({},0);}
void OfflineCache::setSettings(const QVariantMap &s){for(auto k:{QString("automaticEnabled"),QString("wifiOnly")})if(s.contains(k))m_settings[k]=s[k].toBool();if(s.contains("automaticLimitBytes"))m_settings["automaticLimitBytes"]=qMax<qint64>(1,s["automaticLimitBytes"].toLongLong());if(!m_settings["automaticEnabled"].toBool())for(auto k:m_entries.keys())if(!m_entries[k].value("manual").toBool()&&m_entries[k].value("state")!="complete")cancel(k);if(!m_active.isEmpty()&&!m_entries[m_active].value("manual").toBool()
 &&!reserve(m_active,qMax(m_entries[m_active].value("bytes").toLongLong(),m_entries[m_active].value("total").toLongLong())))
   finish(m_active,"Automatic cache capacity exceeded");
 reserve({},0);save();emit changed();pump();}

void OfflineCache::invalidate(const QString &inputKey){const auto k=canonicalKey(inputKey);if(!m_entries.contains(k))return;m_entries[k]["state"]="failed";m_entries[k]["error"]="Cached audio cannot be decoded";save();emit changed();}
