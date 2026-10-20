# Validated document-based private-key import (task 4.7)

Baseline **`c1f7d9d`**, verified **2026-10-20**. Dependency-driven execution order within the
user-approved change: **4.7 before 3.5**. The parent selected this existing-task prerequisite because
3.5 needs a producer of validated protected private-key bytes. Task IDs/scopes are unchanged.
The paused, untracked `docs/verification/credential-repository.md` is preserved unchanged.
Only **4.7** is completed here: **14/67** total. Task **3.5** and all other pending tasks stay unchecked.

## Contract / task-3.5 handoff

- `AndroidPrivateKeys.open(preferences, secrets)` returns the Java-callable, blocking
  `PrivateKeyRepository`. The Context overload uses the existing default preferences and production
  `AndroidSecretStore`. Call key I/O/validation/import/read methods off the main thread.
- `beginImport()` captures the current global key reference and legacy path. `importKey(attempt,
  stream, passphraseBytes)` owns/closes the stream, bounds the **entire** source to **1 MiB**, validates
  with the currently pinned JSch **0.1.55**, unlocks encrypted input, and proves private-key usability
  by signing a fixed synthetic validation message. Unsupported/malformed/public-only input or a wrong
  passphrase fails before any secret write. This does not add new formats or negotiation algorithms.
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
Invalid content, wrong passphrases, excessive size and inaccessible documents have safe explanations.
For an encrypted key, set **Optional Key Passphrase before import**; the controller validates with that
configured choice. Import does not change the global auth boolean, retained passphrase, selected host,
passwords or any host/script/layout data.

`MyConnectionHandler.SshConnectTask` has only a small key-consumer adaptation: protected in-memory
bytes go to old JSch's `addIdentity(name, byte[], null, passphrase[])`; key/parser/store failures are
safe messages, buffers are cleared, identities are removed after the connection attempt, and key auth
does not silently fall back to passwords. Password auth's existing branch is unchanged. No network
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
  APK metadata retains application ID `com.stripe1.xmouse`, version **25 / 2.0.27**, API **24 / 37 / 37**.
- Final actual instrumentation: **OK (52 tests), 18.876s**, zero failures. The unchanged standalone
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

Not run/claimed: API 24/37 device coverage, hardware-backed Keystore/biometric invalidation, physical
power-cut durability, manual DocumentsUI interaction, modern OpenSSH/RSA SHA-2 authentication, complete
password/passphrase integration, full backup extraction, or later-task permission cleanup.

Final evidence: `/tmp/opencode/xmouse-4.7-{final-build.log,final-instrumentation.log,device.txt,
app-badging.txt,emulator.log}`; JVM/lint XML under `app/build/`. Earlier iteration logs use the same
`/tmp/opencode/xmouse-4.7-` prefix. `git diff --check`, new-file whitespace checks and
`openspec validate modernize-xmouse --strict` pass. No staging or commit was performed.
