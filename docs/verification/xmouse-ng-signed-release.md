# XMouse-NG 2.1.0 — signed release verification

Date: **2026-10-25**. Scope: `redesign-xmouse-ng` **3.2 [low]** only, using the recorded user-approved `openai/gpt-6.1-sol#xhigh` exception. Branch: `redesign/xmouse-ng`; HEAD: `301e4a9ce408f77b78f1069c185db36721a081cd`. The APK was built from the **uncommitted working tree**, including the preserved task 3.1 integration fixes. This is not a claim that HEAD alone reproduces it, or that source/artifacts have been published. Task 3.3 remains unchecked.

## Verified artifact

| Field | Actual result |
| --- | --- |
| Visible brand | **XMouse-NG** |
| Version | **2.1.0**, code **26** |
| Application ID / namespace | **`com.stripe1.xmouse`** (unchanged) |
| Minimum / target / compile SDK | **26 / 37 / 37** |
| Launcher | `com.stripe1.xmouse.MainActivity` |
| Signed build output | `app/build/outputs/apk/release/app-release.apk` (not an unsigned APK) |
| Retained release APK | **`/tmp/opencode/xmouse-ng-release/xmouse-ng-2.1.0.apk`** |
| Size | **15,144,342 bytes / 14.4428 MiB** |
| APK SHA-256 | `31377673a521c1a8935eb973f5d841a394815eef5a04fc7a41a1463dbdb06c81` |
| Checksum file | `/tmp/opencode/xmouse-ng-release/xmouse-ng-2.1.0.apk.sha256` |
| Signers | **1**, `apksigner verify --verbose --print-certs` succeeded |
| Signature scheme | **APK Signature Scheme v2 verified**; v1/v3/v3.1/v4 were not used. API 26 supports v2. |
| Certificate SHA-256 | **`C1:C5:FA:CA:7B:FD:24:CE:2B:F5:46:7A:73:1B:71:B0:21:8B:CB:50:3E:B2:0F:81:4D:37:09:25:A4:B8:8E:ED`** — exact expected match |

The retained APK is byte-identical to the signed Gradle output. `sha256sum --check` passed. `aapt dump badging` reported the fields above, including `application-label:'XMouse-NG'`. ZIP CRC checks and `zipalign -c -P 16 -v 4` passed. Signature/alignment/badging checks establish a correctly packaged signed artifact; **Android installation was not attempted**.

## External signing and fail-closed checks

`app/build.gradle` consumes only the existing external Gradle property names `RELEASE_STORE_FILE`, `RELEASE_KEY_ALIAS`, `RELEASE_STORE_PASSWORD`, and `RELEASE_KEY_PASSWORD`. The owner-selected keystore is `/home/homoludens/.gradle/keys/release-key.jks`, alias `release`, verified file mode **0600**. No keystore or password file/value was added to source, release notes, or artifacts. Gradle consumed the credentials privately; verification scripts did not read the external properties file or private-key bytes. The APK necessarily contains its public signing certificate/signature, not the private key.

`verifyLocalReleaseSigning` gates release prebuild, APK/bundle packaging, and Android's signing-validation task when present. The explicit packaging gate matters: an early negative test found that AGP omits `validateSigningRelease` if signing configuration is incomplete. The final implementation does not depend on that task existing. Debug/unit builds remain available without release credentials. Configuration-cache use was disabled for every verification command; the guard also declares itself incompatible with that cache.

**Seven expected-failure checks passed** against the final configuration (nonzero Gradle exit and the intended guard failure, not merely a missing-task error):

1. `:app:assembleRelease` with each of the four required properties individually overridden to blank — **four cases**.
2. `:app:assembleRelease` with a nonexistent external keystore — **one case**.
3. `:app:assembleRelease` with an existing file inside the repository as the keystore — **one case**, rejected before any key parsing.
4. Direct `:app:packageRelease` with all four properties blank — **one case**, preventing a packaging-only bypass.

These are blank-property/invalid-path simulations, not password guesses or attempts with another key. The production guard also rejects absent/null values. Successful signing used only the supplied key and alias. No key substitution occurred.

## Build and focused resource gate

All Gradle commands set `ANDROID_HOME=ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk`, with its `platform-tools` first in PATH. Actual successful release command:

```sh
./gradlew --no-configuration-cache --no-build-cache --console=plain :app:assembleRelease
```

**BUILD SUCCESSFUL**, exit 0, **79.391 seconds** wall-clock; Gradle reported 51 actionable tasks, 16 executed / 35 up-to-date. Release compilation, lint-vital, resource optimization, dexing, signing validation, and packaging succeeded. Lint-vital is not a new full lint pass. Diagnostics were captured in memory and only fixed-message/task/status allowlisted lines were retained; no raw Gradle/error output or secret values were printed or saved.

