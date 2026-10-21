# Validated document-based private-key import (task 4.7)

Baseline **`c1f7d9d`**, verified **2026-10-20**. Dependency-driven execution order within the
user-approved change: **4.7 before 3.5**. The parent selected this existing-task prerequisite because
3.5 needs a producer of validated protected private-key bytes. Task IDs/scopes are unchanged. The
original task-4.7 verification below is historical; the task-4.1 platform follow-up is recorded near
the end of this document.
The paused, untracked `docs/verification/credential-repository.md` is preserved unchanged. At this
original task-4.7 checkpoint, only **4.7** was completed: **14/67** total; task **3.5** and the other
pending tasks remained unchecked then.

**Current status (2026-10-21): 18/67 tasks complete; only 4.1 newly checked**, pending parent review/commit.
The user approved its promotion to **[high]** and bounded-parser integration. Current imports, saved-key
authentication and attempt-only re-entry use `BoundedSshKey`: BC parses/decrypts bounded input and emits
canonical unencrypted in-memory material before JSch identity loading. Stored source bytes/references
remain unchanged. Final verification: **210 JVM tests**, and **88 discovered / 86 passed / 2 opt-in
backup-transport skips / 0 failures** on each of API 26 and API 35. Full current limits, coverage and logs:
[`ssh-crypto.md`](ssh-crypto.md). The task-4.7 contract/results below remain historical evidence, not a
blanket guarantee for every format or arbitrary malformed input.

## Contract / task-3.5 handoff

- `AndroidPrivateKeys.open(preferences, secrets)` returns the Java-callable, blocking
  `PrivateKeyRepository`. The Context overload uses the existing default preferences and production
  `AndroidSecretStore`. Call key I/O/validation/import/read methods off the main thread.
- At the original task-4.7 baseline, `beginImport()` captured the current global key reference and legacy
  path. `importKey(attempt, stream, passphraseBytes)` owned/closed the stream, bounded the **entire** source
  to **1 MiB**, validated with then-pinned JSch **0.1.55**, unlocked encrypted input, and proved
  private-key usability by signing a fixed synthetic validation message. The covered unsupported,
  malformed, public-only, and wrong-passphrase cases returned before secret write. That historical
  result does not bound allocations derived from decrypted malformed plaintext and does not establish the
  same behavior for current JSch 2.28.8. It did not add new formats or negotiation algorithms.
- After validation, the original source bytes (not path text and not a decrypted temporary PEM) are
  encrypted under a **fresh opaque UUID-based `SecretId`**. A successful durable write and fresh exact
  read-back comparison precede one synchronous preferences commit setting
  `LegacyCredentialReferences.GLOBAL_PRIVATE_KEY` (`pref_private_key_credential_ref`) and removing
  `pref_addkeybutton` together. The source document/file is never deleted or modified.
- Publication rechecks the captured configuration under a process-wide lock. Cancellation and
  publication share an attempt lock; cancelled/stale work may leave an encrypted orphan but cannot
  publish its reference. Old ciphertext is never overwritten/deleted. Old reference/path recovery
  context is also retained in `pref_private_key_previous_ref` / `pref_private_key_previous_path`.
  No URI or persistable provider permission is retained; the encrypted application-private copy is
  sufficient after provider removal/revocation.
- A preferences `commit(false)` is an error, not success. The importer synchronously restores only
  its five reference/label/context fields, preserving unrelated preferences. Android's cache changes
  before disk: an uncertain commit or a second rollback failure cannot be claimed to guarantee the
  old pointer on disk. Atomic preferences persistence can contain the old state or the fully verified
  candidate plus previous recovery context; **both key records are retained**, never partial key bytes.
  Tests inject forward failure before disk, after disk and in cache only, with successful rollback.
  Physical power cuts and persistent failures of both forward commit and rollback are not claimed.
- `importLegacy(attempt, phrase)` uses exactly the same bounded validation/encryption/publication path.
  Readable, valid legacy keys can migrate; missing/unreadable/invalid paths retain the original
  configuration until an explicit successful document import. No broad permission workaround exists.
- `readForAuthentication(phrase)` returns caller-owned key bytes, recovering a readable legacy source
  first if no protected reference exists. An existing missing/invalid/unreadable protected reference
  **never** falls back to a raw path, empty key or password. New imports also refuse to bypass an
  unreadable existing record with a fresh ID. There is no Keystore reset, implicit key regeneration or
  durable replacement of unreadable ciphertext authorized by memory-only re-entry consent.
