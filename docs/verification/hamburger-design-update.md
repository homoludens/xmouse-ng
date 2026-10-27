# Hamburger redesign — final verification

Date: **2026-10-26**. Scope: final integration verification of the XMouse-NG two-tab shell, hamburger drawer, shared composer, virtual Actions presets, Computers sheet/editor, and native settings/theme behavior. This records local debug-build and synthetic-fixture evidence; it is not release or live-server acceptance.

## Result

**Passed:** the requested JVM/lint/build gates on the owned API 35 emulator. The repaired editor-to-preset-picker integration test passed in the completed 38-case run and again in the exact-final-source 38-case rerun. That latest run had one unrelated drawer outside-tap failure (37/38); its isolated retry passed 1/1, with emulator System UI termination observed in the failed run. The 24 earlier redesign captures remain retained; the IME captures show the real Gboard and app Send control. Two connection-recovery/Retry cases passed in a separately archived pre-IME run. No physical device was used. Tests and screenshots use synthetic data. **No live SSH connection was attempted.**

## Build, unit tests, lint, and artifact size

Ran the requested gate with the Android SDK at `/home/homoludens/Android/Sdk`:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Final clean-tree result: **BUILD SUCCESSFUL**. Fresh XML totals from `app/build/test-results/testDebugUnitTest/TEST-*.xml`: **66 suites, 496 tests, 0 failures, 0 errors, 0 skipped**. `app/build/reports/lint-results-debug.xml`: **231 warnings, 0 errors**. `git diff --check` passed. The final gate log is `/tmp/opencode/xmouse-final-verification/ime-send-review/attempt-19-final-tree-gates/gradle.log`. Earlier gate attempts and their outcomes are listed in [IME/connected-test run log](ime-send-final-runs.md).

Debug APK comparison (same local SDK/toolchain; baseline built from a detached worktree at the pre-change `HEAD`, not a release build):

| Artifact | Size |
| --- | ---: |
| Baseline `app-debug.apk` | 20,468,695 bytes |
| Final `app-debug.apk` | **22,185,385 bytes** |
| Change | **+1,716,690 bytes (+8.39%)** |
| Final `app-debug-androidTest.apk` | 1,715,387 bytes |

The baseline APK is `/tmp/opencode/xmouse-redesign-baseline/app/build/outputs/apk/debug/app-debug.apk`; baseline and final artifacts are also retained under `/tmp/opencode/xmouse-final-verification/ime-send-review/final-artifacts/`. No dependency or build-configuration delta: `app/build.gradle`, root `build.gradle`, `settings.gradle`, and `gradle.properties` have no working-tree changes. This is a declaration/configuration comparison, not a separately measured resolved dependency-tree comparison. APK sizes are debug-only measurements.

## Emulator and instrumentation

- Targeted only the owned **`xmouse_final_api35_20261026`** AVD / **`emulator-5598`**, Android **15 / API 35**. Its files are retained under `/tmp/opencode/xmouse-final-verification/avds` with isolated user home under `/tmp/opencode/xmouse-final-verification/user-home`. The emulator reported a 960×2160 display override; landscape captures are 2160×960. Instrumentation commands set `ANDROID_SERIAL=emulator-5598`; ADB device operations used `adb -s emulator-5598`. No physical device was contacted.
- Initial attempts had isolated-ADB discovery/install failures, a transient mismatch between host ADB 1.0.36 and SDK ADB 1.0.41, and a System UI not-responding dialog. The emulator was restarted with SDK ADB pinned. Diagnosis showed that production's single-choice picker had six visible/clickable rows; the test's `waitForText` class allowlist omitted their `CheckedTextView` class. The test now finds and clicks “Built-in · Common” through a normal UiAutomator text selector. Its target integration case passed in both the earlier completed 38-case run and the latest exact-source rerun. The latter ran **38 tests, 37 passed, 1 failed** on the unrelated outside-tap drawer assertion; System UI PID 819 was killed during that run. The drawer test passed in an isolated retry (1/1), so the suite failure appears emulator-flaky; no product fix was made. The post-idle picker screenshot/hierarchy are in `/tmp/opencode/xmouse-final-verification/ime-send-review/attempt-14-picker-screenshot-after-idle/`; exact logs, testcase lists, and reports are in the [IME/connected-test run log](ime-send-final-runs.md) and attempt-16, attempt-21, and attempt-22 archives.
- Two earlier additional tests passed in a separate pinned pre-IME run (**2/2, 0 failures/skips**): `resolutionFailureGuidesManualEntryAndRetryRetainsHostWithoutPasswordMislabel` and `activityRecreationAndReturnWithAutoConnectEnabledReattachToTheExistingSession`. They exercise synthetic failure/recovery state and Retry labeling; their report is under `/tmp/opencode/xmouse-final-verification/ime-send-review/pre-ime-send-change-20261026/app/build/outputs/androidTest-results/connected/debug/`.
- The opt-in capture used the debug-only fixture with **320dp width and 1.5× font scale**, both light and dark themes, portrait and landscape, the real software IME, drawer, Computers sheet, and scroll positions of the computer/action forms. The production composer requests `IME_ACTION_SEND` while retaining `TYPE_TEXT_FLAG_MULTI_LINE` and clearing `IME_FLAG_NO_ENTER_ACTION` for that action. The current light/dark Gboard captures (960×2160) are `/tmp/opencode/xmouse-final-verification/captures/final-real-ime-20261026/touchpad-composer-ime-send-light.png` and `.../touchpad-composer-ime-send-dark.png`; Gboard shows its Send arrow, and the app's **Send** button is visible beside the composer.
- **24 PNGs** (about 2.85 MB) were pulled from the owned emulator and reviewed. They are kept outside source control at `/tmp/opencode/xmouse-final-verification/captures/`; landscape frames are 2160×960 and portrait frames 960×2160. Names include `touchpad-{light,dark}`, `touchpad-landscape-{light,dark}`, `touchpad-composer-ime-*`, `navigation-drawer-*`, `computers-sheet-*`, `actions-*`, and top/bottom/IME computer- and action-editor frames.

