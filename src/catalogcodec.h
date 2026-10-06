#pragma once
#include <QByteArray>
#include <QJsonDocument>
#include <QString>
namespace CatalogCodec {
QString descriptionText(const QString &text);
QByteArray aesEncrypt(const QByteArray &plain, const QByteArray &key);
QByteArray aesDecrypt(const QByteArray &cipher, const QByteArray &key, QString &error);
QJsonDocument parseLiteral(const QByteArray &bytes, QString &error);
QJsonDocument decodeRanking(const QByteArray &bytes, QString &error);
QByteArray rankingKey();
} // namespace CatalogCodec
