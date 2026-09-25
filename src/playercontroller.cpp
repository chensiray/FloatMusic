#include "playercontroller.h"
#include "audiofilepolicy.h"
#include <QCoreApplication>
#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QStandardPaths>
#include <QSaveFile>
#include <QUuid>
#include <QFutureWatcher>
#include <QtConcurrent>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QSettings>
#include <QGuiApplication>
#include <QScreen>
#include <QAudioDevice>
#include <QMediaMetaData>
#ifdef Q_OS_ANDROID
#include <QJniObject>
#include <QJniEnvironment>
#endif

namespace {
struct ImportResult { QString path, name, error; };
ImportResult copyAudio(const QUrl &url) {
    if (!url.isLocalFile()) return {{}, {}, QStringLiteral("请拖入本地音频文件，不支持网页链接。")} ;
    QFile source(url.toLocalFile());
    if (!source.open(QIODevice::ReadOnly)) return {{}, {}, QStringLiteral("无法读取这个文件。")} ;
    QString name = QFileInfo(source).fileName();
    QString error = AudioFilePolicy::validate(name, source.size(), source.peek(16));
    if (!error.isEmpty()) return {{}, {}, error};
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation) + "/imports";
    if (!QDir().mkpath(dir)) return {{}, {}, QStringLiteral("无法创建音乐缓存目录。")} ;
    const QString path = dir + "/" + QUuid::createUuid().toString(QUuid::WithoutBraces) + "." + QFileInfo(name).suffix();
    QSaveFile target(path);
    if (!target.open(QIODevice::WriteOnly)) return {{}, {}, QStringLiteral("无法写入音乐缓存。")} ;
    qint64 count = 0;
    while (!source.atEnd()) {
        const QByteArray chunk = source.read(64 * 1024);
        if (chunk.isEmpty() && source.error() != QFileDevice::NoError) return {{}, {}, QStringLiteral("读取文件时发生错误。")} ;
        count += chunk.size();
        if (count > AudioFilePolicy::MaxBytes) return {{}, {}, QStringLiteral("读取时发现文件超过 30 MiB。")} ;
        if (target.write(chunk) != chunk.size()) return {{}, {}, QStringLiteral("写入失败，请检查磁盘空间。")} ;
    }
    if (count == 0 || !target.commit()) return {{}, {}, QStringLiteral("音乐导入失败。")} ;
    return {path, name, {}};
}
}

