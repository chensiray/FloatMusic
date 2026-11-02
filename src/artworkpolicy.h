#pragma once
#include <QUrl>
#include <QVariantMap>
#include <QRegularExpression>
namespace ArtworkPolicy {
inline QString url(const QString &text) {
    QUrl u(text.trimmed());
    if(text.size()>4096||!u.isValid()||u.host().isEmpty()||!u.userInfo().isEmpty()||!(u.scheme()=="https"||u.scheme()=="http"))return {};
    if(u.scheme()=="http")u.setScheme("https");return u.toString();
}
inline QString fromSong(const QString &source,const QVariantMap &song) {
    for(const auto &key:{"coverUrl","picUrl","pic_url","albumPic","albumpic","pic","imgurl","pic_id"}){
        const auto cover=url(song.value(key).toString());if(!cover.isEmpty())return cover;
    }
    const auto album=song.value(song.contains("al")?"al":"album").toMap();
    const auto cover=url(album.value("picUrl").toString());if(!cover.isEmpty())return cover;
    if(source=="tencent"){
        const auto mid=album.value("mid").toString().isEmpty()?song.value("albumMID",song.value("albummid")).toString():album.value("mid").toString();
        if(QRegularExpression("^[A-Za-z0-9]{1,64}$").match(mid).hasMatch())return "https://y.gtimg.cn/music/photo_new/T002R300x300M000"+mid+".jpg";
    }return {};
}
inline QString key(const QVariantMap &track) {
    const auto source=track.value("source").toString(),id=track.value("songId").toString();
    if(!QStringList{"netease","tencent","kuwo"}.contains(source))return {};
    static const QRegularExpression number("^[1-9][0-9]{0,18}$"),mid("^[A-Za-z0-9]{1,64}$");
    return (source=="tencent"?mid:number).match(id).hasMatch()?source+":"+id:QString();
}
}
