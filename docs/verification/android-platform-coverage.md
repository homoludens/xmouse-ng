# Android platform coverage (task 11.5)

**Current integrated-code device matrix: NOT RUN.** No physical device or AVD is currently available. Historical
emulator results below concern earlier task-specific APKs and do not establish runtime coverage of the latest
integrated layout-editor, insets, Back, and gyro changes.

API 26 is the approved minimum; API 24 is unsupported. The API 24 ECDSA failure and approval of the API 26 floor
are recorded in [`ssh-crypto.md`](ssh-crypto.md) and the change proposal. The latest `:app:testDebugUnitTest`,
`:app:lintDebug`, and `:app:assembleDebug` passed as recorded under task 11.1 in
[`tasks.md`](../../openspec/changes/modernize-xmouse/tasks.md). Those JVM/build checks are not Android runtime
coverage. The API 36+ Back instrumentation regression compiled, but was not run (task 10.2).

| API | Evidence recorded in the repository | Current integrated-code device status |
| ---: | --- | --- |
| **26** | Historical API 26 emulator runs are recorded for SSH crypto and session/input checks ([`ssh-crypto.md`](ssh-crypto.md), [`input-lifecycle.md`](input-lifecycle.md), [`session-notification.md`](session-notification.md)). These predate the latest integration. | **NOT RUN.** Current floor behavior is unverified; especially the API 26 no-gyroscope install and touchpad/keyboard/profile/script use in task 10.3. **Release blocker.** |
| **31** | No API 31 device/AVD run is recorded; task 5.5's evidence record explicitly says API 31 was not tested ([`session-command-routing.md`](session-command-routing.md)). | **NOT RUN. Release blocker.** |
| **33** | No API 33 device/AVD run is recorded; task 5.5 explicitly says API 33/34 were not tested ([`session-command-routing.md`](session-command-routing.md)). The API 33+ notification-permission behavior is implemented, but recorded system permission-dialog runs were on API 35, not API 33 ([`session-notification.md`](session-notification.md)). | **NOT RUN. Release blocker.** |
| **34** | Task 7.1 records fake-test coverage for the newer NSD service-info adapter; this is not an API 34 platform run. Task 5.5 explicitly says API 33/34 were not tested ([`session-command-routing.md`](session-command-routing.md)). | **NOT RUN. Release blocker.** |
| **35** | Historical API 35 emulator runs, including notification-permission and session/input checks, are recorded in [`session-notification.md`](session-notification.md), [`input-lifecycle.md`](input-lifecycle.md), and [`ssh-crypto.md`](ssh-crypto.md). They predate the latest integration. | **NOT RUN** on the current integrated code. Current insets and Back behavior remain unverified. **Release blocker.** |
| **36** | Task 5.5 records no API 36 test; task 10.2's API 36+ Back instrumentation regression compiled into the Android-test APK but was not device-run. | **NOT RUN.** Current insets and Back behavior remain unverified. **Release blocker.** |
| **37** | Task 5.5 records no API 37 test. Task 6.2 records JVM permission-policy/session-revocation tests, while API 37 grant/deny/revoke UI verification is explicitly not run. | **NOT RUN.** Current insets, Back, and LAN-permission UI remain unverified. **Release blocker.** |

## Release gaps

- Run the current integrated APK on API 26, 31, 33, 34, 35, 36, and 37; do not count historical APK runs, compilation, or JVM fakes as runtime passes.
- Verify API 26 without a gyroscope, API 35–37 insets/keyboard/navigation and Back behavior, and API 37 LAN-permission grant/deny/revoke UI.
- Complete live LAN and real session-continuity checks (including app switching); these remain unverified, not inferred from JVM or build results.

No device, AVD, test, or ADB run was available/performed for this matrix update. See the task-specific evidence in
[`tasks.md`](../../openspec/changes/modernize-xmouse/tasks.md), especially 6.2, 7.1, 10.1–10.3, and 11.1–11.3.
