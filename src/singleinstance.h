#pragma once
#include <QObject>
#include <QLocalServer>
#include <QLockFile>
#include <memory>

class SingleInstance : public QObject {
    Q_OBJECT
public:
    explicit SingleInstance(const QString &directory, QObject *parent = nullptr);
    // 0: primary, 1: existing instance activated, 2: could not establish ownership.
    int start();
signals:
    void activate();
private:
    QString m_name;
    std::unique_ptr<QLockFile> m_lock;
    QLocalServer m_server;
};
