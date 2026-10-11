#include "sleeptimer.h"
SleepTimer::SleepTimer(QObject *parent) : SleepTimer({}, parent) {}
SleepTimer::SleepTimer(std::function<qint64()> clock, QObject *parent) : QObject(parent), m_clock(std::move(clock)) {
    m_elapsed.start();
    if (!m_clock) m_clock = [this] { return m_elapsed.elapsed(); };
    m_timer.setInterval(1000);
    connect(&m_timer, &QTimer::timeout, this, &SleepTimer::poll);
}
QString SleepTimer::mode() const { return m_mode; }
int SleepTimer::remainingSeconds() const { return m_remaining; }
QString SleepTimer::text() const {
    if (m_mode == "off") return QStringLiteral("未开启");
    if (m_mode == "endOfTrack") return QStringLiteral("播完本首后暂停");
    return QStringLiteral("%1:%2 后暂停").arg(m_remaining / 60).arg(m_remaining % 60, 2, 10, QChar('0'));
}
bool SleepTimer::start(int minutes) {
    if (minutes < 1 || minutes > 180) return false;
    m_mode = "countdown";
    m_remaining = minutes * 60;
    m_deadline = m_clock() + qint64(m_remaining) * 1000;
    m_timer.start(); emit changed();
    return true;
}
void SleepTimer::afterCurrentTrack() {
    m_timer.stop(); m_mode = "endOfTrack"; m_remaining = 0; emit changed();
}
void SleepTimer::cancel() {
    m_timer.stop();
    if (m_mode == "off") return;
    m_mode = "off"; m_remaining = 0; m_deadline = 0; emit changed();
}
bool SleepTimer::consumeTrackEnd() {
    if (m_mode != "endOfTrack") return false;
    cancel(); emit expired(); return true;
}
void SleepTimer::poll() {
    if (m_mode != "countdown") return;
    const qint64 left = m_deadline - m_clock();
    if (left <= 0) { cancel(); emit expired(); return; }
    const int seconds = int((left + 999) / 1000);
    if (m_remaining != seconds) { m_remaining = seconds; emit changed(); }
}
