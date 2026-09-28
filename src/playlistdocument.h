#pragma once
#include <QByteArray>
#include <QVariantMap>
#include <QStringList>

namespace PlaylistDocument {
constexpr qint64 MaxBytes = 8 * 1024 * 1024;
struct Parsed {
    QVariantMap playlist;
    QString onlineId, error;
    int skipped = 0;
};
Parsed parse(const QString &text);
QByteArray encode(const QVariantMap &playlist, const QStringList &ids, QString &error);
}
