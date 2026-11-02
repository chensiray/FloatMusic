#include "musicapi.h"
#include "catalogcodec.h"
#include <QJsonArray>
#include <QJsonDocument>
#include <QDateTime>
#include <QRegularExpression>
#include <QUrlQuery>

namespace {
QString string(const QJsonValue &v) { return v.isString()?v.toString():v.isDouble()?QString::number(v.toInteger()):QString(); }
bool valid(const QString &source,const QString &id) {
    static const QRegularExpression number("^[1-9][0-9]{0,18}$"),mid("^[A-Za-z0-9]{1,64}$");
    return (source=="tencent"?mid:number).match(id).hasMatch() && QStringList{"netease","tencent","kuwo"}.contains(source);
}
QString image(QString value) {
    QUrl u(value); if(u.scheme()=="http")u.setScheme("https");
    return u.isValid() && u.scheme()=="https" && !u.host().isEmpty() && u.userInfo().isEmpty()?u.toString():QString();
}
void metric(QVariantMap &d,const char *key,const QJsonValue &v) {
    bool ok=false; const auto n=string(v).toLongLong(&ok); if(ok && n>=0)d[key]=n;
}
QUrl query(QString url,const QList<QPair<QString,QString>> &items) {
    QUrl u(url);QUrlQuery q;for(const auto &v:items)q.addQueryItem(v.first,v.second);u.setQuery(q);return u;
}
QVariantMap comment(const QJsonObject &o,bool qq) {
    const auto user=o.value("user").toObject();
    QString text=qq?CatalogCodec::descriptionText(o.value("rootcommentcontent").toString()):o.value("content").toString();
    const QString id=string(o.value(qq?"commentid":"commentId"));
    if(id.isEmpty()||text.trimmed().isEmpty())return {};
    const qint64 time=o.value("time").toVariant().toLongLong()*(qq?1000:1);
    QVariantMap row{{"id",id},{"content",text.left(16000)},
        {"author",(qq?o.value("nick").toString():user.value("nickname").toString()).left(200)},
        {"date",time>0?QDateTime::fromMSecsSinceEpoch(time).toString("yyyy-MM-dd HH:mm"):QString()}};
    metric(row,"likes",o.value(qq?"praisenum":"likedCount"));return row;
}
}

void MusicApi::fetchSongDetails(const QVariantMap &track,std::function<void(QVariantMap,QString)> done) {
    const auto source=track.value("source").toString(),id=track.value("songId").toString();
    if(!valid(source,id)){done({},QStringLiteral("本地歌曲暂无在线资料。"));return;}
    if(source=="kuwo") {
        // The public web detail/comment service is currently unavailable. Keep known catalog metadata.
        QVariantMap d;for(const auto &key:QStringList{"album","coverUrl","publishDate"})if(track.contains(key))d[key]=track.value(key);
        done(d,{});return;
    }
    auto parse=[track,source,id,done](QByteArray bytes,QString error) {
        if(!error.isEmpty()){done({},error);return;}
        QJsonParseError p;const auto doc=QJsonDocument::fromJson(bytes,&p);const auto root=doc.object();
        if(p.error!=QJsonParseError::NoError||!doc.isObject()) {done({},QStringLiteral("歌曲资料格式无效。"));return;}
        QJsonObject song;
        if(source=="netease") {
            if(root.value("code").toInt()!=200){done({},QStringLiteral("歌曲资料暂不可用。"));return;}
            const auto songs=root.value("songs").toArray();
            if(!songs.isEmpty())song=songs.first().toObject();
            if(string(song.value("id"))!=id){done({},QStringLiteral("歌曲资料与请求不一致。"));return;}
        } else {
            const auto req=root.value("req_0").toObject();
            if(root.value("code").toInt(-1)!=0||req.value("code").toInt(-1)!=0){done({},QStringLiteral("歌曲资料暂不可用。"));return;}
            song=req.value("data").toObject().value("track_info").toObject();
            if(song.value("mid").toString()!=id){done({},QStringLiteral("歌曲资料与请求不一致。"));return;}
        }
        const auto album=song.value("album").toObject();
        QVariantMap d{{"album",album.value("name").toString()}};
        QString cover=source=="netease"?album.value("picUrl").toString():QString();
        if(source=="tencent") {
            const auto mid=album.value("mid").toString();
            if(QRegularExpression("^[A-Za-z0-9]{1,64}$").match(mid).hasMatch())cover="https://y.gtimg.cn/music/photo_new/T002R300x300M000"+mid+".jpg";
            d["commentSongId"]=string(song.value("id"));
            d["publishDate"]=song.value("time_public").toString();
        } else {
            const auto time=album.value("publishTime").toVariant().toLongLong();
            if(time>0)d["publishDate"]=QDateTime::fromMSecsSinceEpoch(time).toString("yyyy-MM-dd");
        }
        if(!image(cover).isEmpty())d["coverUrl"]=image(cover);
        done(d,{});
    };
    if(source=="tencent") qqRequest("music.pf_song_detail_svr","get_song_detail_yqq",{{"song_mid",id}},parse);
    else get(query(m_endpoints.metadata+"/api/song/detail/",{{"id",id},{"ids","["+id+"]"}}),QStringLiteral("读取歌曲资料"),parse);
}

