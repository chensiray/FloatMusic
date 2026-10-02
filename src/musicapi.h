#pragma once
#include <QObject>
#include <QNetworkAccessManager>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>
#include <QJsonObject>
#include <functional>
#include <QPointer>
#include <QNetworkReply>
#include <memory>
#include <QHash>

// Built-in protocols plus an optional NeteaseCloudMusicApi-compatible server.
class MusicApi : public QObject {
    Q_OBJECT
public:
    struct Endpoints {
        QString metadata = "https://music.163.com";
        QString playback = "https://www.byfuns.top/api/1/";
        int timeoutMs = 15000;
        QString gdStudio;
        QString injahow;
    };
    struct Lyrics { QString original, translation; bool instrumental = false; };
    struct Audio { QUrl url; QString sourceId, sourceName; int bitrate = 0; };
    static Endpoints builtinEndpoints();
    explicit MusicApi(QObject *parent = nullptr);
    explicit MusicApi(const Endpoints &endpoints, QObject *parent = nullptr);
    QString baseUrl() const { return m_base; }
    bool setBaseUrl(const QString &value);
    void search(const QString &keywords);
    void searchPlaylists(const QString &query);
    void fetchRankings(std::function<void(QVariantList, QString)> done);
    void fetchPlaylist(const QString &id, std::function<void(QVariantMap, QString)> done);
    void resolve(const QString &songId, std::function<void(QUrl, QString)> done);
    void resolve(const QString &songId, const QString &quality, std::function<void(QUrl, QString)> done);
    void resolveAudio(const QString &songId, const QString &quality, std::function<void(Audio, QString)> done,
                      const QStringList &excludedSources = {});
    bool hasAudioBackups() const { return m_base.isEmpty() && (!m_endpoints.gdStudio.isEmpty() || !m_endpoints.injahow.isEmpty()); }
    void clearAudioFailures() { m_audioCooldown.clear(); }
    void fetchLyrics(const QString &songId, std::function<void(Lyrics, QString)> done);
    static bool validQuality(const QString &quality);
signals:
    void results(QVariantList tracks, QString error);
    void playlistResults(QVariantList playlists, QString error);
private:
    struct PlaylistFetch;
    struct AudioFetch;
    void tryAudioSource(const std::shared_ptr<AudioFetch> &state);
    void audioRequest(const QUrl &url, bool sample, int timeoutMs, std::function<void(QByteArray, QUrl, QString)> done);
    void fetchPlaylistBatch(const std::shared_ptr<PlaylistFetch> &state);
    void finishPlaylist(const std::shared_ptr<PlaylistFetch> &state);
    QNetworkReply *get(const QUrl &url, const QString &operation, std::function<void(QByteArray, QString)> done);
    static QJsonObject parseJson(const QByteArray &bytes, QString &error);
    QUrl endpoint(const QString &path, const QList<QPair<QString, QString>> &query) const;
    Endpoints m_endpoints;
    QNetworkAccessManager m_network;
    QString m_base;
    int m_searchGeneration = 0;
    int m_playlistSearchGeneration = 0;
    int m_rankingsGeneration = 0;
    int m_sourceGeneration = 0;
    QPointer<QNetworkReply> m_searchReply;
    QPointer<QNetworkReply> m_playlistSearchReply;
    QPointer<QNetworkReply> m_rankingsReply;
    QPointer<QNetworkReply> m_audioReply;
    int m_audioGeneration = 0;
    QHash<QString, qint64> m_audioCooldown;
    QList<qint64> m_gdRequests;
};
