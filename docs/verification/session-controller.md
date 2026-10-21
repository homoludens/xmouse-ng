# Serialized session controller (task 5.1)

Verified against baseline **`456c1b4`**, **2026-10-21**. Task **5.1 [high]** only, assigned
`openai/gpt-6.1-sol#xhigh`. Sole developer; no staging, commit or delegation. Android
minimum/target/compile remain **26 / 37 / 37**, with no dependency changes.
**25/67 tasks complete**; allocation remains **14 high / 53 low**.

## Ownership and production handoff

`SessionController` is pure Kotlin. Its injected FIFO `SessionEventQueue` is the only owner of desired
host, attempt generation, observed connection, auto-connect suppression and close barriers. Production
uses `xmouse-session-owner`; deterministic tests explicitly drain a manual queue. `current` is a volatile
immutable `SessionSnapshot`. Weak subscriptions deliver coalesced latest snapshots only through the
subscriber's executor. Closing a subscription invalidates queued delivery, not the connection. UI code
must retain its observer while attached and supply its main dispatcher without capturing a screen.

`SessionHost` captures the logical `Endpoint`, selected profile ID/username, authentication choice,
opaque password/key/passphrase references and recovery markers, plus shell/initial-command settings.
Normalized logical endpoint + port and credential snapshot define sameness, never a resolved IP. Alias
or hostname display spelling alone does not replace a session; another profile or credential revision
does. Manual endpoints and ports must match the credential profile at construction; callers cannot pair
another target with that profile's credential snapshot. The repository overload authenticates this
captured selection instead of consulting mutable
selected-host/global-auth preferences again. The old legacy entry point delegates to that same snapshot
path. Existing protected storage, key-loss/re-entry, legacy key import and bounded key validation remain.

`SshSessionAttemptFactory` is the actual production adapter, not a fake reducer. It owns the asynchronous
credential provider, attempt-local prompt/trust owner, real `SshTransport`, and strict `HostTrustPolicy` /
`JschSshBackend`. Ready is published only after the transport's command channel and initial write are
usable. The minimal transport progress hook reports resolution/connection; successful strict host-key
verification reports authentication before returning to JSch. Missing service-only resolution fails
closed rather than inventing a DNS name; multi-address/network/NSD resolution remains 6.x/7.x.

`AndroidSessionOwner.create` is the application-context-only composition root ready for the next
started/bound service. It uses `AndroidCredentials`, `AndroidHostTrust`, and a main Handler dispatcher.
Its asynchronous `selectedHost` captures repository metadata after migration preparation. No controller,
attempt or prompt broker stores an Activity, View, dialog, static UI object or decrypted credential cache.
Temporary authentication material is transferred to the existing transport and wiped best-effort. Late,
duplicate or wrong-profile material is discarded/wiped rather than used for another host.

**Staged integration:** `MainActivity` and `MyConnectionHandler` are deliberately unchanged. They still
own the legacy toggle/facade lifetime until **5.4**. Service/permissions (**5.2**), notification (**5.3**),
command routing (**5.5**), input lifecycle (**5.6**), recovery policy (**5.7**) and dismissal (**5.8**) remain
pending. Existing 4.x trust/replacement UI and tests continue to use the legacy presenter. The future
service UI must render typed mismatch errors and wire deliberate trust management, never resume a
cancelled handshake or reuse an invalidated mismatch token. These tests do **not** establish Android
app-switch continuity or the five-minute physical-device acceptance.

## Intent, cancellation and closure

- Repeated explicit/automatic requests for the same pending/connected selection are true no-ops: no
  replacement snapshot, extra credential read, transport creation, ready event or disconnect toggle.
- A deliberate host change records the latest desired host, invalidates the exact old attempt before
  cancellation, and starts no new credential/network attempt until the old close barrier succeeds.
  Old phase/ready/failure/loss/trust/cancel/close events cannot publish or close a newer attempt.
