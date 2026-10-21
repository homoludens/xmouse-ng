# Explicit SSH host-key replacement (task 4.4)

## Contract and implementation

- A normal JSch host-key check still verifies the captured logical endpoint and full public key before
  any SSH user-authentication request. A changed key throws `MISMATCH`; it does not create or display a
  first-trust approval request, and the blocked attempt is torn down without authentication, a command
  channel, or the initial command.
- A mismatch carries only a bounded public observation: the immutable `Endpoint`, previously trusted
  full key, newly presented full key, and an attempt-current gate. It expires after 60 seconds. It is not
  an authentication or acceptance token. A changed connection generation, Activity stop/destroy, dialog
  cancellation, stale callback, or expiry invalidates the candidate, including after the worker claims it.
- The mismatch UI first offers **Review replacement** or **Cancel**. Review displays the intended logical
  hostname/service and port, old/new algorithms and SHA-256 fingerprints, and a warning to verify the
  new key out of band. A second confirmation requires **Replace trusted key** or **Cancel**. Replacing
  trust alone never resumes or authenticates the blocked connection; the user must explicitly connect
  again.
- Replacement is enqueued on the existing serialized SSH worker. The request owner requires explicit
  confirmation and validates the original owner, attempt and deadline. Cancellation stays observable
  after the worker claims publication; repository preflights after staged-snapshot fsync and immediately
  before atomic replacement preserve the old full key and unrelated records if cancellation wins there.
  Under the existing process and OS file locks, replacement compares the expected old **full key** (CAS),
  changes only that identity, and preserves all other endpoint records. It uses the existing private staged
  snapshot, fsync, fail-closed publication marker, atomic rename and fresh read-back protocol. Once atomic
  replacement is attempted, a racing cancellation may suppress the success result but cannot honestly
  roll back a rename that may have committed: only that same explicitly confirmed CAS replacement may
  remain durable. Uncertain I/O remains fail-closed. Cancellation never resumes or authorizes the blocked
  handshake; authentication requires a later fresh connection. There is no forget/reset or "trust
  anything" API.
- The operation does not touch credentials, host settings, saved authentication choices, permissions,
  or service-discovery state. Stable advertised hostnames and service-instance identities use the
  existing `Endpoint.identity` rules.

## Verification

The final uncached verification and exact device runs are recorded below. Only disposable approved AVDs
were used; the SSH integration fixture uses synthetic loopback Apache MINA SSHD keys and credentials,
never a system sshd, real user account, or user SSH host.

### JVM, lint, APKs, fixtures, and audits

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 docs/verification/verify-v2-storage-fixtures.py
python3 docs/verification/audit-ssh-transport.py
openspec validate modernize-xmouse --strict
git diff --check
```

The focused trust regressions were also run first:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g :app:testDebugUnitTest \
  --tests com.stripe1.xmouse.trust.HostTrustRequestsTest \
  --tests com.stripe1.xmouse.trust.HostKeyRepositoryTest \
  --tests com.stripe1.xmouse.trust.JschHostTrustIntegrationTest
```

The final uncached build was `BUILD SUCCESSFUL` in **2m59s**, all **83/83** tasks executed. JVM:
**257 tests / 26 suites**, zero failures, errors, or skips. Lint: **0 errors, 138 warnings**. Fixture
audit: **4/4**; the SSH transport/trust source audit passed. Strict OpenSpec validation and whitespace
checks were rerun after updating the task/report. Logs:
`/tmp/opencode/xmouse-task44-review-focused-tests.log` and
`/tmp/opencode/xmouse-task44-review-final-build.log`.

### Android instrumentation

Final full-suite runs use both APK hashes below; both approved API runs completed after the cancellation
fix.

The final APKs were assembled by the successful 83/83 uncached build above:

- App SHA-256: `f2ada6efd1243edf85d2cb7f94f9acacf2d911041bae5a7446f862b0ba1f26a7`
- Instrumentation SHA-256: `cf2acb98987252f479f63aa7aeba6b0ca62f97a82dcd4c81ab0c02269fa7232b`
- API 26 `xmouse_task41_api26` / `emulator-5584`, fingerprint
  `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`:
  **99 discovered, 97 passed, 2 assumption skips, 0 failures** (111.672s).
- API 35 `xmouse_task31` / `emulator-5580`, fingerprint
  `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`:
  **99 discovered, 97 passed, 2 assumption skips, 0 failures** (116.772s).

Each install used `/tmp/opencode/xmouse-task-4.1/verified-install.py`; it checked the exact approved
serial, AVD, boot state, API, fingerprint, and both SDK variables immediately before installing each APK.
The `--clean` invocation cleared only the approved AVD's `com.stripe1.xmouse` and test-package data.

