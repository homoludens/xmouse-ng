# Control input lifecycle verification

Task **5.6** of `modernize-xmouse` is **complete** after the parent-review transport, scoped-release,
and stale-touch fixes passed the final verification matrix below. Historical failures and earlier
pre-review results are listed separately and are not counted as final passes.

## Input and lifecycle contract

- `SessionController` owns opaque `InputSurfaceToken` and `InputGestureToken` capabilities tied to
  the exact ready attempt, host, generation, presentation, and disconnect epoch. Tokens retain no
  Activity or View. Input from a replaced surface, cancelled gesture, or retired session is rejected.
- `MainActivity` activates its control surface only while resumed, window-focused, outside the
  navigation drawer, attached to a ready session, and presenting the current screen. Pause/focus
  loss, drawer opening, service detachment, and destruction revoke the surface, reset touchpad and
  joystick recognition, and stop gyroscope sampling. Revocation does not disconnect a healthy SSH
  session.
- Input currentness is checked at controller admission, again when the controller's serialized
  event dispatch reaches the owning attempt, in the production SSH coordinator when queued work is
  dequeued, and immediately before the provider `OutputStream.write`. A revoked, not-yet-written
  input event is dropped without declaring transport loss. Bytes already inside the provider write
  cannot be recalled.
- Normal gesture UP stops further admission but preserves commands already admitted to the ordered
  transport queue. Cancellation and surface retirement synchronously revoke transport permission;
  they do not allow an old event to borrow a subsequently resumed surface.
- Button touch sequences capture their gesture token on DOWN. A cancelled sequence's delayed UP or
  click callback cannot fall back to the currently active surface. A fresh DOWN creates a new lease;
  an explicit accessibility click is recognized separately and remains available after a cancelled
  pointer sequence.
- Held mouse buttons are recorded by exact surface token, attempt generation, backend-specific
  button, gesture token, and original attempt. Surface/gesture retirement releases only matching
  holds on that still-usable attempt. Release is conditional on the original DOWN having reached the
  provider-write boundary and not already having a release started; stale cleanup cannot clear or
  release a later gesture's hold.
- The release recognizer intentionally covers only the exact structured mouse-button forms emitted
  by the existing adapters: `xdotool mousedown/up 1..3` and `ydotool click 0x4/0x8` for button
  indices 0..2. Existing `xdotool`/`ydotool` key commands emit a complete key chord (down and up in
  one command), so this lifecycle layer does not track individual keys. Arbitrary raw shell/custom
  commands are not parsed: their remote side effects and partial state cannot be inferred safely.
- A best-effort release is not a promise that a disconnected transport can undo a hold. No claim is
  made that bytes already sent can be recalled or that arbitrary remote shell actions are reversible.

## Review and verification record

- Baseline: `1c097609c7d0ee0d26d099799284129121cae366` (`1c09760`). Current review date: 2026-10-22.
- OpenSpec allocation: 67 tasks total, 14 `[high]` and 53 `[low]`. Task 5.6 is task 30; completion
  advances the change from 29/67 to 30/67. Tasks 5.7 and 5.8 remain untouched and outside this work.
- Earlier pre-review evidence (not evidence for the current implementation): JVM 329 tests / 34
  suites; API 26 instrumentation 136 passed, 2 skipped, 0 failed; API 35 instrumentation 135 passed,
  7 skipped, 0 failed. The parent review then identified transport-queue, scoped-release, and
  stale-touch gaps, so those APK results are historical only.
- A later API 35 review run against an intermediate APK discovered failures in
  `cancelledMouseAndKeyboardButtonTouchUpsCannotBorrowResumedSurfaceButAccessibilityStillWorks`,
  `legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink`, and
  `switchingToSettingsDuringTouchpadDragReleasesOnTheSameSessionAndRejectsStaleInput` (143 tests,
  3 failures). The first also exposed a racy fixture wait for its startup command. The gesture-command
  failures showed normal-UP cleanup invalidating already-admitted commands before provider write.
  The transport/currentness and hold ownership fixes were covered by new controller/transport tests;
  all three focused API 35 regressions subsequently passed.
- The first focused stale-touch rerun found the expected successful-click count was two (fresh
  accessibility activation plus a subsequent fresh touch), not one. The test now verifies both
  successful activations while separately asserting the stale UP/click emits no command; the corrected
  test passed, as did fresh-touch and accessibility coverage.
