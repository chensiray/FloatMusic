#include <QtTest>
#include <QQuickStyle>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QQuickItem>
#include <QStandardPaths>
#include <QSettings>
#include <QQmlExpression>
#include <QFontDatabase>
#include <QDataStream>
#include <QJsonArray>
#include <QJsonDocument>
#include <QTemporaryDir>
#include <QTcpServer>
#include <QTcpSocket>
#include "playercontroller.h"
class DetailsUiTests:public QObject {
    Q_OBJECT
    QQuickItem *visual(QQuickItem *root,const QString &name){if(root->objectName()==name)return root;for(auto *child:root->childItems())if(auto *found=visual(child,name))return found;return nullptr;}
private slots:
    void initTestCase(){QQuickStyle::setStyle("Basic");QFontDatabase::addApplicationFont("C:/Windows/Fonts/msyh.ttc");QFontDatabase::addApplicationFont("C:/Windows/Fonts/segoeui.ttf");}
    void libraryFiltersAndCurrentOccurrence(){
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QCoreApplication::setApplicationName("Library18-"+QUuid::createUuid().toString(QUuid::WithoutBraces));
        QTemporaryDir directory; QVERIFY(directory.isValid());
        const QString path=directory.filePath("intro.wav");
        QFile audio(path); QVERIFY(audio.open(QIODevice::WriteOnly));
        QDataStream stream(&audio); stream.setByteOrder(QDataStream::LittleEndian);
        const QByteArray samples(32000*6,'\0');
        stream.writeRawData("RIFF",4); stream<<quint32(samples.size()+36);
        stream.writeRawData("WAVEfmt ",8); stream<<quint32(16)<<quint16(1)<<quint16(1)<<quint32(16000)<<quint32(32000)<<quint16(2)<<quint16(16);
        stream.writeRawData("data",4); stream<<quint32(samples.size()); stream.writeRawData(samples.constData(),samples.size()); audio.close();
        const QVariantMap local{{"id","local:intro"},{"source","local"},{"name","Quiet Intro"},{"artist","Night Band"},{"path",path}};
        QVariantList rows;
        for(int i=1;i<=40;++i)rows.append(QVariantMap{{"source","kuwo"},{"songId",QString::number(i)},{"name",QString("Online %1").arg(i)},{"artist","Other"}});
        rows.append(local);
        PlayerController player; player.setVolume(0); player.artwork()->setEnabled(false);
        player.libraryAction("importText",{{"text",QString::fromUtf8(QJsonDocument(QJsonObject{{"name","Library"},{"tracks",QJsonArray::fromVariantList(rows)}}).toJson())},{"target","new"}});
        QCOMPARE(player.tracks().size(),41);
        QQmlApplicationEngine engine; engine.rootContext()->setContextProperty("player",&player);
        engine.load(QUrl::fromLocalFile(FLOATMUSIC_QML_FILE)); QVERIFY(!engine.rootObjects().isEmpty());
        auto *window=qobject_cast<QQuickWindow*>(engine.rootObjects().first()); QVERIFY(window); window->show();
        QVERIFY(QMetaObject::invokeMethod(window,"toggleSection",Q_ARG(QVariant,"playlist")));
        auto *list=window->findChild<QObject*>("playlistView"); QVERIFY(list);
        auto *filter=window->findChild<QObject*>("playlistFilter"); QVERIFY2(filter,"Saved playlist search needs a dedicated field");
        filter->setProperty("text","night quiet"); QTRY_COMPARE(list->property("count").toInt(),1);
        filter->setProperty("text",""); QTRY_COMPARE(list->property("count").toInt(),41);
        auto *offline=window->findChild<QObject*>("playlistOfflineOnly"); QVERIFY(offline);
        QVERIFY(QMetaObject::invokeMethod(offline,"clicked")); QTRY_COMPARE(list->property("count").toInt(),1);
        QTRY_VERIFY(visual(window->contentItem(),"offlineBadge-local:intro"));
        QVERIFY(QMetaObject::invokeMethod(offline,"clicked")); QTRY_COMPARE(list->property("count").toInt(),41);
        player.playTrack("local:intro"); QTRY_VERIFY(player.ready()&&!player.busy()); player.toggle();
        filter->setProperty("text","Online"); QTRY_COMPARE(list->property("count").toInt(),40);
        auto *jump=window->findChild<QObject*>("jumpToCurrentPlaylistTrack"); QVERIFY(jump);
        QVERIFY(QMetaObject::invokeMethod(jump,"clicked")); QTRY_COMPARE(filter->property("text").toString(),QString());
        QTRY_VERIFY(visual(window->contentItem(),"libraryTrack-local:intro"));
        QVERIFY(visual(window->contentItem(),"libraryTrack-local:intro")->property("current").toBool());
        player.enqueueTrack(local,"tail");
        const QString last=player.queueEntries().last().toMap().value("queueId").toString();
        player.playQueueEntry(last); QTRY_COMPARE(player.currentQueueEntry(),last); QTRY_VERIFY(!player.busy()); player.toggle();
        QVERIFY(QMetaObject::invokeMethod(window,"openDetail",Q_ARG(QVariant,"queue")));
        auto *queueJump=window->findChild<QObject*>("jumpToCurrentQueueTrack"); QVERIFY(queueJump);
        QVERIFY(QMetaObject::invokeMethod(queueJump,"clicked"));
        QTRY_VERIFY(visual(window->contentItem(),"queueRow_"+last));
        QVERIFY(visual(window->contentItem(),"queueRow_"+last)->property("current").toBool());
    }
    void artistTabOpensProfileAndWorks() {
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QCoreApplication::setApplicationName("ArtistUi18-"+QUuid::createUuid().toString(QUuid::WithoutBraces));
        QSettings().clear();
        QTcpServer server;
        QVERIFY(server.listen(QHostAddress::LocalHost));
        connect(&server, &QTcpServer::newConnection, &server, [&] {
            while (auto *socket = server.nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [socket] {
                    const auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const auto path = request.split(' ').value(1);
                    QByteArray body;
                    if (path.startsWith("/api/search/get"))
                        body = R"({"code":200,"result":{"artistCount":1,"artists":[{"id":41,"name":"Fixture Singer"}]}})";
                    else if (path.startsWith("/api/artist/introduction"))
                        body = R"({"code":200,"briefDesc":"A fixture biography"})";
                    else if (path.startsWith("/api/v1/artist/songs") && path.contains("offset=0"))
                        body = R"({"code":200,"more":true,"songs":[{"id":993,"name":"Fixture work","artists":[{"name":"Fixture Singer"}],"duration":234000,"privilege":{"st":-200}}]})";
                    else if (path.startsWith("/api/v1/artist/songs") && path.contains("offset=1"))
                        body = R"({"code":200,"more":false,"songs":[{"id":994,"name":"Distant Encore","artists":[{"name":"Fixture Singer"}],"album":{"name":"Moonrise"},"duration":211000}]})";
                    socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                    socket->disconnectFromHost();
                });
            }
        });
        MusicApi::Endpoints endpoints;
        endpoints.metadata = QString("http://127.0.0.1:%1").arg(server.serverPort());
        PlayerController player(endpoints);
        player.setSearchSources({"netease"}); player.artwork()->setEnabled(false);
        player.selectPlaylist("favorites");
        QQmlApplicationEngine engine; engine.rootContext()->setContextProperty("player",&player);
        QStringList warnings;
        connect(&engine,&QQmlEngine::warnings,this,[&](const QList<QQmlError>& errors){for(const auto &error:errors) warnings<<error.toString();});
        engine.load(QUrl::fromLocalFile(FLOATMUSIC_QML_FILE)); QVERIFY(!engine.rootObjects().isEmpty());
        auto *window=qobject_cast<QQuickWindow*>(engine.rootObjects().first()); QVERIFY(window); window->show();
        QVERIFY(QMetaObject::invokeMethod(window,"openDetail",Q_ARG(QVariant,"search")));
        auto *tab=window->findChild<QObject*>("artistSearchTab"); QVERIFY(tab); QVERIFY(QMetaObject::invokeMethod(tab,"clicked"));
        auto *input=window->findChild<QObject*>("searchInput"); QVERIFY(input); input->setProperty("text","Fixture");
        QVERIFY(QMetaObject::invokeMethod(window,"startSearch"));
        auto *results=window->findChild<QObject*>("artistSearchResults"); QVERIFY(results);
        QTRY_COMPARE(results->property("count").toInt(),1);
        QVERIFY(QMetaObject::invokeMethod(results,"positionViewAtEnd"));
        QTRY_VERIFY(visual(window->contentItem(),"artistResult_0"));
        QVERIFY(QMetaObject::invokeMethod(visual(window->contentItem(),"artistResult_0"),"clicked"));
        auto *works=window->findChild<QObject*>("artistWorksList"); QVERIFY(works);
        QTRY_COMPARE(works->property("count").toInt(),1);
        QTRY_VERIFY(visual(window->contentItem(),"unavailableBadge-netease:993"));
        QVERIFY(visual(window->contentItem(),"unavailableBadge-netease:993")->isVisible());
        auto *bio=window->findChild<QObject*>("artistBiography"); QVERIFY(bio);
        QTRY_COMPARE(bio->property("text").toString(),QString("A fixture biography"));
        auto *filter=window->findChild<QObject*>("artistWorkFilter"); QVERIFY(filter);
        auto *showAll=window->findChild<QObject*>("showAllArtistWorks"); QVERIFY(showAll);
        QTRY_VERIFY(showAll->property("visible").toBool());
        filter->setProperty("text","ENCORE moonrise");
        QTRY_VERIFY(works->property("complete").toBool());
        QCOMPARE(works->property("count").toInt(),1);
        QTRY_VERIFY(visual(window->contentItem(),"unavailableBadge-netease:994"));
        QVERIFY(!visual(window->contentItem(),"unavailableBadge-netease:994")->isVisible());
        auto *import=window->findChild<QObject*>("importArtistWorks"); QVERIFY(import);
        QVERIFY(import->property("enabled").toBool());
        QVERIFY(QMetaObject::invokeMethod(import,"clicked"));
        auto *target=window->findChild<QObject*>("playlistTransferTarget"); QVERIFY(target);
        QCOMPARE(target->property("currentValue").toString(),QString("favorites"));
        auto *confirm=window->findChild<QObject*>("confirmPlaylistTransfer"); QVERIFY(confirm);
        QVERIFY(QMetaObject::invokeMethod(confirm,"clicked"));
        QTRY_COMPARE(player.tracks().size(),1);
        QCOMPARE(player.tracks().first().toMap().value("id").toString(),QString("netease:994"));
        filter->setProperty("text","");
        QTRY_COMPARE(works->property("count").toInt(),2);
        QVERIFY(QMetaObject::invokeMethod(import,"clicked"));
        target->setProperty("currentIndex",target->property("count").toInt()-1);
        QCOMPARE(target->property("currentValue").toString(),QString("new"));
        QVERIFY(QMetaObject::invokeMethod(confirm,"clicked"));
        QTRY_COMPARE(player.tracks().size(),2);
        QCOMPARE(player.tracks().first().toMap().value("id").toString(),QString("netease:993"));
        window->grabWindow().save("artist-profile.png");
        auto *back=window->findChild<QObject*>("artistProfileBack"); QVERIFY(back); QVERIFY(QMetaObject::invokeMethod(back,"clicked"));
        QTRY_VERIFY(!window->property("artistProfileOpen").toBool());
        QCOMPARE(results->property("count").toInt(),1);
        QVERIFY2(warnings.isEmpty(),qPrintable(warnings.join('\n')));
    }
    void everySongRowHasArtworkAndCanHideIt(){
        QStandardPaths::setTestModeEnabled(true);QCoreApplication::setOrganizationName("FloatMusicTests");QCoreApplication::setApplicationName("ArtworkUi");QSettings().clear();
        PlayerController p;QQmlApplicationEngine e;e.rootContext()->setContextProperty("player",&p);
        e.load(QUrl::fromLocalFile(FLOATMUSIC_QML_FILE));QVERIFY(!e.rootObjects().isEmpty());auto *w=qobject_cast<QQuickWindow*>(e.rootObjects().first());QVERIFY(w);w->show();
        p.libraryAction("importText",{{"text","{\"name\":\"Artwork\",\"tracks\":[{\"id\":\"kuwo:124\",\"source\":\"kuwo\",\"songId\":\"124\",\"name\":\"Playlist artwork\"}]}"},{"target","new"}});
        QCOMPARE(p.tracks().size(),1);
        QVERIFY(QMetaObject::invokeMethod(w,"toggleSection",Q_ARG(QVariant,"playlist")));QTest::qWait(150);
        auto *list=w->findChild<QObject*>("playlistView");QVERIFY(list);QVERIFY(QMetaObject::invokeMethod(list,"positionViewAtEnd"));
        QTRY_VERIFY(visual(w->contentItem(),"songCover-kuwo:124"));
        auto *playlistCover=visual(w->contentItem(),"songCover-kuwo:124");QVERIFY(playlistCover->isVisible());
        p.enqueueTrack({{"id","local:one"},{"source","local"},{"name","No artwork"}},"tail");
        p.enqueueTrack({{"id","kuwo:123"},{"source","kuwo"},{"songId","123"},{"name","Missing cover"}},"tail");
        QVERIFY(QMetaObject::invokeMethod(w,"openDetail",Q_ARG(QVariant,"queue")));QTest::qWait(150);
        auto *local=visual(w->contentItem(),"songCover-local:one");auto *online=visual(w->contentItem(),"songCover-kuwo:123");
        QVERIFY(local);QVERIFY(online);QVERIFY(local->isVisible());QVERIFY(online->isVisible());QVERIFY(local->width()>=40);
        p.artwork()->setEnabled(false);QTRY_VERIFY(!local->isVisible());QVERIFY(!online->isVisible());
        p.artwork()->setEnabled(true);QTRY_VERIFY(local->isVisible());
        for(bool dark:{false,true}){
            QQmlExpression theme(QQmlEngine::contextForObject(w),w,dark?"appearance.mode = 2":"appearance.mode = 1");theme.evaluate();QVERIFY(!theme.hasError());
            w->grabWindow();QTest::qWait(100);w->grabWindow().save(QString("songs-%1.png").arg(dark?"dark":"light"));
        }
        QVERIFY(QMetaObject::invokeMethod(w,"openDetail",Q_ARG(QVariant,"settings")));QVERIFY(QMetaObject::invokeMethod(w,"openSettingsPage",Q_ARG(QVariant,"general")));
        auto *toggle=w->findChild<QObject*>("songCoversSwitch");QVERIFY(toggle);QVERIFY(toggle->property("checked").toBool());
    }
    void detailsEntryAndNarrowThemes(){
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setOrganizationName("FloatMusicTests");QCoreApplication::setApplicationName("DetailsUi");
        QSettings().clear();
        QFontDatabase::addApplicationFont("C:/Windows/Fonts/msyh.ttc");QFontDatabase::addApplicationFont("C:/Windows/Fonts/segoeui.ttf");
        PlayerController p;QQmlApplicationEngine e;e.rootContext()->setContextProperty("player",&p);
        QStringList warnings;connect(&e,&QQmlEngine::warnings,this,[&](const QList<QQmlError>& errors){for(const auto &v:errors)warnings<<v.toString();});
        e.load(QUrl::fromLocalFile(FLOATMUSIC_QML_FILE));QVERIFY(!e.rootObjects().isEmpty());
        auto *w=qobject_cast<QQuickWindow*>(e.rootObjects().first());QVERIFY(w);
        QVERIFY2(w->findChild<QObject*>("currentMusicDetails"),"Current song needs a discoverable music details entry");
        const QVariant track=QVariantMap{{"source","local"},{"name",QStringLiteral("长歌曲名称用于检查窄窗口换行")}};
        QVERIFY(QMetaObject::invokeMethod(w,"openMusicDetails",Q_ARG(QVariant,track)));
        QTRY_VERIFY(w->findChild<QObject*>("musicCommentsList"));
        QTRY_VERIFY2(w->height()>=500,"Opening details from the compact player must provide enough reading space");
        if(qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_DETAILS")){
            p.musicDetails()->action("open",{{"track",QVariantMap{{"id","netease:186016"},{"source","netease"},{"songId","186016"},{"name",QStringLiteral("晴天")},{"artist",QStringLiteral("周杰伦")}}}});
            QTRY_VERIFY_WITH_TIMEOUT(!p.musicDetails()->state().value("loading").toBool(),40000);
            QVERIFY2(p.musicDetails()->state().value("error").toString().isEmpty(),qPrintable(p.musicDetails()->state().value("error").toString()));
            QVERIFY(!p.musicDetails()->state().value("comments").toList().isEmpty());
            auto *view=w->findChild<QObject*>("musicCommentsList");QVERIFY(view);
            QTRY_VERIFY2(qAbs(view->property("contentY").toDouble()-view->property("originY").toDouble())<1,"Newly loaded details must start above the song artwork and title");
        }
        for(bool dark:{false,true}){
            QQmlExpression theme(QQmlEngine::contextForObject(w),w,dark?"appearance.mode = 2":"appearance.mode = 1");theme.evaluate();
            QVERIFY2(!theme.hasError(),qPrintable(theme.error().toString()));QCOMPARE(w->property("darkMode").toBool(),dark);w->resize(340,680);QTest::qWait(100);
            QVERIFY(w->findChild<QObject*>("closeMusicDetails"));
            if(qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_DETAILS")){
                auto *view=w->findChild<QObject*>("musicCommentsList");
                auto *header=view->property("headerItem").value<QQuickItem*>();
                if(header)qInfo()<<"Detail header geometry"<<view->property("contentY")<<view->property("originY")<<header->y()<<header->height();
                QTRY_VERIFY2(qAbs(view->property("contentY").toDouble()-view->property("originY").toDouble())<1,"Initial details header stays visible after narrow layout settles");
            }
            w->grabWindow().save(QString("details-%1.png").arg(dark?"dark":"light"));
        }
        QVERIFY2(warnings.isEmpty(),qPrintable(warnings.join('\n')));
    }
};
QTEST_MAIN(DetailsUiTests)
#include "musicdetails_ui_tests.moc"
