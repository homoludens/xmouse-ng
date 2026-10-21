# Session notification controls and permission UX (task 5.3)

This record covers task **5.3 [low]** only. The implementation publishes explicit, immutable notification
Open controls and Disconnect actions, requests notification permission at the in-app point of use on API 33+,
explains denial/settings behavior, and retains an in-app Session controls menu entry. Task 5.2's service request
epoch/start-ID acceptance, deferred idle-stop bookkeeping, non-sticky lifecycle, and retained-owner cleanup
barrier remain in place. Task 5.4's full Activity/session binding and retirement of the legacy Activity-owned
transport are not implemented or claimed here.

## Behavior and safety

- The foreground notification has a content/Open controls `PendingIntent` targeting `MainActivity` and a
  separate service `PendingIntent` for Disconnect. Both are explicit and use `FLAG_IMMUTABLE`;
  API 35 instrumentation additionally checks the framework's immutable bit. Open uses the existing task and
  `singleTop` Activity path. Notification Open sets a one-resume legacy-auto-connect guard.
- Legacy automatic connection has a separate suppression gate from the active-session UI state. It covers
  both ordinary `onResume` and the asynchronous saved-host selection callback, including a clean notification
  Disconnect whose retained controller now has no `desiredHost`. The controller's disconnect suppression
  remains set across ordinary resume and launcher `ACTION_MAIN`; an explicit Session controls Start clears it
  and remains available.
- The ongoing notification uses channel `xmouse_connected_session` at LOW importance, title
  **XMouse remote session**, generic network-connection text, and the exact actions **Open controls** and
  **Disconnect**. It contains no endpoint or authentication data.
- Disconnect consults only an already-retained application owner. It explicitly disconnects that controller,
  removes the foreground notification and stops the service; it never selects a host or creates an owner.
  Pending selected-host work is invalidated before it can start transport. An unconfirmed transport close
  leaves the retained owner/controller cleanup barrier intact rather than allowing a later action to bypass it.
- The new **Session controls** menu opens in-app Start/Open/Disconnect controls and describes when Android
  may hide notification-drawer actions. Starting a service session is refused while the legacy Activity
  connection is already connected/connecting, avoiding a parallel transport while 5.4 is pending.
- The reverse coexistence gate also blocks legacy toolbar/direct `xMouseTryConnect` while a foreground start
  is reserved for the permission flow, while selected-host metadata is pending (even with `desiredHost == null`),
  or while a service session is active/cleaning up. It runs before legacy credential/transport I/O and is
  rechecked by captured credential callbacks. Bounded one-use start tickets retain this pending state through
  permission and service dispatch; explicit Session controls Start is not disabled by an inactive, suppressed
  service owner. This is a focused coexistence guard, not task 5.4's Activity/session binding or migration.
- On API 33+, choosing Start first shows an XMouse rationale, then requests `POST_NOTIFICATIONS`. A denial
  explains that the required foreground-service disclosure remains in Android Task Manager rather than
  promising a drawer notification; the user may continue without drawer actions, retry where allowed, or open
  notification settings. A prior/permanent denial goes to Settings instead of looping the runtime prompt.
  Notification denial does not block a legal foreground session or the in-app Disconnect control.
- The Activity Result callback is bound to the exact still-reserved, undispatched start ticket that requested
  permission. Activity destruction cancels that activity-owned reservation; a missing, stale, already-dispatched,
  or Disconnect-invalidated ticket is ignored without showing a Continue action or allocating a replacement.
  Stale callbacks cannot clear a newer ticket. The API 35 lifecycle regression reflectively delivers grant and
  denial through the same callback method registered with `RequestPermission` after recreation and Disconnect,
  checks service/host/legacy-attempt counters, and confirms a fresh explicit in-app Start still works. System
  Allow/Don't allow interaction remains covered by the separate real permission-dialog flows below.
- API 26–32 do not request the unsupported runtime permission and still publish the foreground notification
  and actions. Notification/channel visibility can still be changed in system settings; the in-app controls
  remain available.

