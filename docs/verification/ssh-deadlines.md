# Finite SSH deadlines and output draining (task 4.6)

Verified **2026-10-21** against baseline **`7299d11`**. Only **4.6 [high]** is newly complete:
**23/67 tasks**, still **14 high / 53 low**. Sole developer; no staging, commit, or delegation.
The API floor/target/compile remain **26 / 37 / 37**.

## Production contract

`SshTransport` serializes commands on a coordinator, but performs provider operations on bounded
background lanes. A finite `Future` wait is **not** treated as proof that a native call stopped.
Cancellation invalidates its owner immediately, cancels the wait, and independently schedules resource
cleanup. Every session, persistent/temporary channel, stream, and SCP file output has a once-claimed
close slot. Late resource handoffs register with the old closing ledger and are closed there, never
attached to the new connection. Actual socket abort is independent of JSch/channel write locks.

The process-wide `SshRuntime` bounds quarantine across Activity/transport recreation:

- **2 resolver threads**, no queue; **4 operation threads**, no queue.
- **32 cleanup threads / 32 queued close slots**; at most **4 retained resource owners**, each with at
  most eight simultaneously owned close slots. There is a worker for every possible retained resource,
  so already-blocked closes cannot prevent attempting other independent resources. The bounded queue
  absorbs workers' short completion/admission races, not additional owners. Owner admission remains occupied until both the real
  operation and its registered cleanup finish, even after a reported timeout.
- One background scheduler; idle pool threads expire after ten seconds. No caller-runs policy,
  per-timeout replacement executor, unbounded native-DNS thread creation, or main-thread network close.
  The shared runtime maximum is **39 lane workers** (2 + 4 + 32 + 1), separate from a transport's
  coordinator and JSch-owned reader threads; this is not an Android-process-wide thread census.
- Capacity exhaustion fails closed as `CAPACITY`. It does not claim to kill a non-interruptible native
  function. A genuinely wedged provider can consume those fixed slots until it returns/process exit;
  subsequent attempts reject rather than growing threads or using another connection's resources.
- At most **128 queued command operations**. Queue waiting consumes the operation budget. Expired or
  invalidated interactions are discarded, never replayed. Already-started remote input is not undoable.

Production `SshLimits` (milliseconds unless noted):

| Stage | Limit |
| --- | ---: |
| Native single-address resolution | 10,000 |
| Local bounded key/session preparation; trust replacement publication wait | 10,000 |
| TCP socket connect | 15,000 |
| SSH handshake/authentication including first-trust wait | 90,000 |
| Command channel setup | 5,000 |
| Command write + flush | 5,000 |
| Requested exec, including channel setup, drain and completion | 30,000 |
| Existing SCP receive, including protocol/file I/O | 60,000 |
| All scheduled cleanup completion waiting, per owner | 2,000 |
| Keepalive interval / maximum missed responses | 15,000 / 3 responses |
| Idle health polling / bounded status-read operation | 500 / 2,000 |
| Combined requested stdout + stderr retained bytes | 1 MiB |

Connect's stage budgets sum to at most **122 seconds before cleanup / 124 seconds including cleanup**
(TCP is inside SSH, not additive). A previously cancelled owner's coordinator cleanup can additionally
consume up to two 2-second cleanup waits; it cannot wait indefinitely for provider/network completion.
Command-operation budgets include queue waiting. First-trust approval retains its independent **60-second maximum**
inside the SSH budget; no missing UI, expiry, capacity error or timeout approves a key. Replacement
publication still has exact-key CAS and cancellation preflights; an already-started confirmed atomic
publication can remain durable, but never resumes the blocked handshake.

JSch uses a custom socket factory with an already resolved `InetAddress` and explicit TCP timeout;
its unbounded default DNS/socket-creation helper is not used. The immutable logical endpoint and port
remain separate from the attempt's address. This is only the bounded single-address seam, **not** task
6.3 multi-address/platform-network resolution or 6.4 mDNS. Existing trust and authentication failures
stop the attempt; there is no retry/autotrust/algorithm/password fallback.

JSch's finite keepalive settings plus an independent last-inbound-byte silence backstop detect a
half-open socket even if its keepalive write itself blocks. Idle session/channel status is checked
without new user input, and updates the volatile connected snapshot and fixed `SESSION_LOST` or
`CHANNEL_CLOSED` event. Explicit Disconnect invalidates monitors before cleanup; old health timers,
late provider completions and cleanup callbacks cannot close/report a newer owner. Core code retains
no Activity, View, dialog or static UI object. The legacy presenter dispatches loss through its weak,
detachable presentation bridge; service/controller/recovery ownership remains task 5.x.