- `AndroidPrivateKeys.readConfiguredPassphrase(preferences, secrets)` is a temporary **read-only**,
  protected-reference-first bridge. It uses legacy UTF-8 text only when no protected reference exists;
  protected read failure never falls back. All supplied/returned byte buffers belong to the caller
  and are cleared best-effort. No exception retains provider/key-parser messages or causes; even
  provider close failures attached as suppressed exceptions are sanitized.

### Settings and temporary SSH consumer

`SettingsActivity` remains a legacy `PreferenceActivity`. Its controller uses platform
`startActivityForResult` / `onActivityResult` with `ACTION_OPEN_DOCUMENT`, `CATEGORY_OPENABLE`, `*/*`
and a read grant. No activity conversion or new dependency is needed. Only `content:` results are
accepted. Content open/read/validation/Keystore/prefs work runs on its single worker. A cancellation
signal cancels pending provider opens; attempt cancellation also closes an already-open source stream.
Unique outstanding request codes, one-shot
result handling and attempt cancellation on destruction reject duplicates/late results. Recreation
requires reselection rather than serializing a URI, key or passphrase into instance state.

Readable legacy context is recovered on the worker when Settings opens. Unreadable paths display
**“Saved key needs import. Tap to select it with the document picker.”** and retain the original path.
Summaries/labels are fixed non-secret descriptions, never raw paths, provider content or key material.
Invalid content, wrong passphrases, excessive size and inaccessible documents have safe user-facing
explanations when the importer returns a handled failure; this is not a guarantee against parser
resource-exhaustion errors.
For an encrypted key, set **Optional Key Passphrase before import**; the controller validates with that
configured choice. Import does not change the global auth boolean, retained passphrase, selected host,
passwords or any host/script/layout data.

`MyConnectionHandler.SshConnectTask` has only a small key-consumer adaptation: protected in-memory
bytes go to old JSch's `addIdentity(name, byte[], null, passphrase[])`; handled key/parser/store failures
are sanitized, buffers are cleared, identities are removed after the connection attempt, and key auth
does not silently fall back to passwords. This does not catch process-fatal `OutOfMemoryError` or prove
resource-bounded parsing. Password auth's existing branch is unchanged. No network
transport/session/trust/negotiation rewrite was made. The legacy static filename/passphrase fields
are no longer consumed for key loading; **3.5 must remove/rewire their obsolete reads** as part of its
complete repository integration.

Task **3.5** must still centralize every host/default password and passphrase writer, retire persistent
framework secret preferences, wire `CredentialAccess`'s explicit one-attempt re-entry, and gate startup
migration before exposing old writers. Reuse this key importer rather than encrypting path text or
adding path-based JSch loading. Its temporary protected-first passphrase read is not a passphrase-save
repository. Do not start plaintext cleanup while old readers/writers remain. Tasks **4.1/4.2/4.5**
still own the dependency/transport/modern negotiation work; **4.8** still owns obsolete manifest and
other-screen permission cleanup. Existing broad storage declarations and unrelated requests were not
removed. Imported key bytes already live only in `noBackupFilesDir/secrets`; complete legacy credential
backup exclusions remain **3.6**. This intermediate build is not advertised as fully protecting every
active password/passphrase or backup.

## Reproduction

All Android commands used both SDK variables. No machine-specific paths were added to build files.

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest

