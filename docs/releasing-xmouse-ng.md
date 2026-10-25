# XMouse-NG 2.1.0 release and upgrade guide

Release identity: **2.1.0**, version code **26**, visible name **XMouse-NG**, application ID/namespace **`com.stripe1.xmouse`**, minimum **Android 8.0 / API 26**, target/compile API **37**. Renaming the visible app does not create a separate app or reset its data. Artifact verification is recorded in [the signed-release record](verification/xmouse-ng-signed-release.md); earlier integration results remain in [the acceptance record](verification/xmouse-ng-redesign-acceptance.md).

## Before installing or upgrading

- Back up available app metadata and layout/recovery files first, and retain SSH credentials and original private-key documents separately in secure storage. App metadata backup deliberately excludes passwords, passphrases, protected key bytes, and device-bound encryption keys. Restoring metadata is not a working-secret backup; a restore or device-key loss can require credential re-entry/key re-import.
- An in-place update requires the **same application ID and a compatible signing certificate**, not just the XMouse-NG name or a higher version. This APK uses the owner-selected certificate below. Compatibility with any original upstream APK, debug APK, or differently signed fork is **not established**. Check the installed certificate first. If Android rejects the update, stop: do not uninstall, clear app data, or replace the signing key to bypass the rejection.
- The redesign retains existing database tables, storage paths/formats, preference keys, trust records, secret references, global authentication choices, saved computers/ports, scripts, layout/profile IDs, action order/commands/custom widths/colors, and migration/recovery behavior. A blank replacement password on an existing computer preserves its protected secret reference; no stored password is prefilled. Existing CSV recovery sources remain untouched. This is the intended preservation contract, not a claim that a real user's upgrade has been exercised.
- There is no reverse migration. Installing an older APK over migrated data is not a supported rollback. Android 7/API 24–25 and earlier remain on the legacy release.

## Local signing configuration (maintainer)

The keystore and all four signing values are supplied through **external Gradle user properties**, normally `~/.gradle/gradle.properties`, never project properties committed to Git. The existing owner configuration is used; do not create a repository password file or pass passwords on a command line.

Required property names (values intentionally not shown):

- `RELEASE_STORE_FILE`: an existing keystore outside the source repository. On the verified maintainer machine: `/home/homoludens/.gradle/keys/release-key.jks` (owner-only mode `0600`).
- `RELEASE_KEY_ALIAS`: verified alias **`release`**.
- `RELEASE_STORE_PASSWORD` and `RELEASE_KEY_PASSWORD`: private, nonblank values supplied only by the owner in external Gradle user properties. Never print them, enable Gradle debug/property dumps, or attach the properties file to logs/releases.

Expected signing-certificate **SHA-256** (public certificate fingerprint, not an SSH host fingerprint):

```text
C1:C5:FA:CA:7B:FD:24:CE:2B:F5:46:7A:73:1B:71:B0:21:8B:CB:50:3E:B2:0F:81:4D:37:09:25:A4:B8:8E:ED
```

`app/build.gradle` assigns the external release signing configuration and gates `preReleaseBuild`, release packaging, and Android's `validateSigningRelease` with `verifyLocalReleaseSigning`. Gating packaging is essential because Android's plugin can omit its signing-validation task for an incomplete configuration. Missing/blank properties, a nonexistent keystore, or a keystore inside the repository block the release build; there is no unsigned/debug/replacement-key fallback. Debug/unit builds do not require these properties. The guard is not configuration-cache-compatible; release commands explicitly disable that cache. The fingerprint must still be checked on the resulting APK: a configured alias alone is not provenance.

With the selected toolchain (see [build-toolchain.md](build-toolchain.md)) and the private user properties already configured:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew --no-configuration-cache --no-build-cache --console=plain :app:assembleRelease
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging \
  app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

Capture only credential-safe build diagnostics. Stop on a signing error or a certificate mismatch; do not try another key or publish an unsigned artifact. Before publication, inspect the actual release manifest, DEX class definitions, and ZIP entries to exclude debuggability, test instrumentation/Activities/providers, fixture data/keys, HTML/JavaScript, browser reference/demo payloads, and signing material. Verify the version, app ID, API floor, visible label, APK size, and checksum. Retain the APK/checksum/evidence outside source control. A build verified locally is not a GitHub release; publication is a separate gate.

## Validation and remaining limits

The redesign integration gate passed **487 JVM tests** and **36 selected API 35 emulator tests**, plus lint and debug/test APK builds; these were synthetic/recording/owned-emulator checks, not Linux or real-user migration acceptance. See the linked records for exact versions, counts, warnings, and evidence boundaries. Release signing/packaging verification does not add a runtime installation test.

**Not run for this release:** signed install/in-place upgrade under this certificate with real retained user data; API 26 or physical-device runtime; API 36/37-specific behavior; human TalkBack traversal; live Linux/X11/Wayland SSH control or Test connection, discovery, media/brightness, clipboard export, scripts, or sensor accuracy; hardware process-kill/restore and device-to-device secret recovery. No user's phone, databases, vault, preferences, clipboard, or SSH endpoint was accessed during release packaging. Never infer these passes from build/signature checks.
