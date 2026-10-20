# Protected credential repository integration (task 3.5)

Final verification baseline **`6bdf78d`**, verified **2026-10-20**. The parent metadata reclassification
is preserved; **3.5 remains `[high]`**. The prior dependency pause was resolved by the separate,
approved **4.7** commit **`cb98d4d`**; this task reuses that importer. Only **3.5** is completed here:
**15/67** total. No staging/commit, delegation, dependency change, or other task completion occurred.

## Production contract

- `AndroidCredentials.get(context)` owns one application-context database, store and serialized
  worker for the process. It retains no Activity/view/plaintext cache. `execute` dispatches blocking
  work; repository I/O explicitly rejects the main thread. The Activities own presentation only.
- `prepare()` disables writes, authenticates **all existing published references** first, then runs
  the restartable 3.4 migration before enabling saves. An existing missing/corrupt/unavailable record
  cannot be recreated from unexpected leftover plaintext. Unreferenced encrypted preparations still
  resume through 3.4's durable-write/read-back checkpoints. Failure leaves writes disabled and data
  intact; screen entry permits a deliberate retry. Non-secret metadata/settings/scripts remain usable.
- All production secret readers/writers were retired together before enabling migration. Non-secret
  preference controls may remain visible while migration runs: they cannot initialize/persist a secret.
  There is no legacy password/passphrase fallback, including when a protected reference is invalid.
  Only genuinely absent optional credentials yield empty bytes; storage failures never do.
- Every new host/default-password/passphrase save writes a **fresh opaque UUID reference**, requires
  successful durable encrypted storage and fresh exact read-back, then publishes the reference.
  Host insertion/update publishes `Password = NULL` and its reference in a SQLite transaction. Edits
  require the expected ID/reference pair, so a stale dialog or reused SQLite ID cannot overwrite a
  different host. Existing ciphertext is retained, not overwritten/deleted.
- Preference publication uses one synchronous commit for reference + legacy-key removal, checking
  its boolean result. `commit(false)` is reported as failure and disables writes, even if Android's
  cache/disk already contains the candidate. Both old and verified new ciphertext remain; the pointer
  can be old or new after an uncertain commit. No false success or guaranteed rollback is claimed.
- The global private-key importer shares this worker/gate. Its approved bounded validation/encryption
  and recovery behavior is unchanged. `AndroidPrivateKeys.readConfiguredPassphrase` is now protected
  **only**: legacy text requires migration, never read-through. An existing bad key reference cannot
  fall back to a raw path. Readable legacy keys go through the validated importer; unreadable paths
  retain their context and explicit document re-import guidance. `pref_usekeyauth` stays global.

### Actual application paths

| Path | Integrated behavior |
| --- | --- |
| `MainActivity.getPreferences` / drawer population | Read non-secret columns/preferences only. Removed static password/passphrase/key-path fields. |
| Host navigation | Persist selected host ID, not its password. Independent fallback endpoint/password and global auth choice are not overwritten. |
| `ListEditActivity` | Load metadata through the repository; asynchronously load protected password only into the open editor. Add/edit/delete use the repository. Direct Activity launch no longer depends on `MainActivity.db`. Script IDs, CRUD and ascending display order are retained. |
| `HostItem` / adapter | No plaintext password field or password in list/adapter state; only a metadata/reference snapshot. |
| `SettingsActivity` passphrase | Ordinary non-persistent `Preference` launches an asynchronous protected editor. No framework secret default initialization, automatic persistence, secret summary, or saved dialog state. Password inputs disable hierarchy saving/autofill. |
| Retained `ssh_new_conn.xml` | Its password preference is also non-persistent and has no plaintext default, even if inflated later. |
| `PrivateKeySettingsController` | Existing document picker/import now runs behind the same application migration/write gate and reads only protected passphrase bytes. |
| `MyConnectionHandler` | Asynchronously requests attempt-local credentials from the repository, then supplies password/key/passphrase byte arrays to existing JSch. No legacy secret static or raw-path identity load. Buffers/identities are cleared after use; changed authentication failures use fixed safe diagnostics. |

### Explicit re-entry / cancellation

`CredentialAccess` is exercised by production authentication, not an unused facade. `KEY_UNAVAILABLE`
requests explicit **Use once** password/passphrase entry or **Choose for this attempt** key selection.
These choices do not save, replace references, reset Keystore or overwrite unreadable ciphertext.
Private-key document re-entry reuses the importer's bounded/closed/cancellable reader **without
publication**; JSch validates/unlocks the supplied bytes before SSH authentication. A bad document
cannot become the saved key. The normal durable Settings importer still refuses unreadable old state.

Completion continuations return to the application worker; none blocks waiting for UI. Dismissal,
leaving/recreation, explicit disconnect and a **60-second pending re-entry deadline** cancel requests.
The expected document-picker transition may complete its one-shot request; late/duplicate responses
cannot revive cancellation. Provider open/read cancellation closes the source. Credentials and the
selected endpoint/auth choice are captured for the attempt; stale UI results are rejected and cleared.
Byte clearing is best effort; JVM strings/UI input cannot promise forensic memory erasure.

