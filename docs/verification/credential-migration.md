# Restartable credential migration boundary (task 3.4)

## Contract / task-3.5 handoff

`AndroidCredentialMigration.run(DatabaseHandler, SharedPreferences, SecretStore)` is blocking and
Java-callable. Run off the main thread, with all credential writers quiesced, **before** exposing
legacy screens. It owns its SQLite transactions, not the helper's lifetime. Calls serialize in this
process; SharedPreferences does not support cross-process credential writers. Task 3.5 must centralize
all reads/writes before invoking it. **No production startup, Activity, SSH, or legacy save path calls
this boundary yet. This is an opt-in foundation, not protection of current users' startup data.**

The 3.5 wiring audit must include `MainActivity.getPreferences` and host navigation's `setting_pass`
copy, `ListEditActivity` host load/edit/add, the preference-framework `pref_key_passphrase` save, and
`SettingsActivity`/`MyConnectionHandler` global key-path consumption. Do not clear credentials while
any of those paths still expects plaintext or can write it back.

Schema remains **3**, with the existing explicit 2 → 3 chain unchanged. No journal/table/version or
host/script insertion is needed. Stable, non-secret mappings:

| Legacy source | Committed reference | Default preparation `SecretId` |
| --- | --- | --- |
| `contacts.Password`, identified by original integer `id` | `contacts.PasswordCredentialRef` | `migration_host_<lowercase Long.toHexString(id)>_password` |
| `setting_pass` fallback/default password | `setting_pass_credential_ref` | `migration_default_password` |
| `pref_key_passphrase` retained **global** passphrase | `pref_key_passphrase_credential_ref` | `migration_global_passphrase` |

Use `LegacyCredentialReferences` rather than reconstructing these strings. Existing valid references
are honored instead of replaced. The fallback password is independent of the selected host: do not
merge it with that host's password or rewrite `hostPreferenceList`, fallback endpoint/user/port,
aliases, timestamps, locator strings, scripts, or other preferences. Empty secrets are real empty
byte arrays; absent/SQL-NULL sources without references are left absent. Secret strings use UTF-8.

### Durable checkpoints, not an unsafe global "done" marker

Each source is independently restartable:

1. **Legacy state:** original plaintext remains; a reference may already exist.
2. **Prepared state:** encrypted record under its stable/reference ID, **plus original plaintext**.
   Authenticate an existing record and require exact bytes; conflicting, corrupt, unavailable, or
   invalid-reference state stops without overwriting either copy. Write even equal preparations
   through the durable secret-store API: an earlier rename followed by directory-fsync failure is
   not proof of durability. A failed write never authorizes cleanup.
3. **Verified state:** freshly read/authenticate the durable record and compare exact bytes in memory.
   A missing, mismatching, or failed read-back stops with plaintext intact. No migration-owned
   plaintext/hash journal, log, or plaintext temp file is written. Byte buffers are cleared best-effort; legacy Java strings
   cannot be reliably wiped from JVM memory.
4. **Committed state:** SQLite transaction sets that row's reference and SQL NULL password together,
   or one synchronous SharedPreferences `commit()` sets the reference and removes that plaintext key
   together. No unrelated fields are rewritten. Source snapshots are rechecked before cleanup; a
   changed source stops, not silently erased. SQLite errors roll back both fields.

Interruption can leave any mixture of these per-source states. Restart verifies cleared references
again, resumes retained plaintext, and uses the same IDs without inserting/duplicating hosts, scripts,
or encrypted records. A preferences commit returning false is failure, even if its memory cache or
disk has already changed: either durable outcome has plaintext OR verified ciphertext + reference.
Retries also re-verify and synchronously recommit cached reference-only preference states, reconciling
the uncertain memory/disk boundary. There is no separate preference checkpoint to get ahead of cleanup.

`SecretStoreException` retains its safe reason; `CredentialMigrationException` reports safe
invalid-reference/missing-replacement/conflict/source-change/storage reasons, never secret values or
provider messages. Stop migration on error and surface recovery. Do not silently select a default,
overwrite conflicting ciphertext, reset Keystore, or clear a sole source to make migration proceed.
Loss of a key/record **after** verified cleanup requires the existing `CredentialAccess` re-entry
contract; migration cannot guarantee permanent credential availability after later key/storage loss.

### Global key authentication / remaining boundaries

- `pref_usekeyauth` remains a **global** boolean, not a per-host choice; migration preserves it whether
  enabled or disabled, and migrates a retained global passphrase even while disabled.
- `pref_addkeybutton` remains the original raw path, untouched even when nonexistent/unreadable.
  No filesystem read is attempted, no broad permission is requested, and the path's UTF-8 text is
  **never** encrypted as private-key bytes. `Result.legacyKeyImportRequired` conservatively flags a
  nonempty raw path; it does not claim a validated imported key or replace an end-user re-import prompt.
