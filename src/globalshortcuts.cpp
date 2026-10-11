#include "globalshortcuts.h"

#include <QCoreApplication>
#include <QMap>
#include <QSettings>
#include <QSet>

#ifdef Q_OS_WIN
#include <QMutex>
#include <QMutexLocker>
#include <qt_windows.h>
#endif

namespace {
bool normalizeSequence(const QString &input, QString *normalized, QString *error)
{
    const QString text = input.trimmed();
    if (text.isEmpty()) {
        normalized->clear();
        return true;
    }

    const QKeySequence sequence = QKeySequence::fromString(text, QKeySequence::PortableText);
    const auto combination = sequence[0];
    const auto key = combination.key();
    if (sequence.count() != 1 || key == Qt::Key_unknown || key == Qt::Key(0)
        || key == Qt::Key_Control || key == Qt::Key_Alt || key == Qt::Key_Meta
        || key == Qt::Key_Shift || key == Qt::Key_AltGr) {
        *error = QCoreApplication::translate("GlobalShortcuts", "请设置单个有效按键组合");
        return false;
    }
    if (!(combination.keyboardModifiers()
          & (Qt::ControlModifier | Qt::AltModifier | Qt::MetaModifier))) {
        *error = QCoreApplication::translate("GlobalShortcuts", "快捷键必须包含 Ctrl、Alt 或 Win / Meta");
        return false;
    }
    *normalized = sequence.toString(QKeySequence::PortableText);
    return true;
}

#ifdef Q_OS_WIN
bool nativeCombination(const QKeySequence &sequence, UINT *modifiers, UINT *virtualKey)
{
    if (sequence.count() != 1)
        return false;
    const auto combination = sequence[0];
    const auto qtModifiers = combination.keyboardModifiers();
    *modifiers = 0;
    if (qtModifiers & Qt::ControlModifier) *modifiers |= MOD_CONTROL;
    if (qtModifiers & Qt::AltModifier) *modifiers |= MOD_ALT;
    if (qtModifiers & Qt::ShiftModifier) *modifiers |= MOD_SHIFT;
    if (qtModifiers & Qt::MetaModifier) *modifiers |= MOD_WIN;

    const int key = combination.key();
    if (qtModifiers & Qt::KeypadModifier) {
        if (key >= Qt::Key_0 && key <= Qt::Key_9) {
            *virtualKey = VK_NUMPAD0 + key - Qt::Key_0;
            return true;
        }
        switch (key) {
        case Qt::Key_Asterisk: *virtualKey = VK_MULTIPLY; return true;
        case Qt::Key_Plus: *virtualKey = VK_ADD; return true;
        case Qt::Key_Minus: *virtualKey = VK_SUBTRACT; return true;
        case Qt::Key_Period: *virtualKey = VK_DECIMAL; return true;
        case Qt::Key_Slash: *virtualKey = VK_DIVIDE; return true;
        }
    }
    if ((key >= Qt::Key_A && key <= Qt::Key_Z) || (key >= Qt::Key_0 && key <= Qt::Key_9)) {
        *virtualKey = UINT(key);
        return true;
    }
    if (key >= Qt::Key_F1 && key <= Qt::Key_F24) {
        *virtualKey = VK_F1 + key - Qt::Key_F1;
        return true;
    }
    switch (key) {
    case Qt::Key_Space: *virtualKey = VK_SPACE; return true;
    case Qt::Key_Left: *virtualKey = VK_LEFT; return true;
    case Qt::Key_Right: *virtualKey = VK_RIGHT; return true;
    case Qt::Key_Up: *virtualKey = VK_UP; return true;
    case Qt::Key_Down: *virtualKey = VK_DOWN; return true;
    case Qt::Key_Home: *virtualKey = VK_HOME; return true;
    case Qt::Key_End: *virtualKey = VK_END; return true;
    case Qt::Key_PageUp: *virtualKey = VK_PRIOR; return true;
    case Qt::Key_PageDown: *virtualKey = VK_NEXT; return true;
    case Qt::Key_Insert: *virtualKey = VK_INSERT; return true;
    case Qt::Key_Delete: *virtualKey = VK_DELETE; return true;
    case Qt::Key_Backspace: *virtualKey = VK_BACK; return true;
    case Qt::Key_Tab: *virtualKey = VK_TAB; return true;
    case Qt::Key_Backtab: *virtualKey = VK_TAB; *modifiers |= MOD_SHIFT; return true;
    case Qt::Key_Return:
    case Qt::Key_Enter: *virtualKey = VK_RETURN; return true;
    case Qt::Key_Escape: *virtualKey = VK_ESCAPE; return true;
    case Qt::Key_Pause: *virtualKey = VK_PAUSE; return true;
    case Qt::Key_Print: *virtualKey = VK_SNAPSHOT; return true;
    case Qt::Key_CapsLock: *virtualKey = VK_CAPITAL; return true;
    case Qt::Key_NumLock: *virtualKey = VK_NUMLOCK; return true;
    case Qt::Key_ScrollLock: *virtualKey = VK_SCROLL; return true;
    case Qt::Key_Clear: *virtualKey = VK_CLEAR; return true;
    case Qt::Key_Menu: *virtualKey = VK_APPS; return true;
    case Qt::Key_Help: *virtualKey = VK_HELP; return true;
    case Qt::Key_VolumeUp: *virtualKey = VK_VOLUME_UP; return true;
    case Qt::Key_VolumeDown: *virtualKey = VK_VOLUME_DOWN; return true;
    case Qt::Key_VolumeMute: *virtualKey = VK_VOLUME_MUTE; return true;
    case Qt::Key_MediaNext: *virtualKey = VK_MEDIA_NEXT_TRACK; return true;
    case Qt::Key_MediaPrevious: *virtualKey = VK_MEDIA_PREV_TRACK; return true;
    case Qt::Key_MediaStop: *virtualKey = VK_MEDIA_STOP; return true;
    case Qt::Key_MediaPlay:
    case Qt::Key_MediaPause:
    case Qt::Key_MediaTogglePlayPause: *virtualKey = VK_MEDIA_PLAY_PAUSE; return true;
    }
    if (key > 0 && key < 0x10000) {
        const SHORT mappedKey = VkKeyScanExW(WCHAR(key), GetKeyboardLayout(0));
        if (mappedKey != -1) {
            *virtualKey = LOBYTE(mappedKey);
            const BYTE shiftState = HIBYTE(mappedKey);
            if (shiftState & 1) *modifiers |= MOD_SHIFT;
            if (shiftState & 2) *modifiers |= MOD_CONTROL;
            if (shiftState & 4) *modifiers |= MOD_ALT;
            return true;
        }
    }
    return false;
}

QMutex nativeIdMutex;
QSet<int> nativeIds;
int nextNativeId = 0x4000;

class WindowsRegistrar final : public GlobalShortcuts::Registrar {
public:
    ~WindowsRegistrar() override
    {
        const auto remaining = m_ids;
        for (int id : remaining)
            unregisterShortcut(id);
    }

