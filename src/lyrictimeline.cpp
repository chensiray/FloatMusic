#include "lyrictimeline.h"
#include <QMap>
#include <QRegularExpression>
#include <QVariantMap>

namespace {
QMap<qint64, QString> parse(const QString &lrc) {
    static const QRegularExpression timestamp(R"(\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?\])");
    static const QRegularExpression offsetTag(R"(\[offset:([+-]?\d+)\])", QRegularExpression::CaseInsensitiveOption);
    const auto offsetMatch = offsetTag.match(lrc);
    const qint64 offset = offsetMatch.hasMatch() ? offsetMatch.captured(1).toLongLong() : 0;
    QMap<qint64, QString> result;
    for (const auto &line : lrc.split('\n')) {
        auto matches = timestamp.globalMatch(line);
        QList<qint64> times;
        qsizetype end = 0;
        while (matches.hasNext()) {
            const auto match = matches.next();
            if (match.captured(2).toInt() >= 60) continue;
            const qint64 time = match.captured(1).toLongLong() * 60000
                + match.captured(2).toInt() * 1000
                + match.captured(3).leftJustified(3, '0').toInt() - offset;
            times.append(time);
            end = match.capturedEnd();
        }
        const auto text = line.mid(end).trimmed();
        for (const auto time : times) {
            if (result.contains(time) && !result[time].isEmpty() && !text.isEmpty()) result[time] += '\n' + text;
            else if (!result.contains(time) || !text.isEmpty()) result[time] = text;
        }
    }
    return result;
}
}

QVariantList lyricTimeline(const QString &original, const QString &translation) {
    const auto source = parse(original), translated = parse(translation);
    const auto &timeline = source.isEmpty() ? translated : source;
    QVariantList rows;
    for (auto it = timeline.cbegin(); it != timeline.cend(); ++it)
        rows.append(QVariantMap{{"time", it.key()}, {"original", source.value(it.key())}, {"translation", translated.value(it.key())}});
    return rows;
}
