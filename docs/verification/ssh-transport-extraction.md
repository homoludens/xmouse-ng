# UI-independent SSH transport extraction (task 4.2)

Verified **2026-10-21**, baseline **`74a8de2`**. Only **4.2 [high]** is newly complete:
**19/67** tasks; allocation remains **14 high / 53 low**. No staging, commit, delegation, dependency,
SDK-floor, credential-format, or authentication-choice change occurred. Minimum Android remains
**8 / API 26**, compile/target **37**.

## Implementation boundary

- `SshTransport` is a pure Java asynchronous boundary owning a dedicated **serial** executor and every
  session, persistent command channel, channel input stream, and temporary exec/SCP resource. Creation,
  authentication handoff, connect/open, writes/flush, exec output collection, SCP file/stream I/O, and
  every resource close run on that worker. Public status queries read volatile snapshots only.
- `SshBackend` supplies blocking resource interfaces. `JschSshBackend` adapts the actual maintained
  JSch implementation without Android dependencies, UI references, logs, or static application state.
  Its required `SessionPolicy` receives the logical `Endpoint` separately from the attempt's dial
  address. This is a future trust/resolution seam, **not implemented host verification**.
- `LegacySshPolicy.unverifiedHostKeys()` explicitly preserves the old **INSECURE**
  `StrictHostKeyChecking=no` behavior at the legacy UI composition root. The generic transport does not
  choose that policy. No new trust exception, fallback, algorithm override, or automatic approval was
  added. **Do not advertise this intermediate application as secure against server impersonation.**
- All production JSch `KeyPair.load` / raw `jsch.addIdentity` calls remain confined to `BoundedSshKey`.
  The backend's final identity handoff calls that boundary, including revalidation of original source
  bytes and raw passphrase bytes. The protected repository, original encrypted source representation,
  saved/attempt-only recovery, and publication protocol were not changed. Identities are cleared after
  authentication and again on cleanup; attempt-owned buffers are cleared best-effort.
- `MyConnectionHandler` is now an Activity-local compatibility **presenter**, not an `AsyncTask` or a
  holder of JSch sessions/channels/pipes. Its only SSH actions submit work to the transport. Dialogs,
  toasts, clipboard actions, connection icons, and intentional recent-command/requested-output display
  remain presentation. The recent-command TextView is no longer static.
- `TransportPresentation` posts through an injected presentation executor and holds its listener only
  through a weak reference. Detach invalidates already-queued and later completions, even if another
  listener attaches. Production uses an Android main Handler. Stop/destroy dismiss presentation;
  destruction clears Activity/screen/credential-UI references and queues terminal transport cleanup.
  An old Activity destroys its own facade, not whichever facade later occupies the legacy static slot.
- Ordered command text, UTF-8/CRLF shell framing, configured-shell exec selection, and Java-callable
  xdotool/ydotool adapters are retained. A true command return now explicitly means **queue acceptance**,
  not completed write/remote execution. Write/exec failure closes established resources and invalidates
  unsent interaction work; it is not replayed after another connection.
- Requested exec results retain the legacy stderr-first/stdout-second, line-separator-stripped display
  and Copy action; errors display fixed text. JSch directs both streams into collectors concurrently,
  avoiding the old sequential stderr-then-stdout reader deadlock. Persistent shell output is discarded
  rather than left in an unconsumed pipe. This is minimal extraction plumbing, not task-4.6 acceptance.
- The dormant `scp -f test.jpg` receive path remains available through `downloadScp(command, File, ...)`,
  without `Activity.openFileOutput`. It owns channel/streams/file output, handles bounded header parsing,
  EOF/errors, exact content length and acknowledgements. Its only View call site was already commented
  out: that obsolete anonymous-AsyncTask comment was removed, **not enabled** as a new remote feature.
- Transport failures expose fixed enums only, not provider exceptions/causes/messages, key material or
  typed text. Presentation logs only fixed events (`connection_attempt`, `exec_command_failed`,
  `ssh_operation_failed`). Existing intentional on-screen command/output content is not logged.

## Tests and thread evidence

**226 JVM tests / 23 suites passed**, zero failures/errors/skips: the previous **210** plus **16** new
transport/presentation cases. The original command-translation, legacy layout/storage, bounded-key,
drawer Back enablement, and credential dialog regressions remain in the full suite.

`SshTransportTest` executes the **production transport** with shared `RecordingSshBackend` resource
implementations. It creates no Activity, Context, View, Android Handler/Looper or UI mocks. Cases cover:

