#include <QtTest>
#include <QFontDatabase>
#include <QDataStream>
#include <QFile>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QSettings>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTcpServer>
#include <QTcpSocket>
#include "playercontroller.h"

static QQuickItem *visualItem(QQuickItem *root, const QString &name) {
    if (!root) return nullptr;
    if (root->objectName() == name) return root;
    for (auto *child : root->childItems())
        if (auto *found = visualItem(child, name)) return found;
    return nullptr;
}

static bool click(QQuickWindow *window, const QString &name) {
    auto *item = visualItem(window->contentItem(), name);
    if (!item || !item->isVisible() || !item->isEnabled()) return false;
    const auto point = item->mapToScene(QPointF(item->width() / 2, item->height() / 2)).toPoint();
    if (!QRect(QPoint(0, 0), window->size()).contains(point)) return false;
    QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier, point);
    QCoreApplication::processEvents();
    return true;
}

static bool capture(QQuickWindow *window, const QString &name) {
    const auto directory = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
    if (directory.isEmpty()) return true;
    QDir().mkpath(directory);
    QTest::qWait(50); // Let Qt Quick present the state being inspected.
    return window->grabWindow().save(directory + "/" + name + ".png");
}

static QVariantMap shortcutRow(QObject *shortcuts, const QString &action) {
    if (!shortcuts) return {};
    for (const auto &value : shortcuts->property("bindings").toList()) {
        const auto row = value.toMap();
        if (row.value("action").toString() == action) return row;
    }
    return {};
}

static void reveal(QQuickItem *item) {
    if (!item) return;
    for(auto *group=item->parentItem();group;group=group->parentItem())if(group->property("settingsKey").isValid()){
        item->window()->setProperty("settingsPage",group->property("settingsKey"));QTest::qWait(40);break;
    }
    QTest::qWait(30); // Rebuilt model rows need a layout frame before their scroll position is known.
    for (auto *ancestor = item->parentItem(); ancestor; ancestor = ancestor->parentItem()) {
        if (!ancestor->property("contentY").isValid()) continue;
        const auto point = item->mapToItem(ancestor, QPointF(0, 0));
        const qreal contentY = ancestor->property("contentY").toDouble();
        const qreal contentHeight = ancestor->property("contentHeight").toDouble();
        const qreal desired = contentY + point.y() - 10;
        ancestor->setProperty("contentY", qBound(0.0, desired, qMax(0.0, contentHeight - ancestor->height())));
        QCoreApplication::processEvents();
    }
}

