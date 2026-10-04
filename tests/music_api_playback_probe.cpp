// Independent, silent Qt Multimedia diagnostic. URLs arrive via stdin,
// never via command-line arguments or diagnostic output.
#include <QCoreApplication>
#include <QAudioBuffer>
#include <QAudioBufferOutput>
#include <QAudioOutput>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMediaMetaData>
#include <QMediaPlayer>
#include <QNetworkProxyFactory>
#include <QTimer>
#include <QUrl>
#include <iostream>
#include <string>

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    qInstallMessageHandler([](QtMsgType, const QMessageLogContext &, const QString &) {});
    QNetworkProxyFactory::setUseSystemConfiguration(true);
    std::string line;
    std::getline(std::cin, line);
    const auto input = QJsonDocument::fromJson(QByteArray::fromStdString(line)).object();
    const QUrl source(input.value("url").toString());
    QJsonObject result{{"decoded", false}, {"paused", false}, {"resumed", false},
                       {"seek_ok", false}, {"decoded_frames", 0}};
    if (!source.isValid() || source.isEmpty()) {
        result["outcome"] = "invalid_address";
        std::cout << QJsonDocument(result).toJson(QJsonDocument::Compact).toStdString() << std::endl;
        return 2;
    }
    QMediaPlayer player;
    QAudioOutput speaker;
    speaker.setMuted(true);
    QAudioBufferOutput buffers;
    player.setAudioOutput(&speaker);
    player.setAudioBufferOutput(&buffers);
    bool finished = false;
    int phase = 0;
    qint64 frames = 0, afterSeekFrames = 0, seekTarget = 0;
    const auto finish = [&](const QString &outcome) {
        if (finished) return;
        finished = true;
        result["outcome"] = outcome;
        result["duration_ms"] = player.duration();
        result["seekable"] = player.isSeekable();
        result["source_codec"] = player.metaData().value(QMediaMetaData::AudioCodec).toString();
        result["decoded_frames"] = frames;
        result["decoded_after_seek_frames"] = afterSeekFrames;
        result["seek_target_ms"] = seekTarget;
        result["position_after_seek_ms"] = player.position();
        player.stop();
        std::cout << QJsonDocument(result).toJson(QJsonDocument::Compact).toStdString() << std::endl;
        app.quit();
    };
    QObject::connect(&player, &QMediaPlayer::errorOccurred, &app, [&](QMediaPlayer::Error error, const QString &) {
        result["error_code"] = static_cast<int>(error);
        finish(result.value("decoded").toBool() ? "error_after_decode" : "decode_error");
    });
    QObject::connect(&buffers, &QAudioBufferOutput::audioBufferReceived, &app, [&](const QAudioBuffer &buffer) {
        if (finished || !buffer.isValid() || buffer.frameCount() <= 0) return;
        frames += buffer.frameCount();
        result["decoded"] = true;
        result["sample_rate"] = buffer.format().sampleRate();
        result["channels"] = buffer.format().channelCount();
        if (phase == 3) afterSeekFrames += buffer.frameCount();
        if (phase != 0) return;
        phase = 1;
        QTimer::singleShot(250, &app, [&] {
            if (finished) return;
            player.pause();
            result["paused"] = player.playbackState() == QMediaPlayer::PausedState;
            phase = 2;
            QTimer::singleShot(150, &app, [&] {
                if (finished) return;
                seekTarget = player.isSeekable() && player.duration() > 1000
                    ? qMin<qint64>(5000, player.duration() / 2) : 0;
                if (seekTarget) player.setPosition(seekTarget);
                phase = 3;
                player.play();
                result["resumed"] = player.playbackState() == QMediaPlayer::PlayingState;
            });
        });
    });
    QTimer progress;
    progress.setInterval(50);
    QObject::connect(&progress, &QTimer::timeout, &app, [&] {
        if (finished || phase != 3 || afterSeekFrames <= 0 || player.position() < seekTarget + 200) return;
        result["seek_ok"] = seekTarget > 0;
        const bool controls = result.value("paused").toBool() && result.value("resumed").toBool()
            && result.value("seek_ok").toBool();
        finish(controls ? "decoded_controls" : "decoded_only");
    });
    progress.start();
    QTimer::singleShot(qBound(1000, input.value("timeout_ms").toInt(10000), 15000), &app, [&] {
        finish(result.value("decoded").toBool() ? "decoded_controls_incomplete" : "decode_timeout");
    });
    player.setSource(source);
    player.play();
    return app.exec();
}