- Two full API 35 attempts started with notifications denied and failed only
  `actualMainPreferencesAndHostNavigationKeepFallbackIndependentAndPersistSelection`: its fixture
  intentionally starts a host-selection foreground service, which pauses at the consent dialog when
  permission is denied. The focused test passes with permission granted. The clean final full suite
  therefore ran with notification permission granted, while the five permission-specific cases were
  run separately from denied state below; the final full run passed.
- A targeted Gradle invocation was interrupted before completion; the explicit targeted rerun and the
  final full JVM task both completed successfully. No historical APK result is substituted for the
  final artifacts.
- Android commands must set both `ANDROID_HOME` and `ANDROID_SDK_ROOT` to
  `/home/homoludens/Android/Sdk` and use the matching SDK `adb` with server port 5038. Approved
  disposable identities are API 26 `xmouse_task41_api26` (`emulator-5584`,
  `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`) and
  API 35 `xmouse_task31` (`emulator-5580`,
  `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`). Verify the
  identity before every APK install. The observed original API 35 `POST_NOTIFICATIONS` state was
  denied, with flags `[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]`; that exact state
  was restored after the five isolated API 35 cases. Both emulators were stopped and ADB reported no
  connected devices.
- The physical five-minute app-switch test was not run. Emulator/system Settings coverage used a
  recording backend and makes no physical-device, sensor-accuracy, or real-LAN claim.

## Final verification commands and evidence

All final builds ran after the last source/test edit, on 2026-10-22. The final Gradle command used both
SDK variables and completed successfully:

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

The final APK installs were preceded by identity assertions and used these exact commands (API 26
serial `emulator-5584`; API 35 serial `emulator-5580`):

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5584 install -r app/build/outputs/apk/debug/app-debug.apk
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5584 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Before each full run, only the two disposable package data areas were cleared. Each Android command
below was prefixed with both SDK environment variables:

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5584 shell pm clear com.stripe1.xmouse
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5584 shell pm clear com.stripe1.xmouse.test
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 shell pm clear com.stripe1.xmouse
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 shell pm clear com.stripe1.xmouse.test
```

The API 26 full run was:

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5584 shell am instrument -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

For the final API 35 full run, `POST_NOTIFICATIONS` was explicitly granted after the two disposable
package clears, because the existing host-navigation integration fixture starts a foreground
service. After package clear, permission was granted with:

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 shell pm grant \
  com.stripe1.xmouse android.permission.POST_NOTIFICATIONS
```

The instrumentation command was:

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 shell am instrument -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

The five permission-specific API 35 tests each ran in a separate instrumentation process. Before
each, the two disposable package areas were cleared and this denied permission state was established
with both SDK variables set on every Android command:

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 shell pm revoke com.stripe1.xmouse android.permission.POST_NOTIFICATIONS
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 shell pm clear-permission-flags com.stripe1.xmouse android.permission.POST_NOTIFICATIONS user-set
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 shell pm clear-permission-flags com.stripe1.xmouse android.permission.POST_NOTIFICATIONS user-fixed
```

Denied state was asserted. Each invocation used this form, substituting one method name from the
list below:

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 shell am instrument -w -r \
  -e class "com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#${TEST_METHOD}" \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

```text
stalePermissionResultsAfterRecreationAndDisconnectCannotStartOrClearNewerRequest
notificationPermissionGrantedAtPointOfUsePublishesActionsAfterSystemConsent
notificationPermissionDenialExplainsTaskManagerAndPreservesInAppDisconnect
revokedNotificationPermissionAllowsSessionAndInAppDisconnectWithoutPromptingOnResume
permanentNotificationDenialOffersSettingsInsteadOfAnotherRuntimePrompt
```

After the permission matrix, the originally observed denied state was restored by revoking the
permission and clearing both `user-set` and `user-fixed` flags as above. The approved emulators were
stopped with:

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5580 emu kill
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  /home/homoludens/Android/Sdk/platform-tools/adb -P 5038 -s emulator-5584 emu kill
```

Final static checks were run as `python3 docs/verification/audit-ssh-transport.py`,
`python3 docs/verification/verify-v2-storage-fixtures.py`,
`openspec validate modernize-xmouse --strict`, and `git diff --check`. Logging inventory used
`rg -n --glob '*.{java,kt}' '(Log\.|Timber\.|Logger|System\.(out|err)|printStackTrace|System\.console)' app/src/main/java`;
the sensitive-content search used the following case-insensitive scan and returned 0 matches:

