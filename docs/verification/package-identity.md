# XMouse-NG 2.1.0 package-identity verification

Date: **2026-10-28**. Scope: `rename-package-identity`, using the approved
`openai/gpt-6-luna#max` assignment. This is local verification of the new
`net.droopia.xmouse_ng` identity; it is not a publication or a claim that the
old installed app was migrated.

## Source and package audit

- All **253** Java/Kotlin files from `main`, `debug`, `test`, `androidTest`, and
  `testFixtures` are under `net/droopia/xmouse_ng`; every package declaration
  matches its source path. There are no Java/Kotlin files left under the old
  package path.
- A source comparison against the pre-move files found only the intended
  package/path rewrites, new app/test-package assertions and moved source-path
  references. The legacy preferences fixture names remain
  `com.stripe1.xmouse_preferences.xml`.
- The scoped old-identity search found only the intentional legacy fixture
  references and the unchanged `AndroidSecretStore.KEY_ALIAS`
  (`com.stripe1.xmouse.secrets.aes-gcm.v1`) and `SecretRecordCipher` AAD
  (`com.stripe1.xmouse/secret-store\0`). Database, preference, saved-layout,
  credential, and secret-record formats were not renamed or migrated.
- The built debug/release manifests and DEX package trees contain no old app
  class namespace. Manifest component class definitions resolve in the built
  APKs. The release DEX contains no test or debug-fixture classes.

## Build and test results

The credential-safe wrapper at `/tmp/opencode/control-performance/safe-gradle.py`
captured Gradle output with an allowlist; raw Gradle diagnostics were not
retained or printed, and release passwords/properties/private-key bytes were
not read. The final forced build pass ran:

```text
--rerun-tasks :app:testDebugUnitTest :app:lint :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease
```

Gradle reported **BUILD SUCCESSFUL** with all **135 tasks executed**. The full
JVM report contains **549 tests in 74 suites**, with **0 failures, errors, or
skips**. The aggregate lint task passed; its debug XML report contains **282
warnings and no errors**. No warning baseline comparison was performed.

The focused identity instrumentation test
`ApplicationTest.applicationContextHasExpectedPackage` passed on the dedicated
API 26 `xmouse_control_performance_api26` emulator (**1 test, 0 failures**).
The emulator reported `ro.kernel.qemu=1`, API level 26, and boot complete. Only
the new debug app and its `.test` APK were used; no old-package APK was
installed, no physical device was attached, and no data wipe or `pm clear` was
run.

## Built artifact identity

Inspected the actual APK manifests/badging and DEX, not only Gradle source
configuration:

| Artifact | Actual application ID | Version | Min / target / compile SDK | Launcher |
| --- | --- | --- | --- | --- |
| Debug app | `net.droopia.xmouse_ng` | 2.1.0 (26) | 26 / 37 / 37 | `net.droopia.xmouse_ng.MainActivity` |
| Instrumentation APK | `net.droopia.xmouse_ng.test` | — | 26 / 37 / 37 | — |
| Optimized release app | `net.droopia.xmouse_ng` | 2.1.0 (26) | 26 / 37 / 37 | `net.droopia.xmouse_ng.MainActivity` |

Both app APKs report the visible label **XMouse-NG** and `debuggable=false` for
release. The test manifest targets `net.droopia.xmouse_ng`; its two synthetic
provider classes and authorities resolve under the new test identity. All ten
app-owned release components and all twelve debug app components resolve to
classes in their APKs.

| APK | Size | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 20,764,490 bytes | `fb5937eba2a521162410e43c60204840ea786381750f678d6dc9d34bfd603d40` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,745,133 bytes | `9ad2393e95d40d83ab6e93060d44e2a32a5b1a271572b0dfb9d56ea600980d9f` |
| `app/build/outputs/apk/release/app-release.apk` | 3,727,209 bytes | `dce6ce558d79c53076fc011ac7e126c8d0929a456000bc773ccb6765d18a4f0b` |

Release signature verification found **one signer**, RSA-2048, with APK Signature
Scheme v2 valid. Its public certificate SHA-256 is
`C1:C5:FA:CA:7B:FD:24:CE:2B:F5:46:7A:73:1B:71:B0:21:8B:CB:50:3E:B2:0F:81:4D:37:09:25:A4:B8:8E:ED`,
matching the existing release certificate. ZIP CRC checks and
`zipalign -c -v 4` passed for all three APKs.

## Installation and data boundary

The new application ID is distinct from `com.stripe1.xmouse`, so it is a
separate Android installation and does not update or gain access to the old
app's private database, preferences, files, credentials, or Android Keystore
keys. No cross-package data/credential migration was added. The old app and
its installed data were not accessed or changed. A real old-release/new-release
side-by-side install was deliberately not attempted; the emulator smoke test
verified only the new app and its derived instrumentation package.

## Other verification and limits

- `git diff --check`: passed.
- Strict `openspec validate rename-package-identity --strict --json`: passed
  with no issues after the task status was updated.
- The moved source tree retains 82 trailing-whitespace lines that are present
  unchanged in the corresponding pre-move files; no new trailing whitespace
  was introduced. The verification record and task file have none.
- This record documents the initial local 2.1.0 identity check before release
  preparation; no commit/publication was performed at that point. The later
  2.1.1 release is documented separately in
  [the 2.1.1 release verification](xmouse-ng-2.1.1-release.md). A successful
  build and signature check do not establish a user-data upgrade, real-device,
  or live SSH/runtime test.
