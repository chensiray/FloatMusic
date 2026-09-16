#include "singleinstance.h"
#include <QCryptographicHash>
#include <QLocalSocket>
#include <QDir>
#include <QThread>

SingleInstance::SingleInstance(const QString &directory, QObject *parent) : QObject(parent) {
    QDir().mkpath(directory);
    m_name = "FloatMusic-" + QCryptographicHash::hash(directory.toUtf8(), QCryptographicHash::Sha256).toHex().left(24);
    m_lock = std::make_unique<QLockFile>(directory + "/instance.lock");
    m_lock->setStaleLockTime(0);
    m_server.setSocketOptions(QLocalServer::UserAccessOption);
    connect(&m_server, &QLocalServer::newConnection, this, [this] {
        while (auto *socket = m_server.nextPendingConnection()) {
            socket->disconnectFromServer(); socket->deleteLater(); emit activate();
        }
    });
}
int SingleInstance::start() {
    if (!m_lock->tryLock()) {
        // A simultaneous launch can reach here before the first instance starts listening.
        for (int i = 0; i < 20; ++i) {
            QLocalSocket socket; socket.connectToServer(m_name);
            if (socket.waitForConnected(100)) return 1;
            QThread::msleep(50);
        }
        return 2;
    }
    QLocalServer::removeServer(m_name);
    return m_server.listen(m_name) ? 0 : 2;
}
