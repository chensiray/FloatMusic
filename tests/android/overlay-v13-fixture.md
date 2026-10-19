# Android 1.3 native visual fixture

`OverlayV13VisualTest.java` runs the installed APK's actual `OverlayWindow` on the Android UI thread. It renders twelve scenes: light and dark themes, each showing the compact player, bilingual lyrics, playlist, settings, vertical volume popup, and a 95-song playlist in a short card at the maximum available 130% scale. The same fixture works against 1.2 and 1.3 for a direct baseline comparison. The fixture APK packages no production classes; production Java and the Qt bindings are compile-time dependencies only.

Every scene uses unique preferences and isolated files/cache/no-backup directories. It creates an attached service instance without `onCreate`, marks the service destroyed to prevent publication, supplies fixed in-memory songs and lyric data, and never resolves audio or contacts a music service. Cleanup closes the overlay, cancels callbacks, shuts down its executor, removes the fixture's three named preference files, and deletes only its isolated directory. It restores `PlayerBridge.foreground` and `picking` after each scene, and preserves/restores the pre-run bridge event queue. It does not modify the bridge UI/snapshot, Qt readiness, activity reference or service singleton. The target application's real settings and song library are not read or edited. The runner installs and then uninstalls only `org.floatmusic.v13visualtest`; it does not uninstall or clear the target package.

The fixture checks existing 48dp player/tab touch targets, measured window bounds, three independent quality selectors, bilingual lyric rows, native volume touch endpoints 0/50/100, popup bounds, maximum scale width, short-card scrolling, and second-page access to song 95. Existing 1.2 and 1.1 UI fixtures remain the fuller queue/history/sleep/lyrics/quality regression suites. This fixture adds no requirements for new app behavior.

Each scene writes a JSON view tree with bounds, padding, minimum sizes, layout sizes/margins, visibility, clickability, focusability, enabled state, scroll positions, text size, gravity, maximum lines and font padding. `-Screenshots` additionally saves a PNG drawn by the real Android view after layout; these are native view captures, not browser or generated previews. They omit the surrounding Android screen and compositor shadow. The runner collects them through the target's debug `run-as` boundary. Use PowerShell 7.4 or later to preserve PNG bytes when redirecting native output.

Compile without any device activity:

```powershell
./tests/android/run-overlay-v13-visual-test.ps1 -CompileOnly
```

Build the separate test APK without any device activity:

```powershell
./tests/android/run-overlay-v13-visual-test.ps1 -BuildOnly
```

After the integration owner confirms the selected device and installed target APK, collect a baseline, then repeat with the new target build and a different output directory:

```powershell
./tests/android/run-overlay-v13-visual-test.ps1 -DeviceSerial '<serial>' -AdbPort 5038 -Screenshots -OutputDir ./artifacts/v1.3-android-preview-2026-10-15/baseline-v12
./tests/android/run-overlay-v13-visual-test.ps1 -DeviceSerial '<serial>' -AdbPort 5038 -Screenshots -OutputDir ./artifacts/v1.3-android-preview-2026-10-15/candidate-v13
```

Each run creates a fresh `run-<timestamp>-<guid>/captures` directory. Pass those two capture directories to the comparison:

```powershell
./tests/android/compare-overlay-v13-visual.ps1 -BaselineDir '<baseline captures>' -CandidateDir '<candidate captures>'
```

Comparison requires exactly identical participating geometry and declared touch/layout configuration for all twelve real view trees, including text measurements and scrolling. Android skips layout for `GONE` subtrees, so their stale cached bounds are reported separately when the node or an ancestor is `GONE` in both captures; no other properties or pixel tolerances are exempted. Their layout sizes, margins, padding, visibility, text measurements and touch declarations remain strict. It reports sampled image color differences separately, without asserting implementation-specific palette values. Review the actual PNGs for color, glass shading, contrast, glyphs, clipping and readability. A successful comparison does not prove correctness on every Android version/device, native audio decoding or long-press drag behavior. Device execution requires overlay permission and the matching standard Android debug signature. An active target process is restarted by Android instrumentation.

The unchanged local native Java regressions can be run with:

```powershell
./tests/android/run-overlay-v12-contract-test.ps1 -OutputDir ./artifacts/v1.3-android-preview-2026-10-15/contracts
./tests/android/run-resolver-tests.ps1 -OutputDir ./artifacts/v1.3-android-preview-2026-10-15/resolver
```

The native source glob in the 1.2 contract and UI runners automatically includes newly added Drawable classes. Older compile lists may need the same dependency-only adjustment; business assertions must remain intact.