    int registerShortcut(const QKeySequence &sequence, QString *error) override
    {
        UINT modifiers = 0;
        UINT key = 0;
        if (!nativeCombination(sequence, &modifiers, &key)) {
            *error = QCoreApplication::translate("GlobalShortcuts", "此按键暂不支持全局快捷键");
            return 0;
        }

        const QMutexLocker locker(&nativeIdMutex);
        for (int attempt = 0; attempt < 0x8000; ++attempt) {
            const int id = nextNativeId++;
            if (nextNativeId > 0xbfff) nextNativeId = 0x4000;
            if (nativeIds.contains(id)) continue;
            if (!RegisterHotKey(nullptr, id, modifiers | MOD_NOREPEAT, key)) {
                const DWORD nativeError = GetLastError();
                *error = nativeError == ERROR_HOTKEY_ALREADY_REGISTERED
                    ? QCoreApplication::translate("GlobalShortcuts", "快捷键已被其他程序占用")
                    : QCoreApplication::translate("GlobalShortcuts", "无法注册全局快捷键（错误 %1）").arg(nativeError);
                return 0;
            }
            nativeIds.insert(id);
            m_ids.insert(id);
            return id;
        }
        *error = QCoreApplication::translate("GlobalShortcuts", "全局快捷键数量已达到系统上限");
        return 0;
    }

