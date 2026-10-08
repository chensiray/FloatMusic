#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QPointer>
#include <QSettings>
#include <QStandardPaths>
#include <QProcess>
#include <QtEndian>
#include "musicapi.h"

namespace {
QByteArray mp3Sample(bool high = true) {
    // MPEG-1 Layer III, 44.1 kHz stereo: 320 or 128 kbps, three full frames.
    const int frameSize = high ? 1044 : 417;
    QByteArray frame(frameSize, '\0');
    frame.replace(0, 4, QByteArray::fromHex(high ? "fffbe000" : "fffb9000"));
    return frame + frame + frame;
}
QJsonObject qqLink(const QString &url, const QString &mid = "004MpJjW07rAPl") {
    return {{"code", 0}, {"data", QJsonObject{{"songMID", mid}, {"songID", 42},
        {"kbps", "319kbps"}, {"url", url}}}};
}
QByteArray flacSample(int rate = 44100, int bits = 24, int seconds = 226) {
    QByteArray info(34, '\0');
    qToBigEndian<quint16>(4096, info.data()); qToBigEndian<quint16>(4096, info.data() + 2);
    const quint64 packed = (quint64(rate) << 44) | (quint64(1) << 41)
        | (quint64(bits - 1) << 36) | quint64(rate) * seconds;
    qToBigEndian<quint64>(packed, info.data() + 10);
    return QByteArray::fromHex("664c614380000022") + info;
}
QByteArray vorbisSample() {
    QByteArray packet = QByteArray::fromHex("01766f72626973000000000244ac00000000000000ee020000000000b801");
    QByteArray page(27, '\0'); page.replace(0, 4, "OggS"); page[26] = 1;
    return page + char(packet.size()) + packet;
}
QByteArray aacSample() {
    const QByteArray esds = QByteArray::fromHex("0000001b6573647300000000040d40150000000002ee000002ee00");
    QByteArray entry(36, '\0'); qToBigEndian<quint32>(36 + esds.size(), entry.data());
    entry.replace(4, 4, "mp4a"); qToBigEndian<quint16>(1, entry.data() + 14);
    qToBigEndian<quint16>(2, entry.data() + 24); qToBigEndian<quint16>(16, entry.data() + 26);
    qToBigEndian<quint32>(quint32(44100) << 16, entry.data() + 32);
    return QByteArray::fromHex("00000018667479704d344120000000004d34412069736f6d") + entry + esds;
}
QJsonObject kuwoLink(const QString &url, const QString &rid = "42", int type = 0,
                    int duration = 226, const QString &format = "mp3", int bitrate = 320) {
    return {{"code", 200}, {"msg", "ok"}, {"data", QJsonObject{{"rid", rid}, {"type", type},
        {"duration", duration}, {"format", format}, {"bitrate", bitrate}, {"url", url}}}};
}
QByteArray unprobedContainerSample(bool id3) {
    if (id3) return QByteArray::fromHex("49443304000000020000") + QByteArray(32768, '\0') + mp3Sample();
    const auto aac = aacSample();
    return aac.first(24) + QByteArray::fromHex("000080086d646174") + QByteArray(32768, '\0') + aac.mid(24);
}
}

