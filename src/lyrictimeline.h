#pragma once
#include <QString>
#include <QVariantList>

// One row per timestamp, retaining original and translated text separately.
QVariantList lyricTimeline(const QString &original, const QString &translation);
