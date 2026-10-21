# Endpoint-bound SSH host trust (task 4.3)

Verified **2026-10-21**, baseline **`cd3eb6f`**. Only **4.3 [high]** is newly complete:
**20/67** tasks, still **14 high / 53 low**. Sole developer, no delegation, staging, or commit.
Android minimum/target/compile remain **26 / 37 / 37**. Parent review is required before 4.4.

## Production contract and identity

- The insecure `LegacySshPolicy` is deleted. The production presenter composes `HostTrustPolicy` with
  `StrictHostKeyChecking=yes`; there is no `no`, automatic acceptance, password fallback, or legacy
  algorithm enablement. Certificate-to-plain-key fallback is explicitly disabled; unsupported public
  key forms fail closed rather than bypassing the repository.
- The per-session JSch repository callback receives the actual server KEX public key. It returns `OK`
  **only after** a matching saved key or explicit first approval plus durable write/read-back. With
  strict checking, rejection/failure throws before JSch can authenticate. Repository mutation methods
  reject calls: JSch cannot add/remove/replace keys through an alternate path.
- Trust uses the existing **`Endpoint.identity`** rules from 2.4: normalized manual hostname/IP + port;
  a discovered locator's normalized stable target hostname + port when available; otherwise the
  normalized service instance/type/domain + port. Display strings remain separate. A per-session alias
  binds JSch's callback to that captured logical endpoint, never `request.address` or JSch's numeric-IP
  decoration. Different ports are different trust identities. Service locators do not invent DNS names.
- Any different full key for an existing identity is a hard **MISMATCH**, including a different key
  algorithm. There is no first-trust prompt or overwrite action for that case. Normal reconnects and dial
  address changes always consult the repository; approval is not a reusable connection-wide bypass.
- `BoundedSshKey` remains the sole production JSch `KeyPair.load` / raw `jsch.addIdentity` boundary.
  Password/key choice, original encrypted private-key records, and attempt-local normalization are
  unchanged. Local credential preparation/key validation is not wire authentication.

## Private durable trust format

`AndroidHostTrust.open` uses **`noBackupFilesDir/host-trust/approved-v1.bin`**, directory **0700**, files
**0600**. Trust contains public keys, not credential ciphertext, and does not read or depend on Android
Keystore. A lost credential encryption key therefore cannot make the trust repository unreadable.
Current backup rules already allow only the generated safe metadata snapshot and four legacy layouts;
this trust directory is deliberately **not backed up**, is not added to the snapshot, and is not a new
cloud/transfer feature. Restored configuration must establish trust on its destination.

The binary snapshot is `DataOutputStream` big-endian **XMHT** (`0x584d4854`), integer version **1**, and
integer record count. Each record has identity kind byte (host/service), the normalized identity strings
(`writeUTF`) and integer port, full SSH public-key byte length + bytes, and the OpenSSH-style
**`SHA256:` + unpadded Base64(SHA-256(full key))** fingerprint. Algorithm is derived from the full key,
not trusted separately. Reads recompute/check the fingerprint and reject duplicate identities, bad
normalization/ports, unknown kinds/versions, truncation, trailing data, and invalid full keys.

Bounds: **512 records / 1 MiB snapshot / 16 KiB key**; public-key SSH strings must fit remaining input,
at most three fields, 2049 bytes per field and 64 printable ASCII algorithm bytes. Maintained **BC 1.86
`OpenSSHPublicKeyUtil`** parses and validates RSA, NIST P-256/P-384/P-521 ECDSA, and Ed25519 public keys;
exact re-encoding rejects inconsistent type/curve and non-canonical forms. SHA-256 uses the platform
`MessageDigest`, not an application cryptographic algorithm. Parser/provider errors are not retained.

All I/O runs on the SSH worker. Operations share a process lock and an OS file lock. First approval
reads/validates existing state, stages a same-directory private file, fsyncs it, checks cancellation,
durably creates an **`.unverified`** marker, atomically renames the snapshot, fsyncs its directory, and
freshly reads/validates the result. Only then is the marker removed/directory synced and approval
returned. A write, rename, directory-sync or read-back failure is failure, not trust publication; an
uncertain publication poisons the owner and retains/recreates the fail-closed marker for reopen.
Interrupted/unrecognized artifacts without a snapshot are not interpreted as a fresh repository.
Corrupt/unreadable records are never reset, erased, or treated as unknown keys. There is no automatic
recovery/deletion API. Physical power cuts or a persistent inability to durably write even an error
marker are not claimed to provide transactional rollback; the live owner still fails closed.

