# Session command routing, ordering, and loss (task 5.5)

Verified **2026-10-22**. This record covers **task 5.5 [low]** only. The service-owned command path is used by
the existing controls, custom buttons, and saved scripts. Controller and transport generations discard work
that has not reached the provider when a session is invalidated. Recent-command feedback means the current
attempt/transport accepted the command into its local queue; it is not a provider-write or remote-execution
acknowledgment, and accepted queued work can still be discarded after a subsequent loss. Script results are
scoped to both the initiating session attempt and the opaque screen-presentation token.

The baseline is commit `9a9d6e4e2200f27ab882273723d8452d6be0fe69`. After this task, OpenSpec progress is
**30/67**; the complexity allocation remains **14 [high] / 53 [low]**. Tasks 5.6 onward remain unchecked and
are not implemented or claimed here.

## Routing and ordering review

- `MainActivity.onCreate` creates an Activity-scoped `SessionCommandTransport` from the application context.
  `initDoTool` passes that screen's proxy into the selected `XDoTool` or `YDoTool`. `MyMouseView` touchpad
  gestures, `MyJSView` joystick movement, MainActivity mouse buttons, volume shortcuts and batch typing use
  their creating Activity's adapter; the custom-button adapter likewise dispatches through its owning Activity.
  These existing UI routes no longer read a process-wide static adapter/proxy, so detached old-screen input
  cannot borrow a reattached screen's presentation token.
- `CustomKeyboardButtonAdapter` retains the existing `CustomCommandDispatcher` translations. Recognized button
  commands still use the selected xdotool/ydotool adapter; unknown shell commands are passed to the transport
  unchanged. The integration test checks the default `Minimize` button's raw
  `xdotool windowminimize $(xdotool getactivewindow)` command at the recording provider boundary.
- Saved-script selection remains the actual `NavigationView` menu action in `MainActivity.onNavigationItemSelected`.
  It sends the original script text through `SessionCommandTransport.executeExecCommand` with the presenter's
  opaque `PresentationToken`. The API 26 test invokes the menu item action directly because drawer script labels
  are not exposed as clickable text there.
- Each Activity has its own `SessionCommandTransport` proxy, retaining only the application context and an
  atomic opaque `PresentationToken` (never an Activity, presenter, listener, or View). Binding publishes the
  presenter's newly attached token; unbinding clears only that token. Every shell submission snapshots its
  token before entering the controller queue, so a queued command cannot acquire a reattached screen's token.
- The controller serializes mixed shell/exec admission, caps commands awaiting owner-queue dispatch at 128, and
  checks the admitted attempt/host/disconnect generation again when dispatch runs. `SshTransport` has its own
  bounded queue and transport-generation checks. Neither layer transfers a command to a replacement attempt.
  The proxy's `true` return means controller-event-queue admission only. Recent-command feedback is emitted
  later, only when the live attempt's `SshTransport` accepts the shell write into its local queue; transport
  rejection produces no feedback. It is explicitly not evidence that the provider wrote the command or the
  remote host executed it. Work accepted into that local queue can still be dropped before provider write if
  transport loss follows.
- Startup-command recent text remains scoped to the screen present when that attempt began and is posted once
  after connection succeeds; binding a later Activity does not replay it. Ordinary shell/custom input instead
  captures the current screen token per submission. Attempt invalidation and a second token/generation check at
  UI dispatch prevent stale-attempt, queued-old-screen, disconnect, and host-replacement callbacks from
  updating a new screen.
- Exec callbacks are attempt-scoped and screen-scoped. Loss, cancellation, host replacement, or a newer attempt
  invalidates the old result scope; Activity detach/recreation replaces the screen token. Old results cannot
  create a dialog on a new Activity or for a new host. Output dialogs are dismissed on stop/destroy.
- The complete production command-callsite search found the MainActivity/control/custom-button/script paths
  described above and the `SshSessionAttemptFactory` handoff to the service-owned `SshTransport`. The legacy
  `MyConnectionHandler` compatibility class still contains direct transport methods, but there is no
  `MyConnectionHandler` construction callsite in `app/src/main`; MainActivity does not instantiate or use it.

