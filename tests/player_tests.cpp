#include <QtTest>
#include <QTemporaryDir>
#include <QDataStream>
#include <QFile>
#include <QStandardPaths>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QQuickItem>
#include <QQuickStyle>
#include <QFontDatabase>
#include <QMimeData>
#include <QDragEnterEvent>
#include <QDropEvent>
#include <QProcess>
#include <QScopeGuard>
#include <QSettings>
#include <QJsonDocument>
#include <QJsonObject>
#include <cmath>
#include "playercontroller.h"
#include "playlistapi_fixture.h"

// Only the network boundary is replaced; lyric parsing, timing and QML stay real.
class DesktopLyricApiFixture : public QTcpServer {
public:
    QString original = QStringLiteral("[00:00.00]让音乐留在手边\n[00:04.00]下一句\n[00:08.00]一首很长的歌词会自然换行，陪你一路向前\n[00:12.00]第四句\n[00:16.00]第五句\n[00:20.00]第六句\n[00:24.00]第七句\n[00:28.00]第八句");
    QString translation = QStringLiteral("[00:00.00]Keep music close\n[00:04.00]The next line\n[00:08.00]A longer translated lyric wraps without losing its words\n[00:12.00]Line four\n[00:16.00]Line five\n[00:20.00]Line six\n[00:24.00]Line seven\n[00:28.00]Line eight");
    DesktopLyricApiFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    const auto request = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", request);
                    if (!request.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const auto body = QJsonDocument(QJsonObject{{"code", 200},
                        {"lrc", QJsonObject{{"lyric", original}}},
                        {"tlyric", QJsonObject{{"lyric", translation}}}}).toJson();
                    socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                    socket->disconnectFromHost();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
};

static QQuickItem *findVisualItem(QQuickItem *root, const QString &name) {
    if (!root) return nullptr;
    if (root->objectName() == name) return root;
    for (auto *child : root->childItems())
        if (auto *found = findVisualItem(child, name)) return found;
    return nullptr;
}

class PlayerTests : public QObject {
    Q_OBJECT
private slots:
    void initTestCase() {
        QCoreApplication::setOrganizationName("FloatMusicTests");
        QStandardPaths::setTestModeEnabled(true);
        QCoreApplication::setApplicationName("ui-tests-"+QUuid::createUuid().toString(QUuid::WithoutBraces));
        QQuickStyle::setStyle("Basic");
        // The headless plugin does not enumerate Windows system fonts.
        QFontDatabase::addApplicationFont("C:/Windows/Fonts/msyh.ttc");
        QFontDatabase::addApplicationFont("C:/Windows/Fonts/segoeui.ttf");
        QFontDatabase::addApplicationFont("C:/Windows/Fonts/seguisym.ttf");
        QGuiApplication::setFont(QFont("Microsoft YaHei UI"));
    }
    void importPlaybackAndSeek() {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        const QString path = dir.filePath("试听.WAV");
        QFile wave(path);
        QVERIFY(wave.open(QIODevice::WriteOnly));
        QDataStream stream(&wave); stream.setByteOrder(QDataStream::LittleEndian);
        const quint32 bytes = 16000 * 2 * 8;
        stream.writeRawData("RIFF", 4); stream << quint32(36 + bytes);
        stream.writeRawData("WAVEfmt ", 8); stream << quint32(16) << quint16(1) << quint16(1)
            << quint32(16000) << quint32(32000) << quint16(2) << quint16(16);
        stream.writeRawData("data", 4); stream << bytes;
        stream.writeRawData(QByteArray(bytes, '\0').constData(), bytes);
        wave.close();
        PlayerController controller;
        controller.importFile(QUrl::fromLocalFile(path));
        QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 15000);
        QVERIFY2(controller.ready(), qPrintable(controller.error()));
        QVERIFY(controller.seekable());
        QCOMPARE(controller.duration(), 8000);
        QCOMPARE(controller.title(), QStringLiteral("试听.WAV"));
        controller.toggle();
        QTRY_VERIFY(controller.playing());
        QTRY_VERIFY_WITH_TIMEOUT(controller.position() > 200, 5000);
        const int originalVolume = controller.volume();
        controller.setVolume(-10); QCOMPARE(controller.volume(), 0);
        controller.setVolume(135); QCOMPARE(controller.volume(), 100);
        controller.setVolume(35); QCOMPARE(controller.volume(), 35);
        const auto outputs = controller.audioOutputs();
        QVERIFY(!outputs.isEmpty());
        for (const auto &entry : outputs) {
            const auto device = entry.toMap();
            qInfo() << "Output:" << device.value("name").toString();
            controller.selectOutput(device.value("id").toString());
            QCOMPARE(controller.selectedOutput(), device.value("id").toString());
            QVERIFY(controller.playing());
        }
        controller.selectOutput(""); controller.setVolume(originalVolume);
        controller.toggle();
        QTRY_VERIFY(!controller.playing());
        controller.seek(4000);
        QTRY_VERIFY(qAbs(controller.position() - 4000) < 200);
        const auto pausedPosition = controller.position();
        QTest::qWait(300);
        QVERIFY(qAbs(controller.position() - pausedPosition) < 100);
        controller.toggle();
        QTRY_VERIFY(controller.playing());
        controller.seek(6000);
        QTRY_VERIFY(controller.position() >= 5800);
        controller.toggle();
        QFile invalid(dir.filePath("fake.mp3"));
        QVERIFY(invalid.open(QIODevice::WriteOnly)); invalid.write("not music"); invalid.close();
        controller.importFile(QUrl::fromLocalFile(invalid.fileName()));
        QTRY_VERIFY(!controller.busy());
        QVERIFY(!controller.error().isEmpty());
        QVERIFY(controller.ready()); // Rejected imports preserve the loaded track.
        QCOMPARE(controller.title(), QStringLiteral("试听.WAV"));
        QFile oversized(dir.filePath("large.wav"));
        QVERIFY(oversized.open(QIODevice::WriteOnly));
        oversized.write("RIFF0000WAVEfmt ");
        QVERIFY(oversized.resize(30LL * 1024 * 1024 + 1));
        oversized.close();
        controller.importFile(QUrl::fromLocalFile(oversized.fileName()));
        QTRY_VERIFY(!controller.busy());
        QVERIFY(controller.error().contains("30 MiB"));
        QVERIFY(controller.ready());
    }
    void exitTerminatesProcess() {
        for (const auto &action : {"--close-child", "--exit-button-child"}) {
            QProcess child;
            child.start(QCoreApplication::applicationFilePath(), {"-platform", "offscreen", action});
            QVERIFY(child.waitForStarted());
            QVERIFY(child.waitForFinished(10000));
            QCOMPARE(child.exitStatus(), QProcess::NormalExit);
            QCOMPARE(child.exitCode(), 0);
        }
    }
    void qmlWindow() {
        PlayerController controller;
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QVERIFY(window);
        QVERIFY(window->flags().testFlag(Qt::WindowStaysOnTopHint));
        QVERIFY(window->flags().testFlag(Qt::FramelessWindowHint));
        QVERIFY(!window->isVisible());
        auto *icon = window->findChild<QQuickWindow *>("floatingIcon");
        QVERIFY(icon); QVERIFY(icon->isVisible()); QCOMPARE(icon->width(), 64);
        QVERIFY(QTest::qWaitForWindowExposed(icon));
        QTest::qWait(100);
        QTest::mouseClick(icon, Qt::LeftButton, Qt::NoModifier, QPoint(32,32));
        QTRY_VERIFY(window->isVisible()); QTRY_VERIFY(!icon->isVisible());
        QTest::qWait(400);
        const auto artifactDir = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        if (!artifactDir.isEmpty()) {
            QDir().mkpath(artifactDir);
            QVERIFY(window->grabWindow().save(artifactDir + "/windows-compact.png"));
        }
        window->hide(); QTRY_VERIFY(icon->isVisible()); window->show();
        QTest::qWait(200);
        QVERIFY(window->height() < 500); // Card without an expanded section.
        if (!artifactDir.isEmpty()) QVERIFY(window->grabWindow().save(artifactDir + "/windows-expanded.png"));
        QVERIFY(window->setProperty("section", "more"));
        QVERIFY(window->setProperty("detail", "search"));
        QTest::qWait(100);
        QVERIFY(window->findChild<QQuickItem *>("searchInput")->isVisible());
        auto *quality = window->findChild<QObject *>("qualitySelector"); QVERIFY(quality);
        QVERIFY(QMetaObject::invokeMethod(quality, "activated", Q_ARG(int, 2)));
        QCOMPARE(controller.quality(), QString("exhigh"));
        if (!artifactDir.isEmpty()) QVERIFY(window->grabWindow().save(artifactDir + "/windows-search.png"));
        window->setProperty("section", "lyrics"); QTest::qWait(100);
        QVERIFY(window->findChild<QQuickItem *>("lyricsText")->isVisible());
        QVERIFY(window->findChild<QObject *>("lyricsText")->property("readOnly").toBool());
        if (!artifactDir.isEmpty()) QVERIFY(window->grabWindow().save(artifactDir + "/windows-lyrics.png"));
        window->setProperty("section", "playlist");
        QTemporaryDir files;
        QFile bogus(files.filePath("dragged.mp3"));
        QVERIFY(bogus.open(QIODevice::WriteOnly)); bogus.write("This is not music."); bogus.close();
        QMimeData mime; mime.setUrls({QUrl::fromLocalFile(bogus.fileName())});
        QDragEnterEvent enter(QPoint(100, 100), Qt::CopyAction, &mime, Qt::LeftButton, Qt::NoModifier);
        QCoreApplication::sendEvent(window, &enter);
        QVERIFY(enter.isAccepted());
        QDropEvent drop(QPointF(100, 100), Qt::CopyAction, &mime, Qt::LeftButton, Qt::NoModifier);
        QCoreApplication::sendEvent(window, &drop);
        QTRY_VERIFY(!controller.error().isEmpty());
        QCOMPARE(warnings.size(), 0);
        QVERIFY(window->findChild<QObject *>("exitButton"));
        // A window close must be accepted, not hidden into the icon as in 0.2.
        QVERIFY(window->close());
        QVERIFY(!window->isVisible());
    }
    void desktopLyricPreferences() {
        const auto previousName = QCoreApplication::applicationName();
        const auto restoreName = qScopeGuard([previousName] { QCoreApplication::setApplicationName(previousName); });
        QCoreApplication::setApplicationName("ui-lyrics-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
        const QVariantMap track{{"id", "netease:101"}, {"source", "netease"}, {"songId", "101"},
                                {"name", QStringLiteral("歌词验证")}, {"artist", QStringLiteral("测试歌手")}};
        QSettings().setValue("playback/session", QJsonDocument(QJsonObject::fromVariantMap(
            {{"track", track}, {"position", 0}, {"duration", 60000}})).toJson());
        DesktopLyricApiFixture api; QVERIFY(api.listen(QHostAddress::LocalHost));
        PlayerController controller; controller.setApiBase(api.base());
        QTRY_COMPARE(controller.currentTrack(), QString("netease:101"));
        controller.retryLyrics();
        QTRY_VERIFY(!controller.lyricsLoading());
        QVERIFY2(!controller.lyricsFailed(), qPrintable(controller.lyricsMessage()));
        QCOMPARE(controller.lyricLines().size(), 8);
        QCOMPARE(controller.currentLyricIndex(), 0);
        QQmlApplicationEngine engine; QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        window->setProperty("section", "lyrics"); window->show();
        QVERIFY(QTest::qWaitForWindowExposed(window));
        QTest::qWait(80);
        for (auto *item : window->findChildren<QQuickItem *>()) {
            const auto label = item->property("displayText").toString();
            if (item->inherits("QQuickComboBox") && (label == QStringLiteral("原文")
                    || label == QStringLiteral("译文") || label == QStringLiteral("双语")
                    || label == QStringLiteral("原文与译文")))
                QVERIFY2(!item->isVisible(), "Language preferences must not occupy the lyrics page");
        }
        auto *mode = window->findChild<QQuickItem *>("lyricModeSelector");
        auto *size = window->findChild<QQuickItem *>("lyricFontSizeSlider");
        auto *preview = window->findChild<QQuickItem *>("lyricFontPreview");
        auto *value = window->findChild<QObject *>("lyricFontSizeValue");
        auto *view = window->findChild<QQuickItem *>("timedLyrics");
        auto *plain = window->findChild<QQuickItem *>("lyricsText");
        QVERIFY(mode); QVERIFY(size); QVERIFY(preview); QVERIFY(value); QVERIFY(view); QVERIFY(plain);
        QCOMPARE(size->property("value").toInt(), 18);
        QCOMPARE(mode->property("currentIndex").toInt(), 0);
        QTRY_VERIFY(findVisualItem(view, "lyricOriginal-0"));
        auto *original = findVisualItem(view, "lyricOriginal-0");
        auto *translated = findVisualItem(view, "lyricTranslation-0"); QVERIFY(translated);
        QCOMPARE(original->property("text").toString(), QStringLiteral("让音乐留在手边"));
        QCOMPARE(original->property("font").value<QFont>().pixelSize(), 20);
        QVERIFY(!translated->isVisible());
        const auto artifacts = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        if (!artifacts.isEmpty()) QVERIFY(QDir().mkpath(artifacts));
        auto capture = [&](const QString &name) {
            QTest::qWait(80);
            return artifacts.isEmpty() || window->grabWindow().save(artifacts + "/" + name + ".png");
        };
        QVERIFY(window->setProperty("section", "more")); QVERIFY(window->setProperty("detail", "settings"));
        QTRY_VERIFY(mode->isVisible() && size->isVisible() && preview->isVisible());
        auto *theme = window->findChild<QObject *>("themeSelector"); QVERIFY(theme);
        QVERIFY(QMetaObject::invokeMethod(theme, "activated", Q_ARG(int, 1)));
        QVERIFY(QMetaObject::invokeMethod(mode, "activated", Q_ARG(int, 2)));
        QTRY_COMPARE(mode->property("currentIndex").toInt(), 2);
        size->setProperty("value", 26.0); QVERIFY(QMetaObject::invokeMethod(size, "moved"));
        QTRY_COMPARE(value->property("text").toString(), QString("26"));
        QTRY_COMPARE(preview->property("font").value<QFont>().pixelSize(), 28);
        const auto previewBottom = preview->mapToScene(QPointF(preview->width(), preview->height()));
        QVERIFY(previewBottom.x() <= window->width() && previewBottom.y() <= window->height());
        QVERIFY(capture("windows-lyric-settings-26-light"));
        window->setProperty("section", "lyrics");
        QTRY_VERIFY(translated->isVisible());
        QCOMPARE(translated->property("text").toString(), QString("Keep music close"));
        QTRY_COMPARE(original->property("font").value<QFont>().pixelSize(), 28);
        QTRY_COMPARE(translated->property("font").value<QFont>().pixelSize(), 28);
        QVERIFY(capture("windows-timed-lyrics-26-light"));
        controller.setLyricOffset(5000);
        QTRY_COMPARE(controller.currentLyricIndex(), 1);
        QTRY_COMPARE(original->property("font").value<QFont>().pixelSize(), 26);
        QTRY_VERIFY(findVisualItem(view, "lyricOriginal-1"));
        auto *current = findVisualItem(view, "lyricOriginal-1");
        QTRY_COMPARE(current->property("font").value<QFont>().pixelSize(), 28);
        QCOMPARE(current->property("color").value<QColor>(), window->property("accent").value<QColor>());
        view->forceActiveFocus(); QTest::keyClick(window, Qt::Key_Down);
        QTRY_VERIFY(view->property("manualBrowsing").toBool());
        window->setProperty("section", "more"); window->setProperty("detail", "settings");
        QVERIFY(QMetaObject::invokeMethod(theme, "activated", Q_ARG(int, 2)));
        size->setProperty("value", 14.0); QVERIFY(QMetaObject::invokeMethod(size, "moved"));
        QTRY_COMPARE(preview->property("font").value<QFont>().pixelSize(), 16);
        QVERIFY(capture("windows-lyric-settings-14-dark"));
        QVERIFY(QMetaObject::invokeMethod(mode, "activated", Q_ARG(int, 1)));
        window->setProperty("section", "lyrics");
        QTRY_COMPARE(current->property("text").toString(), QString("The next line"));
        QTRY_COMPARE(current->property("font").value<QFont>().pixelSize(), 16);
        QVERIFY(!translated->isVisible());
        QVERIFY(view->property("manualBrowsing").toBool());
        auto *follow = window->findChild<QQuickItem *>("returnToCurrentLyric"); QVERIFY(follow);
        QTRY_VERIFY(follow->isVisible());
        QVERIFY(QMetaObject::invokeMethod(follow, "clicked"));
        QTRY_VERIFY(!view->property("manualBrowsing").toBool());
        QVERIFY(capture("windows-timed-lyrics-14-dark"));
        api.original = QStringLiteral("让音乐留在手边\n下一句");
        api.translation = "Keep music close\nThe next line";
        controller.retryLyrics(); QTRY_VERIFY(!controller.lyricsLoading());
        QTRY_VERIFY(controller.lyricLines().isEmpty()); QTRY_VERIFY(plain->isVisible());
        QTRY_COMPARE(plain->property("text").toString(), QString("Keep music close\nThe next line"));
        QTRY_COMPARE(plain->property("font").value<QFont>().pixelSize(), 14);
        for (int choice : {0, 2}) {
            window->setProperty("section", "more"); window->setProperty("detail", "settings");
            QVERIFY(QMetaObject::invokeMethod(mode, "activated", Q_ARG(int, choice)));
            window->setProperty("section", "lyrics");
            QTRY_VERIFY(plain->property("text").toString().contains(QStringLiteral("让音乐留在手边")));
            QCOMPARE(plain->property("text").toString().contains("Keep music close"), choice == 2);
        }
        size->setProperty("value", 26.0); QVERIFY(QMetaObject::invokeMethod(size, "moved"));
        QTRY_COMPARE(plain->property("font").value<QFont>().pixelSize(), 26);
        QVERIFY(capture("windows-plain-lyrics-26-dark"));
        QPointer<QQuickWindow> closed(window); window->deleteLater(); QTRY_VERIFY(closed.isNull());
        QQmlApplicationEngine reopened; QSignalSpy reopenWarnings(&reopened, &QQmlEngine::warnings);
        reopened.rootContext()->setContextProperty("player", &controller);
        reopened.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(reopened.rootObjects().size(), 1);
        auto *restored = reopened.rootObjects().first();
        QCOMPARE(restored->findChild<QObject *>("lyricModeSelector")->property("currentIndex").toInt(), 2);
        QCOMPARE(restored->findChild<QObject *>("lyricFontSizeSlider")->property("value").toInt(), 26);
        QCOMPARE(restored->findChild<QObject *>("lyricsText")->property("font").value<QFont>().pixelSize(), 26);
        QVERIFY(restored->findChild<QObject *>("lyricsText")->property("text").toString().contains("Keep music close"));
        QCOMPARE(warnings.size(), 0); QCOMPARE(reopenWarnings.size(), 0);
    }
    void desktopLyricPreferenceBounds_data() {
        QTest::addColumn<int>("storedFont"); QTest::addColumn<int>("storedMode");
        QTest::addColumn<int>("fontSize"); QTest::addColumn<int>("mode");
        QTest::newRow("below-minimum") << -40 << -1 << 14 << 0;
        QTest::newRow("above-maximum") << 90 << 99 << 26 << 2;
    }
    void desktopLyricPreferenceBounds() {
        QFETCH(int, storedFont); QFETCH(int, storedMode); QFETCH(int, fontSize); QFETCH(int, mode);
        const auto previousName = QCoreApplication::applicationName();
        const auto restoreName = qScopeGuard([previousName] { QCoreApplication::setApplicationName(previousName); });
        QCoreApplication::setApplicationName("ui-lyrics-bounds-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
        QSettings settings; settings.setValue("lyrics/fontSize", storedFont); settings.setValue("lyrics/displayMode", storedMode); settings.sync();
        PlayerController controller; QQmlApplicationEngine engine; QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = engine.rootObjects().first();
        auto *size = window->findChild<QObject *>("lyricFontSizeSlider");
        auto *language = window->findChild<QObject *>("lyricModeSelector");
        auto *preview = window->findChild<QObject *>("lyricFontPreview");
        auto *text = window->findChild<QObject *>("lyricsText");
        QVERIFY(size); QVERIFY(language); QVERIFY(preview); QVERIFY(text);
        QCOMPARE(size->property("value").toInt(), fontSize);
        QCOMPARE(language->property("currentIndex").toInt(), mode);
        QCOMPARE(preview->property("font").value<QFont>().pixelSize(), fontSize + 2);
        QCOMPARE(text->property("font").value<QFont>().pixelSize(), fontSize);
        // Settings batches property writes. Verify the actual exit/reopen contract
        // rather than inspecting its backing QSettings during a pending batch.
        QPointer<QObject> closed(window); window->deleteLater(); QTRY_VERIFY(closed.isNull());
        QSettings persisted;
        QCOMPARE(persisted.value("lyrics/fontSize").toInt(), fontSize);
        QCOMPARE(persisted.value("lyrics/displayMode").toInt(), mode);
        QQmlApplicationEngine reopened;
        reopened.rootContext()->setContextProperty("player", &controller);
        reopened.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(reopened.rootObjects().size(), 1);
        auto *restored = reopened.rootObjects().first();
        QCOMPARE(restored->findChild<QObject *>("lyricFontSizeSlider")->property("value").toInt(), fontSize);
        QCOMPARE(restored->findChild<QObject *>("lyricModeSelector")->property("currentIndex").toInt(), mode);
        QCOMPARE(warnings.size(), 0);
    }
    void desktopAppearanceReset() {
        const auto previousName = QCoreApplication::applicationName();
        const auto restoreName = qScopeGuard([previousName] { QCoreApplication::setApplicationName(previousName); });
        QCoreApplication::setApplicationName("ui-reset-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
        QSettings settings; settings.setValue("appearance/windowScale", 1.2); settings.setValue("appearance/backgroundOpacity", .4);
        settings.setValue("appearance/iconX", 250); settings.setValue("appearance/iconY", 180);
        settings.setValue("appearance/mode", 2); settings.setValue("appearance/animationsEnabled", false);
        settings.setValue("lyrics/fontSize", 24); settings.setValue("lyrics/displayMode", 1); settings.sync();
        PlayerController controller; QQmlApplicationEngine engine; QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        auto *icon = window->findChild<QQuickWindow *>("floatingIcon"); QVERIFY(icon);
        auto *reset = window->findChild<QObject *>("resetAppearanceButton"); QVERIFY(reset);
        window->setProperty("section", "more"); window->setProperty("detail", "settings");
        window->show(); QVERIFY(QTest::qWaitForWindowExposed(window));
        QVERIFY(QMetaObject::invokeMethod(reset, "clicked"));
        QTRY_COMPARE(window->property("contentScale").toDouble(), 1.0);
        QTest::qWait(200); // Let scale-dependent layout and the existing fit timer settle.
        const QRect area = controller.desktopWorkArea(80, 152);
        const QPoint expected(qBound(area.x() + 8, 48, area.x() + area.width() - 64 - 8),
                              qBound(area.y() + 8, 120, area.y() + area.height() - 64 - 8));
        QTRY_COMPARE(icon->position(), expected);
        QTRY_COMPARE(window->position(), QPoint(
            qBound(area.x() + 8, expected.x(), area.x() + area.width() - window->width() - 8),
            qBound(area.y() + 8, expected.y(), area.y() + area.height() - window->height() - 8)));
        QVERIFY(window->property("darkMode").toBool());
        QVERIFY(!window->findChild<QObject *>("animationsSwitch")->property("checked").toBool());
        QCOMPARE(window->findChild<QObject *>("lyricFontSizeSlider")->property("value").toInt(), 24);
        QCOMPARE(window->findChild<QObject *>("lyricModeSelector")->property("currentIndex").toInt(), 1);
        QPointer<QQuickWindow> closed(window); window->deleteLater(); QTRY_VERIFY(closed.isNull());
        QSettings persisted;
        QCOMPARE(persisted.value("appearance/windowScale").toDouble(), 1.0);
        QCOMPARE(persisted.value("appearance/backgroundOpacity").toDouble(), 1.0);
        QCOMPARE(persisted.value("appearance/iconX").toInt(), expected.x());
        QCOMPARE(persisted.value("appearance/iconY").toInt(), expected.y());
        QQmlApplicationEngine reopened;
        reopened.rootContext()->setContextProperty("player", &controller);
        reopened.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(reopened.rootObjects().size(), 1);
        auto *restored = qobject_cast<QQuickWindow *>(reopened.rootObjects().first()); QVERIFY(restored);
        QCOMPARE(restored->findChild<QQuickWindow *>("floatingIcon")->position(), expected);
        QCOMPARE(restored->property("contentScale").toDouble(), 1.0);
        QVERIFY(restored->property("darkMode").toBool());
        QVERIFY(!restored->findChild<QObject *>("animationsSwitch")->property("checked").toBool());
        QCOMPARE(restored->findChild<QObject *>("lyricFontSizeSlider")->property("value").toInt(), 24);
        QCOMPARE(restored->findChild<QObject *>("lyricModeSelector")->property("currentIndex").toInt(), 1);
        QCOMPARE(warnings.size(), 0);
    }
    void desktopSearchLibrarySelection() {
        const auto previousApplicationName = QCoreApplication::applicationName();
        const auto restoreApplicationName = qScopeGuard([previousApplicationName] {
            QCoreApplication::setApplicationName(previousApplicationName);
        });
        QCoreApplication::setApplicationName("ui-multisource-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
        MusicApi::Endpoints endpoints;
        endpoints.metadata = "http://127.0.0.1:1";
        endpoints.playback = endpoints.metadata;
        endpoints.gdStudio = endpoints.metadata;
        endpoints.injahow = endpoints.metadata;
        endpoints.vkeys = endpoints.metadata;
        endpoints.ourcraft = endpoints.metadata;
        endpoints.timeoutMs = 100;
        PlayerController controller(endpoints);
        controller.setApiBase("");
        controller.setSearchSources({"netease"});
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QVERIFY(window);
        QVERIFY(window->setProperty("section", "more"));
        QVERIFY(window->setProperty("detail", "search"));
        window->show();
        QVERIFY(QTest::qWaitForWindowExposed(window));
        auto *netease = window->findChild<QQuickItem *>("searchSourceNetease");
        auto *tencent = window->findChild<QQuickItem *>("searchSourceTencent");
        auto *kuwo = window->findChild<QQuickItem *>("searchSourceKuwo");
        auto *searchButton = window->findChild<QQuickItem *>("searchButton");
        auto *input = window->findChild<QQuickItem *>("searchInput");
        QVERIFY(netease); QVERIFY(tencent); QVERIFY(kuwo); QVERIFY(searchButton); QVERIFY(input);
        QTRY_VERIFY(netease->isVisible() && tencent->isVisible() && kuwo->isVisible());
        QTRY_VERIFY(netease->property("checked").toBool());
        QVERIFY(!tencent->property("checked").toBool());
        QVERIFY(!kuwo->property("checked").toBool());
        QTRY_VERIFY(searchButton->isEnabled());
        QTest::qWait(50);
        QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier,
                          tencent->mapToScene(QPointF(tencent->width() / 2, tencent->height() / 2)).toPoint());
        QTRY_COMPARE(controller.searchSources(), QStringList({"netease", "tencent"}));
        QTRY_VERIFY(tencent->property("checked").toBool());
        kuwo->forceActiveFocus();
        QTRY_VERIFY(kuwo->hasActiveFocus());
        QTest::keyClick(window, Qt::Key_Space);
        QTRY_COMPARE(controller.searchSources(), QStringList({"netease", "tencent", "kuwo"}));
        QTRY_VERIFY(kuwo->property("checked").toBool());
        const auto artifactDir = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        if (!artifactDir.isEmpty()) {
            QVERIFY(QDir().mkpath(artifactDir));
            QTest::qWait(50);
            QVERIFY(window->grabWindow().save(artifactDir + "/windows-multisource-search.png"));
        }
        for (auto *source : {netease, tencent, kuwo}) {
            source->forceActiveFocus();
            QTRY_VERIFY(source->hasActiveFocus());
            QTest::keyClick(window, Qt::Key_Space);
            QTRY_VERIFY(!source->property("checked").toBool());
        }
        QTRY_VERIFY(controller.searchSources().isEmpty());
        QTRY_VERIFY(!searchButton->isEnabled());
        QVERIFY(input->setProperty("text", QStringLiteral("接口隔离验证")));
        QSignalSpy accepted(input, SIGNAL(accepted()));
        QSignalSpy searchChanges(&controller, &PlayerController::searchChanged);
        QVERIFY(accepted.isValid());
        input->forceActiveFocus();
        QTRY_VERIFY(input->hasActiveFocus());
        QTest::keyClick(window, Qt::Key_Return);
        QTRY_COMPARE(accepted.size(), 1);
        QCOMPARE(searchChanges.size(), 0);
        QVERIFY(controller.searchMessage().contains(QStringLiteral("至少选择")));
        QVERIFY(!controller.searching());
        QVERIFY(controller.searchResults().isEmpty());
        if (!artifactDir.isEmpty()) {
            QTest::qWait(50);
            QVERIFY(window->grabWindow().save(artifactDir + "/windows-multisource-empty.png"));
        }
        QVERIFY(window->setProperty("searchKind", "playlists"));
        QTRY_VERIFY(netease->isVisible() && tencent->isVisible() && kuwo->isVisible());
        QTRY_VERIFY(!searchButton->isEnabled());
        QVERIFY(controller.searchSources().isEmpty());
        QSignalSpy playlistChanges(&controller, &PlayerController::playlistSearchChanged);
        input->forceActiveFocus(); QTest::keyClick(window, Qt::Key_Return);
        QCOMPARE(playlistChanges.size(), 0);
        QVERIFY(controller.playlistSearchMessage().contains(QStringLiteral("至少选择")));
        QVERIFY(!controller.playlistSearching());
        QVERIFY(controller.playlistResults().isEmpty());
        tencent->forceActiveFocus(); QTest::keyClick(window, Qt::Key_Space);
        QTRY_COMPARE(controller.searchSources(), QStringList({"tencent"}));
        QTRY_VERIFY(searchButton->isEnabled());
        if (!artifactDir.isEmpty()) {
            QTest::qWait(50);
            QVERIFY(window->grabWindow().save(artifactDir + "/windows-multisource-playlist-search.png"));
        }
        QTest::qWait(30);
        QCOMPARE(warnings.size(), 0);
    }
    void desktopPlatformQualitySelection() {
        const auto previousApplicationName = QCoreApplication::applicationName();
        const auto restoreApplicationName = qScopeGuard([previousApplicationName] {
            QCoreApplication::setApplicationName(previousApplicationName);
        });
        QCoreApplication::setApplicationName("ui-platform-quality-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
        MusicApi::Endpoints endpoints;
        endpoints.metadata = "http://127.0.0.1:1";
        endpoints.playback = endpoints.metadata;
        endpoints.gdStudio = endpoints.metadata;
        endpoints.injahow = endpoints.metadata;
        endpoints.vkeys = endpoints.metadata;
        endpoints.ourcraft = endpoints.metadata;
        endpoints.timeoutMs = 100;
        QObject controllers;
        auto *initial = new PlayerController(endpoints, &controllers);
        initial->setApiBase("");
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", initial);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QVERIFY(window);
        QVERIFY(window->setProperty("section", "more"));
        QVERIFY(window->setProperty("detail", "settings"));
        window->show();
        QVERIFY(QTest::qWaitForWindowExposed(window));
        auto *quality = window->findChild<QQuickItem *>("qualitySelector");
        auto *source = window->findChild<QQuickItem *>("currentSourceName");
        auto *theme = window->findChild<QObject *>("themeSelector");
        QVERIFY(quality); QVERIFY(source); QVERIFY(theme);
        QVERIFY(QMetaObject::invokeMethod(theme, "activated", Q_ARG(int, 1)));
        QQuickItem *viewport = quality->parentItem();
        while (viewport && !viewport->property("contentY").isValid()) viewport = viewport->parentItem();
        QVERIFY2(viewport, "The settings page must scroll to the online quality control");
        auto revealQuality = [&] {
            const double top = viewport->property("contentY").toDouble()
                + quality->mapToItem(viewport, QPointF()).y();
            const double end = qMax(0.0, viewport->property("contentHeight").toDouble() - viewport->height());
            return viewport->setProperty("contentY", qBound(0.0, top - 40.0, end));
        };
        QVERIFY(revealQuality());
        QTRY_VERIFY(quality->isEnabled());
        QTRY_COMPARE(quality->property("count").toInt(), 5);
        quality->forceActiveFocus();
        QTRY_VERIFY(quality->hasActiveFocus());
        QTest::keyClick(window, Qt::Key_End);
        QTRY_COMPARE(initial->quality(), QString("hires"));
        QTest::keyClick(window, Qt::Key_Up);
        QTRY_COMPARE(initial->quality(), QString("lossless"));
        QTRY_COMPARE(quality->property("currentIndex").toInt(), 3);
        QTRY_COMPARE(quality->property("displayText").toString(), QStringLiteral("无损 FLAC"));
        const auto artifactDir = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        if (!artifactDir.isEmpty()) QVERIFY(QDir().mkpath(artifactDir));
        const QList<QPair<QString, QString>> platforms{
            {"tencent", QStringLiteral("QQ音乐")},
            {"kuwo", QStringLiteral("酷我音乐")},
            {"netease", QStringLiteral("网易云")}
        };
        for (const auto &platform : platforms) {
            const QString songId = platform.first == "tencent" ? "004MpJjW07rAPl" : "42";
            const QVariantMap track{{"id", platform.first + ':' + songId}, {"source", platform.first},
                                    {"songId", songId}, {"name", QStringLiteral("恢复的测试歌曲")},
                                    {"artist", QStringLiteral("测试歌手")}};
            QSettings().setValue("playback/session", QJsonDocument(QJsonObject::fromVariantMap(
                {{"track", track}, {"position", 3250}, {"duration", 8000}})).toJson());
            // Rebind the same controls so the model changes from five qualities to one
            // and back. Restored sessions defer all audio and lyric network requests.
            auto *restored = new PlayerController(endpoints, &controllers);
            engine.rootContext()->setContextProperty("player", restored);
            QTRY_COMPARE(restored->currentTrack(), track.value("id").toString());
            QVERIFY(restored->ready());
            QVERIFY(!restored->busy());
            QVERIFY(!restored->playing());
            QCOMPARE(restored->quality(), QString("lossless"));
            QTRY_VERIFY(source->isVisible());
            QTRY_COMPARE(source->property("text").toString(), platform.second);
            QVERIFY(revealQuality());
            if (platform.first == "netease") {
                QTRY_VERIFY(quality->isEnabled());
                QTRY_COMPARE(quality->property("count").toInt(), 5);
                QTRY_COMPARE(quality->property("currentIndex").toInt(), 3);
                QTRY_COMPARE(quality->property("displayText").toString(), QStringLiteral("无损 FLAC"));
            } else {
                QTRY_VERIFY(!quality->isEnabled());
                QTRY_COMPARE(quality->property("count").toInt(), 1);
                QTRY_COMPARE(quality->property("currentIndex").toInt(), 0);
                QTRY_COMPARE(quality->property("displayText").toString(), QStringLiteral("普通音质"));
                QVERIFY(restored->qualityInfo().contains(QStringLiteral("普通音质")));
                auto *popup = quality->property("popup").value<QObject *>();
                QVERIFY(popup);
                QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier,
                    quality->mapToScene(QPointF(quality->width() / 2, quality->height() / 2)).toPoint());
                QVERIFY(!popup->property("visible").toBool());
                QCOMPARE(restored->quality(), QString("lossless"));
            }
            QTest::qWait(50); // Also check the queued model-selection synchronization.
            QCOMPARE(quality->property("currentIndex").toInt(), platform.first == "netease" ? 3 : 0);
            const auto bottom = quality->mapToScene(QPointF(quality->width(), quality->height()));
            QVERIFY(bottom.x() <= window->width());
            QVERIFY(bottom.y() > 0 && bottom.y() <= window->height());
            if (!artifactDir.isEmpty())
                QVERIFY(window->grabWindow().save(artifactDir + "/windows-quality-" + platform.first + ".png"));
        }
        QCOMPARE(warnings.size(), 0);
    }
    void desktopSearchLimitSettingAndPlatformSwitch() {
        const auto previousName = QCoreApplication::applicationName();
        const auto restoreName = qScopeGuard([previousName] { QCoreApplication::setApplicationName(previousName); });
        QCoreApplication::setApplicationName("ui-v1-settings-" + QUuid::createUuid().toString(QUuid::WithoutBraces));
        MusicApi::Endpoints endpoints;
        endpoints.metadata = "http://127.0.0.1:1"; endpoints.timeoutMs = 100;
        PlayerController controller(endpoints);
        controller.setApiBase(""); controller.setSearchResultLimit(50);
        QQmlApplicationEngine engine; QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        window->setProperty("section", "more"); window->setProperty("detail", "settings");
        window->show(); QVERIFY(QTest::qWaitForWindowExposed(window));
        auto *selector = window->findChild<QQuickItem *>("searchResultLimitSelector"); QVERIFY(selector);
        QTRY_COMPARE(selector->property("currentIndex").toInt(), 3);
        auto *theme = window->findChild<QObject *>("themeSelector"); QVERIFY(theme);
        QQuickItem *viewport = selector->parentItem();
        while (viewport && !viewport->property("contentY").isValid()) viewport = viewport->parentItem();
        QVERIFY(viewport);
        const auto artifacts = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        auto capture = [&](const QString &name) {
            QTest::qWait(120);
            return artifacts.isEmpty() || window->grabWindow().save(artifacts + "/" + name + ".png");
        };
        for (int mode : {1, 2}) {
            QVERIFY(QMetaObject::invokeMethod(theme, "activated", Q_ARG(int, mode)));
            const double top = viewport->property("contentY").toDouble() + selector->mapToItem(viewport, QPointF()).y();
            viewport->setProperty("contentY", qBound(0.0, top - 40.0,
                qMax(0.0, viewport->property("contentHeight").toDouble() - viewport->height())));
            QTest::qWait(80);
            selector->forceActiveFocus();
            QTRY_VERIFY(selector->hasActiveFocus());
            QTest::keyClick(window, Qt::Key_Space);
            QVERIFY(capture(mode == 1 ? "v1-search-limit-dropdown-light" : "v1-search-limit-dropdown-dark"));
            QTest::keyClick(window, Qt::Key_Escape);
        }
        selector->forceActiveFocus(); QTest::keyClick(window, Qt::Key_Down);
        QTRY_COMPARE(controller.searchResultLimit(), 100);
        QTRY_COMPARE(selector->property("currentIndex").toInt(), 4);
        PlayerController restored(endpoints); QCOMPARE(restored.searchResultLimit(), 100);
        // Changing the provider uses the same route as an actual ranking platform click.
        window->setProperty("detail", "rankings");
        auto *kuwo = findVisualItem(window->contentItem(), "rankingSource_kuwo"); QVERIFY(kuwo);
        QVERIFY(QMetaObject::invokeMethod(kuwo, "clicked"));
        QTRY_VERIFY(!controller.rankingsLoading());
        QCOMPARE(controller.rankingSource(), QString("kuwo"));
        QCOMPARE(controller.rankings().size(), 3);
        QVERIFY(capture("v1-kuwo-rankings-dark"));
        QVERIFY(kuwo->property("selected").toBool());
        QCOMPARE(warnings.size(), 0);
    }
    void desktopThemesAndNavigation() {
        PlayerController controller;
        QTemporaryDir music;
        QFile wave(music.filePath("界面验证.wav")); QVERIFY(wave.open(QIODevice::WriteOnly));
        QDataStream wav(&wave); wav.setByteOrder(QDataStream::LittleEndian);
        const quint32 bytes = 16000 * 2 * 30;
        wav.writeRawData("RIFF", 4); wav << quint32(36 + bytes);
        wav.writeRawData("WAVEfmt ", 8); wav << quint32(16) << quint16(1) << quint16(1)
            << quint32(16000) << quint32(32000) << quint16(2) << quint16(16);
        wav.writeRawData("data", 4); wav << bytes; wav.writeRawData(QByteArray(bytes, '\0').constData(), bytes); wave.close();
        controller.setVolume(0); controller.importFile(QUrl::fromLocalFile(wave.fileName()));
        QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 10000); QVERIFY(controller.ready());
        controller.toggle(); QTRY_VERIFY(controller.playing());
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QVERIFY(window); window->show();
        window->setProperty("section", "more"); window->setProperty("detail", "search");
        QVERIFY(QTest::qWaitForWindowExposed(window));
        auto *theme = window->findChild<QObject *>("themeSelector");
        QVERIFY2(theme, "Appearance settings must offer system, light and dark themes");
        auto *bar = window->findChild<QQuickItem *>("playerBar"); QVERIFY(bar);
        const auto barY = bar->mapToScene(QPointF()).y();
        auto *input = window->findChild<QObject *>("searchInput"); QVERIFY(input);
        auto *searchField = qobject_cast<QQuickItem *>(input); QVERIFY(searchField);
        QVERIFY2(searchField->width() > 180, "The compact search field must remain usable");
        QVERIFY(searchField->mapToScene(QPointF(searchField->width(), 0)).x() < window->width());
        input->setProperty("text", QStringLiteral("保留搜索内容"));
        for (int mode : {1, 2}) {
            QVERIFY(QMetaObject::invokeMethod(theme, "activated", Q_ARG(int, mode)));
            QTRY_COMPARE(window->property("darkMode").toBool(), mode == 2);
            for (const auto &section : {"lyrics", "playlist", "more"}) {
                QVERIFY(window->setProperty("section", section));
                QTest::qWait(30);
                QVERIFY(bar->isVisible());
                QCOMPARE(bar->mapToScene(QPointF()).y(), barY);
                QVERIFY(controller.playing());
            }
            auto luminance = [](QColor color) {
                auto linear = [](double c) {return c <= .04045 ? c/12.92 : std::pow((c+.055)/1.055, 2.4);};
                return .2126*linear(color.redF())+.7152*linear(color.greenF())+.0722*linear(color.blueF());
            };
            const double a = luminance(window->property("accent").value<QColor>());
            const double b = luminance(window->property("accentInk").value<QColor>());
            QVERIFY2((qMax(a,b)+.05)/(qMin(a,b)+.05) >= 4.5, "Primary button text must be readable in both themes");
            QCOMPARE(input->property("text").toString(), QStringLiteral("保留搜索内容"));
            const auto artifacts = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
            if (!artifacts.isEmpty()) {
                QDir().mkpath(artifacts);
                QVERIFY(window->grabWindow().save(artifacts + (mode == 1 ? "/theme-light.png" : "/theme-dark.png")));
                window->setProperty("section", "more"); window->setProperty("detail", "settings"); QTest::qWait(50);
                QVERIFY(window->grabWindow().save(artifacts + (mode == 1 ? "/settings-light.png" : "/settings-dark.png")));
                auto *themeItem = qobject_cast<QQuickItem *>(theme); QVERIFY(themeItem);
                QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier, themeItem->mapToScene(QPointF(themeItem->width()/2, themeItem->height()/2)).toPoint());
                QTest::qWait(150);
                QVERIFY(window->grabWindow().save(artifacts + (mode == 1 ? "/dropdown-light.png" : "/dropdown-dark.png")));
                QTest::keyClick(window, Qt::Key_Escape);
                window->setProperty("section", "more"); window->setProperty("detail", "search");
            }
        }
        controller.toggle(); QTRY_VERIFY(!controller.playing());
        auto *slider = window->findChild<QQuickItem *>("progress"); QVERIFY(slider);
        const QPoint seekPoint = slider->mapToScene(QPointF(slider->width()*.75, slider->height()/2)).toPoint();
        QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier, seekPoint);
        QTRY_VERIFY(qAbs(controller.position() - 22500) < 1000);
        auto *fill = slider->findChild<QQuickItem *>("playedFill"); QVERIFY(fill);
        QVERIFY(fill->width() > slider->width()*.65 && fill->width() < slider->width()*.85);
        QTest::qWait(60);
        const auto progressImage = window->grabWindow();
        const auto playedPixel = slider->mapToScene(QPointF(slider->width()*.25, slider->height()/2)).toPoint();
        const auto unplayedPixel = slider->mapToScene(QPointF(slider->width()*.9, slider->height()/2)).toPoint();
        QCOMPARE(progressImage.pixelColor(playedPixel), window->property("accent").value<QColor>());
        QCOMPARE(progressImage.pixelColor(unplayedPixel), window->property("line").value<QColor>());
        auto *volume = window->findChild<QQuickItem *>("volumeSlider"); QVERIFY(volume);
        QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier, volume->mapToScene(QPointF(volume->width()/2,volume->height()/2)).toPoint());
        QVERIFY(qAbs(controller.volume() - 50) < 3);
        window->setProperty("section", "more"); window->setProperty("detail", "settings");
        QTest::qWait(100);
        QVERIFY(window->findChild<QQuickItem *>("outputSelector")->isVisible());
        QVERIFY(window->findChild<QObject *>("playlistView")->property("count").toInt() > 0);
        auto *size = window->findChild<QObject *>("windowScaleSlider"); QVERIFY(size);
        const int originalWidth = window->width();
        const double originalScale = window->property("contentScale").toDouble();
        size->setProperty("value", 120.0);
        QVERIFY(QMetaObject::invokeMethod(size, "moved"));
        QTest::qWait(100);
        QVERIFY(window->width() >= originalWidth);
        QVERIFY(window->property("contentScale").toDouble() >= originalScale);
        auto *opacity = window->findChild<QObject *>("backgroundOpacitySlider"); QVERIFY(opacity);
        opacity->setProperty("value", 20.0);
        QVERIFY(QMetaObject::invokeMethod(opacity, "moved"));
        QCOMPARE(window->opacity(), 1.0); // The native window must remain interactive.
        QVERIFY(bar->mapToScene(QPointF(0,bar->height())).y() <= window->height());
        // Reopening the application must retain the selected appearance.
        QPointer<QQuickWindow> closed(window);
        window->deleteLater();
        QTRY_VERIFY(closed.isNull()); // Destroy the first UI, as an actual application exit does.
        QQmlApplicationEngine reopened;
        reopened.rootContext()->setContextProperty("player", &controller);
        reopened.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(reopened.rootObjects().size(), 1);
        QVERIFY(reopened.rootObjects().first()->property("darkMode").toBool());
        QCOMPARE(warnings.size(), 0);
    }
    void liveWindowSearchAndLyrics() {
        if (!qEnvironmentVariableIsSet("FLOATMUSIC_LIVE_TESTS")) QSKIP("Live UI network test is opt-in.");
        PlayerController controller; controller.setApiBase(""); controller.setQuality("standard"); controller.setVolume(0);
        QQmlApplicationEngine engine; QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        window->setProperty("section", "more"); window->setProperty("detail", "search");
        window->show(); QVERIFY(QTest::qWaitForWindowExposed(window));
        auto *input = window->findChild<QObject *>("searchInput"); QVERIFY(input);
        input->setProperty("text", QStringLiteral("海阔天空"));
        QVERIFY(QMetaObject::invokeMethod(input, "accepted"));
        QTRY_VERIFY_WITH_TIMEOUT(!controller.searching(), 20000);
        QVERIFY2(!controller.searchResults().isEmpty(), qPrintable(controller.searchMessage()));
        QTest::qWait(150);
        auto *results = window->findChild<QQuickItem *>("searchResults"); QVERIFY(results);
        // ListView delegates are visual children, not necessarily QObject children of the root.
        std::function<QQuickItem *(QQuickItem *)> findPlay = [&](QQuickItem *item) -> QQuickItem * {
            if (item->objectName() == "playSearchResult" && item->isVisible()) return item;
            for (auto *child : item->childItems()) if (auto *found = findPlay(child)) return found;
            return nullptr;
        };
        auto *play = findPlay(results); QVERIFY(play);
        QTest::mouseClick(window, Qt::LeftButton, Qt::NoModifier, play->mapToScene(QPointF(play->width()/2, play->height()/2)).toPoint());
        QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 40000);
        QVERIFY2(controller.error().isEmpty(), qPrintable(controller.error())); QTRY_VERIFY(controller.playing());
        QTRY_VERIFY_WITH_TIMEOUT(!controller.lyricsLoading(), 20000);
        QVERIFY2(!controller.lyricsFailed(), qPrintable(controller.lyricsMessage())); QVERIFY(!controller.lyrics().isEmpty());
        const auto artifactDir = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        if (!artifactDir.isEmpty()) {
            QDir().mkpath(artifactDir);
            auto *theme = window->findChild<QObject *>("themeSelector"); QVERIFY(theme);
            for (int mode : {1,2}) {
                QVERIFY(QMetaObject::invokeMethod(theme, "activated", Q_ARG(int, mode)));
                QTest::qWait(100); QVERIFY(controller.playing());
                QVERIFY(window->grabWindow().save(artifactDir + (mode == 1 ? "/live-search-light.png" : "/live-search-dark.png")));
            }
        }
        window->setProperty("section", "lyrics"); QTest::qWait(150);
        auto *text = window->findChild<QQuickItem *>("lyricsText"); QVERIFY(text); QVERIFY(text->isVisible());
        QVERIFY(!text->property("text").toString().trimmed().isEmpty());
        if (!artifactDir.isEmpty()) QVERIFY(window->grabWindow().save(artifactDir + "/live-lyrics.png"));
        QCOMPARE(warnings.size(), 0);
        controller.toggle();
    }
    void rankingsNavigationAndMotion() {
        PlaylistApiFixture mock; QVERIFY(mock.listen(QHostAddress::LocalHost));
        PlayerController controller; controller.setApiBase(mock.base());
        controller.setSearchSources({"netease"});
        QQmlApplicationEngine engine; QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        window->show(); QVERIFY(QTest::qWaitForWindowExposed(window));
        auto *size = window->findChild<QObject *>("windowScaleSlider"); QVERIFY(size);
        size->setProperty("value", 100.0); QVERIFY(QMetaObject::invokeMethod(size, "moved"));
        auto *opacity = window->findChild<QObject *>("backgroundOpacitySlider"); QVERIFY(opacity);
        opacity->setProperty("value", 100.0); QVERIFY(QMetaObject::invokeMethod(opacity, "moved"));
        auto *theme = window->findChild<QObject *>("themeSelector"); QVERIFY(theme);
        auto *rankings = window->findChild<QQuickItem *>("rankingsView"); QVERIFY(rankings);
        auto *songs = window->findChild<QQuickItem *>("onlinePlaylistTracks"); QVERIFY(songs);
        const auto artifacts = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        auto capture = [&](const QString &name) {
            QTest::qWait(200);
            return artifacts.isEmpty() || window->grabWindow().save(artifacts + "/" + name + ".png");
        };
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, QVariant("rankings"))));
        QTRY_VERIFY(!controller.rankingsLoading()); QTRY_COMPARE(rankings->property("count").toInt(), 6);
        QVERIFY(rankings->isVisible());
        const auto requestCount = mock.requests.size();
        QVERIFY(QMetaObject::invokeMethod(window, "openDetail", Q_ARG(QVariant, QVariant("rankings"))));
        QTest::qWait(100); QCOMPARE(mock.requests.size(), requestCount); // Reopening uses the loaded list.
        for (int mode : {1, 2}) {
            QVERIFY(QMetaObject::invokeMethod(theme, "activated", Q_ARG(int, mode)));
            QVERIFY(capture(mode == 1 ? "rankings-light" : "rankings-dark"));
        }
        // Populate a large rank after unavailable entries to check text width and original numbering.
        QJsonArray ids;
        for (int i = 1; i <= 1000; ++i) ids.append(QJsonObject{{"id", i == 1000 ? 104 : 0}});
        mock.playlist["trackIds"] = ids; mock.playlist["trackCount"] = 1000;
        QVERIFY(QMetaObject::invokeMethod(window, "openOnlinePlaylist",
            Q_ARG(QVariant, QVariant("243")), Q_ARG(QVariant, QVariant("rankings"))));
        QTRY_VERIFY(!controller.onlinePlaylistLoading()); QTRY_COMPARE(songs->property("count").toInt(), 1);
        QVERIFY(songs->isVisible()); QVERIFY(!rankings->isVisible());
        QTest::qWait(150);
        std::function<QQuickItem *(QQuickItem *)> rankText = [&](QQuickItem *item) -> QQuickItem * {
            if (item->isVisible() && item->property("text").toString() == "1000") return item;
            for (auto *child : item->childItems()) if (auto *found = rankText(child)) return found;
            return nullptr;
        };
        auto *number = rankText(songs); QVERIFY(number);
        QVERIFY(number->width() >= number->implicitWidth());
        QVERIFY(capture("ranking-detail-dark"));
        QVERIFY(QMetaObject::invokeMethod(window, "closeOnlinePlaylist"));
        QTRY_VERIFY(rankings->isVisible()); QCOMPARE(window->property("detail").toString(), QString("rankings"));
        mock.rankingsStatus = 503;
        auto *refresh = window->findChild<QObject *>("refreshRankings"); QVERIFY(refresh);
        QVERIFY(QMetaObject::invokeMethod(refresh, "clicked")); QTRY_VERIFY(!controller.rankingsLoading());
        QCOMPARE(rankings->property("count").toInt(), 6); QVERIFY(controller.rankingsMessage().contains("503"));
        QVERIFY(capture("rankings-refresh-error"));
        auto *modeButton = window->findChild<QQuickItem *>("playbackModeButton"); QVERIFY(modeButton);
        auto *popup = window->findChild<QObject *>("playbackModePopup"); QVERIFY(popup);
        QVERIFY(QMetaObject::invokeMethod(modeButton, "clicked")); QTRY_VERIFY(popup->property("opened").toBool());
        auto *popupContent = popup->property("contentItem").value<QQuickItem *>(); QVERIFY(popupContent);
        const auto top = popupContent->mapToScene(QPointF());
        const auto bottom = popupContent->mapToScene(QPointF(popupContent->width(), popupContent->height()));
        QVERIFY(top.y() >= 0 && bottom.y() <= modeButton->mapToScene(QPointF()).y());
        QVERIFY(top.x() >= 0 && bottom.x() <= window->width());
        QCOMPARE(popup->property("z").toInt(), 1000);
        QVERIFY(capture("playback-menu-over-rankings"));
        QVERIFY(QMetaObject::invokeMethod(popup, "close"));
        auto *motion = window->findChild<QObject *>("animationsSwitch"); QVERIFY(motion);
        motion->setProperty("checked", false); QVERIFY(QMetaObject::invokeMethod(motion, "toggled"));
        modeButton->setProperty("down", true);
        QCOMPARE(modeButton->property("visualScale").toDouble(), 1.0);
        motion->setProperty("checked", true); QVERIFY(QMetaObject::invokeMethod(motion, "toggled"));
        QCOMPARE(modeButton->property("visualScale").toDouble(), .965);
        modeButton->setProperty("down", false);
        QCOMPARE(modeButton->property("visualScale").toDouble(), 1.0);
        QCOMPARE(warnings.size(), 0);
    }
    void androidWelcomeLayout() {
        // Android now has a dedicated QML welcome page; the overlay is native Java.
        PlayerController controller;
        QQmlApplicationEngine engine;
        QSignalSpy warnings(&engine, &QQmlEngine::warnings);
        engine.rootContext()->setContextProperty("player", &controller);
        const auto page = QFileInfo(QStringLiteral(FLOATMUSIC_QML_FILE)).dir().filePath("AndroidMain.qml");
        engine.load(QUrl::fromLocalFile(page));
        QCOMPARE(engine.rootObjects().size(), 1);
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first()); QVERIFY(window);
        QQuickItem *flickable = nullptr, *exit = nullptr;
        for (auto *item : window->findChildren<QQuickItem *>()) {
            if (item->property("contentY").isValid() && item->property("contentHeight").isValid()) flickable = item;
            if (item->inherits("QQuickButton") && item->property("text").toString() == QStringLiteral("退出浮音")) exit = item;
        }
        QVERIFY(flickable); QVERIFY(exit);
        const auto artifacts = qEnvironmentVariable("FLOATMUSIC_TEST_ARTIFACTS");
        for (int mode : {1, 2}) {
            window->setProperty("themeMode", mode);
            for (const auto size : {QSize(360,780), QSize(780,360), QSize(320,568)}) {
                window->resize(size); window->show();
                QVERIFY(QTest::qWaitForWindowExposed(window));
                flickable->setProperty("contentY", 0.0);
                QTest::qWait(150);
                QVERIFY(window->isVisible()); // Launch must not hide the activity page.
                if (!artifacts.isEmpty()) {
                    QDir().mkpath(artifacts);
                    QVERIFY(window->grabWindow().save(artifacts + QString("/android-welcome-%1-%2x%3.png")
                        .arg(mode == 1 ? "light" : "dark").arg(size.width()).arg(size.height())));
                }
                const auto end = qMax(0.0, flickable->property("contentHeight").toDouble() - flickable->height());
                flickable->setProperty("contentY", end); QTest::qWait(80);
                const auto bottom = exit->mapToScene(QPointF(exit->width(), exit->height()));
                QVERIFY(bottom.x() <= window->width());
                QVERIFY(bottom.y() <= window->height() && bottom.y() > 0);
                if (!artifacts.isEmpty() && size == QSize(780,360))
                    QVERIFY(window->grabWindow().save(artifacts + QString("/android-welcome-%1-landscape-bottom.png")
                        .arg(mode == 1 ? "light" : "dark")));
            }
        }
        QCOMPARE(warnings.size(), 0);
    }

};
int main(int argc, char **argv) {
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("FloatMusicTests");
    if (app.arguments().contains("--close-child") || app.arguments().contains("--exit-button-child")) {
        QStandardPaths::setTestModeEnabled(true); QCoreApplication::setApplicationName("exit-child");
        QQuickStyle::setStyle("Basic");
        PlayerController player; QQmlApplicationEngine engine; engine.rootContext()->setContextProperty("player", &player);
        engine.load(QUrl::fromLocalFile(QStringLiteral(FLOATMUSIC_QML_FILE)));
        if(engine.rootObjects().isEmpty())return 8;
        auto *window=qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        QTimer::singleShot(0,window,[window]{window->show();});
        QTimer::singleShot(200,window,[window,&app]{
            if(app.arguments().contains("--close-child"))window->close();
            else {auto *button=window->findChild<QObject *>("exitButton");if(!button||!QMetaObject::invokeMethod(button,"clicked"))QCoreApplication::exit(7);}
        });
        QTimer::singleShot(5000,&app,[]{QCoreApplication::exit(9);});
        return app.exec();
    }
    PlayerTests tests; return QTest::qExec(&tests,argc,argv);
}
#include "player_tests.moc"