- connect, configured shell, initial command, ordered Java-adapter/custom writes and non-I/O status reads;
- exec result ordering and temporary-channel closure; SCP bytes, three acknowledgements and stream closure;
- failures at create/connect/authentication cleanup/open/output/input/channel-connect/write/flush boundaries;
- write loss discarding queued input, successful fresh connection without replay, exec failure cleanup;
- malformed/error/truncated SCP replies and bounded headers; disconnect close failures attempting every
  remaining resource, idempotence, and terminal close during a blocked handshake;
- actual `JschSshBackend` policy/endpoint/auth selection without network, malformed-key rejection at the
  bounded final handoff, and canonical ECDSA identity publication/removal using a synthetic fixture.

`TransportPresentationTest` proves dispatcher-only delivery and rejection of queued/late results across
detach/reattach, including command/output and failure events.

The three new `SshTransportAndroidTest` methods passed in **both full device suites**:

1. Actual Android main-thread callers submit connect, ordered writes, exec, SCP and disconnect. Every
   backend event, including `session.connect`, `session.open`, `shell.write`, `shell.flush`,
   `exec.command`, `exec.read`, `exec.write`, temporary/persistent stream/channel closes and session close,
   records `Thread.currentThread()`. Assertions require **`xmouse-ssh-io`**, never
   `Looper.getMainLooper().thread`; presentation assertions require the actual **main** thread.
2. Detach/reattach and terminal close while a synthetic handshake is deliberately blocked return from
   main immediately, perform cleanup on the worker, send no initial command after cancellation, and
   deliver no late result to either old or replacement screen.
3. The actual legacy presenter, attached to a real `MainActivity` with an injected production transport
   and recording backend, preserves recent-command display, requested exec dialog text and Copy button.
   Presentation callbacks are main-only; backend operations are not main. No real host is contacted.

The existing Android logging test now checks the facade's public connection snapshot instead of its
removed public JSch session field. Its real-keyboard/logcat/privacy assertions are retained. The full
device runs also retain every credential/import/migration/database-path assertion and all bounded-key
tests. No real SSH host or user remote input was used by any verification here.

## Final build, APKs and devices

Every Android tooling invocation set **both** SDK variables; emulator launches also set
`ANDROID_ADB_SERVER_PORT=5038`. No machine path was added to build configuration.

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

Final uncached build: **BUILD SUCCESSFUL, 2m22s, 83/83 tasks executed**;
`/tmp/opencode/xmouse-task42-final-build-v2.log`. Lint: **0 errors/fatal, 138 warnings**, with no findings
in the new transport/tests and no suppression/baseline. Existing deprecated APIs/Jetifier, JVM CDS and
bundled unused BC PKIX TLS-helper warnings remain visible. Fixture audit **4/4**, transport source audit,
strict OpenSpec validation and whitespace checks pass.

Actual APK badging retains application `com.stripe1.xmouse`, version **25 / 2.0.27**, min/target/compile
**26 / 37 / 37**. Test package `com.stripe1.xmouse.test` uses AndroidJUnitRunner. Final SHA-256:

- App: `ebbb362f311ba4587400b1a286f5c179fb3c1e6fe24171792954baaa62d00531`
- Instrumentation: `e39b34ec1712cd8bbc82068b1ac9abeb46fb60f82c256cc9b63999bcd068d0f7`

Both suites ran those **same final APKs**:

| Approved disposable AVD / serial | Result | Time |
| --- | --- | --- |
| API 26 `xmouse_task41_api26` / `emulator-5584` | 91 discovered, **89 passed / 2 skips / 0 failures** | 50.764s |
| API 35 `xmouse_task31` / `emulator-5580` | 91 discovered, **89 passed / 2 skips / 0 failures** | 68.345s |

The two skips are the unchanged opt-in backup-transport stage/verify methods (Android status **-4**),
not exclusions of transport, credential, crypto or UI regressions. Runner `OK (91 tests)` includes them.

Before **each install**, the retained `verified-install.py` checked serial, exact approved AVD name,
boot-completed state, API, fingerprint and both SDK variables. Only these disposable AVDs' app/test
package data was cleared. Their fingerprints were:

- API 26: `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`
- API 35: `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`