// Replace only remote HTTP responses; every menu operates the real controller.
class UiCatalogFixture : public QTcpServer {
public:
    UiCatalogFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [socket] {
                    const auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const auto path = QUrl(QString::fromUtf8(request.split(' ').value(1))).path();
                    QByteArray body;
                    if (path.endsWith("/cloudsearch"))
                        body = R"({"code":200,"result":{"songCount":1,"songs":[{"id":101,"name":"Search song","dt":180000,"ar":[{"name":"Fixture artist"}]}]}})";
                    else if (path.endsWith("/playlist/detail"))
                        body = R"({"code":200,"playlist":{"id":243,"name":"Fixture playlist","trackCount":1,"trackIds":[{"id":104}],"tracks":[{"id":104,"name":"Online song","dt":180000,"ar":[{"name":"Fixture artist"}]}]}})";
                    else body = R"({"code":200,"songs":[]})";
                    socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                  + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                    socket->disconnectFromHost();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
};

static bool writeWave(const QString &path) {
    QFile file(path);
    if (!file.open(QIODevice::WriteOnly)) return false;
    QDataStream stream(&file);
    stream.setByteOrder(QDataStream::LittleEndian);
    const quint32 bytes = 16000 * 2 * 30;
    stream.writeRawData("RIFF", 4); stream << quint32(36 + bytes);
    stream.writeRawData("WAVEfmt ", 8); stream << quint32(16) << quint16(1) << quint16(1)
        << quint32(16000) << quint32(32000) << quint16(2) << quint16(16);
    stream.writeRawData("data", 4); stream << bytes;
    return stream.writeRawData(QByteArray(bytes, '\0').constData(), bytes) == int(bytes);
}

class V12UiTests : public QObject {
    Q_OBJECT
private slots:
    void initTestCase() {
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QStandardPaths::setTestModeEnabled(true);
        QQuickStyle::setStyle("Basic");
        QFontDatabase::addApplicationFont("C:/Windows/Fonts/msyh.ttc");
        QFontDatabase::addApplicationFont("C:/Windows/Fonts/segoeui.ttf");
        QGuiApplication::setFont(QFont("Microsoft YaHei UI"));
    }
    void init() {
        QCoreApplication::setApplicationName("v12-ui-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
        QSettings settings;
        settings.setValue("appearance/animationsEnabled", false);
        settings.setValue("appearance/mode", 1);
        settings.sync();
    }
    void moreNavigationAndEmptyQueue() {
        PlayerController controller;
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QVERIFY(window);
        QVERIFY(QMetaObject::invokeMethod(window, "activateWindow"));
        QVERIFY(QTest::qWaitForWindowExposed(window));
        QVERIFY(click(window, "moreTab"));
        QTRY_VERIFY(visualItem(window->contentItem(), "queueEntry"));
        QVERIFY(click(window, "queueEntry"));
        QCOMPARE(window->property("detail").toString(), QString("queue"));
        auto *queue = visualItem(window->contentItem(), "queueView");
        QVERIFY(queue);
        QCOMPARE(queue->property("count").toInt(), 0);
        QVERIFY(visualItem(window->contentItem(), "queueEmptyState")->isVisible());
        QVERIFY(!visualItem(window->contentItem(), "clearQueueButton")->isEnabled());
        QVERIFY(!visualItem(window->contentItem(), "nextButton")->isEnabled());
        QTest::keyClick(window, Qt::Key_Escape);
        QTRY_COMPARE(window->property("detail").toString(), QString());
        QVERIFY(click(window, "historyEntry"));
        QCOMPARE(window->property("detail").toString(), QString("history"));
        QVERIFY(visualItem(window->contentItem(), "historyEmptyState")->isVisible());
        QCOMPARE(warnings.size(), 0);
    }
    void duplicateQueueDragMenusAndConfirmation() {
        PlayerController controller;
        for (int i = 0; i < 24; ++i) {
            const QString source = i % 2 ? "tencent" : "netease";
            const QString songId = i < 3 ? "42" : QString::number(100 + i);
            const QVariantMap track{{"id", source + ":" + songId}, {"songId", songId},
                {"source", source}, {"name", i < 3 ? QStringLiteral("重复歌曲") : QStringLiteral("队列歌曲 %1").arg(i + 1)},
                {"artist", QStringLiteral("测试歌手")}, {"duration", 180000}};
            QVERIFY(QMetaObject::invokeMethod(&controller, "enqueueTrack", Q_ARG(QVariantMap, track), Q_ARG(QString, "tail")));
        }
        const auto original = controller.property("queueEntries").toList();
        QCOMPARE(original.size(), 24);
        const auto firstId = original.first().toMap().value("queueId").toString();
        QVERIFY(!firstId.isEmpty());
        QVERIFY(firstId != original.at(2).toMap().value("queueId").toString());
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QVERIFY(window);
        QVERIFY(QMetaObject::invokeMethod(window, "activateWindow"));
        QVERIFY(QTest::qWaitForWindowExposed(window));
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, "queue")));
        QTest::qWait(60);
        auto *queue = visualItem(window->contentItem(), "queueView"); QVERIFY(queue);
        reveal(queue);
        QTest::qWait(30);
        auto *handle = visualItem(window->contentItem(), "queueDrag_" + firstId); QVERIFY(handle);
        const QPoint start = handle->mapToScene(QPointF(handle->width() / 2, handle->height() / 2)).toPoint();
        const QPoint edge = queue->mapToScene(QPointF(15, queue->height() - 7)).toPoint();
        const QRectF windowBounds(0, 0, window->width(), window->height());
        QVERIFY(windowBounds.contains(queue->mapRectToScene(QRectF(0, 0, queue->width(), queue->height()))));
        QVERIFY(windowBounds.contains(start));
        QVERIFY(windowBounds.contains(edge));
        QTest::mousePress(window, Qt::LeftButton, Qt::NoModifier, start);
        QTest::mouseMove(window, start + QPoint(0, 20), 30);
        QTest::mouseMove(window, edge, 30);
        QTest::qWait(600);
        QVERIFY(queue->property("contentY").toDouble() > 0);
        QVERIFY(capture(window, "v12-queue-drag-edge"));
        QTest::mouseRelease(window, Qt::LeftButton, Qt::NoModifier, edge);
        QTRY_VERIFY(controller.property("queueEntries").toList().first().toMap().value("queueId").toString() != firstId);
        const auto moved = controller.property("queueEntries").toList();
        int newIndex = -1;
        for (int i = 0; i < moved.size(); ++i) if (moved.at(i).toMap().value("queueId").toString() == firstId) newIndex = i;
        QVERIFY(newIndex > 1);
        QCOMPARE(moved.size(), 24);
        QVERIFY(QMetaObject::invokeMethod(queue, "positionViewAtBeginning"));
        QTest::qWait(30);
        const auto selected = moved.first().toMap().value("queueId").toString();
        QVERIFY(click(window, "queueMenu_" + selected));
        QVERIFY(click(window, "queueMoveDown"));
        QTRY_COMPARE(controller.property("queueEntries").toList().at(1).toMap().value("queueId").toString(), selected);
        QVERIFY(click(window, "queueMenu_" + selected));
        QVERIFY(click(window, "queueRemove"));
        QCOMPARE(controller.property("queueEntries").toList().size(), 24);
        QVERIFY(click(window, "cancelPlaybackRemoval"));
        QCOMPARE(controller.property("queueEntries").toList().size(), 24);
        QVERIFY(click(window, "clearQueueButton"));
        QVERIFY(click(window, "confirmPlaybackRemoval"));
        QTRY_COMPARE(controller.property("queueEntries").toList().size(), 0);
        QVERIFY(capture(window, "v12-queue-empty"));
        QCOMPARE(warnings.size(), 0);
    }
    void sleepTimerAndActualHistory() {
        QTemporaryDir directory; QVERIFY(directory.isValid());
        const QString path = directory.filePath(QStringLiteral("雨夜.WAV"));
        QVERIFY(writeWave(path));
        PlayerController controller;
        controller.importFile(QUrl::fromLocalFile(path));
        QTRY_VERIFY_WITH_TIMEOUT(controller.ready() && !controller.busy(), 15000);
        if (!controller.playing()) controller.toggle();
        QTRY_VERIFY(controller.playing());
        QTRY_COMPARE(controller.property("historyEntries").toList().size(), 1);
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        QVERIFY(QMetaObject::invokeMethod(window, "activateWindow"));
        QVERIFY(QTest::qWaitForWindowExposed(window));
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, "queue")));
        QTest::qWait(30);
        const auto currentEntry = controller.property("currentQueueEntry").toString();
        QVERIFY(!currentEntry.isEmpty());
        auto *currentRow = visualItem(window->contentItem(), "queueRow_" + currentEntry); QVERIFY(currentRow);
        QVERIFY(currentRow->property("current").toBool());
        QVERIFY(capture(window, "v12-queue-current"));
        QVERIFY(click(window, "queueMenu_" + currentEntry));
        QVERIFY(click(window, "queueRemove"));
        QCOMPARE(controller.property("queueEntries").toList().size(), 1);
        QVERIFY(click(window, "confirmPlaybackRemoval"));
        QCOMPARE(controller.property("queueEntries").toList().size(), 0);
        QVERIFY(controller.playing());
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, "sleep")));
        QTest::qWait(30);
        QVERIFY(click(window, "sleepPreset30"));
        QTRY_COMPARE(controller.property("sleepMode").toString(), QString("countdown"));
        QVERIFY(controller.property("sleepRemaining").toInt() > 1790);
        QVERIFY(visualItem(window->contentItem(), "sleepStatusButton")->isVisible());
        QVERIFY(click(window, "cancelSleepButton"));
        QTRY_COMPARE(controller.property("sleepMode").toString(), QString("off"));
        auto *minutes = visualItem(window->contentItem(), "sleepMinutes"); QVERIFY(minutes);
        minutes->setProperty("text", "181");
        QVERIFY(!visualItem(window->contentItem(), "startCustomSleep")->isEnabled());
        minutes->setProperty("text", "45");
        QVERIFY(click(window, "startCustomSleep"));
        QTRY_VERIFY(controller.property("sleepRemaining").toInt() > 2690);
        QVERIFY(click(window, "sleepAfterCurrent"));
        QCOMPARE(controller.property("sleepMode").toString(), QString("endOfTrack"));
        QVERIFY(capture(window, "v12-sleep-current"));
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, "history")));
        QTest::qWait(30);
        auto *history = visualItem(window->contentItem(), "historyView"); QVERIFY(history);
        QCOMPARE(history->property("count").toInt(), 1);
        QVERIFY(capture(window, "v12-history-light"));
        QVERIFY(click(window, "historyPlay_0"));
        QTRY_VERIFY(controller.playing() && !controller.busy());
        QCOMPARE(controller.property("historyEntries").toList().size(), 1);
        const int queueSize = controller.property("queueEntries").toList().size();
        QVERIFY(click(window, "historyMenu_0"));
        QVERIFY(click(window, "historyEnqueueTail"));
        QCOMPARE(controller.property("queueEntries").toList().size(), queueSize + 1);
        QCOMPARE(controller.property("historyEntries").toList().size(), 1);
        QVERIFY(click(window, "clearHistoryButton"));
        QCOMPARE(controller.property("historyEntries").toList().size(), 1);
        QVERIFY(click(window, "confirmPlaybackRemoval"));
        QCOMPARE(controller.property("historyEntries").toList().size(), 0);
        QVERIFY(controller.playing());
        QCOMPARE(warnings.size(), 0);
    }
    void queueDarkThemeAndShortDesktopStayScrollable() {
        PlayerController controller;
        for (int i = 0; i < 16; ++i) {
            const QVariantMap track{{"id", "netease:" + QString::number(700 + i)}, {"songId", QString::number(700 + i)},
                {"source", "netease"}, {"name", QStringLiteral("紧凑布局验证歌曲 %1").arg(i + 1)},
                {"artist", QStringLiteral("测试歌手")}, {"duration", 180000}};
            QVERIFY(QMetaObject::invokeMethod(&controller, "enqueueTrack", Q_ARG(QVariantMap, track), Q_ARG(QString, "tail")));
        }
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        QVERIFY(QMetaObject::invokeMethod(window, "activateWindow"));
        QVERIFY(QTest::qWaitForWindowExposed(window));
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, "settings")));
        auto *theme = window->findChild<QObject *>("themeSelector"); QVERIFY(theme);
        QVERIFY(QMetaObject::invokeMethod(theme, "activated", Q_ARG(int, 2)));
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, "queue")));
        QTest::qWait(40);
        QVERIFY(window->property("darkMode").toBool());
        QVERIFY(capture(window, "v12-queue-dark"));
        auto *queue = visualItem(window->contentItem(), "queueView"); QVERIFY(queue);
        QVERIFY(queue->property("contentHeight").toDouble() > queue->height());
        QVERIFY(window->setProperty("workArea", QRectF(0, 0, 1024, 540)));
        QTest::qWait(40);
        QVERIFY(window->height() <= 524);
        auto *outer = visualItem(window->contentItem(), "outerScroll"); QVERIFY(outer);
        auto *flickable = outer->property("contentItem").value<QQuickItem *>(); QVERIFY(flickable);
        QVERIFY(flickable->property("contentHeight").toDouble() > flickable->height());
        flickable->setProperty("contentY", flickable->property("contentHeight").toDouble() - flickable->height());
        QTest::qWait(30);
        QVERIFY(QRectF(0, 0, window->width(), window->height()).contains(
            queue->mapRectToScene(QRectF(0, 0, queue->width(), queue->height()))));
        QVERIFY(capture(window, "v12-queue-short-desktop"));
        auto *scale = visualItem(window->contentItem(), "windowScaleSlider"); QVERIFY(scale);
        scale->setProperty("value", 140);
        QVERIFY(QMetaObject::invokeMethod(scale, "moved"));
        QVERIFY(window->setProperty("workArea", QRectF(0, 0, 1024, 540)));
        QTest::qWait(30);
        QCOMPARE(window->property("contentScale").toDouble(), 1.4);
        QVERIFY(window->height() <= 524);
        flickable->setProperty("contentY", flickable->property("contentHeight").toDouble() - flickable->height());
        QTest::qWait(20);
        QVERIFY(QRectF(0, 0, window->width(), window->height()).contains(
            queue->mapRectToScene(QRectF(0, 0, queue->width(), queue->height()))));
        const QPoint scaledEdge = queue->mapToScene(QPointF(15, queue->height() - 7)).toPoint();
        QVERIFY(QRect(QPoint(0, 0), window->size()).contains(scaledEdge));
        QVERIFY(capture(window, "v12-queue-short-scaled"));
        QCOMPARE(warnings.size(), 0);
    }
    void localSearchAndOnlineMenusUseThePlaybackQueue() {
        UiCatalogFixture service; QVERIFY(service.listen(QHostAddress::LocalHost));
        QTemporaryDir directory; QVERIFY(directory.isValid());
        const QString path = directory.filePath("Menu test.WAV");
        QVERIFY(writeWave(path));
        PlayerController controller;
        controller.setApiBase(service.base());
        controller.setSearchSources({"netease"});
        controller.importFile(QUrl::fromLocalFile(path));
        QTRY_VERIFY_WITH_TIMEOUT(controller.ready() && !controller.busy(), 15000);
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        QVERIFY(QMetaObject::invokeMethod(window, "activateWindow"));
        QVERIFY(QTest::qWaitForWindowExposed(window));
        window->setProperty("section", "playlist");
        QTest::qWait(40);
        const auto localId = controller.currentTrack();
        const int initialQueueSize = controller.property("queueEntries").toList().size();
        QVERIFY(click(window, "libraryMenu_" + localId));
        QVERIFY(click(window, "trackEnqueueTail"));
        QCOMPARE(controller.property("queueEntries").toList().size(), initialQueueSize + 1);
        QCOMPARE(controller.property("queueEntries").toList().last().toMap().value("id").toString(), localId);
        const auto librarySize = controller.tracks().size();
        controller.search("fixture");
        QTRY_VERIFY(!controller.searching());
        QCOMPARE(controller.searchResults().size(), 1);
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, "search")));
        QTest::qWait(40);
        auto *searchMenu = visualItem(window->contentItem(), "searchMenu_0"); QVERIFY(searchMenu);
        reveal(searchMenu);
        QVERIFY(click(window, "searchMenu_0"));
        QVERIFY(click(window, "trackEnqueueNext"));
        QCOMPARE(controller.property("queueEntries").toList().size(), initialQueueSize + 2);
        QCOMPARE(controller.property("queueEntries").toList().at(1).toMap().value("songId").toString(), QString("101"));
        QCOMPARE(controller.tracks().size(), librarySize);
        controller.libraryAction("openOnline", {{"id", "243"}});
        QTRY_VERIFY(!controller.onlinePlaylistLoading());
        QCOMPARE(controller.onlinePlaylist().value("tracks").toList().size(), 1);
        window->setProperty("showingOnlinePlaylist", true);
        QTest::qWait(40);
        auto *onlineMenu = visualItem(window->contentItem(), "onlineMenu_0"); QVERIFY(onlineMenu);
        reveal(onlineMenu);
        QVERIFY(click(window, "onlineMenu_0"));
        QVERIFY(capture(window, "v12-online-queue-menu"));
        QVERIFY(click(window, "trackEnqueueTail"));
        QCOMPARE(controller.property("queueEntries").toList().size(), initialQueueSize + 3);
        QCOMPARE(controller.property("queueEntries").toList().last().toMap().value("songId").toString(), QString("104"));
        QCOMPARE(controller.tracks().size(), librarySize);
        QCOMPARE(warnings.size(), 0);
    }
    void shortcutCaptureConflictDisableAndDefaults() {
        PlayerController controller;
        auto *shortcuts = controller.property("shortcuts").value<QObject *>();
        QVERIFY(shortcuts);
        QCOMPARE(shortcuts->property("bindings").toList().size(), 6);
        QString parsedSequence;
        QVERIFY(QMetaObject::invokeMethod(shortcuts, "captureSequence", Q_RETURN_ARG(QString, parsedSequence),
            Q_ARG(int, int(Qt::Key_K)), Q_ARG(int, int(Qt::ControlModifier | Qt::ShiftModifier))));
        QCOMPARE(parsedSequence, QString("Ctrl+Shift+K"));
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        QVERIFY(QMetaObject::invokeMethod(window, "activateWindow"));
        QVERIFY(QTest::qWaitForWindowExposed(window));
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, "settings")));
        QTest::qWait(30);
        auto *edit = visualItem(window->contentItem(), "shortcutEdit_togglePlay"); QVERIFY(edit);
        reveal(edit);
        QVERIFY(click(window, "shortcutEdit_togglePlay"));
        auto *captureField = visualItem(window->contentItem(), "shortcutCaptureField"); QVERIFY(captureField);
        QVERIFY(captureField->isVisible());
        QTRY_VERIFY(captureField->hasActiveFocus());
        QVERIFY(capture(window, "v12-shortcut-capture-open"));
        QTest::keyClick(window, Qt::Key_K, Qt::ControlModifier | Qt::ShiftModifier);
        QVERIFY(capture(window, "v12-shortcut-capture-keys"));
        QVERIFY(click(window, "saveShortcutBinding"));
        QTRY_COMPARE(shortcutRow(shortcuts, "togglePlay").value("sequence").toString(), QString("Ctrl+Shift+K"));
        edit = visualItem(window->contentItem(), "shortcutEdit_togglePlay"); QVERIFY(edit); reveal(edit);
        QVERIFY(click(window, "shortcutEdit_togglePlay"));
        QTRY_VERIFY(captureField->hasActiveFocus());
        QTest::keyClick(window, Qt::Key_Right, Qt::ControlModifier | Qt::AltModifier);
        QVERIFY(click(window, "saveShortcutBinding"));
        QCOMPARE(shortcutRow(shortcuts, "togglePlay").value("sequence").toString(), QString("Ctrl+Shift+K"));
        QVERIFY(!shortcuts->property("message").toString().isEmpty());
        QVERIFY(visualItem(window->contentItem(), "shortcutCaptureError")->isVisible());
        QVERIFY(capture(window, "v12-shortcut-conflict"));
        QVERIFY(click(window, "cancelShortcutBinding"));
        auto *disable = visualItem(window->contentItem(), "shortcutDisable_togglePlay"); QVERIFY(disable); reveal(disable);
        QVERIFY(click(window, "shortcutDisable_togglePlay"));
        QTRY_VERIFY(shortcutRow(shortcuts, "togglePlay").value("sequence").toString().isEmpty());
        auto *defaults = visualItem(window->contentItem(), "resetShortcutDefaults"); QVERIFY(defaults); reveal(defaults);
        QVERIFY(click(window, "resetShortcutDefaults"));
        QTRY_COMPARE(shortcutRow(shortcuts, "togglePlay").value("sequence").toString(), QString("Ctrl+Alt+Space"));
        QVERIFY(capture(window, "v12-shortcuts-light"));
        QTest::keyClick(window, Qt::Key_F, Qt::ControlModifier);
        QTRY_COMPARE(window->property("detail").toString(), QString("search"));
        QTest::keyClick(window, Qt::Key_Escape);
        QTRY_COMPARE(window->property("detail").toString(), QString());
        QCOMPARE(warnings.size(), 0);
    }
};

QTEST_MAIN(V12UiTests)
#include "v12_ui_tests.moc"
