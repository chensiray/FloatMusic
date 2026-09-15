#include <QtTest>
#include "audiofilepolicy.h"
class PolicyTests : public QObject {
    Q_OBJECT
private slots:
    void bounds() {
        QVERIFY(AudioFilePolicy::validate("song.MP3", AudioFilePolicy::MaxBytes, "ID3").isEmpty());
        QVERIFY(!AudioFilePolicy::validate("song.mp3", AudioFilePolicy::MaxBytes + 1, "ID3").isEmpty());
        QVERIFY(!AudioFilePolicy::validate("song.mp3", 0, "ID3").isEmpty());
    }
    void disguisedFiles() {
        QVERIFY(!AudioFilePolicy::validate("evil.exe", 128, "ID3").isEmpty());
        QVERIFY(!AudioFilePolicy::validate("renamed.mp3", 128, "not audio").isEmpty());
        QVERIFY(!AudioFilePolicy::validate("fake.wav", 128, "RIFF1234AVI ").isEmpty());
        QVERIFY(AudioFilePolicy::validate("record.wav", 128, "RIFF1234WAVE").isEmpty());
        QVERIFY(AudioFilePolicy::validate("song.flac", 128, "fLaC").isEmpty());
        QVERIFY(AudioFilePolicy::validate("song.ogg", 128, "OggS").isEmpty());
        QVERIFY(AudioFilePolicy::validate("song.m4a", 128, QByteArray::fromHex("0000001866747970")).isEmpty());
    }
};
QTEST_APPLESS_MAIN(PolicyTests)
#include "policy_tests.moc"
