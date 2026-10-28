## 1. Release build preparation

- [x] 1.1 [low] Prepare version 2.1.2 / code 28 and implement the strict opt-in unsigned F-Droid APK path without reading signing properties, with isolated output and unsigned-AAB rejection; verify strict flag parsing, positive unsigned APK build, default and partial-signing failures, and signed APK plus Play AAB identity/certificate using the existing external signer through credential-safe tooling.

## 2. Privacy policy

- [x] 2.1 [low] Source-audit current SSH, discovery, clipboard, persistence, secret, backup, logging, and dependency behavior; add identical source-hostable and Android-resource policy text plus an About entry to a native offline, scrollable, selectable privacy screen; verify content parity, focused JVM/UI assertions, and that no browser/network behavior is introduced.

## 3. Store listing material

- [x] 3.1 [low] Add shared English title, short/full descriptions, version-28 changelog, synthetic screenshot copies, and a metadata validator; verify all store text limits, screenshot dimensions/formats, changelog version, privacy/project links, and absence of availability claims or badges.
- [x] 3.2 [low] Generate the 512×512 store icon, 1024×500 feature graphic, density-specific classic icons, and API-26 adaptive/round icons from the unchanged `docs/xmouse_ng_icon.png`; verify rendered mask-safe artwork, dimensions, no PNG metadata, resource/manifest references, before/after byte sizes, and absence of the full-resolution source in the APK.

## 4. F-Droid reproduction preparation

- [x] 4.1 [low] Add a clearly gated F-Droid recipe for the real Gradle app with unsigned mode, the verified existing signer fingerprint, and a future GitHub binary URL pattern but no invented tag/commit; build the same source checkpoint in two independent clean locations without shared build/cache outputs, compare unsigned APKs, and verify signature copying against the exact signed APK with apksigcopier/apksigner, stopping and recording any concrete reproducibility blocker without relaxing release settings.

## 5. Submission handoff and integration verification

- [x] 5.1 [low] Add concise maintainer-facing F-Droid/Play checklists and a source-hash-bound verification record; run the full JVM/lint/debug/instrumentation-compile regression, metadata and strict OpenSpec validation, signed AAB structure/page-alignment/native-library audits where pinned tooling is available, and whitespace/diff checks, recording unavailable external/device checks as NOT RUN and leaving all work uncommitted.

## 6. Authorized GitHub publication

- [ ] 6.1 [low] After tasks 4.1 and 5.1 pass, verify the remote fork/tag/release state; commit the prepared source, fast-forward push only `homoludens/xmouse-ng` `main`, create annotated `v2.1.2` on the verified artifact-source commit, and publish the verified signed APK/AAB plus checksum files as a non-prerelease GitHub release; download and independently verify the published assets, record actual commit/tag/release hashes and links in a docs-only follow-up, and leave Google Play/F-Droid service-side submission NOT RUN.
