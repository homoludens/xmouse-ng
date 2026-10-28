## Why

The released app currently uses `com.stripe1.xmouse`; the owner selected `net.droopia.xmouse_ng` as a new Android/source package identity. The new ID is intentionally a separate app, so Android will keep the old app installed independently and will not automatically expose or transfer its private data.

## What Changes

- **BREAKING:** Set both Android namespace and application ID to `net.droopia.xmouse_ng`, and rename production, debug, test, fixture, and instrumentation Java/Kotlin packages and source paths accordingly.
- Update Android component/resource references, test provider authorities, release keep rules, package assertions, and current user-facing installation guidance.
- Publish the new app identity as version **2.1.1 (27)** with a concise README, Touchpad/Actions screenshots, attribution to the upstream fork, and build instructions; keep the existing **v2.1.0** release and its old package artifact unchanged.
- Preserve the Android API settings, saved-data formats, legacy import formats, crypto AAD/key-alias format, and historical release evidence. Do not add cross-app data or credential migration.

## Capabilities

### New Capabilities

- `application-identity`: Defines the new Android package identity and its separate-install/no-automatic-data-transfer behavior.

### Modified Capabilities

None. No existing durable main specs are registered; the new install identity is specified separately.

## Impact

Android Gradle namespace/applicationId and version, all app source-set package declarations/imports/paths, manifests and XML component names/authorities, shrinking rules, package-sensitive tests/tooling, README/release guidance, and the `homoludens/xmouse-ng` GitHub release. The old `com.stripe1.xmouse` app and its private database, preferences, files, credentials, and Android Keystore keys remain owned by that installed app; the new ID receives a separate sandbox. No application-data migration, signing-key change, or feature behavior change is included.