- `disconnect()` clears desired intent, invalidates pending and active work idempotently, and suppresses
  automatic connect regardless of the saved preference. Capture `automaticConnectPermit()` **before**
  asynchronous selection/preparation; late `connectAutomatically(host, permit)` cannot undo a newer
  deliberate connect/disconnect or switch to an obsolete host. Only deliberate `connectIfNeeded` clears
  suppression. After involuntary loss, desired host remains but state is honestly Error; this task does
  not initiate any automatic recovery loop. Future 5.7 can apply its bounded foreground recovery policy.
- `SessionInteractions` permits one weakly presented credential/trust request. An absent observer cancels
  the request, never accepts it. Detach cancels pending consent but leaves an established transport alone. Queued/stale
  requests and dismissals are attempt/request scoped. Credential preparation/re-entry has a **60-second**
  maximum; first trust retains its existing independent **60-second** bound inside SSH's deadline.
  One shared timer has remove-on-cancel enabled; no per-attempt pool growth or UI waiting.
- New `SshTransport.closeConfirmed` is terminal and idempotent. Its success requires **actual** admitted
  operations and all registered/late cleanup slots to retire, not just cancelled Futures or a snapshot
  of already-known resources. It also remembers cleanup exceptions after an owner has retired.
  The existing process-wide 4.6 resource/quarantine limits and independent socket abort remain intact.
  The controller's combined credential/transport barrier is bounded to **5 seconds** (short injected
  limits in tests). Timeout/close error yields `CLEANUP_UNCONFIRMED`, retains the old attempt identity,
  and blocks every replacement: it never claims that the previous provider physically stopped.
  Quarantined resources still close independently; a later completion does not silently unblock/start
  another host. Java cannot terminate arbitrary native code. Do not replace this owner to bypass its
  blocked barrier; retain it until safe terminal/process teardown rather than inferring cleanup from
  observer detachment or an elapsed deadline.
- `SessionSnapshot.closed` marks terminal shutdown **intent**, not proof of physical closure; the
  connection observation remains Closing/Error until the barrier's outcome. The service integration must
  retain/check connection intent and automatic suppression before any preference-driven startup, not
  recreate an owner merely because the saved auto-connect preference is true after Disconnect.
- Transport loss retains the existing unsent-input discard contract; nothing queues/replays an old
  click, key, script or initial command for recovery. This task adds no command buffering or input path.

## Verification coverage

- `SessionControllerTest`: every connection phase, repeated explicit/auto connect, host changes and
  latest desired selection during close, stale phase/ready/error/trust-cancel/loss/close events,
  disconnect and delayed auto-return, retained involuntary-loss intent without recovery, failed close,
  terminal shutdown, profile/auth/port identity and observer detach/recreation.
  Explicit Retry during involuntary-loss cleanup waits for the existing barrier; an automatic return
  cannot request recovery, and Disconnect during that cleanup cannot restore the old Error afterward.
- `SshSessionAttemptFactoryTest`: actual production transport with recording resources and synthetic
  credentials; cancellation in credential/resolution/create/handshake/channel/ready stages; exact-host
  material and late-buffer wiping; duplicate callbacks; close exception/stall and credential cancellation
  deadline; missing UI, queued/stale prompt isolation, and loss without reconnect. Recorded DNS/I/O
  threads are not the caller and are exactly the existing `xmouse-ssh-dns` / `xmouse-ssh-io` lanes.
- `SessionPipelineIntegrationTest`: actual controller/adapter/transport/JSch/strict-trust pipeline against
  synthetic JVM-only MINA loopback servers; first approval precedes credentials/commands; repeated
  requests and observer recreation retain the same server session object; profile switching replaces it
  only after confirmed close; cancellation during actual trust wait and late Accept never authenticate
  the old endpoint. No process/shell is launched; command handlers only consume synthetic bytes.
- `SessionControllerAndroidTest`: actual main Looper callers/observers plus production controller,
  adapter and transport. A non-interruptible synthetic handshake/close cannot block main or admit another
  host; observer delivery is main-only, backend I/O is never main. Healthy detach/reattach retains one
  session; repeated Disconnect and delayed auto-return cannot reconnect. Main heartbeat bound: **500 ms**.