    void unregisterShortcut(int id) override
    {
        const QMutexLocker locker(&nativeIdMutex);
        if (!m_ids.remove(id)) return;
        UnregisterHotKey(nullptr, id);
        nativeIds.remove(id);
    }

private:
    QSet<int> m_ids;
};
#endif
}

GlobalShortcuts::GlobalShortcuts(bool nativeEnabled, QObject *parent)
    : QObject(parent)
{
#ifdef Q_OS_WIN
    if (nativeEnabled)
        m_registrar = std::make_unique<WindowsRegistrar>();
#else
    Q_UNUSED(nativeEnabled);
#endif
    initialize();
}

GlobalShortcuts::GlobalShortcuts(std::unique_ptr<Registrar> registrar, QObject *parent)
    : QObject(parent), m_registrar(std::move(registrar))
{
    initialize();
}

GlobalShortcuts::~GlobalShortcuts()
{
    if (!m_registrar) return;
    if (auto *application = QCoreApplication::instance())
        application->removeNativeEventFilter(this);
    for (const auto &binding : m_bindings) {
        if (binding.registrationId)
            m_registrar->unregisterShortcut(binding.registrationId);
    }
}

void GlobalShortcuts::initialize()
{
    const auto add = [this](const char *action, const QString &label, const char *sequence) {
        const QString defaultSequence = QString::fromLatin1(sequence);
        m_bindings.append({QString::fromLatin1(action), label, defaultSequence, defaultSequence, 0, {}});
    };
    add("togglePlay", tr("播放 / 暂停"), "Ctrl+Alt+Space");
    add("previous", tr("上一首"), "Ctrl+Alt+Left");
    add("next", tr("下一首"), "Ctrl+Alt+Right");
    add("volumeUp", tr("增大音量"), "Ctrl+Alt+Up");
    add("volumeDown", tr("减小音量"), "Ctrl+Alt+Down");
    add("toggleWindow", tr("显示 / 隐藏窗口"), "Ctrl+Alt+M");

    QSettings settings;
    for (auto &binding : m_bindings) {
        const QString saved = settings.value(QStringLiteral("shortcuts/") + binding.action,
                                             binding.defaultSequence).toString();
        QString normalized;
        QString error;
        if (normalizeSequence(saved, &normalized, &error)) {
            binding.sequence = normalized;
        } else {
            binding.error = tr("保存的快捷键无效，已恢复默认设置");
            setMessage(binding.label + QStringLiteral("：") + binding.error);
        }
    }

    // Read the complete saved set before resolving duplicates, so saved swaps
    // remain valid. Each repair restores one custom value to its unique default.
    bool repaired;
    do {
        repaired = false;
        QMap<QString, QList<int>> duplicates;
        for (int i = 0; i < m_bindings.size(); ++i) {
            if (!m_bindings[i].sequence.isEmpty())
                duplicates[m_bindings[i].sequence].append(i);
        }
        for (const auto &indices : duplicates) {
            if (indices.size() < 2) continue;
            int keep = indices.first();
            for (int index : indices) {
                if (m_bindings[index].sequence == m_bindings[index].defaultSequence) {
                    keep = index;
                    break;
                }
            }
            for (int index : indices) {
                if (index == keep) continue;
                auto &binding = m_bindings[index];
                binding.sequence = binding.defaultSequence;
                binding.error = tr("保存的快捷键重复，已恢复默认设置");
                setMessage(binding.label + QStringLiteral("：") + binding.error);
                repaired = true;
            }
        }
    } while (repaired);

    if (m_registrar) {
        for (auto &binding : m_bindings) {
            if (binding.sequence.isEmpty()) continue;
            QString error;
            binding.registrationId = m_registrar->registerShortcut(
                QKeySequence::fromString(binding.sequence, QKeySequence::PortableText), &error);
            if (!binding.registrationId) {
                binding.error = error.isEmpty() ? tr("无法注册全局快捷键") : error;
                setMessage(binding.label + QStringLiteral("：") + binding.error);
            }
        }
        if (auto *application = QCoreApplication::instance())
            application->installNativeEventFilter(this);
    }
}

