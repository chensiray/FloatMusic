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
#include <QAudioDevice>
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

PlayerController::PlayerController(QObject *parent) : QObject(parent) {
    connect(&m_library, &PlaylistStore::changed, this, [this] {
        if (!m_library.error().isEmpty()) { m_error = m_library.error(); emit changed(); }
        emit libraryChanged(); syncQueue();
    });
    connect(&m_api, &MusicApi::results, this, [this](QVariantList tracks, QString error) {
        m_searching = false; m_searchResults = tracks;
        m_searchMessage = error.isEmpty() ? (tracks.isEmpty() ? QStringLiteral("没有找到歌曲。") : QStringLiteral("找到 %1 首，点击 + 加入当前歌单。").arg(tracks.size())) : error;
        emit searchChanged();
    });
#ifndef Q_OS_ANDROID
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
    connect(&m_player, &QMediaPlayer::mediaStatusChanged, this, [this](QMediaPlayer::MediaStatus s) {
        // Events from the old song must not finish a new import or URL request.
        if (m_resolving || m_importing) { sync(); return; }
        if (s == QMediaPlayer::LoadedMedia || s == QMediaPlayer::BufferedMedia) {
            m_busy = false;
            m_ready = m_player.hasAudio() && !m_player.hasVideo();
            if (!m_ready) { m_player.stop(); m_error = QStringLiteral("请选择纯音频文件，不支持视频。"); }
            if (m_ready && m_autoplay) { m_autoplay = false; m_player.play(); }
        }
        if (s == QMediaPlayer::InvalidMedia) { m_busy = false; m_ready = false; }
        sync();
        if (s == QMediaPlayer::EndOfMedia) QTimer::singleShot(0, this, [this] { if (m_player.mediaStatus() == QMediaPlayer::EndOfMedia) step(1, true); });
    });
    connect(&m_player, &QMediaPlayer::errorOccurred, this, [this](QMediaPlayer::Error, const QString &message) {
        if (m_resolving || m_importing) return;
        m_error = QStringLiteral("无法解码此音频：") + message; m_busy = false; m_ready = false; sync();
    });
#else
    m_timer.setInterval(250);
    connect(&m_timer, &QTimer::timeout, this, &PlayerController::sync);
    m_timer.start();
    QTimer::singleShot(500, this, &PlayerController::syncQueue);
#endif
}
void PlayerController::setApiBase(const QString &url) {
    if (!m_api.setBaseUrl(url)) m_searchMessage = QStringLiteral("请输入 http(s) 服务地址，不要包含账号、密码或查询参数。");
    else { m_searching = false; m_searchResults.clear(); m_searchMessage = QStringLiteral("服务地址已保存。"); syncQueue(); }
    emit searchChanged();
}
void PlayerController::search(const QString &keywords) {
    m_searching = !keywords.trimmed().isEmpty(); m_searchResults.clear(); m_searchMessage.clear(); emit searchChanged();
    m_api.search(keywords);
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
void PlayerController::syncQueue() {
#ifdef Q_OS_ANDROID
    androidCommand("queue", QString::fromUtf8(QJsonDocument(QJsonObject::fromVariantMap({{"tracks", tracks()}, {"api", apiBase()}})).toJson(QJsonDocument::Compact)));
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
void PlayerController::loadTrack(const QVariantMap &track, bool autoplay) {
#ifdef Q_OS_ANDROID
    Q_UNUSED(autoplay);
    syncQueue(); androidCommand("track", track.value("id").toString());
#else
    const int ticket = ++m_loadGeneration;
    auto load = [this, track, autoplay, ticket](QUrl url, QString error) {
        if (ticket != m_loadGeneration) return;
        m_resolving = false;
        if (!error.isEmpty()) { m_busy = false; m_error = error; emit changed(); return; }
        m_player.stop(); m_player.setSource({});
        m_error.clear(); m_currentTrack = track.value("id").toString(); m_title = track.value("name").toString();
        m_online = track.value("source").toString() == "netease";
        m_ready = false; m_busy = true; m_autoplay = autoplay;
        m_player.setSource(url); sync();
    };
    if (track.value("source").toString() == "netease") {
        m_busy = true; m_resolving = true; m_error.clear(); emit changed(); m_api.resolve(track.value("songId").toString(), load);
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
    if (!m_ready) return;
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
void PlayerController::quit() { ++m_loadGeneration; androidCommand("stop"); m_player.stop(); QCoreApplication::quit(); }
void PlayerController::rejectDrop() { m_error = QStringLiteral("请一次拖入一首本地音频。"); emit changed(); }
void PlayerController::sync() {
#ifdef Q_OS_ANDROID
    const auto state = QJniObject::callStaticObjectMethod("org/floatmusic/player/PlayerBridge", "snapshot", "()Ljava/lang/String;");
    const QJsonObject o = QJsonDocument::fromJson(state.toString().toUtf8()).object();
    if (o.isEmpty()) return;
    const auto imported = o.value("importedTrack").toObject().toVariantMap();
    if (!imported.isEmpty() && m_library.add(imported)) androidCommand("ackImport");
    m_currentTrack = o.value("currentTrack").toString();
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
#else
    m_position = m_player.position(); m_duration = m_player.duration();
    m_playing = m_player.playbackState() == QMediaPlayer::PlayingState;
    m_seekable = m_player.isSeekable() && m_ready;
    m_status = m_busy ? QStringLiteral("正在导入 / 加载音频…") : m_playing ? (m_online ? QStringLiteral("正在播放 · 网易云") : QStringLiteral("正在播放 · 本地音频")) : m_ready ? QStringLiteral("已就绪 · 点击播放") : QStringLiteral("导入音乐，或从歌单选择歌曲");
#endif
    emit changed();
}
