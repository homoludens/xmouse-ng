# Device-key loss / credential re-entry boundary (task 3.2)

Verified **2026-10-20**, baseline `eafb916`. Task **3.2** is complete at the protected-read /
presentation boundary (11/67 total). No database schema, credential migration, production SSH,
legacy preference save path, or existing screen was changed. Tasks **3.3–3.5** remain unchecked.

## Implemented contract and integration handoff

`CredentialAccess` is a concrete, Java-callable consumer of `SecretStore.read`, not just another
error enum. Call `read(id, presentation, completion)` off the main thread:

| Store outcome | Observable action |
| --- | --- |
| Readable bytes | `Available(bytes, STORED)`; caller owns/clears the bytes |
| Missing record (`null`) | `Missing`; no device-key-loss request |
| `KEY_UNAVAILABLE` | Actively calls `CredentialReentryHandler.requestReentry(request)`; no credential completion until explicit entry/cancellation |
| Invalid/tampered record | `Failed(INVALID_RECORD)`; no re-entry/reset request |
| Unsupported version | `Failed(UNSUPPORTED_VERSION)`; no re-entry/reset request |
| Storage/encryption failure | `Failed` with the original safe reason; no re-entry/reset request |

The presentation receives the opaque credential reference and `KEY_UNAVAILABLE` reason. It may
present the associated password/passphrase entry or private-key re-import using non-secret context
owned by its caller. It must never automatically substitute an empty/default credential or legacy
plaintext. This contract is suitable for the later protected repository without migrating legacy
rows or rewiring SSH prematurely.

- `request.cancel()` completes with `Cancelled`: stop the requesting authentication attempt, retaining
  authentication choices and configuration. The presentation/attempt owner must cancel on user
  dismissal, leaving/recreation, no available UI, or its attempt timeout; this boundary does not own
  an Activity, a timer, or a session. `read` also returns the request so the attempt owner can cancel it.
- `request.useForThisAttempt(bytes)` means explicit **use for this attempt only**, not Save/Reset.
  Accepted input is copied into `Available(bytes, REENTERED_FOR_ATTEMPT)`; presentation and consumer
  separately own/clear their buffers. JVM memory clearing remains best effort.
- Completion/cancellation is one-shot and thread-safe. Late/repeated replies return false and cannot
  revive the attempt. Callbacks run on the thread providing the result; callers marshal to UI/SSH
  executors as appropriate. No worker waits synchronously for UI.
- Neither choice writes anything, deletes/overwrites ciphertext, regenerates/deletes a device key,
  clears preferences, or touches a database/layout. There is **no durable replacement/reset API**.
  Consequently a later read of an unavailable credential requests re-entry again. A future durable
  replacement must have separate deliberate consent scoped to encrypted credential state, not reuse
  this one-attempt consent as permission to discard data.

**Presentation contract versus end-user UI:** production `CredentialAccess` issues the request to an
injected presentation handler. The instrumentation's recording handler verifies that actual call and
exercises explicit entry/cancellation. No existing screen currently invokes it; end-user prompts,
credential-reference mapping, and authentication integration await task 3.5 (document import awaits
4.7). These tests are **not** claims of a visible dialog or of encrypted production authentication.
The existing legacy plaintext sources remain unchanged and are not claimed protected by this task.

## Commands and results

All Android commands used both SDK environment variables; no machine-specific project configuration
was added:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Final run: **BUILD SUCCESSFUL in 2m39s; 83/83 tasks executed**. Unit XML: **155 tests / 16 suites,
zero failures/errors/skips**, including **10 new tests** (9 Kotlin, 1 Java interop). They cover stored
and missing credentials, active/deferred key-loss requests, all other failure reasons, explicit
memory-only entry, cancellation, late replies, oversized input, concurrent cancellation/entry, safe
result diagnostics, and consumer exceptions not being reclassified as key loss. Lint: **0 errors /
155 existing warnings**, no findings in the new sources and no suppression/baseline added. An initial
test-only `UseKtx` warning was fixed with `SharedPreferences.edit(commit = true)` and rerun, not
suppressed. Existing Jetifier, Java deprecation, native-strip, and test-JVM CDS warnings remain.

### Actual Android instrumentation

Reused **only** the disposable API 35 AVD from task 3.1. Before installation, verified the serial's
AVD name was `xmouse_task31` and boot completion was `1`; no physical/personal device or user AVD was
used, created, or erased.

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048
# After boot, from a separate shell with both SDK exports:
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument -w \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu kill
```

**OK (12 tests), 3.136s**: all 8 existing tests plus 4 `CredentialAccessAndroidTest` cases:

1. Delete a real UUID-scoped AndroidKeyStore test key after saving ciphertext, reopen through the
   production factory, and verify `CredentialAccess` actually calls the presentation handler with a
   re-entry request. Cancel and reject a late response; no replacement key or credential is produced.
2. Explicitly supply one-attempt replacement bytes, verify their result/origin, clear returned bytes,
   and reopen: another request is required; the old ciphertext remains byte-identical and no key is
   regenerated.
3. Inject Android's `KeyPermanentlyInvalidatedException` into the cipher's key-source boundary; verify
   the same request/cancellation and preservation behavior. The original real test key remains usable.
   This is deterministic **exception-path coverage**, not hardware/biometric invalidation evidence.
4. Alter a real encrypted record's tag/version and read an absent reference: preserve the distinct
   invalid-record/unsupported-version/missing outcomes and never issue the key-loss re-entry request.

Every case loads the existing synthetic `contactsManager-v2.sql` into isolated real Android SQLite
storage and reopens through production `DatabaseHandler` at version 2. Before/after comparisons include
**every host/script column and value**, IDs 7/42 and 13/29, aliases, endpoints, ports, usernames, scripts,
timestamps, and the existing synthetic plaintext fields. The existing XML preference fixture is
loaded into UUID-named real SharedPreferences; selected host, global key-auth choice/path/passphrase
and all values remain identical. Active plus three synthetic legacy-format layout files remain
byte-identical. Only test-owned UUID aliases, preferences, and directories are cleaned up.

Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
The disposable emulator was shut down after verification. **Not run/claimed:** API 24/37 devices,
hardware-backed/biometric invalidation, end-user UI/recreation/deadline integration, durable credential
replacement, migration, or production SSH integration.

Evidence: `/tmp/opencode/xmouse-task-3.2/{full-verification.log,full-verification-final.log,
instrumentation-final.log,emulator.log}` and unit/lint XML under `app/build/`.
`git diff --check` and new-file whitespace checks pass. `openspec validate modernize-xmouse --strict`
reports the change valid. No staging or commit performed.
