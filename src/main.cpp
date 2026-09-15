#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include "playercontroller.h"
int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);
    app.setOrganizationName("FloatMusic"); app.setApplicationName("FloatMusic");
    QQuickStyle::setStyle("Basic");
    PlayerController controller;
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("player", &controller);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
    engine.loadFromModule("FloatMusic", "Main");
    return app.exec();
}
