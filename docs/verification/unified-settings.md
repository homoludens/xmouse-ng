# Unified settings verification

## Implementation summary

Settings use a shared native Views/XML host and `PreferenceFragmentCompat` screens for Touchpad, Keyboard, Authentication & SSH, Appearance, and About. `SECTION_ALL` remains an index, so normal Settings navigation does not initialize credential or private-key flows. The legacy `SettingsActivity` route remains an Authentication & SSH alias. The computer editor remains a separate form and retains its existing behavior of clearing an unsaved password when backgrounded.

Preference keys/defaults, input scalar formats, SSH behavior, protected passphrase handling, private-key import/results, routes, and Touchpad spacing are preserved. Preferences do not write defaults merely by opening a screen. Scripts, tabs, and the layout editor are unchanged. Authentication/key tests use synthetic fixtures only. The computer editor toolbar now uses the shared 20sp settings heading; global editor/Scripts heading styles remain unchanged.

## Runtime environment

Runtime checks used only the owned API 35 AVD `emulator-5598`, with Android SDK platform-tools ADB v41 pinned on isolated port `5049`. The host `/home/homoludens/bin/adb` v36 was not used to connect to the emulator; port `5037` was left untouched. No physical device, real key, or real host was used.

The emulator was launched with `-adb-path /home/homoludens/Android/Sdk/platform-tools/adb`. Connected Gradle commands used this environment and device selection:

```sh
env -u ADB_SERVER_SOCKET \
  ADB_SERVER_PORT=5049 \
  ANDROID_HOME=/home/homoludens/Android/Sdk \
  ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ANDROID_USER_HOME=/tmp/opencode/xmouse-unified-settings-runtime/user-home \
  ANDROID_AVD_HOME=/tmp/opencode/xmouse-unified-settings-runtime/avd \
  ANDROID_ADB_SERVER_PORT=5049 ANDROID_SERIAL=emulator-5598 \
  JAVA_TOOL_OPTIONS=-DANDROID_ADB_SERVER_PORT=5049 \
  PATH=/home/homoludens/Android/Sdk/platform-tools:$PATH \
  ./gradlew --no-daemon -DANDROID_ADB_SERVER_PORT=5049 \
  :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5598 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.stripe1.xmouse.UnifiedSettingsAndroidTest
```

## Focused runtime results

On the final review tree and API 35 emulator:

| Run | Result | Notes |
| --- | ---: | --- |
| `UnifiedSettingsAndroidTest`, no capture argument | **5 passed, 1 skipped**, 0 test failures | The capture test assumes `captureUnifiedSettings=true` before creating files or launching screens. The explicit assumption is reported as a skip, not a failure. |
| `capturesRepresentativeSettingsScreensForRuntimeEvidence`, with `captureUnifiedSettings=true` | **1/1 passed** | Opt-in capture exercised seven normal scenes plus the Appearance theme-choice dialog and verified that opening/dismissing the dialog does not write the appearance preference. Run on API 35. |
| `CredentialRepositoryAndroidTest#nativeComputerEditorUsesPersistentLabelsBlankPasswordAdvancedValidationRecreationAndConfirmedDelete` | **1/1 passed** | Includes an assertion on the rendered computer-form toolbar title at 20sp, alongside its existing credential/editor checks. |

The final review package did not rerun the full credential or private-key suites. Their immediately preceding full runs passed **29/29** and **19/19**, respectively; this package changes no credential/key production behavior. Earlier exploratory full-suite results are recorded below and are not counted as final passes.

Foreground run logs are under `/tmp/opencode/xmouse-verification-20261027/`: `unified-settings-normal-optout-recovered.log`, `unified-settings-capture-optin.log`, `computer-form-heading-focused-retry.log`, and `final-gradle-gate-review.log`. The prior complete credential and key-import run logs are `credentials-final.log` and `private-key-import-final.log` in the same directory.

### Capture command

Run the opt-in method alone with the same owned-device environment; the capture flag is a separate runner argument:

