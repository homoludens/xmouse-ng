# Safe Android backups and SQLite compaction (task 3.6)

Verified **2026-10-20**. This records task 3.6 only; no other OpenSpec task was advanced.

## Behavior

- `AndroidManifest.xml` installs `MetadataBackupAgent` as a full-backup-only agent and references
  both the legacy `backup_rules.xml` and API 31+ `data_extraction_rules.xml`.
- Both rule sets allow only the generated `backup/safe-config-v1.bin` metadata snapshot and the
  active layout plus three legacy presets. The current extraction rules repeat the same allowlist
  for cloud backup and device transfer. No database or shared-preferences domain is included, so
  the mixed `contactsManager` database, its WAL/SHM/journal, and the mixed default preferences file
  cannot be copied raw. Application credentials are stored under `noBackupFilesDir` and are not in
  the snapshot.
- At backup time the agent calls credential preparation and metadata projection on the serialized
  repository worker. The versioned snapshot contains host/script metadata, selected endpoint and
  allowlisted non-secret settings, layouts, and only credential-presence bits. It omits secret bytes,
  secret references, and private-key paths. Restore recreates metadata without credentials and sets
  explicit password/passphrase/private-key re-entry markers.
- After successful credential migration, the worker truncates a WAL checkpoint when applicable,
  runs SQLite `VACUUM`, checkpoints WAL again, and commits the retry marker only after completion.
  A failure leaves migration retryable. This is logical compaction, **not** guaranteed forensic or
  hardware erasure from flash storage.
- Restore records credential-required host IDs it actually inserted in a small SQLite pending-marker
  journal in the same transaction as metadata. Re-entry markers are committed to preferences before
  the journal is cleared. If that preferences commit fails, a repeated restore recovers only those
  journaled IDs; conflicting existing rows are never overwritten or marked solely by ID, and rows
  that have since acquired credentials are excluded.
- Settings can open a passphrase editor for the specific restored-marker/no-record case. Existing
  references still require successful ciphertext reads; missing or damaged records are not shown as
  empty. A restored host's untouched blank password cannot silently replace credentials: an explicit
  confirmation is required to save an empty password.

## Verification results

All Android commands used both SDK variables. Final build command:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- **BUILD SUCCESSFUL**; 83 Gradle tasks, 8 executed and 75 up-to-date.
- Unit tests: **189 tests / 20 suites; zero failures, errors, or skips**.
- Lint: **0 errors, 142 warnings**. Three `UseKtx` findings remain on synchronous
  `SharedPreferences.commit()` calls in `AndroidCredentialMigration.kt` and
  `CredentialRepository.kt`, plus the transport test fixture; their Boolean results are required to
  detect failed durable marker commits or prevent backing up unstaged fixture data. The two
  `ApplySharedPref` findings are existing calls in `MainActivity.java`. No lint suppression was
  added. The backup/restore implementation and ordinary instrumentation tests have no lint findings;
  the one transport-fixture warning is intentional to preserve synchronous commit-result checking.
- Both the debug APK and instrumentation APK assembled. `git diff --check` passed.
- Full API 35 instrumentation command:

  ```sh
  export ANDROID_HOME=/home/homoludens/Android/Sdk
  export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
  "$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument --user 0 -w \
    com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
  ```

  Result: **OK (79 tests)**. This includes the new Boolean-marker rollback fault test, restore
  preferences-failure/retry with existing-row preservation, restored Settings passphrase editing,
  one-attempt authentication, strict failure on missing/tampered passphrase records, and explicit
  confirmation before saving an empty restored-host password. The two local-transport fixture
  methods are opt-in and were exercised separately below, not counted as ordinary suite passes.

The approved `xmouse_task31` AVD was the only device used (`emulator-5580`, API 35, boot complete).
Its process identified `-avd xmouse_task31 -port 5580`; the serial/device list and boot state were
checked before each APK install. The initial attempt to stage transport data in owner user 0 refused
to overwrite an existing `pref_key_passphrase` entry and made no changes. The fixture was instead
run in a newly created, isolated ephemeral Android user (ID 10); the owner user's app data was not
cleared or used as test input.

### Android local-transport backup and restore

On that isolated user, the opt-in instrumentation fixture staged **synthetic test-only** legacy and
encrypted credential sources, host/script metadata, four layout files, and an unallowlisted file.
Android's actual full-backup agent and `com.android.localtransport/.LocalTransport` were then used:

1. `bmgr --user 10 backupnow --monitor com.stripe1.xmouse` completed with **Success** and no agent
   errors. An initial setup attempt on an earlier ephemeral user was refused until that user's
   setup-complete flag was set; the final run set it before backup.
2. `bmgr --user 10 list sets` returned token `1 : Local disk image`.
3. After clearing only the synthetic test package data for user 10,
   `bmgr --user 10 restore 1 com.stripe1.xmouse --monitor` completed with `restoreFinished: 0`.
4. `verifyMetadataAndReentryAfterSystemTransportRestore` passed. It verified recovered hosts,
   scripts, layouts, and selected/authentication settings; absent raw credential sources/references
   and encrypted secret records; explicit re-entry markers; and exclusion of the unallowlisted file.
   Result: **OK (1 test)**. The staging method also passed: **OK (1 test)**.

The ephemeral test user was removed after switching back to owner user 0; `pm list users` then showed
only the owner. Owner backup remained disabled with its original Google transport selected. The AVD
was stopped after verification.

### Commands used for the local-transport exercise

With the approved emulator already booted and the APKs installed (the serial check above was repeated
before each installation):

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell pm create-user --ephemeral --for-testing XMouseBackupTest
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am start-user -w 10
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell pm install-existing --user 10 com.stripe1.xmouse
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell pm install-existing --user 10 com.stripe1.xmouse.test
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am switch-user 10
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument --user 10 -w \
  -e class 'com.stripe1.xmouse.secrets.CredentialMetadataBackupTransportAndroidTest#stageSyntheticPackageDataForSystemBackup' \
  -e backupTransportScenario stage com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell bmgr --user 10 activate true
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell bmgr --user 10 transport com.android.localtransport/.LocalTransport
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell bmgr --user 10 enable true
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell settings --user 10 put secure user_setup_complete 1
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell bmgr --user 10 backupnow --monitor com.stripe1.xmouse
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell bmgr --user 10 list sets
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell pm clear --user 10 com.stripe1.xmouse
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell bmgr --user 10 restore 1 com.stripe1.xmouse --monitor
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument --user 10 -w \
  -e class 'com.stripe1.xmouse.secrets.CredentialMetadataBackupTransportAndroidTest#verifyMetadataAndReentryAfterSystemTransportRestore' \
  -e backupTransportScenario verify com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am switch-user 0
```

## Limits

The API 35 exercise used Android's debug-only **local** transport and an explicit local restore. It
does not establish Google/cloud backup behavior, a cross-device transfer, or operation on pre-API-31
runtime backup. Legacy rules and both modern extraction sections were parsed from packaged resources;
the actual API 35 system run exercised the modern rules. No hardware/flash-erasure verification was
performed, and no secure-erasure guarantee is made.

No staging or commit was performed.
