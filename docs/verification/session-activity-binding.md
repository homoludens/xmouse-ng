# MainActivity session binding and recreation (task 5.4)

Verified **2026-10-22**. This record covers **task 5.4 [low]** only. The task is complete: OpenSpec progress is
**28/67**, with the existing allocation unchanged at **14 high / 53 low**. Tasks 5.5–5.8 and all later tasks
remain unchecked and are not claimed here.

The application-owned `SessionController` remains the session owner; `MainActivity` binds, observes and presents
state. An observer-only `BIND_AUTO_CREATE` service lifetime no longer disconnects the owner, suppresses saved
auto-connect or invalidates a permission/start reservation. Destruction cleanup is retained for a service that
accepted a real one-use Connect/auto-connect start. Activity stop/recreation detaches presentation without
disconnecting a retained session. Activity commands use the service-backed ordered transport proxy; stale queued
work is rejected, and activity-local command dialogs are dismissed rather than retained as session state.

The test transports, endpoints, account names, command text and credentials are synthetic. The SSH mismatch
integration uses an in-process Apache MINA server and the real production JSch/backend/transport/factory/controller
path. No user host, live SSH server or physical device was used. This is not evidence of five-minute physical-device
app-switch continuity.

## Verification

The final matrix was run against commit `7091e09e7a1ee6497cd3381f0e429ebdf30b2fe1` plus the current unstaged
work. Both `ANDROID_HOME` and `ANDROID_SDK_ROOT` were `/home/homoludens/Android/Sdk`; ADB server port was
**5038**. APK identity was checked against the approved AVD fingerprint immediately before each install, and
installed app/instrumentation APKs were pulled and SHA-256 checked before the full runs.

### Build, JVM tests and artifacts

Command: `./gradlew testDebugUnitTest lint assembleDebug assembleRelease assembleDebugAndroidTest --no-daemon`.
The full build after the final source/test changes succeeded; final follow-up log:
`/tmp/opencode/xmouse-task54-final-build-v7.txt` (`BUILD SUCCESSFUL`).

- JVM: **311 tests in 32 suites; 0 failures, 0 errors, 0 skipped**.
- Lint: **137 warnings, 0 errors** (`app/build/reports/lint-results-debug.xml`).
- Debug, unsigned release and instrumentation APKs assembled successfully.

| Artifact | Size (bytes) | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 19,657,205 | `dbc170b8e946561cf0c97862342d6ec038610f6521b1ea92b028887e6cb4d3c0` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 14,408,433 | `de98210b49fb6bed945355f7b6d6a172e4460e747cadc77070d3e25f282a1ec0` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 916,361 | `8414f6cf6047b3bb3f3776bcac3b533948a550e11a8446f2ab838c2ef2debdf2` |

### Clean instrumentation

The approved disposable AVDs were run sequentially with app and instrumentation data cleared before each clean
full run. AVD directory: `/tmp/opencode/xmouse-task52-avd`; AVDs: `xmouse_task41_api26` and `xmouse_task31`.
The API 35 AVD used host-GPU rendering. Full runs used
`adb -P 5038 -s <serial> shell am instrument -w -r com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner`.

| API | AVD / serial | Build identity | Full result | Log |
| --- | --- | --- | --- | --- |
| 26 | `xmouse_task41_api26` / `emulator-5584` | Android 8.0.0; `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` | **130 discovered: 128 passed, 2 assumption-skipped, 0 failed**; 414.745 s | `/tmp/opencode/xmouse-task54-api26-final-clean-instrumentation-v5.txt` |
| 35 | `xmouse_task31` / `emulator-5580` | Android 15; `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`; host-GPU | **134 discovered: 127 passed, 7 assumption-skipped, 0 failed**; 394.678 s | `/tmp/opencode/xmouse-task54-api35-final-clean-instrumentation-host-gpu-v4.txt` |

Both full runs skipped the two opt-in system-backup fixture tests. API 35 additionally skipped the five
permission-state cases in the clean full run; each was run separately against the same installed final APKs and
passed (**1 test, 0 failures**):

| Permission case | Result log | Denied-state precondition log |
| --- | --- | --- |
| `notificationPermissionGrantedAtPointOfUsePublishesActionsAfterSystemConsent` | `/tmp/opencode/xmouse-task54-api35-final-permission-grant.txt` | `/tmp/opencode/xmouse-task54-api35-final-permission-grant-precondition.txt` |
| `notificationPermissionDenialExplainsTaskManagerAndPreservesInAppDisconnect` | `/tmp/opencode/xmouse-task54-api35-final-permission-denial.txt` | `/tmp/opencode/xmouse-task54-api35-final-permission-denial-precondition.txt` |
| `revokedNotificationPermissionAllowsSessionAndInAppDisconnectWithoutPromptingOnResume` | `/tmp/opencode/xmouse-task54-api35-final-permission-revoked.txt` | `/tmp/opencode/xmouse-task54-api35-final-permission-revoked-precondition.txt` |
| `permanentNotificationDenialOffersSettingsInsteadOfAnotherRuntimePrompt` | `/tmp/opencode/xmouse-task54-api35-final-permission-permanent.txt` | `/tmp/opencode/xmouse-task54-api35-final-permission-permanent-precondition.txt` |
| `stalePermissionResultsAfterRecreationAndDisconnectCannotStartOrClearNewerRequest` | `/tmp/opencode/xmouse-task54-api35-final-permission-stale.txt` | `/tmp/opencode/xmouse-task54-api35-final-permission-stale-precondition.txt` |