QVariantList GlobalShortcuts::bindings() const
{
    QVariantList result;
    for (const auto &binding : m_bindings) {
        result.append(QVariantMap{{QStringLiteral("action"), binding.action},
                                  {QStringLiteral("label"), binding.label},
                                  {QStringLiteral("sequence"), binding.sequence},
                                  {QStringLiteral("defaultSequence"), binding.defaultSequence},
                                  {QStringLiteral("registered"), binding.registrationId != 0},
                                  {QStringLiteral("error"), binding.error}});
    }
    return result;
}

bool GlobalShortcuts::setBinding(const QString &action, const QString &sequence)
{
    int index = -1;
    QStringList desired;
    for (int i = 0; i < m_bindings.size(); ++i) {
        desired.append(m_bindings[i].sequence);
        if (m_bindings[i].action == action) index = i;
    }
    if (index < 0) return fail(-1, tr("未知快捷键操作"));
    QString normalized;
    QString error;
    if (!normalizeSequence(sequence, &normalized, &error)) return fail(index, error);
    if (!normalized.isEmpty()) {
        for (int i = 0; i < desired.size(); ++i) {
            if (i != index && desired[i] == normalized)
                return fail(index, tr("快捷键已用于“%1”").arg(m_bindings[i].label));
        }
    }
    desired[index] = normalized;
    return apply(desired, index);
}

bool GlobalShortcuts::resetDefaults()
{
    QStringList desired;
    for (const auto &binding : m_bindings)
        desired.append(binding.defaultSequence);
    return apply(desired);
}

bool GlobalShortcuts::apply(const QStringList &sequences, int changedIndex)
{
    QList<int> desiredIds(m_bindings.size(), 0);
    QList<int> acquired;
    QMap<QString, int> existing;
    for (const auto &binding : m_bindings) {
        if (binding.registrationId)
            existing.insert(binding.sequence, binding.registrationId);
    }

    if (m_registrar) {
        for (int i = 0; i < m_bindings.size(); ++i) {
            if (sequences[i].isEmpty()) continue;
            desiredIds[i] = existing.value(sequences[i]);
            if (desiredIds[i]) continue;
            if (changedIndex >= 0 && i != changedIndex
                && sequences[i] == m_bindings[i].sequence) continue;
            QString error;
            const int id = m_registrar->registerShortcut(
                QKeySequence::fromString(sequences[i], QKeySequence::PortableText), &error);
            if (!id) {
                for (int acquiredId : acquired)
                    m_registrar->unregisterShortcut(acquiredId);
                return fail(i, error.isEmpty() ? tr("无法注册全局快捷键") : error);
            }
            desiredIds[i] = id;
            acquired.append(id);
        }

        // Keep the old registrations until every new one has succeeded. Keys
        // reused by another action keep their IDs throughout a defaults reset.
        if (m_capturing) {
            // Capture keeps the OS keys free for the editor. Probe proposed
            // registrations above, then release them without activating them.
            for (int id : acquired)
                m_registrar->unregisterShortcut(id);
            desiredIds.fill(0);
        } else {
            const QSet<int> retained(desiredIds.cbegin(), desiredIds.cend());
            for (const auto &binding : m_bindings) {
                if (binding.registrationId && !retained.contains(binding.registrationId))
                    m_registrar->unregisterShortcut(binding.registrationId);
            }
        }
    }

    QSettings settings;
    for (int i = 0; i < m_bindings.size(); ++i) {
        auto &binding = m_bindings[i];
        binding.sequence = sequences[i];
        binding.registrationId = desiredIds[i];
        if (changedIndex < 0 || i == changedIndex)
            binding.error.clear();
        settings.setValue(QStringLiteral("shortcuts/") + binding.action, binding.sequence);
    }
    settings.sync();
    QString remainingError;
    for (const auto &binding : m_bindings) {
        if (!binding.error.isEmpty()) {
            remainingError = binding.label + QStringLiteral("：") + binding.error;
            break;
        }
    }
    setMessage(remainingError);
    emit bindingsChanged();
    return true;
}