## Final build and APK evidence

The final build used both required SDK environment variables:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL`; 307 unit tests passed, with zero failures/errors/skips. Lint reported zero errors
and 137 warnings across the existing project; no detector-wide suppression was added. The final APKs used for
the runs below were:

| APK | Size | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 19,651,956 bytes | `bbd66b20769d383b0a90dd69b38774375b0c3ba3da564e370e0ca3a3b0ceb3c5` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 806,662 bytes | `80a26597a2a8fa68bbf673929703a8fc392aa421924b5b1e9ccbe9b72b718f7e` |

`aapt` 36.0.0 on the final app APK reported package `com.stripe1.xmouse`, version `2.0.27` (code 25),
compile/target API 37, and min API 26. The generated merged manifest is
`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`; the final
`aapt dump permissions app/build/outputs/apk/debug/app-debug.apk` output is exactly:

```text
package: com.stripe1.xmouse
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.CHANGE_WIFI_MULTICAST_STATE'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.HIGH_SAMPLING_RATE_SENSORS'
permission: com.stripe1.xmouse.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.stripe1.xmouse.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

The same merged manifest has `uses-feature android.hardware.sensor.gyroscope` (required by default), and declares
`com.stripe1.xmouse.session.SessionForegroundService` with `exported=false` and
`foregroundServiceType=connectedDevice` (`0x10`). `MainActivity` remains exported as the launcher Activity and
has `launchMode=singleTop` for the explicit notification Open action. The API 26 and API 35 installed `base.apk`
files were pulled back from each device and hash-matched to the two APKs in the table above.

## Device instrumentation

Each final APK install was preceded by checking the approved AVD identity, API, and build fingerprint. Android
commands used both `ANDROID_HOME=/home/homoludens/Android/Sdk` and
`ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk`, with ADB server port 5038. Both AVDs are the approved
disposable images under `/tmp/opencode/xmouse-task52-avd`; rendering used host GPU. The API 26 identity was
verified from the emulator process command line, AVD `config.ini` system-image path, serial, API, and
fingerprint; this image does not publish `ro.boot.qemu.avd_name`.

| API | AVD / serial / directory | System image | Release / build / ABI | Fingerprint | Tests / pass / skip / fail | Duration |
| ---: | --- | --- | --- | --- | ---: | ---: |
| 26 | `xmouse_task41_api26` / `emulator-5584` / `/tmp/opencode/xmouse-task52-avd/xmouse_task41_api26.avd` | `/home/homoludens/Android/Sdk/system-images/android-26/google_apis/x86_64` | Android 8.0.0 / `OSR1.180418.026` / x86_64 | `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` | 122 / 120 / 2 / 0 | 246.762 s |
| 35 | `xmouse_task31` / `emulator-5580` / `/tmp/opencode/xmouse-task52-avd/xmouse_task31.avd` | `/home/homoludens/Android/Sdk/system-images/android-35/google_apis/x86_64` | Android 15 / `AE3A.240806.043` / x86_64 | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` | 126 / 119 / 7 / 0 | 271.818 s |

Before the final clean-baseline runs, the final app and test APKs were installed on both AVDs with these
commands:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
ADB=/home/homoludens/Android/Sdk/platform-tools/adb
$ADB -P 5038 -s emulator-5584 install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -P 5038 -s emulator-5584 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$ADB -P 5038 -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -P 5038 -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Each installed `base.apk` was pulled to
`/tmp/opencode/xmouse-task53-lifecycle-installed-app-api26.apk`,
`/tmp/opencode/xmouse-task53-lifecycle-installed-test-api26.apk`,
`/tmp/opencode/xmouse-task53-lifecycle-installed-app-api35.apk`, and
`/tmp/opencode/xmouse-task53-lifecycle-installed-test-api35.apk`, and matched the corresponding build SHA-256. Only the approved disposable `com.stripe1.xmouse` and
`com.stripe1.xmouse.test` package data were cleared on each verified AVD. The exact reset commands were:

```sh
$ADB -P 5038 -s emulator-5584 shell pm clear com.stripe1.xmouse
$ADB -P 5038 -s emulator-5584 shell pm clear com.stripe1.xmouse.test
$ADB -P 5038 -s emulator-5580 shell pm clear com.stripe1.xmouse
$ADB -P 5038 -s emulator-5580 shell pm clear com.stripe1.xmouse.test
```

The API 35 full-suite precondition was then set to `POST_NOTIFICATIONS` granted and verified with `dumpsys`:

```sh
$ADB -P 5038 -s emulator-5580 shell pm grant com.stripe1.xmouse android.permission.POST_NOTIFICATIONS
$ADB -P 5038 -s emulator-5580 shell dumpsys package com.stripe1.xmouse | grep 'android.permission.POST_NOTIFICATIONS: granted='
```

The final suite commands were:

```sh
$ADB -P 5038 -s emulator-5584 shell am instrument -w -r com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
$ADB -P 5038 -s emulator-5580 shell am instrument -w -r com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

