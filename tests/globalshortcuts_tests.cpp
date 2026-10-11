#include <QtTest>
#include <QSettings>
#include <QTemporaryDir>
#include <QSignalSpy>
#include <memory>
#include "globalshortcuts.h"

#ifdef Q_OS_WIN
#include <qt_windows.h>
#endif

namespace {
struct RegistryState {
    int nextId = 400;
    QMap<int, QString> active;
    QSet<QString> occupied;
    QStringList acquired;
    QList<int> released;
};

// Only the OS resource boundary is replaced: configuration, validation,
// persistence, rollback and native message dispatch all use production code.
class FakeRegistrar final : public GlobalShortcuts::Registrar {
public:
    explicit FakeRegistrar(std::shared_ptr<RegistryState> state) : state(std::move(state)) {}
    int registerShortcut(const QKeySequence &sequence, QString *error) override {
        const auto text = sequence.toString(QKeySequence::PortableText);
        if (state->occupied.contains(text) || state->active.values().contains(text)) {
            *error = QStringLiteral("快捷键已被其他程序占用");
            return 0;
        }
        const int id = ++state->nextId;
        state->active.insert(id, text);
        state->acquired.append(text);
        return id;
    }
    void unregisterShortcut(int id) override {
        state->active.remove(id);
        state->released.append(id);
    }
    std::shared_ptr<RegistryState> state;
};

QVariantMap row(const GlobalShortcuts &shortcuts, const QString &action) {
    for (const auto &value : shortcuts.bindings()) {
        const auto binding = value.toMap();
        if (binding.value("action").toString() == action) return binding;
    }
    return {};
}
QString sequence(const GlobalShortcuts &shortcuts, const QString &action) {
    return row(shortcuts, action).value("sequence").toString();
}
std::unique_ptr<GlobalShortcuts::Registrar> registrar(const std::shared_ptr<RegistryState> &state) {
    return std::make_unique<FakeRegistrar>(state);
}
}