- `CredentialRepositoryAndroidTest` adds real SQLite/preferences/encrypted-store snapshot isolation:
  changing the selected profile, auth choice and password reference after capture still reads only the
  captured profile's old retained credential, without resetting/changing the new choices.

## Final commands and evidence

Every Android command used **both** SDK variables. Final uncached build:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
LINT_PRINT_STACKTRACE=true ./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks \
  --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 docs/verification/verify-v2-storage-fixtures.py
python3 docs/verification/audit-ssh-transport.py
openspec validate modernize-xmouse --strict
git diff --check
```

**BUILD SUCCESSFUL in 3m36s; 83/83 tasks executed**. **305 JVM tests / 32 suites**, zero failures,
errors or skips (282 previous + **12 controller / 8 adapter / 3 real-pipeline methods**). Lint:
**0 errors/fatal / 136 warnings**, no new session-source finding, suppression or baseline. Existing
Jetifier/deprecation/JVM CDS and bundled unused BC helper warnings remain visible. Storage fixtures
**4/4**, SSH source/privacy boundaries (now also auditing the pure session package), strict OpenSpec and
whitespace checks pass. `ConnectionStateTest` also covers the two new non-ready phases.

Final log: `/tmp/opencode/xmouse-task51-final-build-3.log`. JVM XML is retained in
`/tmp/opencode/xmouse-task51-final-jvm/`; lint in `/tmp/opencode/xmouse-task51-final-lint.xml` (also the
normal `app/build/` reports). Counts/hashes: `/tmp/opencode/xmouse-task51-final-summary.json`.
No runtime/test source changed after this build; both complete device runs used these APKs.

APK identity: `com.stripe1.xmouse`, version **25 / 2.0.27**, min/target/compile **26 / 37 / 37**;
instrumentation `com.stripe1.xmouse.test` / AndroidJUnitRunner. SHA-256:

- App: `26d21b2c3af0e29ad857c9a2a32a67295d630766439aaa7299c565357a7a1ef9`
- Instrumentation: `82322d6fcd6d7340de24419ed9067f81949fec85b2361efe4cd0dace2daf4d67`

Actual APK badging is `/tmp/opencode/xmouse-task51-final-{app,test}-badging.txt`. No permission,
application ID, SDK, SSH/crypto dependency, algorithm, key-format or encrypted-record change occurred.

### Final Android runs

Both complete suites used the same final hashes above, sequentially. Before **each** app/test install,
`/tmp/opencode/xmouse-task-4.1/verified-install.py` freshly checked exact approved serial/AVD/API,
boot=1, fingerprint and both SDK variables. `--clean` was used only for the app install and only clears
the approved disposable app/test package data. The runner always stops that AVD in `finally`.

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-4.1/avd-api26
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task41_api26 -port 5584 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task51-final-api26-emulator.log 2>&1
# Second shell with the same SDK/ADB/PATH exports:
python3 /tmp/opencode/xmouse-task51-run-device.py 26
# Only after API 26 stopped, API 35 uses ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd:
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task51-final-api35-emulator.log 2>&1
python3 /tmp/opencode/xmouse-task51-run-device.py 35
```

The retained runner invokes the helper separately for both APKs, then the complete runner:

```sh
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task51-final-api26-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task51-final-api26-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell am instrument --user 0 -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 emu kill
# API 35: emulator-5580 / xmouse_task31 / API 35, with separate fresh checks before each install.
```

| Approved AVD / serial | Full result | Duration |
| --- | --- | ---: |
| API 26 `xmouse_task41_api26` / `emulator-5584` | **106 discovered; 104 passed / 2 assumption skips / 0 failures** | 111.483s |
| API 35 `xmouse_task31` / `emulator-5580` | **106 discovered; 104 passed / 2 assumption skips / 0 failures** | 123.199s |

