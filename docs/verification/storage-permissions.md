# Storage-permission removal and key-auth verification (task 4.8)

Verified **2026-10-21**. Task **4.8 [low]** is complete: **24/67** tasks; the allocation remains
**14 high / 53 low**. No other task state changed. No staging or commit.

## Change and preserved behavior

- Removed the app's `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` manifest declarations. No
  `MANAGE_EXTERNAL_STORAGE` declaration was present. `INTERNET` remains declared; no networking or
  future local-network permission behavior was changed.
- An audit of `app/src/main` found no remaining `requestPermissions`, permission-result callback,
  `checkSelfPermission`, rationale, or all-files-settings path for the old key picker. There was no
  active runtime request/check/rationale code to remove from `SettingsActivity`, `MainActivity`, or
  `PrivateKeySettingsController`.
- `PrivateKeySettingsController` remains the Settings key-selection producer. Its preference launches
  `ACTION_OPEN_DOCUMENT` with `CATEGORY_OPENABLE`, `*/*`, and a read URI grant. It does not request
  filesystem permission or retain a provider URI. The validated encrypted app-private key remains
  usable after source removal/revocation.
- Existing readable legacy key paths may still be read and migrated through the same bounded key
  boundary. An inaccessible legacy path retains its configuration and asks the user to re-import via
  the document picker; no permission workaround was added. Protected credentials, key references,
  source-removal availability, global authentication choices, supported formats, and trust/deadline/
  transport behavior are unchanged.
- `FileDialog.java` was left untouched: it is not referenced outside its own legacy implementation,
  and no active key-import or permission callback uses it. Removing that unrelated file-browser class
  was unnecessary for permission cleanup. `SettingsActivity.java` and `MainActivity.java` also did
  not need permission-path edits.

## Final build, tests, and source checks

All Android tooling commands used both SDK variables. The final uncached build command was:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Result: **BUILD SUCCESSFUL**, **83/83 tasks executed** in 3m11s; **282 JVM tests / 29 suites**,
0 failures, errors, or skips; lint **0 errors / 136 warnings** and no finding in the new permission
test. The production SSH-authentication server matrix remained green: all three
`JschAuthenticationIntegrationTest` methods passed, covering password authentication, encrypted ECDSA,
and encrypted RSA SHA-2 when the synthetic server has no SHA-1 RSA signature factory.

Also passed:

```sh
python3 docs/verification/verify-v2-storage-fixtures.py  # 4/4 fixtures
python3 docs/verification/audit-ssh-transport.py         # bounded key/trust/transport source boundary
openspec validate modernize-xmouse --strict
git diff --check
```

The production-source audit searched for broad storage permission names and request/check/result/rationale
APIs under `app/src/main`; it found **no matches**. There are no obsolete key-picker permission callbacks
or tests expecting the app to request storage access. The previous two runtime-denial assertions in
`PrivateKeyImportAndroidTest` were consolidated into the stronger shared PackageManager assertion below.

## Shipped APK permission audit

`aapt dump permissions` on the final **app APK** reports these `uses-permission` entries:

```text
android.permission.INTERNET
android.permission.HIGH_SAMPLING_RATE_SENSORS
com.stripe1.xmouse.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

The app also defines the matching app-private receiver permission. Neither this APK nor the
instrumentation APK requests `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or
`MANAGE_EXTERNAL_STORAGE`. The separate test APK requests only `android.permission.REORDER_TASKS`;
its test-only document provider's `MANAGE_DOCUMENTS` component protection is not a target-app storage
permission request.

The exact audit commands were:

