# Started-and-bound connected-device session service (task 5.2)

Task **5.2 [low]** implementation and verification only. The session controller/owner handoff is 5.1;
the notification's Open/Disconnect actions and POST_NOTIFICATIONS runtime request/explanation are 5.3;
MainActivity's Connect controls and legacy transport retirement are 5.4. The existing visible MainActivity
is used to exercise the new `SessionForegroundService.requestConnect` entry point; its legacy menu remains
unchanged until 5.4. No five-minute app-switch acceptance, API 37 LAN permission, or later task is claimed.

## Production contract

- `SessionForegroundService` is explicitly declared `exported=false` with the `connectedDevice` FGS type.
  The manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `CHANGE_WIFI_MULTICAST_STATE` (the documented network-connected-device prerequisite and a normal
  permission), `INTERNET`, `ACCESS_NETWORK_STATE`, and the POST_NOTIFICATIONS declaration needed by the
  later 5.3 permission flow.
- The only production start API accepts an Activity and refuses calls unless it is not finishing/destroyed,
  is at least STARTED, and its window is visible. It starts an explicit service request, returns a distinct
  result for not-visible and platform-rejected calls, and cancels the transient one-use ticket if Android
  rejects the start. No host, username, password, passphrase, key bytes, or other connection data is put in
  the Intent. A ticket is in-memory-only, one-use, bounded, and tied to the expected action.
- A valid request promotes the service with `startForeground` before resolving the process owner or asking
  it to read selected-host metadata. The channel and ongoing notification are generic: no endpoint or
  authentication data is shown. Notification permission prompting/denial explanation and actions remain
  5.3; the platform disclosure is not hidden when notification-tray permission is absent.
- Service binding alone only exposes the current immutable snapshot, a detachable state observer, and the
  weak prompt broker. It does not start foreground execution or select a host. A binder's subscription
  closes on unbind/destroy. Session/network work is requested only after a valid started request and
  remains asynchronous behind the existing controller/attempt owner.
- `XMouseApplication` retains one `AndroidSessionOwner` per process. Ordinary unbind detaches binder
  observers without disconnecting the started service's session. Actual `onDestroy` requests a
  nonblocking controller disconnect, removes the disclosure, and retains the same owner/controller; a
  recreated service cannot evade its `CLEANUP_UNCONFIRMED` barrier or lose explicit-disconnect
  auto-connect suppression. Process death ends the physical session; a new process has no saved restart
  ticket and does not start itself.
- Every selected-host completion is checked against its latest valid Android `startId` before publishing
  a failure or requesting a controller change. The controller queue rechecks both the captured disconnect
  epoch and request currency before accepting explicit or automatic service Connect, so an out-of-order
  completion, explicit Disconnect, or service destruction cannot revive stale metadata work. The service
  receives a serialized acceptance/rejection acknowledgment guarded by the same start ID and destruction
  state. Only a failed/rejected latest selection sets the deferred-idle-stop flag; every new valid request
  clears it. Retiring stale callbacks can complete that deferred stop only while the flag remains set, so
  an older result cannot stop a newer accepted-but-not-yet-applied Connect.
- `onStartCommand` handles null, unknown-action, or unissued-ticket starts without selection/network work
  and always returns `START_NOT_STICKY`. There is no boot receiver, alarm, WorkManager job, or sticky
  redelivery path. A future foreground auto-connect uses the same visible-Activity launcher and captures
  the controller permit before asynchronous metadata/credential preparation.

## Android platform guidance consulted (2026-10-21)