void MusicApi::fetchComments(const QVariantMap &track,const QString &order,int page,std::function<void(QVariantMap,QString)> done) {
    const auto source=track.value("source").toString(),id=track.value("songId").toString();
    if(!valid(source,id)||page<0||page>=50||!(order=="hot"||order=="new")){done({},QStringLiteral("评论请求无效。"));return;}
    if(source=="kuwo"){done({},QStringLiteral("此来源的公开评论暂不可用，可切换其他来源。"));return;}
    if(source=="tencent" && track.value("commentSongId").toString().isEmpty()) {
        fetchSongDetails(track,[this,track,order,page,done](QVariantMap d,QString error){
            if(!error.isEmpty()||d.value("commentSongId").toString().isEmpty()){done({},error.isEmpty()?QStringLiteral("评论所需的歌曲编号暂不可用。"):error);return;}
            auto t=track;t["commentSongId"]=d.value("commentSongId");fetchComments(t,order,page,done);
        });return;
    }
    const bool qq=source=="tencent";
    QUrl url=qq?query(m_endpoints.qqComments,{{"biztype","1"},{"topid",track.value("commentSongId").toString()},
        {"cmd",order=="hot"?"6":"8"},{"pagenum",QString::number(page)},{"pagesize","20"},{"format","json"}}):
        query(m_endpoints.metadata+"/api/v1/resource/comments/R_SO_4_"+id,{{"limit","20"},{"offset",QString::number(page*20)}});
    get(url,QStringLiteral("读取评论"),[done,qq,order,page](QByteArray bytes,QString error){
        if(!error.isEmpty()){done({},error);return;}
        QJsonParseError p;const auto doc=QJsonDocument::fromJson(bytes,&p);const auto root=doc.object();
        if(p.error!=QJsonParseError::NoError||!doc.isObject()||root.value("code").toInt(-1)!=(qq?0:200)) {
            done({},QStringLiteral("评论暂不可用，请稍后重试或切换来源。"));return;
        }
        QJsonValue raw;QJsonValue total;bool more=false;
        if(qq) {
            // cmd=6 already selects hot order; both commands put their page in "comment".
            const auto group=root.value("comment").toObject();
            raw=group.value("commentlist");total=root.value("comment").toObject().value("commenttotal");
            bool ok=false;const auto groupTotal=string(group.value("commenttotal")).toLongLong(&ok);
            more=ok && (page+1)*20<groupTotal;
        } else {
            raw=root.value(order=="hot"?"hotComments":"comments");total=root.value("total");
            more=order=="new" && root.value("more").toBool();
        }
        if(!raw.isArray()){done({},QStringLiteral("评论列表格式无效。"));return;}
        QVariantList rows;for(const auto &v:raw.toArray()){const auto row=comment(v.toObject(),qq);if(!row.isEmpty())rows.append(row);if(rows.size()>=20)break;}
        QVariantMap result{{"comments",rows},{"hasMore",more && !rows.isEmpty() && page<49}};
        metric(result,"commentCount",total);done(result,{});
    });
}
