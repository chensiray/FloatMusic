#include "catalogcodec.h"
#include <QJsonArray>
#include <QJsonObject>
#include <QJsonParseError>
#include <QHash>
#include <QRegularExpression>
#include <array>

// Original portable implementation of AES-128 ECB, following NIST FIPS 197
// (https://doi.org/10.6028/NIST.FIPS.197-upd1), no third-party code incorporated.
// This codec implements the public Kuwo metadata wire protocol, not audio access.
namespace {
using Byte = unsigned char;
Byte mul(Byte a, Byte b) {
    Byte r = 0;
    for (int i = 0; i < 8; ++i) {
        if (b & 1)
            r ^= a;
        const bool high = a & 128;
        a = Byte(a << 1);
        if (high)
            a ^= 0x1b;
        b >>= 1;
    }
    return r;
}
Byte rot(Byte a, int n) {
    return Byte((a << n) | (a >> (8 - n)));
}
struct Tables {
    std::array<Byte, 256> s{}, inv{};
    Tables() {
        for (int i = 0; i < 256; ++i) {
            Byte p = 1, a = Byte(i);
            int e = 254;
            while (e) {
                if (e & 1)
                    p = mul(p, a);
                a = mul(a, a);
                e >>= 1;
            }
            if (!i)
                p = 0;
            s[i] = p ^ rot(p, 1) ^ rot(p, 2) ^ rot(p, 3) ^ rot(p, 4) ^ 0x63;
            inv[s[i]] = Byte(i);
        }
    }
};
const Tables &tables() {
    static const Tables t;
    return t;
}
std::array<Byte, 176> expand(const QByteArray &key) {
    std::array<Byte, 176> k{};
    for (int i = 0; i < 16; ++i)
        k[i] = Byte(key[i]);
    Byte r = 1;
    for (int n = 16; n < 176; n += 4) {
        std::array<Byte, 4> t{};
        for (int i = 0; i < 4; ++i)
            t[i] = k[n - 4 + i];
        if (n % 16 == 0) {
            const Byte x = t[0];
            for (int i = 0; i < 3; ++i)
                t[i] = tables().s[t[i + 1]];
            t[3] = tables().s[x];
            t[0] ^= r;
            r = mul(r, 2);
        }
        for (int i = 0; i < 4; ++i)
            k[n + i] = k[n + i - 16] ^ t[i];
    }
    return k;
}
void block(Byte *s, const std::array<Byte, 176> &k, bool inverse) {
    const auto add = [&](int r) {
        for (int i = 0; i < 16; ++i)
            s[i] ^= k[r * 16 + i];
    };
    const auto sub = [&] {
        for (int i = 0; i < 16; ++i)
            s[i] = inverse ? tables().inv[s[i]] : tables().s[s[i]];
    };
    const auto shift = [&] {
        std::array<Byte, 16> t{};
        for (int c = 0; c < 4; ++c)
            for (int r = 0; r < 4; ++r)
                t[c * 4 + r] = s[((c + (inverse ? 4 - r : r)) % 4) * 4 + r];
        for (int i = 0; i < 16; ++i)
            s[i] = t[i];
    };
    const auto mix = [&] {
        for (int c = 0; c < 4; ++c) {
            Byte *p = s + 4 * c;
            const std::array<Byte, 4> a{p[0], p[1], p[2], p[3]};
            for (int r = 0; r < 4; ++r)
                p[r] = inverse ? mul(a[r], 14) ^ mul(a[(r + 1) % 4], 11) ^ mul(a[(r + 2) % 4], 13) ^
                                     mul(a[(r + 3) % 4], 9)
                               : mul(a[r], 2) ^ mul(a[(r + 1) % 4], 3) ^ a[(r + 2) % 4] ^ a[(r + 3) % 4];
        }
    };
    if (!inverse) {
        add(0);
        for (int r = 1; r <= 10; ++r) {
            sub();
            shift();
            if (r < 10)
                mix();
            add(r);
        }
    } else {
        add(10);
        for (int r = 9; r >= 0; --r) {
            shift();
            sub();
            add(r);
            if (r > 0)
                mix();
        }
    }
}
// Bounded recursive-descent literal reader. JSON values, quoted strings and
// inert True/False/None are admitted; expressions and function calls are rejected.
class LiteralReader {
    QString text;
    qsizetype pos = 0;
    bool valid = true;
    void ws() {
        while (pos < text.size() && text[pos].isSpace())
            ++pos;
    }
    bool take(QChar c) {
        ws();
        if (pos < text.size() && text[pos] == c) {
            ++pos;
            return true;
        }
        return false;
    }
    QString string() {
        ws();
        if (pos >= text.size() || (text[pos] != '\'' && text[pos] != '"')) {
            valid = false;
            return {};
        }
        const auto quote = text[pos++];
        QString out;
        while (pos < text.size()) {
            const auto c = text[pos++];
            if (c == quote)
                return out;
            if (c.unicode() < 32) {
                valid = false;
                return {};
            }
            if (c != '\\') {
                out += c;
                continue;
            }
            if (pos == text.size())
                break;
            const auto e = text[pos++];
            if (e == '\'' || e == '"' || e == '\\' || e == '/')
                out += e;
            else if (e == 'n')
                out += '\n';
            else if (e == 'r')
                out += '\r';
            else if (e == 't')
                out += '\t';
            else if (e == 'b')
                out += '\b';
            else if (e == 'f')
                out += '\f';
            else if (e == 'u' || e == 'x') {
                const int n = e == 'u' ? 4 : 2;
                bool ok = false;
                const auto h = text.mid(pos, n);
                const auto code = h.toUShort(&ok, 16);
                if (h.size() != n || !ok) {
                    valid = false;
                    return {};
                }
                out += QChar(code);
                pos += n;
            } else {
                valid = false;
                return {};
            }
        }
        valid = false;
        return {};
    }
    QJsonValue value(int depth) {
        ws();
        if (depth > 48 || pos >= text.size()) {
            valid = false;
            return {};
        }
        const auto c = text[pos];
        if (c == '\'' || c == '"')
            return string();
        if (take('{')) {
            QJsonObject o;
            if (take('}'))
                return o;
            do {
                auto key = string();
                if (!valid || !take(':')) {
                    valid = false;
                    return {};
                }
                o.insert(key, value(depth + 1));
                if (!valid)
                    return {};
                if (take('}'))
                    return o;
            } while (take(','));
            valid = false;
            return {};
        }
        if (take('[')) {
            QJsonArray a;
            if (take(']'))
                return a;
            do {
                a.append(value(depth + 1));
                if (!valid)
                    return {};
                if (take(']'))
                    return a;
            } while (take(','));
            valid = false;
            return {};
        }
        for (const auto &token : {QStringLiteral("true"), QStringLiteral("false"), QStringLiteral("null"),
                                  QStringLiteral("True"), QStringLiteral("False"), QStringLiteral("None")})
            if (text.mid(pos, token.size()) == token) {
                pos += token.size();
                return token == "null" || token == "None" ? QJsonValue(QJsonValue::Null)
                                                          : QJsonValue(token == "true" || token == "True");
            }
        const auto begin = pos;
        while (pos < text.size() && QStringLiteral("-+0123456789.eE").contains(text[pos]))
            ++pos;
        QJsonParseError p;
        const auto d = QJsonDocument::fromJson(("[" + text.mid(begin, pos - begin) + "]").toUtf8(), &p);
        if (p.error != QJsonParseError::NoError || d.array().size() != 1) {
            valid = false;
            return {};
        }
        return d.array()[0];
    }