- [Connected-device foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device): requires the manifest service type, `FOREGROUND_SERVICE_CONNECTED_DEVICE` in addition to `FOREGROUND_SERVICE`, and at least one documented prerequisite. The selected `CHANGE_WIFI_MULTICAST_STATE` network prerequisite is declared; it is not treated as a runtime grant.
- [Foreground-service launch](https://developer.android.com/develop/background-work/services/fgs/launch): start with `startForegroundService`, then promote from `onStartCommand`; service type must match the manifest. The service promotes before selected-host/credential work.
- [Background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): target-31+ apps cannot ordinarily start an FGS from the background; a user-visible activity is a permitted source, and invalid attempts can raise `ForegroundServiceStartNotAllowedException`. The launcher rejects a non-visible Activity and reports Android's synchronous start failure.
- [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission#foreground-service): `POST_NOTIFICATIONS` is not a prerequisite to launch an FGS, but an FGS must still provide its notification. If the user denies notification permission, Android shows the FGS notice in Task Manager rather than the notification drawer. This release declares the permission for 5.3 but does not prompt or claim drawer visibility here.

## Verification results

Final verification was run on 2026-10-21 against the same APK pair on the existing approved disposable
AVDs; no new AVD copies or user AVDs were used. Every Android tooling command set `ANDROID_HOME` and
`ANDROID_SDK_ROOT` to `/home/homoludens/Android/Sdk`; ADB used server port 5038.

Build command:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- Build: `BUILD SUCCESSFUL`; 307 unit tests across 32 suites, zero skipped/failures/errors.
- `lintDebug`: 137 warnings, zero errors. In the task 5.2 source/test files, the only remaining lint
  warning is `QueryPermissionsNeeded` on the test's package-scoped query of this app for a boot receiver;
  it does not query other packages and no `<queries>` entry was added. Six avoidable warnings found in the
  earlier task-file inventory (`ApplySharedPref`, `InlinedApi`, two `ObsoleteSdkInt`, two `UseKtx`) were
  removed. The related deprecated platform `PreferenceManager` compiler warnings were also removed. No
  blanket or detector-wide suppression was added.
- Final APK artifacts:

  | APK | Size | SHA-256 |
  | --- | ---: | --- |
  | `app/build/outputs/apk/debug/app-debug.apk` | 19,527,961 bytes | `94ae09fcafb6e42a5a00f7a8efdd69fcf3b168b1059ac1cf079573aeb69a5c7d` |
  | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 661,805 bytes | `5492abc1a45938e656ae4d6ab41f1a1e5a24fa4a9f08953bfbbceb7a47de810e` |

`aapt dump permissions` and `aapt dump xmltree` on that final app APK verified package `com.stripe1.xmouse`,
version `2.0.27` (code 25), compile SDK 37, min SDK 26, and target SDK 37. The exact merged `uses-permission`
set is `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `HIGH_SAMPLING_RATE_SENSORS`, and the
AndroidX-generated `com.stripe1.xmouse.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. The manifest declares
`com.stripe1.xmouse.session.SessionForegroundService` with `exported=false` and
`foregroundServiceType=0x10` (`connectedDevice`). Its only merged receiver is AndroidX ProfileInstaller; it
has no `BOOT_COMPLETED` filter. Instrumentation also verified the connected-device `ServiceInfo` type,
required permission declarations, and absence of a boot receiver on-device.

The full instrumentation command on each device was:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  adb -P 5038 -s <serial> shell am instrument -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

| API | AVD / serial | AVD directory and system image | SDK / release / build / patch / ABI | `ro.build.fingerprint` | Discovered / passed / skipped / failed | Time |
| --- | --- | --- | --- | --- | ---: | ---: |
| 26 | `xmouse_task41_api26` / `emulator-5584` | `/tmp/opencode/xmouse-task52-avd/xmouse_task41_api26.avd`; `/home/homoludens/Android/Sdk/system-images/android-26/google_apis/x86_64` | 26 / 8.0.0 / `OSR1.180418.026` / 2018-04-05 / x86_64 | `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` | 115 / 113 / 2 / 0 | 166.755 s |
| 35 | `xmouse_task31` / `emulator-5580` | `/tmp/opencode/xmouse-task52-avd/xmouse_task31.avd`; `/home/homoludens/Android/Sdk/system-images/android-35/google_apis/x86_64` | 35 / 15 / `AE3A.240806.043` / 2024-09-05 / x86_64 | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` | 115 / 113 / 2 / 0 | 250.473 s |

The runner's `numtests=115` includes two assumption skips (`INSTRUMENTATION_STATUS_CODE=-4`), not 115
passes. The skipped tests are `stageSyntheticPackageDataForSystemBackup` and
`verifyMetadataAndReentryAfterSystemTransportRestore`; both are explicitly opt-in destructive backup
fixtures and call `assumeTrue` unless the corresponding backup scenario argument is supplied. Each device
reported 113 terminal pass statuses (`0`), 2 skips (`-4`), and 0 failures (`-2`). All nine
`SessionForegroundServiceAndroidTest` cases reported pass status `0` on both APIs, including held-controller-
queue interleavings where an older success or failure retires before the newest accepted Connect is applied,
and where a latest failure defers stopping until an older valid selection retires.

The service tests verify actual visible-Activity foreground start and notification/type enforcement; bind-only,
null/unknown-action, background-start rejection, and `START_NOT_STICKY`; unbind/rebind without disconnect;
actual service destruction closes active work while preserving the owner, suppression, and uncertain-close
barrier; out-of-order old success/failure cannot supersede the newest explicit/automatic request; and
Disconnect during metadata preparation invalidates that result while a later visible request can proceed.
API 35 grants `POST_NOTIFICATIONS` inside the disposable test environment only to inspect the FGS notice;
the 5.3 runtime-permission UX is not exercised. Attempts use synthetic hosts and recording transports, not
live SSH.

Task 5.2 implementation/evidence files:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/stripe1/xmouse/XMouseApplication.kt`
- `app/src/main/java/com/stripe1/xmouse/session/AndroidSessionOwner.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionForegroundService.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionServiceRuntime.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionController.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/androidTest/java/com/stripe1/xmouse/session/SessionForegroundServiceAndroidTest.kt`
- `app/src/test/java/com/stripe1/xmouse/session/SessionControllerTest.kt`
- `openspec/changes/modernize-xmouse/tasks.md`
- `docs/verification/session-service.md`

`MainActivity` was not migrated; no 5.3 notification action/permission UX was added. Task 5.2 is marked
complete after the final verification above and is ready for parent review.

Both emulators were stopped after their runs. `openspec validate modernize-xmouse --strict` and
`git diff --check` passed after the evidence update. No personal device, user AVD, user SSH host, system
`sshd`, or system account was used.

## Evidence limits

This evidence proves service and controller lifecycle composition, not a live SSH connection, notification
Disconnect action, notification permission UX, LAN access on API 37, process-death recovery, task-dismissal
cleanup, or the five-minute physical-device app-switch scenario. Those later-task/release checks remain not
run and are not claimed by task 5.2.
