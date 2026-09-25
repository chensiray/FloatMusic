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
#include <cmath>
#include "playercontroller.h"

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
        for (const auto size : {QSize(360,780), QSize(780,360)}) {
            window->resize(size); window->show();
            QVERIFY(QTest::qWaitForWindowExposed(window));
            QTest::qWait(100);
            QVERIFY(window->isVisible()); // Launch must not hide the activity page.
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
