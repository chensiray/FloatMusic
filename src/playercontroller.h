#pragma once
#include <QObject>
#include <QUrl>
#include <QMediaPlayer>
#include <QAudioOutput>
#include <QTimer>
#include <QMediaDevices>
#include <QVariantList>
#include <QRect>
#include "playliststore.h"
#include "musicapi.h"

class PlayerController : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString title READ title NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(QString error READ error NOTIFY changed)
    Q_PROPERTY(qint64 position READ position NOTIFY changed)
    Q_PROPERTY(qint64 duration READ duration NOTIFY changed)
    Q_PROPERTY(bool playing READ playing NOTIFY changed)
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(bool seekable READ seekable NOTIFY changed)
    Q_PROPERTY(bool android READ android CONSTANT)
    Q_PROPERTY(bool overlayAllowed READ overlayAllowed NOTIFY changed)
    Q_PROPERTY(int volume READ volume WRITE setVolume NOTIFY audioSettingsChanged)
    Q_PROPERTY(QVariantList audioOutputs READ audioOutputs NOTIFY audioSettingsChanged)
    Q_PROPERTY(QString selectedOutput READ selectedOutput NOTIFY audioSettingsChanged)
    Q_PROPERTY(QString outputName READ outputName NOTIFY audioSettingsChanged)
    Q_PROPERTY(QVariantList playlists READ playlists NOTIFY libraryChanged)
    Q_PROPERTY(QVariantList tracks READ tracks NOTIFY libraryChanged)
    Q_PROPERTY(QString activePlaylist READ activePlaylist NOTIFY libraryChanged)
    Q_PROPERTY(QString currentTrack READ currentTrack NOTIFY changed)
    Q_PROPERTY(QVariantList searchResults READ searchResults NOTIFY searchChanged)
    Q_PROPERTY(bool searching READ searching NOTIFY searchChanged)
    Q_PROPERTY(QString searchMessage READ searchMessage NOTIFY searchChanged)
    Q_PROPERTY(QString apiBase READ apiBase NOTIFY searchChanged)
    Q_PROPERTY(QString quality READ quality NOTIFY changed)
    Q_PROPERTY(QString qualityInfo READ qualityInfo NOTIFY changed)
    Q_PROPERTY(QString lyrics READ lyrics NOTIFY lyricsChanged)
    Q_PROPERTY(QString translation READ translation NOTIFY lyricsChanged)
    Q_PROPERTY(QString lyricsMessage READ lyricsMessage NOTIFY lyricsChanged)
    Q_PROPERTY(bool lyricsLoading READ lyricsLoading NOTIFY lyricsChanged)
    Q_PROPERTY(bool lyricsFailed READ lyricsFailed NOTIFY lyricsChanged)
    Q_PROPERTY(bool online READ online NOTIFY changed)
    Q_PROPERTY(QString artist READ artist NOTIFY changed)
    Q_PROPERTY(QVariantList favorites READ favorites NOTIFY favoritesChanged)
    Q_PROPERTY(bool currentFavorite READ currentFavorite NOTIFY changed)
    Q_PROPERTY(QString favoriteMessage READ favoriteMessage NOTIFY favoritesChanged)