API 26 reproduction (the same SDK/ADB exports apply to both installs and instrumentation):

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-4.1/avd-api26
export PATH="$ANDROID_HOME/platform-tools:$PATH"
"$ANDROID_HOME/emulator/emulator" -list-avds
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task41_api26 -port 5584 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task44-review-api26-emulator.log 2>&1 &
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 wait-for-device
while [ "$("$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell \
  getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task44-review-api26-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task44-review-api26-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell am instrument -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 emu kill
```

API 35 reproduction (using the same SDK/ADB/PATH exports, with the approved API 35 AVD home):

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd
"$ANDROID_HOME/emulator/emulator" -list-avds
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task44-review-api35-emulator.log 2>&1 &
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 wait-for-device
while [ "$("$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell \
  getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5580 xmouse_task31 35 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task44-review-api35-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5580 xmouse_task31 35 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task44-review-api35-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell am instrument -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 emu kill
```

The two API 26 assumption skips are the pre-existing opt-in backup-transport stage/verify tests, status
`-4`; no host-trust, credential, key-import, or transport test was skipped. Final log and pre-install
identity files: `/tmp/opencode/xmouse-task44-review-api26-instrumentation.log` and
`/tmp/opencode/xmouse-task44-review-api26-{app,test}-identity.json`.

The same two pre-existing backup-transport assumption skips (`-4`) occurred on API 35; no host-trust,
credential, key-import, or transport test was skipped. Final log and pre-install identity files:
`/tmp/opencode/xmouse-task44-review-api35-instrumentation.log` and
`/tmp/opencode/xmouse-task44-review-api35-{app,test}-identity.json`. After both runs, ADB server port
5038 listed no devices and neither approved emulator console port (5580 or 5584) was listening.

### Coverage and limits

- Repository/request tests cover exact-key CAS, preservation of unrelated records, stale/second
  confirmation rejection, immediate cancellation while staging fsync is blocked, no success result when
  cancellation wins, and the post-rename race where only the exact confirmed replacement may remain.
- Actual production `SshTransport`, JSch policy and repository are exercised against an isolated MINA
  SSHD server. The server records every user-authentication request (including `none`), credential
  authenticator calls, channels and commands. Cases cover same-identity reconnect with a changed key,
  a different dial address, stable-hostname discovered selection, a changed logical service identity,
  deliberate replacement followed by a fresh successful connect, and the old key blocking afterward.
  A staging-blocked cancellation through the production transport returns failure, preserves the old key
  and unrelated records, and leaves the changed server with no authentication, channel, or command.
- Android tests exercise the real Activity-local mismatch dialogs, old/new display and warning, both
  Cancel paths, explicit confirmation, durable reopen, unchanged auth-choice preference, and an actual
  Activity stop while replacement staging fsync is blocked; stop returns while the worker is blocked and
  the old/unrelated records remain. They make no live-network claim; ordering/no-auth proof comes from the
  actual JSch loopback fixture.
- This is not DNS/mDNS/NSD implementation or a LAN/device-discovery acceptance test. It does not complete
  the task 4.5 authentication matrix, task 4.6 comprehensive transport deadlines, task 5.x session
  ownership, API 37 device coverage, real-LAN DHCP, or opt-in backup-transport verification.

## Exact task-4.4 manifest

```text
app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java
app/src/main/java/com/stripe1/xmouse/transport/JschSshBackend.java
app/src/main/java/com/stripe1/xmouse/transport/SshBackend.java
app/src/main/java/com/stripe1/xmouse/transport/SshTransport.java
app/src/main/java/com/stripe1/xmouse/transport/TransportPresentation.java
app/src/main/java/com/stripe1/xmouse/trust/HostKeyMismatch.java
app/src/main/java/com/stripe1/xmouse/trust/HostKeyRepository.kt
app/src/main/java/com/stripe1/xmouse/trust/HostTrustException.kt
app/src/main/java/com/stripe1/xmouse/trust/HostTrustPolicy.kt
app/src/main/java/com/stripe1/xmouse/trust/HostTrustReplacementUi.kt
app/src/main/java/com/stripe1/xmouse/trust/HostTrustRequests.kt
app/src/test/java/com/stripe1/xmouse/trust/HostKeyRepositoryTest.kt
app/src/test/java/com/stripe1/xmouse/trust/HostTrustRequestsTest.kt
app/src/test/java/com/stripe1/xmouse/trust/JschHostTrustIntegrationTest.kt
app/src/androidTest/java/com/stripe1/xmouse/HostTrustAndroidTest.kt
docs/verification/audit-ssh-transport.py
docs/verification/ssh-trust-replacement.md
openspec/changes/modernize-xmouse/tasks.md
```
