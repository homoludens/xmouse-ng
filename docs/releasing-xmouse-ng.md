# XMouse-NG 2.1.1 release and upgrade guide

Release identity: **2.1.1**, version code **27**, visible name **XMouse-NG**, application ID/namespace **`net.droopia.xmouse_ng`**, minimum **Android 8.0 / API 26**, target/compile API **37**. This is a new, separate Android app from `com.stripe1.xmouse`; both packages may remain installed. The old package's private database, preferences, files, credentials, and Android Keystore keys are isolated and are not readable or automatically transferred to this app. The linked signed-release, acceptance, and older verification records are historical evidence for the artifacts they actually inspected; records that report `com.stripe1.xmouse` do not identify or verify the new package's APK. The 2.1.0 identity check is recorded in [the package-identity verification](verification/package-identity.md); final 2.1.1 build/publication evidence is recorded in [the 2.1.1 release verification](verification/xmouse-ng-2.1.1-release.md).

## Before installing or upgrading

- Back up available app metadata and layout/recovery files first, and retain SSH credentials and original private-key documents separately in secure storage. App metadata backup deliberately excludes passwords, passphrases, protected key bytes, and device-bound encryption keys. Restoring metadata is not a working-secret backup; a restore or device-key loss can require credential re-entry/key re-import.
- An in-place update requires the **same application ID and a compatible signing certificate**, not just the XMouse-NG name or a higher version. Because this release uses `net.droopia.xmouse_ng`, it cannot update `com.stripe1.xmouse` in place, even if both APKs use the same certificate. The old app can remain installed independently. Future updates to this package require the same new ID and a compatible signer. If Android rejects an update, stop: do not uninstall, clear app data, or replace the signing key to bypass the rejection.
- The app retains its existing database tables, storage paths/formats, preference keys, trust records, secret references, global authentication choices, saved computers/ports, scripts, layout/profile IDs, action order/commands/custom widths/colors, and in-app migration/recovery behavior **within its own sandbox**. This does not grant access to the old package's data. No database, preferences, files, credentials, or Android Keystore keys are migrated across package IDs; credentials must be entered again and private keys selected/imported in the new app as needed. A blank replacement password on an existing computer preserves that computer's protected secret reference; no stored password is prefilled. Existing CSV recovery sources remain untouched. This is the intended within-package preservation contract, not a claim that a real user's upgrade has been exercised.
- There is no cross-package or reverse migration. Installing an older APK over migrated data is not a supported rollback. Android 7/API 24–25 and earlier remain on the legacy release.

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
