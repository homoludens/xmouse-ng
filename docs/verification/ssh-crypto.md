# SSH crypto dependency migration and bounded key handling (task 4.1 — verified)

Verified **2026-10-21** after the user-approved promotion of 4.1 to **[high]** and bounded-parser
integration. **Only 4.1 is newly complete: 18/67 tasks complete**, pending parent review/commit.
The user-approved **API 26 minimum remains unchanged**. The earlier API 24 ECDSA failure remains a real
historical incompatibility, not a fixed or supported platform.

The historical JSch 2.28.8 allocation failure is addressed by a shared maintained-parser boundary, not by
raising minSdk, catching `OutOfMemoryError`, increasing the heap, or rerunning randomized inputs until
green. JSch now receives canonical unencrypted memory-only output from BC, never unvalidated key input.
Final JVM verification passed **210/210**. Each final API 26/API 35 full instrumentation run discovered
**88 tests: 86 passed, 2 opt-in backup-transport tests assumption-skipped, 0 failures**. Historical runs
and the exact missing-randomized-fixture limitation are retained below.

No host-trust/session-controller/server-authentication work from 4.2/4.3/4.5 was added. Nothing is staged
or committed.

## Bounded parser boundary and preservation

`BoundedSshKey` is used by document/legacy imports, `PrivateKeyRepository.readForAuthentication`, the
repository's saved-key and attempt-only authentication continuations, and the final `MyConnectionHandler`
identity handoff. A source audit finds JSch `KeyPair.load`/`addIdentity` calls only inside that boundary.
It uses BC 1.86's PEM decryption, ASN.1/key and OpenSSH utilities, preceded by structural/resource checks;
it does not implement a cryptographic algorithm or install a global provider. A successful import is
signed and verified with a fixed synthetic message (RSA SHA-2 for RSA).

Original source bytes remain encrypted at rest under the existing fresh-reference/read-back/atomic
publication protocol. Normalization is temporary and in memory; it does not migrate or rewrite existing
records. Every later use rechecks the key with the supplied raw passphrase bytes. Failed import/read/
attempt-only input preserves prior ciphertext, references, recovery context and auth choices. A missing
device key is still never regenerated implicitly. Owned temporary byte arrays are wiped best-effort;
immutable BC/BigInteger objects, PEM reader strings and VM/provider copies cannot be guaranteed wiped.

Explicit limits and supported forms:

| Boundary | Limit / behavior |
| --- | --- |
| Document / decoded input | 1 MiB total source; 16 KiB decoded key structure; 1024 passphrase bytes |
| DER | Definite lengths within remaining input, max 16 constructed levels / 128 nodes / 2049-byte INTEGER; no recursive preflight or input-length allocation |
| Key parameters | RSA up to 16384 bits; named NIST P-256/P-384/P-521 EC; maintained-parser EdDSA forms where supported |
| Traditional PEM | RSA PKCS#1 / EC SEC1, plain or DES-EDE3/AES-128/192/256-CBC encrypted; passphrase bytes map unchanged to BC's 8-bit traditional PEM KDF |
| OpenSSH | One key; bounded outer strings; BC checks private-string lengths against remaining decrypted bytes before copying; bcrypt 1..64 rounds and 16..64-byte salt |
| PKCS#8 PBES2 | AES-128/192/256-CBC; PBKDF2 1..600000 rounds with SHA-1/224/256/384/512 PRF, or scrypt with primary memory <=32 MiB, `N*r*p <= 2^20`, `r <= 8`, `p <= 16`; 8..64-byte salt |
| Other inputs | Unsupported containers/ciphers/KDFs return sanitized `UNSUPPORTED_FORMAT`; excessive work/size returns `RESOURCE_LIMIT` or `TOO_LARGE`; malformed/decryption failures never fall through to JSch |

The source-file bound alone was insufficient; decrypted structures and KDF parameters are now checked
too. The original supported four external RSA/ECDSA plain/encrypted PEM fixtures still import and sign
after provider/source removal. PKCS#8 (plain/PBKDF2/scrypt), encrypted OpenSSH RSA/EC, NIST P-384/P-521,
raw non-ASCII/NUL passphrases, and OpenSSH Ed25519 have positive JVM coverage. This is bounded format
support, not a claim that every format accepted by raw JSch (for example PuTTY/F-Secure/DSA) is accepted.

