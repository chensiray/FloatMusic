#include <QtTest>
#include <QTemporaryDir>
#include <QDataStream>
#include <QFile>
#include <QStandardPaths>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QQuickStyle>
#include <QFontDatabase>
#include <QMimeData>
#include <QDragEnterEvent>
#include <QDropEvent>
#include "playercontroller.h"

class PlayerTests : public QObject {
    Q_OBJECT
private slots:
    void initTestCase() {
        QStandardPaths::setTestModeEnabled(true);
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
        QVERIFY(window->height() > 500);
        if (!artifactDir.isEmpty()) QVERIFY(window->grabWindow().save(artifactDir + "/windows-expanded.png"));
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
    }
};
QTEST_MAIN(PlayerTests)
#include "player_tests.moc"

