# Protected secret-store foundation (task 3.1)

Verified **2026-10-20**, baseline `404d73f`. Only **3.1** is completed (10/67).
No existing production authentication, database, preference, UI, manifest, or dependency path changed.

## Contract and handoff

- `AndroidSecretStore.open(context)` returns the blocking, Java-callable `SecretStore` boundary.
  Call off the main thread. `SecretId` accepts only `[a-z0-9][a-z0-9_-]{0,63}`: use opaque,
  non-secret references, not labels, endpoints, passwords, or paths. Secret bytes are limited to 1 MiB.
  Missing records return null; unreadable records throw a typed `SecretStoreException` with no
  provider message/cause. Callers own returned plaintext and should clear it after use; JVM memory
  wiping is best effort, not a forensic guarantee.
- AES-256 `AndroidKeyStore` key alias: `com.stripe1.xmouse.secrets.aes-gcm.v1`, encrypt/decrypt
  purposes, GCM only, no padding, randomized encryption required. Every encryption creates a fresh
  Cipher and uses its provider-generated **96-bit random nonce**, never a caller-selected/reused IV.
  Random nonces have probabilistic collision bounds; uniqueness samples are not a mathematical proof.
  The non-exportable AES key encrypts opaque bytes directly: imported SSH files are encrypted bytes,
  **not** arbitrary SSH identities imported into hardware Keystore. Hardware backing is not required
  or promised.
- V1 record: ASCII `XMSS` (4 bytes), version `1` (1), nonce (12), ciphertext + authentication tag (16).
  AES-GCM AAD is ASCII `com.stripe1.xmouse/secret-store\0`, the five-byte header, big-endian uint16
  ID length, then ASCII ID bytes. The fixed version defines the algorithm; malformed/oversized records,
  unknown versions, altered bytes, and cross-ID substitution fail closed. Plaintext is returned only
  after `doFinal` authenticates. This is not rollback/replay protection against an old valid same-ID record.
- Records live exclusively under `applicationContext.noBackupFilesDir/secrets/`, directory mode 0700,
  record/staging/lock mode 0600. The factory retains files/key provider, not a Context/Activity.
  A process monitor plus file lock serializes access across instances/processes. Reads are bounded;
  canonical containment also rejects a record symlink escaping the directory.
- Writes authenticate an existing record **before** replacement, encrypt in memory, write only
  ciphertext to a same-directory staging file, sync the file, atomically `Os.rename`, then sync the
  directory. New directory creation syncs its parent. A pre-rename failure keeps the old record;
  directory-sync failure reports `STORAGE_FAILURE` even if the complete new record was renamed.
  Callers must re-read to reconcile that uncertain commit, never assume a failed write succeeded.
  Interrupted encrypted staging artifacts are retained for recovery, not promoted or silently erased.
- Reads never generate keys. Writes permit initial key creation only when no record/recovery artifact
  exists (the empty lock file alone does not count). A missing, invalidated, or unusable key is not
  deleted/replaced; an unreadable record is not overwritten. There is deliberately no implicit reset API.
  Task **3.2** adds the [explicit, cancellable re-entry presentation contract](credential-reentry.md),
  with in-memory use for one attempt only and no reset. End-user UI/authentication wiring,
  migration/integration/legacy backup exclusions remain **3.3–3.6**; existing legacy plaintext is
  unchanged and is not claimed protected yet.

## Commands and results

All Android commands used both SDK variables; no machine-specific build configuration was added:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging app/build/outputs/apk/debug/app-debug.apk
```

**BUILD SUCCESSFUL, 1m38s, all 83 tasks executed**. XML: **145 tests / 14 suites, zero
failures/errors/skips**, including **25 new tests** (9 cipher, 16 filesystem/store). Lint:
**0 errors/fatal, 155 unchanged warnings**, none in new sources; no suppression/baseline added.
APK identity remains `com.stripe1.xmouse`, version 25 / 2.0.27, SDK 24 / 37 / 37.
Existing Jetifier, Java deprecation, native-symbol-strip, and test-JVM CDS warnings remain visible.

Core tests cover empty/Unicode/binary/maximum round trips, every record-byte mutation and truncation,
wrong key/ID, versions, 256 sampled distinct nonces, bounded reads, key-loss preservation, encrypted-only
staging, partial-write/fsync/rename/directory-sync faults, interrupted staging, and concurrent instances.
They use **real JVM JCA AES-GCM with synthetic in-memory keys**, host files, and an injected file-I/O
adapter (directory fsync/permissions are simulated there), not AndroidKeyStore or hardware evidence.
The same **25 cases also pass independently** with only compiled core/test classes, Kotlin stdlib
2.4.21, JUnit 4.13.2 and Hamcrest 1.3 on the JVM classpath, **without Android/Mockito/JSch**;
the exact expanded Java command is in `pure-jvm.log` below.

### Actual Android verification

KVM was available. Created **only** a disposable AVD beneath `/tmp/opencode/xmouse-task-3.1/avd`
from the already-installed `system-images;android-35;google_apis;x86_64` revision 9:

```sh
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd
# Parent directory was created first; answered "no" to custom hardware creation.
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --name xmouse_task31 \
  --package 'system-images;android-35;google_apis;x86_64' \
  --path "$ANDROID_AVD_HOME/xmouse_task31.avd"
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048
# After sys.boot_completed=1, from a separate shell with both SDK exports:
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument -w \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu kill
```

**OK (8 tests), 2.435s**: existing application smoke + **7 new Android tests**. Real AndroidKeyStore
round trips across reopened stores, AES key non-exportability/256-bit/GCM attributes, 128 distinct nonce
samples, ciphertext/nonce/tag tampering, record swaps/malformed/unknown versions, test-key deletion with
no regeneration, real private/no-backup paths and POSIX permissions, symlink containment, encrypted
staging inspection, and injected pre-rename failure preserving the previous credential all passed.
Tests use the production factory/crypto/POSIX implementation with UUID test aliases and isolated
subdirectories under the target application's actual `noBackupFilesDir`; cleanup deletes only those.
Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
No physical/personal device or existing AVD state was touched. The disposable emulator was shut down.

An initial instrumentation run exposed existing Android-test runtime alignment to JUnit 4.12 despite
4.13.2 compile APIs. The final tests use equivalent explicit typed exception assertions compatible
with that runtime, without changing production dependency resolution. Early SDK-symbol and JVM-fixture
lint findings were fixed, not suppressed. **Not run/claimed:** API 24/37 device or hardware-backed
Keystore, hardware/biometric key invalidation, power-cut durability, full backup extraction, UI re-entry,
credential migration, production SSH integration. These remain later-task/release coverage.

Evidence: `/tmp/opencode/xmouse-task-3.1/{full-verification-rerun.log,instrumentation-final.log,
pure-jvm.log,app-badging.txt,androidtest-runtime-dependencies.log,emulator.log}`;
unit/lint XML under `app/build/`. `git diff --check` passes. No staging or commit performed.
