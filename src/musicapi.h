#pragma once
#include <QObject>
#include <QNetworkAccessManager>
#include <QUrl>
#include <QVariantList>
#include <QJsonObject>
#include <functional>

// NeteaseCloudMusicApi-compatible server; never stores cookies or resolved audio URLs.
class MusicApi : public QObject {
    Q_OBJECT
public:
    explicit MusicApi(QObject *parent = nullptr);
    QString baseUrl() const { return m_base; }
    bool setBaseUrl(const QString &value);
    void search(const QString &keywords);
    void resolve(const QString &songId, std::function<void(QUrl, QString)> done);
signals:
    void results(QVariantList tracks, QString error);
private:
    void request(const QString &path, const QList<QPair<QString, QString>> &query,
                 std::function<void(QJsonObject, QString)> done);
    QNetworkAccessManager m_network;
    QString m_base;
    int m_searchGeneration = 0;
};
