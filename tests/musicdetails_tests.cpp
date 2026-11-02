#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QJsonDocument>
#include <QJsonArray>
#include <QTemporaryDir>
#include <QUrlQuery>
#include <QTimer>
#include "musicdetails.h"

class DetailsServer : public QTcpServer {
public:
    bool fail = false;
    int delay = 0, requests = 0;
    DetailsServer() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *s = nextPendingConnection()) {
                connect(s, &QTcpSocket::disconnected, s, &QObject::deleteLater);
                connect(s, &QTcpSocket::readyRead, s, [this,s] {
                    auto raw = s->property("raw").toByteArray() + s->readAll();
                    s->setProperty("raw", raw);
                    if (!raw.contains("\r\n\r\n") || s->property("sent").toBool()) return;
                    s->setProperty("sent", true); ++requests;
                    const QUrl u(QString::fromUtf8(raw.split(' ').value(1)));
                    const QUrlQuery q(u);
                    QJsonObject body;
                    if(u.path()=="/music/tencent/search/song"){
                        body={{"code",0},{"data",QJsonObject{{"list",QJsonArray{QJsonObject{{"songMID","Abc"},{"title","Song"},{"singer","Artist"}}}}}}};
                    } else if(u.path()=="/qq"){
                        body={{"code",0},{"req_0",QJsonObject{{"code",0},{"data",QJsonObject{{"track_info",QJsonObject{{"mid","Abc"},{"id",123},{"album",QJsonObject{{"name","Album"},{"mid","Alb1"}}}}}}}}}};
                    } else if (u.path()=="/qq-comments") {
                        body={{"code",0},{"comment",QJsonObject{{"commenttotal",41},{"commentlist",QJsonArray{
                            QJsonObject{{"commentid","qq-1"},{"rootcommentcontent","A &amp; B"},{"nick","Author"},{"praisenum",0},{"time",1700000000}}}}}}};
                    } else if (u.path().contains("playlist")) {
                        body={{"code",200},{"playlist",QJsonObject{{"id",123},{"name","List"},{"trackCount",0},
                            {"trackIds",QJsonArray{}},{"tracks",QJsonArray{}},{"coverImgUrl","https://example.com/list.jpg"},
                            {"playCount",12000},{"subscribedCount",0},{"commentCount",43}}}};
                    } else if (u.path().contains("detail")) {
                        body = {{"code",200},{"songs",QJsonArray{QJsonObject{{"id",q.queryItemValue("id").toInt()},
                            {"album",QJsonObject{{"name","Album"},{"picUrl","https://example.com/art.jpg"}}}}}}};
                    } else {
                        const int offset=q.queryItemValue("offset").toInt();
                        auto c=[](int id){return QJsonObject{{"commentId",id},{"content",QString("Text %1").arg(id)},
                            {"user",QJsonObject{{"nickname","Author"}}},{"likedCount",0},{"time",1700000000000.0}};};
                        body={{"code",200},{"total",41},{"more",offset==0},
                            {"comments",offset==0?QJsonArray{c(1),c(2)}:QJsonArray{c(2),c(3)}},
                            {"hotComments",QJsonArray{c(4)}}};
                    }
                    const auto bytes=QJsonDocument(body).toJson(QJsonDocument::Compact);
                    const auto response=QByteArray(fail?"HTTP/1.1 503 Failure\r\n":"HTTP/1.1 200 OK\r\n")+
                        "Content-Type: application/json\r\nConnection: close\r\nContent-Length: "+QByteArray::number(bytes.size())+"\r\n\r\n"+bytes;
                    QTimer::singleShot(delay,s,[s,response]{s->write(response);s->disconnectFromHost();});
                });
            }
        });
        listen(QHostAddress::LocalHost);
    }
    MusicApi::Endpoints endpoints() const {
        MusicApi::Endpoints e; e.metadata=QString("http://127.0.0.1:%1").arg(serverPort());e.qqComments=e.metadata+"/qq-comments";e.qqMusicu=e.metadata+"/qq";e.vkeys=e.metadata; e.timeoutMs=1000; return e;
    }
};
class MusicDetailsTests : public QObject {
    Q_OBJECT
    QVariantMap track(QString id="1") {return {{"id","netease:"+id},{"source","netease"},{"songId",id},{"name","Song"},{"artist","Artist"}};}
private slots:
    void initTestCase(){QCoreApplication::setOrganizationName("FloatMusicTests");QCoreApplication::setApplicationName("Details");}
    void metadataMissingCountsStayMissing() {
        DetailsServer server; MusicApi api(server.endpoints()); QVariantMap result; bool done=false;
        api.fetchSongDetails(track(),[&](QVariantMap d,QString e){QVERIFY(e.isEmpty());result=d;done=true;});
        QTRY_VERIFY(done); QCOMPARE(result.value("album").toString(),"Album");
        QVERIFY(!result.contains("playCount")); QVERIFY(!result.contains("favoriteCount"));
    }
    void paginationDeduplicatesAndStops() {
        DetailsServer server; QTemporaryDir dir; MusicDetails d(server.endpoints(),dir.path());
        d.action("open",{{"track",track()}}); QTRY_VERIFY(!d.state().value("loading").toBool());
        QCOMPARE(d.state().value("comments").toList().size(),1);
        d.action("order",{{"order","new"}}); QTRY_VERIFY(!d.state().value("loading").toBool());
        QVERIFY(d.state().value("hasMore").toBool());
        d.action("more",{}); QTRY_VERIFY(!d.state().value("loading").toBool());
        QCOMPARE(d.state().value("comments").toList().size(),3); QVERIFY(!d.state().value("hasMore").toBool());
        QCOMPARE(d.state().value("details").toMap().value("commentCount").toLongLong(),41);
    }
    void playlistCountsAreNotSongCounts() {
        DetailsServer server;MusicApi api(server.endpoints());QVariantMap result;bool done=false;
        api.fetchPlaylist("123",[&](QVariantMap d,QString e){QVERIFY(e.isEmpty());result=d;done=true;});
        QTRY_VERIFY(done);QCOMPARE(result.value("playCount").toLongLong(),12000);
        QVERIFY(result.contains("favoriteCount"));QCOMPARE(result.value("favoriteCount").toLongLong(),0);
        QCOMPARE(result.value("commentCount").toLongLong(),43);QCOMPARE(result.value("coverUrl").toString(),"https://example.com/list.jpg");
    }
    void qqHotPageUsesCommentGroup() {
        DetailsServer server;MusicApi api(server.endpoints());bool done=false;QVariantMap result;
        api.fetchComments({{"source","tencent"},{"songId","Abc"},{"commentSongId","123"}},"hot",0,[&](QVariantMap d,QString e){QVERIFY(e.isEmpty());result=d;done=true;});
        QTRY_VERIFY(done);QCOMPARE(result.value("comments").toList().size(),1);
        QCOMPARE(result.value("comments").toList().first().toMap().value("content").toString(),"A & B");
        QVERIFY(result.value("hasMore").toBool());
    }
    void lateRepliesAndCloseAreIgnored() {
        DetailsServer server; server.delay=100; QTemporaryDir dir; MusicDetails d(server.endpoints(),dir.path());
        d.action("open",{{"track",track("1")}}); QTest::qWait(20);
        d.action("open",{{"track",track("2")}}); QTRY_VERIFY(!d.state().value("loading").toBool());
        QCOMPARE(d.state().value("track").toMap().value("songId").toString(),"2");
        d.action("retry",{}); d.action("close",{}); QTest::qWait(200);
        QVERIFY(!d.state().value("loading").toBool());
    }
    void reconfirmingChosenSourceStillShowsCandidates() {
        DetailsServer server;QTemporaryDir dir;MusicDetails d(server.endpoints(),dir.path());
        d.action("open",{{"track",track()}});QTRY_VERIFY(!d.state().value("loading").toBool());
        d.action("source",{{"source","tencent"}});QTRY_COMPARE(d.state().value("candidates").toList().size(),1);
        d.action("select",{{"id","tencent:Abc"}});QTRY_VERIFY(!d.state().value("loading").toBool());
        QVERIFY(!d.state().value("choosing").toBool());
        d.action("source",{{"source","kuwo"}});d.action("source",{{"source","tencent"}});
        QTRY_COMPARE(d.state().value("candidates").toList().size(),1);QVERIFY(d.state().value("choosing").toBool());
        d.action("select",{{"id","tencent:Abc"}});QTRY_VERIFY(!d.state().value("loading").toBool());
        d.action("source",{{"source","tencent"}});QTRY_COMPARE(d.state().value("candidates").toList().size(),1);
        QVERIFY(d.state().value("choosing").toBool());
    }
    void failedRefreshKeepsOfflineSnapshot() {
        DetailsServer server; QTemporaryDir dir;
        {MusicDetails d(server.endpoints(),dir.path());d.action("open",{{"track",track()}});QTRY_VERIFY(!d.state().value("loading").toBool());}
        server.fail=true;
        MusicDetails d(server.endpoints(),dir.path());d.action("open",{{"track",track()}});
        QTRY_VERIFY(!d.state().value("loading").toBool());
        QVERIFY(d.state().value("stale").toBool()); QVERIFY(!d.state().value("error").toString().isEmpty());
        QCOMPARE(d.state().value("comments").toList().size(),1);
    }
    void invalidLocalAndUnsupportedDoNotInventEmptySuccess() {
        DetailsServer server; QTemporaryDir dir; MusicDetails d(server.endpoints(),dir.path());
        d.action("open",{{"track",QVariantMap{{"source","local"}}}});
        QVERIFY(!d.state().value("loading").toBool()); QVERIFY(!d.state().value("error").toString().isEmpty());
        d.action("open",{{"track",QVariantMap{{"source","kuwo"},{"songId","123"},{"name","Song"}}}});
        QTRY_VERIFY(!d.state().value("loading").toBool()); QVERIFY(!d.state().value("error").toString().isEmpty());
    }
    void livePublicSources() {
        if(!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_DETAILS"))QSKIP("Opt-in public network smoke test");
        MusicApi api;
        for(const auto &t:QVariantList{track("186016"),QVariantMap{{"source","tencent"},{"songId","0039MnYb0qxYhV"}}}){
            bool done=false;QString error;QVariantMap details;
            api.fetchSongDetails(t.toMap(),[&](QVariantMap d,QString e){details=d;error=e;done=true;});
            QTRY_VERIFY_WITH_TIMEOUT(done,25000);QVERIFY2(error.isEmpty(),qPrintable(error));QVERIFY(!details.value("coverUrl").toString().isEmpty());
            for(const QString &order:QStringList{"hot","new"}){
                done=false;QVariantMap result;auto request=t.toMap();request["commentSongId"]=details.value("commentSongId");
                api.fetchComments(request,order,0,[&](QVariantMap d,QString e){result=d;error=e;done=true;});
                QTRY_VERIFY_WITH_TIMEOUT(done,25000);QVERIFY2(error.isEmpty(),qPrintable(error));
                QVERIFY(!result.value("comments").toList().isEmpty());QVERIFY(result.value("commentCount").toLongLong()>0);
            }
        }
    }
};
QTEST_GUILESS_MAIN(MusicDetailsTests)
#include "musicdetails_tests.moc"