PlayerController::PlayerController(QObject *parent) : PlayerController(MusicApi::Endpoints{}, parent) {}
PlayerController::PlayerController(const MusicApi::Endpoints &endpoints, QObject *parent) : QObject(parent), m_api(endpoints) {
    m_favorites = QJsonDocument::fromJson(QSettings().value("library/favorites").toByteArray()).array().toVariantList();
    const QString savedQuality = QSettings().value("netease/quality", "standard").toString();
    if (MusicApi::validQuality(savedQuality)) m_quality = savedQuality;
    connect(&m_library, &PlaylistStore::changed, this, [this] {
        if (!m_library.error().isEmpty()) { m_error = m_library.error(); emit changed(); }
        emit libraryChanged(); syncQueue();
    });
    connect(&m_api, &MusicApi::results, this, [this](QVariantList tracks, QString error) {
        m_searching = false; m_searchResults = tracks;
        m_searchMessage = error.isEmpty() ? (tracks.isEmpty() ? QStringLiteral("没有找到歌曲。") : QStringLiteral("找到 %1 首，可直接点击播放。").arg(tracks.size())) : error;
        emit searchChanged();
    });
#ifndef Q_OS_ANDROID
    m_mediaTimeout.setSingleShot(true); m_mediaTimeout.setInterval(20000);
    connect(&m_mediaTimeout, &QTimer::timeout, this, [this] {
        m_autoplay = false; m_resolving = true; m_player.stop(); m_player.setSource({}); m_resolving = false;
        m_busy = false; m_ready = false; m_pendingPosition = -1;
        m_error = QStringLiteral("音频加载超时，请检查网络后重试，或选择其他音质。"); sync();
    });
    m_player.setAudioOutput(&m_output);
    m_volume = qBound(0, QSettings().value("audio/volume", 70).toInt(), 100);
    m_selectedOutput = QSettings().value("audio/output").toString();
    m_output.setVolume(m_volume / 100.f);
    connect(&m_devices, &QMediaDevices::audioOutputsChanged, this, &PlayerController::refreshOutputs);
    refreshOutputs();
    connect(&m_player, &QMediaPlayer::positionChanged, this, &PlayerController::sync);
    connect(&m_player, &QMediaPlayer::durationChanged, this, &PlayerController::sync);
    connect(&m_player, &QMediaPlayer::playbackStateChanged, this, &PlayerController::sync);
    connect(&m_player, &QMediaPlayer::seekableChanged, this, &PlayerController::sync);
    connect(&m_player, &QMediaPlayer::metaDataChanged, this, &PlayerController::sync);
    connect(&m_player, &QMediaPlayer::mediaStatusChanged, this, [this](QMediaPlayer::MediaStatus s) {
        // Events from the old song must not finish a new import or URL request.
        if (m_resolving || m_importing) { sync(); return; }
        if (s == QMediaPlayer::LoadedMedia || s == QMediaPlayer::BufferedMedia) {
            m_mediaTimeout.stop();
            m_busy = false;
            m_ready = m_player.hasAudio() && !m_player.hasVideo();
            if (!m_ready) { m_player.stop(); m_error = QStringLiteral("请选择纯音频文件，不支持视频。"); }
            if (m_ready && m_pendingPosition >= 0) {
                const auto resume = m_pendingPosition; m_pendingPosition = -1;
                if (m_player.isSeekable()) m_player.setPosition(qBound<qint64>(0, resume, qMax<qint64>(0, m_player.duration() - 1)));
            }
            if (m_ready && m_autoplay) { m_autoplay = false; m_player.play(); }
        }
        if (s == QMediaPlayer::InvalidMedia) { m_mediaTimeout.stop(); m_busy = false; m_ready = false; }
        sync();
        if (s == QMediaPlayer::EndOfMedia) QTimer::singleShot(0, this, [this] { if (m_player.mediaStatus() == QMediaPlayer::EndOfMedia) step(1, true); });
    });
    connect(&m_player, &QMediaPlayer::errorOccurred, this, [this](QMediaPlayer::Error code, const QString &message) {
        if (m_resolving || m_importing) return;
        m_mediaTimeout.stop(); m_autoplay = false; m_pendingPosition = -1;
        m_error = (code == QMediaPlayer::NetworkError ? QStringLiteral("音频连接失败：") : QStringLiteral("音频无法播放或解码："))
            + message + (m_online ? QStringLiteral("。请重试或更换音质。") : QString());
        m_busy = false; m_ready = false; sync();
    });
#else
    m_timer.setInterval(250);
    connect(&m_timer, &QTimer::timeout, this, &PlayerController::sync);
    m_timer.start();
    QTimer::singleShot(500, this, &PlayerController::syncQueue);
#endif
}
void PlayerController::setApiBase(const QString &url) {
    if (m_busy) { m_searchMessage = QStringLiteral("请等待当前加载完成后切换服务。"); emit searchChanged(); return; }
    if (!m_api.setBaseUrl(url)) m_searchMessage = QStringLiteral("请输入 http(s) 服务地址，不要包含账号、密码或查询参数。");
    else { m_searching = false; m_searchResults.clear(); m_searchMessage = url.trimmed().isEmpty() ? QStringLiteral("已使用内置网易云接口，可直接按歌名搜索。"): QStringLiteral("自定义服务地址已保存。"); syncQueue(); }
    emit searchChanged();
}
void PlayerController::search(const QString &keywords) {
    m_searching = !keywords.trimmed().isEmpty(); m_searchResults.clear(); m_searchMessage.clear(); emit searchChanged();
    m_api.search(keywords);
}
void PlayerController::playSearchResult(int index) {
    if (m_busy || index < 0 || index >= m_searchResults.size()) return;
    loadTrack(m_searchResults[index].toMap(), true);
}
void PlayerController::setQuality(const QString &quality) {
    if (m_busy || !MusicApi::validQuality(quality) || quality == m_quality) return;
    m_quality = quality; QSettings().setValue("netease/quality", quality); emit changed();
    if (m_online && !m_loadedTrack.isEmpty()) loadTrack(m_loadedTrack, m_playing, true);
}
QString PlayerController::qualityInfo() const {
#ifdef Q_OS_ANDROID
    return m_online ? m_androidQualityInfo : QStringLiteral("音质选项用于在线歌曲；本地文件保持原格式。");
#else
    const QString codec = m_player.metaData().stringValue(QMediaMetaData::AudioCodec);
    const int bitrate = m_player.metaData().value(QMediaMetaData::AudioBitRate).toInt();
    const QString actual = codec.isEmpty() ? QStringLiteral("等待音频信息") : codec + (bitrate > 0 ? QStringLiteral(" · %1 kbps").arg(bitrate / 1000) : QString());
    if (!m_online) return QStringLiteral("音质选项用于在线歌曲；本地文件保持原格式。");
    const QStringList keys{"standard", "higher", "exhigh", "lossless", "hires"};
    const QStringList labels{QStringLiteral("标准"), QStringLiteral("较高"), QStringLiteral("极高"), QStringLiteral("无损"), QStringLiteral("Hi-Res")};
    const int index = keys.indexOf(m_loadedQuality);
    return QStringLiteral("当前音源请求：%1 · 播放格式：%2").arg(index >= 0 ? labels[index] : QStringLiteral("未知"), actual);
#endif
}
void PlayerController::retryPlayback() {
    if (m_busy || m_requestedTrack.isEmpty()) return;
    loadTrack(m_requestedTrack, true, m_requestedTrack == m_loadedTrack && m_ready);
}
void PlayerController::retryLyrics() {
    if (!m_online || m_loadedTrack.isEmpty() || m_lyricsLoading) return;
    const int ticket = ++m_lyricsGeneration;
    m_lyricsLoading = true; m_lyricsFailed = false; m_lyricsMessage = QStringLiteral("正在获取歌词…"); emit lyricsChanged();
    m_api.fetchLyrics(m_loadedTrack.value("songId").toString(), [this, ticket](MusicApi::Lyrics lyrics, QString error) {
        if (ticket != m_lyricsGeneration) return;
        m_lyricsLoading = false; m_lyricsFailed = !error.isEmpty();
        if (error.isEmpty()) { m_lyrics = lyrics.original; m_translation = lyrics.translation; }
        m_lyricsMessage = !error.isEmpty() ? error : lyrics.instrumental ? QStringLiteral("纯音乐，暂无文字歌词。")
            : m_lyrics.isEmpty() ? QStringLiteral("该歌曲暂无歌词。") : m_translation.isEmpty() ? QStringLiteral("已获取原文，暂无译文。") : QStringLiteral("已获取原文和译文。");
        emit lyricsChanged();
    });
}
void PlayerController::addSearchResult(int index) {
    if (index < 0 || index >= m_searchResults.size()) return;
    if (!m_library.add(m_searchResults[index].toMap())) m_error = m_library.error();
    else m_searchMessage = QStringLiteral("已加入当前歌单（相同歌曲不会重复添加）。");
    emit searchChanged(); emit changed();
}
void PlayerController::createPlaylist(const QString &name) {
    if (!m_library.create(name)) { m_error = QStringLiteral("歌单名称需为 1–60 个字符，且磁盘可写。"); emit changed(); }
}
void PlayerController::renamePlaylist(const QString &name) {
    if (!m_library.rename(name)) { m_error = QStringLiteral("歌单名称需为 1–60 个字符，且磁盘可写。"); emit changed(); }
}
void PlayerController::deletePlaylist() {
    if (!m_library.removePlaylist()) { m_error = QStringLiteral("至少保留一个歌单。"); emit changed(); }
}
void PlayerController::selectPlaylist(const QString &id) { m_library.select(id); }
void PlayerController::removeTrack(const QString &id) { m_library.removeTrack(id); }
QVariantMap PlayerController::favoriteTrack(const QString &id) const {
    for (const auto &entry : m_favorites) if (entry.toMap().value("id").toString() == id) return entry.toMap();
    return {};
}
bool PlayerController::currentFavorite() const { return !favoriteTrack(m_loadedTrack.value("id").toString()).isEmpty(); }
void PlayerController::saveFavorites() {
    QSettings settings;
    settings.setValue("library/favorites", QJsonDocument(QJsonArray::fromVariantList(m_favorites)).toJson(QJsonDocument::Compact));
    settings.sync();
    if (settings.status() != QSettings::NoError) m_favoriteMessage = QStringLiteral("收藏保存失败，请检查存储空间。");
    emit favoritesChanged(); emit changed();
}
void PlayerController::toggleFavorite() {
    if (m_loadedTrack.isEmpty()) {
        m_favoriteMessage = QStringLiteral("先播放一首歌曲，再收藏。"); emit favoritesChanged(); return;
    }
    if (currentFavorite()) { removeFavorite(m_loadedTrack.value("id").toString()); return; }
    m_favorites.append(m_loadedTrack); m_favoriteMessage = QStringLiteral("已收藏到本机"); saveFavorites();
}
void PlayerController::removeFavorite(const QString &id) {
    for (qsizetype i = 0; i < m_favorites.size(); ++i) if (m_favorites[i].toMap().value("id").toString() == id) {
        m_favorites.removeAt(i); m_favoriteMessage = QStringLiteral("已取消收藏"); saveFavorites(); return;
    }
}
void PlayerController::playFavorite(const QString &id) {
    const auto track = favoriteTrack(id);
    if (!m_busy && !track.isEmpty()) loadTrack(track, true);
}
void PlayerController::addFavorite(const QString &id) {
    const auto track = favoriteTrack(id); if (track.isEmpty()) return;
    m_favoriteMessage = m_library.add(track) ? QStringLiteral("已加入当前歌单") : m_library.error();
    emit favoritesChanged();
}
QRect PlayerController::desktopWorkArea(int x, int y) const {
    auto *screen = QGuiApplication::screenAt(QPoint(x, y));
    if (!screen) screen = QGuiApplication::primaryScreen();
    return screen ? screen->availableGeometry() : QRect(0, 0, 1280, 720);
}
void PlayerController::syncQueue() {
#ifdef Q_OS_ANDROID
    androidCommand("queue", QString::fromUtf8(QJsonDocument(QJsonObject::fromVariantMap({{"tracks", tracks()}, {"api", apiBase()}, {"quality", m_quality}})).toJson(QJsonDocument::Compact)));
#endif
}
void PlayerController::playTrack(const QString &id) {
    if (m_busy) return;
    for (const auto &entry : tracks()) if (entry.toMap().value("id").toString() == id) { loadTrack(entry.toMap(), true); return; }
}
void PlayerController::previous() { step(-1); }
void PlayerController::next() { step(1); }
void PlayerController::step(int delta, bool automatic) {
    const auto songs = tracks(); if (songs.isEmpty() || m_busy) return;
    int index = -1;
    for (int i = 0; i < songs.size(); ++i) if (songs[i].toMap().value("id").toString() == m_currentTrack) index = i;
    // Automatic playback stops at the end of the list. Manual navigation wraps.
    if (automatic && (index < 0 || index + 1 >= songs.size())) return;
    const int target = index < 0 ? (delta > 0 ? 0 : songs.size() - 1) : (index + delta + songs.size()) % songs.size();
    loadTrack(songs[target].toMap(), true);
}
void PlayerController::loadTrack(const QVariantMap &track, bool autoplay, bool preservePosition) {
#ifdef Q_OS_ANDROID
    m_requestedTrack = track;
    syncQueue();
    androidCommand("play", QString::fromUtf8(QJsonDocument(QJsonObject::fromVariantMap({{"track", track}, {"autoplay", autoplay}, {"preserve", preservePosition}})).toJson(QJsonDocument::Compact)));
#else
    const int ticket = ++m_loadGeneration;
    m_requestedTrack = track;
    const QString quality = m_quality;
    auto load = [this, track, autoplay, preservePosition, quality, ticket](QUrl url, QString error) {
        if (ticket != m_loadGeneration) return;
        m_resolving = false;
        if (!error.isEmpty()) { m_busy = false; m_error = error; sync(); return; }
        const qint64 resume = preservePosition ? m_player.position() : -1;
        m_mediaTimeout.stop(); m_resolving = true;
        m_player.stop(); m_player.setSource({}); m_resolving = false;
        m_error.clear(); m_currentTrack = track.value("id").toString(); m_title = track.value("name").toString();
        m_online = track.value("source").toString() == "netease";
        m_loadedTrack = track; m_loadedQuality = quality;
        m_ready = false; m_busy = true; m_autoplay = autoplay;
        m_pendingPosition = resume;
        ++m_lyricsGeneration; m_lyricsLoading = false; m_lyricsFailed = false; m_lyrics.clear(); m_translation.clear();
        m_lyricsMessage = m_online ? QStringLiteral("正在获取歌词…") : QStringLiteral("本地音乐暂不自动匹配在线歌词。"); emit lyricsChanged();
        m_mediaTimeout.start(); m_player.setSource(url); sync();
        if (m_online) retryLyrics();
    };
    if (track.value("source").toString() == "netease") {
        m_busy = true; m_resolving = true; m_error.clear(); sync(); m_api.resolve(track.value("songId").toString(), quality, load);
    } else {
        const QString path = track.value("path").toString();
        load(QUrl::fromLocalFile(path), QFileInfo::exists(path) ? QString() : QStringLiteral("本地音频副本已丢失，请重新导入。"));
    }
#endif
}
void PlayerController::setVolume(int volume) {
    volume = qBound(0, volume, 100);
#ifdef Q_OS_ANDROID
    androidCommand("volume", QString::number(volume));
#else
    m_output.setVolume(volume / 100.f);
    QSettings().setValue("audio/volume", volume);
#endif
    m_volume = volume;
    emit audioSettingsChanged();
}
void PlayerController::selectOutput(const QString &id) {
#ifdef Q_OS_ANDROID
    androidCommand("output", id);
#else
    bool valid = id.isEmpty();
    for (const auto &device : QMediaDevices::audioOutputs())
        valid |= QString::fromLatin1(device.id().toHex()) == id;
    if (!valid) { m_error = QStringLiteral("该输出设备已断开，请重新选择。"); emit changed(); return; }
    m_selectedOutput = id;
    QSettings().setValue("audio/output", id);
    refreshOutputs();
#endif
}
void PlayerController::refreshOutputs() {
#ifdef Q_OS_ANDROID
    androidCommand("outputs");
#else
    QVariantList outputs{QVariantMap{{"id", ""}, {"name", QStringLiteral("跟随系统默认")}}};
    QAudioDevice target = QMediaDevices::defaultAudioOutput();
    bool found = m_selectedOutput.isEmpty();
    for (const auto &device : QMediaDevices::audioOutputs()) {
        const QString id = QString::fromLatin1(device.id().toHex());
        outputs.append(QVariantMap{{"id", id}, {"name", device.description()}});
        if (id == m_selectedOutput) { target = device; found = true; }
    }
    if (!found) { m_selectedOutput.clear(); QSettings().remove("audio/output"); }
    // QAudioOutput otherwise retains the endpoint chosen before Bluetooth was connected.
    if (m_output.device().id() != target.id()) {
        const bool resume = m_player.playbackState() == QMediaPlayer::PlayingState;
        if (resume) m_player.pause();
        m_output.setDevice(target);
        if (resume && !target.isNull()) m_player.play();
    }
    m_audioOutputs = outputs;
    m_outputName = target.isNull() ? QStringLiteral("没有可用的输出设备") : target.description();
    emit audioSettingsChanged();
#endif
}
PlayerController::~PlayerController() {
#ifndef Q_OS_ANDROID
    m_player.stop(); m_player.setSource({});
#endif
}
bool PlayerController::android() const {
#ifdef Q_OS_ANDROID
    return true;
#else
    return false;
#endif
}
void PlayerController::androidCommand(const QString &command, const QString &value) {
#ifdef Q_OS_ANDROID
    const auto c = QJniObject::fromString(command), v = QJniObject::fromString(value);
    QJniObject::callStaticMethod<void>("org/floatmusic/player/PlayerBridge", "command", "(Ljava/lang/String;Ljava/lang/String;)V", c.object<jstring>(), v.object<jstring>());
    QJniEnvironment env;
    if (env->ExceptionCheck()) { env->ExceptionClear(); m_error = QStringLiteral("Android 系统操作失败，请重新打开应用。"); emit changed(); }
#else
    Q_UNUSED(command); Q_UNUSED(value);
#endif
}
void PlayerController::importFile(const QUrl &url) {
#ifdef Q_OS_ANDROID
    androidCommand("import", url.toString());
#else
    if (m_busy) { m_error = QStringLiteral("正在导入，请稍候。"); emit changed(); return; }
    m_busy = true; m_importing = true; m_error.clear(); emit changed();
    auto *watcher = new QFutureWatcher<ImportResult>(this);
    connect(watcher, &QFutureWatcher<ImportResult>::finished, this, [this, watcher] {
        const auto result = watcher->result(); watcher->deleteLater();
        m_importing = false;
        if (!result.error.isEmpty()) { m_busy = false; m_error = result.error; emit changed(); return; }
        const QVariantMap track{{"id", "local:" + QFileInfo(result.path).baseName()}, {"source", "local"}, {"name", result.name}, {"artist", QStringLiteral("本地文件")}, {"path", result.path}};
        if (!m_library.add(track)) { m_busy = false; m_error = m_library.error(); emit changed(); return; }
        loadTrack(track, false);
    });
    watcher->setFuture(QtConcurrent::run(copyAudio, url));
#endif
}
void PlayerController::chooseAndroidFile() { androidCommand("pick"); }
void PlayerController::toggle() {
#ifdef Q_OS_ANDROID
    androidCommand("toggle");
#else
    if (!m_ready || m_busy) return;
    m_error.clear();
    if (m_player.playbackState() == QMediaPlayer::PlayingState) m_player.pause();
    else { if (m_player.mediaStatus() == QMediaPlayer::EndOfMedia) m_player.setPosition(0); m_player.play(); }
#endif
}
void PlayerController::seek(qint64 milliseconds) {
    if (!m_seekable) return;
#ifdef Q_OS_ANDROID
    androidCommand("seek", QString::number(qBound<qint64>(0, milliseconds, m_duration)));
#else
    m_player.setPosition(qBound<qint64>(0, milliseconds, m_duration));
#endif
}
void PlayerController::showFloating() { androidCommand("float"); }
void PlayerController::initializeAndroidUi() { androidCommand("ready"); }
int PlayerController::androidThemeMode() const {
#ifdef Q_OS_ANDROID
    return QJniObject::callStaticMethod<jint>("org/floatmusic/player/PlayerBridge", "themeMode", "()I");
#else
    return 0;
#endif
}
void PlayerController::setDarkTheme(bool dark) { androidCommand("theme", dark ? "dark" : "light"); }
void PlayerController::quit() { ++m_loadGeneration; ++m_lyricsGeneration; m_mediaTimeout.stop(); androidCommand("stop"); m_player.stop(); QCoreApplication::quit(); }
void PlayerController::rejectDrop() { m_error = QStringLiteral("请一次拖入一首本地音频。"); emit changed(); }
void PlayerController::sync() {
#ifdef Q_OS_ANDROID
    const auto state = QJniObject::callStaticObjectMethod("org/floatmusic/player/PlayerBridge", "snapshot", "()Ljava/lang/String;");
    const QJsonObject o = QJsonDocument::fromJson(state.toString().toUtf8()).object();
    if (o.isEmpty()) return;
    const auto imported = o.value("importedTrack").toObject().toVariantMap();
    if (!imported.isEmpty() && m_library.add(imported)) androidCommand("ackImport");
    m_currentTrack = o.value("currentTrack").toString();
    const auto loaded = o.value("loadedTrack").toObject().toVariantMap();
    m_loadedQuality = o.value("loadedQuality").toString();
    m_androidQualityInfo = o.value("qualityInfo").toString();
    if (loaded != m_loadedTrack && !loaded.isEmpty()) {
        m_loadedTrack = loaded;
        m_requestedTrack = loaded;
        m_online = loaded.value("source").toString() == "netease";
        ++m_lyricsGeneration; m_lyricsLoading = false; m_lyricsFailed = false;
        m_lyrics.clear(); m_translation.clear();
        m_lyricsMessage = m_online ? QStringLiteral("正在获取歌词…") : QStringLiteral("本地音乐暂不自动匹配在线歌词。");
        emit lyricsChanged();
        if (m_online) retryLyrics();
    }
    const auto outputs = o.value("outputs").toArray().toVariantList();
    const int volume = o.value("volume").toInt(70);
    const QString selected = o.value("selectedOutput").toString(), outputName = o.value("outputName").toString();
    if (outputs != m_audioOutputs || volume != m_volume || selected != m_selectedOutput || outputName != m_outputName) {
        m_audioOutputs = outputs; m_volume = volume; m_selectedOutput = selected; m_outputName = outputName;
        emit audioSettingsChanged();
    }
    m_title = o.value("title").toString(); m_status = o.value("status").toString(); m_error = o.value("error").toString();
    m_position = o.value("position").toInteger(); m_duration = o.value("duration").toInteger();
    m_playing = o.value("playing").toBool(); m_ready = o.value("ready").toBool(); m_busy = o.value("busy").toBool();
    m_seekable = m_ready && m_duration > 0; m_overlayAllowed = o.value("overlayAllowed").toBool();
    processOverlayEvents();
    publishOverlayUi();
#else
    m_position = m_player.position(); m_duration = m_player.duration();
    m_playing = m_player.playbackState() == QMediaPlayer::PlayingState;
    m_seekable = m_player.isSeekable() && m_ready;
    m_status = m_busy ? QStringLiteral("正在导入 / 加载音频…") : m_playing ? (m_online ? QStringLiteral("正在播放 · 网易云") : QStringLiteral("正在播放 · 本地音频")) : m_ready ? QStringLiteral("已就绪 · 点击播放") : QStringLiteral("导入音乐，或从歌单选择歌曲");
#endif
    emit changed();
}

