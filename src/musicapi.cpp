#include "musicapi.h"
#include <QNetworkReply>
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonArray>
#include <QSettings>

MusicApi::MusicApi(QObject *parent) : QObject(parent) {
    m_base = QSettings().value("netease/apiBase").toString();
}
bool MusicApi::setBaseUrl(const QString &value) {
    const QUrl url(value.trimmed());
    if (!value.trimmed().isEmpty() && (!url.isValid() || url.host().isEmpty()
        || (url.scheme() != "https" && url.scheme() != "http")
        || !url.userInfo().isEmpty() || url.hasQuery() || url.hasFragment())) return false;
    m_base = value.trimmed();
    while (m_base.endsWith('/')) m_base.chop(1);
    ++m_searchGeneration;
    QSettings().setValue("netease/apiBase", m_base);
    return true;
}
void MusicApi::request(const QString &path, const QList<QPair<QString, QString>> &query,
                       std::function<void(QJsonObject, QString)> done) {
    if (m_base.isEmpty()) { done({}, QStringLiteral("请先设置网易云 API 服务地址。")); return; }
    QUrl url(m_base + path); QUrlQuery params;
    for (const auto &pair : query) params.addQueryItem(pair.first, pair.second);
    url.setQuery(params);
    QNetworkRequest req(url); req.setTransferTimeout(15000);
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    auto *reply = m_network.get(req);
    reply->setReadBufferSize(2 * 1024 * 1024 + 1);
    connect(reply, &QIODevice::readyRead, reply, [reply] {
        if (reply->bytesAvailable() > 2 * 1024 * 1024) reply->abort();
    });
    connect(reply, &QNetworkReply::finished, this, [reply, done] {
        const auto bytes = reply->readAll(); const auto error = reply->error(); reply->deleteLater();
        if (error != QNetworkReply::NoError) { done({}, QStringLiteral("API 连接失败或超时，请检查服务地址和网络。")); return; }
        QJsonParseError parse;
        const auto doc = QJsonDocument::fromJson(bytes, &parse);
        if (parse.error != QJsonParseError::NoError || !doc.isObject()) { done({}, QStringLiteral("API 返回格式错误，应为 JSON。")); return; }
        const auto object = doc.object();
        if (object.value("code").toInt() != 200) { done({}, QStringLiteral("API 请求未成功，服务可能需要登录或暂不可用。")); return; }
        done(object, {});
    });
}
void MusicApi::search(const QString &keywords) {
    const int ticket = ++m_searchGeneration;
    if (keywords.trimmed().isEmpty()) { emit results({}, {}); return; }
    request("/cloudsearch", {{"keywords", keywords.trimmed()}, {"type", "1"}, {"limit", "30"}},
        [this, ticket](QJsonObject json, QString error) {
            if (ticket != m_searchGeneration) return;
            QVariantList tracks;
            for (const auto &value : json.value("result").toObject().value("songs").toArray()) {
                const auto song = value.toObject(); const QString id = QString::number(song.value("id").toInteger());
                if (id == "0" || song.value("name").toString().isEmpty()) continue;
                QStringList artists;
                const auto array = song.contains("ar") ? song.value("ar").toArray() : song.value("artists").toArray();
                for (const auto &artist : array) artists << artist.toObject().value("name").toString();
                tracks << QVariantMap{{"id", "netease:" + id}, {"songId", id}, {"source", "netease"},
                    {"name", song.value("name").toString()}, {"artist", artists.join(" / ")}};
            }
            emit results(tracks, error);
        });
}
void MusicApi::resolve(const QString &songId, std::function<void(QUrl, QString)> done) {
    request("/song/url/v1", {{"id", songId}, {"level", "standard"}}, [done](QJsonObject json, QString error) {
        if (!error.isEmpty()) { done({}, error); return; }
        const auto data = json.value("data").toArray();
        const QUrl url(data.isEmpty() ? QString() : data.first().toObject().value("url").toString());
        if (!url.isValid() || url.host().isEmpty() || (url.scheme() != "https" && url.scheme() != "http")) {
            done({}, QStringLiteral("该歌曲暂无可播放地址，可能受版权或账号权限限制。")); return;
        }
        done(url, {});
    });
}