class MultiSourceFixture : public QTcpServer {
public:
    QList<QUrl> requests;
    QList<QByteArray> requestHeaders;
    int qqSearchStatus = 200, qqAudioStatus = 200, kuwoMobiStatus = 200;
    bool denyQqAudio = false, emptyQqLyrics = false, denyKuwo = false;
    QHash<QString, QJsonObject> qqLinks;
    QHash<QString, QJsonObject> kuwoLinks;
    QHash<QString, QByteArray> audioBodies;
    QHash<QString, int> audioStatuses;
    QHash<QString, qint64> audioSizes;
    int audioDelay = 0;
    MultiSourceFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const QUrl url(QString::fromUtf8(request.split(' ').value(1)));
                    const QUrlQuery query(url); requests.append(url); requestHeaders.append(request.toLower());
                    QByteArray body; int status = 200, delay = 0; qint64 fullSize = 0;
                    const QString keyword = query.queryItemValue(query.hasQueryItem("keyword") ? "keyword" : query.hasQueryItem("s") ? "s" : "keywords", QUrl::FullyDecoded);
                    if (keyword == "slow") delay = 250;
                    if (url.path() == "/music/tencent/search/song") {
                        status = qqSearchStatus;
                        body = QJsonDocument(QJsonObject{{"code", 0}, {"data", QJsonObject{{"list", QJsonArray{
                            QJsonObject{{"songMID", "004MpJjW07rAPl"}, {"title", keyword}, {"singer", "QQ singer"}, {"interval", 254}},
                            QJsonObject{{"songMID", "004MpJjW07rAPl"}, {"title", "duplicate"}},
                            QJsonObject{{"songMID", "../../bad"}, {"title", "invalid"}}
                        }}}}}).toJson();
                    } else if (url.path() == "/music/tencent/song/link") {
                        status = qqAudioStatus;
                        const QString quality = query.queryItemValue("quality");
                        if (!qqLinks.isEmpty()) body = QJsonDocument(qqLinks.value(quality,
                            QJsonObject{{"code", 0}, {"data", QJsonObject{}}})).toJson();
                        else body = QJsonDocument(QJsonObject{{"code", denyQqAudio ? 500 : 0}, {"data", QJsonObject{{"songMID", query.queryItemValue("mid")}, {"url", base() + "/audio?token=keep%2Bthis"}, {"kbps", "192kbps"}}}}).toJson();
                    } else if (url.path() == "/v2/music/tencent/lyric") {
                        body = QJsonDocument(QJsonObject{{"code", 200}, {"data", QJsonObject{
                            {"lrc", ""}, {"trans", emptyQqLyrics ? "" : "[00:01.00]translation"},
                            {"yrc", emptyQqLyrics ? "" : "[1000,900](1000,300,0)Hello (1300,600,0)world\n[2000,500](2000,500,0)Next"}
                        }}}).toJson();
                    } else if (url.path() == "/mobi.s") {
                        status = kuwoMobiStatus;
                        body = QJsonDocument(kuwoLinks.value(query.queryItemValue("br"),
                            QJsonObject{{"code", 404}, {"msg", "quality unavailable"}})).toJson();
                    } else if (url.path() == "/ourcraft") {
                        const QString type = query.queryItemValue("type");
                        if (type == "search") body = QJsonDocument(QJsonObject{{"ok", true}, {"songs", QJsonArray{
                            QJsonObject{{"id", "42"}, {"name", query.queryItemValue("id", QUrl::FullyDecoded)}, {"singer", "Kuwo singer"}, {"duration", "11"}}
                        }}}).toJson();
                        else if (type == "url") body = QJsonDocument(QJsonObject{{"ok", !denyKuwo}, {"url", base() + "/proxy?url=" + QString::fromLatin1(QUrl::toPercentEncoding(base() + "/audio?token=origin%2Bvalue"))}}).toJson();
                        else if (type == "lrc") body = "[00:00.00]Kuwo original\n[00:01.50]Second line";
                    } else if (url.path() == "/meting") {
                        if (query.queryItemValue("type") == "lrc") body = "[00:00.00]Meting original";
                        else body = QJsonDocument(QJsonArray{QJsonObject{{"url", base() + "/audio?from=meting"}, {"lrc", base() + "/meting?type=lrc"}}}).toJson();
                    } else if (audioBodies.contains(url.path())) {
                        body = audioBodies.value(url.path()); status = audioStatuses.value(url.path(), 200);
                        fullSize = audioSizes.value(url.path()); delay = audioDelay;
                    } else if (url.path() == "/audio" || url.path() == "/proxy") {
                        body = "RIFF0000WAVEfmt 0000000000000000000000000000000000000000";
                    } else {
                        body = QJsonDocument(QJsonObject{{"code", 200}, {"result", QJsonObject{{"songs", QJsonArray{
                            QJsonObject{{"id", 42}, {"name", keyword}, {"ar", QJsonArray{QJsonObject{{"name", "Netease singer"}}}}}
                        }}}}}).toJson();
                    }
                    QPointer<QTcpSocket> safe(socket);
                    auto send = [safe, body, status, fullSize] {
                        if (!safe) return;
                        const QByteArray range = fullSize > 0 ? "Content-Range: bytes 0-" + QByteArray::number(body.size() - 1)
                            + '/' + QByteArray::number(fullSize) + "\r\n" : QByteArray();
                        safe->write("HTTP/1.1 " + QByteArray::number(status) + " Fixture\r\nContent-Type: application/json\r\nSet-Cookie: anonymous-session=fixture; Path=/\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\n" + range + "Connection: close\r\n\r\n" + body);
                        safe->disconnectFromHost();
                    };
                    if (delay) QTimer::singleShot(delay, socket, send); else send();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
    MusicApi::Endpoints endpoints() const {
        MusicApi::Endpoints e{base(), base() + "/legacy", 1000};
        e.vkeys = base(); e.ourcraft = base() + "/ourcraft"; e.injahow = base() + "/meting";
        return e;
    }
};