API 26 passed all notification/session-coexistence tests, including
`api26ConnectControlDoesNotRequestUnsupportedNotificationPermission`; its two skips were the opt-in destructive
backup fixtures:
`stageSyntheticPackageDataForSystemBackup` and `verifyMetadataAndReentryAfterSystemTransportRestore`.

### API 26 retained-state diagnosis and resolution

The earlier API 26 full run and its two isolated reproductions began with stale target-app data; regardless of
whether an earlier clear had been attempted, the app package was not in a clean state. The test package's earlier
clear status could not be established from its run log (its inspected data directory contained only `cache/` and
`code_cache/`). Before reset, the target app had zero host rows, one encrypted secret file, and protected-reference
preference keys. A temporary worker-thread diagnostic emitted only sanitized states: default-password,
global-passphrase, and global-private-key references were each `MISSING_RECORD`; the first preflight failure was
`defaultPassword`; `prepare=false`, `writable=false`, and migration was `NOT_REACHED`. These were missing records,
not a Keystore `KEY_UNAVAILABLE` result. The temporary probe was run with:

```sh
adb -P 5038 -s emulator-5584 shell am instrument -w -r -e class com.stripe1.xmouse.secrets.DefaultCredentialStateDiagnosticAndroidTest com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

Its invocation outputs are
`/tmp/opencode/xmouse-task53-api26-baseline-credential-diagnostic.txt` and
`/tmp/opencode/xmouse-task53-api26-clean-credential-diagnostic-v2.txt`. The exact sanitized lines are retained in
`/tmp/opencode/xmouse-task53-api26-credential-baseline-diagnostics.txt`.

This explains both symptoms without implicating API 26 crypto or Settings behavior. `CredentialRepository.prepare()`
validates published references before migration and correctly fails closed on the missing default-password
record. `PrivateKeyImportAndroidTest.uiFixture()` removes the global private-key/passphrase references and raw
legacy key path, but intentionally snapshots/restores other default preferences and app data. Consequently,
`SettingsActivity` never reaches the system-picker import in
`actualSettingsDispatchesSystemPickerImportsProviderAndRetainsKeyAfterRemoval`; the same early `prepare()` failure
prevents migration/removal of the `pref_key_passphrase` fixture value in
`actualSettingsInvalidDocumentResultKeepsPreviousUsableKey`.

After clearing both approved disposable packages, the sanitized diagnostic showed all three references `ABSENT`,
zero secret files, `prepare=true`, `writable=true`, and migration `COMPLETED`. The two original tests then passed
individually, with their original assertions intact. These focused checks used a temporary test APK that added
only the diagnostic probe; the target app APK was unchanged. The probe was removed before the final build, and
both tests also passed in the final-APK full suite:
`/tmp/opencode/xmouse-task53-clean-api26-picker-repro.txt` and
`/tmp/opencode/xmouse-task53-clean-api26-invalid-document-repro.txt`. No production/test-fixture change, timeout
extension, assertion removal, key reset, or migration bypass was needed. The final clean full-suite log is
`/tmp/opencode/xmouse-task53-lifecycle-final-api26-instrumentation.txt` (122 total, 120 passed, the two backup skips,
zero failures); the earlier failing-run history remains in
`/tmp/opencode/xmouse-task53-final-api26-instrumentation.txt`,
`/tmp/opencode/xmouse-task53-repro-picker-api26.txt`, and
`/tmp/opencode/xmouse-task53-repro-invalid-document-api26.txt`.

The final API 35 full run skipped the same two backup fixtures, four stateful permission-flow tests, and the new
denied-permission lifecycle regression. It started with notification permission granted, so those five
permission-dependent tests were skipped by their explicit preconditions. The four system grant/denial flows and
the lifecycle regression were then run separately against the same final APK pair. Each passed:

| Isolated API 35 case | Result |
| --- | --- |
| `notificationPermissionGrantedAtPointOfUsePublishesActionsAfterSystemConsent` | 1 passed; system Allow granted permission and published both actions; `/tmp/opencode/xmouse-task53-lifecycle-final-permission-grant-api35.txt` |
| `notificationPermissionDenialExplainsTaskManagerAndPreservesInAppDisconnect` | 1 passed; Don't allow, continue legally, then in-app Disconnect closed transport and removed the notice; final retry `/tmp/opencode/xmouse-task53-lifecycle-final-permission-denial-api35-retry.txt` |
| `revokedNotificationPermissionAllowsSessionAndInAppDisconnectWithoutPromptingOnResume` | 1 passed; session started after external revocation, remained available across Activity resume, and disconnected in-app; `/tmp/opencode/xmouse-task53-lifecycle-final-permission-revoked-api35.txt` |
| `permanentNotificationDenialOffersSettingsInsteadOfAnotherRuntimePrompt` | 1 passed; Settings opened, and retry returned to Settings guidance rather than another runtime prompt; `/tmp/opencode/xmouse-task53-lifecycle-final-permission-permanent-api35.txt` |
| `stalePermissionResultsAfterRecreationAndDisconnectCannotStartOrClearNewerRequest` | 1 passed; recreated Activity ignored grant/denial without the canceled ticket; old-ticket results preserved a newer request; Disconnect invalidated its reservation without host selection, service restart, transport attempt, or legacy connect; fresh explicit Start succeeded; `/tmp/opencode/xmouse-task53-lifecycle-stale-result-api35.txt` |

For each isolated API 35 run, the host first verified AVD `xmouse_task31`, API 35, and fingerprint
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`, then applied:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
ADB=/home/homoludens/Android/Sdk/platform-tools/adb
$ADB -P 5038 -s emulator-5580 shell pm revoke com.stripe1.xmouse android.permission.POST_NOTIFICATIONS
$ADB -P 5038 -s emulator-5580 shell pm clear-permission-flags com.stripe1.xmouse android.permission.POST_NOTIFICATIONS user-set
$ADB -P 5038 -s emulator-5580 shell pm clear-permission-flags com.stripe1.xmouse android.permission.POST_NOTIFICATIONS user-fixed
$ADB -P 5038 -s emulator-5580 shell dumpsys package com.stripe1.xmouse | grep 'android.permission.POST_NOTIFICATIONS: granted='
```

The final `dumpsys` precondition was `granted=false` with neither `user-set` nor `user-fixed`. The grant and
denial tests clear the app's `session_notification_permission_requested` preference before prompting; the
revoked and permanent-denial tests set that preference to `true` before Start. The permanent-denial test itself
sets `user-set user-fixed` before presenting Settings guidance. Each exact isolated invocation was as follows
(the precondition block above was repeated before each run):

```sh
$ADB -P 5038 -s emulator-5580 shell am instrument -w -r -e class com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#notificationPermissionGrantedAtPointOfUsePublishesActionsAfterSystemConsent com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
$ADB -P 5038 -s emulator-5580 shell am instrument -w -r -e class com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#notificationPermissionDenialExplainsTaskManagerAndPreservesInAppDisconnect com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
$ADB -P 5038 -s emulator-5580 shell am instrument -w -r -e class com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#revokedNotificationPermissionAllowsSessionAndInAppDisconnectWithoutPromptingOnResume com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
$ADB -P 5038 -s emulator-5580 shell am instrument -w -r -e class com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#permanentNotificationDenialOffersSettingsInsteadOfAnotherRuntimePrompt com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
$ADB -P 5038 -s emulator-5580 shell am instrument -w -r -e class com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#stalePermissionResultsAfterRecreationAndDisconnectCannotStartOrClearNewerRequest com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

