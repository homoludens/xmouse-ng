# Additive contactsManager migration (task 3.3)

## Contract / next-task handoff

`DatabaseHandler` retains database **contactsManager**, tables **contacts/scripts**, and all
existing columns, row IDs and values. Schema **3** supports exactly **2 → 3**. History inspected:
`ea68c8a:src/com/stripe1/xmouse/util/DatabaseHandler.java` and the current-path introduction
`316d727` both already declare version 2; no version-1 schema is established. Older/unknown upgrade
paths and all downgrades throw `SQLiteException`, without changing tables or `user_version`.
An unversioned database with existing user tables is also rejected, not treated as a new install.
This does **not** make installing an old APK safe: that binary still has destructive callbacks.

Only these columns are appended to `contacts`; `scripts` is unchanged:

| Column | SQLite declaration / meaning |
| --- | --- |
| `PasswordCredentialRef` | nullable `TEXT`; opaque `SecretId` reference for this host's password |
| `LocatorType` | `TEXT NOT NULL DEFAULT 'manual'`, CHECK permits `manual` / `discovered_service` |
| `ServiceInstanceName` | nullable `TEXT`; original literal DNS-SD instance label |
| `ServiceType` | nullable `TEXT`; original SSH service type |
| `ServiceDomain` | nullable `TEXT`; original DNS-SD domain |
| `StableTargetHostname` | nullable `TEXT`; optional advertised stable DNS hostname, never a resolved IP |

Existing and legacy-inserted hosts default to `manual`, using original `Host` plus `Port` for
`EndpointLocator.Manual`; all other new fields default to SQL NULL. `discovered_service` maps the
service tuple and optional target to `EndpointLocator.DiscoveredService`, with existing `Port`.
Keep the original strings and derive normalized identity with `Endpoint`, not stored `toString()`.
Resolved addresses/network handles are attempt-local. Future repositories must validate `SecretId`
and complete locator shapes through the domain values; the schema's enum CHECK is not that validation.
Do not interpret missing/incomplete service metadata as permission to fall back to a transient address.

Fresh creation uses the identical v2 definitions plus the same six ALTER statements as upgrade.
The helper owns the schema. The old constructor still accepts `MainActivity.initDb`'s exact ordered
TEXT declarations (or empty lists), validates immediately, and retains no mutable caller lists/SQL.
`DatabaseHandler(context)` is available without schema arguments. Leave `MainActivity.hostDBKeys`
unchanged: current host CRUD still uses those five positional fields.
The concrete helper implements `java.io.Closeable` for resource-safe callers on API 24–28, where
the platform's `SQLiteOpenHelper` did not yet implement `AutoCloseable`.

`SQLiteOpenHelper` transactionally applies DDL and `user_version`; exceptions roll everything back.
The v2 migration first checks required legacy fields/types; extra user columns/objects are retained.
Conflicting new column names fail and roll back, never silently repair/rebuild the database. Open
through the helper; its lifecycle callbacks are not standalone transaction-owning APIs.

**Scope boundary:** no plaintext field is cleared, encrypted, normalized or backfilled with a reference;
no preferences, selected-host choice, global key authentication, or active credential writes change.
No migration journal/checkpoint table is introduced. Tasks **3.4/3.5** must store and re-read verified
encrypted replacements before transactionally updating references/clearing plaintext and then handling
preferences. Global key/passphrase/default-password references still need their later repository
mapping; this schema does not invent per-host key choices. Backup exclusions remain **3.6**.

## Reproduction

All Android commands use both SDK variables. Tests load the unchanged synthetic v2 asset, not user
storage, under UUID-owned Context database directories. Existing task-3.2 key-loss tests now open the
current helper/schema and retain their complete metadata/preferences/layout-preservation comparisons.

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
# Separate shell with the same SDK exports, after boot; verify name BEFORE installation:
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu avd name
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell getprop sys.boot_completed
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument -w \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu kill
```

## Verification results

Verified **2026-10-20**, baseline `a5c4506`. Only task **3.3** completed (12/67 total).

- Final full Gradle run: **BUILD SUCCESSFUL in 1m49s; 83/83 tasks executed**, without build cache.
  Unit XML: **155 tests / 16 suites, zero failures/errors/skips**. Lint: **0 errors/fatal / 155
  existing warnings**, no new-source findings or suppressions. App and instrumentation APKs built.
- Unchanged standard-library v2 fixture checks: **4 tests passed**; fixtures still declare version 2.
- Actual Android suite: **OK (25 tests), 3.516s**: all 12 existing tests plus **13 migration tests**.
  The four existing credential-re-entry tests retain key-loss, cancellation, memory-only re-entry,
  ciphertext, preferences and non-secret preservation coverage after upgrading their v2 fixture.

Migration coverage uses real Android SQLite/SQLiteOpenHelper, not mock databases:

1. Every original host/script column value and ID (7/42, 13/29), including synthetic plaintext and
   timestamps, remains identical. Extra synthetic columns/table/index/trigger also survive; table
   rootpages remain stable, integrity check succeeds, and repeated readable/writable opens change nothing.
2. Fresh databases from all three constructor forms match upgraded column order/types/PK/defaults and
   whitespace-normalized schema SQL (including CHECK). Later caller-list mutation cannot change schema.
   Existing CRUD still inserts/reads/edits/deletes only the intended synthetic host/script IDs; omitted
   metadata defaults to manual/NULL. Service-only and stable-target metadata plus an opaque `SecretId`
   round-trip through SQLite and reconstruct the domain identity; invalid/null locator kinds are rejected.
3. Future-version 4 → 3 opens in both modes and the current callback for 3 → 2 reject without altering
   any table, row, schema object or version. Unknown 1 → 3 / 2 → 4, unversioned user tables, and an
   incomplete v2 schema also preserve their complete snapshots. Version 1 here is only a rejection
   probe, not a newly inferred historical format.
4. A real duplicate-column SQL error after preceding ALTERs rolls back every addition/version on two
   retries. An exception after all ALTERs rolls back the actual helper transaction, then normal retry
   succeeds. An explicitly uncommitted DDL/version transaction also rolls back, then upgrades normally.
   This models transaction interruption; it is not a physical power-cut/process-kill durability test.
5. Unsupported caller field counts/types fail before any database file is created.

The first full run exposed a test-only API-29 `SQLiteOpenHelper.use` cast; fixed with explicit close
and the concrete helper's `Closeable` compatibility, not suppression. The first device run exposed a
missing fresh-test parent directory because modern helpers open `getDatabasePath` directly; fixed in
the isolated Context, then reran the entire full build and all 25 Android tests successfully.

Reused only `/tmp/opencode/xmouse-task-3.1/avd/xmouse_task31.avd` (API 35). Before **each** installation,
verified `emulator-5580` reported AVD `xmouse_task31` and boot completion `1`. Fingerprint:
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`. No physical/personal
device or other AVD was used. UUID-owned databases/directories were cleaned up; the emulator was stopped.

**Not run/claimed:** API 24/37 device coverage, physical power-cut recovery, encrypted credential
migration/checkpoints, backup extraction, production authentication or discovery persistence wiring.
Those remain later tasks, not substitutes for this schema-only result.

Evidence: `/tmp/opencode/xmouse-task-3.3/{full-verification.log,full-verification-final.log,
full-verification-final-rerun.log,instrumentation-initial.log,instrumentation-final.log,emulator.log}`;
unit/lint XML under `app/build/`. `git diff --check`, new-file whitespace checks and
`openspec validate modernize-xmouse --strict` pass. No staging or commit performed.