Both requested streams are delivered directly to synchronized bounded collectors, not sequential
stderr/stdout readers. Retained bytes and copying work are capped across **both** streams; overflow
is discarded while the coordinator aborts the operation and returns `OUTPUT_LIMIT`, **never partial
success**. Completion without an exit status is `CHANNEL_CLOSED`; transport disappearance is
`SESSION_LOST`. A received nonzero exit status retains the legacy requested-output contract (complete
output is displayed, not an assertion that the remote command succeeded). Within budgets,
stderr-first/stdout-second, line-separator-stripped display and Copy
remain unchanged. Persistent stdout/stderr are discarded immediately with constant-memory sinks.
Exec, write, SCP, connection, resolution, channel and cleanup timeouts have fixed typed categories.
Provider exceptions, credentials, key bytes, command text and remote error text are not diagnostics.
`BoundedSshKey` remains the only raw production identity-loading boundary.

## Verification coverage

- `SshDeadlineTest` exercises the production transport with intentionally non-interruptible recording
  resources: DNS/create/connect/auth-clear/open/output/input/channel/write/flush/exec/SCP/close stalls,
  cancellation before/during work, late resource handoff, bounded resolver/operation saturation,
  queue expiry, idle closure, output overflow, all-resource cleanup, captured old timer invocation and
  replacement-session isolation. Test-owned blocked calls are explicitly released after assertions.
- `JschDeadlineIntegrationTest` uses actual production transport/JSch/trust with JVM-only Apache MINA
  SSHD and plain loopback socket fixtures. It covers an actually saturated loopback TCP accept queue,
  identification/KEX/auth/channel stalls, an actual
  remote-window-blocked 4 MiB write, non-completing exec/SCP, simultaneous stderr/stdout, sustained
  persistent output while input is delivered, output overflow, idle channel/session closure, missing
  exec completion status, healthy idle keepalive replies and actual keepalive traffic on a
  transparent-then-blackholed TCP connection. Generated host keys and
  synthetic credentials only; no fixture launches a shell/process, system sshd, or contacts a user host.
- `SshDeadlineAndroidTest` calls the real transport from the actual Android main Looper with short
  injected budgets. It proves main-heartbeat/presentation delivery during non-interruptible handshake,
  write and close stalls, bounded classified failure, coordinator progress, independent cleanup, and
  unsent-input discard. It does not package MINA or claim a server handshake on Android.
- Existing trust/no-auth/mismatch/replacement, authentication matrix, bounded-key, credential/import,
  migration, backup/privacy, command translation and legacy presenter/Copy tests remain in the suites.

Short real-time test configurations use the same limit fields and no production sleeps/timeout mocks:

| Configuration | DNS / preparation / TCP / SSH / channel / write / exec / SCP / cleanup (ms) | Keepalive ms × misses; poll/status ms; output |
| --- | --- | --- |
| Pure transport | 180 / 220 / 220 / 350 / 220 / 220 / 450 / 450 / 120 | 100 × 2; 40/120; 4096 bytes |
| Actual JSch/MINA | 500 / 2500 / 700 / 2200 / 600 / 700 / 1500 / 900 / 500 | 200 × 2; 50/200; 1 MiB |
| Actual Android main | 200 / 350 / 200 / 350 / 250 / 250 / 450 / 450 / 150 | 150 × 2; 50/150; 4096 bytes |

Backend recording assertions retain actual `Thread` identities and require I/O/cleanup on
`xmouse-ssh-io`, never Android main. The audited executor configuration names resolution
`xmouse-ssh-dns`, serial coordination `xmouse-ssh-coordinator`, and health scheduling
`xmouse-ssh-timer`. Neither caller/status access nor Android main performs provider I/O. Actual
Android main-heartbeat assertions allow **500 ms** while the provider remains blocked; dispatched
presentation requires `Looper.getMainLooper().thread`. Existing Android main/presenter/Copy tests also
retain their off-main resource and main-only presentation assertions.

Saturation tests hold two non-interruptible resolver-seam calls or four non-interruptible operations, then make **20
more attempts**, asserting fixed active counts and `CAPACITY` rather than additional workers. Operation
owners remain retained at four until the blocked creates return; afterward active operations/retained
owners return to zero and each late session close is recorded exactly once. Captured old health
timers are explicitly invoked ten times after a replacement connection is active. These are bounded
capacity/ownership assertions, not a claim that Java can forcibly terminate arbitrary native code or
that physical heap-leak analysis was performed.