### Deterministic regressions (no deliberate memory exhaustion)

`BoundedKeyFixtures.malformedDer()` is a tiny malformed DER structure with an integer length exceeding
remaining input. The test reads the length without allocating it. Its encrypted counterpart is generated
deterministically with maintained BC APIs and valid padding; a separate assertion proves that successful
decryption yields that malformed DER. These fixtures test the old unchecked-length mechanism without
invoking the unsafe old parser. They are not the original randomized failing PEM and are not claimed to
be a valid key that decrypts under two passphrases.

Fixed external encrypted keys exercise real missing/wrong passphrases. Unit tests also cover truncated,
overflowing, deeply nested, high-node-count and oversized inputs; excessive PBKDF2/bcrypt/scrypt work;
2000 seeded bounded structural cases; and a mock proving rejected input never reaches JSch identity
loading. Android regressions exercise provider imports, saved encrypted reads, the final identity handoff,
and malformed attempt-only re-entry after real device-key loss, preserving prior records and choices.

## Final API 26 / API 35 verification (2026-10-21)

Both APKs have minSdk **26**, targetSdk **37**, compileSdk **37**. SHA-256:

- App: `6009ea678e9ed0578edaab2ed5c27084e152cbdd67a936c46ed435767dadc196`
- Instrumentation: `ddec8ed82da692b88afcb22294b68f0dedffe72631a5447cfe315d2b72ddaaab`

All logs below are under `/tmp/opencode/xmouse-task-4.1/`. Before **each** installation,
`verified-install.py` checked serial, exact approved AVD name, API, boot-completed state, fingerprint and
both SDK variables, and saved `hardening-final-api{26,35}-{app,test}-identity.json`. Only these disposable
AVDs' app/test package data was cleared; the real target application database was not used for isolated
credential fixtures. Install logs have the corresponding `-install.log` suffix.

| AVD / serial | Fingerprint | Full result |
| --- | --- | --- |
| `xmouse_task41_api26` / `emulator-5584` | `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` | 88 discovered, 86 passed, 2 opt-in skips, 0 failures; 54.167s |
| `xmouse_task31` / `emulator-5580` | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` | 88 discovered, 86 passed, 2 opt-in skips, 0 failures; 55.475s |

Exact output: `hardening-final-api26-instrumentation.log`, `hardening-final-api35-instrumentation.log`.
Consolidated hashes, per-install identities, JVM/lint counts and Android status codes are in
`hardening-summary.json`.
The skips are the existing opt-in system-backup transport stage/verify methods, not new exclusions. All
existing migration/checkpoint/repository/backup/import/privacy regressions ran. Both AVDs were stopped;
final `adb -P 5038 devices -l` showed no devices.

Device command pattern (both SDK variables were set for every Android tooling invocation):

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
# Verify exact AVD identity and clean only the disposable packages before installing.
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task-4.1/hardening-final-api26-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task-4.1/hardening-final-api26-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell am instrument -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 emu kill
# Repeat for approved API 35: emulator-5580 / xmouse_task31 / API 35.
```

## Pins and provider decision