### Parent review fixes

- Each re-entry request owns its dialog, provider cancellation and deadline. Completion, cancellation,
  picker read failure and expiry remove its bookkeeping immediately when handled, without waiting
  for Activity stop. Cleanup is idempotent and identity-scoped: an old deadline/read callback cannot
  cancel a later request or dismiss its dialog. Explicit Choose is recorded before launching, so a
  fast Activity result cannot be cancelled by the chooser dialog's later dismissal callback.
- Re-entry picker codes wrap **0x3fff → 0x3000**, staying within lower 16 bits and disjoint from the
  durable importer's **0x4000+** range. Issued codes are retired for the Activity owner's lifetime,
  including cancelled/expired pickers, to reject late/duplicate responses. After all 4096 codes are
  exhausted in one owner, selection cancels safely with guidance to reopen the screen, rather than
  reuse an ambiguous code or send an illegal request code. No stored credentials change.
- New host/script dialogs have **Save / Cancel** only. Existing records retain **Save / Delete /
  Cancel**; persistence, authentication choices and cancellation semantics are unchanged.

## Verification

- Final post-review Gradle run: **BUILD SUCCESSFUL, 3m22s, 83/83 tasks executed**, no build cache.
  **189 JVM tests / 20 suites**, zero failures/errors/skips. Two new tests verify attempt-buffer
  cleanup and removal of plaintext password state from `HostItem`; three further allocator tests
  cover the wrap boundary, retired-code skipping and safe exhaustion/new-owner allocation.
- Lint: **0 errors/fatal, 139 warnings**, no new-source findings or added suppression/baseline.
  App and instrumentation APKs build; identity remains `com.stripe1.xmouse`, version **25 / 2.0.27**,
  minimum/target/compile **24 / 37 / 37**. Existing deprecation/Jetifier/native-strip/CDS notices remain.
- Final post-review disposable API 35 instrumentation: **OK (70 tests), 54.325s**. All **52 previous**
  cases plus **18 repository/application integration cases** passed. Existing v2 fixture checks also
  pass **4/4**. Earlier 186-JVM/68-Android results are superseded, not reused as current proof.
- Real Android SQLite/SharedPreferences/AndroidKeyStore cover v2 host and default/global preference
  migration; every original host/script ID and non-secret column; selected-host and global auth choice;
  independent fallback; host add/edit/reopen/ID reuse/stale dialogs; protected preference replacement;
  failed migration/read-back/uncertain preferences publication; real device-key deletion; missing
  protected records with leftover legacy text; and explicit one-attempt entry/cancellation without reset.
- Actual `ListEditActivity` exercises protected prefill, edit, add, cancel and recreation/reopen.
  Actual Settings verifies non-persistent framework types/default inflation, secure editor save/cancel/
  recreation, unchanged auth/selection, and absence of secret values in the preferences XML.
  Migration-failure UI tests retain host metadata and non-secret settings with host Save disabled.
- Actual Main preferences/navigation persist selection without rewriting fallback credentials; a blank
  synthetic endpoint deliberately prevents network work. Actual Main key-loss presentation completes
  passphrase + system-document one-attempt re-entry against the test provider, keeping ciphertext,
  reference and lost-key state unchanged. Provider picker launches/results are instrumented, not manual
  DocumentsUI navigation. Reopened protected key bytes also sign through existing JSch in memory.
- Existing four host/script dialog binding unit cases retain layout/metadata checks; protected password
  prefill coverage moved to the actual Android editor test rather than a plaintext static fixture.
  Existing key-import UI assertions now require the protected empty passphrase instead of plaintext;
  worker barriers use the shared owner. No preservation/authentication assertion was simply deleted.
- Review regressions assert one Cancel for new host/script dialogs and three distinct roles for edits.
  Actual Android tests assert request/deadline/dialog bookkeeping is empty after successful document
  re-entry, Cancel, provider read failure and expiry. Captured old deadlines are invoked while later
  prompts/pickers remain pending; stale/duplicate results cannot revive them. Expiry logic is tested
  by invoking the registered production deadline, not by claiming a wall-clock 60-second timing test.

Tests use UUID-owned databases, encrypted directories and Keystore aliases. UI tests temporarily inject
the real production repository with test-owned SQLite/store and actual default SharedPreferences,
then restore the previous owner/preferences. Main's non-secret helper is pointed at that fixture for
navigation assertions. This isolates fixtures without replacing SQLite, SharedPreferences, crypto,
repository or Activity code with mocks. It is **not** an upgrade-install or real-server SSH test.