The repeated-close case additionally holds **all three recorded closes across four successive owners**
(12 simultaneously blocked close calls), requires every call to have started exactly once with zero
queued cleanup, and rejects a fifth owner. Releasing the calls returns retained owners to zero and
permits a fresh successful connection. This catches cleanup starvation that a single blocked-close
test would miss. Raw socket abort is registered before provider disconnect as an additional safeguard.

## Final commands and evidence

Every Android command sets both SDK variables. The final command was:

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

Final uncached build: **BUILD SUCCESSFUL in 3m39s; 83/83 tasks executed**,
`/tmp/opencode/xmouse-task46-final-build-v4.log`. **282 JVM tests / 29 suites**, zero failures, errors,
or skips (260 previous + **12 pure deadline / 10 actual JSch methods**). Lint: **0 errors/fatal / 138
warnings**, no new-source finding, suppression, or baseline. Existing deprecation/Jetifier, JVM CDS and
bundled BC unused TLS-helper warnings remain visible. Storage fixtures **4/4**, SSH source/privacy
audits, strict OpenSpec and whitespace checks pass.

Actual JSch assertions include a **700 ms TCP timeout winning over the 2200 ms SSH budget** on a
saturated loopback accept queue; two 2200 ms identification/KEX stalls; an actual **4 MiB** remote-window
blocked write; exact complete output of **196,608 `E` stderr bytes + 196,608 `O` stdout bytes**; more
than **2 MiB persistent output before input delivery**; `OUTPUT_LIMIT` rather than partial exec success;
and healthy idle replies for **1100 ms**, longer than the injected **600 ms** silence cutoff. The
blackhole case requires actual client keepalive bytes to be observed/dropped before finite loss. The
retained XML records each method's outcome and timing; these are not fake backend socket claims.

Final APK identity: application `com.stripe1.xmouse`, version **25 / 2.0.27**, min/target/compile **26 /
37 / 37**. Instrumentation package `com.stripe1.xmouse.test`, AndroidJUnitRunner. SHA-256:

- App: `58cf95b722a2855b5e7bf1511e05ee42f6330b0a121477b482ed2a039ed79441`
- Instrumentation: `8c2857485e37b04058e3ec173b44fe91b6a3f2d2d2543b8e014b74ad0448dbd0`

Badging: `/tmp/opencode/xmouse-task46-{app,test}-badging.txt`; counts/hashes:
`/tmp/opencode/xmouse-task46-final-summary.json`; retained final JVM XML:
`/tmp/opencode/xmouse-task46-final-jvm/`; lint XML:
`/tmp/opencode/xmouse-task46-final-lint.xml` (also normal `app/build/` reports).

### Android final APK runs

Both full suites passed on the **same final APK hashes above**. Before **each** install, the retained
`/tmp/opencode/xmouse-task-4.1/verified-install.py` checks exact approved serial/AVD/API, boot=1,
fingerprint and both SDK variables. The runner clears only approved disposable app/test package data,
records the APK hashes again, and stops that AVD in a `finally` block. No personal device/user AVD is
authorized. Actual commands:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-4.1/avd-api26
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task41_api26 -port 5584 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task46-final-v4-api26-emulator.log 2>&1
# A second shell with the same SDK/ADB/PATH exports:
python3 /tmp/opencode/xmouse-task46-run-device.py 26
# After that AVD stops, API 35 uses ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd:
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task46-final-v4-api35-emulator.log 2>&1
python3 /tmp/opencode/xmouse-task46-run-device.py 35
```

The retained runner invokes the same verified-install helper separately for app and test APK, with
`--clean` only on the app install, then:

```sh
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell am instrument --user 0 -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 emu kill
# API 35: identical commands with emulator-5580, after both fresh install identity checks.
```

| Approved AVD / serial | Full result | Duration |
| --- | --- | ---: |
| API 26 `xmouse_task41_api26` / `emulator-5584` | 102 discovered, **100 passed / 2 skips / 0 failures** | 110.545s |
| API 35 `xmouse_task31` / `emulator-5580` | 102 discovered, **100 passed / 2 skips / 0 failures** | 123.857s |

API 26's fresh app/test install identities both recorded boot=1 and fingerprint
`Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`.
Its two unchanged status **-4** assumption skips are the opt-in backup-transport methods
`CredentialMetadataBackupTransportAndroidTest.verifyMetadataAndReentryAfterSystemTransportRestore`
and `stageSyntheticPackageDataForSystemBackup`.
API 35's app/test identities likewise both recorded boot=1 and fingerprint
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`, with exactly those
same two skips. No transport, trust, authentication, crypto/import, credential/migration or privacy
regression was skipped; both new deadline methods passed on each API. Runner `OK (102 tests)` includes
the skips and is not reported as 102 passes.