## First approval, cancellation, and UI ownership

- The real first-trust dialog shows the **intended hostname/logical service, port, key algorithm and
  SHA-256 fingerprint**, asks the user to verify it with the computer owner, and offers distinct
  **Accept and save / Reject / Cancel** actions. Back/cancel does not approve. It does not show a
  transient resolved address as the identity being approved.
- `HostTrustRequests` and its request, repository, policy and transport contain no Android UI object.
  The observer is weak, generation-scoped and dispatched to Android main; `HostTrustUi` is retained
  only by the Activity-local presenter. It creates/dismisses dialogs on main. The worker alone awaits
  the request with a monotonic **60-second maximum**; no observer cancels immediately.
- Stop/background, destroy, disconnect, a newer transport attempt, stale attempt generation, Reject,
  Cancel, or expiry invalidates pending approval and wakes the worker. Detach invalidates queued show
  and dismiss callbacks, including across reattachment. Expired/finished/old request callbacks cannot
  approve a new endpoint, key, or attempt, nor dismiss a newer request's dialog.
- Accept is bound to the immutable captured endpoint/full key. Cancellation before the publication
  preflight prevents storage. If cancellation races an already-started publication after explicit
  Accept, the same explicitly approved pair can remain durable, but cancellation still prevents that
  pending handshake from authenticating. It can never publish another pair or authorize another
  attempt; a later connection performs normal repository verification. No main-thread cancellation
  waits for filesystem/network completion. This is not the full future session-controller state machine.
- Fixed transport categories distinguish host trust, changed host identity, and trust-storage failure.
  The presenter shows fixed safe explanations; no provider message, credential, private key, command,
  or typed content is logged. Intentional on-screen public fingerprints are not diagnostic logging.

## Actual JSch ordering proof and tests

**249 JVM tests / 26 suites**, zero failures/errors/skips: the previous 226 plus **23** trust cases:

- **9 repository tests:** durable full-key/fingerprint reopen; normalized host/port and distinct port/IP;
  logical service and stable advertised hostname; immutable copies; no mismatch replacement; corrupt,
  truncated, oversized, trailing/version/fingerprint failures; unreadable paths and
  interrupted artifacts; staging sync/rename/read-back/directory/final-sync failures; bounded malformed
  key lengths; cancellation before storage and after staging fsync.
- **8 request tests:** approval before completion, fresh repository verification on reconnect, Reject,
  Cancel, missing UI, actual bounded expiry and late Accept, detach/reattach, stale attempt, changed key
  without replacement prompt, cancellation returning while staging I/O remains blocked, and stale queued
  UI delivery/new-key isolation.
- **6 integration methods** (several contain multiple cases): actual production `SshTransport`,
  `JschSshBackend`, `HostTrustPolicy`, repository and request owner connect to an isolated **Apache MINA
  SSHD 2.20.0** server with generated synthetic ECDSA host keys and test-only credentials. Its authentication
  service records **every `SSH_MSG_USERAUTH_REQUEST`**, including `none`; credential authenticators,
  channel initialization, command requests and received initial input are separately recorded.

These are not fake backend/policy ordering assumptions. While the actual first-key callback awaits
approval, and after **Reject, Cancel, deadline, leaving, mismatch, corrupt repository or persistence
failure**, assertions require **zero authentication requests, zero credential-authenticator calls, zero
channels and zero command requests**. Reject/Cancel run with both password and locally validated ECDSA
client-key selection. Accept enables password authentication and the initial command; the server-side
authenticator also checks that a separately reopened repository already contains durable trust.
Disconnect wakes pending trust and old Accept cannot authorize a subsequent attempt.