bool GlobalShortcuts::fail(int index, const QString &error)
{
    if (index >= 0) {
        m_bindings[index].error = error;
        setMessage(m_bindings[index].label + QStringLiteral("：") + error);
        emit bindingsChanged();
    } else {
        setMessage(error);
    }
    return false;
}

void GlobalShortcuts::setMessage(const QString &message)
{
    if (m_message == message) return;
    m_message = message;
    emit messageChanged();
}

QString GlobalShortcuts::captureSequence(int key, int modifiers) const
{
    const auto acceptedModifiers = Qt::KeyboardModifiers(modifiers)
        & (Qt::ControlModifier | Qt::AltModifier | Qt::MetaModifier | Qt::ShiftModifier | Qt::KeypadModifier);
    const QKeySequence captured(QKeyCombination(acceptedModifiers, Qt::Key(key)));
    QString normalized;
    QString error;
    if (!normalizeSequence(captured.toString(QKeySequence::PortableText), &normalized, &error))
        return {};
    return normalized;
}

void GlobalShortcuts::setCapturing(bool active)
{
    if (m_capturing == active) return;
    m_capturing = active;
    if (m_registrar) {
        if (active) {
            for (auto &binding : m_bindings) {
                if (binding.registrationId)
                    m_registrar->unregisterShortcut(binding.registrationId);
                binding.registrationId = 0;
            }
        } else {
            setMessage({});
            for (auto &binding : m_bindings) {
                binding.error.clear();
                if (binding.sequence.isEmpty()) continue;
                QString error;
                binding.registrationId = m_registrar->registerShortcut(
                    QKeySequence::fromString(binding.sequence, QKeySequence::PortableText), &error);
                if (!binding.registrationId) {
                    binding.error = error.isEmpty() ? tr("无法注册全局快捷键") : error;
                    setMessage(binding.label + QStringLiteral("：") + binding.error);
                }
            }
        }
    }
    emit bindingsChanged();
    emit capturingChanged();
}

bool GlobalShortcuts::nativeEventFilter(const QByteArray &eventType, void *message, qintptr *result)
{
#ifdef Q_OS_WIN
    if (!m_registrar || m_capturing || !message
        || (eventType != "windows_dispatcher_MSG" && eventType != "windows_generic_MSG")) return false;
    const auto *nativeMessage = static_cast<const MSG *>(message);
    if (nativeMessage->message != WM_HOTKEY || nativeMessage->hwnd) return false;
    for (const auto &binding : m_bindings) {
        if (!binding.registrationId || WPARAM(binding.registrationId) != nativeMessage->wParam) continue;
        UINT modifiers = 0;
        UINT key = 0;
        if (!nativeCombination(QKeySequence::fromString(binding.sequence, QKeySequence::PortableText),
                               &modifiers, &key)
            || LOWORD(nativeMessage->lParam) != modifiers || HIWORD(nativeMessage->lParam) != key) return false;
        if (result) *result = 0;
        emit activated(binding.action);
        return true;
    }
#else
    Q_UNUSED(eventType);
    Q_UNUSED(message);
    Q_UNUSED(result);
#endif
    return false;
}
