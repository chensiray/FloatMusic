#include <QtTest>
#include <QClipboard>
#include <QGuiApplication>
#include <QFontDatabase>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QDir>
#include <QMimeData>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QSettings>
#include <QStandardPaths>
#include <QStyleHints>
#include <QTcpServer>
#include <QTcpSocket>
#include <memory>
#include "playercontroller.h"

// Only the HTTP boundary is replaced. Parsing, controller, QML pointer gestures,
// and the native clipboard are production code.
class CopyApiFixture : public QTcpServer {
public:
    QString original = QStringLiteral("[ar:作者]\n[00:00.00]收藏夹 <b>原文</b> 🎵\n[00:04.00]第二句\n[00:08.00]最后一句");
    QString translation = QStringLiteral("[00:00.00]Keep <b>words</b> 🎵\n[00:04.00]Second line");
    QString description = QStringLiteral("收藏夹 <b>原样</b> 🎵 ") + QStringLiteral("横向也显示不全的很长简介。").repeated(8)
        + "\n" + QStringLiteral("很长的歌单简介，完整保留所有内容。\n").repeated(18) + QStringLiteral("最后一个字。");
    CopyApiFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    const auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const auto path = QUrl(QString::fromUtf8(request.split(' ').value(1))).path();
                    QJsonObject response{{"code", 200}};
                    if (path.endsWith("/lyric")) {
                        response.insert("lrc", QJsonObject{{"lyric", original}});
                        response.insert("tlyric", QJsonObject{{"lyric", translation}});
                    } else if (path.endsWith("/playlist/detail")) {
                        response.insert("playlist", QJsonObject{{"id", 243}, {"name", QStringLiteral("复制测试")},
                            {"description", description}, {"trackCount", 1},
                            {"trackIds", QJsonArray{QJsonObject{{"id", 101}}}},
                            {"tracks", QJsonArray{QJsonObject{{"id", 101}, {"name", "Song"}}}}});
                    } else if (path.endsWith("/search") || path.endsWith("/toplist")) {
                        const QJsonArray rows{QJsonObject{{"id", 243}, {"name", "Copy card"},
                            {"description", description}, {"trackCount", 1},
                            {"creator", QJsonObject{{"nickname", "Creator only"}}}}};
                        if (path.endsWith("/toplist")) response.insert("list", rows);
                        else response.insert("result", QJsonObject{{"playlistCount", 1}, {"playlists", rows}});
                    } else response.insert("songs", QJsonArray{});
                    const auto body = QJsonDocument(response).toJson();
                    socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                    socket->disconnectFromHost();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
};

static QQuickItem *visualItem(QQuickItem *root, const QString &name) {
    if (!root) return nullptr;
    if (root->objectName() == name) return root;
    for (auto *child : root->childItems())
        if (auto *found = visualItem(child, name)) return found;
    return nullptr;
}

static QQuickItem *visibleText(QQuickItem *root, const QString &text) {
    if (!root || !root->isVisible()) return nullptr;
    if (root->property("text").toString() == text) return root;
    for (auto *child : root->childItems())
        if (auto *found = visibleText(child, text)) return found;
    return nullptr;
}

class CopyUiTests : public QObject {
    Q_OBJECT
    std::unique_ptr<CopyApiFixture> api;
    std::unique_ptr<PlayerController> player;
    std::unique_ptr<QQmlApplicationEngine> engine;
    QQuickWindow *window = nullptr;
    QList<QPair<QClipboard::Mode, QMap<QString, QByteArray>>> savedClipboard;
    QString previousName;
    const QString sentinel = QStringLiteral("existing clipboard — 保留我");

