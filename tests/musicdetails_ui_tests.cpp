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
#include "playercontroller.h"
class DetailsUiTests:public QObject {
    Q_OBJECT
    QQuickItem *visual(QQuickItem *root,const QString &name){if(root->objectName()==name)return root;for(auto *child:root->childItems())if(auto *found=visual(child,name))return found;return nullptr;}
private slots:
    void initTestCase(){QQuickStyle::setStyle("Basic");QFontDatabase::addApplicationFont("C:/Windows/Fonts/msyh.ttc");QFontDatabase::addApplicationFont("C:/Windows/Fonts/segoeui.ttf");}
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
