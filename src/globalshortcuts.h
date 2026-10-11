#pragma once

#include <QAbstractNativeEventFilter>
#include <QKeySequence>
#include <QObject>
#include <QStringList>
#include <QVariantList>
#include <memory>

class GlobalShortcuts : public QObject, public QAbstractNativeEventFilter {
    Q_OBJECT
    Q_PROPERTY(QVariantList bindings READ bindings NOTIFY bindingsChanged)
    Q_PROPERTY(QString message READ message NOTIFY messageChanged)
    Q_PROPERTY(bool nativeEnabled READ nativeEnabled CONSTANT)
    Q_PROPERTY(bool capturing READ capturing NOTIFY capturingChanged)

public:
    // Owns successful registrations until unregisterShortcut is called. A zero
    // result must leave existing registrations untouched and describe the error.
    class Registrar {
    public:
        virtual ~Registrar() = default;
        virtual int registerShortcut(const QKeySequence &sequence, QString *error) = 0;
        virtual void unregisterShortcut(int id) = 0;
    };

    explicit GlobalShortcuts(bool nativeEnabled = true, QObject *parent = nullptr);
    explicit GlobalShortcuts(std::unique_ptr<Registrar> registrar, QObject *parent = nullptr);
    ~GlobalShortcuts() override;

    QVariantList bindings() const;
    QString message() const { return m_message; }
    bool nativeEnabled() const { return bool(m_registrar); }
    bool capturing() const { return m_capturing; }
    Q_INVOKABLE bool setBinding(const QString &action, const QString &sequence);
    Q_INVOKABLE bool resetDefaults();
    Q_INVOKABLE QString captureSequence(int key, int modifiers) const;
    Q_INVOKABLE void setCapturing(bool active);
    bool nativeEventFilter(const QByteArray &eventType, void *message, qintptr *result) override;

signals:
    void bindingsChanged();
    void messageChanged();
    void capturingChanged();
    void activated(const QString &action);

private:
    struct Binding {
        QString action;
        QString label;
        QString sequence;
        QString defaultSequence;
        int registrationId = 0;
        QString error;
    };
    void initialize();
    bool apply(const QStringList &sequences, int changedIndex = -1);
    bool fail(int index, const QString &error);
    void setMessage(const QString &message);

    QList<Binding> m_bindings;
    std::unique_ptr<Registrar> m_registrar;
    QString m_message;
    bool m_capturing = false;
};
