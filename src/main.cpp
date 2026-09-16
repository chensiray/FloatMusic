#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QStandardPaths>
#include "singleinstance.h"
#include "playercontroller.h"
int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);
    app.setOrganizationName("FloatMusic"); app.setApplicationName("FloatMusic");
    app.setApplicationVersion("0.3.0");
#ifndef Q_OS_ANDROID
    SingleInstance instance(QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation));
    const int instanceResult = instance.start();
    if (instanceResult != 0) return instanceResult == 1 ? 0 : 2;
#endif
    QQuickStyle::setStyle("Basic");
    PlayerController controller;
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("player", &controller);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
    engine.loadFromModule("FloatMusic", "Main");
#ifndef Q_OS_ANDROID
    QObject::connect(&instance, &SingleInstance::activate, &engine, [&engine] {
        if (!engine.rootObjects().isEmpty()) QMetaObject::invokeMethod(engine.rootObjects().first(), "activateWindow");
    });
#endif
    return app.exec();
}