With **all four signing properties overridden to blank**, `:app:testDebugUnitTest --tests com.stripe1.xmouse.XMouseNgThemeResourcesTest` passed: actual XML **3 tests, 0 failures/errors/skips**. This exercised the real resource/branding/API/theme checks and demonstrated debug/unit compilation without the release key. The full **487-test JVM / 36 selected API 35 runtime** results belong to the [task 3.1 acceptance record](xmouse-ng-redesign-acceptance.md), not a new full-suite or device run here. Its archived logs remain authoritative because a focused Gradle run can replace current test-result XML files.

Strict `openspec validate redesign-xmouse-ng --strict` and `git diff --check` passed. No production runtime dependency declarations changed. The prior 812-line resolved debug dependency graphs were identical to the pre-redesign baseline; that graph was not rerun for task 3.2. The signed release size above is an actual measurement, **not** a debug-to-release growth comparison; the same-toolchain debug growth remains **1,023,979 bytes (+5.0624%)** in the earlier record.

## Actual release ZIP, manifest, and DEX audit

Audited the **retained signed APK**, not only source sets or the debug APK:

- **1,072 ZIP entries**, CRCs valid; **zero** HTML/HTM/JS/MJS/CJS/JS bundles, browser-reference/handoff/demo/debug-fixture paths, external TTF/OTF fonts, nested APKs, or private key/keystore file entries.
- The only asset entries are **`assets/dexopt/baseline.prof`** and **`assets/dexopt/baseline.profm`**, generated Android baseline profiles. There are no application fixture/reference assets, legacy SQL/preference test payloads, SSH crypto test keys, or password property files.
- Decoded **four DEX files / 27,308 class definitions** (8,853 + 7,411 + 9,422 + 1,622). Compared actual definitions against **38 source-derived debug/test file-class prefixes**, including nested classes, plus explicit fixture/test/browser-tooling namespaces. **Zero** debug/test fixture matches, `MainInsetsFixtureActivity`, recording test backends, synthetic key providers, AndroidX test/JUnit/Mockito/MINA SSHD test code, WebKit dependency classes, or debug Compose preview host Activities/adapters were defined. Legitimate existing Compose preview annotations are not equated with debug-only host tooling.
- The release manifest has **eight production Activities**, the existing `SessionForegroundService`, AndroidX startup provider, and standard profile-installer receiver. **No debug-only Activities/providers, instrumentation, `testOnly=true`, or `debuggable=true`**. Normal backup/metadata application identity remains present. No second session owner or runtime browser was added.

## Local evidence and publication handoff

All binaries and raw verification evidence remain outside source under **`/tmp/opencode/xmouse-ng-release/`**:

- `xmouse-ng-2.1.0.apk`, `xmouse-ng-2.1.0.apk.sha256`, `checksum-check.txt`.
- `assemble-release.{log,json}` (credential-safe allowlist), `apksigner-verify.txt` (public certificate information), `certificate-check.json`, `aapt-badging.txt`, `aapt-manifest.txt`, `zipalign-check.txt`.
- `release-apk-audit.json`, `apk-zip-entries.txt`, `apk-app-class-definitions.txt`, `audit-release.py`.
- `guard-negative-cases.json`, `guard-direct-package.json`, individual safe negative-test logs/metadata, `debug-without-signing.{log,json}`, `focused-theme-jvm.json`, and `safe-gradle.py` (no secret access).
- `release-source-inputs.json`: SHA-256s for **288 production source/build/wrapper inputs**, HEAD, unchanged dependency declaration check, and keystore permission only. It records no password or private-key bytes. The original 3.1 evidence remains separate under `/tmp/opencode/xmouse-ng-verification/`.

For task 3.3, commit/publish only authorized source and these truthful notes; verify it corresponds to the recorded working-tree inputs. This APK predates that commit. If rebuilding changes any artifact bytes, rerun certificate/badging/ZIP/DEX/alignment checks and replace the published checksum/evidence consistently; do not attribute this artifact to a source commit without checking. No staging, commit, remote change, push, archive, GitHub release, or binary addition to the repository occurred in task 3.2.

## Preservation, privacy, and unrun checks

See [the user-facing release/upgrade guide](../releasing-xmouse-ng.md): a same-key in-place update can retain the existing package/data identity; an original/debug/differently signed install is **not assumed compatible**. Back up available metadata/layout recovery sources and retain credentials separately. Do not uninstall/clear data to bypass an update rejection. Metadata backup excludes secrets/device keys and does not promise cross-device credential recovery. No reverse migration is supplied.

**Still NOT RUN:** Android install/in-place upgrade under this certificate with real retained user data; physical-device or redesigned API 26 runtime; API 36/37-specific behavior; human TalkBack traversal; live Linux/X11/Wayland SSH/control/Test connection, NSD discovery, media/brightness effects, clipboard export, scripts, or sensor accuracy; hardware process-kill/restore and device-to-device migration. Release packaging did not access a phone, real app database/vault/preferences, clipboard, or SSH endpoint. Prior synthetic storage/trust/secret preservation checks are not these tests. Task 3.3 publication and all unrelated `modernize-xmouse` statuses remain unchanged.
