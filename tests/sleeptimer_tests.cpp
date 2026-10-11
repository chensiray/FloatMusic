#include <QtTest>
#include "sleeptimer.h"

class SleepTimerTests : public QObject {
    Q_OBJECT
private slots:
    void deadlineExpiryIsOnceAndIgnoresLatePolling() {
        qint64 now = 1000;
        SleepTimer timer([&now] { return now; });
        QSignalSpy expiry(&timer, &SleepTimer::expired);
        QVERIFY(timer.start(1));
        QCOMPARE(timer.mode(), QString("countdown"));
        QCOMPARE(timer.remainingSeconds(), 60);
        now += 59001; timer.poll();
        QCOMPARE(timer.remainingSeconds(), 1);
        QCOMPARE(expiry.size(), 0);
        now += 20000; timer.poll();
        QCOMPARE(expiry.size(), 1);
        QCOMPARE(timer.mode(), QString("off"));
        QCOMPARE(timer.remainingSeconds(), 0);
        timer.poll();
        QCOMPARE(expiry.size(), 1);
    }
    void cancellationReplacementAndInvalidInput() {
        qint64 now = 0;
        SleepTimer timer([&now] { return now; });
        QSignalSpy expiry(&timer, &SleepTimer::expired);
        QVERIFY(timer.start(180));
        QVERIFY(!timer.start(181));
        QVERIFY(!timer.start(0));
        QCOMPARE(timer.remainingSeconds(), 10800);
        QVERIFY(timer.start(15));
        QCOMPARE(timer.remainingSeconds(), 900);
        timer.cancel(); now = 10000000; timer.poll();
        QCOMPARE(expiry.size(), 0);
        QCOMPARE(timer.mode(), QString("off"));
        QVERIFY(!timer.consumeTrackEnd());
    }
    void endOfTrackPreemptsCountdownAndExpiresOnce() {
        qint64 now = 0;
        SleepTimer timer([&now] { return now; });
        QSignalSpy expiry(&timer, &SleepTimer::expired);
        QVERIFY(timer.start(30));
        timer.afterCurrentTrack();
        now = 1800001; timer.poll();
        QCOMPARE(expiry.size(), 0);
        QCOMPARE(timer.mode(), QString("endOfTrack"));
        QVERIFY(timer.consumeTrackEnd());
        QCOMPARE(expiry.size(), 1);
        QCOMPARE(timer.mode(), QString("off"));
        QVERIFY(!timer.consumeTrackEnd());
    }
};
QTEST_GUILESS_MAIN(SleepTimerTests)
#include "sleeptimer_tests.moc"