  public:
    explicit LiteralReader(const QByteArray &bytes) : text(QString::fromUtf8(bytes)) {}
    QJsonDocument parse(QString &error) {
        const auto v = value(0);
        ws();
        if (!valid || pos != text.size() || (!v.isObject() && !v.isArray())) {
            error = QStringLiteral("歌单响应字面量格式无效。");
            return {};
        }
        return v.isObject() ? QJsonDocument(v.toObject()) : QJsonDocument(v.toArray());
    }
};
} // namespace
namespace CatalogCodec {
QString descriptionText(const QString &text) {
    // Convert metadata to inert text, without rendering HTML or fetching embedded resources.
    QString plain = text.left(16000);
    static const QRegularExpression breaks(
        "<\\s*(?:br\\b[^>]*|/\\s*(?:p|div|li|h[1-6])\\s*)>", QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression tags("<!--[\\s\\S]*?-->|<\\s*/?\\s*[A-Za-z][^>]*>");
    plain.replace(breaks, "\n");
    plain.replace(tags, "");
    static const QRegularExpression entities(
        "&(#(?:[xX][0-9a-fA-F]{1,8}|[0-9]{1,10})|amp|lt|gt|quot|apos|nbsp);");
    static const QHash<QString, QString> named{{"amp", "&"}, {"lt", "<"}, {"gt", ">"},
                                              {"quot", "\""}, {"apos", "'"}, {"nbsp", " "}};
    QString decoded;
    decoded.reserve(plain.size());
    qsizetype offset = 0;
    auto matches = entities.globalMatch(plain);
    while (matches.hasNext()) {
        const auto match = matches.next();
        const auto token = match.captured(1);
        QString value = match.captured();
        if (!token.startsWith('#'))
            value = named.value(token, value);
        else {
            const bool hex = token.size() > 1 && token[1].toLower() == 'x';
            bool valid = false;
            const auto code = token.mid(hex ? 2 : 1).toUInt(&valid, hex ? 16 : 10);
            if (valid && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff) &&
                (code >= 0x20 || code == 9 || code == 10 || code == 13)) {
                const char32_t character = code;
                value = QString::fromUcs4(&character, 1);
            }
        }
        decoded += plain.mid(offset, match.capturedStart() - offset);
        decoded += value;
        offset = match.capturedEnd();
    }
    decoded += plain.mid(offset);
    decoded.replace("\r\n", "\n");
    decoded.replace('\r', '\n');
    QStringList lines;
    for (const auto &line : decoded.split('\n')) {
        const auto trimmed = line.simplified();
        if (!trimmed.isEmpty()) lines.append(trimmed);
    }
    return lines.join('\n').left(4000);
}
QByteArray aesEncrypt(const QByteArray &plain, const QByteArray &key) {
    if (key.size() != 16 || plain.size() > 2 * 1024 * 1024)
        return {};
    auto data = plain;
    const int pad = 16 - data.size() % 16;
    data.append(QByteArray(pad, char(pad)));
    const auto k = expand(key);
    for (qsizetype i = 0; i < data.size(); i += 16)
        block(reinterpret_cast<Byte *>(data.data() + i), k, false);
    return data;
}
QByteArray aesDecrypt(const QByteArray &cipher, const QByteArray &key, QString &error) {
    if (key.size() != 16 || cipher.isEmpty() || cipher.size() % 16 || cipher.size() > 2 * 1024 * 1024) {
        error = QStringLiteral("榜单密文长度无效。");
        return {};
    }
    auto data = cipher;
    const auto k = expand(key);
    for (qsizetype i = 0; i < data.size(); i += 16)
        block(reinterpret_cast<Byte *>(data.data() + i), k, true);
    const int pad = Byte(data.back());
    if (pad < 1 || pad > 16) {
        error = QStringLiteral("榜单密文填充无效。");
        return {};
    }
    for (int i = 0; i < pad; ++i)
        if (Byte(data[data.size() - 1 - i]) != pad) {
            error = QStringLiteral("榜单密文填充无效。");
            return {};
        }
    data.chop(pad);
    return data;
}
QJsonDocument parseLiteral(const QByteArray &bytes, QString &error) {
    if (bytes.size() > 2 * 1024 * 1024) {
        error = QStringLiteral("歌单响应过大。");
        return {};
    }
    return LiteralReader(bytes).parse(error);
}
QByteArray rankingKey() {
    return QByteArray::fromHex("7057273dc7fa29bf39442d72dd5e8ce4");
}
QJsonDocument decodeRanking(const QByteArray &bytes, QString &error) {
    if (bytes.size() > 2 * 1024 * 1024) {
        error = QStringLiteral("榜单响应过大。");
        return {};
    }
    const auto encoded = QByteArray::fromPercentEncoding(bytes.trimmed());
    const auto result = QByteArray::fromBase64Encoding(encoded, QByteArray::AbortOnBase64DecodingErrors);
    if (!result) {
        error = QStringLiteral("榜单 Base64 格式无效。");
        return {};
    }
    const auto plain = aesDecrypt(result.decoded, rankingKey(), error);
    if (!error.isEmpty())
        return {};
    QJsonParseError p;
    const auto doc = QJsonDocument::fromJson(plain, &p);
    if (p.error != QJsonParseError::NoError || !doc.isObject()) {
        error = QStringLiteral("榜单解密响应格式无效。");
        return {};
    }
    return doc;
}
} // namespace CatalogCodec