- Selected `com.github.mwiede:jsch:2.28.8`. The [maintainer's 2.28.8 GitHub release](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.8)
  is dated 2026-10-10, and [Maven Central metadata](https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/maven-metadata.xml)
  reports 2.28.8 as both latest and release. The [upstream README](https://github.com/mwiede/jsch#how-to-use-this-library-as-a-replacement-for-comjcraftjsch)
  documents this artifact as a drop-in replacement retaining the `com.jcraft.jsch` package and
  instructs consumers to keep only one JSch implementation. Its default public-key algorithms
  include RSA-SHA2 and ECDSA and exclude SHA-1 `ssh-rsa`.
- Selected `org.bouncycastle:bcprov-jdk18on:1.86`, a stable [Bouncy Castle 1.86 release](https://www.bouncycastle.org/download/bouncy-castle-java/)
  dated 2026-09-11. Its published metadata identifies it as the Java 8+ lightweight crypto/JCA
  artifact. Upstream JSch documents BC as the fallback for Ed25519/Curve25519 on older Java
  runtimes; its base implementation calls BC lightweight EdDSA classes. The JSch POM itself has no
  dependency requirements. The hardening uses **`bcpkix-jdk18on:1.86`** for its maintained PEM decryptor
  and its pinned **`bcutil-jdk18on:1.86`** transitive ASN.1 models. No PG artifact was added. All are
  Java-8-compatible artifacts; actual API 26 execution verifies the used paths.
- The BC provider is **not registered globally**. The old SpongyCastle dependencies and
  `MainActivity`'s priority-1 provider insertion were removed. API 24 confirms that BC on the classpath
  does not make JSch's default ECDSA JCA lookup use BC; an explicit, unregistered BC provider instance
  supplies the missing parameters service in a diagnostic probe, but JSch has no per-JCA provider
  selector for its ECDSA adapter. API 26's platform service lets the unchanged JSch adapter work, so no
  custom adapter, fork, provider registration, or production workaround was needed.

## Dependency graph and build

Every Android tooling command used both environment variables:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --no-daemon --console=plain --max-workers=2 \
  :app:dependencies --configuration debugRuntimeClasspath
./gradlew --no-daemon --console=plain --max-workers=2 \
  :app:dependencies --configuration debugUnitTestRuntimeClasspath
./gradlew --no-daemon --console=plain --max-workers=2 \
  :app:dependencies --configuration debugAndroidTestRuntimeClasspath
```

Results:

- JVM suite: **210 tests / 21 suites**, zero failures/errors/skips.
- Lint: **0 errors/fatal, 144 warnings**. One added warning is an unused bundled BC PKIX TLS trust-manager
  helper; no application code calls that helper (the integration uses the PEM decryptor). No suppression
  or baseline was added. Existing warning
  sources include the AGP `android.enableJetifier=true` deprecation, deprecated Java/instrumentation
  APIs (including legacy preference APIs), and the test JVM bootstrap-classpath/CDS message; no
  suppression or baseline was introduced.
- Review build after fixture-isolation changes: **BUILD SUCCESSFUL** for JVM tests, lint, app APK, and
  instrumentation APK (83 actionable tasks rerun with `--no-build-cache --rerun-tasks`). Log:
  `/tmp/opencode/xmouse-task-4.1/hardening-final-build-v2.log` (2m15s). Lint report:
  `app/build/reports/lint-results-debug.txt`.
- Debug and instrumentation APKs assembled. `checkDebugDuplicateClasses` and
  `checkDebugAndroidTestDuplicateClasses` passed.
- Final APK badging confirms both the app and instrumentation APK have **minSdk 26 / targetSdk 37**;
  the app APK also reports compile SDK 37. The review API 35 and API 26 suites below ran these APKs.
  Badging evidence: `/tmp/opencode/xmouse-task-4.1/hardening-app-badging.txt` and
  `hardening-test-badging.txt`; hashes are listed above.
- `debugRuntimeClasspath` and `debugUnitTestRuntimeClasspath` each resolve exactly one JSch
  coordinate, `com.github.mwiede:jsch:2.28.8`, plus the single
  `org.bouncycastle:bcprov-jdk18on:1.86` artifact plus PKIX/util 1.86. Neither graph contains `com.jcraft:jsch` or
  SpongyCastle. The Android Gradle plugin's separate `debugAndroidTestRuntimeClasspath` contains
  instrumentation dependencies only; the tested application runtime is supplied by the target app
  APK and does not add a competing JSch implementation.
- Final graph outputs are `/tmp/opencode/xmouse-task-4.1/hardening-dependencies-{debugRuntimeClasspath,
  debugUnitTestRuntimeClasspath,debugAndroidTestRuntimeClasspath}.log`; they contain one app JSch
  coordinate (`com.github.mwiede:jsch:2.28.8`) and no old JSch or SpongyCastle.

## Historical device evidence (before bounded-parser integration)

The completion/blocking descriptions in this section describe those earlier checkpoints, not the final
status above. Historical `OK (84 tests)` output included the two opt-in backup-transport assumption skips.

### API 35 previous regression suite — passed (historical APK)

Only the approved disposable `xmouse_task31` AVD was used. Before each final APK installation, serial,
AVD name, boot state, Android API, fingerprint, and both SDK variables were checked. The ADB client used
port 5038 to avoid the host's older ADB server.

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 install --no-streaming -r \
  app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 install --no-streaming -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell am instrument --user 0 -w \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 emu kill
```

Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`
(API 35), serial `emulator-5580`, AVD `xmouse_task31`. After clearing only the disposable AVD's app and
test-package data, the complete updated instrumentation suite passed **84 tests** in **62.2s**, zero
failures. This includes the final JSch diagnostic test, all four external PEM import fixtures, key
source-removal/invalid/cancel/preservation tests, migration/repository tests, backup tests, and logging
privacy tests. Exact output is `final-api35-instrumentation.log`; the full device/APK identity record is
`device-evidence-api35-final.txt` (individual pre-install fingerprints are also retained in
`api35-device-before-final-app-install.txt` and `api35-device-before-final-test-install.txt`). Selected
synthetic-only crypto diagnostics are in `final-api35-diagnostics-logcat.log`. The emulator was stopped
after the run.

### API 26 initial gate before floor change — passed

The approved new disposable Google APIs x86_64 AVD was created only under
`/tmp/opencode/xmouse-task-4.1/avd-api26` (`xmouse_task41_api26`, serial `emulator-5584`). Its fingerprint
was `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` (API 26).
Before installing either APK, serial, AVD name, boot state, API, fingerprint, and both SDK environment
variables were freshly checked. At this gate the app APK was still **minSdk 24 / targetSdk 37**; its
SHA-256 was `8cdc8b9cbb40c9a6b50a4cdca3b6212c526bb27a472a4c5f2df2db9717ea66a0` (instrumentation APK
`d68030bd16585487782ed0309c7ffad3a0ee41d530531879e0aeb30281cf4ec0`).

- `JSchCryptoProviderAndroidTest`: **1 test passed**. The actual JSch implementation initialized and
  RSA-SHA2-256, RSA-SHA2-512, ECDSA P-256, and Ed25519 synthetic sign/verify checks passed. Provider
  ordering and JSch config remained unchanged.
- `PrivateKeyImportAndroidTest`: **17 tests passed**, including separate external plain/encrypted RSA
  traditional PEM and ECDSA P-256 SEC1 PEM fixtures, source removal followed by key use, invalid/wrong
  passphrase, cancellation, and preservation of the previous usable key. This established that the
  unchanged min-24 APK meets the conditional API 26 crypto/import gate without global provider changes.
- Logs: `/tmp/opencode/xmouse-task-4.1/api26-gate-jsch-crypto.log`,
  `api26-gate-private-key-import.log`, `api26-gate-diagnostics-logcat.log`, and the two
  `api26-device-before-*-install.txt` identity records. The consolidated device/APK identity and hash
  record is `device-evidence-api26-gate.txt`.

### API 26 prior final APK run — historical; superseded by review rerun

After raising the configured floor, the **final** app and instrumentation APKs (both minSdk 26 / target
37) were installed on the same disposable API 26 AVD after checking its identity before each install.
The crypto and complete private-key import classes were rerun after clearing only that AVD's app/test
data: **18 tests passed**, zero failures. Log:
`/tmp/opencode/xmouse-task-4.1/final-api26-focused-instrumentation.log`; install and identity records are
`final-api26-install-app.log`, `final-api26-install-test.log`, `final-api26-device-before-app-install.txt`,
and `final-api26-device-before-test-install.txt`. The consolidated device/APK identity, hashes, focused
counts, and full-suite result are in `device-evidence-api26-final.txt`.

The previous full API 26 run had **84 tests and 38 failures** (`final-api26-instrumentation.log`). Review
traced the credential migration/repository/backup failures to test-only `ContextWrapper` fixtures that
overrode `getDatabasePath()` but not `openOrCreateDatabase()`. API 26 `SQLiteOpenHelper` used the latter;
the wrapper delegated to the base application Context and opened the shared app database while tests had
seeded a separate fixture file. The existing database-migration fixture already overrode both open
overloads. The review follow-up below applies that pattern and checks the actual SQLite path. The old
38-failure run is now historical fixture evidence, not a production regression and not a passing suite.

The earlier focused crypto/import result (18 passed) used the then-current instrumentation APK and is
retained as historical evidence only. The review rerun below uses the updated APK and is authoritative
for current API 26 suite status.

### API 26 review reruns — fixture isolation fixed; intermittent importer OOM observed

The approved disposable `xmouse_task41_api26` AVD (`emulator-5584`, API 26, fingerprint
`Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`) was checked
before installs. Only its `com.stripe1.xmouse` and `com.stripe1.xmouse.test` data were cleared. Both review
APKs report minSdk 26 / targetSdk 37; their hashes are recorded above.

- The first clean full review run on instrumentation APK hash
  `29ddd076c9e0bc258e7c62eee90c0f11d96e0f4c5bd0c15a38c95497c29784df` reached **84 tests; 1 failure** in
  `review-api26-instrumentation.log`. The failure was
  `PrivateKeyImportAndroidTest.invalidPassphraseLeavesExistingKeyAndGlobalPassphraseChoiceUntouched`.
  `JSch KeyPairRSA.parse` raised `OutOfMemoryError` requesting 1,374,342,352 bytes while
  `PrivateKeyRepository.validate()` was executing `key.decrypt()`; the stack is recorded at lines 1040–1049 of
  that log. The test method uses generated synthetic encrypted RSA input, but the exact bytes from the
  failing randomized attempt were not retained. A focused rerun passed (`review-api26-isolated-oom.log`);
  do not treat it as a deterministic reproducer.
- Parent inspection of upstream JSch 2.28.8 `KeyPairRSA.java` explains the mechanism: in its
  `parseASN1DER` branch, the decoded length comes from decrypted plaintext and `new byte[length]` is
  allocated before checking that the length fits the remaining plaintext via `System.arraycopy`. The
  1 MiB source-file bound therefore does not bound this derived allocation. Wrong-passphrase decryption
  can produce malformed plaintext that reaches this length handling.
- A fresh no-cache rebuild produced the current instrumentation APK hash listed above. A second clean full
  API 26 run against that APK passed **84 tests, zero failures in 65.6s**; two opt-in backup-transport
  tests were assumption-skipped. Output: `review-final-api26-instrumentation.log`. The prior credential
  migration/repository/backup and picker failures did not reproduce.
- At that checkpoint, the later pass did not establish safety or reproducibility, show whether API 35 was
  also affected, or mean that raising minSdk to API 28 would fix the upstream unchecked length. Task 4.1
  remained unchecked until the subsequent approved hardening and verification recorded above.
- Test-only `IsolatedDatabaseContext` now overrides both `openOrCreateDatabase` overloads, keeps
  `getDatabasePath()` fixture-scoped, and asserts `SQLiteDatabase.path` against the fixture path. It is
  used by credential migration, repository, backup/restore, credential-access, and database-migration
  fixtures. Restore destinations use the same checked wrapper. No production database code or regression
  assertions were changed.
- The API 26 emulator was stopped after the rerun; no security-algorithm scope was added. Consolidated
  device/APK identity and both-run evidence: `/tmp/opencode/xmouse-task-4.1/review-api26-device-evidence.txt`.

### Historical decision point — hardening subsequently approved on 2026-10-21

The parent initially paused production changes and requested approval for a maintained bounded-parser
integration, deterministic regressions, old-key preservation and API 26/API 35 verification. On
2026-10-21 the user approved promotion of 4.1 to **[high]** with
`openai/gpt-6.1-sol#xhigh`. The parent updated the task criteria and classification amendment (14 high /
53 low, approximately 21% high). That approved scope is now implemented and verified above; no heap-size,
minimum-version, global-provider or `OutOfMemoryError`-catch workaround was used.

### API 35 review rerun — passed

The approved disposable `xmouse_task31` AVD (`emulator-5580`, API 35, fingerprint
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`) was checked before
installs. Only its app and test-package data were cleared. The full suite using the review APKs passed
**84 tests in 60.22s, zero failures** using the latest rebuilt APKs. Logs:
`/tmp/opencode/xmouse-task-4.1/review-final-api35-instrumentation.log`,
`review-final-api35-install-app.log`, and `review-final-api35-install-test.log`. The API 35 emulator was
stopped after the run. Consolidated device/APK evidence:
`/tmp/opencode/xmouse-task-4.1/review-api35-device-evidence.txt`.

### API 24 diagnostics — historical ECDSA failure; not an active support target

A new disposable Google APIs x86_64 AVD was created only under
`/tmp/opencode/xmouse-task-4.1/avd` (`xmouse_task41`, serial `emulator-5582`). Its fingerprint was
`Android/sdk_google_phone_x86_64/generic_x86_64:7.0/NYC/6696031:userdebug/dev-keys` (API 24). Before
both APK installations, serial, AVD name, boot state, API, fingerprint, and both SDK environment
variables were freshly checked. No personal/physical device or other AVD was used.

Focused commands:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5582 shell am instrument --user 0 -w \
  -e class com.stripe1.xmouse.JSchCryptoProviderAndroidTest \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5582 shell am instrument --user 0 -w \
  -e class 'com.stripe1.xmouse.secrets.PrivateKeyImportAndroidTest#importsExternalRsaTraditionalPemPlainFixture' \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5582 shell am instrument --user 0 -w \
  -e class 'com.stripe1.xmouse.secrets.PrivateKeyImportAndroidTest#importsExternalRsaTraditionalPemEncryptedFixture' \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5582 shell am instrument --user 0 -w \
  -e class 'com.stripe1.xmouse.secrets.PrivateKeyImportAndroidTest#importsExternalEcdsaSec1PemPlainFixture' \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5582 shell am instrument --user 0 -w \
  -e class 'com.stripe1.xmouse.secrets.PrivateKeyImportAndroidTest#importsExternalEcdsaSec1PemEncryptedFixture' \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5582 emu kill
```

Results:

- `JSchCryptoProviderAndroidTest`: **1 test failed** at `KeyPair.ECDSA.getSignature()`, which returned
  `null`. JSch's captured exception is `java.security.NoSuchAlgorithmException: EC AlgorithmParameters
  not available`, thrown by default `AlgorithmParameters.getInstance("EC")` in
  `com.jcraft.jsch.jce.SignatureECDSAN.setPrvKey`. RSA-SHA2-256/512 signing and modern host-key/password
  configuration assertions passed before this point. Ed25519 and final provider/config assertions were
  not reached; provider/config equality was checked immediately before the ECDSA operation.
- The stage probe found **no registered `AlgorithmParameters.EC` service** on API 24. Default
  `Signature`, `KeyFactory`, and `KeyPairGenerator` lookups selected AndroidOpenSSL and worked. Selecting
  `AlgorithmParameters.EC` explicitly from an unregistered BC provider instance then allowed the
  AndroidOpenSSL key factory and signature services to sign and verify a synthetic message. The explicit
  BC provider instance also passed its full synthetic ECDSA sign/verify probe. No provider registration
  or replacement occurred, and JSch config was restored after the diagnostics.
- Import fixtures independently exercised task 4.7's RSA traditional PEM and ECDSA P-256 SEC1 PEM forms,
  plain and encrypted. RSA plain and encrypted importer tests passed, including source removal and later
  key usability. Both ECDSA tests failed at the same JSch signature stage and the importer reported
  `PrivateKeyImportException: INVALID_KEY`; this isolates the issue from key parsing/decryption and the
  prior combined fixture loop's ambiguous ordering.
- The missing lookup is clear, but JSch's ECDSA adapter exposes no per-JCA provider selector. Supplying
  BC just for `AlgorithmParameters` would need a custom adapter/fork or global provider registration to
  affect JSch. Both options are outside the authorized bounded fix; global registration/replacement is
  excluded by the provider-safety decision. No production workaround was applied.
- The emulator was stopped after diagnostics; final `adb devices -l` showed no devices. Logs are under
  `/tmp/opencode/xmouse-task-4.1/`: `diagnostic-api24-jsch-crypto.log`, `diagnostic-api24-logcat.log`,
  and `diagnostic-api24-import-{rsa-plain,rsa-encrypted,ecdsa-plain,ecdsa-encrypted}.log`.

## Scope and completion

**Task 4.1 is complete; 18/67 tasks complete.** The maintained-parser boundary and deterministic
plain/decrypted-length tests address the recorded mechanism; success is not inferred merely from the
later randomized green reruns. The original exact failing random input is unavailable, so no replay of
that exact PEM is claimed. Existing database-fixture isolation fixes remain intact. All other task
checkboxes are unchanged.

Not claimed: arbitrary private-key formats or unlimited resource use, guaranteed zeroization of
immutable/provider copies, API 37 device coverage, hardware-backed Keystore, physical power-cut
durability, manual DocumentsUI navigation, or the opt-in real backup-transport stage/restore cycle in
these default suites. The live SSH server/password/RSA-SHA2 authentication matrix, host trust and session
ownership remain tasks 4.2/4.3/4.5. API 24 remains unsupported; its missing EC parameters service was
addressed only by the separately approved API 26 floor.

Verification iterations are retained: `hardening-build.log` exposed a canonical EdDSA PKCS#8 public-field
compatibility issue, corrected by emitting minimal RFC 5208; `hardening-final-build.log` exposed duplicate
BC license resources, resolved by merging rather than excluding licenses; `hardening-final-verification-build.log`
caught a fixture encryptor's UTF-8 re-encoding, corrected to raw-byte maintained BC derivation and checked
with an explicit decrypted-content assertion. Final logs are the `hardening-final-...` runs identified
above, not those failed iterations. Legacy fixture verification is **4/4**;
`openspec validate modernize-xmouse --strict` and `git diff --check` pass.

## Task-4.1 file manifest

Includes the dependency/floor/fixture-isolation work retained from the earlier review. Pre-existing
untracked tooling and unrelated OpenSpec archives/spec roots were not edited or staged.

```text
README.md
app/build.gradle
app/src/main/java/com/stripe1/xmouse/MainActivity.java
app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java
app/src/main/java/com/stripe1/xmouse/secrets/BoundedSshKey.kt
app/src/main/java/com/stripe1/xmouse/secrets/KeyInputBounds.kt
app/src/main/java/com/stripe1/xmouse/secrets/PrivateKeyRepository.kt
app/src/main/java/com/stripe1/xmouse/secrets/CredentialRepository.kt
app/src/main/java/com/stripe1/xmouse/secrets/CredentialUi.kt
app/src/main/java/com/stripe1/xmouse/secrets/PrivateKeySettingsController.kt
app/src/test/java/com/stripe1/xmouse/secrets/BoundedSshKeyTest.kt
app/src/test/java/com/stripe1/xmouse/secrets/PrivateKeyRepositoryTest.kt
app/src/testFixtures/java/com/stripe1/xmouse/secrets/BoundedKeyFixtures.kt
app/src/androidTest/java/com/stripe1/xmouse/DatabaseMigrationAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/JSchCryptoProviderAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/JSchTestDiagnostics.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/IsolatedDatabaseContext.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/CredentialAccessAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/CredentialMigrationAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/CredentialRepositoryAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/CredentialMetadataBackupAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/PrivateKeyImportAndroidTest.kt
app/src/androidTest/assets/ssh-crypto/rsa1024.pem
app/src/androidTest/assets/ssh-crypto/rsa1024-encrypted.pem
app/src/androidTest/assets/ssh-crypto/ecdsa-p256.pem
app/src/androidTest/assets/ssh-crypto/ecdsa-p256-encrypted.pem
docs/build-toolchain.md
docs/verification/private-key-import.md
docs/verification/ssh-crypto.md
openspec/changes/modernize-xmouse/proposal.md
openspec/changes/modernize-xmouse/design.md
openspec/changes/modernize-xmouse/specs/modern-android-runtime/spec.md
openspec/changes/modernize-xmouse/specs/secure-ssh/spec.md
openspec/changes/modernize-xmouse/tasks.md
```

No staging or commit was performed.
