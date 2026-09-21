#pragma once
#include <QObject>
#include <QNetworkAccessManager>
#include <QUrl>
#include <QVariantList>
#include <QJsonObject>
#include <functional>
#include <QPointer>
#include <QNetworkReply>

// Built-in protocols plus an optional NeteaseCloudMusicApi-compatible server.
class MusicApi : public QObject {
    Q_OBJECT
public:
    struct Endpoints {
        QString metadata = "https://music.163.com";
        QString playback = "https://www.byfuns.top/api/1/";
        int timeoutMs = 15000;
    };
    struct Lyrics { QString original, translation; bool instrumental = false; };
    explicit MusicApi(QObject *parent = nullptr);
    explicit MusicApi(const Endpoints &endpoints, QObject *parent = nullptr);
    QString baseUrl() const { return m_base; }
    bool setBaseUrl(const QString &value);
    void search(const QString &keywords);
    void resolve(const QString &songId, std::function<void(QUrl, QString)> done);
    void resolve(const QString &songId, const QString &quality, std::function<void(QUrl, QString)> done);
    void fetchLyrics(const QString &songId, std::function<void(Lyrics, QString)> done);
    static bool validQuality(const QString &quality);
signals:
    void results(QVariantList tracks, QString error);
private:
    QNetworkReply *get(const QUrl &url, const QString &operation, std::function<void(QByteArray, QString)> done);
    static QJsonObject parseJson(const QByteArray &bytes, QString &error);
    QUrl endpoint(const QString &path, const QList<QPair<QString, QString>> &query) const;
    Endpoints m_endpoints;
    QNetworkAccessManager m_network;
    QString m_base;
    int m_searchGeneration = 0;
    QPointer<QNetworkReply> m_searchReply;
};
