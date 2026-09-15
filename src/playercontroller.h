#pragma once
#include <QObject>
#include <QUrl>
#include <QMediaPlayer>
#include <QAudioOutput>
#include <QTimer>
#include <QMediaDevices>
#include <QVariantList>

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
public:
    explicit PlayerController(QObject *parent = nullptr);
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
    Q_INVOKABLE void setVolume(int volume);
    Q_INVOKABLE void selectOutput(const QString &id);
    Q_INVOKABLE void refreshOutputs();
    Q_INVOKABLE void importFile(const QUrl &url);
    Q_INVOKABLE void chooseAndroidFile();
    Q_INVOKABLE void toggle();
    Q_INVOKABLE void seek(qint64 milliseconds);
    Q_INVOKABLE void showFloating();
    Q_INVOKABLE void quit();
    Q_INVOKABLE void rejectDrop();
signals:
    void changed();
    void audioSettingsChanged();
private:
    void sync();
    void androidCommand(const QString &command, const QString &value = {});
    QMediaPlayer m_player;
    QAudioOutput m_output;
    QMediaDevices m_devices;
    int m_volume = 70;
    QVariantList m_audioOutputs;
    QString m_selectedOutput, m_outputName;
    QTimer m_timer;
    QString m_title = QStringLiteral("还没有导入音乐");
    QString m_status = QStringLiteral("选择一首本地音频，开始试听");
    QString m_error;
    QString m_cachedFile;
    qint64 m_position = 0, m_duration = 0;
    bool m_playing = false, m_ready = false, m_busy = false, m_seekable = false, m_overlayAllowed = false;
};