public:
    explicit PlayerController(QObject *parent = nullptr);
    explicit PlayerController(const MusicApi::Endpoints &endpoints, QObject *parent = nullptr);
    ~PlayerController() override;
    QString title() const { return m_title; }
    QString status() const { return m_status; }
    QString error() const { return m_error; }
    qint64 position() const { return m_position; }
    qint64 duration() const { return m_duration; }
    bool playing() const { return m_playing; }
    bool ready() const { return m_ready; }
    bool busy() const { return m_busy; }
    bool seekable() const { return m_seekable; }
    bool android() const;
    bool overlayAllowed() const { return m_overlayAllowed; }
    int volume() const { return m_volume; }
    QVariantList audioOutputs() const { return m_audioOutputs; }
    QString selectedOutput() const { return m_selectedOutput; }
    QString outputName() const { return m_outputName; }
    QVariantList playlists() const { return m_library.playlists(); }
    QVariantList tracks() const { return m_library.tracks(); }
    QString activePlaylist() const { return m_library.activeId(); }
    QString currentTrack() const { return m_currentTrack; }
    QVariantList searchResults() const { return m_searchResults; }
    bool searching() const { return m_searching; }
    QString searchMessage() const { return m_searchMessage; }
    QString apiBase() const { return m_api.baseUrl(); }
    QString quality() const { return m_quality; }
    QString qualityInfo() const;
    QString lyrics() const { return m_lyrics; }
    QString translation() const { return m_translation; }
    QString lyricsMessage() const { return m_lyricsMessage; }
    bool lyricsLoading() const { return m_lyricsLoading; }
    bool lyricsFailed() const { return m_lyricsFailed; }
    bool online() const { return m_online; }
    QString artist() const { return m_loadedTrack.value("artist").toString(); }
    QVariantList favorites() const { return m_favorites; }
    bool currentFavorite() const;
    QString favoriteMessage() const { return m_favoriteMessage; }
    Q_INVOKABLE void toggleFavorite();
    Q_INVOKABLE void playFavorite(const QString &id);
    Q_INVOKABLE void addFavorite(const QString &id);
    Q_INVOKABLE void removeFavorite(const QString &id);
    Q_INVOKABLE QRect desktopWorkArea(int x, int y) const;
    Q_INVOKABLE void setQuality(const QString &quality);
    Q_INVOKABLE void playSearchResult(int index);
    Q_INVOKABLE void retryPlayback();
    Q_INVOKABLE void retryLyrics();
    Q_INVOKABLE void setApiBase(const QString &url);
    Q_INVOKABLE void search(const QString &keywords);
    Q_INVOKABLE void addSearchResult(int index);
    Q_INVOKABLE void createPlaylist(const QString &name);
    Q_INVOKABLE void renamePlaylist(const QString &name);
    Q_INVOKABLE void deletePlaylist();
    Q_INVOKABLE void selectPlaylist(const QString &id);
    Q_INVOKABLE void removeTrack(const QString &id);
    Q_INVOKABLE void playTrack(const QString &id);
    Q_INVOKABLE void previous();
    Q_INVOKABLE void next();
    Q_INVOKABLE void setVolume(int volume);
    Q_INVOKABLE void selectOutput(const QString &id);
    Q_INVOKABLE void refreshOutputs();
    Q_INVOKABLE void importFile(const QUrl &url);
    Q_INVOKABLE void chooseAndroidFile();
    Q_INVOKABLE void toggle();
    Q_INVOKABLE void seek(qint64 milliseconds);
    Q_INVOKABLE void showFloating();
    Q_INVOKABLE void initializeAndroidUi();
    Q_INVOKABLE int androidThemeMode() const;
    Q_INVOKABLE void setDarkTheme(bool dark);
    Q_INVOKABLE void quit();
    Q_INVOKABLE void rejectDrop();
signals:
    void changed();
    void audioSettingsChanged();
    void libraryChanged();
    void searchChanged();
    void lyricsChanged();
    void favoritesChanged();
private:
    void loadTrack(const QVariantMap &track, bool autoplay, bool preservePosition = false);
    void step(int delta, bool automatic = false);
    void syncQueue();
    void saveFavorites();
    QVariantMap favoriteTrack(const QString &id) const;
    QVariantList m_favorites;
    QString m_favoriteMessage;
#ifdef Q_OS_ANDROID
    void processOverlayEvents();
    void publishOverlayUi();
    QByteArray m_overlayUi;
    QString m_overlayMessage;
#endif
    PlaylistStore m_library;
    MusicApi m_api;
    QVariantList m_searchResults;
    QString m_currentTrack, m_searchMessage;
    bool m_searching = false, m_autoplay = false, m_resolving = false, m_importing = false, m_online = false;
    int m_loadGeneration = 0;
    int m_lyricsGeneration = 0;
    QString m_quality = "standard", m_loadedQuality;
    QString m_androidQualityInfo;
    QString m_lyrics, m_translation, m_lyricsMessage = QStringLiteral("播放在线歌曲后显示歌词。");
    bool m_lyricsLoading = false, m_lyricsFailed = false;
    QVariantMap m_loadedTrack, m_requestedTrack;
    qint64 m_pendingPosition = -1;
    QTimer m_mediaTimeout;
    void sync();
    void androidCommand(const QString &command, const QString &value = {});
    QMediaPlayer m_player;
    QAudioOutput m_output;
    QMediaDevices m_devices;
    int m_volume = 70;
    QVariantList m_audioOutputs;
    QString m_selectedOutput, m_outputName;
    QTimer m_timer;
    QString m_title = QStringLiteral("还没有选择音乐");
    QString m_status = QStringLiteral("导入本地音频，或按歌名搜索试听");
    QString m_error;
    QString m_cachedFile;
    qint64 m_position = 0, m_duration = 0;
    bool m_playing = false, m_ready = false, m_busy = false, m_seekable = false, m_overlayAllowed = false;
};
