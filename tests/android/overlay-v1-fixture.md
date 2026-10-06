# Android 1.0 fixture

`run-overlay-v1-contract-test.ps1` compiles the production Android Java, the new instrumentation fixture and the JVM contracts against local API 36 / Java 8 / Qt bindings. It runs the three new contract groups and the four existing 0.9 contract groups without any device or network.

`run-overlay-v1-ui-test.ps1 -DeviceSerial <serial> [-Screenshots]` requires the rebuilt 1.0 target APK signed with the local Android debug key and the overlay permission. It installs only the separate test package, checks eight named scenes, records instrumentation results, then uninstalls the test package. The target package is retained.

The eight scenes exercise the actual OverlayWindow with local state fixtures and inspect the bridge events: source selection/cursor preservation; no-source song and playlist IME/submit gating; mixed song identities; quality/settings draft preservation; touch-time refresh deferral; integer result-count command and pending-state acknowledgement; ranking source choices and canonical QQ ranking IDs; Kuwo playlist card/detail source and canonical ID. Published source metadata includes count, creator and update frequency where provided.

Screenshots are written to the target app cache directory `v1-ui-fixture`, one per named scene. Large-list upward/downward system gestures remain covered by the existing `run-scroll-test.ps1` fixture against the rebuilt 1.0 target: local playlist, song search and playlist search. Its playlist search now includes the new shared source controls because it uses the installed production OverlayWindow.

The 0.9 instrumentation scene that expected no source controls and unconditional Net playlist search is intentionally superseded by this fixture. The 0.9 JVM contracts still run unchanged.
