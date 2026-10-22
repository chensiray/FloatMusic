#pragma once
#include <QObject>

// UI language only. Track metadata and lyric content never pass through here.
class UiLanguage : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString code READ code WRITE setCode NOTIFY changed)
public:
    explicit UiLanguage(QObject *parent = nullptr);
    QString code() const { return m_code; }
    void setCode(const QString &code);
    Q_INVOKABLE QString text(const QString &source) const;
    Q_INVOKABLE void refresh();
signals:
    void changed();
private:
    QString m_code;
};
