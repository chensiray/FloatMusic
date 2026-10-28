package org.qtproject.qt.android.bindings;

/**
 * Test-only boundary: keep the real PlayerActivity and Android lifecycle while
 * excluding Qt's ARM64 native loader from the x86 emulator fixture. Qt/QML and
 * native-process shutdown remain full-APK device acceptance checks.
 */
public class QtActivity extends android.app.Activity { }