The DHCP analogue first connects `desktop.local` through **127.0.0.1**, then the same logical hostname,
port and host key through **127.0.0.2**: no second prompt, normal authentication succeeds. A third server
on the alternate address with a different key is blocked, with no prompt/authentication/channel/command.
No system sshd, OS user database, real user SSH host, remote shell command, or generated test credential
file is used. The synthetic command handler only records/consumes input, never launches a process.

The pinned maintained test server is **JVM `testImplementation` only**, selected from Maven Central's
[published stable versions](https://repo.maven.apache.org/maven2/org/apache/sshd/sshd-core/maven-metadata.xml)
(2.20.0; 3.0.0-M6 is a milestone, not the chosen stable line). Final dependency graphs show SSHD only in
`debugUnitTestRuntimeClasspath`, not application/instrumentation runtime. No Android compatibility of
this JVM server dependency is claimed or required. Exactly one production JSch implementation remains,
**mwiede 2.28.8**, with BC **1.86**, no old JSch or SpongyCastle. SLF4J's missing-binding notice uses its
NOP logger; no server logging binding was installed.

The **4 new Android tests** use the actual presenter, `MainActivity`, first-trust dialog, main Looper,
pending owner, and Android private files with synthetic public keys. They verify displayed identity,
algorithm/fingerprint and all three buttons; accept/read-back/modes; Reject/Cancel/late callbacks;
actual Activity stop/return with stale old approval and a different endpoint; and a real **1200 ms**
bounded wait off-main while main remains responsive. Destruction clears pending owner/weak observer
and presenter Activity/trust-UI fields. Android tests do not run the JVM SSH server or claim a live
network handshake; that ordering is exercised by the actual JSch JVM fixture above. All prior parser,
crypto/import, migration/credential, backup, transport/thread, translation and privacy regressions ran.

## Final build and device evidence

Every Android tooling invocation set **both SDK variables**. Final uncached command:

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

**BUILD SUCCESSFUL, 2m21s, 83/83 tasks executed**; lint **0 errors/fatal / 138 warnings**, no new-source
finding, suppression or baseline. Both APKs retain application ID `com.stripe1.xmouse`, version
**25 / 2.0.27**, min/target/compile **26 / 37 / 37**. Existing deprecated API/Jetifier/CDS/bundled unused
BC PKIX TLS-helper warnings remain. Legacy fixtures **4/4**, source boundary audit, strict OpenSpec
validation and whitespace checks pass.

Final APK SHA-256 (both complete device suites used these same APKs):

- App: `2863b1fee868cdb00a3db3ec9e390de6853798d75a92c3a5c86b3ff285f50308`
- Instrumentation: `849a582c2b445a5b2233cdd2503896c9c2f5a91b98117917431edb3425131b34`

| Approved disposable AVD / serial | Full result | Time |
| --- | --- | --- |
| API 26 `xmouse_task41_api26` / `emulator-5584` | 95 discovered, **93 passed / 2 skips / 0 failures** | 57.929s |
| API 35 `xmouse_task31` / `emulator-5580` | 95 discovered, **93 passed / 2 skips / 0 failures** | 58.479s |

The two unchanged skips are opt-in system-backup transport stage/verify methods (status **-4**), not
trust, credential, import or transport tests. Runner `OK (95 tests)` includes those skips.
Before **each successful install**, `verified-install.py` checked exact serial/approved AVD, boot=1,
API, fingerprint and both SDK variables and wrote per-install identity JSON:

- API 26: `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`
- API 35: `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`

Device command pattern, with the SDK exports above:

```sh
export ANDROID_ADB_SERVER_PORT=5038
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-4.1/avd-api26
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task41_api26 -port 5584 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048
# After boot, in a second shell with the same SDK/ADB/PATH exports:
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task43-api26-final-v2-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task43-api26-final-v2-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell am instrument -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 emu kill
# API 35: ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd;
# xmouse_task31 / port 5580 / emulator-5580 / API 35; identical install identity checks/full suite.
```

Only approved disposable app/test package data was cleared. Both AVDs were stopped after their runs;
final `adb -P 5038 devices -l` showed **no devices**. No personal device or user AVD was accessed.

Evidence under `/tmp/opencode/`:

- `xmouse-task43-final-build.log`, `xmouse-task43-final-summary.json` (JVM/lint/APK hashes/device counts).
- `xmouse-task43-api26-final-v2-{instrumentation.log,summary.json,app-identity.json,test-identity.json}`
  and matching `{app,test}-install.log`.
- `xmouse-task43-api35-final-{instrumentation.log,summary.json,app-identity.json,test-identity.json}`
  and matching `{app,test}-install.log`.
- `xmouse-task43-{app,test}-badging.txt`, `xmouse-task43-dependencies-{debugRuntimeClasspath,
  debugUnitTestRuntimeClasspath,debugAndroidTestRuntimeClasspath}.log`.
- JVM XML in `app/build/test-results/testDebugUnitTest/`; lint XML/text/HTML in `app/build/reports/`.

Iteration logs are not substituted for final results: iteration 1 caught a synthetic server username
mismatch (rejection cases already passed); iteration 2 caught a Kotlin test-fixture `lateinit`
accessibility error; iteration 3 passed 246 JVM tests and the earlier API 26 95-test suite. The final
uncached 249-test build and both full suites followed the additional persistence/cancellation/mode
assertions. Initial API 26 install attempts encountered old system-adb/server-version contention;
only this approved emulator was stopped/relaunched with SDK platform-tools first in PATH and ADB 5038.
All identity checks were repeated before successful installs; failed install attempts are not passes.

## Remaining task boundaries

- **4.4:** basic mismatch blocking is required and implemented now; deliberate trust-management
  replacement and broader discovery/retry/reconnect acceptance remain pending. No early replacement UI.
- **4.5:** no full password-failure/encrypted RSA-SHA2/ECDSA server authentication matrix is claimed.
- **4.6:** only the trust prompt and existing 3000 ms persistent channel setup are bounded here;
  comprehensive DNS/connect/KEX/exec/SCP deadlines, output budgets and keepalives remain pending.
  Cancellation of a pending trust wait is immediate; an unrelated blocked network operation can still
  delay worker cleanup. No guarantee of physical power-cut recovery or unlimited repository capacity.
- **5.x:** Activity-local transport construction/disposal, legacy connect toggle/static facade, service,
  notification, session continuity and complete controller generations/recovery remain pending. Leaving
  during first trust cancels; this is not a foreground-service or app-switch continuity implementation.
- No discovery/resolver/LAN permission/editor work, real-LAN DHCP, real SSH account, hardware or API 37
  device coverage, physical heap-leak proof, or opt-in backup transport cycle is claimed for this task.

## Exact task-4.3 file manifest

```text
M app/build.gradle
M app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java
M app/src/main/java/com/stripe1/xmouse/transport/JschSshBackend.java
D app/src/main/java/com/stripe1/xmouse/transport/LegacySshPolicy.java
M app/src/main/java/com/stripe1/xmouse/transport/SshBackend.java
M app/src/main/java/com/stripe1/xmouse/transport/SshTransport.java
A app/src/main/java/com/stripe1/xmouse/trust/AndroidHostTrust.kt
A app/src/main/java/com/stripe1/xmouse/trust/ApprovedHostKey.kt
A app/src/main/java/com/stripe1/xmouse/trust/HostKeyRepository.kt
A app/src/main/java/com/stripe1/xmouse/trust/HostTrustException.kt
A app/src/main/java/com/stripe1/xmouse/trust/HostTrustPolicy.kt
A app/src/main/java/com/stripe1/xmouse/trust/HostTrustRequests.kt
A app/src/test/java/com/stripe1/xmouse/trust/HostKeyRepositoryTest.kt
A app/src/test/java/com/stripe1/xmouse/trust/HostTrustRequestsTest.kt
A app/src/test/java/com/stripe1/xmouse/trust/JschHostTrustIntegrationTest.kt
A app/src/test/java/com/stripe1/xmouse/trust/TrustFixture.kt
A app/src/androidTest/java/com/stripe1/xmouse/HostTrustAndroidTest.kt
M docs/verification/audit-ssh-transport.py
A docs/verification/ssh-host-trust.md
M openspec/changes/modernize-xmouse/tasks.md
```

Pre-existing untracked `.agents/`, `.opencode/`, OpenSpec archive/spec/config roots are untouched.
**No staging or commit. Stop after 4.3 for parent review.**
