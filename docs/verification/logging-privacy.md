# Authentication and typed-content logging (task 3.7)

Verified **2026-10-20**. This records task 3.7 only; no other OpenSpec task was advanced.

## Logging policy and implementation

- Authentication diagnostics do not include usernames, hostnames, ports, passwords, passphrases,
  private-key paths/bytes, or exception contents. Connection, command-send, SSH/SCP failure, and
  remote SCP error events use fixed messages.
- Typed keyboard input, custom command text, layout contents, and exception details are not written
  to logs. Layout/settings failures use fixed diagnostics and generic user-facing messages instead
  of exception text or input contents.
- Command dispatch and intentional on-screen command/output behavior are retained. The JVM
  regression verifies a synthetic typed custom command is dispatched unchanged to a recording
  transport while the Android logger receives no interactions.

## Verification

All Android build commands used both SDK variables:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- **BUILD SUCCESSFUL**. Unit tests: **190 passed, zero failures**.
- Lint: **0 errors, 143 warnings**.
- Debug and instrumentation APKs assembled successfully.
- `git diff --check` passed.

The API 35 instrumentation suite ran on the approved disposable AVD (`emulator-5580`, `xmouse_task31`):

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument --user 0 -w \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (80 tests)**, including `LoggingPrivacyAndroidTest.actualKeyboardInvalidCommandAndLayoutFailureDoNotLogRawValues`.
That test clears logcat, enters a unique synthetic typed-text sentinel through the real keyboard,
invokes the invalid-command path, and parses a malformed synthetic layout. It captures logcat for
the app PID, asserts neither sentinel appears, and asserts the fixed invalid-command and layout
failure events do appear. It also verifies there is no SSH session before or after the actions and
restores the test preferences/layout. No remote command is executed. The two opt-in local
backup-transport methods are outside the default suite and were not run for this task.

The JVM regression
`CustomKeyboardButtonAdapterDelegationTest.typedCustomCommandIsDispatchedWithoutLoggingItsContent`
uses a synthetic sentinel and a recording transport; it verifies no Android `Log` calls occur and
the original command is dispatched. The invalid-command regression also verifies each malformed
input emits only the fixed `invalid_custom_command` event.

### Source audit

The source audit covered app-owned Java/Kotlin production sources under `app/src/main/java`,
including the changed `MainActivity`, `MyConnectionHandler`, custom-button, file-picker, host-list,
mouse/JavaScript-view, and settings paths. The complete logging/output inventory was collected with:

```sh
rg -n --glob '*.{java,kt}' '(Log\.|Timber\.|Logger|System\.(out|err)|printStackTrace|System\.console)' app/src/main/java
```

Every active app logging call in that inventory was reviewed. The potentially sensitive log/stack
trace search below returned no matches:

```sh
rg -n -i --glob '*.{java,kt}' \
  'Log\.(v|d|i|w|e)\s*\([^;\n]*(password|passphrase|private.?key|credential|typed|exception|throwable)|printStackTrace\s*\(|System\.(out|err)\.(print|println)\s*\(' \
  app/src/main/java/com/stripe1/xmouse
```

The inventory includes fixed diagnostic events and commented-out historical log statements; manual
review confirmed active messages do not interpolate credentials, private-key data, typed/custom
command content, layout data, remote error text, or exception details. This audit covers this
application's source only; it does **not** audit logging performed internally by third-party
libraries or Android itself.

No staging or commit was performed.