The visual test uses production XML/renderers with synthetic fixture data. Computer-editor screenshots deliberately do not launch the credential-bearing Activity (which is `FLAG_SECURE`); the form is shown in the isolated fixture instead. These captures are not a pixel-golden comparison or a claim of a live connected session. Exact test selectors, report paths, and failed-attempt/rerun history are in [IME/connected-test run log](ime-send-final-runs.md).

## Production wiring reviewed

- The shell exposes Touchpad and Actions bottom destinations. The drawer keeps Computers/session controls, pointer/keyboard settings, layouts/actions, scripts, legacy layout tools, clipboard send, authentication, appearance, About, and exit routes. Drawer Back/outside dismissal, selection into the Computers sheet, and focus behavior were exercised.
- Connection presentation maps preparing, resolving, connecting, host approval, authenticating, connected, disconnecting, failed, and disconnected states to explicit labels/actions. A failed session presents Retry; a ready session presents Disconnect. The Computers sheet reuses existing management and session routes.
- `ShellNavigation` retires input capabilities on destination/overlay changes; overlays suspend command input. The composer draft is shared in memory across the two shell tabs, drawer/Computers interactions, and reconnect flows, and blank/disconnected sends are gated. Draft text is not serialized: external screens, ordinary backgrounding, and navigation restoration are privacy boundaries.
- Built-in Common/Navigate/Media/F1–F12 presets render read-only from the virtual catalog. Their stable choice identity is distinct from saved profile IDs, so an ID collision cannot select or overwrite a saved layout. Presets do not seed saved profiles. Ten-column tile placement is retained.
- The native computer editor retains persistent labels, validation, blank-password preservation, advanced port fields, and confirmed deletion. Settings bind to persisted input preferences; System/Light/Dark appearance is persisted and applied through AppCompat.

## Synthetic boundary and limitations

The Android session tests use recording/fake owners and transports; repository/editor tests use isolated synthetic databases and credentials. The UI capture fixture uses synthetic names/commands. Invalid editor input is checked to stop the Test action locally. These tests establish UI wiring, state transitions, gating, and non-mutation behavior only. **They do not establish DNS/LAN reachability, host-key trust against a real host, authentication, SSH channel readiness, remote command execution, clipboard delivery, or actual Linux input effects. No real SSH endpoint or user credential was used.**

No physical-device run, API 26 run, manual TalkBack traversal, live discovery, or signed-release/in-place-upgrade check is claimed. Lint warnings remain as reported above.

## Intentional design deviations

- Keep the **ten-column** Actions grid; do not switch the rendering model to the reference's smaller grid.
- Do not add a separate **Keys** destination; virtual key presets remain under Actions.
- Omit Touchpad **Super** and **Alt+Tab** quick keys. This is deliberate scope, not a missing verification item.