```sh
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump permissions app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump permissions app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The final app APK badging is application `com.stripe1.xmouse`, version **25 / 2.0.27**, min/target/
compile SDK **26 / 37 / 37**. SHA-256:

- App APK: `988d8333de2ce51c9c02b4bd4b78071d0aa1e074348e12708ccbbcef8445a540`
- Instrumentation APK: `02e7bc231a81043f61f9e9ad37f643f37d95cb1ab08c70e1cee503c39a3c3025`

The instrumentation test `StoragePermissionsAndroidTest.mergedAppManifestDoesNotRequestOrHoldBroadStoragePermissions`
queries the installed **target app** using `PackageManager.GET_PERMISSIONS`. It asserts all three broad
permissions are absent from the package's requested-permission list; `READ_EXTERNAL_STORAGE` and
`WRITE_EXTERNAL_STORAGE` are denied, and `MANAGE_EXTERNAL_STORAGE` is denied on API 30+. It also asserts
that `INTERNET` remains requested and granted. This is run against the actual package on both API 26 and
API 35, in addition to the APK audit.

`PrivateKeyImportAndroidTest.actualSettingsDispatchesSystemPickerImportsProviderAndRetainsKeyAfterRemoval`
launches the actual `SettingsActivity`, dispatches the key preference's real `PreferenceActivity`
list-row click, and observes the resulting `ACTION_OPEN_DOCUMENT` / `CATEGORY_OPENABLE` / `*/*` picker
request. A test-only synthetic `DocumentsProvider` returns an in-memory RSA identity. Import completes,
the provider document is removed, and the stored key is reopened and signs a synthetic message. The test
then rechecks PackageManager declarations/grants; no broad storage permission is present or granted.

The complete API suites additionally import plain and encrypted RSA and ECDSA documents through the
synthetic provider, remove/revoke each source, and verify subsequent key reads/signing. On-device
`SshAuthenticationAndroidTest` exercises the shipped `JschSshBackend` with the encrypted ECDSA key,
checks the selected `publickey` mode and final bounded identity publication/removal, and rejects a wrong
passphrase before any socket attempt. The **wire-level** password, RSA-SHA2, and ECDSA SSH authentication
matrix is the existing JVM-only `JschAuthenticationIntegrationTest` against an isolated loopback MINA
SSHD fixture. No live OpenSSH/device/network handshake is claimed.

## Approved disposable AVD runs

The exact final APK hashes above were installed only on the approved disposable AVDs. Before **each**
APK installation, `/tmp/opencode/xmouse-task-4.1/verified-install.py` checked the serial, exact AVD name,
API level, `boot=1`, fingerprint, and both SDK variables. Its `--clean` option was used on the app install
only; it cleared only `com.stripe1.xmouse` and `com.stripe1.xmouse.test` on that disposable AVD. The
instrumentation runner invoked the complete suite. Each device then ran `emu kill`; after API 35,
`adb -P 5038 devices -l` listed no devices. No personal device, user AVD, or SSH host was accessed.

| API / AVD and serial | Fingerprint | Instrumentation result | Duration |
| --- | --- | --- | ---: |
| 26 / `xmouse_task41_api26`, `emulator-5584` | `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` | **103 discovered; 101 passed, 2 assumption skips, 0 failures** | 109.574s |
| 35 / `xmouse_task31`, `emulator-5580` | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` | **103 discovered; 101 passed, 2 assumption skips, 0 failures** | 117.425s |

Both skips are the existing opt-in system backup-transport tests
`CredentialMetadataBackupTransportAndroidTest.verifyMetadataAndReentryAfterSystemTransportRestore`
and `stageSyntheticPackageDataForSystemBackup`; no import, authentication, or permission test was
skipped. The exact device identity records have `boot=1`, the expected API/fingerprint, and both required
SDK paths.

Reproduction command pattern (API 26 and API 35 were run sequentially, stopping API 26 before API 35):

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-4.1/avd-api26
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task41_api26 -port 5584 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task48-final-v2-api26-emulator.log 2>&1 &
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 wait-for-device
while [ "$("$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task48-final-v2-api26-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task48-final-v2-api26-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell am instrument --user 0 -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 emu kill

export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task48-final-v2-api35-emulator.log 2>&1 &
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 wait-for-device
while [ "$("$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5580 xmouse_task31 35 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task48-final-v2-api35-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5580 xmouse_task31 35 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task48-final-v2-api35-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell am instrument --user 0 -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 emu kill
```

## Evidence and limits

- Final build: `/tmp/opencode/xmouse-task48-final-v2-build.log`.
- Full API 26 / API 35 instrumentation: `/tmp/opencode/xmouse-task48-final-v2-api26-instrumentation.log` and
  `/tmp/opencode/xmouse-task48-final-v2-api35-instrumentation.log`.
- Pre-install identities and install logs: `/tmp/opencode/xmouse-task48-final-v2-api{26,35}-{app,test}-identity.json`
  and matching `-install.log`; emulator logs use `xmouse-task48-final-v2-api{26,35}-emulator.log`.
- Actual APK permission/badging/SHA-256 outputs: `/tmp/opencode/xmouse-task48-final-{app,test}-permissions.txt`,
  `/tmp/opencode/xmouse-task48-final-{app,test}-badging.txt`, and `xmouse-task48-final-apk-sha256.txt`.
- JVM XML: `app/build/test-results/testDebugUnitTest/`; lint report: `app/build/reports/lint-results-debug.xml`
  and `.txt`.

The current Android verification is API 26 and API 35 (the approved available “current Android” target
for this task); no API 37 device, physical device, manual DocumentsUI session, system backup/restore
transport, or live OpenSSH device handshake is claimed. The two backup-transport skips remain explicitly
not run. Authentication wire behavior is covered by the actual JVM JSch/MINA server matrix; Android
verification covers the installed PackageManager, provider import, source removal, protected read/sign,
and final JSch identity boundary without broad storage permission.

## Exact task-4.8 file manifest

```text
app/src/main/AndroidManifest.xml
app/src/androidTest/java/com/stripe1/xmouse/secrets/PrivateKeyImportAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/StoragePermissionsAndroidTest.kt
docs/verification/storage-permissions.md
openspec/changes/modernize-xmouse/tasks.md
```

No changes to authentication preferences/credentials, key parsing/formats, INTERNET, host trust, SSH
deadlines, transport, FileDialog, local-network permissions, services/discovery, or editor features.
