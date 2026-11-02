#pragma once
#include <QByteArray>
#include <QJsonDocument>
#include <QJsonObject>

// Playback polling must not reapply an unchanged sidecar while a lyric request
// is in flight. Access times and download progress are not lyric revisions.
inline bool acceptCacheLyricSnapshot(QByteArray &previous, const QJsonObject &entry, bool trackChanged, bool requestLoading = false) {
    QJsonObject revision;
    for (const auto *key : {"key", "original", "translation", "originalState", "translationState"})
        revision.insert(key, entry.value(key));
    const auto next = QJsonDocument(revision).toJson(QJsonDocument::Compact);
    const bool changed = trackChanged || next != previous;
    previous = next;
    return changed && (!requestLoading || trackChanged || !entry.value("original").toString().isEmpty() || !entry.value("translation").toString().isEmpty());
}