    bool loadWindow(const QString &section) {
        engine = std::make_unique<QQmlApplicationEngine>();
        engine->rootContext()->setContextProperty("player", player.get());
        engine->load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        if (engine->rootObjects().size() != 1) return false;
        window = qobject_cast<QQuickWindow *>(engine->rootObjects().first());
        if (!window) return false;
        window->setProperty("section", section);
        window->show();
        return QTest::qWaitForWindowExposed(window);
    }
    void hold(QQuickItem *item) {
        const QPoint point = item->mapToScene(QPointF(qMin(20.0, item->width() / 2), item->height() / 2)).toPoint();
        QTest::mousePress(window, Qt::LeftButton, Qt::NoModifier, point);
        QTest::qWait(QGuiApplication::styleHints()->mousePressAndHoldInterval() + 160);
        QTest::mouseRelease(window, Qt::LeftButton, Qt::NoModifier, point);
        QCoreApplication::processEvents();
    }
    bool setMode(int mode) {
        auto *selector = window->findChild<QObject *>("lyricModeSelector");
        return selector && QMetaObject::invokeMethod(selector, "activated", Q_ARG(int, mode));
    }
    void fetchLyrics() { player->retryLyrics(); }

private slots:
    void initTestCase() {
        // The offscreen platform does not discover Windows system fonts.
        QFontDatabase::addApplicationFont("C:/Windows/Fonts/msyh.ttc");
        QFontDatabase::addApplicationFont("C:/Windows/Fonts/segoeui.ttf");
        QGuiApplication::setFont(QFont("Microsoft YaHei UI"));
        previousName = QCoreApplication::applicationName();
        auto *clipboard = QGuiApplication::clipboard();
        QList<QClipboard::Mode> modes{QClipboard::Clipboard};
        if (clipboard->supportsSelection()) modes.append(QClipboard::Selection);
        if (clipboard->supportsFindBuffer()) modes.append(QClipboard::FindBuffer);
        for (auto mode : modes) {
            QMap<QString, QByteArray> snapshot;
            if (const auto *mime = clipboard->mimeData(mode))
                for (const auto &format : mime->formats()) snapshot.insert(format, mime->data(format));
            savedClipboard.append({mode, snapshot});
        }
    }
    void cleanupTestCase() {
        for (const auto &entry : savedClipboard) {
            auto *mime = new QMimeData;
            for (auto it = entry.second.cbegin(); it != entry.second.cend(); ++it) mime->setData(it.key(), it.value());
            QGuiApplication::clipboard()->setMimeData(mime, entry.first);
        }
        QCoreApplication::setApplicationName(previousName);
    }
    void init() {
        QCoreApplication::setApplicationName("ui-copy-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
        const QVariantMap track{{"id", "netease:101"}, {"source", "netease"}, {"songId", "101"}, {"name", "Song"}, {"artist", "Artist"}};
        QSettings().setValue("playback/session", QJsonDocument(QJsonObject::fromVariantMap(
            {{"track", track}, {"position", 0}, {"duration", 60000}})).toJson());
        api = std::make_unique<CopyApiFixture>();
        QVERIFY(api->listen(QHostAddress::LocalHost));
        player = std::make_unique<PlayerController>();
        player->setApiBase(api->base());
        QTRY_COMPARE(player->currentTrack(), QString("netease:101"));
        QGuiApplication::clipboard()->setText(sentinel);
    }
    void cleanup() {
        engine.reset(); window = nullptr; player.reset(); api.reset();
        QSettings().clear();
    }
    void timedLongPressCopiesWholeSong_data() {
        QTest::addColumn<int>("mode"); QTest::addColumn<QString>("expected");
        QTest::newRow("original") << 0 << QStringLiteral("收藏夹 <b>原文</b> 🎵\n第二句\n最后一句");
        QTest::newRow("translation-with-missing-line") << 1 << QStringLiteral("Keep <b>words</b> 🎵\nSecond line");
        QTest::newRow("bilingual") << 2 << QStringLiteral("收藏夹 <b>原文</b> 🎵\nKeep <b>words</b> 🎵\n第二句\nSecond line\n最后一句");
    }
    void timedLongPressCopiesWholeSong() {
        QFETCH(int, mode); QFETCH(QString, expected);
        fetchLyrics(); QTRY_VERIFY(!player->lyricsLoading()); QCOMPARE(player->lyricLines().size(), 3);
        QVERIFY(loadWindow("lyrics")); QVERIFY(setMode(mode));
        auto *view = window->findChild<QQuickItem *>("timedLyrics"); QVERIFY(view);
        QQuickItem *first = nullptr; QTRY_VERIFY((first = visualItem(view, "lyricOriginal-0")));
        hold(first);
        QTRY_COMPARE(QGuiApplication::clipboard()->text(), expected);
        auto *feedback = window->findChild<QQuickItem *>("copyFeedback");
        QVERIFY(feedback && feedback->isVisible());
    }
    void plainLongPressCopiesWholeSong_data() {
        QTest::addColumn<int>("mode"); QTest::addColumn<QString>("expected");
        QTest::newRow("original") << 0 << QStringLiteral("收藏夹 <b>原文</b> 🎵\n第二句\n最后一句");
        QTest::newRow("translation") << 1 << QStringLiteral("Keep <b>words</b> 🎵\nSecond line");
        QTest::newRow("bilingual") << 2 << QStringLiteral("收藏夹 <b>原文</b> 🎵\n第二句\n最后一句\n\nKeep <b>words</b> 🎵\nSecond line");
    }
    void plainLongPressCopiesWholeSong() {
        QFETCH(int, mode); QFETCH(QString, expected);
        api->original = QStringLiteral("[AR:作者]\n收藏夹 <b>原文</b> 🎵\n第二句\n最后一句");
        api->translation = QStringLiteral("Keep <b>words</b> 🎵\nSecond line");
        fetchLyrics(); QTRY_VERIFY(!player->lyricsLoading()); QVERIFY(player->lyricLines().isEmpty());
        QVERIFY(loadWindow("lyrics")); QVERIFY(setMode(mode));
        auto *plain = window->findChild<QQuickItem *>("lyricsText"); QVERIFY(plain); QTRY_VERIFY(plain->isVisible());
        hold(plain);
        QTRY_COMPARE(QGuiApplication::clipboard()->text(), expected);
    }
    void emptyLyricsAndMissingTranslationPreserveClipboard() {
        api->original.clear(); api->translation.clear();
        fetchLyrics(); QTRY_VERIFY(!player->lyricsLoading());
        QVERIFY(loadWindow("lyrics")); QVERIFY(setMode(1));
        auto *plain = window->findChild<QQuickItem *>("lyricsText"); QVERIFY(plain);
        hold(plain); QCOMPARE(QGuiApplication::clipboard()->text(), sentinel);
        api->original = QStringLiteral("[00:00.00]只有原文");
        fetchLyrics(); QTRY_VERIFY(!player->lyricsLoading());
        auto *view = window->findChild<QQuickItem *>("timedLyrics"); QVERIFY(view);
        QQuickItem *first = nullptr; QTRY_VERIFY((first = visualItem(view, "lyricOriginal-0")));
        hold(first); QCOMPARE(QGuiApplication::clipboard()->text(), sentinel);
    }
    void scrollingLyricsDoesNotCopy() {
        api->original.clear(); api->translation.clear();
        for (int i = 0; i < 30; ++i) api->original += QString("[00:%1.00]可以滚动的一整首歌词 %2\n").arg(i, 2, 10, QLatin1Char('0')).arg(i);
        fetchLyrics(); QTRY_VERIFY(!player->lyricsLoading());
        QVERIFY(loadWindow("lyrics"));
        auto *view = window->findChild<QQuickItem *>("timedLyrics"); QVERIFY(view);
        QTRY_VERIFY(view->property("contentHeight").toDouble() > view->height());
        const QPoint start = view->mapToScene(QPointF(view->width()/2, view->height()*.7)).toPoint();
        const double initial = view->property("contentY").toDouble();
        QTest::mousePress(window, Qt::LeftButton, Qt::NoModifier, start);
        for (int step = 1; step <= 6; ++step) QTest::mouseMove(window, start - QPoint(0, step*18), 35);
        QTest::qWait(QGuiApplication::styleHints()->mousePressAndHoldInterval() + 160);
        QTest::mouseRelease(window, Qt::LeftButton, Qt::NoModifier, start - QPoint(0, 108));
        QVERIFY(qAbs(view->property("contentY").toDouble() - initial) > 30);
        QCOMPARE(QGuiApplication::clipboard()->text(), sentinel);
    }
    void localDescriptionCopiesFullOriginalWithoutOpeningEditor() {
        api->description = QStringLiteral("收藏夹 <b>原样</b> 🎵 ") + QStringLiteral("横向显示不全的本地歌单完整原文。").repeated(18) + QStringLiteral("最后一个字。");
        player->libraryAction("describe", {{"description", api->description}});
        QVERIFY(loadWindow("playlist"));
        QQuickItem *text = nullptr; QTRY_VERIFY((text = visibleText(window->contentItem(), api->description)));
        QTRY_VERIFY(text->property("truncated").toBool());
        hold(text);
        QTRY_COMPARE(QGuiApplication::clipboard()->text(), api->description);
        QVERIFY(!window->property("sheetOpen").toBool());
        QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier, text->mapToScene(QPointF(20, text->height()/2)).toPoint());
        QTRY_VERIFY(window->property("sheetOpen").toBool());
    }
    void onlineDescriptionCopiesFullOriginal() {
        // Remote metadata is already decoded/sanitized by CatalogCodec. Use plain
        // user text here; localDescription also covers literal markup preservation.
        api->description = QStringLiteral("收藏夹 原样 🎵 ") + QStringLiteral("横向也显示不全的很长简介。").repeated(8)
            + "\n" + QStringLiteral("很长的歌单简介，完整保留所有内容。\n").repeated(18) + QStringLiteral("最后一个字。");
        player->libraryAction("openOnline", {{"id", "243"}});
        QTRY_VERIFY(!player->onlinePlaylistLoading());
        QCOMPARE(player->onlinePlaylist().value("description").toString(), api->description);
        QVERIFY(loadWindow("more")); window->setProperty("showingOnlinePlaylist", true);
        QQuickItem *text = nullptr; QTRY_VERIFY((text = visibleText(window->contentItem(), api->description)));
        QTRY_VERIFY(text->property("truncated").toBool());
        hold(text);
        QTRY_COMPARE(QGuiApplication::clipboard()->text(), api->description);
    }
    void emptyDescriptionPreservesClipboardAndLongPressDoesNotEdit() {
        QVERIFY(loadWindow("playlist"));
        QQuickItem *text = nullptr; QTRY_VERIFY((text = visibleText(window->contentItem(), QStringLiteral("点击添加歌单简介"))));
        hold(text);
        QCOMPARE(QGuiApplication::clipboard()->text(), sentinel);
        QVERIFY(!window->property("sheetOpen").toBool());
    }
    void descriptionsExpandCollapseAndReset() {
        const QString content=QStringLiteral("完整简介，保留换行。\n").repeated(12)+QStringLiteral("最后一段。");
        player->libraryAction("describe",{{"description",content}});
        QVERIFY(loadWindow("playlist"));
        auto *text=visualItem(window->contentItem(),"localPlaylistDescription");
        auto *toggle=visualItem(window->contentItem(),"localDescriptionToggle");QVERIFY(text);QVERIFY(toggle);
        QTRY_VERIFY(toggle->isVisible());QVERIFY(text->property("truncated").toBool());
        const auto path=qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");if(!path.isEmpty()){QDir().mkpath(path);QVERIFY(window->grabWindow().save(path+"/description-collapsed.png"));}
        QTest::mouseClick(window,Qt::LeftButton,Qt::NoModifier,toggle->mapToScene(QPointF(toggle->width()/2,toggle->height()/2)).toPoint());
        QTRY_VERIFY(!text->property("truncated").toBool());QCOMPARE(text->property("text").toString(),content);QVERIFY(!window->property("sheetOpen").toBool());
        if(!path.isEmpty())QVERIFY(window->grabWindow().save(path+"/description-expanded.png"));
        QVERIFY(QMetaObject::invokeMethod(toggle,"clicked"));QTRY_VERIFY(text->property("truncated").toBool());
        QVERIFY(QMetaObject::invokeMethod(toggle,"clicked"));QTRY_VERIFY(!text->property("truncated").toBool());
        player->libraryAction("describe",{{"description",QStringLiteral("短简介")}});
        QTRY_VERIFY(!toggle->isVisible());QCOMPARE(text->property("maximumLineCount").toInt(),2);
        player->libraryAction("openOnline",{{"id","243"}});QTRY_VERIFY(!player->onlinePlaylistLoading());
        window->setProperty("section","more");window->setProperty("showingOnlinePlaylist",true);
        auto *remote=visualItem(window->contentItem(),"onlinePlaylistDescription");auto *remoteToggle=visualItem(window->contentItem(),"onlineDescriptionToggle");QVERIFY(remote);QVERIFY(remoteToggle);
        QTRY_VERIFY(remoteToggle->isVisible());QVERIFY(remote->property("truncated").toBool());
        QVERIFY(QMetaObject::invokeMethod(remoteToggle,"clicked"));QTRY_VERIFY(!remote->property("truncated").toBool());
        QVERIFY(QMetaObject::invokeMethod(remoteToggle,"clicked"));QTRY_VERIFY(remote->property("truncated").toBool());
    }
    void settingsHaveCategoryPagesAndBackNavigation() {
        QVERIFY(loadWindow("more"));window->setProperty("detail","settings");
        auto *index=visualItem(window->contentItem(),"settingsIndex");auto *general=visualItem(window->contentItem(),"settingsCategory_general");auto *back=visualItem(window->contentItem(),"settingsBack");QVERIFY(index);QVERIFY(general);QVERIFY(back);
        QTRY_VERIFY(index->isVisible());QTRY_VERIFY(general->width()>100&&general->height()>=48);QTest::qWait(80);QVERIFY(!window->findChild<QQuickItem *>("themeSelector")->isVisible());
        QTest::mouseClick(window,Qt::LeftButton,Qt::NoModifier,general->mapToScene(QPointF(general->width()/2,general->height()/2)).toPoint());
        QTRY_COMPARE(window->property("settingsPage").toString(),QString("general"));QVERIFY(!index->isVisible());
        QVERIFY(window->findChild<QQuickItem *>("languageSelector")->isVisible());QVERIFY(!window->findChild<QQuickItem *>("desktopLyricRowSelector")->isVisible());
        QTest::mouseClick(window,Qt::LeftButton,Qt::NoModifier,back->mapToScene(QPointF(back->width()/2,back->height()/2)).toPoint());QTRY_VERIFY(index->isVisible());
        const auto path=qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        auto *theme=window->findChild<QObject *>("themeSelector");QVERIFY(theme);
        for(const auto &language:{QString("zh_CN"),QString("en")})for(int mode:{1,2}){
            QVERIFY(QMetaObject::invokeMethod(window,"setUiLanguage",Q_ARG(QVariant,language)));QVERIFY(QMetaObject::invokeMethod(theme,"activated",Q_ARG(int,mode)));QTest::qWait(60);
            if(!path.isEmpty()){QDir().mkpath(path);QVERIFY(window->grabWindow().save(path+"/settings-index-"+language+QString::number(mode)+".png"));}
        }
        window->setProperty("settingsPage","floating");QTRY_VERIFY(window->findChild<QQuickItem *>("desktopLyricRowSelector")->isVisible());QVERIFY(!window->findChild<QQuickItem *>("languageSelector")->isVisible());
        QTest::keyClick(window,Qt::Key_Escape);QTRY_COMPARE(window->property("settingsPage").toString(),QString());QCOMPARE(window->property("detail").toString(),QString("settings"));
        window->setProperty("settingsPage","service");QVERIFY(window->findChild<QQuickItem *>("searchResultLimitSelector")->isVisible());
        window->setProperty("detail","search");QCOMPARE(window->property("settingsPage").toString(),QString());
    }
    void descriptionCardsLongPressWithoutNavigating_data() {
        QTest::addColumn<bool>("ranking"); QTest::addColumn<bool>("hasDescription");
        QTest::newRow("search-description") << false << true;
        QTest::newRow("search-placeholder") << false << false;
        QTest::newRow("ranking-description") << true << true;
        QTest::newRow("ranking-creator-only") << true << false;
    }
    void descriptionCardsLongPressWithoutNavigating() {
        QFETCH(bool, ranking); QFETCH(bool, hasDescription);
        api->description = hasDescription ? QStringLiteral("收藏夹原文 🎵 ") + QStringLiteral("超长卡片简介原样复制 ").repeated(18) + QStringLiteral("尾字") : QString();
        player->setSearchSources({"netease"});
        if (ranking) {
            player->libraryAction("loadRankings", {});
            QTRY_VERIFY(!player->rankingsLoading()); QCOMPARE(player->rankings().size(), 1);
        } else {
            player->libraryAction("searchPlaylists", {{"query", "copy"}});
            QTRY_VERIFY(!player->playlistSearching()); QCOMPARE(player->playlistResults().size(), 1);
        }
        QVERIFY(loadWindow("more"));
        window->setProperty("detail", ranking ? "rankings" : "search");
        window->setProperty("searchKind", "playlists");
        const QString shown = hasDescription ? api->description : ranking ? QString("Creator only") : QStringLiteral("暂无简介");
        auto *view = window->findChild<QQuickItem *>(ranking ? "rankingsView" : "playlistSearchResults"); QVERIFY(view);
        QQuickItem *text = nullptr; QTRY_VERIFY((text = visibleText(view, shown)));
        // Inline headers can initially put the first card below the viewport.
        const double top = view->property("contentY").toDouble() + text->mapToItem(view, QPointF()).y();
        const double minimum = view->property("originY").toDouble();
        const double maximum = minimum + qMax(0.0, view->property("contentHeight").toDouble() - view->height());
        // Native Windows can expose a taller viewport than offscreen. Never put
        // an already-visible card out of bounds: its rebound consumes the next tap.
        view->setProperty("contentY", qBound(minimum, top - 35, maximum));
        QTRY_VERIFY(!view->property("moving").toBool());
        hold(text);
        QTRY_COMPARE(QGuiApplication::clipboard()->text(), hasDescription ? api->description : sentinel);
        QVERIFY(!window->property("showingOnlinePlaylist").toBool());
        QTRY_VERIFY(!view->property("moving").toBool());
        QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier, text->mapToScene(QPointF(20, text->height()/2)).toPoint());
        QTRY_VERIFY(window->property("showingOnlinePlaylist").toBool());
    }
};

int main(int argc, char **argv) {
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("FloatMusicTests");
    QStandardPaths::setTestModeEnabled(true);
    QQuickStyle::setStyle("Basic");
    CopyUiTests tests;
    return QTest::qExec(&tests, argc, argv);
}
#include "copy_ui_tests.moc"