#ifdef Q_OS_ANDROID
void PlayerController::processOverlayEvents() {
    const auto raw = QJniObject::callStaticObjectMethod("org/floatmusic/player/PlayerBridge", "takeEvents", "()Ljava/lang/String;");
    const auto events = QJsonDocument::fromJson(raw.toString().toUtf8()).array();
    auto findTrack = [](const QVariantList &list, const QString &id) {
        for (const auto &item : list) if (item.toMap().value("id").toString() == id) return item.toMap();
        return QVariantMap{};
    };
    for (const auto &entry : events) {
        const auto event = entry.toObject();
        const auto action = event.value("action").toString(), value = event.value("value").toString();
        m_overlayMessage.clear();
        if (action == "search") search(value);
        else if (action == "playResult" || action == "addResult") {
            const auto track = findTrack(m_searchResults, value);
            if (track.isEmpty()) continue;
            if (action == "playResult") { if(!m_busy)loadTrack(track,true); }
            else m_overlayMessage = m_library.add(track) ? QStringLiteral("已加入当前歌单") : m_library.error();
        } else if (action == "playTrack") playTrack(value);
        else if (action == "selectPlaylist") selectPlaylist(value);
        else if (action == "createPlaylist" || action == "renamePlaylist") {
            const bool ok = action == "createPlaylist" ? m_library.create(value) : m_library.rename(value);
            m_overlayMessage = ok ? QStringLiteral("歌单已保存") : QStringLiteral("保存失败：歌单名称需为 1–60 个字符，并确保存储可写。");
        } else if (action == "deletePlaylist") {
            m_overlayMessage = m_library.removePlaylist() ? QStringLiteral("歌单已删除") : QStringLiteral("至少保留一个歌单。");
        } else if (action == "removeTrack") removeTrack(value);
        else if (action == "quality") { setQuality(value); syncQueue(); }
        else if (action == "api") { setApiBase(value); m_overlayMessage=m_searchMessage; }
        else if (action == "retryLyrics") retryLyrics();
        else if (action == "retryPlayback") retryPlayback();
        else if (action == "quit") { quit(); return; }
        else if (action == "favoriteCurrent") {
            toggleFavorite(); m_overlayMessage=m_favoriteMessage;
        } else if (action == "playFavorite" || action == "addFavorite" || action == "removeFavorite") {
            if(action=="playFavorite")playFavorite(value);
            else if(action=="addFavorite"){addFavorite(value);m_overlayMessage=m_favoriteMessage;}
            else {removeFavorite(value);m_overlayMessage=m_favoriteMessage;}
        }
    }
}
void PlayerController::publishOverlayUi() {
    const QVariantMap data{{"results",m_searchResults},{"searching",m_searching},{"searchMessage",m_searchMessage},
        {"playlists",playlists()},{"tracks",tracks()},{"activePlaylist",activePlaylist()},
        {"lyrics",m_lyrics},{"translation",m_translation},{"lyricsMessage",m_lyricsMessage},
        {"lyricsLoading",m_lyricsLoading},{"lyricsFailed",m_lyricsFailed},{"online",m_online},
        {"quality",m_quality},{"qualityInfo",qualityInfo()},{"api",apiBase()},
        {"favorites",m_favorites},{"message",m_overlayMessage}};
    const auto json=QJsonDocument(QJsonObject::fromVariantMap(data)).toJson(QJsonDocument::Compact);
    if(json==m_overlayUi)return;
    m_overlayUi=json;
    const auto value=QJniObject::fromString(QString::fromUtf8(json));
    QJniObject::callStaticMethod<void>("org/floatmusic/player/PlayerBridge","updateUi","(Ljava/lang/String;)V",value.object<jstring>());
}
#endif