No xdotool/ydotool command adapter, command translation, raw custom-command text, gesture model, or action model
was rewritten. `CustomKeyboardButtonAdapter` now obtains the existing adapter/proxy from its owning Activity;
its command translation remains unchanged. JVM `DoToolTransportTest`/`YdoToolTest` regressions cover both
backend translations, including Unicode, quotes, commas, spaces, and line breaks.

## Build, lint, and APKs

Both required SDK variables were set for Android tooling:
`ANDROID_HOME=ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk`.

Command:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew --no-daemon --console=plain --no-build-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Result: **BUILD SUCCESSFUL**, `/tmp/opencode/xmouse-task55-recent-feedback-final-gradle.log`.

- JVM: **319 tests, 0 failures, 0 errors, 0 skipped** (33 XML test reports).
- Lint: **137 warnings, 0 errors**; `lintDebug` succeeded.
- Debug, unsigned release, and Android-test APKs assembled successfully.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 19,610,600 | `13a64f92029f8398cf5768446074fbb052d69f595bcf06afe0c1442b6e4c96fa` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 14,408,433 | `f07f83d862c7409e46efa6a59c832941db1d27631294eeaaa6d48e61c2a63d25` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 916,361 | `84555e2ea10bd6b4a09eb4a6274f292c556fdb254ea2fc20a560fac502169baf` |

The debug APK reports package `com.stripe1.xmouse`, version `25` / `2.0.27`, min SDK **26**, target SDK
**37**, and compile SDK **37**. The merged debug manifest is 5,299 bytes with SHA-256
`8b9473b28934f47f82218099116df5720343a84c2d03937ea2f01df05c27cd33`. Its declared permissions include
`INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `HIGH_SAMPLING_RATE_SENSORS`, and the app's
dynamic-receiver permission.

## Clean instrumentation

The approved disposable AVDs were run sequentially using ADB server port **5038**. Both app and test package
data were cleared before each full-suite run. The final command used both SDK variables and the dedicated ADB
port:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
ANDROID_AVD_HOME=/tmp/opencode/xmouse-task52-avd \
ANDROID_ADB_SERVER_PORT=5038 \
python3 /tmp/opencode/xmouse-task55-feedback-instrumentation.py
```

Before every APK installation, the approved helper verified the AVD name, API, boot state, SDK environment,
and exact build fingerprint. Install-attempt records and identity files are under
`/tmp/opencode/xmouse-task55-feedback-final2-api26-*` and
`/tmp/opencode/xmouse-task55-feedback-final2-api35-*`. The complete machine-readable record is
`/tmp/opencode/xmouse-task55-feedback-final2-instrumentation-summary.json`; all four final APK installs
succeeded on attempt 1.

| API | AVD / serial / fingerprint | Result | Instrumentation log |
| --- | --- | --- | --- |
| 26 | `xmouse_task41_api26` / `emulator-5584` / `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` | **133 discovered: 131 passed, 2 assumption-skipped, 0 failed**; 403.351 s | `/tmp/opencode/xmouse-task55-feedback-final2-api26-full-instrumentation.log` |
| 35 | `xmouse_task31` / `emulator-5580` / `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` (host-GPU) | **137 discovered: 130 passed, 7 assumption-skipped, 0 failed**; 421.395 s | `/tmp/opencode/xmouse-task55-feedback-final2-api35-full-instrumentation.log` |

Both full runs skipped the two opt-in backup-fixture tests. The API 35 full run also skipped the five
permission-state tests below; each was run in a separate instrumentation invocation against the same final APK
pair and passed. Before each isolated run, app/test data was cleared and host commands revoked
`POST_NOTIFICATIONS` and cleared `user-set`/`user-fixed`. The verified precondition each time was
`granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]`, with no `USER_SET` or
`USER_FIXED` flag.

