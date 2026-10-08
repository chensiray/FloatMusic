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
        QString vkeys;
        QString ourcraft;
        QString qqMusicu = "https://u.y.qq.com/cgi-bin/musicu.fcg";
        QString kuwoPlaylistSearch = "https://search.kuwo.cn/r.s";
        QString kuwoPlaylistDetail = "https://nplserver.kuwo.cn/pl.svc";
        QString kuwoRanking = "https://wbd.kuwo.cn/api/bd/bang/bang_info";
        QString kuwoMobi;
    };
    struct Lyrics { QString original, translation; bool instrumental = false; };
    struct Audio {
        QUrl url; QString sourceId, sourceName; int bitrate = 0;
        QString quality, format;
        int sampleRate = 0, bitsPerSample = 0;
        QString notice;
        double expectedDurationSeconds = 0;
    };
    static Endpoints builtinEndpoints();
    explicit MusicApi(QObject *parent = nullptr);
    explicit MusicApi(const Endpoints &endpoints, QObject *parent = nullptr);
    QString baseUrl() const { return m_base; }
    bool setBaseUrl(const QString &value);
    void search(const QString &keywords);
    void search(const QString &keywords, const QStringList &sources);
    void search(const QString &keywords, const QStringList &sources, int limit);
    void searchPlaylists(const QString &query);
    void searchPlaylists(const QString &query, const QStringList &sources);
    void searchPlaylists(const QString &query, const QStringList &sources, int limit);
    void fetchRankings(std::function<void(QVariantList, QString)> done);
    void fetchRankings(const QString &source, std::function<void(QVariantList, QString)> done);
    void fetchPlaylist(const QString &id, std::function<void(QVariantMap, QString)> done);
    void resolve(const QString &songId, std::function<void(QUrl, QString)> done);
    void resolve(const QString &songId, const QString &quality, std::function<void(QUrl, QString)> done);
    void resolveAudio(const QString &songId, const QString &quality, std::function<void(Audio, QString)> done,
                      const QStringList &excludedSources = {});
    void resolveAudio(const QString &source, const QString &songId, const QString &quality,
                      std::function<void(Audio, QString)> done, const QStringList &excludedSources = {},
                      double expectedDurationSeconds = 0);
    bool hasAudioBackups() const { return m_base.isEmpty() && (!m_endpoints.gdStudio.isEmpty() || !m_endpoints.injahow.isEmpty()); }
    bool hasAudioBackups(const QString &source) const;
    void clearAudioFailures() { m_audioCooldown.clear(); }
    void fetchLyrics(const QString &songId, std::function<void(Lyrics, QString)> done);
    void fetchLyrics(const QString &source, const QString &songId, std::function<void(Lyrics, QString)> done);
    static bool validQuality(const QString &quality);
    static bool validQuality(const QString &source, const QString &quality);
signals:
    void results(QVariantList tracks, QString error);
    void playlistResults(QVariantList playlists, QString error);
private:
    struct PlaylistFetch;
    struct AudioFetch;
    struct CatalogFetch;
    struct TencentSongSearch;
    struct TencentPlaylistSearch;
    void fetchTencentSongSearchPage(const std::shared_ptr<TencentSongSearch> &state);
    void fetchTencentPlaylistSearchPage(const std::shared_ptr<TencentPlaylistSearch> &state);
    void fetchCatalogPage(const std::shared_ptr<CatalogFetch> &state);
    void fetchCatalogPlaylist(const QString &source, const QString &kind, const QString &id,
                              std::function<void(QVariantMap, QString)> done);
    void fetchNeteaseRankings(std::function<void(QVariantList, QString)> done);
    void tryAudioSource(const std::shared_ptr<AudioFetch> &state);
    void fetchMetingLyrics(const QString &source, const QString &songId, std::function<void(Lyrics, QString)> done);
    void audioRequest(const QUrl &url, bool sample, int timeoutMs,
                      std::function<void(QByteArray, QUrl, QString, qint64)> done);
    void fetchPlaylistBatch(const std::shared_ptr<PlaylistFetch> &state);
    void finishPlaylist(const std::shared_ptr<PlaylistFetch> &state);
    QNetworkReply *get(const QUrl &url, const QString &operation, std::function<void(QByteArray, QString)> done);
    QNetworkReply *request(const QUrl &url, const QByteArray &body, bool post, const QString &operation,
                           std::function<void(QByteArray, QString)> done);
    QNetworkReply *qqRequest(const QString &module, const QString &method, const QJsonObject &params,
                             std::function<void(QByteArray, QString)> done);
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
    QList<QPointer<QNetworkReply>> m_searchReplies;
    QPointer<QNetworkReply> m_playlistSearchReply;
    QList<QPointer<QNetworkReply>> m_playlistSearchReplies;
    QPointer<QNetworkReply> m_rankingsReply;
    QPointer<QNetworkReply> m_audioReply;
    int m_audioGeneration = 0;
    QHash<QString, qint64> m_audioCooldown;
    QList<qint64> m_gdRequests;
};