The two status **-4** skips on each API are exactly the unchanged opt-in system backup-transport methods
`CredentialMetadataBackupTransportAndroidTest.verifyMetadataAndReentryAfterSystemTransportRestore` and
`stageSyntheticPackageDataForSystemBackup`. Runner `OK (106 tests)` includes these skips and is not
reported as 106 passes. Both new controller/main-Looper methods and the new captured-repository-selection
method passed on each API; no crypto/import, trust/replacement, transport/deadline, authentication,
credential/migration/privacy or legacy UI regression was excluded.

Fresh app **and** test install identities record these fingerprints, boot=1 and the expected API:

- API 26: `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`
- API 35: `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`

Per-device evidence prefix: `/tmp/opencode/xmouse-task51-final-api{26,35}-`, with `instrumentation.log`,
`summary.json`, `run.log`, `app-identity.json`, `test-identity.json`, `app-install.log`, `test-install.log`
and `emulator.log`. Summary APK hashes match the final build. After both runs, `adb -P 5038 devices -l`
listed **no devices**, and console ports **5580 / 5584** were both closed. No checks remain running.
No personal device, user AVD, real user SSH host, system sshd or OS account was used. API 37/physical
device/LAN/five-minute app switching and the opt-in system-backup cycle are **not run**, not passed.

### Verification iterations

The first uncached build found the existing domain phase-enumeration test needed the two newly
introduced PreparingCredentials/Closing values; its original all-phases and only-connected-is-ready
assertions were expanded, not removed. The second build passed its JVM run but hit an internal lint
Kotlin FIR/experimental-detector crash. No lint detector was disabled or suppressed; the final
stable-source uncached rerun with full lint stack traces passed all lint analysis, JVM and APK tasks
without reproducing that tooling failure. One earlier focused build also caught a test using the
JVM-only `TrustFileIo` on Android; it now uses the existing `AndroidSecretFileIo`. Earlier results are not
substituted for the final APK/device evidence.

## Exact task-5.1 manifest

```text
app/src/main/java/com/stripe1/xmouse/connection/ConnectionFailure.kt
app/src/main/java/com/stripe1/xmouse/connection/ConnectionState.kt
app/src/main/java/com/stripe1/xmouse/secrets/AuthenticationSelection.kt
app/src/main/java/com/stripe1/xmouse/secrets/CredentialRepository.kt
app/src/main/java/com/stripe1/xmouse/session/AndroidSessionOwner.kt
app/src/main/java/com/stripe1/xmouse/session/SessionController.kt
app/src/main/java/com/stripe1/xmouse/session/SessionEventQueue.kt
app/src/main/java/com/stripe1/xmouse/session/SessionHost.kt
app/src/main/java/com/stripe1/xmouse/session/SessionInteractions.kt
app/src/main/java/com/stripe1/xmouse/session/SshSessionAttemptFactory.kt
app/src/main/java/com/stripe1/xmouse/transport/SshTransport.java
app/src/main/java/com/stripe1/xmouse/trust/HostTrustPolicy.kt
app/src/test/java/com/stripe1/xmouse/connection/ConnectionStateTest.kt
app/src/test/java/com/stripe1/xmouse/session/SessionControllerTest.kt
app/src/test/java/com/stripe1/xmouse/session/SessionPipelineIntegrationTest.kt
app/src/test/java/com/stripe1/xmouse/session/SshSessionAttemptFactoryTest.kt
app/src/testFixtures/java/com/stripe1/xmouse/session/SessionFixtures.kt
app/src/testFixtures/java/com/stripe1/xmouse/transport/RecordingSshBackend.kt
app/src/androidTest/java/com/stripe1/xmouse/SessionControllerAndroidTest.kt
app/src/androidTest/java/com/stripe1/xmouse/secrets/CredentialRepositoryAndroidTest.kt
docs/verification/audit-ssh-transport.py
docs/verification/session-controller.md
openspec/changes/modernize-xmouse/tasks.md
```

The shared recording backend now honors abort during a late connect, like the production JSch socket
owner, so cancellation fixtures cannot revive their own synthetic session after closure. All its old
regressions ran unchanged. No unrelated file, pre-existing untracked tooling/OpenSpec config/archive/
spec placeholder was removed, staged or committed. **Stop after 5.1 for parent review.**