```sh
rg -n -i --glob '*.{java,kt}' 'Log\.(v|d|i|w|e)\s*\([^;\n]*(password|passphrase|private.?key|credential|typed|exception|throwable)|printStackTrace\s*\(|System\.(out|err)\.(print|println)\s*\(' app/src/main/java/com/stripe1/xmouse
```

Results (final log directory: `/tmp/opencode/xmouse-task56-final/`):

| Check | Final result | Evidence |
| --- | --- | --- |
| JVM unit tests | 333 passed; 34 suites; 0 failed, 0 errors, 0 skipped | `gradle-final.log`; `app/build/test-results/testDebugUnitTest/` |
| Android lint | 141 warnings, 0 errors | `gradle-final.log`; `app/build/reports/lint-results-debug.xml` |
| Debug, unsigned release, debug Android-test APK builds | all built successfully | `gradle-final.log` |
| API 26 full instrumentation | 137 passed, 2 skipped, 0 failed (139 total) | `api26-full-instrumentation-final.log` |
| API 35 full instrumentation | 136 passed, 7 skipped, 0 failed (143 total), with notification permission granted for service-start fixtures | `api35-full-instrumentation-final-granted.log` |
| Five isolated API 35 permission tests | 5/5 passed, each from denied permission with flags reset before invocation | `api35-isolated-permission-matrix.log` and `api35-permission-{1..5}-*.log` |
| SSH trust/transport source audit | passed; no unverified-host fallback | `audit-ssh-transport-final.log` |
| Version-2 storage fixture audit | 4/4 passed | `storage-fixtures-final.log` |
| Logging inventory and sensitive-content search | inventory: 35 source lines; sensitive logging/console patterns: 0 matches | `logging-inventory-final.txt`; `sensitive-logging-search-final.log`; instrumentation `LoggingPrivacyAndroidTest` |
| OpenSpec strict validation and whitespace | passed after marking only task 5.6 complete | final `openspec validate modernize-xmouse --strict`; `git diff --check` |

Final artifact SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `adaff156e77a02eff3e8b41f920c5a3c67843744e8fef33a4107fa85d653cd47` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `293ada7cdf03dc24a09158415a7a2405c99619c7f9f4e2507ef6ee2e710b342a` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `d01d0986468c41b796829adee4e261a974400a8d3d56e3bf98500b8bfcbb2133` |

Install identities were rechecked before each install. API 26 was AVD `xmouse_task41_api26`, serial
`emulator-5584`, API 26, product/device `sdk_gphone_x86_64` / `generic_x86_64`, fingerprint
`Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`
(`api26-app-install-identity.json`, `api26-test-install-identity.json`). API 35 was AVD
`xmouse_task31`, serial `emulator-5580`, API 35, fingerprint
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`
(`api35-final-app-identity.json`, `api35-final-test-identity.json`). Each disposable app and test
package was cleared before full-suite runs; the permission matrix also cleared them before each
isolated case. Final permission restoration and emulator shutdown are recorded in
`api35-permission-restore-and-stop.log`, `api35-emulator-stop.log`, `api26-emulator-stop.log`, and
the final empty `adb devices -l` output.

Final task-specific changed-path manifest (16 paths; no 5.7/5.8 changes). Pre-existing unrelated
untracked harness/OpenSpec setup paths were left untouched and are excluded:

```text
app/src/androidTest/java/com/stripe1/xmouse/LoggingPrivacyAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/session/SessionForegroundServiceAndroidTest.kt
app/src/main/java/com/stripe1/xmouse/CustomKeyboardButtonAdapter.java
app/src/main/java/com/stripe1/xmouse/MainActivity.java
app/src/main/java/com/stripe1/xmouse/MyJSView.java
app/src/main/java/com/stripe1/xmouse/MyMouseView.java
app/src/main/java/com/stripe1/xmouse/session/ControlInput.kt
app/src/main/java/com/stripe1/xmouse/session/SessionCommandTransport.kt
app/src/main/java/com/stripe1/xmouse/session/SessionController.kt
app/src/main/java/com/stripe1/xmouse/session/SshSessionAttemptFactory.kt
app/src/main/java/com/stripe1/xmouse/transport/SshTransport.java
app/src/test/java/com/stripe1/xmouse/session/ControlInputTest.kt
app/src/test/java/com/stripe1/xmouse/session/SessionControllerTest.kt
app/src/test/java/com/stripe1/xmouse/session/SessionPipelineIntegrationTest.kt
docs/verification/input-lifecycle.md
openspec/changes/modernize-xmouse/tasks.md
```