Every isolated case began with `POST_NOTIFICATIONS` denied and both app/test data cleared. The AVD's original
permission state was granted; it was restored to **granted**, with `user-set` and `user-fixed` flags clear.
Evidence: `/tmp/opencode/xmouse-task54-api35-final-permission-restored.txt`. Both AVDs were stopped afterward;
ADB listed no active devices and no emulator process remained.

### Task-specific regressions

- `autoConnectOffIdleBindTeardownThenEnableAndReturnConnectsExactlyOnce` performs the reviewed OFF → idle
  Activity bind → service teardown → ON → return sequence. It also verifies teardown preserves a pending
  auto-connect reservation and controller intent, and return selects/creates exactly one session.
- `activityRecreationAndReturnWithAutoConnectEnabledReattachToTheExistingSession` verifies the existing ready
  attempt is neither closed nor duplicated on recreation/return. Existing started-service destruction,
  explicit-Disconnect suppression, and uncertain-close tests remain in the instrumentation suite.
- `actualFactoryControllerPublishesChangedHostReviewOnlyAfterConfirmedCloseAndReplacementDoesNotResumeHandshake`
  drives a changed key through the real `SessionController`, `SshSessionAttemptFactory`, `SshTransport`, JSch
  policy/backend and MINA loopback server. A gated real-session close proves no review appears before confirmed
  cleanup; endpoint and complete trusted/presented keys are checked; explicit CAS replacement persists; the
  blocked handshake never authenticates or sends a command; a fresh Connect accepts the new key and rejects
  the old key without authenticating it.
- `actualFactorySuppressesChangedKeyReviewAfterCancellationDetachDisconnectStalenessOrUnconfirmedClose`
  verifies cancellation, detach, Disconnect, superseding host intent and unconfirmed cleanup cannot publish a
  replacement review.
- Existing consent, Activity output-dialog and queued shell/exec command regressions continue to pass, including
  `SessionControllerTest.queuedShellAndExecCommandsAreDroppedAcrossHostReplacementAndRecovery` and
  `explicitDisconnectInvalidatesAlreadyQueuedShellAndExecCommands`.

Additional checks passed after the final source change:

- `python3 docs/verification/verify-v2-storage-fixtures.py`: **4/4** fixtures.
- `python3 docs/verification/audit-ssh-transport.py`: source boundary/privacy checks passed; no unverified-host
  fallback.
- `openspec validate modernize-xmouse --strict`: valid.
- `git diff --check`: clean.

### Exact 5.4 changed-path manifest

This is the complete set of modified/new paths attributable to task 5.4, including adapted tests, the source audit,
the task checkbox and this verification record:

- `app/src/androidTest/java/com/stripe1/xmouse/HostTrustAndroidTest.kt`
- `app/src/androidTest/java/com/stripe1/xmouse/LoggingPrivacyAndroidTest.kt`
- `app/src/androidTest/java/com/stripe1/xmouse/secrets/CredentialRepositoryAndroidTest.kt`
- `app/src/androidTest/java/com/stripe1/xmouse/session/SessionForegroundServiceAndroidTest.kt`
- `app/src/main/java/com/stripe1/xmouse/CustomKeyboardButtonAdapter.java`
- `app/src/main/java/com/stripe1/xmouse/HostListItemAdapter.java`
- `app/src/main/java/com/stripe1/xmouse/MainActivity.java`
- `app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java`
- `app/src/main/java/com/stripe1/xmouse/secrets/CredentialUi.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionActivityPresenter.kt` (new)
- `app/src/main/java/com/stripe1/xmouse/session/SessionCommandTransport.kt` (new)
- `app/src/main/java/com/stripe1/xmouse/session/SessionController.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionForegroundService.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionInteractions.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionServiceRuntime.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SshSessionAttemptFactory.kt`
- `app/src/main/java/com/stripe1/xmouse/trust/AndroidHostTrust.kt`
- `app/src/main/java/com/stripe1/xmouse/trust/HostTrustRequests.kt`
- `app/src/test/java/com/stripe1/xmouse/session/SessionControllerTest.kt`
- `app/src/test/java/com/stripe1/xmouse/session/SessionPipelineIntegrationTest.kt`
- `docs/verification/audit-ssh-transport.py`
- `openspec/changes/modernize-xmouse/tasks.md`
- `docs/verification/session-activity-binding.md`

### Failure and rerun history

- An initial API 26 emulator launch timed out while the emulator/ADB client and server used mismatched ports or
  binaries. It was restarted with the SDK-matched ADB client on port 5038; final API 26 clean instrumentation
  completed with zero failures.
- The first API 35 full run exposed suite-order contamination: a preceding started Service could finish destruction
  after the next test installed its owner. Instrumentation setup now stops the prior service and waits for all
  created service instances to be destroyed before clearing/injecting the owner. The rebuilt final APKs passed the
  complete API 35 run.
- API 35 SwiftShader runs had previously caused System UI ANRs; the approved AVD was run with host-GPU rendering.
- The isolated permission tests require a host-controlled denied permission state. Each was run separately after
  clearing data and confirming denial, then the original granted state was restored and verified.

No files were staged and no commit was created. Only task 5.4 is marked complete; live SSH and physical-device
five-minute continuity remain unverified.