export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048
# Separate shell with both SDK exports. Check name/boot immediately BEFORE EACH installation:
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu avd name
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell getprop sys.boot_completed
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
# Repeat name/boot checks before the test APK installation:
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument -w \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu kill
```

Only the existing disposable **API 35** AVD at that exact path/name was used. No personal/physical
device or other AVD was touched. Instrumentation uses generated RSA-1024 (test fixtures only) and
ECDSA-256 identities, never a user key or a network/remote command. A real `DocumentsProvider` in the
**test APK only** serves in-memory synthetic bytes through descriptor pipes. Its separately scoped
fixture-control provider grants/revokes individual document URIs; neither provider ships in the app.
Actual Settings intent/result tests intercept the system-picker launch with an instrumentation monitor:
they verify the actual Activity dispatch and provider import, **not manual navigation inside DocumentsUI**.
Both broad storage permissions remain denied in the successful Settings test.

## Verification results

- Full unit suite: **184 tests / 18 suites**, zero failures/errors/skips, including **16 new JVM cases**.
  RSA/ECDSA plain/encrypted identities sign successfully after source removal; invalid/public-only
  content, missing/wrong passphrase, exact-limit/oversize streams, read/close failures, cancellation,
  stale results, secret-write/read-back/reference failures and unreadable records/paths are covered.
- Actual Android suite: **52 tests passed**, including all **39 existing** and **13 new** cases. Real
  AndroidKeyStore, private encrypted files and SharedPreferences are used, not just store doubles.
  New cases cover provider removal plus read-grant revocation, four RSA/ECDSA encryption combinations,
  invalid/oversized/inaccessible provider sources, readable/unreadable legacy recovery, passphrase
  rejection, real pre-rename failure, uncertain write and failed/missing/mismatched read-back,
  preferences commit(false) modes, device-key loss without reset, cancelled/superseded publication,
  protected passphrase precedence, and actual Settings success/cancel/invalid/recreation/late-result
  handling. JSch signs with reopened imported bytes; **no SSH server authentication is claimed**.
- Final full Gradle run: **BUILD SUCCESSFUL in 2m23s, all 83 tasks executed**, without build cache.
  App/test APKs build. Lint: **0 errors/fatal, 154 existing warnings**, none in new sources; no
  suppression/baseline added. The obsolete Settings permission-request finding disappeared.
  This original task-4.7 APK metadata was application ID `com.stripe1.xmouse`, version **25 / 2.0.27**,
  API **24 / 37 / 37**; the current API 26 floor is documented in the task-4.1 follow-up below.
- Final actual instrumentation at that verification: **OK (52 tests), 18.876s**, zero failures. The unchanged standalone
  version-2 fixture verification also passes **4/4** cases. Fingerprint:
  `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
  Completed tests clean up their UUID-owned fixtures and restore the UI tests' prior default preferences
  and pre-existing key artifacts. The disposable emulator was stopped after verification.

Early iterations fixed test-harness faults, not weakened assertions: a signature-only provider permission
cannot be assumed held by shell; a provider's separate test-APK process needs standalone platform Java,
not target-only Kotlin libraries; and observing the preferences cache is not a worker/UI completion
barrier. The final suite waits for worker completion before asserting the resulting summary. An initial
build command timed out at two minutes and was rerun with a sufficient timeout. A streaming adb install
failed during startup; verified-name/boot installations using `--no-streaming` succeeded.

At the original task-4.7 verification, not run/claimed: API 24/37 device coverage, hardware-backed
Keystore/biometric invalidation, physical power-cut durability, manual DocumentsUI interaction, modern
OpenSSH/RSA SHA-2 authentication, complete password/passphrase integration, full backup extraction, or
later-task permission cleanup.

## Historical task-4.1 platform and fixture-isolation follow-up (2026-10-20)

Status at that review was **17/67 tasks complete; 4.1 unchecked**. The API 24/Android 7 diagnostic
exercised four independent RSA/ECDSA plain/encrypted fixtures: RSA passed, while ECDSA failed during JSch
signing because API 24 has no default `AlgorithmParameters.EC` service. The user-approved API 26/Android
8 minimum stands: the unchanged min-24 APK passed the focused API 26 crypto/import gate, and the current
API 26 and API 35 full suites later passed on the updated APKs. This is a floor amendment, not an API 24
fix.

A review of the first final API 26 full run (**84 tests, 38 failures**) found that several test-only
`ContextWrapper` fixtures changed `getDatabasePath()` but inherited `openOrCreateDatabase()`. On API 26,
`SQLiteOpenHelper` therefore opened the base application's shared database instead of the separately
seeded fixture. Credential migration/repository/backup fixtures now use the established two-overload
isolated-context pattern and assert the actual `SQLiteDatabase.path`; restore destinations use the same
wrapper. This supersedes the earlier 38-failure result as a fixture-isolation diagnosis, not as a passing
test run.

After clearing only the disposable API 26 AVD's app/test data, a review run on instrumentation APK hash
`29ddd076c9e0bc258e7c62eee90c0f11d96e0f4c5bd0c15a38c95497c29784df` had **84 tests and 1 failure**. The
test `invalidPassphraseLeavesExistingKeyAndGlobalPassphraseChoiceUntouched` reached JSch
`KeyPairRSA.parse` and threw `OutOfMemoryError` requesting **1,374,342,352 bytes** while
`PrivateKeyRepository.validate()` was executing `key.decrypt()`. The stack is at lines 1040–1049 of
`/tmp/opencode/xmouse-task-4.1/review-api26-instrumentation.log`. The method generates synthetic
encrypted RSA input, but the exact bytes from the failed randomized attempt were not retained; this is
not a deterministic reproducer. A focused retest passed.