Both approved AVDs were stopped after their complete runs. Final `adb -P 5038 devices -l` listed no
devices, and approved console ports **5580 / 5584** were both closed. No personal device, user AVD,
system sshd, OS account or real user SSH host was used. No API 37/physical-device/real-LAN/app-switch
continuity or opt-in system-backup cycle is claimed; service/controller, recovery, permission,
discovery and editor work remains in its existing tasks.

Final dependency graphs `/tmp/opencode/xmouse-task46-dependencies-{debugRuntimeClasspath,
debugUnitTestRuntimeClasspath,debugAndroidTestRuntimeClasspath}.log` confirm MINA SSHD **2.20.0** only
in JVM test runtime, not either Android runtime; the sole production JSch remains **mwiede 2.28.8**
with BC **1.86**, without old JSch/SpongyCastle. These configuration-only commands did not rebuild or
replace either verified APK.

```sh
# With both SDK exports above, repeated separately for each named configuration:
./gradlew --no-daemon --console=plain --max-workers=2 :app:dependencies --configuration debugRuntimeClasspath
./gradlew --no-daemon --console=plain --max-workers=2 :app:dependencies --configuration debugUnitTestRuntimeClasspath
./gradlew --no-daemon --console=plain --max-workers=2 :app:dependencies --configuration debugAndroidTestRuntimeClasspath
```

Retained per-device evidence uses `/tmp/opencode/xmouse-task46-final-v4-api{26,35}-` prefixes:
`instrumentation.log`, `summary.json`, `run.log`, `app-identity.json`, `test-identity.json`,
`app-install.log`, and `test-install.log`. Summary hashes must match the final APK hashes above.

### Verification iterations

Earlier runs are not substituted for final evidence. Focused iteration 1 caught old tests' 3000 ms
channel constant and sequential-close-order assumption; the new 5000 ms policy and independent cleanup
now require the same complete close set, with stronger blocked-close/once-only cases. Iteration 2 caught
loss of a replacement storage failure's typed reason through `ExecutionException`; reason mapping was
restored and the original regression retained. Iteration 3 caught a fixture's force-close without an SSH
close packet; that correctly produced a finite exec timeout, not a wire channel-close event. The final
missing-status fixture sends a real channel close and expects `CHANNEL_CLOSED` without partial success.
One initial shell invocation also rejected an unquoted test glob; it ran no tests.

The first uncached build passed 279 JVM tests. Review moved keepalive initialization before JSch's
reader starts and added actual keepalive-traffic assertions; the second uncached build also passed.
The verification set was completed with the native TCP/healthy-reply cases. The
v3 build passed all 281 tests and both complete Android suites (100 passed / 2 skips each, 113.199s on
API 26 and 122.701s on API 35) on its exact APKs. Saturation review
then identified that multiple permanently blocked closes could occupy all cleanup workers and starve
other independent resource closes. Final v4 reserves a worker for every admitted resource slot, reduces
owner admission to four, and adds the 12-concurrent-blocked-close regression. The final uncached build
above passed all 282 tests after that change. Both full Android suites above were also rerun on v4's
exact APKs; v3 results are not substituted for those final results. No runtime/test source changed after
the final v4 uncached build.

## Exact task-4.6 manifest

```text
app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java
app/src/main/java/com/stripe1/xmouse/transport/JschSshBackend.java
app/src/main/java/com/stripe1/xmouse/transport/SshBackend.java
app/src/main/java/com/stripe1/xmouse/transport/SshConnectionRequest.java
app/src/main/java/com/stripe1/xmouse/transport/SshLimits.java
app/src/main/java/com/stripe1/xmouse/transport/SshRuntime.java
app/src/main/java/com/stripe1/xmouse/transport/SshTransport.java
app/src/test/java/com/stripe1/xmouse/transport/SshTransportTest.kt
app/src/test/java/com/stripe1/xmouse/transport/SshDeadlineTest.kt
app/src/test/java/com/stripe1/xmouse/transport/JschDeadlineIntegrationTest.kt
app/src/testFixtures/java/com/stripe1/xmouse/transport/RecordingSshBackend.kt
app/src/androidTest/java/com/stripe1/xmouse/SshDeadlineAndroidTest.kt
docs/verification/audit-ssh-transport.py
docs/verification/ssh-deadlines.md
openspec/changes/modernize-xmouse/tasks.md
```

No dependency, credential-format, authentication-choice, SDK, permission/discovery/editor or service
changes. Pre-existing untracked tooling/OpenSpec roots are untouched. No staging or commit; stop after
4.6 for parent review.
