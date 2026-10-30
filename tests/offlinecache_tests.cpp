#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QDataStream>
#include <QSettings>
#include <QJsonArray>
#include <QJsonDocument>
#include <QDir>
#include <QStandardPaths>
#include "offlinecache.h"
static QByteArray wave(){QByteArray b;QDataStream s(&b,QIODevice::WriteOnly);s.setByteOrder(QDataStream::LittleEndian);s.writeRawData("RIFF",4);s<<quint32(32036);s.writeRawData("WAVEfmt ",8);s<<quint32(16)<<quint16(1)<<quint16(1)<<quint32(16000)<<quint32(32000)<<quint16(2)<<quint16(16);s.writeRawData("data",4);s<<quint32(32000);s.writeRawData(QByteArray(32000,'\0').constData(),32000);return b;}
class Fixture:public QTcpServer{
public:
 int requests=0;int audioRequests=0;int audioDownloads=0;bool hold=false;bool lyricFailure=false;bool emptyLyrics=false;QString mode;QByteArray body=wave();
 Fixture(){listen(QHostAddress::LocalHost);connect(this,&QTcpServer::newConnection,this,[this]{while(auto s=nextPendingConnection()){connect(s,&QTcpSocket::disconnected,s,&QObject::deleteLater);connect(s,&QTcpSocket::readyRead,s,[this,s]{auto r=s->property("r").toByteArray()+s->readAll();s->setProperty("r",r);if(!r.contains("\r\n\r\n")||s->property("sent").toBool())return;s->setProperty("sent",true);++requests;auto url=r.split(' ').value(1);if(url.startsWith("/audio")){++audioRequests;if(!r.toLower().contains("range:"))++audioDownloads;}if(hold&&url.startsWith("/audio"))return;QByteArray data=body;int status=200;bool length=true;if(url.contains("lyric"))data=R"({"code":200,"lrc":{"lyric":"[00:00.00]Original"},"tlyric":{"lyric":"[00:00.00]Translation"}})";else if(url.startsWith("/song/url"))data=QJsonDocument(QJsonObject{{"code",200},{"data",QJsonArray{QJsonObject{{"url",base()+"/audio"},{"br",128000},{"type","wav"},{"level","standard"}}}}}).toJson();else if(mode=="html")data="<html>denied</html>";else if(mode=="http")status=403;else if(mode=="partial")status=206;else if(mode=="unknown")length=false;if(url.contains("lyric")&&lyricFailure){status=503;data="failed";}else if(url.contains("lyric")&&emptyLyrics)data=R"({"code":200})";auto declared=data.size()+(mode=="truncated"&&!url.contains("lyric")?100:0);s->write("HTTP/1.1 "+QByteArray::number(status)+" OK\r\nConnection: close\r\n"+(length?"Content-Length: "+QByteArray::number(declared)+"\r\n":QByteArray())+"\r\n"+(mode=="stream"&&url.startsWith("/audio")?data.left(16000):data));if(mode!="stream"||!url.startsWith("/audio"))s->disconnectFromHost();});}});}
 QString base()const{return QString("http://127.0.0.1:%1").arg(serverPort());}
 MusicApi::Endpoints endpoints()const{MusicApi::Endpoints e;e.metadata=base();e.playback=base();e.qqMusicu=base();e.timeoutMs=500;return e;}
 MusicApi::Audio audio()const{MusicApi::Audio a;a.url=QUrl(base()+"/audio");a.quality="standard";a.format="wav";return a;}
};
static QVariantMap track(QString id="123"){return {{"source","netease"},{"songId",id},{"id","netease:"+id},{"name","Offline"}};}
class CacheTests:public QObject{
 Q_OBJECT
private slots:
 void initTestCase(){QStandardPaths::setTestModeEnabled(true);QCoreApplication::setOrganizationName("FloatMusicCacheTests");QCoreApplication::setApplicationName("OfflineCacheTests");QSettings().clear();}
 void cleanup(){QSettings().clear();}
 void downloadAndRestart(){QTemporaryDir dir;Fixture f;QString key;{OfflineCache c(f.endpoints(),dir.path());key=c.enqueue(track(),"lossless",true,f.audio());QTRY_COMPARE(c.entry(key).value("state").toString(),QString("complete"));QTRY_COMPARE(c.entry(key).value("lyricState").toString(),QString("available"));QCOMPARE(c.lookup(track(),"lossless").value("quality").toString(),QString("standard"));}int before=f.requests;f.close();OfflineCache c(f.endpoints(),dir.path());auto hit=c.lookup(track(),"lossless");QVERIFY(!hit.isEmpty());QCOMPARE(hit.value("translation").toString(),QString("[00:00.00]Translation"));QTest::qWait(50);QCOMPARE(f.requests,before);c.setInUse(key);c.remove(key);QVERIFY(c.entry(key).value("pendingDelete").toBool());QVERIFY(c.lookup(track(),"lossless").isEmpty());c.setInUse({});QVERIFY(c.entry(key).isEmpty());}
 void failures_data(){QTest::addColumn<QString>("mode");QTest::newRow("html")<<"html";QTest::newRow("http")<<"http";QTest::newRow("truncated")<<"truncated";QTest::newRow("partial")<<"partial";}
 void failures(){QFETCH(QString,mode);QTemporaryDir d;Fixture f;f.mode=mode;OfflineCache c(f.endpoints(),d.path());auto k=c.enqueue(track(),"standard",true,f.audio());QTRY_COMPARE(c.entry(k).value("state").toString(),QString("failed"));QVERIFY(c.lookup(track(),"standard").isEmpty());QVERIFY(QDir(d.path()).entryList({"*.part","*.audio"},QDir::Files).isEmpty());}
 void cancellation(){QTemporaryDir d;Fixture f;f.hold=true;OfflineCache c(f.endpoints(),d.path());auto k=c.enqueue(track(),"standard",false,f.audio());QTRY_COMPARE(c.entry(k).value("state").toString(),QString("downloading"));c.setSettings({{"automaticEnabled",false}});QCOMPARE(c.entry(k).value("state").toString(),QString("cancelled"));QVERIFY(QDir(d.path()).entryList({"*.part"},QDir::Files).isEmpty());c.remove(k);QTest::qWait(50);QVERIFY(c.entries().isEmpty());}
 void quotaProtection(){QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());c.setSettings({{"automaticLimitBytes",f.body.size()*2}});auto manual=c.enqueue(track("1"),"standard",true,f.audio());QTRY_COMPARE(c.entry(manual).value("state").toString(),QString("complete"));auto a=c.enqueue(track("2"),"standard",false,f.audio());QTRY_COMPARE(c.entry(a).value("state").toString(),QString("complete"));c.setInUse(a);auto b=c.enqueue(track("3"),"standard",false,f.audio());QTRY_COMPARE(c.entry(b).value("state").toString(),QString("complete"));c.setSettings({{"automaticLimitBytes",f.body.size()}});QVERIFY(c.entry(b).isEmpty());QVERIFY(!c.entry(a).isEmpty());auto blocked=c.enqueue(track("4"),"standard",false,f.audio());QTRY_COMPARE(c.entry(blocked).value("state").toString(),QString("failed"));QVERIFY(!c.entry(manual).isEmpty());c.setInUse({});auto next=c.enqueue(track("5"),"standard",false,f.audio());QTRY_COMPARE(c.entry(next).value("state").toString(),QString("complete"));QVERIFY(c.entry(a).isEmpty());c.promote(next);c.clear(false);QCOMPARE(c.entries().size(),2);}
 void unknownLengthAndMissingFile(){QTemporaryDir d;Fixture f;f.mode="unknown";OfflineCache c(f.endpoints(),d.path());auto k=c.enqueue(track(),"standard",true,f.audio());QTRY_COMPARE(c.entry(k).value("state").toString(),QString("complete"));QCOMPARE(c.entry(k).value("total").toLongLong(),qint64(-1));QVERIFY(QFile::remove(c.entry(k).value("path").toString()));QVERIFY(c.lookup(track(),"standard").isEmpty());QCOMPARE(c.entry(k).value("state").toString(),QString("failed"));}
 void dedupPreservesManualAndRequestedMappings(){
  QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());
  auto first=c.enqueue(track(),"hires",true,f.audio());
  auto second=c.enqueue(track(),"exhigh",false,f.audio());
  QCOMPARE(first,second);
  QTRY_COMPARE(c.entry(first).value("state").toString(),QString("complete"));
  QVERIFY(c.entry(first).value("manual").toBool());
  auto requests=c.entry(first).value("requestedQualities").toStringList();
  QVERIFY(requests.contains("hires"));QVERIFY(requests.contains("exhigh"));
  QCOMPARE(c.entries().size(),1);
 }
 void loweringQuotaStopsActiveDownload(){
  QTemporaryDir d;Fixture f;f.mode="stream";OfflineCache c(f.endpoints(),d.path());
  auto k=c.enqueue(track(),"standard",false,f.audio());
  QTRY_VERIFY(c.entry(k).value("bytes").toLongLong()>0);
  c.setSettings({{"automaticLimitBytes",8000}});
  QCOMPARE(c.entry(k).value("state").toString(),QString("failed"));
  QVERIFY(c.entry(k).value("error").toString().contains("capacity"));
  QVERIFY(QDir(d.path()).entryList({"*.part","*.audio"},QDir::Files).isEmpty());
 }
 void indexFailureNeverPublishesReadyAudio(){
  QTemporaryDir d;Fixture f;f.hold=true;OfflineCache c(f.endpoints(),d.path());
  auto k=c.enqueue(track(),"standard",true,f.audio());
  QTRY_COMPARE(c.entry(k).value("state").toString(),QString("downloading"));
  c.cancel(k);QVERIFY(QFile::remove(d.path()+"/index.json"));
  QVERIFY(QDir().mkdir(d.path()+"/index.json"));f.hold=false;c.retry(k);
  QTRY_COMPARE(c.entry(k).value("state").toString(),QString("failed"));
  QVERIFY(c.lookup(track(),"standard").isEmpty());
  QVERIFY(QDir(d.path()).entryList({"*.part","*.audio"},QDir::Files).isEmpty());
 }
 void failedLyricRetryKeepsAvailableCopy(){
  QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());
  auto k=c.enqueue(track(),"standard",true,f.audio());
  QTRY_COMPARE(c.entry(k).value("translationState").toString(),QString("available"));
  const auto original=c.entry(k).value("lyrics").toString();
  const auto translation=c.entry(k).value("translation").toString();
  f.lyricFailure=true;c.retryLyrics(k);
  QTRY_VERIFY(!c.entry(k).value("lyricError").toString().isEmpty());
  QCOMPARE(c.entry(k).value("lyricState").toString(),QString("available"));
  QCOMPARE(c.entry(k).value("translationState").toString(),QString("available"));
  QCOMPARE(c.entry(k).value("lyrics").toString(),original);
  QCOMPARE(c.entry(k).value("translation").toString(),translation);
  f.lyricFailure=false;f.emptyLyrics=true;c.retryLyrics(k);
  QTRY_COMPARE(c.entry(k).value("lyricState").toString(),QString("available"));
  QTest::qWait(80);
  QCOMPARE(c.entry(k).value("lyrics").toString(),original);
  QCOMPARE(c.entry(k).value("translation").toString(),translation);
 }
 void listingDoesNotCopyLyricPayload(){
  QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());
  auto k=c.enqueue(track(),"standard",true,f.audio());
  QTRY_COMPARE(c.entry(k).value("translationState").toString(),QString("available"));
  QVERIFY(!c.entries().first().toMap().contains("lyrics"));
  QVERIFY(!c.entries().first().toMap().contains("translation"));
  QVERIFY(!c.entry(k).value("lyrics").toString().isEmpty());
 }
 void failedPromotionDoesNotPromiseProtection(){
  QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());
  auto k=c.enqueue(track(),"standard",false,f.audio());
  QTRY_COMPARE(c.entry(k).value("translationState").toString(),QString("available"));
  QVERIFY(QFile::remove(d.path()+"/index.json"));QVERIFY(QDir().mkdir(d.path()+"/index.json"));
  c.promote(k);
  QVERIFY(!c.entry(k).value("manual").toBool());
  QVERIFY(c.entry(k).value("error").toString().contains("index"));
 }
 void canonicalizingTaskKeepsQueuedManualCopy(){
  QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());c.setBaseUrl(f.base());
  bool added=false;
  connect(&c,&OfflineCache::changed,&c,[&]{
   if(added)return;
   for(const auto &v:c.entries())if(v.toMap().value("state")=="resolving"){
    added=true;c.enqueue(track(),"lossless",true);break;
   }
  });
  auto k=c.enqueue(track(),"hires",false);
  QTRY_COMPARE(c.entry(k).value("state").toString(),QString("complete"));
  QVERIFY(c.entry(k).value("manual").toBool());
  QVERIFY(c.entry(k).value("requestedQualities").toStringList().contains("lossless"));
  QVERIFY(c.entry(k).value("requestedQualities").toStringList().contains("hires"));
  QCOMPARE(c.entries().size(),1);
 }
 void sameSizedDamageIsRejectedOnUse(){
  QTemporaryDir d;Fixture f;QString key;
  {OfflineCache c(f.endpoints(),d.path());key=c.enqueue(track(),"standard",true,f.audio());
   QTRY_COMPARE(c.entry(key).value("state").toString(),QString("complete"));
   QTRY_COMPARE(c.entry(key).value("lyricState").toString(),QString("available"));}
  QFile audio(d.path()+"/"+key+".audio");QVERIFY(audio.open(QIODevice::ReadWrite));
  QVERIFY(audio.seek(100));QCOMPARE(audio.write("damage"),qint64(6));QVERIFY(audio.flush());
  // Some Windows filesystems coalesce back-to-back write timestamps.
  QVERIFY(audio.setFileTime(QDateTime::currentDateTimeUtc().addSecs(2),QFileDevice::FileModificationTime));audio.close();
  OfflineCache restored(f.endpoints(),d.path());
  QVERIFY(restored.lookup(track(),"standard").isEmpty());
  QCOMPARE(restored.entry(key).value("state").toString(),QString("failed"));
 }
 void explicitResaveCancelsDeferredDelete(){
  QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());
  auto k=c.enqueue(track(),"standard",true,f.audio());
  QTRY_COMPARE(c.entry(k).value("state").toString(),QString("complete"));
  c.setInUse(k);c.remove(k);QVERIFY(c.entry(k).value("pendingDelete").toBool());
  QCOMPARE(c.enqueue(track(),"standard",true,f.audio()),k);
  QVERIFY(!c.entry(k).value("pendingDelete").toBool());c.setInUse({});
  QVERIFY(!c.lookup(track(),"standard").isEmpty());
 }
 void corruptIndexRecoversManagedOrphans(){
  QTemporaryDir d;Fixture f;QString key;
  {OfflineCache c(f.endpoints(),d.path());key=c.enqueue(track(),"standard",true,f.audio());
   QTRY_COMPARE(c.entry(key).value("lyricState").toString(),QString("available"));}
  QFile index(d.path()+"/index.json");QVERIFY(index.open(QIODevice::WriteOnly));index.write("{");index.close();
  QFile unrelated(d.path()+"/keep.audio");QVERIFY(unrelated.open(QIODevice::WriteOnly));unrelated.write("keep");unrelated.close();
  OfflineCache restored(f.endpoints(),d.path());QVERIFY(restored.entries().isEmpty());
  QVERIFY(!QFile::exists(d.path()+"/"+key+".audio"));QVERIFY(QFile::exists(unrelated.fileName()));
  QCOMPARE(restored.summary().value("totalBytes").toLongLong(),qint64(0));
 }
 void resaveDifferentRequestReusesPendingCanonicalFile(){
  QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());c.setBaseUrl(f.base());
  auto audio=f.audio();audio.quality="lossless";
  auto k=c.enqueue(track(),"lossless",true,audio);
  QTRY_COMPARE(c.entry(k).value("lyricState").toString(),QString("available"));
  const auto stamp=c.entry(k).value("modifiedTime");const auto requests=f.audioDownloads;
  c.setInUse(k);c.remove(k);
  const auto requested=c.enqueue(track(),"hires",true);
  QTRY_COMPARE(c.entry(requested).value("state").toString(),QString("complete"));
  QVERIFY(!c.entry(k).value("pendingDelete").toBool());QCOMPARE(f.audioDownloads,requests);
  QCOMPARE(c.entry(k).value("modifiedTime"),stamp);c.setInUse({});
  QVERIFY(!c.lookup(track(),"hires").isEmpty());
 }
 void actualQualityDedup(){QTemporaryDir d;Fixture f;OfflineCache c(f.endpoints(),d.path());c.setBaseUrl(f.base());auto k=c.enqueue(track(),"hires",true);QTRY_COMPARE(c.entry(k).value("state").toString(),QString("complete"));QCOMPARE(c.entry(k).value("key").toString(),OfflineCache::identity(track(),"lossless"));QTRY_COMPARE(c.entry(k).value("lyricState").toString(),QString("available"));auto second=c.enqueue(track(),"exhigh",true);QTRY_COMPARE(c.entry(second).value("state").toString(),QString("complete"));QCOMPARE(c.entries().size(),1);QCOMPARE(c.lookup(track(),"exhigh").value("quality").toString(),QString("lossless"));}
};
QTEST_GUILESS_MAIN(CacheTests)
#include "offlinecache_tests.moc"