| Isolated API 35 case | Result | Log |
| --- | --- | --- |
| `notificationPermissionGrantedAtPointOfUsePublishesActionsAfterSystemConsent` | **1 passed, 0 failed**; 9.907 s | `/tmp/opencode/xmouse-task55-feedback-final2-api35-permission-1-instrumentation.log` |
| `notificationPermissionDenialExplainsTaskManagerAndPreservesInAppDisconnect` | **1 passed, 0 failed**; 13.910 s | `/tmp/opencode/xmouse-task55-feedback-final2-api35-permission-2-instrumentation.log` |
| `revokedNotificationPermissionAllowsSessionAndInAppDisconnectWithoutPromptingOnResume` | **1 passed, 0 failed**; 10.545 s | `/tmp/opencode/xmouse-task55-feedback-final2-api35-permission-3-instrumentation.log` |
| `permanentNotificationDenialOffersSettingsInsteadOfAnotherRuntimePrompt` | **1 passed, 0 failed**; 13.353 s | `/tmp/opencode/xmouse-task55-feedback-final2-api35-permission-4-instrumentation.log` |
| `stalePermissionResultsAfterRecreationAndDisconnectCannotStartOrClearNewerRequest` | **1 passed, 0 failed**; 12.303 s | `/tmp/opencode/xmouse-task55-feedback-final2-api35-permission-5-instrumentation.log` |

After all isolated cases, the script restored and verified `POST_NOTIFICATIONS` as granted, with
`user-set`/`user-fixed` clear; the resulting state was
`granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]`. After both AVDs stopped, ADB
server port 5038 listed no attached devices and no emulator process remained. Stop evidence is in
`/tmp/opencode/xmouse-task55-feedback-final2-api26-stop.log` and
`/tmp/opencode/xmouse-task55-feedback-final2-api35-stop.log`.

An earlier API 26 full run used the previous test APK hash
`7e746eadce91fdea206ed6d382e88c61cb6c6fdbb5c10e5e79292d85c86bebfe` and exposed a test-fixture timing issue:
the deliberately blocked exec exceeded the fixture's 250 ms channel / 450 ms exec budget before the test could
recreate the Activity. The fixture now uses a 10 s channel/exec gate budget and bounded cleanup for this
deterministic hold; production timeouts were not changed.

A separate preliminary API 26 install attempt against the final APK hashes lost its ADB connection during the
install helper's `ro.build.fingerprint` query (`/tmp/opencode/xmouse-task55-feedback-api26-app-install.host.log`);
it failed before either APK was installed or any test ran. The subsequent final2 run above started clean,
verified both AVD identities before each install, installed all four APKs on their first attempt, and passed
the full API 26/API 35 and isolated permission runs.

## Task-specific evidence and source audits

- `SessionControllerTest.mixedCommandsKeepAdmissionOrderAndLossDropsQueuedWorkBeforeFreshGeneration` checks
  mixed shell/exec FIFO admission, controller-generation invalidation, and no replay on a fresh generation.
- `SessionPipelineIntegrationTest.productionControllerJschAndMinaPreserveMixedCommandOrderAndNeverReplayAfterLoss`
  uses the production controller, attempt factory, JSch transport, and an in-process Apache MINA SSH server.
  It observes `shell:first` → `exec:middle` → `shell:last`, disconnects the real loopback session, queues shell
  and exec input behind the pending loss event, reconnects on the same port/key, and verifies neither stale
  command ran on the new session. `realTransportDropsCommandsBehindAProviderCallThatFailsAndAReplacementAttemptDoesNotReplayThem`
  additionally gates a real `SshTransport` provider operation and verifies input queued behind it is dropped at
  the transport-generation boundary. Existing `SshDeadlineTest.blockedExecWriteFlushAndScpHaveFiniteTypedOutcomesAndDropUnsentInput`
  covers the direct transport boundary as well.