class GlobalShortcutsTests : public QObject {
    Q_OBJECT
private slots:
    void initTestCase() {
        QVERIFY(settingsDirectory.isValid());
        QCoreApplication::setOrganizationName("FloatMusicHotkeyTests");
        QCoreApplication::setApplicationName("IsolatedShortcuts");
        QSettings::setDefaultFormat(QSettings::IniFormat);
        QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, settingsDirectory.path());
    }
    void init() { QSettings().clear(); }

    void disabledNativeModeRemainsEditableAndPersists() {
        {
            GlobalShortcuts shortcuts(false);
            QVERIFY(!shortcuts.nativeEnabled());
            QCOMPARE(shortcuts.bindings().size(), 6);
            QVERIFY(shortcuts.setBinding("togglePlay", "ctrl+alt+p"));
            QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+P");
            QVERIFY(!row(shortcuts, "togglePlay").value("registered").toBool());
            QVERIFY(row(shortcuts, "togglePlay").value("error").toString().isEmpty());
            QVERIFY(shortcuts.setBinding("next", ""));
        }
        GlobalShortcuts reopened(false);
        QCOMPARE(sequence(reopened, "togglePlay"), "Ctrl+Alt+P");
        QCOMPARE(sequence(reopened, "next"), "");
        QCOMPARE(QSettings().value("shortcuts/togglePlay").toString(), "Ctrl+Alt+P");
        QVERIFY(reopened.resetDefaults());
        GlobalShortcuts defaults(false);
        QCOMPARE(sequence(defaults, "togglePlay"), "Ctrl+Alt+Space");
        QCOMPARE(sequence(defaults, "next"), "Ctrl+Alt+Right");
    }

    void rejectsInvalidAndDuplicateBindingsWithoutChangingRegistration() {
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        const auto original = state->active;
        for (const QString &invalid : {QString("P"), QString("Shift+P"), QString("Ctrl"),
             QString("Ctrl+Alt"), QString("Ctrl+P, Ctrl+N"), QString("Ctrl+NotAKey")}) {
            QVERIFY2(!shortcuts.setBinding("togglePlay", invalid), qPrintable(invalid));
            QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Space");
            QVERIFY(!shortcuts.message().isEmpty());
            QCOMPARE(state->active, original);
        }
        QVERIFY(!shortcuts.setBinding("togglePlay", "ctrl+alt+left"));
        QVERIFY(!row(shortcuts, "togglePlay").value("error").toString().isEmpty());
        QVERIFY(!shortcuts.setBinding("unknown", "Ctrl+Alt+P"));
        QCOMPARE(state->active, original);
        QVERIFY(shortcuts.setBinding("togglePlay", "Meta+Shift+P"));
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Meta+Shift+P");
        QVERIFY(row(shortcuts, "togglePlay").value("error").toString().isEmpty());
    }

    void capturesOnlyUsableSingleCombinations() {
        GlobalShortcuts shortcuts(false);
        QCOMPARE(shortcuts.captureSequence(Qt::Key_P, Qt::ControlModifier | Qt::AltModifier), "Ctrl+Alt+P");
        QCOMPARE(shortcuts.captureSequence(Qt::Key_F8, Qt::MetaModifier | Qt::ShiftModifier), "Meta+Shift+F8");
        QCOMPARE(shortcuts.captureSequence(Qt::Key_Left, Qt::ControlModifier | Qt::AltModifier), "Ctrl+Alt+Left");
        QVERIFY(shortcuts.captureSequence(Qt::Key_Control, Qt::ControlModifier).isEmpty());
        QVERIFY(shortcuts.captureSequence(Qt::Key_Alt, Qt::ControlModifier | Qt::AltModifier).isEmpty());
        QVERIFY(shortcuts.captureSequence(Qt::Key_P, Qt::ShiftModifier).isEmpty());
        QVERIFY(shortcuts.captureSequence(Qt::Key_unknown, Qt::ControlModifier).isEmpty());
    }

    void operatingSystemConflictKeepsOldKeyAndSavedConfiguration() {
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+P"));
        const auto before = state->active;
        state->occupied.insert("Ctrl+Alt+N");
        QSignalSpy changes(&shortcuts, &GlobalShortcuts::bindingsChanged);
        QVERIFY(!shortcuts.setBinding("togglePlay", "Ctrl+Alt+N"));
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+P");
        QCOMPARE(QSettings().value("shortcuts/togglePlay").toString(), "Ctrl+Alt+P");
        QCOMPARE(state->active, before);
        QVERIFY(row(shortcuts, "togglePlay").value("registered").toBool());
        QVERIFY(shortcuts.message().contains(QStringLiteral("占用")));
        QVERIFY(changes.size() > 0);
    }

    void successfulEditLeavesUnrelatedRegistrationsIntactAndReleasesOwnedKeys() {
        auto state = std::make_shared<RegistryState>();
        QMap<int, QString> before;
        {
            GlobalShortcuts shortcuts(registrar(state));
            before = state->active;
            QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+P"));
            for (auto it = before.cbegin(); it != before.cend(); ++it) {
                if (it.value() != "Ctrl+Alt+Space") QCOMPARE(state->active.value(it.key()), it.value());
            }
            QCOMPARE(state->released.size(), 1);
            const auto acquired = state->acquired.size();
            QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+P"));
            QCOMPARE(state->acquired.size(), acquired);
            QVERIFY(shortcuts.setBinding("togglePlay", ""));
            QVERIFY(!row(shortcuts, "togglePlay").value("registered").toBool());
            QVERIFY(!state->active.values().contains("Ctrl+Alt+P"));
        }
        QVERIFY(state->active.isEmpty());
    }

    void defaultsConflictRollsBackEntireOperation() {
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+P"));
        QVERIFY(shortcuts.setBinding("next", "Ctrl+Alt+N"));
        const auto before = state->active;
        state->occupied.insert("Ctrl+Alt+Right");
        QVERIFY(!shortcuts.resetDefaults());
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+P");
        QCOMPARE(sequence(shortcuts, "next"), "Ctrl+Alt+N");
        QCOMPARE(state->active, before);
        QCOMPARE(QSettings().value("shortcuts/togglePlay").toString(), "Ctrl+Alt+P");
        QVERIFY(!state->active.values().contains("Ctrl+Alt+Space"));
        state->occupied.clear();
        QVERIFY(shortcuts.resetDefaults());
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Space");
        QCOMPARE(sequence(shortcuts, "next"), "Ctrl+Alt+Right");
    }

    void defaultsCanReuseKeysCurrentlyAssignedToAnotherAction() {
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(shortcuts.setBinding("togglePlay", ""));
        QVERIFY(shortcuts.setBinding("next", "Ctrl+Alt+Space"));
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+Right"));
        const auto before = state->active;
        const auto acquisitions = state->acquired.size();
        QVERIFY(shortcuts.resetDefaults());
        QCOMPARE(state->active, before);
        QCOMPARE(state->acquired.size(), acquisitions);
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Space");
        QCOMPARE(sequence(shortcuts, "next"), "Ctrl+Alt+Right");
    }

    void startupConflictsAreVisibleAndCanBeRetried() {
        auto state = std::make_shared<RegistryState>();
        state->occupied.insert("Ctrl+Alt+Space");
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(!row(shortcuts, "togglePlay").value("registered").toBool());
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Space");
        QVERIFY(!shortcuts.message().isEmpty());
        QVERIFY(row(shortcuts, "next").value("registered").toBool());
        state->occupied.clear();
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+Space"));
        QVERIFY(row(shortcuts, "togglePlay").value("registered").toBool());
    }

    void invalidSavedConfigurationNeverRegistersConflictingBindings() {
        QSettings().setValue("shortcuts/togglePlay", "Ctrl+P, Ctrl+N");
        QSettings().setValue("shortcuts/next", "Ctrl+Alt+Left");
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(!shortcuts.message().isEmpty());
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Space");
        QCOMPARE(sequence(shortcuts, "next"), "Ctrl+Alt+Right");
        QCOMPARE(state->active.size(), 6);
    }

    void savedSwapsAndDisabledKeysReloadAsPortableText() {
        QSettings().setValue("shortcuts/togglePlay", "ctrl+alt+right");
        QSettings().setValue("shortcuts/next", "ctrl+alt+space");
        QSettings().setValue("shortcuts/volumeUp", "");
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Right");
        QCOMPARE(sequence(shortcuts, "next"), "Ctrl+Alt+Space");
        QCOMPARE(sequence(shortcuts, "volumeUp"), "");
        QCOMPARE(state->active.size(), 5);
        QVERIFY(shortcuts.message().isEmpty());
        QVERIFY(shortcuts.setBinding("volumeDown", " Ctrl+Alt+V "));
        QCOMPARE(QSettings().value("shortcuts/togglePlay").toString(), "Ctrl+Alt+Right");
        QCOMPARE(QSettings().value("shortcuts/volumeUp").toString(), "");
    }

    void editingAnotherActionDoesNotRetryAnUnrelatedStartupConflict() {
        auto state = std::make_shared<RegistryState>();
        state->occupied.insert("Ctrl+Alt+Space");
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(shortcuts.setBinding("next", "Ctrl+Alt+N"));
        QCOMPARE(sequence(shortcuts, "next"), "Ctrl+Alt+N");
        QVERIFY(row(shortcuts, "next").value("registered").toBool());
        QVERIFY(!row(shortcuts, "togglePlay").value("registered").toBool());
        QVERIFY(!row(shortcuts, "togglePlay").value("error").toString().isEmpty());
    }

    void captureSuspendsOwnedRegistrationsAndRestoresConfiguredKeys() {
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        const int spaceId = state->active.key("Ctrl+Alt+Space");
        QSignalSpy capturingChanges(&shortcuts, &GlobalShortcuts::capturingChanged);
        QSignalSpy activated(&shortcuts, &GlobalShortcuts::activated);
        QVERIFY(!shortcuts.capturing());
        shortcuts.setCapturing(true);
        QVERIFY(shortcuts.capturing());
        QVERIFY(state->active.isEmpty());
        QVERIFY(!row(shortcuts, "togglePlay").value("registered").toBool());
        const auto releases = state->released.size();
        shortcuts.setCapturing(true);
        QCOMPARE(state->released.size(), releases);
#ifdef Q_OS_WIN
        MSG message{};
        message.message = WM_HOTKEY;
        message.wParam = WPARAM(spaceId);
        message.lParam = MAKELPARAM(MOD_CONTROL | MOD_ALT, VK_SPACE);
        qintptr result = 81;
        QVERIFY(!shortcuts.nativeEventFilter("windows_dispatcher_MSG", &message, &result));
        QCOMPARE(result, qintptr(81));
        QVERIFY(activated.isEmpty());
#else
        Q_UNUSED(spaceId);
#endif
        shortcuts.setCapturing(false);
        QVERIFY(!shortcuts.capturing());
        QCOMPARE(state->active.size(), 6);
        QVERIFY(row(shortcuts, "togglePlay").value("registered").toBool());
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Space");
        const auto acquisitions = state->acquired.size();
        shortcuts.setCapturing(false);
        QCOMPARE(state->acquired.size(), acquisitions);
        QCOMPARE(capturingChanges.size(), 2);
    }

    void editsDuringCaptureProbeConflictsAndKeepRegistrationsSuspended() {
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+P"));
        shortcuts.setCapturing(true);
        state->occupied.insert("Ctrl+Alt+N");
        QVERIFY(!shortcuts.setBinding("togglePlay", "Ctrl+Alt+N"));
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+P");
        QCOMPARE(QSettings().value("shortcuts/togglePlay").toString(), "Ctrl+Alt+P");
        QVERIFY(state->active.isEmpty());
        QVERIFY(!shortcuts.message().isEmpty());
        state->occupied.clear();
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+N"));
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+N");
        QCOMPARE(QSettings().value("shortcuts/togglePlay").toString(), "Ctrl+Alt+N");
        QVERIFY(state->active.isEmpty());
        QVERIFY(!row(shortcuts, "togglePlay").value("registered").toBool());
        shortcuts.setCapturing(false);
        QVERIFY(state->active.values().contains("Ctrl+Alt+N"));
        QVERIFY(!state->active.values().contains("Ctrl+Alt+P"));
    }

    void captureRestorationReportsConflictsAndPreservesSavedSequences() {
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+Space"));
        shortcuts.setCapturing(true);
        state->occupied.insert("Ctrl+Alt+Space");
        shortcuts.setCapturing(false);
        QVERIFY(!shortcuts.capturing());
        QVERIFY(!row(shortcuts, "togglePlay").value("registered").toBool());
        QVERIFY(!row(shortcuts, "togglePlay").value("error").toString().isEmpty());
        QVERIFY(row(shortcuts, "next").value("registered").toBool());
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Space");
        QCOMPARE(QSettings().value("shortcuts/togglePlay").toString(), "Ctrl+Alt+Space");
        QVERIFY(shortcuts.message().contains(QStringLiteral("占用")));
    }

    void capturedDefaultsProbeEntireSetAndRollBackOnConflict() {
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+P"));
        QVERIFY(shortcuts.setBinding("next", "Ctrl+Alt+N"));
        shortcuts.setCapturing(true);
        state->occupied.insert("Ctrl+Alt+Right");
        QVERIFY(!shortcuts.resetDefaults());
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+P");
        QCOMPARE(sequence(shortcuts, "next"), "Ctrl+Alt+N");
        QCOMPARE(QSettings().value("shortcuts/togglePlay").toString(), "Ctrl+Alt+P");
        QVERIFY(state->active.isEmpty());
        state->occupied.clear();
        QVERIFY(shortcuts.resetDefaults());
        QCOMPARE(sequence(shortcuts, "togglePlay"), "Ctrl+Alt+Space");
        QCOMPARE(sequence(shortcuts, "next"), "Ctrl+Alt+Right");
        QVERIFY(state->active.isEmpty());
        shortcuts.setCapturing(false);
        QCOMPARE(state->active.size(), 6);
    }

    void restoredSwapsDispatchTheActionNowAssignedToTheRegisteredKey() {
#ifdef Q_OS_WIN
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QVERIFY(shortcuts.setBinding("togglePlay", ""));
        QVERIFY(shortcuts.setBinding("next", "Ctrl+Alt+Space"));
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+Right"));
        const int spaceId = state->active.key("Ctrl+Alt+Space");
        QVERIFY(shortcuts.resetDefaults());
        QSignalSpy activated(&shortcuts, &GlobalShortcuts::activated);
        MSG message{};
        message.message = WM_HOTKEY;
        message.wParam = WPARAM(spaceId);
        message.lParam = MAKELPARAM(MOD_CONTROL | MOD_ALT, VK_SPACE);
        qintptr result = -1;
        QVERIFY(shortcuts.nativeEventFilter("windows_dispatcher_MSG", &message, &result));
        QCOMPARE(activated.size(), 1);
        QCOMPARE(activated.at(0).at(0).toString(), "togglePlay");
#else
        QSKIP("Windows native message envelope only exists on Windows");
#endif
    }

    void nativeEventsIgnoreUnknownStaleAndForeignMessages() {
#ifdef Q_OS_WIN
        auto state = std::make_shared<RegistryState>();
        GlobalShortcuts shortcuts(registrar(state));
        QSignalSpy activated(&shortcuts, &GlobalShortcuts::activated);
        const int id = state->active.key("Ctrl+Alt+Space");
        MSG message{};
        message.message = WM_HOTKEY;
        message.wParam = WPARAM(id);
        message.lParam = MAKELPARAM(MOD_CONTROL | MOD_ALT, VK_SPACE);
        qintptr result = 73;
        QVERIFY(!shortcuts.nativeEventFilter("foreign", &message, &result));
        QCOMPARE(result, qintptr(73));
        message.wParam = WPARAM(id + 10000);
        QVERIFY(!shortcuts.nativeEventFilter("windows_dispatcher_MSG", &message, &result));
        message.wParam = WPARAM(id);
        message.message = WM_KEYDOWN;
        QVERIFY(!shortcuts.nativeEventFilter("windows_generic_MSG", &message, &result));
        message.message = WM_HOTKEY;
        message.hwnd = reinterpret_cast<HWND>(quintptr(1));
        QVERIFY(!shortcuts.nativeEventFilter("windows_generic_MSG", &message, &result));
        message.hwnd = nullptr;
        QVERIFY(!shortcuts.nativeEventFilter("windows_generic_MSG", nullptr, &result));
        message.lParam = MAKELPARAM(MOD_CONTROL | MOD_ALT, VK_RIGHT);
        QVERIFY(!shortcuts.nativeEventFilter("windows_dispatcher_MSG", &message, &result));
        message.lParam = MAKELPARAM(MOD_CONTROL | MOD_ALT, VK_SPACE);
        QVERIFY(activated.isEmpty());
        QVERIFY(shortcuts.nativeEventFilter("windows_dispatcher_MSG", &message, &result));
        QCOMPARE(result, qintptr(0));
        QCOMPARE(activated.takeFirst().at(0).toString(), "togglePlay");
        QVERIFY(shortcuts.setBinding("togglePlay", "Ctrl+Alt+P"));
        QVERIFY(!shortcuts.nativeEventFilter("windows_dispatcher_MSG", &message, &result));
        QVERIFY(activated.isEmpty());
        QVERIFY(shortcuts.setBinding("togglePlay", ""));
        message.wParam = WPARAM(state->nextId);
        QVERIFY(!shortcuts.nativeEventFilter("windows_dispatcher_MSG", &message, &result));
#else
        QSKIP("Windows native message envelope only exists on Windows");
#endif
    }

private:
    QTemporaryDir settingsDirectory;
};

QTEST_GUILESS_MAIN(GlobalShortcutsTests)
#include "globalshortcuts_tests.moc"
