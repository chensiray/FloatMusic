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
#include "playercontroller.h"

class MockApi : public QTcpServer {
public:
    QByteArray lastTarget;
    QByteArray wave;
    MockApi() {
        QDataStream stream(&wave, QIODevice::WriteOnly); stream.setByteOrder(QDataStream::LittleEndian);
        const quint32 bytes = 16000 * 2 * 2;
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
                    const QString keyword=query.queryItemValue("keywords"); QByteArray body; QByteArray contentType="application/json";
                    int status=200;
                    if (url.path()=="/audio.wav") { body=wave; contentType="audio/wav"; }
                    else if(keyword=="bad-json") body="<html>not an API</html>";
                    else if(keyword=="http-error") {status=503;body="unavailable";}
                    else if(keyword=="denied") body=R"({"code":301})";
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
                    if(keyword=="slow")QTimer::singleShot(300,socket,send); else send();
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
          while(restored.playlists().size()>1)QVERIFY(restored.removePlaylist()); QVERIFY(!restored.removePlaylist());
        }
    }
    void searchErrorsAndStaleResponses() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost)); MusicApi api; QSignalSpy results(&api,&MusicApi::results);
        QVERIFY(api.setBaseUrl("")); api.search("test"); QCOMPARE(results.size(),1); QVERIFY(!results.takeFirst()[1].toString().isEmpty());
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
    void streamingQueueAndLocalPersistence() {
        MockApi mock; QVERIFY(mock.listen(QHostAddress::LocalHost)); PlayerController player;
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