- Real Activity/service instrumentation test
  `legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink` exercises touchpad, joystick, mouse
  button, volume key, batch typing, Backspace and volume custom buttons, raw shell fallback, saved-script menu
  execution, output copy, and Activity recreation against the service owner's recording backend. It verifies
  initial startup command runs once and shell inputs precede the saved exec in provider-observed order. The raw
  custom command appears verbatim in the recent-command widget; after recreation a newly accepted shell command
  appears verbatim on the new screen, while a command rejected after Disconnect leaves that text unchanged.
  A shell submission made through the detached Activity's retained proxy can still be admitted by the live
  session, but has no screen token and cannot refresh the new Activity's widget.
- `SessionControllerTest.acceptedShellFeedbackUsesItsAdmissionScreenAndNeverRefreshesAfterStaleOrRejectedInput`
  dispatches a same-attempt old-screen command after reattachment (accepted by the fake transport but not shown
  on the new screen), rejects a queued old-host command before it reaches the retiring attempt, reports the
  exact new-screen command only after acceptance, and leaves the recent text unchanged for transport rejection,
  queued Disconnect, and post-disconnect rejection. Existing queue-cap and no-replay regressions remain enabled.
- `lateExecOutputFromTheOldPresentationIsNotShownAfterActivityRecreation` and
  `lateExecOutputFromAReplacedAttemptIsNotShownOnTheNewHost` use recording-backend gates to complete old script
  operations after screen recreation or host replacement; neither displays old output on the new screen/host.
  `SessionInteractionsTest.anOldScreenCannotReceiveExecOutputAfterRecreationOrReattachment` covers the queued
  presentation race at the JVM boundary.
- `python3 docs/verification/verify-v2-storage-fixtures.py`: **4/4 fixtures passed**.
- `python3 docs/verification/audit-ssh-transport.py`: source boundary/privacy audit passed; no unverified-host
  fallback.
- `openspec validate modernize-xmouse --strict`: valid before and after task bookkeeping.
- `git diff --check`: clean before and after the final verification record.

## Exact task 5.5 changed-path manifest

- `app/src/androidTest/java/com/stripe1/xmouse/session/SessionForegroundServiceAndroidTest.kt`
- `app/src/androidTest/java/com/stripe1/xmouse/LoggingPrivacyAndroidTest.kt`
- `app/src/main/java/com/stripe1/xmouse/CustomKeyboardButtonAdapter.java`
- `app/src/main/java/com/stripe1/xmouse/MainActivity.java`
- `app/src/main/java/com/stripe1/xmouse/MyJSView.java`
- `app/src/main/java/com/stripe1/xmouse/MyMouseView.java`
- `app/src/main/java/com/stripe1/xmouse/session/SessionActivityPresenter.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionCommandTransport.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionController.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionInteractions.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SshSessionAttemptFactory.kt`
- `app/src/main/java/com/stripe1/xmouse/transport/SshTransport.java`
- `app/src/test/java/com/stripe1/xmouse/session/SessionControllerTest.kt`
- `app/src/test/java/com/stripe1/xmouse/session/SessionInteractionsTest.kt`
- `app/src/test/java/com/stripe1/xmouse/session/SessionPipelineIntegrationTest.kt`
- `app/src/testFixtures/java/com/stripe1/xmouse/session/SessionFixtures.kt`
- `app/src/testFixtures/java/com/stripe1/xmouse/transport/RecordingSshBackend.kt`
- `openspec/changes/modernize-xmouse/tasks.md`
- `docs/verification/session-command-routing.md`

## Evidence limits

These are API 26/API 35 emulator runs with synthetic credentials and recording/loopback SSH resources. No
physical-device run, live SSH host, LAN-discovery test, or five-minute real-device app-switch acceptance was
performed. API 31, 33/34, 36, and 37 were not tested here. This task does not implement inactive-input/gyro
suspension (5.6), recovery policy (5.7), or Exit/task-dismissal cleanup (5.8). No files were staged and no
commit was created; unrelated pre-existing untracked `.agents/`, `.opencode/`, and OpenSpec placeholder paths
were left untouched.