Initial iterations caught a test's incorrect null-summary expectation (the existing fixed guidance is
intentional) and the real missing-reference/leftover-text recovery gap. The final preflight fixes the
latter without changing 3.4's standalone checkpoint tests. All final suites were rerun successfully.
The review rerun also caught the fast-result/dismissal race, which was fixed. Button/cancel tests now
wait for Android's asynchronous dialog event dispatch before asserting cleanup; no assertion was
removed or weakened. The interrupted review build was not treated as a passed verification run.
The final evidence audit found a test-only `Uri.parse` KTX style warning; it was fixed with `toUri`,
then the full uncached build, lint, both APKs and all 70 Android tests were rerun. The final report's
139 warnings do not include that new-source finding. No runtime/test source changed after that run.

### Reproduction / evidence

Every Android command used both SDK variables; no machine path was added to build configuration:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 docs/verification/verify-v2-storage-fixtures.py
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048
# Separate shell with both SDK exports. Repeat these name/boot checks BEFORE EACH APK installation:
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu avd name
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell getprop sys.boot_completed
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
# Repeat name/boot checks, then install the test APK:
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument -w \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu kill
```

Only `/tmp/opencode/xmouse-task-3.1/avd/xmouse_task31.avd` was used. Serial **emulator-5580** reported
**xmouse_task31 / boot 1 before every install**. Fingerprint:
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
No personal/physical device or other AVD was touched; the disposable emulator was shut down.

Final evidence: `/tmp/opencode/xmouse-3.5-review-{final-build.log,final-instrumentation.log,
device-final.txt,app-badging.txt,emulator.log}` and JVM/lint XML under `app/build/`. Earlier review
iteration logs use the same prefix; pre-review integration logs use `/tmp/opencode/xmouse-3.5-`.
Source audit of production `getString`/`putString` secret keys, host `Password` writes and
`addIdentity` calls found no active plaintext secret writer/legacy secret reader outside migration.
The remaining Settings host-query match is inside its pre-existing commented-out block.

## Remaining boundaries / handoff

- **3.6:** backup exclusions/data-extraction rules and SQLite checkpoint/compaction remain unchecked.
  Logical NULL/removal does not erase legacy database pages/journals/WAL or backup residuals. Old
  encrypted records/orphans are intentionally retained. No forensic flash erasure is claimed.
- **3.7:** legacy typed-content/command logging still needs its dedicated audit/removal. Changed
  credential paths do not emit secret values or parser/provider messages; this is not a whole-app
  logging-security claim.
- **4.1–4.6, 4.8, 5.x:** library/provider upgrades, host trust, transport extraction/deadlines, permission
  cleanup and session lifecycle remain future work. Existing Activity/AsyncTask ownership and insecure
  host-trust setup are not fixed or endorsed by 3.5. Pending credential prompts have a deadline, not a
  new guarantee that the old SSH handshake/session is bounded or survives Activity destruction.
- Not run/claimed: API 24/37 devices, hardware/biometric invalidation, physical power cuts, manual system
  picker interaction, real-server authentication, full backup extraction or whole-capability security.

## Exact changed files

- `app/src/main/java/com/stripe1/xmouse/secrets/CredentialRepository.kt` (new)
- `app/src/main/java/com/stripe1/xmouse/secrets/CredentialUi.kt` (new)
- `app/src/main/java/com/stripe1/xmouse/secrets/ReentryPickerCodes.kt` (new)
- `app/src/main/java/com/stripe1/xmouse/secrets/AndroidCredentialMigration.kt`
- `app/src/main/java/com/stripe1/xmouse/secrets/AndroidSecretStore.kt`
- `app/src/main/java/com/stripe1/xmouse/secrets/AndroidPrivateKeys.kt`
- `app/src/main/java/com/stripe1/xmouse/secrets/PrivateKeyRepository.kt`
- `app/src/main/java/com/stripe1/xmouse/secrets/PrivateKeySettingsController.kt`
- `app/src/main/java/com/stripe1/xmouse/MainActivity.java`
- `app/src/main/java/com/stripe1/xmouse/ListEditActivity.java`
- `app/src/main/java/com/stripe1/xmouse/SettingsActivity.java`
- `app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java`
- `app/src/main/java/com/stripe1/xmouse/HostItem.java`
- `app/src/main/res/xml/pref_auth.xml`
- `app/src/main/res/xml/ssh_new_conn.xml`
- `app/src/test/java/com/stripe1/xmouse/secrets/AuthenticationMaterialTest.kt` (new)
- `app/src/test/java/com/stripe1/xmouse/secrets/ReentryPickerCodesTest.kt` (new)
- `app/src/test/java/com/stripe1/xmouse/ListEditActivityDialogTest.java`
- `app/src/androidTest/java/com/stripe1/xmouse/secrets/CredentialRepositoryAndroidTest.kt` (new)
- `app/src/androidTest/java/com/stripe1/xmouse/secrets/PrivateKeyImportAndroidTest.kt`
- `docs/verification/credential-repository.md` (replaces the untracked pause report)
- `openspec/changes/modernize-xmouse/tasks.md` (3.5 checkbox only)
