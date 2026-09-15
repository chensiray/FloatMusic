#include "audiofilepolicy.h"
#include <QFileInfo>
#include <QStringList>

QString AudioFilePolicy::validate(const QString &name, qint64 bytes, const QByteArray &h)
{
    const QString ext = QFileInfo(name).suffix().toLower();
    if (!QStringList{"mp3", "wav", "flac", "ogg", "m4a", "aac"}.contains(ext))
        return QStringLiteral("仅支持 MP3、WAV、FLAC、OGG、M4A、AAC 音频。");
    if (bytes <= 0) return QStringLiteral("文件为空或无法读取文件大小。");
    if (bytes > MaxBytes) return QStringLiteral("文件超过 30 MiB（31,457,280 字节），请选择更小的音频。");
    const auto b = [&](int i) { return i < h.size() ? static_cast<unsigned char>(h[i]) : 0; };
    bool valid = false;
    if (ext == "mp3") valid = h.startsWith("ID3") || (b(0) == 255 && (b(1) & 0xe0) == 0xe0 && (b(1) & 6) != 0);
    if (ext == "aac") valid = h.startsWith("ID3") || (b(0) == 255 && (b(1) & 0xf6) == 0xf0);
    if (ext == "wav") valid = (h.startsWith("RIFF") || h.startsWith("RF64")) && h.mid(8, 4) == "WAVE";
    if (ext == "flac") valid = h.startsWith("fLaC");
    if (ext == "ogg") valid = h.startsWith("OggS");
    if (ext == "m4a") valid = h.mid(4, 4) == "ftyp";
    return valid ? QString() : QStringLiteral("文件内容与音频扩展名不匹配，或文件已损坏。");
}
