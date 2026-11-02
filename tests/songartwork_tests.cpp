#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>
#include <QTemporaryDir>
#include <QSettings>
#include "songartwork.h"
#include "artworkpolicy.h"
class ArtworkServer:public QTcpServer {
public:
    int requests=0,active=0,peak=0;
    ArtworkServer(){connect(this,&QTcpServer::newConnection,this,[this]{while(auto *s=nextPendingConnection()){
        connect(s,&QTcpSocket::disconnected,s,&QObject::deleteLater);connect(s,&QTcpSocket::readyRead,s,[this,s]{
            const auto bytes=s->readAll();if(s->property("sent").toBool()||!bytes.contains("\r\n\r\n"))return;s->setProperty("sent",true);++requests;peak=qMax(peak,++active);
            const auto line=QString::fromUtf8(bytes.split(' ').value(1));const auto id=line.contains("id=2")?"2":"1";
            const QByteArray body=QByteArray("{\"code\":200,\"songs\":[{\"id\":")+id+",\"album\":{\"picUrl\":\"https://example.com/album.jpg\"}}]}";
            QTimer::singleShot(40,s,[this,s,body]{--active;s->write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+QByteArray::number(body.size())+"\r\n\r\n"+body);s->disconnectFromHost();});
        });}});listen(QHostAddress::LocalHost);}
    MusicApi::Endpoints endpoints(){MusicApi::Endpoints e;e.metadata=QString("http://127.0.0.1:%1").arg(serverPort());e.timeoutMs=500;return e;}
};
class SongArtworkTests:public QObject{
    Q_OBJECT
    QVariantMap track(QString id="1"){return {{"source","netease"},{"songId",id},{"id","netease:"+id}};}
private slots:
    void initTestCase(){QCoreApplication::setOrganizationName("FloatMusicTests");QCoreApplication::setApplicationName("Artwork");}
    void init(){QSettings().clear();}
    void completesMissingAndDeduplicates(){ArtworkServer s;QTemporaryDir dir;SongArtwork a(s.endpoints(),nullptr,dir.filePath("covers.json"));
        for(int i=0;i<12;i++)a.request(track());a.request(track("2"));
        QTRY_VERIFY(!a.cover(track()).isEmpty());QTRY_VERIFY(!a.cover(track("2")).isEmpty());QCOMPARE(s.requests,2);QVERIFY(s.peak<=2);
        QTest::qWait(300);SongArtwork restored(s.endpoints(),nullptr,dir.filePath("covers.json"));QCOMPARE(restored.cover(track()),a.cover(track()));
    }
    void disabledPersistsAndDoesNotFetch(){ArtworkServer s;QTemporaryDir dir;SongArtwork a(s.endpoints(),nullptr,dir.filePath("covers.json"));a.setEnabled(false);a.request(track());QTest::qWait(80);QCOMPARE(s.requests,0);QVERIFY(a.cover(track()).isEmpty());SongArtwork b(s.endpoints(),nullptr,dir.filePath("other.json"));QVERIFY(!b.enabled());}
    void preservesProvidedAndRejectsUnsafeImages(){ArtworkServer s;QTemporaryDir dir;SongArtwork a(s.endpoints(),nullptr,dir.filePath("covers.json"));auto t=track();t["coverUrl"]="https://example.com/provided.jpg";a.request(t);QCOMPARE(a.cover(track()),t.value("coverUrl").toString());QCOMPARE(s.requests,0);QVERIFY(ArtworkPolicy::url("file:///secret").isEmpty());QVERIFY(ArtworkPolicy::url("https://user:pass@example.com/art").isEmpty());QCOMPARE(ArtworkPolicy::fromSong("tencent",{{"albumMID","Album1"}}),QString("https://y.gtimg.cn/music/photo_new/T002R300x300M000Album1.jpg"));}
    void liveMissingArtwork(){
        if(!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_DETAILS"))QSKIP("Opt-in artwork provider check");
        QTemporaryDir dir;SongArtwork a(MusicApi::builtinEndpoints(),nullptr,dir.filePath("live.json"));
        for(const auto &t:QVariantList{track("186016"),QVariantMap{{"source","tencent"},{"songId","0039MnYb0qxYhV"},{"id","tencent:0039MnYb0qxYhV"}}}){
            a.request(t.toMap());QTRY_VERIFY_WITH_TIMEOUT(!a.cover(t.toMap()).isEmpty(),35000);
        }
    }
};
QTEST_GUILESS_MAIN(SongArtworkTests)
#include "songartwork_tests.moc"