Representative repeat commands (after launching only the approved AVD with its specified `ANDROID_AVD_HOME`):

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task42-api26-repeat-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task42-api26-repeat-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell am instrument -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 emu kill
# API 35 equivalent: emulator-5580 / xmouse_task31 / API 35.
```

Full evidence under `/tmp/opencode/`:

- `xmouse-task42-final-summary.json`: JVM/lint counts, final APK hashes, per-install device identities.
- `xmouse-task42-api26-final-v2-{instrumentation.log,summary.json,app-identity.json,test-identity.json}`
  and matching `-{app,test}-install.log`.
- `xmouse-task42-api35-final-v2-retry-{instrumentation.log,summary.json,app-identity.json,test-identity.json}`
  and matching `-{app,test}-install.log`.
- `xmouse-task42-{app,test}-badging.txt`; JVM XML in `app/build/test-results/testDebugUnitTest/`, lint
  XML/text/HTML in `app/build/reports/`.

Both AVDs were stopped after their successful run. Final `adb -P 5038 devices -l` returned **no devices**.
No personal device, existing user AVD, or live SSH server was used.

### Verification iterations, not counted as passes

The first emulator launch omitted the emulator's separate ADB-server-port environment variable: it
booted but was not visible to isolated server 5038. Only its exact verified process was stopped, then
the same approved AVD was relaunched with `ANDROID_ADB_SERVER_PORT=5038`. Initial APK push/install
attempts on each boot also hit transient ADB transport/server failures; successful retries repeated all
identity checks before each install. Those failed install logs remain; no test pass was inferred.

The first full API 26 run on the earlier APK crashed after **57 passed / 2 skips / 1 failed**, with 91
discovered (`xmouse-task42-api26-final-instrumentation.log`). All three new transport tests had passed,
but queued `MainActivity.onResume` preparation encountered a closed fixture SQLite pool. Main now
captures the application repository **before** queueing work, uses that same owner on its worker, and
drops stale-screen preparation/host-selection work before repository access. The presenter exposes a
worker-side volatile-generation gate without querying UI state. The credential fixture barrier also
drains pending main lifecycle/picker callbacks **before** enqueueing its worker barrier; no assertion,
database isolation overload or path check was removed. The final uncached build and both complete
suites above were rerun after these changes. Earlier green build/APK results are not substituted for
that final evidence.

## Remaining task boundaries and limits

- **4.3 / 4.4:** endpoint-keyed trust persistence, first-trust approval, mismatch blocking and deliberate
  replacement are **not implemented**. The isolated legacy trust policy is still insecure.
- **4.5:** no live password/RSA-SHA2/ECDSA server-authentication matrix was run or claimed.
- **4.6:** only the existing **3000 ms persistent-channel connect timeout** is preserved. Session/exec/SCP
  waits and output collection do not yet have comprehensive deadlines/size budgets, keepalive failure
  handling or sustained-output acceptance tests. An in-flight blocked operation can delay queued
  cleanup, although it cannot block Android main. The small draining changes above prevent introducing
  an unconsumed shell pipe or sequential exec-output deadlock; they do not complete 4.6.
- **5.x:** the legacy Activity still constructs/disposes its facade/transport, and the static facade
  adapter, auto-connect toggle, desired-session/recreation/service/notification/recovery/input-lifecycle
  work remains pending. This extraction is **not** an app-switch continuity or foreground-service claim.
  Queue invalidation and detachable callbacks are local transport safety, not the future session
  controller's complete attempt-generation state machine.
- No discovery/resolver/LAN-permission/editor work, API 37 device run, hardware/real-device or real-LAN
  acceptance, arbitrary private-key-format support, guaranteed memory erasure, or opt-in system-backup
  cycle is claimed here.

## Exact task-4.2 file manifest

```text
app/src/main/java/com/stripe1/xmouse/CommandTransport.java
app/src/main/java/com/stripe1/xmouse/MainActivity.java
app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java
app/src/main/java/com/stripe1/xmouse/MyInterface.java
app/src/main/java/com/stripe1/xmouse/MyMouseView.java
app/src/main/java/com/stripe1/xmouse/transport/SshConnectionRequest.java
app/src/main/java/com/stripe1/xmouse/transport/SshBackend.java
app/src/main/java/com/stripe1/xmouse/transport/LegacySshPolicy.java
app/src/main/java/com/stripe1/xmouse/transport/JschSshBackend.java
app/src/main/java/com/stripe1/xmouse/transport/SshTransport.java
app/src/main/java/com/stripe1/xmouse/transport/TransportPresentation.java
app/src/test/java/com/stripe1/xmouse/transport/SshTransportTest.kt
app/src/test/java/com/stripe1/xmouse/transport/TransportPresentationTest.kt
app/src/testFixtures/java/com/stripe1/xmouse/transport/RecordingSshBackend.kt
app/src/androidTest/java/com/stripe1/xmouse/SshTransportAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/LoggingPrivacyAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/CredentialRepositoryAndroidTest.kt
docs/verification/audit-ssh-transport.py
docs/verification/ssh-transport-extraction.md
openspec/changes/modernize-xmouse/tasks.md
```

Pre-existing untracked `.agents/`, `.opencode/`, OpenSpec archive/spec/config roots are untouched.
**No staging or commit performed. Stop after 4.2 for parent review.**