Parent inspection of upstream JSch **2.28.8** explains the mechanism: `KeyPairRSA.java`'s `parseASN1DER`
branch decodes a length from decrypted plaintext and allocates `new byte[length]` before checking that
length fits the remaining plaintext via `System.arraycopy`. The 1 MiB input-file cap does not bound this
derived allocation. Wrong-passphrase decryption can produce malformed plaintext that reaches this
length handling.

A fresh no-cache build produced the current instrumentation APK. The latest clean full API 26 run passed
**84 tests, zero failures in 65.6s** (two opt-in backup-transport cases assumption-skipped); the latest
full API 35 run passed **84/84 in 60.22s**. No parser or crypto production code changed between these
runs. Those passes did not resolve the unchecked allocation, establish API-26 specificity, or show that
raising minSdk to 28 would fix it. The entire invalid-import contract was therefore not treated as safe
at that checkpoint. No key bytes or credentials were logged.

### Approved task-4.1 hardening and verification (2026-10-21)

The user approved promotion of 4.1 to **[high]** and implementation with `openai/gpt-6.1-sol#xhigh`.
`BoundedSshKey` now uses BC 1.86 parsing/decryption with explicit source, binary, DER depth/node/length,
key-parameter and KDF limits. It gives JSch canonical unencrypted temporary output; no raw imported,
decrypted, saved or attempt-only structure bypasses that boundary. `PrivateKeyRepository` keeps original
encrypted-at-rest bytes and reference publication unchanged. Later wrong-passphrase reads are checked
again. Repository authentication rejects invalid saved and one-attempt keys before returning material,
and the final legacy SSH handoff independently uses the boundary. No lost device key is regenerated.

Supported RSA/ECDSA traditional PEM plain/encrypted fixtures still sign after source removal. The 1 MiB
source cap is complemented by a 16 KiB decoded-structure cap, bounded DER and SSH strings, 1024 raw
passphrase bytes, and explicit cipher/KDF work limits detailed in `ssh-crypto.md`. Unsupported formats
fail specifically rather than entering JSch's unsafe parser. Canonical output is never persisted;
temporary arrays are cleared best-effort, with no claim to wipe immutable or provider/VM copies.

Deterministic tiny malformed DER and valid-padding encrypted-malformed fixtures verify rejection before
JSch loading without allocating the declared length. A separate test confirms decryption reaches the
malformed DER; fixed external keys also test real wrong passphrases. These are synthetic regressions for
the mechanism, not the missing exact original randomized PEM. Previous usable keys/references/auth
choices survive failures, including saved-record reads and attempt-only re-entry after device-key loss.

The final uncached JVM suite passed **210/210**; lint reported **0 errors / 144 warnings**; both min-26 /
target-37 APKs built. Full final API 26 and API 35 instrumentation each discovered **88 tests: 86 passed,
2 existing opt-in backup-transport assumption skips, 0 failures**. These results apply to the exact final
APK hashes recorded in `ssh-crypto.md`. The fix is the maintained bounded boundary, not the floor
amendment, an OOM catch, or randomized reruns. Only task 4.1 was newly checked; no staging/commit occurred.

This later evidence supersedes the original run's “not run” platform coverage only for the tests and API
levels stated here; API 24 ECDSA remains unsupported, and no API 37 device run is claimed. Exact final
API 26/API 35 logs, limits and device/APK identity evidence are in [`ssh-crypto.md`](ssh-crypto.md) and
`/tmp/opencode/xmouse-task-4.1/`. Both approved disposable AVDs were stopped after verification.

Final evidence: `/tmp/opencode/xmouse-4.7-{final-build.log,final-instrumentation.log,device.txt,
app-badging.txt,emulator.log}`; JVM/lint XML under `app/build/`. Earlier iteration logs use the same
`/tmp/opencode/xmouse-4.7-` prefix. `git diff --check`, new-file whitespace checks and
`openspec validate modernize-xmouse --strict` pass. No staging or commit was performed.