- `LegacyCredentialReferences.GLOBAL_PRIVATE_KEY` reserves `pref_private_key_credential_ref` for the
  future validated import/repository boundary. This task never sets it or asserts that any existing
  value is a usable key. Keep raw key context until actual bytes have been validated, durably encrypted,
  read-back verified and atomically referenced through the approved importer (4.7). In 3.5, an unresolved
  path needs explicit re-import/recovery, not silent disabling of key authentication or password fallback.
- Production UI/SSH/protected save wiring remains **3.5**; document selection/key validation **4.7**;
  backup exclusions and database checkpoint/compaction/residual plaintext handling (including SQLite's
  own existing pages/journals/WAL, not a new migration journal) **3.6**; logging
  cleanup **3.7**. Existing legacy paths can still write plaintext until centralized in 3.5. Do not
  advertise this intermediate build as fully protecting active credentials or backups.

## Verification

Verified **2026-10-20**, baseline `a362e08`. Only **3.4** completed, **13/67** total.

- Final full Gradle run: **BUILD SUCCESSFUL in 2m27s; 83/83 tasks executed**, no build cache.
  **168 JVM tests / 17 suites, zero failures/errors/skips** (13 new migration-core tests). Core tests
  use in-memory source/store doubles; actual crypto/persistence verification is below. Lint: **0
  errors/fatal, 155 unchanged warnings**, no new-source findings or suppressions. Both APKs built.
- Unchanged v2 fixture parser/assertions: **4 tests passed**.
- Actual API 35 Android run: **OK (39 tests), 12.093s**: all 25 existing cases plus **14 new migration
  cases**, using the unchanged synthetic v2 SQL/XML assets with real Android SQLite,
  SharedPreferences and AndroidKeyStore through production boundaries.
- The checkpoint matrix covers **26 separate interrupt/reopen/resume scenarios**: before/after secret
  writes and before/after read-back for each of two hosts/default password/global passphrase;
  before/after each host update and after its SQLite commit; before/after each preferences commit.
  In-transaction faults roll back. Durable plaintext OR verified referenced ciphertext remains usable,
  hosts/scripts stay at IDs **7/42** and **13/29**, and repeated runs retain exactly four secret records.
- Additional Android cases cover real encrypted-file pre-rename failure; uncertain completed writes;
  failed/missing/wrong read-back; preferences commit(false) before disk, after disk, and cache-only
  changes (both preference sources, six scenarios); matching/missing/conflicting existing references;
  invalid IDs; real test-key deletion after preparation without key regeneration; ciphertext tampering;
  missing records/key loss after cleanup; source-change rejection; real SQL trigger-induced update
  failure/rollback; empty versus absent sources; disabled global auth; nonexistent raw key context;
  all original non-secret columns/preferences/scripts and extra/nondefault locator/endpoint strings.
  Successful commit/fault cases also compare actual preferences XML disk values to the memory view.

Reused **only** `/tmp/opencode/xmouse-task-3.1/avd/xmouse_task31.avd`. Verified `emulator-5580` reported
`xmouse_task31` / boot `1` immediately before **each final APK installation**. Fingerprint:
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
UUID-owned test databases/preferences/secret directories/aliases were cleaned up. No personal device,
other AVD or production key/data was touched; the disposable emulator was shut down after testing.

These are deterministic **injected exceptions and reopen/retry tests**, not physical power cuts or
process-kill/hardware durability evidence. **Not run/claimed:** API 24/37 devices, hardware-backed key
invalidation, production startup/UI/SSH migration, validated private-key import, backup extraction or
database forensic erasure. Remaining boundaries above are unchanged, not completed by these tests.

Evidence: `/tmp/opencode/xmouse-task-3.4/{full-verification.log,full-verification-final.log,
instrumentation-initial.log,instrumentation-final.log,emulator.log,app-badging.txt}`; final unit/lint XML
under `app/build/`. `git diff --check`, new-file whitespace checks and strict OpenSpec validation pass.
No staging or commit performed.

All Android commands set both SDK variables; no machine-specific build configuration was added:

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
# Separate shell with the same SDK exports; verify name and boot BEFORE installing:
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu avd name
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell getprop sys.boot_completed
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument -w \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu kill
```

Initial Android execution found two **test-harness** faults: the cache-only commit adapter discarded
an unrelated pending preference while committing another entry, and Android cached old `SELECT *`
column metadata after the test's extra-column ALTER. The adapter now models full-memory preference
snapshot commits; preservation queries use explicit current-schema projections. No preservation
assertion was removed or relaxed. The first lint run's two KTX style findings were fixed without
suppressions; synchronous preference commits still inspect their boolean failure result.
