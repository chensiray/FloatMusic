#pragma once
#include <QObject>
#include <QTimer>
#include <QElapsedTimer>
#include <functional>

class SleepTimer : public QObject {
    Q_OBJECT
public:
    explicit SleepTimer(QObject *parent = nullptr);
    explicit SleepTimer(std::function<qint64()> clock, QObject *parent = nullptr);
    QString mode() const;
    int remainingSeconds() const;
    QString text() const;
    bool start(int minutes);
    void afterCurrentTrack();
    void cancel();
    bool consumeTrackEnd();
public slots:
    void poll();
signals:
    void changed();
    void expired();
private:
    QElapsedTimer m_elapsed;
    std::function<qint64()> m_clock;
    QTimer m_timer;
    QString m_mode = "off";
    qint64 m_deadline = 0;
    int m_remaining = 0;
};
