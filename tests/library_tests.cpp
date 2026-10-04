#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QStandardPaths>
#include <QSettings>
#include <QDataStream>
#include <QJsonDocument>
#include <QJsonArray>
#include <QUrlQuery>
#include <QPointer>
#include <QSslSocket>
#include <QAudioBufferOutput>
#include <QAudioBuffer>
#include <QMediaMetaData>
#include <algorithm>
#include "playercontroller.h"
#include "playlistapi_fixture.h"

class MockApi : public QTcpServer {
public:
    QByteArray lastTarget;
    QByteArray wave;
    QList<QUrl> requests;
    QString failingQuality;
    int gdStatus = 200, legacyStatus = 200, metingStatus = 200;
    QByteArray gdBody;
    bool badGdAudio = false, hangGd = false, corruptGdAudio = false;
    bool failingLyrics = false, noLyrics = false;
    int firstLyricDelay = 0;
    explicit MockApi(int seconds = 2) {
        QDataStream stream(&wave, QIODevice::WriteOnly); stream.setByteOrder(QDataStream::LittleEndian);
        const quint32 bytes = 16000 * 2 * seconds;
        stream.writeRawData("RIFF",4); stream << quint32(36+bytes); stream.writeRawData("WAVEfmt ",8);
        stream << quint32(16) << quint16(1) << quint16(1) << quint32(16000) << quint32(32000) << quint16(2) << quint16(16);
        stream.writeRawData("data",4); stream << bytes; stream.writeRawData(QByteArray(bytes,'\0').constData(),bytes);
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    auto data = socket->property("request").toByteArray() + socket->readAll(); socket->setProperty("request",data);
                    if (!data.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent",true);
                    lastTarget=data.split(' ').value(1); const QUrl url(QString::fromUtf8(lastTarget)); const QUrlQuery query(url);
                    requests.append(url);
                    const QString keyword=query.queryItemValue(query.hasQueryItem("s") ? "s" : "keywords", QUrl::FullyDecoded);
                    QByteArray body; QByteArray contentType="application/json";
                    int status=200;
                    if (url.path()=="/audio.wav") { body=wave; contentType="audio/wav"; }
                    else if(url.path()=="/bad-audio") { body="<html>expired audio link</html>"; contentType="text/html"; }
                    else if(url.path()=="/bad-decode") { body="fLaC000000000000000000000000000000"; contentType="audio/flac"; }
                    else if(url.path()=="/gd") {
                        if(hangGd)return;
                        status=gdStatus;
                        body=gdBody.isEmpty() ? QJsonDocument(QJsonObject{{"url",base()+(badGdAudio ? "/bad-audio" : corruptGdAudio ? "/bad-decode" : "/audio.wav?signature=keep%2Bthis")},{"br",740},{"size",12345}}).toJson() : gdBody;
                    }
                    else if(url.path()=="/meting") {
                        status=metingStatus;
                        if(query.queryItemValue("type")=="url") {
                            socket->write("HTTP/1.1 302 Found\r\nLocation: "+(base()+"/audio.wav?token=signed%2Bvalue").toUtf8()+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                            socket->disconnectFromHost();return;
                        }
                        body=QJsonDocument(QJsonArray{QJsonObject{{"name","Test song"},{"artist","Test artist"},{"url",base()+"/meting?server=netease&type=url&id=101"},{"pic",""},{"lrc",""}}}).toJson();
                    }
                    else if(keyword=="bad-json") body="<html>not an API</html>";
                    else if(keyword=="http-error") {status=503;body="unavailable";}
                    else if(keyword=="denied") body=R"({"code":301})";
                    else if(keyword=="bad-schema") body=R"({"code":200})";
                    else if(keyword=="huge") body=QByteArray(2*1024*1024+4096, 'x');
                    else if(keyword=="hang") return;
                    else if(url.path()=="/lyric" || url.path()=="/api/song/lyric") {
                        if(failingLyrics) {status=502;body="upstream error";}
                        else if(noLyrics) body=R"({"code":200,"nolyric":true})";
                        else body=QJsonDocument(QJsonObject{{"code",200}, {"lrc", QJsonObject{{"lyric", "[00:00.00]original-"+query.queryItemValue("id")}}}, {"tlyric",QJsonObject{{"lyric","[00:00.00]translation"}}}}).toJson();
                    }
                    else if(url.path()=="/api/1/") {
                        contentType="text/plain";
                        if(legacyStatus!=200) {status=legacyStatus;body="upstream unavailable";}
                        else if(query.queryItemValue("level")==failingQuality) {status=503;body="unavailable";}
                        else if(query.queryItemValue("id")=="999") body="<html>not audio</html>";
                        else body=(base()+"/audio.wav?signature=keep%2Bthis").toUtf8();
                    }
                    else if(url.path()=="/song/url/v1") {
                        if(query.queryItemValue("id")=="999") body=R"({"code":200,"data":[{"url":null}]})";
                        else body=QJsonDocument(QJsonObject{{"code",200},{"data",QJsonArray{QJsonObject{{"url",base()+"/audio.wav"}}}}}).toJson();
                    } else {
                        const QJsonArray songs = keyword=="empty" ? QJsonArray{} : QJsonArray{
                            QJsonObject{{"id",101},{"name",keyword},{"ar",QJsonArray{QJsonObject{{"name",QStringLiteral("测试歌手")}}}}},
                            QJsonObject{{"id",102},{"name","Second"},{"ar",QJsonArray{}}},
                            QJsonObject{{"id",999},{"name",QStringLiteral("受限歌曲")},{"ar",QJsonArray{}}}};
                        body=QJsonDocument(QJsonObject{{"code",200},{"result",QJsonObject{{"songs",songs}}}}).toJson();
                    }
                    QPointer<QTcpSocket> safe(socket);
                    auto send=[safe,body,contentType,status] {
                        if(!safe)return;
                        safe->write("HTTP/1.1 "+QByteArray::number(status)+" OK\r\nContent-Type: "+contentType+"\r\nContent-Length: "+QByteArray::number(body.size())+"\r\nConnection: close\r\n\r\n"+body);
                        safe->disconnectFromHost();
                    };
                    if(keyword=="slow")QTimer::singleShot(300,socket,send);
                    else if(url.path().endsWith("/lyric") && query.queryItemValue("id")=="101" && firstLyricDelay>0) QTimer::singleShot(firstLyricDelay,socket,send);
                    else send();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:"+QString::number(serverPort()); }
};

class LibraryTests : public QObject {
    Q_OBJECT
private slots:
    void initTestCase() {
        QVERIFY(QSslSocket::supportsSsl());
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QCoreApplication::setApplicationName("library-"+QUuid::createUuid().toString(QUuid::WithoutBraces));
    }
    void persistenceAndPlaylistEditing() {
        QString id;
        { PlaylistStore store; QVERIFY(!store.create("  ")); QVERIFY(store.create(QStringLiteral("晚间歌单"))); id=store.activeId();
          QVERIFY(store.rename(QStringLiteral("学习音乐")));
          QVariantMap song{{"id","netease:42"},{"name",QStringLiteral("晴天")},{"source","netease"},{"songId","42"}};
          QVERIFY(store.add(song)); QVERIFY(store.add(song)); QCOMPARE(store.tracks().size(),1);
          QVERIFY(store.create("Other")); QCOMPARE(store.tracks().size(),0); QVERIFY(store.select(id)); QCOMPARE(store.tracks().size(),1);
        }
        { PlaylistStore restored; QCOMPARE(restored.activeId(),id); QCOMPARE(restored.tracks().size(),1);
          QVERIFY(restored.removeTrack("netease:42")); QCOMPARE(restored.tracks().size(),0); QVERIFY(restored.removePlaylist());
          const auto lists = restored.playlists();
          for (const auto &entry : lists) {
              const auto listId = entry.toMap().value("id").toString();
              if (listId == "favorites") continue;
              QVERIFY(restored.select(listId)); QVERIFY(restored.removePlaylist());
          }
          QCOMPARE(restored.playlists().size(), 1);
          QCOMPARE(restored.activeId(), QString("favorites"));
          QVERIFY(!restored.removePlaylist());
        }
    }
    void searchErrorsAndStaleResponses() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost)); MusicApi api; QSignalSpy results(&api,&MusicApi::results);
        QVERIFY(api.setBaseUrl("")); QVERIFY(api.baseUrl().isEmpty()); // Empty now selects the built-in provider.
        QVERIFY(!api.setBaseUrl("file:///secret")); QVERIFY(!api.setBaseUrl("https://user:password@example.com"));
        QVERIFY(api.setBaseUrl(mock.base()+"/"));
        api.search(QStringLiteral("周杰伦 & 夜曲")); QTRY_COMPARE(results.size(),1);
        auto found=results.takeFirst(); QCOMPARE(found[0].toList().size(),3);
        QCOMPARE(found[0].toList().first().toMap().value("name").toString(),QStringLiteral("周杰伦 & 夜曲"));
        for(const auto &keyword:{"bad-json","http-error","denied"}) {api.search(keyword);QTRY_COMPARE(results.size(),1);QVERIFY(!results.takeFirst()[1].toString().isEmpty());}
        api.search("empty");QTRY_COMPARE(results.size(),1);auto empty=results.takeFirst();QVERIFY(empty[0].toList().isEmpty());QVERIFY(empty[1].toString().isEmpty());
        api.search("slow");QTest::qWait(30);api.search("latest");QTRY_COMPARE(results.size(),1);
        QCOMPARE(results.takeFirst()[0].toList().first().toMap()["name"].toString(),QString("latest"));QTest::qWait(400);QCOMPARE(results.size(),0);
        bool done=false; api.resolve("999",[&](QUrl url,QString error){QVERIFY(url.isEmpty());QVERIFY(!error.isEmpty());done=true;}); QTRY_VERIFY(done);
        mock.close(); api.search("offline");QTRY_COMPARE_WITH_TIMEOUT(results.size(),1,20000);QVERIFY(!results.takeFirst()[1].toString().isEmpty());
    }
    void reportsHttpStatus() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost)); MusicApi api;
        QVERIFY(api.setBaseUrl(mock.base())); QSignalSpy results(&api, &MusicApi::results);
        api.search("http-error"); QTRY_COMPARE(results.size(), 1);
        QVERIFY2(results.first()[1].toString().contains("503"), qPrintable(results.first()[1].toString()));
    }
    void gdSourceResolvesRequestedQuality() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost));
        MusicApi::Endpoints endpoints{mock.base(),mock.base()+"/api/1/",1000};
        endpoints.gdStudio=mock.base()+"/gd"; endpoints.injahow=mock.base()+"/meting";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl(""));
        bool done=false; QUrl media; QString error;
        api.resolve("101","hires",[&](QUrl u,QString e){media=u;error=e;done=true;});
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(),qPrintable(error));
        QCOMPARE(media.toEncoded(),(mock.base()+"/audio.wav?signature=keep%2Bthis").toUtf8());
        QCOMPARE(mock.requests.first().path(),QString("/gd"));
        QCOMPARE(QUrlQuery(mock.requests.first()).queryItemValue("br"),QString("999"));
    }
    void resolverFallsBackAfter523AndInvalidAudio() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost));
        mock.badGdAudio=true; mock.legacyStatus=523;
        MusicApi::Endpoints endpoints{mock.base(),mock.base()+"/api/1/",1000};
        endpoints.gdStudio=mock.base()+"/gd"; endpoints.injahow=mock.base()+"/meting";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl(""));
        bool done=false; QUrl media; QString error;
        api.resolve("101","hires",[&](QUrl u,QString e){media=u;error=e;done=true;});
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(),qPrintable(error));
        QCOMPARE(media.toEncoded(),(mock.base()+"/audio.wav?token=signed%2Bvalue").toUtf8());
        QVERIFY(std::any_of(mock.requests.cbegin(),mock.requests.cend(),[](const QUrl &u){return u.path()=="/bad-audio";}));
    }
    void resolverTimeoutUsesBackupAndCustomApiStaysSelected() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost)); mock.hangGd=true;
        MusicApi::Endpoints endpoints{mock.base(),mock.base()+"/api/1/",120};
        endpoints.gdStudio=mock.base()+"/gd"; endpoints.injahow=mock.base()+"/meting";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl(""));
        bool done=false; QUrl media; QString error;
        api.resolve("101","standard",[&](QUrl u,QString e){media=u;error=e;done=true;});
        QTRY_VERIFY_WITH_TIMEOUT(done,2000); QVERIFY2(error.isEmpty(),qPrintable(error)); QVERIFY(!media.isEmpty());
        QCOMPARE(mock.requests.first().path(),QString("/gd"));
        mock.requests.clear(); QVERIFY(api.setBaseUrl(mock.base())); done=false;
        api.resolve("101","hires",[&](QUrl u,QString e){media=u;error=e;done=true;});
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(),qPrintable(error));
        QVERIFY(std::none_of(mock.requests.cbegin(),mock.requests.cend(),[](const QUrl &u){return u.path()=="/gd"||u.path()=="/meting"||u.path()=="/api/1/";}));
    }
    void exhaustedAudioSourcesReportFailuresOnce() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost));
        mock.gdStatus=503; mock.legacyStatus=523; mock.metingStatus=502;
        MusicApi::Endpoints endpoints{mock.base(),mock.base()+"/api/1/",500};
        endpoints.gdStudio=mock.base()+"/gd"; endpoints.injahow=mock.base()+"/meting";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl(""));
        int calls=0; QString error;
        api.resolve("101","hires",[&](QUrl url,QString e){QVERIFY(url.isEmpty());error=e;++calls;});
        QTRY_COMPARE(calls,1); QVERIFY(error.contains("503")); QVERIFY(error.contains("523")); QVERIFY(error.contains("502"));
        QCOMPARE(mock.requests.size(),3);
        api.resolve("101","hires",[&](QUrl url,QString e){QVERIFY(url.isEmpty());QVERIFY(!e.isEmpty());++calls;});
        QTRY_COMPARE(calls,2); QCOMPARE(mock.requests.size(),3); // Outage cooldown avoids hammering the same servers.
        QTest::qWait(150); QCOMPARE(calls,2);
    }
    void cancelledAudioDoesNotReturnStaleUrl() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost)); mock.hangGd=true;
        MusicApi::Endpoints endpoints{mock.base(),mock.base()+"/api/1/",500}; endpoints.gdStudio=mock.base()+"/gd";
        MusicApi api(endpoints); QVERIFY(api.setBaseUrl("")); bool cancelled=false;
        api.resolveAudio("101","hires",[&](MusicApi::Audio audio,QString error){QVERIFY(audio.url.isEmpty());QVERIFY(!error.isEmpty());cancelled=true;});
        QTRY_VERIFY(!mock.requests.isEmpty()); QVERIFY(api.setBaseUrl(mock.base()));
        QTRY_VERIFY(cancelled); QTest::qWait(600); QCOMPARE(mock.requests.size(),1);
    }
    void fallbackPlaybackShowsSourceAndPreservesPosition() {
        MockApi mock(15); QVERIFY(mock.listen(QHostAddress::LocalHost)); mock.gdStatus=503; mock.legacyStatus=523;
        MusicApi::Endpoints endpoints{mock.base(),mock.base()+"/api/1/",500};
        endpoints.gdStudio=mock.base()+"/gd"; endpoints.injahow=mock.base()+"/meting";
        PlayerController player(endpoints); player.setSearchSources({"netease"}); player.setApiBase(""); player.setQuality("standard");
        player.search("Fallback track"); QTRY_VERIFY(!player.searching()); player.playSearchResult(0);
        QTRY_VERIFY_WITH_TIMEOUT(!player.busy(),5000); QVERIFY2(player.error().isEmpty(),qPrintable(player.error())); QTRY_VERIFY(player.playing());
        QVERIFY(player.qualityInfo().contains("INJAHOW"));
        player.toggle(); QTRY_VERIFY(!player.playing()); player.seek(4000);
        player.setQuality("hires"); QTRY_VERIFY_WITH_TIMEOUT(!player.busy(),5000); QVERIFY(player.error().isEmpty());
        QCOMPARE(player.currentTrack(),QString("netease:101")); QVERIFY(!player.playing()); QVERIFY(qAbs(player.position()-4000)<500);
        QVERIFY(player.qualityInfo().contains("INJAHOW"));
    }
    void decoderFailureTriesNextSource() {
        MockApi mock(15); QVERIFY(mock.listen(QHostAddress::LocalHost)); mock.corruptGdAudio=true; mock.legacyStatus=523;
        MusicApi::Endpoints endpoints{mock.base(),mock.base()+"/api/1/",500};endpoints.gdStudio=mock.base()+"/gd";endpoints.injahow=mock.base()+"/meting";
        PlayerController player(endpoints);player.setSearchSources({"netease"});player.setApiBase("");player.search("Corrupt source");QTRY_VERIFY(!player.searching());
        player.playSearchResult(0);QTRY_VERIFY_WITH_TIMEOUT(player.playing(),6000);
        QVERIFY2(player.error().isEmpty(),qPrintable(player.error()));QVERIFY(player.qualityInfo().contains("INJAHOW"));
        QCOMPARE(player.currentTrack(),QString("netease:101"));
    }
    void rankingsParsingAndEndpoints() {
        PlaylistApiFixture mock; QVERIFY(mock.listen(QHostAddress::LocalHost));
        MusicApi api(MusicApi::Endpoints{mock.base(), mock.base(), 1000});
        QVERIFY(api.setBaseUrl(""));
        QVariantList lists; QString error; bool done = false;
        auto fetch = [&] {
            done = false;
            api.fetchRankings([&](QVariantList value, QString message) {
                lists = value; error = message; done = true;
            });
        };
        fetch(); QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        QCOMPARE(mock.requests.last().path(), QString("/api/toplist"));
        QStringList ids;
        for (const auto &list : lists) ids.append(list.toMap().value("id").toString());
        QCOMPARE(ids, QStringList({"243", "242", "244", "241", "240", "245"}));
        QCOMPARE(lists.first().toMap().value("trackCount").toInt(), 1000);
        QCOMPARE(lists.last().toMap().value("trackCount").toInt(), -1);
        QVERIFY(!lists.first().toMap().value("description").toString().isEmpty());
        QVERIFY(api.setBaseUrl(mock.base())); fetch(); QTRY_VERIFY(done);
        QCOMPARE(mock.requests.last().path(), QString("/toplist"));
        for (const QByteArray body : {QByteArray("not JSON"), QByteArray(R"({"code":301})"),
                                     QByteArray(R"({"code":200})"),
                                     QByteArray(R"({"code":200,"list":[{"id":0,"name":"bad"}]})")}) {
            mock.rankingsBody = body; fetch(); QTRY_VERIFY(done);
            QVERIFY(lists.isEmpty()); QVERIFY(!error.isEmpty());
        }
        mock.rankingsBody = R"({"code":200,"list":[]})";
        fetch(); QTRY_VERIFY(done); QVERIFY(lists.isEmpty()); QVERIFY(error.isEmpty());
    }
    void rankingsRefreshAndSourceChanges() {
        PlaylistApiFixture first, second;
        QVERIFY(first.listen(QHostAddress::LocalHost)); QVERIFY(second.listen(QHostAddress::LocalHost));
        PlayerController player; player.setApiBase(first.base());
        first.rankingsDelay = 100;
        player.libraryAction("loadRankings", {}); QVERIFY(player.rankingsLoading());
        player.libraryAction("loadRankings", {}); // Only one in-flight request.
        QTRY_VERIFY(!player.rankingsLoading()); QCOMPARE(first.requests.size(), 1);
        const auto saved = player.rankings(); QCOMPARE(saved.size(), 6); QVERIFY(player.rankingsMessage().isEmpty());
        first.rankingsStatus = 503;
        player.libraryAction("loadRankings", {}); QTRY_VERIFY(!player.rankingsLoading());
        QCOMPARE(player.rankings(), saved); QVERIFY(player.rankingsMessage().contains("503"));
        first.rankingsStatus = 200; first.rankingsDelay = 300;
        player.libraryAction("loadRankings", {}); QTRY_COMPARE(first.requests.size(), 3);
        player.setApiBase(second.base());
        QVERIFY(!player.rankingsLoading()); QVERIFY(player.rankings().isEmpty()); QVERIFY(player.rankingsMessage().isEmpty());
        second.rankingsBody = R"({"code":200,"list":[{"id":300,"name":"Other source"}]})";
        player.libraryAction("loadRankings", {}); QTRY_VERIFY(!player.rankingsLoading());
        QCOMPARE(player.rankings().first().toMap().value("id").toString(), QString("300"));
        QTest::qWait(350);
        QCOMPARE(player.rankings().size(), 1); // Delayed old source cannot repopulate the list.
        QCOMPARE(player.rankings().first().toMap().value("id").toString(), QString("300"));
        second.rankingsBody = R"({"code":200,"list":[]})";
        player.libraryAction("loadRankings", {}); QTRY_VERIFY(!player.rankingsLoading());
        QVERIFY(player.rankings().isEmpty()); QVERIFY(!player.rankingsMessage().isEmpty());
    }
    void incompletePlaylistKeepsOriginalRanks() {
        PlaylistApiFixture mock; QVERIFY(mock.listen(QHostAddress::LocalHost));
        MusicApi api; QVERIFY(api.setBaseUrl(mock.base()));
        QVariantMap playlist; QString error; bool done = false;
        api.fetchPlaylist("243", [&](QVariantMap value, QString message) {
            playlist = value; error = message; done = true;
        });
        QTRY_VERIFY(done); QVERIFY2(error.isEmpty(), qPrintable(error));
        const auto tracks = playlist.value("tracks").toList(); QCOMPARE(tracks.size(), 2);
        QCOMPARE(tracks[0].toMap().value("playlistPosition").toInt(), 1);
        QCOMPARE(tracks[1].toMap().value("playlistPosition").toInt(), 4);
        QCOMPARE(playlist.value("trackCount").toInt(), 4); QVERIFY(!playlist.value("warning").toString().isEmpty());
        QCOMPARE(mock.requests.size(), 2);
        QCOMPARE(mock.requests.last().path(), QString("/song/detail"));
    }
    void liveRankings() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_TESTS")) QSKIP("Live rankings test is opt-in.");
        MusicApi api; QVERIFY(api.setBaseUrl(""));
        bool done = false; QVariantList lists; QString error;
        api.fetchRankings([&](QVariantList value, QString message) { lists = value; error = message; done = true; });
        QTRY_VERIFY_WITH_TIMEOUT(done, 20000);
        QVERIFY2(error.isEmpty(), qPrintable(error)); QVERIFY(!lists.isEmpty());
        auto hot = std::find_if(lists.cbegin(), lists.cend(), [](const QVariant &list) {
            return list.toMap().value("name").toString().contains(QStringLiteral("热歌榜"));
        });
        QVERIFY2(hot != lists.cend(), "The live service should return a hot songs playlist.");
        qInfo() << "Live rankings:" << lists.size() << "hot playlist:" << hot->toMap().value("id").toString();
        QVariantMap playlist; done = false;
        api.fetchPlaylist(hot->toMap().value("id").toString(), [&](QVariantMap value, QString message) {
            playlist = value; error = message; done = true;
        });
        QTRY_VERIFY_WITH_TIMEOUT(done, 60000);
        QVERIFY2(error.isEmpty(), qPrintable(error)); QVERIFY(!playlist.value("tracks").toList().isEmpty());
        qInfo() << "Live hot songs:" << playlist.value("name").toString()
                << "reported:" << playlist.value("trackCount").toInt()
                << "loaded:" << playlist.value("tracks").toList().size()
                << "warning:" << playlist.value("warning").toString();
    }
    void liveResolveAudio() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_TESTS")) QSKIP("Live audio resolver test is opt-in.");
        MusicApi api; QVERIFY(api.setBaseUrl(""));
        const QString song = qEnvironmentVariable("FLOATMUSIC_LIVE_SONG", "1220792");
        const QString quality = qEnvironmentVariable("FLOATMUSIC_LIVE_QUALITY", "hires");
        bool done = false; QUrl media; QString error;
        api.resolve(song, quality, [&](QUrl value, QString message) { media = value; error = message; done = true; });
        QTRY_VERIFY_WITH_TIMEOUT(done, 30000);
        qInfo() << "Audio resolver song:" << song << "quality:" << quality
                << "valid URL:" << !media.isEmpty() << "error:" << error;
        QVERIFY2(error.isEmpty(), qPrintable(error));
        QVERIFY(!media.isEmpty());
    }
    void liveAudioSourcesPlayback() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_TESTS")) QSKIP("Live audio playback is opt-in.");
        MusicApi api;QVERIFY(api.setBaseUrl(""));bool done=false;MusicApi::Audio audio;QString error;
        const QString song=qEnvironmentVariable("FLOATMUSIC_LIVE_SONG","1220792");
        const QString quality=qEnvironmentVariable("FLOATMUSIC_LIVE_QUALITY","hires");
        const QStringList excluded=qEnvironmentVariable("FLOATMUSIC_LIVE_EXCLUDED").split(',',Qt::SkipEmptyParts);
        api.resolveAudio(song,quality,[&](MusicApi::Audio a,QString e){audio=a;error=e;done=true;},excluded);
        QTRY_VERIFY_WITH_TIMEOUT(done,30000);QVERIFY2(error.isEmpty(),qPrintable(error));
        QMediaPlayer media;QAudioOutput output;output.setMuted(true);media.setAudioOutput(&output);
        QAudioBufferOutput buffers;media.setAudioBufferOutput(&buffers);int decoded=0;
        connect(&buffers,&QAudioBufferOutput::audioBufferReceived,this,[&](const QAudioBuffer &b){if(b.isValid())++decoded;});
        media.setSource(audio.url);media.play();QTRY_VERIFY_WITH_TIMEOUT(decoded>0,15000);QTRY_VERIFY(media.position()>150);
        media.pause();QTRY_COMPARE(media.playbackState(),QMediaPlayer::PausedState);
        const auto paused=media.position();QTest::qWait(350);QVERIFY(qAbs(media.position()-paused)<200);
        QVERIFY(media.isSeekable());media.setPosition(10000);QTRY_VERIFY(media.position()>=9500);
        media.play();QTRY_VERIFY(media.position()>10200);
        qInfo()<<"Live audio passed:"<<song<<quality<<audio.sourceName<<"source quality"<<audio.bitrate
               <<"codec"<<media.metaData().stringValue(QMediaMetaData::AudioCodec)<<"decoded buffers"<<decoded;
        media.stop();
    }
    void directPlaybackQualityAndLyrics() {
        MockApi mock(12); QVERIFY(mock.listen(QHostAddress::LocalHost)); PlayerController player;
        player.setSearchSources({"netease"});
        QVERIFY2(player.metaObject()->indexOfProperty("quality") >= 0, "Missing quality selection");
        player.setApiBase(mock.base()); player.search("Network track"); QTRY_VERIFY(!player.searching());
        const auto count = player.tracks().size();
        QVERIFY(QMetaObject::invokeMethod(&player, "playSearchResult", Q_ARG(int, 0)));
        QTRY_VERIFY_WITH_TIMEOUT(player.playing(), 15000);
        QCOMPARE(player.tracks().size(), count); // Direct listening must not create a playlist entry.
        QTRY_VERIFY(player.property("lyrics").toString().contains("original"));
        QVERIFY(player.property("translation").toString().contains("translation"));
        player.seek(4000); player.toggle(); QTRY_VERIFY(!player.playing());
        QVERIFY(QMetaObject::invokeMethod(&player, "setQuality", Q_ARG(QString, QString("lossless"))));
        QTRY_VERIFY_WITH_TIMEOUT(!player.busy(), 15000);
        QCOMPARE(player.property("quality").toString(), QString("lossless"));
        QVERIFY(!player.playing()); QVERIFY(qAbs(player.position() - 4000) < 500);
        player.toggle(); QTRY_VERIFY(player.playing());
        QVERIFY(QMetaObject::invokeMethod(&player, "setQuality", Q_ARG(QString, QString("invalid"))));
        QCOMPARE(player.property("quality").toString(), QString("lossless"));
    }
    void builtinProtocolsAndErrors() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost));
        MusicApi api(MusicApi::Endpoints{mock.base(), mock.base()+"/api/1/", 1000}); QVERIFY(api.setBaseUrl(""));
        QSignalSpy results(&api, &MusicApi::results);
        api.search(QStringLiteral("夜曲 & +")); QTRY_COMPARE(results.size(), 1);
        QCOMPARE(results.takeFirst()[0].toList().first().toMap()["name"].toString(), QStringLiteral("夜曲 & +"));
        QCOMPARE(mock.requests.last().path(), QString("/api/search/get"));
        QVERIFY(mock.lastTarget.contains("%2B"));
        for (const QString level : {"standard","higher","exhigh","lossless","hires"}) {
            bool done=false;
            api.resolve("101",level,[&](QUrl url,QString error) {
                QVERIFY2(error.isEmpty(),qPrintable(error));
                QCOMPARE(url.toEncoded(), (mock.base()+"/audio.wav?signature=keep%2Bthis").toUtf8()); done=true;
            });
            QTRY_VERIFY(done); QCOMPARE(QUrlQuery(mock.requests.last()).queryItemValue("level"),level);
        }
        for (const QString keyword : {"bad-json","bad-schema","http-error","denied","huge","hang"}) {
            api.search(keyword); QTRY_COMPARE_WITH_TIMEOUT(results.size(),1,3000);
            const QString error=results.takeFirst()[1].toString(); QVERIFY(!error.isEmpty());
            if(keyword=="hang") QVERIFY(error.contains(QStringLiteral("超时")));
            if(keyword=="huge") QVERIFY(error.contains("2 MiB"));
        }
        bool done=false;
        api.resolve("999","standard",[&](QUrl u,QString e){QVERIFY(u.isEmpty());QVERIFY(!e.isEmpty());done=true;}); QTRY_VERIFY(done);
        MusicApi::Lyrics lyrics; QString error; done=false;
        api.fetchLyrics("101",[&](MusicApi::Lyrics l,QString e){lyrics=l;error=e;done=true;}); QTRY_VERIFY(done);
        QVERIFY(error.isEmpty()); QVERIFY(lyrics.original.contains("original-101")); QVERIFY(!lyrics.translation.isEmpty());
        mock.noLyrics=true; done=false;
        api.fetchLyrics("101",[&](MusicApi::Lyrics l,QString e){lyrics=l;error=e;done=true;}); QTRY_VERIFY(done);
        QVERIFY(error.isEmpty()); QVERIFY(lyrics.instrumental); QVERIFY(lyrics.original.isEmpty());
        mock.failingLyrics=true; done=false;
        api.fetchLyrics("101",[&](MusicApi::Lyrics,QString e){error=e;done=true;}); QTRY_VERIFY(done); QVERIFY(error.contains("502"));
    }
    void staleLyricsAndFailedQualityKeepPlaying() {
        MockApi mock(12); QVERIFY(mock.listen(QHostAddress::LocalHost)); mock.firstLyricDelay=1600;
        PlayerController player(MusicApi::Endpoints{mock.base(),mock.base()+"/api/1/",3000});
        player.setSearchSources({"netease"}); player.setApiBase(""); player.setQuality("standard"); player.search("two songs"); QTRY_VERIFY(!player.searching());
        player.playSearchResult(0); QTRY_VERIFY(player.playing());
        player.playSearchResult(1); QTRY_COMPARE(player.currentTrack(),QString("netease:102")); QTRY_VERIFY(player.playing());
        QTRY_VERIFY(player.lyrics().contains("original-102")); QTest::qWait(1700);
        QVERIFY(player.lyrics().contains("original-102")); QVERIFY(!player.lyrics().contains("original-101"));
        mock.failingQuality="hires"; player.setQuality("hires"); QTRY_VERIFY(!player.busy());
        QVERIFY(player.error().contains("503")); QVERIFY(player.playing()); QCOMPARE(player.currentTrack(),QString("netease:102"));
        mock.failingQuality.clear(); player.retryPlayback(); QTRY_VERIFY(!player.busy()); QTRY_VERIFY(player.playing()); QVERIFY(player.error().isEmpty());
        mock.failingLyrics=true; QTRY_VERIFY(!player.lyricsLoading()); player.retryLyrics(); QTRY_VERIFY(!player.lyricsLoading());
        QVERIFY(player.lyricsFailed()); QVERIFY(player.lyricsMessage().contains("502")); QVERIFY(player.playing());
        mock.failingLyrics=false; player.retryLyrics(); QTRY_VERIFY(!player.lyricsLoading()); QVERIFY(!player.lyricsFailed());
    }
    void liveBuiltinWindows() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_TESTS")) QSKIP("Live network test is opt-in.");
        PlayerController player; player.setSearchSources({"netease"}); player.setApiBase(""); player.setQuality("standard"); player.setVolume(0);
        player.search(QStringLiteral("海阔天空")); QTRY_VERIFY_WITH_TIMEOUT(!player.searching(),20000);
        QVERIFY2(!player.searchResults().isEmpty(),qPrintable(player.searchMessage()));
        int chosen=0;
        for(int i=0;i<player.searchResults().size();++i) if(player.searchResults()[i].toMap()["songId"].toString()=="347230") chosen=i;
        qInfo() << "Live song:" << player.searchResults()[chosen].toMap()["songId"].toString();
        for(const QString level:{"standard","higher","exhigh","lossless","hires"}) {
            player.setQuality(level);
            if (player.currentTrack().isEmpty()) player.playSearchResult(chosen);
            QTRY_VERIFY_WITH_TIMEOUT(!player.busy(),40000);
            // One explicit retry documents transient upstream failures without silently looping.
            if(!player.error().isEmpty()) {qInfo()<<"Retry:"<<level<<player.error();player.retryPlayback();QTRY_VERIFY_WITH_TIMEOUT(!player.busy(),40000);}
            QVERIFY2(player.error().isEmpty(),qPrintable(level+": "+player.error())); QTRY_VERIFY_WITH_TIMEOUT(player.playing(),10000);
            QTRY_VERIFY_WITH_TIMEOUT(player.position()>100,10000);
            player.toggle(); QTRY_VERIFY(!player.playing()); player.seek(10000); QTRY_VERIFY(player.position()>=9500);
            player.toggle(); QTRY_VERIFY(player.playing()); QTRY_VERIFY(player.position()>10200);
            qInfo()<<"Live passed:"<<level<<player.qualityInfo()<<"duration"<<player.duration();
        }
        QTRY_VERIFY_WITH_TIMEOUT(!player.lyricsLoading(),20000);
        QVERIFY2(!player.lyricsFailed(),qPrintable(player.lyricsMessage())); QVERIFY(!player.lyrics().isEmpty());
        qInfo()<<"Lyrics chars:"<<player.lyrics().size()<<"translation chars:"<<player.translation().size();
        player.toggle();
        MusicApi api; QVERIFY(api.setBaseUrl("")); bool done=false; MusicApi::Lyrics translated; QString lyricError;
        api.fetchLyrics("4337372",[&](MusicApi::Lyrics value,QString error){translated=value;lyricError=error;done=true;});
        QTRY_VERIFY_WITH_TIMEOUT(done,20000); QVERIFY2(lyricError.isEmpty(),qPrintable(lyricError));
        QVERIFY(!translated.original.isEmpty()); QVERIFY(!translated.translation.isEmpty());
        qInfo()<<"Translated lyric chars:"<<translated.original.size()<<translated.translation.size();
    }
    void streamingQueueAndLocalPersistence() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost)); PlayerController player;
        player.setSearchSources({"netease"});
        player.createPlaylist("Playback tests"); player.setApiBase(mock.base()); player.search("Network track");
        QTRY_VERIFY(!player.searching()); QCOMPARE(player.searchResults().size(),3);
        player.addSearchResult(0); player.addSearchResult(0); player.addSearchResult(1); QCOMPARE(player.tracks().size(),2);
        player.playTrack("netease:101");QTRY_VERIFY_WITH_TIMEOUT(player.playing(),15000);
        player.next();QTRY_COMPARE(player.currentTrack(),QString("netease:102"));QTRY_VERIFY(player.playing());
        player.previous();QTRY_COMPARE(player.currentTrack(),QString("netease:101"));QTRY_VERIFY(player.playing());
        player.seek(1800);QTRY_COMPARE_WITH_TIMEOUT(player.currentTrack(),QString("netease:102"),5000);
        QTRY_VERIFY(player.playing());player.seek(1800);QTRY_VERIFY_WITH_TIMEOUT(!player.playing(),5000);QCOMPARE(player.currentTrack(),QString("netease:102"));
        player.next();QTRY_COMPARE(player.currentTrack(),QString("netease:101"));QTRY_VERIFY(player.playing());player.toggle();
        player.addSearchResult(2);player.playTrack("netease:999");QTRY_VERIFY(!player.busy());QVERIFY(!player.error().isEmpty());QCOMPARE(player.currentTrack(),QString("netease:101"));
        QTemporaryDir source; QFile local(source.filePath("persistent.wav"));QVERIFY(local.open(QIODevice::WriteOnly));local.write(mock.wave);local.close();
        player.importFile(QUrl::fromLocalFile(local.fileName()));QTRY_VERIFY_WITH_TIMEOUT(!player.busy(),15000);QVERIFY(player.ready());
        const auto songs=player.tracks();const auto saved=songs.last().toMap();QVERIFY(QFile::remove(local.fileName()));QVERIFY(QFile::exists(saved["path"].toString()));
        {PlayerController reopened;QCOMPARE(reopened.tracks().size(),4);reopened.playTrack(saved["id"].toString());QTRY_VERIFY(reopened.playing());reopened.toggle();}
        player.removeTrack(saved["id"].toString());QCOMPARE(player.tracks().size(),3);
        player.selectPlaylist("default");player.next();QVERIFY(!player.playing());
    }
};
QTEST_GUILESS_MAIN(LibraryTests)
#include "library_tests.moc"
