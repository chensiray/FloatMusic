#pragma once
#include <QTcpServer>
#include <QTcpSocket>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QPointer>
#include <QTimer>
#include <QUrl>

// Deterministic loopback service for rankings and incomplete playlist responses.
class PlaylistApiFixture : public QTcpServer {
public:
    QList<QUrl> requests;
    int rankingsStatus = 200;
    int rankingsDelay = 0;
    QByteArray rankingsBody = QJsonDocument(QJsonObject{{"code", 200}, {"list", QJsonArray{
        QJsonObject{{"id", 240}, {"name", QStringLiteral("独立音乐榜")}},
        QJsonObject{{"id", 241}, {"name", QStringLiteral("原创榜")}},
        QJsonObject{{"id", 242}, {"name", QStringLiteral("新歌榜")}},
        QJsonObject{{"id", 243}, {"name", QStringLiteral("热歌榜")}, {"trackCount", 1000},
                    {"updateFrequency", QStringLiteral("每周更新")},
                    {"description", QStringLiteral("听见此刻的热门与新声，收录本周最受欢迎的音乐。")}},
        QJsonObject{{"id", 244}, {"name", QStringLiteral("网易云飙升榜")}},
        QJsonObject{{"id", 245}, {"name", QStringLiteral("民谣榜")}},
        QJsonObject{{"id", 243}, {"name", QStringLiteral("重复条目")}},
        QJsonObject{{"id", 0}, {"name", QStringLiteral("无效 ID")}},
        QJsonObject{{"id", 246}, {"name", " "}}
    }}}).toJson();
    QJsonObject playlist = {{"id", 243}, {"name", QStringLiteral("热歌榜")},
        {"description", QStringLiteral("本周热歌 · 测试数据")}, {"trackCount", 4},
        {"trackIds", QJsonArray{QJsonObject{{"id", 101}}, QJsonObject{{"id", 0}},
                               QJsonObject{{"id", 103}}, QJsonObject{{"id", 104}}}},
        {"tracks", QJsonArray{QJsonObject{{"id", 101}, {"name", QStringLiteral("第一首")}},
                              QJsonObject{{"id", 104}, {"name", QStringLiteral("第四首")}}}}};

    PlaylistApiFixture() {
        connect(this, &QTcpServer::newConnection, this, [this] {
            while (auto *socket = nextPendingConnection()) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                connect(socket, &QTcpSocket::readyRead, socket, [this, socket] {
                    const auto bytes = socket->property("request").toByteArray() + socket->readAll();
                    socket->setProperty("request", bytes);
                    if (!bytes.contains("\r\n\r\n") || socket->property("sent").toBool()) return;
                    socket->setProperty("sent", true);
                    const QUrl url(QString::fromUtf8(bytes.split(' ').value(1)));
                    requests.append(url);
                    QByteArray body;
                    int status = 200, delay = 0;
                    if (url.path().endsWith("/toplist")) {
                        body = rankingsBody; status = rankingsStatus; delay = rankingsDelay;
                    } else if (url.path().endsWith("/playlist/detail")) {
                        body = QJsonDocument(QJsonObject{{"code", 200}, {"playlist", playlist}}).toJson();
                    } else if (url.path().endsWith("/song/detail")) {
                        // Song 103 is unavailable: returned positions must remain 1 and 4.
                        body = R"({"code":200,"songs":[]})";
                    } else {
                        status = 404; body = R"({"code":404})";
                    }
                    QPointer<QTcpSocket> safe(socket);
                    auto send = [safe, body, status] {
                        if (!safe) return;
                        safe->write("HTTP/1.1 " + QByteArray::number(status) + " Fixture\r\n"
                            "Content-Type: application/json\r\nContent-Length: " + QByteArray::number(body.size())
                            + "\r\nConnection: close\r\n\r\n" + body);
                        safe->disconnectFromHost();
                    };
                    if (delay) QTimer::singleShot(delay, socket, send); else send();
                });
            }
        });
    }
    QString base() const { return "http://127.0.0.1:" + QString::number(serverPort()); }
};
