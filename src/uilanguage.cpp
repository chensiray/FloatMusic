#include "uilanguage.h"
#include <QSettings>
#include <QHash>
#include <QRegularExpression>
#include <algorithm>
#ifdef Q_OS_ANDROID
#include <QJniObject>
#endif

namespace {
const QHash<QString, QString> &english() {
    static const QHash<QString, QString> entries = {
#include "uilanguage_en.inc"
    };
    return entries;
}
struct Pattern { QRegularExpression expression; QString result; QList<int> arguments; };
const QList<Pattern> &patterns() {
    static const QList<Pattern> list = [] {
        QList<Pattern> result;
        const QRegularExpression placeholder("%([1-9][0-9]*)");
        for (auto it = english().cbegin(); it != english().cend(); ++it) {
            auto matches = placeholder.globalMatch(it.key());
            QString expression = "^"; qsizetype end = 0; QList<int> arguments;
            while (matches.hasNext()) {
                const auto match = matches.next();
                expression += QRegularExpression::escape(it.key().mid(end, match.capturedStart() - end)) + "([\\s\\S]*?)";
                arguments.append(match.captured(1).toInt()); end = match.capturedEnd();
            }
            if (!arguments.isEmpty()) result.append({QRegularExpression(expression + QRegularExpression::escape(it.key().mid(end)) + "$"), it.value(), arguments});
        }
        // Prefer the more specific message when templates share a prefix.
        std::sort(result.begin(), result.end(), [](const Pattern &a, const Pattern &b) { return a.expression.pattern().size() > b.expression.pattern().size(); });
        return result;
    }();
    return list;
}
}
UiLanguage::UiLanguage(QObject *parent) : QObject(parent) {
    m_code = QSettings().value("interface/language", "zh_CN").toString() == "en" ? "en" : "zh_CN";
    refresh();
}
void UiLanguage::setCode(const QString &code) {
    const QString normalized = code == "en" ? "en" : "zh_CN";
    if (m_code == normalized) return;
    m_code = normalized;
    QSettings().setValue("interface/language", m_code);
#ifdef Q_OS_ANDROID
    QJniObject nativeCode=QJniObject::fromString(m_code);
    QJniObject::callStaticMethod<void>("org/floatmusic/player/PlayerBridge","setLanguage","(Ljava/lang/String;)V",nativeCode.object<jstring>());
#endif
    emit changed();
}
void UiLanguage::refresh(){
#ifdef Q_OS_ANDROID
    const auto value=QJniObject::callStaticObjectMethod("org/floatmusic/player/PlayerBridge","languageCode","()Ljava/lang/String;").toString();
    const QString next=value=="en"?"en":"zh_CN";
    if(m_code!=next){m_code=next;QSettings().setValue("interface/language",m_code);emit changed();}
#endif
}
QString UiLanguage::text(const QString &source) const {
    if (m_code != "en" || source.isEmpty()) return source;
    auto found = english().constFind(source);
    if (found != english().cend()) return *found;
    for (const auto &pattern : patterns()) {
        auto match = pattern.expression.match(source);
        if (!match.hasMatch()) continue;
        QString result = pattern.result;
        // Substitute in one pass so user text containing %1 is preserved verbatim.
        const QRegularExpression placeholder("%([1-9][0-9]*)");
        auto placeholders = placeholder.globalMatch(result); QList<QRegularExpressionMatch> substitutions;
        while (placeholders.hasNext()) substitutions.prepend(placeholders.next());
        for (const auto &part : substitutions) {
            const int index = pattern.arguments.indexOf(part.captured(1).toInt());
            if (index >= 0) result.replace(part.capturedStart(), part.capturedLength(), text(match.captured(index + 1)));
        }
        return result;
    }
    for (const auto &separator : {QStringLiteral("\n"), QStringLiteral(" · ")}) {
        if (!source.contains(separator)) continue;
        auto pieces = source.split(separator);
        for (auto &piece : pieces) piece = text(piece);
        return pieces.join(separator);
    }
    for (const auto &prefix : {QStringLiteral("失败："), QStringLiteral("音频无法播放或解码："), QStringLiteral("音频连接失败：")})
        if (source.startsWith(prefix)) return text(prefix) + text(source.mid(prefix.size()));
    return source;
}
