#pragma once
#include <QString>
#include <QByteArray>
namespace AudioFilePolicy {
constexpr qint64 MaxBytes = 30LL * 1024 * 1024;
QString validate(const QString &name, qint64 bytes, const QByteArray &header);
}