Permission revocation was applied by the host between instrumentation processes because Android terminates
the target process on runtime-permission revocation; the revoked-flow evidence is for the next foreground use,
not survival of an active process. The first isolated denial attempt hit a transient UiAutomator
`StaleObjectException` while querying the system permission dialog's changing accessibility tree. After clearing
only the disposable app/test data and resetting permission flags, the unchanged test passed on retry
(`/tmp/opencode/xmouse-task53-lifecycle-final-permission-denial-api35-retry.txt`); the initial attempt remains at
`/tmp/opencode/xmouse-task53-lifecycle-final-permission-denial-api35.txt`. After all isolated runs, the host
restored `POST_NOTIFICATIONS` to granted, cleared `user-set`/`user-fixed`, and verified `granted=true`. The final
full-suite logs are `/tmp/opencode/xmouse-task53-lifecycle-final-api26-instrumentation.txt` and
`/tmp/opencode/xmouse-task53-lifecycle-final-api35-instrumentation.txt`. The focused lifecycle run before the
clean final suites is also retained at `/tmp/opencode/xmouse-task53-permission-lifecycle-api35.txt`. Session
attempts use a synthetic recording transport, not a live SSH host.

## Evidence limits

This is API 26 and API 35 emulator evidence, not physical-device acceptance. It does not claim API 33/34/36/37,
LAN access, live SSH continuity, or task 5.4 Activity/session migration. Notification denial was verified not
to block a newly requested foreground session and to retain in-app Disconnect; OS process termination on a
later actual permission revocation is documented above rather than represented as an in-process survival
guarantee. The earlier API 26 Settings failures were caused by stale disposable app-package state and pass on
the clean baseline described above. The final API 26 and API 35 cross-version emulator suites have no failures;
the listed backup and stateful-permission/lifecycle skips remain intentional and were separately addressed where
required.

## Final change manifest and OpenSpec bookkeeping

This reviewed task's baseline is commit `daffd27`. Marking task 5.3 complete brings progress to **27/67**
actionable tasks. The change's complexity allocation remains **14 [high] / 53 [low]**; the task tags and scopes
were not changed. Task 5.4 remains unchecked and is not implemented by this work.

Exact task 5.3 file manifest (repository-relative paths):

- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/stripe1/xmouse/MainActivity.java`
- `app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java`
- `app/src/main/java/com/stripe1/xmouse/session/SessionForegroundService.kt`
- `app/src/main/java/com/stripe1/xmouse/session/SessionServiceRuntime.kt`
- `app/src/main/res/menu/main.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/androidTest/java/com/stripe1/xmouse/session/SessionForegroundServiceAndroidTest.kt`
- `app/src/testFixtures/java/com/stripe1/xmouse/transport/RecordingSshBackend.kt`
- `openspec/changes/modernize-xmouse/tasks.md`
- `docs/verification/session-notification.md`

This evidence is limited to the recorded API 26/API 35 emulator runs and synthetic recording transport; it does
not claim physical-device acceptance, live SSH continuity, or completion of task 5.4.