class MultiSourceApiTests : public QObject {
    Q_OBJECT
    void checkLivePlatform(const QString &source) {
        MusicApi api; QVERIFY(api.setBaseUrl("")); QSignalSpy spy(&api, &MusicApi::results);
        api.search(QStringLiteral("两只老虎"), {source}); QTRY_COMPARE_WITH_TIMEOUT(spy.size(), 1, 20000);
        const auto event = spy.takeFirst(); QVERIFY2(event[1].toString().isEmpty(), qPrintable(event[1].toString()));
        const auto tracks = event[0].toList(); QVERIFY(tracks.size() >= 2);
        for (int index = 0; index < 2; ++index) {
            const QString id = tracks[index].toMap().value("songId").toString();
            bool done = false; MusicApi::Audio audio; QString error;
            api.resolveAudio(source, id, "standard", [&](MusicApi::Audio value, QString failure) { audio = value; error = failure; done = true; });
            QTRY_VERIFY_WITH_TIMEOUT(done, 30000); QVERIFY2(error.isEmpty(), qPrintable(error)); QVERIFY(!audio.url.isEmpty());
            qInfo().noquote() << "Anonymous sample:" << source << id << "via" << audio.sourceId << "bitrate" << audio.bitrate;
            done = false; MusicApi::Lyrics lyrics;
            api.fetchLyrics(source, id, [&](MusicApi::Lyrics value, QString failure) { lyrics = value; error = failure; done = true; });
            QTRY_VERIFY_WITH_TIMEOUT(done, 45000); QVERIFY2(error.isEmpty(), qPrintable(error)); QVERIFY(!lyrics.original.trimmed().isEmpty());
            qInfo() << "Lyrics characters:" << lyrics.original.size();
            const QString helper = qEnvironmentVariable("FLOATMUSIC_QT_PROBE");
            if (helper.isEmpty()) continue;
            QProcess decoder; decoder.setProcessChannelMode(QProcess::SeparateChannels); decoder.start(helper, {});
            QVERIFY(decoder.waitForStarted(5000));
            decoder.write(QJsonDocument(QJsonObject{{"url", audio.url.toString(QUrl::FullyEncoded)}, {"timeout_ms", 15000}}).toJson(QJsonDocument::Compact) + '\n');
            decoder.closeWriteChannel(); QVERIFY(decoder.waitForFinished(20000));
            const auto decoded = QJsonDocument::fromJson(decoder.readAllStandardOutput()).object();
            // Signed URL and FFmpeg diagnostics remain inside the child process; never log them.
            qInfo().noquote() << "Qt outcome:" << decoded.value("outcome").toString();
            QVERIFY(decoded.value("decoded").toBool()); QVERIFY(decoded.value("paused").toBool());
            QVERIFY(decoded.value("resumed").toBool()); QVERIFY(decoded.value("seek_ok").toBool());
        }
    }
private slots:
    void initTestCase() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QCoreApplication::setApplicationName("multisource-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
    }
    void combinedSearchKeepsPlatformIdentityAndSelectionOrder() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); QSignalSpy spy(&api, &MusicApi::results);
        api.search(QStringLiteral("同名 & +"), {"kuwo", "tencent", "netease", "tencent"});
        QTRY_COMPARE(spy.size(), 1); const auto event = spy.takeFirst(); const auto tracks = event[0].toList();
        QCOMPARE(tracks.size(), 3); QVERIFY(event[1].toString().isEmpty());
        QCOMPARE(tracks[0].toMap().value("id").toString(), QString("kuwo:42"));
        QCOMPARE(tracks[1].toMap().value("id").toString(), QString("tencent:004MpJjW07rAPl"));
        QCOMPARE(tracks[2].toMap().value("id").toString(), QString("netease:42"));
        QCOMPARE(tracks[0].toMap().value("duration").toDouble(), 11.0);
        QCOMPARE(tracks[1].toMap().value("duration").toDouble(), 254.0);
        for (const auto &track : tracks) { QCOMPARE(track.toMap().value("name").toString(), QStringLiteral("同名 & +")); QVERIFY(!track.toMap().value("sourceName").toString().isEmpty()); }
        QCOMPARE(fixture.requests.size(), 3);
    }
    void partialFailureAndStaleResultsAreHandled() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.qqSearchStatus = 503;
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); QSignalSpy spy(&api, &MusicApi::results);
        api.search("partial", {"netease", "tencent", "kuwo"}); QTRY_COMPARE(spy.size(), 1);
        auto event = spy.takeFirst(); QCOMPARE(event[0].toList().size(), 2); QVERIFY(event[1].toString().contains("QQ"));
        fixture.qqSearchStatus = 200;
        api.search("slow", {"tencent", "netease"}); QTest::qWait(20); api.search("latest", {"kuwo"});
        QTRY_COMPARE(spy.size(), 1); QCOMPARE(spy.takeFirst()[0].toList()[0].toMap().value("name").toString(), QString("latest"));
        QTest::qWait(300); QVERIFY(spy.isEmpty());
        api.search("unused", {}); QCOMPARE(spy.size(), 1); QVERIFY(spy.takeFirst()[0].toList().isEmpty());
    }
    void qqUsesOrdinaryQualityAndAnonymousBackup() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY(error.isEmpty()); QCOMPARE(audio.sourceId, QString("qq-vkeys"));
        QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("quality"), QString("4"));
        QCOMPARE(QUrlQuery(audio.url).queryItemValue("token", QUrl::FullyDecoded), QString("keep+this"));
        done = false; fixture.denyQqAudio = true;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY(error.isEmpty()); QCOMPARE(audio.sourceId, QString("qq-injahow"));
        QCOMPARE(QUrlQuery(audio.url).queryItemValue("from"), QString("meting"));
    }
    void qqRequests320InsteadOfSilentlyUsingStandard() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/mp3"] = mp3Sample();
        fixture.qqLinks["8"] = qqLink(fixture.base() + "/mp3?token=signed%2Bvalue");
        fixture.qqLinks["4"] = qqLink(fixture.base() + "/audio");
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl(""));
        bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "exhigh", [&](MusicApi::Audio a, QString e) {
            audio = a; error = e; done = true;
        });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("quality"), QString("8"));
        QCOMPARE(audio.sourceId, QString("qq-vkeys-exhigh")); QCOMPARE(audio.bitrate, 320);
        QCOMPARE(QUrlQuery(audio.url).queryItemValue("token", QUrl::FullyDecoded), QString("signed+value"));
    }
    void qqRejectsAnotherSongsMid() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.qqLinks["4"] = qqLink(fixture.base() + "/audio", "AnotherSongMid");
        auto endpoints = fixture.endpoints(); endpoints.injahow.clear();
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl(""));
        bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) {
            audio = a; error = e; done = true;
        });
        QTRY_VERIFY(done); QVERIFY(audio.url.isEmpty()); QVERIFY(!error.isEmpty());
        QCOMPARE(fixture.requests.size(), 1);
    }
    void audioProbesRequestAtMost16KiB() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio, QString) { done = true; });
        QTRY_VERIFY(done); QCOMPARE(fixture.requestHeaders.size(), 2);
        QVERIFY(fixture.requestHeaders.last().contains("\r\nrange: bytes=0-16383\r\n"));
    }
    void audioFormatsComeFromTheirHeaders_data() {
        QTest::addColumn<QByteArray>("sample"); QTest::addColumn<QString>("format");
        QTest::addColumn<QString>("quality"); QTest::addColumn<int>("bitrate");
        QTest::addColumn<int>("sampleRate"); QTest::addColumn<int>("bits");
        QTest::newRow("320-mp3") << mp3Sample() << QString("MP3") << QString("exhigh") << 320 << 44100 << 0;
        QTest::newRow("flac-with-wrong-mime") << flacSample() << QString("FLAC") << QString("lossless") << 0 << 44100 << 24;
        QTest::newRow("m4a-aac") << aacSample() << QString("AAC") << QString("standard") << 192 << 44100 << 0;
        QTest::newRow("ogg-vorbis") << vorbisSample() << QString("Vorbis") << QString("standard") << 192 << 44100 << 0;
    }
    void audioFormatsComeFromTheirHeaders() {
        QFETCH(QByteArray, sample); QFETCH(QString, format); QFETCH(QString, quality);
        QFETCH(int, bitrate); QFETCH(int, sampleRate); QFETCH(int, bits);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/codec"] = sample; fixture.qqLinks["4"] = qqLink(fixture.base() + "/codec");
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl(""));
        bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(audio.format, format); QCOMPARE(audio.quality, quality); QCOMPARE(audio.bitrate, bitrate);
        QCOMPARE(audio.sampleRate, sampleRate); QCOMPARE(audio.bitsPerSample, bits);
    }
    void audioProbeReadsPastId3WithoutDownloadingTheWholeResponse() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        QByteArray sample = QByteArray::fromHex("49443304000000001000") + QByteArray(2048, '\0') + mp3Sample();
        sample.append(QByteArray(64 * 1024, '\0')); // This fixture deliberately ignores Range.
        fixture.audioBodies["/large-id3"] = sample; fixture.qqLinks["4"] = qqLink(fixture.base() + "/large-id3");
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl(""));
        bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(audio.format, QString("MP3")); QCOMPARE(audio.bitrate, 320);
        QCOMPARE(audio.sampleRate, 44100);
    }
    void unprobedContainersStayPlayableAtStandard_data() {
        QTest::addColumn<QByteArray>("sample");
        QTest::newRow("large-id3-cover-art") << unprobedContainerSample(true);
        QTest::newRow("m4a-codec-metadata-at-the-end") << unprobedContainerSample(false);
    }
    void unprobedContainersStayPlayableAtStandard() {
        QFETCH(QByteArray, sample);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/unprobed"] = sample; fixture.qqLinks["4"] = qqLink(fixture.base() + "/unprobed");
        auto endpoints = fixture.endpoints(); endpoints.injahow.clear();
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error)); QVERIFY(!audio.url.isEmpty());
        QCOMPARE(audio.quality, QString("standard")); QVERIFY(audio.format.isEmpty()); QCOMPARE(audio.bitrate, 0);
        QCOMPARE(audio.sampleRate, 0); QCOMPARE(audio.bitsPerSample, 0); QCOMPARE(fixture.requests.size(), 2);
        QVERIFY(fixture.requestHeaders.last().contains("\r\nrange: bytes=0-16383\r\n"));
    }
    void unprobedContainersCannotClaimAHigherQuality_data() { unprobedContainersStayPlayableAtStandard_data(); }
    void unprobedContainersCannotClaimAHigherQuality() {
        QFETCH(QByteArray, sample);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.audioBodies["/unprobed"] = sample;
        for (const auto &code : QStringList{"10", "8", "4"}) fixture.qqLinks[code] = qqLink(fixture.base() + "/unprobed");
        auto endpoints = fixture.endpoints(); endpoints.injahow.clear();
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error)); QCOMPARE(audio.sourceId, QString("qq-vkeys"));
        QCOMPARE(audio.quality, QString("standard")); QVERIFY(audio.format.isEmpty()); QCOMPARE(audio.bitrate, 0);
        QVERIFY(!audio.notice.isEmpty()); QCOMPARE(fixture.requests.size(), 6);
        QCOMPARE(QUrlQuery(fixture.requests[0]).queryItemValue("quality"), QString("10"));
        QCOMPARE(QUrlQuery(fixture.requests[2]).queryItemValue("quality"), QString("8"));
        QCOMPARE(QUrlQuery(fixture.requests[4]).queryItemValue("quality"), QString("4"));
    }
    void malformedContainerHeadersAreRejected_data() {
        QTest::addColumn<QByteArray>("sample");
        QTest::newRow("non-synchsafe-id3") << (QByteArray::fromHex("49443304000080000000") + QByteArray(32768, '\0'));
        QTest::newRow("truncated-ftyp-box") << (QByteArray::fromHex("00100000667479704d344120") + QByteArray(32768, '\0'));
    }
    void malformedContainerHeadersAreRejected() {
        QFETCH(QByteArray, sample);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.audioBodies["/bad"] = sample;
        fixture.qqLinks["4"] = qqLink(fixture.base() + "/bad"); auto endpoints = fixture.endpoints(); endpoints.injahow.clear();
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) { QVERIFY(a.url.isEmpty()); QVERIFY(!e.isEmpty()); done = true; });
        QTRY_VERIFY(done); QCOMPARE(fixture.requests.size(), 2);
    }
    void serviceFailuresCoolDownOneSourceButMissingSongsDoNot_data() {
        QTest::addColumn<int>("status"); QTest::addColumn<bool>("atSample"); QTest::addColumn<bool>("cooldown");
        QTest::newRow("api-523") << 523 << false << true;
        QTest::newRow("api-599") << 599 << false << true;
        QTest::newRow("cdn-523") << 523 << true << true;
        QTest::newRow("cdn-500") << 500 << true << true;
        QTest::newRow("api-404") << 404 << false << false;
        QTest::newRow("cdn-404") << 404 << true << false;
    }
    void serviceFailuresCoolDownOneSourceButMissingSongsDoNot() {
        QFETCH(int, status); QFETCH(bool, atSample); QFETCH(bool, cooldown);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.audioBodies["/audio"] = mp3Sample(false);
        if (atSample) fixture.audioStatuses["/audio"] = status; else fixture.qqAudioStatus = status;
        auto endpoints = fixture.endpoints(); endpoints.injahow.clear();
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; QString error;
        auto receive = [&](MusicApi::Audio, QString e) { error = e; done = true; };
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", receive); QTRY_VERIFY(done); QVERIFY(!error.isEmpty());
        const int firstCount = fixture.requests.size(); QCOMPARE(firstCount, atSample ? 2 : 1);
        fixture.qqAudioStatus = 200; fixture.audioStatuses["/audio"] = 200; done = false;
        api.resolveAudio("tencent", "AnotherSongMid", "standard", receive); QTRY_VERIFY(done);
        QCOMPARE(error.isEmpty(), !cooldown); QCOMPARE(fixture.requests.size(), cooldown ? firstCount : firstCount + 2);
    }
    void qqMissingFlacFallsBackTo320WithoutCoolingDownAnotherSong() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/missing"] = "not found"; fixture.audioStatuses["/missing"] = 404;
        fixture.audioBodies["/mp3"] = mp3Sample(); fixture.audioBodies["/flac"] = flacSample();
        fixture.qqLinks["10"] = qqLink(fixture.base() + "/missing"); fixture.qqLinks["8"] = qqLink(fixture.base() + "/mp3");
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl(""));
        bool done = false; MusicApi::Audio audio; QString error;
        auto receive = [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; };
        api.resolveAudio("tencent", "004MpJjW07rAPl", "lossless", receive);
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(audio.quality, QString("exhigh")); QCOMPARE(audio.sourceId, QString("qq-vkeys-exhigh"));
        QVERIFY(!audio.notice.isEmpty()); QCOMPARE(audio.bitrate, 320);
        QCOMPARE(QUrlQuery(fixture.requests[0]).queryItemValue("quality"), QString("10"));
        QCOMPARE(QUrlQuery(fixture.requests[2]).queryItemValue("quality"), QString("8"));
        fixture.qqLinks["10"] = qqLink(fixture.base() + "/flac", "DifferentSongMid"); fixture.requests.clear(); done = false;
        api.resolveAudio("tencent", "DifferentSongMid", "lossless", receive);
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error)); QCOMPARE(audio.quality, QString("lossless"));
        QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("quality"), QString("10"));
    }
    void qqMasterIsExplicitAndReportsActualHighSpecification() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/flac"] = flacSample(48000, 24, 250);
        fixture.audioBodies["/master"] = flacSample(192000, 24, 254);
        fixture.qqLinks["10"] = qqLink(fixture.base() + "/flac"); fixture.qqLinks["14"] = qqLink(fixture.base() + "/master");
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl(""));
        bool done = false; MusicApi::Audio audio; QString error;
        auto receive = [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; };
        api.resolveAudio("tencent", "004MpJjW07rAPl", "lossless", receive);
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(audio.quality, QString("lossless")); QCOMPARE(audio.sampleRate, 48000);
        QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("quality"), QString("10"));
        for (const auto &request : fixture.requests) QVERIFY(QUrlQuery(request).queryItemValue("quality") != "14");
        fixture.requests.clear(); done = false;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "master", receive);
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("quality"), QString("14"));
        QCOMPARE(audio.sourceId, QString("qq-vkeys-master")); QCOMPARE(audio.quality, QString("master"));
        QCOMPARE(audio.format, QString("FLAC")); QCOMPARE(audio.sampleRate, 192000); QCOMPARE(audio.bitsPerSample, 24);
        QVERIFY(audio.notice.contains(QStringLiteral("原始母带")));
    }
    void excludingOneQqQualityStillAllowsAnotherQuality() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/mp3"] = mp3Sample(); fixture.qqLinks["8"] = qqLink(fixture.base() + "/mp3");
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl(""));
        bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; }, {"qq-vkeys-lossless"});
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error)); QCOMPARE(audio.sourceId, QString("qq-vkeys-exhigh"));
        QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("quality"), QString("8"));
    }
    void kuwoVorbisInPlaceOfFlacRetries320() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/vorbis"] = vorbisSample(); fixture.audioBodies["/mp3"] = mp3Sample();
        fixture.kuwoLinks["2000kflac"] = kuwoLink(fixture.base() + "/vorbis", "42", 0, 226, "flac", 2000);
        fixture.kuwoLinks["320kmp3"] = kuwoLink(fixture.base() + "/mp3");
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error)); QCOMPARE(audio.format, QString("MP3"));
        QCOMPARE(audio.quality, QString("exhigh")); QCOMPARE(audio.bitrate, 320); QCOMPARE(audio.sourceId, QString("kuwo-mobi-exhigh"));
        QVERIFY(!audio.notice.isEmpty()); QCOMPARE(QUrlQuery(fixture.requests[0]).queryItemValue("br"), QString("2000kflac"));
        QCOMPARE(QUrlQuery(fixture.requests[2]).queryItemValue("br"), QString("320kmp3"));
        const QUrlQuery first(fixture.requests.first()); QCOMPARE(first.queryItemValue("rid"), QString("42"));
        QCOMPARE(first.queryItemValue("type"), QString("convert_url_with_sign"));
        QCOMPARE(first.queryItemValue("source"), QString("jiakong")); QCOMPARE(first.queryItemValue("f"), QString("web"));
    }
    void kuwoFlacDoesNotReportTheNominal2000AsItsBitrate() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/flac"] = flacSample(); fixture.audioSizes["/flac"] = 28037216;
        fixture.kuwoLinks["2000kflac"] = kuwoLink(fixture.base() + "/flac", "42", 0, 226, "flac", 2000);
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(audio.quality, QString("lossless")); QCOMPARE(audio.format, QString("FLAC"));
        QCOMPARE(audio.sampleRate, 44100); QCOMPARE(audio.bitsPerSample, 24); QCOMPARE(audio.bitrate, 992);
    }
    void kuwoRejectsIdentityOrPromptAudioBeforeAnyLegacyFallback_data() {
        QTest::addColumn<QString>("rid"); QTest::addColumn<int>("type"); QTest::addColumn<int>("duration");
        QTest::newRow("wrong-rid") << QString("260839262") << 0 << 226;
        QTest::newRow("prompt-type") << QString("42") << 1 << 226;
        QTest::newRow("short-prompt") << QString("42") << 0 << 11;
    }
    void kuwoRejectsIdentityOrPromptAudioBeforeAnyLegacyFallback() {
        QFETCH(QString, rid); QFETCH(int, type); QFETCH(int, duration);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.kuwoLinks["2000kflac"] = kuwoLink(fixture.base() + "/audio", rid, type, duration);
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY(audio.url.isEmpty()); QVERIFY(!error.isEmpty()); QCOMPARE(fixture.requests.size(), 1);
        QCOMPARE(fixture.requests.first().path(), QString("/mobi.s"));
    }
    void kuwoRejectsAFlacPromptWithMisleadingMetadata() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/short-flac"] = flacSample(44100, 24, 11);
        fixture.kuwoLinks["2000kflac"] = kuwoLink(fixture.base() + "/short-flac");
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY(audio.url.isEmpty()); QVERIFY(!error.isEmpty()); QCOMPARE(fixture.requests.size(), 2);
    }
    void kuwoKeepsARealShortSongWhenItsExpectedDurationMatches() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/short-flac"] = flacSample(44100, 24, 11);
        fixture.kuwoLinks["2000kflac"] = kuwoLink(fixture.base() + "/short-flac", "42", 0, 11, "flac", 2000);
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; }, {}, 11);
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error)); QCOMPARE(audio.format, QString("FLAC"));
        QCOMPARE(audio.quality, QString("lossless")); QCOMPARE(audio.sourceId, QString("kuwo-mobi-lossless"));
        QCOMPARE(fixture.requests.size(), 2);
    }
    void kuwoRejectsPromptDurationWhenExpectedSongIsLonger() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/short-flac"] = flacSample(44100, 24, 11);
        fixture.kuwoLinks["2000kflac"] = kuwoLink(fixture.base() + "/short-flac", "42", 0, 11, "flac", 2000);
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; }, {}, 70);
        QTRY_VERIFY(done); QVERIFY(audio.url.isEmpty()); QVERIFY(error.contains(QStringLiteral("目标歌曲")));
        QCOMPARE(fixture.requests.size(), 1);
    }
    void kuwoLegacyCannotBypassShortAudioProtectionAfterMobiFails_data() {
        QTest::addColumn<double>("expectedDuration");
        QTest::newRow("no-target-duration") << 0.0;
        QTest::newRow("longer-target-duration") << 70.0;
    }
    void kuwoLegacyCannotBypassShortAudioProtectionAfterMobiFails() {
        QFETCH(double, expectedDuration);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.kuwoMobiStatus = 503;
        fixture.audioBodies["/audio"] = flacSample(44100, 24, 11);
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; }, {}, expectedDuration);
        QTRY_VERIFY(done); QVERIFY(audio.url.isEmpty()); QVERIFY(error.contains(QStringLiteral("目标")));
        QCOMPARE(fixture.requests.size(), 5);
    }
    void kuwoKeepsALegacyShortSongWhenExpectedDurationMatches() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.kuwoMobiStatus = 503;
        fixture.audioBodies["/audio"] = flacSample(44100, 24, 11);
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "lossless", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; }, {}, 11);
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error)); QCOMPARE(audio.format, QString("FLAC"));
        QCOMPARE(audio.sourceId, QString("kuwo-origin")); QCOMPARE(fixture.requests.size(), 5);
    }
    void kuwoMissing320FallsBackToCorrect128() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.audioBodies["/128"] = mp3Sample(false); fixture.kuwoLinks["128kmp3"] = kuwoLink(fixture.base() + "/128");
        auto endpoints = fixture.endpoints(); endpoints.kuwoMobi = fixture.base() + "/mobi.s";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("kuwo", "42", "exhigh", [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(audio.bitrate, 128); QCOMPARE(audio.quality, QString("standard")); QCOMPARE(audio.sourceId, QString("kuwo-mobi"));
        QVERIFY(!audio.notice.isEmpty()); QCOMPARE(QUrlQuery(fixture.requests[0]).queryItemValue("br"), QString("320kmp3"));
        QCOMPARE(QUrlQuery(fixture.requests[1]).queryItemValue("br"), QString("128kmp3"));
    }
    void kuwoPrefersOriginalCdnAndCanRetryProxy() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        auto receive = [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; };
        api.resolveAudio("kuwo", "42", "lossless", receive); QTRY_VERIFY(done); QVERIFY(error.isEmpty());
        QCOMPARE(audio.sourceId, QString("kuwo-origin")); QCOMPARE(audio.url.path(), QString("/audio"));
        QCOMPARE(QUrlQuery(audio.url).queryItemValue("token", QUrl::FullyDecoded), QString("origin+value"));
        done = false; api.resolveAudio("kuwo", "42", "standard", receive, {"kuwo-origin"}); QTRY_VERIFY(done);
        QVERIFY(error.isEmpty()); QCOMPARE(audio.sourceId, QString("kuwo-proxy")); QCOMPARE(audio.url.path(), QString("/proxy"));
    }
    void audioFailureSuggestsAvailableRecovery_data() {
        QTest::addColumn<QString>("platform");
        QTest::addColumn<QString>("songId");
        QTest::addColumn<bool>("qualitySelectable");
        QTest::newRow("qq-selectable-quality") << QString("tencent") << QString("004MpJjW07rAPl") << true;
        QTest::newRow("kuwo-selectable-quality") << QString("kuwo") << QString("42") << true;
        QTest::newRow("netease-selectable-quality") << QString("netease") << QString("42") << true;
    }
    void audioFailureSuggestsAvailableRecovery() {
        QFETCH(QString, platform); QFETCH(QString, songId); QFETCH(bool, qualitySelectable);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        fixture.denyQqAudio = true; fixture.denyKuwo = true;
        auto endpoints = fixture.endpoints(); endpoints.injahow.clear();
        MusicApi api(endpoints);
        // The Netease fixture returns no playable address from the custom API.
        QVERIFY(api.setBaseUrl(platform == "netease" ? fixture.base() : QString()));
        int calls = 0; MusicApi::Audio audio; QString error;
        api.resolveAudio(platform, songId, "standard", [&](MusicApi::Audio value, QString failure) {
            audio = value; error = failure; ++calls;
        });
        QTRY_COMPARE_WITH_TIMEOUT(calls, 1, 3000); QVERIFY(audio.url.isEmpty());
        QVERIFY2(error.contains(QStringLiteral("重试")), qPrintable(error));
        if (qualitySelectable) {
            QVERIFY2(error.endsWith(QStringLiteral("请检查网络后重试或更换音质。")), qPrintable(error));
        } else {
            QVERIFY2(error.contains(QStringLiteral("其他歌曲")), qPrintable(error));
            QVERIFY2(!error.contains(QStringLiteral("更换音质")), qPrintable(error));
        }
    }
    void platformLyricsAndQqWordTimedConversion() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Lyrics lyrics; QString error;
        auto receive = [&](MusicApi::Lyrics l, QString e) { lyrics = l; error = e; done = true; };
        api.fetchLyrics("tencent", "004MpJjW07rAPl", receive); QTRY_VERIFY(done); QVERIFY(error.isEmpty());
        QCOMPARE(lyrics.original, QString("[00:01.000]Hello world\n[00:02.000]Next")); QCOMPARE(lyrics.translation, QString("[00:01.00]translation"));
        fixture.emptyQqLyrics = true; done = false; api.fetchLyrics("tencent", "004MpJjW07rAPl", receive); QTRY_VERIFY(done);
        QVERIFY(error.isEmpty()); QVERIFY(lyrics.original.contains("Meting original"));
        done = false; api.fetchLyrics("kuwo", "42", receive); QTRY_VERIFY(done); QVERIFY(error.isEmpty()); QVERIFY(lyrics.original.contains("Kuwo original"));
    }
    void invalidSourcesAndIdsNeverIssueRequests() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false;
        api.resolveAudio("tencent", "../bad", "standard", [&](MusicApi::Audio a, QString e) { QVERIFY(a.url.isEmpty()); QVERIFY(!e.isEmpty()); done = true; });
        QVERIFY(done); QVERIFY(fixture.requests.isEmpty()); QVERIFY(api.hasAudioBackups("tencent")); QVERIFY(api.hasAudioBackups("kuwo")); QVERIFY(!api.hasAudioBackups("invalid"));
    }
    void unsupportedQualityNeverIssuesARequest_data() {
        QTest::addColumn<QString>("source"); QTest::addColumn<QString>("id"); QTest::addColumn<QString>("quality");
        QTest::newRow("qq-higher") << QString("tencent") << QString("004MpJjW07rAPl") << QString("higher");
        QTest::newRow("qq-hires") << QString("tencent") << QString("004MpJjW07rAPl") << QString("hires");
        QTest::newRow("kuwo-master") << QString("kuwo") << QString("42") << QString("master");
        QTest::newRow("netease-master") << QString("netease") << QString("42") << QString("master");
    }
    void unsupportedQualityNeverIssuesARequest() {
        QFETCH(QString, source); QFETCH(QString, id); QFETCH(QString, quality);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); bool done = false;
        api.resolveAudio(source, id, quality, [&](MusicApi::Audio a, QString e) { QVERIFY(a.url.isEmpty()); QVERIFY(!e.isEmpty()); done = true; });
        QVERIFY(done); QVERIFY(fixture.requests.isEmpty()); QVERIFY(!MusicApi::validQuality(source, quality));
        QVERIFY(MusicApi::validQuality("hires")); QVERIFY(!MusicApi::validQuality("master"));
    }
    void aNewResolveCancelsTheOlderAudioProbeExactlyOnce() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost)); fixture.audioDelay = 150;
        fixture.audioBodies["/flac"] = flacSample(); fixture.audioBodies["/mp3"] = mp3Sample(false);
        fixture.qqLinks["10"] = qqLink(fixture.base() + "/flac"); fixture.qqLinks["4"] = qqLink(fixture.base() + "/mp3");
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); int olderCalls = 0, latestCalls = 0; QString error;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "lossless", [&](MusicApi::Audio a, QString e) {
            ++olderCalls; QVERIFY(a.url.isEmpty()); QVERIFY(!e.isEmpty());
        });
        QTRY_COMPARE(fixture.requests.size(), 2);
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio a, QString e) {
            ++latestCalls; error = e; QCOMPARE(a.bitrate, 128); QCOMPARE(a.quality, QString("standard"));
        });
        QTRY_COMPARE(latestCalls, 1); QCOMPARE(olderCalls, 1); QVERIFY2(error.isEmpty(), qPrintable(error));
        QTest::qWait(200); QCOMPARE(olderCalls, 1); QCOMPARE(latestCalls, 1);
    }
    void qqIdentityMatchesPlaylistAndSessionContract_data() {
        QTest::addColumn<QString>("mid");
        QTest::addColumn<bool>("valid");
        QTest::newRow("one-character") << QString("A") << true;
        QTest::newRow("short-mixed-case") << QString("AbCd123") << true;
        QTest::newRow("maximum-length") << QString(64, QChar('a')) << true;
        QTest::newRow("empty") << QString() << false;
        QTest::newRow("too-long") << QString(65, QChar('a')) << false;
        QTest::newRow("punctuation") << QString("abc/def") << false;
    }
    void qqIdentityMatchesPlaylistAndSessionContract() {
        QFETCH(QString, mid); QFETCH(bool, valid);
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl(""));
        bool done = false; QString error; MusicApi::Audio audio;
        api.resolveAudio("tencent", mid, "standard", [&](MusicApi::Audio value, QString failure) {
            audio = value; error = failure; done = true;
        });
        QTRY_VERIFY(done);
        QCOMPARE(error.isEmpty(), valid);
        QCOMPARE(!audio.url.isEmpty(), valid);
        if (valid) {
            QVERIFY(!fixture.requests.isEmpty());
            QCOMPARE(QUrlQuery(fixture.requests.first()).queryItemValue("mid"), mid);
        } else QVERIFY(fixture.requests.isEmpty());
    }
    void requestsStayAnonymousAfterServerSetsCookies() {
        MultiSourceFixture fixture; QVERIFY(fixture.listen(QHostAddress::LocalHost));
        MusicApi api(fixture.endpoints()); QVERIFY(api.setBaseUrl("")); QSignalSpy spy(&api, &MusicApi::results);
        api.search("first", {"tencent"}); QTRY_COMPARE(spy.size(), 1); spy.clear();
        api.search("second", {"tencent", "kuwo"}); QTRY_COMPARE(spy.size(), 1);
        bool done = false;
        api.resolveAudio("tencent", "004MpJjW07rAPl", "standard", [&](MusicApi::Audio, QString) { done = true; });
        QTRY_VERIFY(done);
        for (const auto &header : fixture.requestHeaders) { QVERIFY(!header.contains("\r\ncookie:")); QVERIFY(!header.contains("\r\nauthorization:")); }
    }
    void liveAnonymousQq() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_MULTISOURCE")) QSKIP("Opt-in network test; set FLOATMUSIC_LIVE_MULTISOURCE=1.");
        checkLivePlatform("tencent");
    }
    void liveHighQualityHeaders() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_QUALITY")) QSKIP("Opt-in 16 KiB header test; set FLOATMUSIC_LIVE_QUALITY=1.");
        MusicApi api; QVERIFY(api.setBaseUrl(""));
        for (const QString &source : QStringList{"tencent", "kuwo"}) {
            const QString id = source == "tencent" ? "001RGrEX3ija5X" : "397242799";
            for (const QString &quality : QStringList{"exhigh", "lossless"}) {
                bool done = false; MusicApi::Audio audio; QString error;
                api.resolveAudio(source, id, quality, [&](MusicApi::Audio a, QString e) { audio = a; error = e; done = true; });
                QTRY_VERIFY_WITH_TIMEOUT(done, 30000); QVERIFY2(error.isEmpty(), qPrintable(error));
                QVERIFY(!audio.url.isEmpty()); QCOMPARE(audio.quality, quality);
                QCOMPARE(audio.format, quality == "lossless" ? QString("FLAC") : QString("MP3"));
                QCOMPARE(audio.sampleRate, 44100); if (quality == "lossless") QVERIFY(audio.bitsPerSample >= 16);
                else QCOMPARE(audio.bitrate, 320);
                qInfo().noquote() << "High quality header:" << source << audio.sourceId << audio.quality << audio.format
                    << audio.sampleRate << "Hz" << audio.bitsPerSample << "bits" << audio.bitrate << "kbps";
            }
        }
    }
    void liveAnonymousKuwo() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_MULTISOURCE")) QSKIP("Opt-in network test; set FLOATMUSIC_LIVE_MULTISOURCE=1.");
        checkLivePlatform("kuwo");
    }
    void liveAnonymousQqBackup() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_MULTISOURCE")) QSKIP("Opt-in network test; set FLOATMUSIC_LIVE_MULTISOURCE=1.");
        MusicApi api; QVERIFY(api.setBaseUrl("")); bool done = false; MusicApi::Audio audio; QString error;
        api.resolveAudio("tencent", "001RGrEX3ija5X", "standard", [&](MusicApi::Audio value, QString failure) { audio = value; error = failure; done = true; }, {"qq-vkeys"});
        QTRY_VERIFY_WITH_TIMEOUT(done, 30000); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(audio.sourceId, QString("qq-injahow")); QCOMPARE(audio.url.scheme(), QString("https"));
        qInfo().noquote() << "Anonymous QQ backup validated via" << audio.sourceId << "over HTTPS";
    }
};
QTEST_GUILESS_MAIN(MultiSourceApiTests)
#include "multisource_api_tests.moc"