```sh
env -u ADB_SERVER_SOCKET \
  ADB_SERVER_PORT=5049 \
  ANDROID_HOME=/home/homoludens/Android/Sdk \
  ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ANDROID_USER_HOME=/tmp/opencode/xmouse-unified-settings-runtime/user-home \
  ANDROID_AVD_HOME=/tmp/opencode/xmouse-unified-settings-runtime/avd \
  ANDROID_ADB_SERVER_PORT=5049 ANDROID_SERIAL=emulator-5598 \
  JAVA_TOOL_OPTIONS=-DANDROID_ADB_SERVER_PORT=5049 \
  PATH=/home/homoludens/Android/Sdk/platform-tools:$PATH \
  ./gradlew --no-daemon -DANDROID_ADB_SERVER_PORT=5049 \
  :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5598 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.stripe1.xmouse.UnifiedSettingsAndroidTest#capturesRepresentativeSettingsScreensForRuntimeEvidence \
  -Pandroid.testInstrumentationRunnerArguments.captureUnifiedSettings=true
```

The `Build.VERSION.SDK_INT >= 29` assumption also runs before file or UI work, since the capture exports to MediaStore. Capture was exercised on API 35; **API 26 was not runtime-tested**.

### Screenshot evidence

The original five PNGs were preserved. Three additional real-UI captures were exported from the owned emulator, copied into the same evidence directory, and visually inspected:

- [Settings index, light](evidence/unified-settings-runtime-20261027/unified-settings-index-light.png)
- [Touchpad, light](evidence/unified-settings-runtime-20261027/unified-touchpad-light.png)
- [Keyboard, light](evidence/unified-settings-runtime-20261027/unified-keyboard-light.png)
- [Authentication & SSH, light](evidence/unified-settings-runtime-20261027/unified-authentication-light.png)
- [Authentication & SSH, dark](evidence/unified-settings-runtime-20261027/unified-authentication-dark.png)
- [Appearance, light](evidence/unified-settings-runtime-20261027/unified-appearance-light.png)
- [Appearance theme chooser](evidence/unified-settings-runtime-20261027/unified-appearance-theme-dialog-light.png)
- [About, light](evidence/unified-settings-runtime-20261027/unified-about-light.png)

### Superseded runtime attempts

- One initial connected run could not start instrumentation because ADB clients v36 and v41 contended for the device. The emulator was relaunched with its SDK ADB v41 pinned and both `ADB_SERVER_PORT` and the Android/Gradle port set to `5049`; the final connected runs completed. The Gradle emulator-console warning (`Failed to start Emulator console for 5598`) remained non-fatal.
- The first UI run after the cold boot had three interaction failures while an Android screenshot confirmed a **System UI isn't responding** modal over the launcher. The modal was dismissed with **Wait**, no test assertion was changed, and the ordinary UI class rerun passed 5 tests with the capture test skipped.
- The first focused computer-form attempt looked up a nonexistent AppCompat title view ID. The assertion now finds the visible Toolbar title by its rendered text and verifies its actual `textSize`; the focused test then passed.
- Previous credential explorations exposed an unsaved-password expectation inconsistent with the existing editor behavior, discovery-dismissal timing, a notification-permission revocation that force-stopped instrumentation, and a Scripts test assumption that a discovery view exists. These were corrected without changing credential business logic; the later full credential run passed 29/29.

## Final Gradle gate

After the review changes, ran:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- **Build successful**; all four requested tasks completed.
- JVM unit tests: **516 passed**, 0 failures, 0 errors, 0 skipped.
- Lint: **0 errors, 282 warnings** across the app. Remaining warnings include unused legacy settings strings and existing SharedPreferences/test warnings; no findings remain in the shared settings host/fragment or settings layout.
- Debug app and instrumentation APKs assembled successfully.
- `git diff --check`: passed.
- Current debug app APK: **20,764,541 bytes**, SHA-256 `499637df739d7716946b6c915357a7ca8b34b942ca838f09fe2c052cd446c5ea`.
- Current debug Android-test APK: **1,751,687 bytes**, SHA-256 `055005cb241be2712a6a46fc98fe3b4c8545980049890e08efe49fbd773a9194`.

## Remaining limitations

The final review package ran the full unified-settings class, the opt-in capture method, the focused computer-editor test, and the four-task build gate; it did not repeat the full credential/key suites. No API 26 runtime, TalkBack session, live SSH connection, production key, or release build was exercised. Lint still reports the 282 project warnings noted above. The owned emulator and isolated ADB server are stopped after verification.
