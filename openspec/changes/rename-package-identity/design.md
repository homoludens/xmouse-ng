## Context

See proposal.md and specs/application-identity/spec.md. The current namespace/application ID and Java/Kotlin packages are `com.stripe1.xmouse`; Android manifests use both relative and fully-qualified component names, and instrumentation has an explicit provider authority. Some exact old-ID occurrences are historical release evidence or legacy import fixture names rather than current runtime package declarations.

## Goals / Non-Goals

**Goals:** Make the built app, Android components, test package, and production/debug/test source packages consistently use `net.droopia.xmouse_ng`; publish it as XMouse-NG 2.1.1 (version code 27); provide a concise README with Touchpad/Actions screenshots, upstream-fork attribution and build steps; preserve the prior app as a separately installed app and accurately explain isolation to users.

**Non-Goals:** Do not transfer private app data or credentials, alter the old app, change visible branding or SDK floors, rename persisted table/preference/layout formats, or change secret-store AAD/key-alias formats. Historical reports must continue to identify the artifacts they actually verified.

## Decisions

1. **Change both `namespace` and `applicationId`.** They both become `net.droopia.xmouse_ng`. This aligns generated `R`/BuildConfig/source identity and Android's installed application identity. The instrumentation ID derives as `net.droopia.xmouse_ng.test`; explicit synthetic-provider authorities are updated. Retaining the old application ID was considered but rejected because the user explicitly chose a new app identity.

2. **Move and rename all app-owned Java/Kotlin source-set packages.** Apply the new package and corresponding directory layout to `main`, `debug`, `test`, `androidTest`, and `testFixtures`; update imports, manifest-qualified components, custom view XML names, test identities, and the InputEventCodes R8 rule. Resolve Android classes from the new namespace rather than compatibility wrappers in the old namespace.

3. **Separate current documentation from historical evidence.** Update README and the current release/upgrade guide to warn that both apps can coexist and no private data/credential migration occurs. Preserve dated release, verification, build-toolchain, and prior-change records that document the earlier package as historical facts. Preserve legacy fixture filenames/content and import code that deliberately recognizes old-format data.

4. **Keep non-package persistence/crypto identifiers stable.** Database/table names, preference keys, saved-layout formats, secret-store AAD and AndroidKeyStore alias remain byte-for-byte unchanged. They are data-format identifiers, not Java package declarations; changing them would create unnecessary compatibility risk and would not grant the new package access to the old app's sandbox.

## Risks / Trade-offs

- [Users may mistake the new app for an in-place update] → Explicitly state separate-install and no-auto-transfer behavior in current README/release guidance; keep the old app's files untouched.
- [Stale FQCNs can break component inflation, test discovery, or optimized reflection] → Search all active app source sets/manifests/resources/rules and verify actual debug/instrumentation APK IDs, component definitions, and release mapping/content.
- [Mass source relocation can leave stale imports or generated package assumptions] → Perform a deterministic scope-limited package/path rewrite, then run the full JVM/lint/debug/test APK gates and API 35 install/instrumentation smoke if the owned emulator is available.
- [A test may accidentally assert the old ID or legacy fixtures may be renamed] → Add explicit identity assertions while retaining original package strings only in archived evidence and legacy-format fixtures/crypto data identifiers.

## Migration Plan

There is no in-place app-data migration. Publish version 2.1.1 (code 27) under `net.droopia.xmouse_ng` alongside the existing v2.1.0 release under the old ID; the user may keep or separately uninstall the old app, but the new app starts with its own empty/private state. Reverting the source before publishing restores the prior build identity. After publishing the new ID, updates must continue using `net.droopia.xmouse_ng` and the compatible existing release certificate; restoring the old app requires its prior application ID and signer. Do not transfer app data or change the signing key.
