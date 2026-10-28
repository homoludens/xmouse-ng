## Model routing

All tasks are `[low]` under the project classification: the requested valid ID and separate-install behavior are explicit, and this is a broad but mechanical package/resource/build migration rather than a novel algorithm. Implementation and its associated verification use the mandatory `openai/gpt-6-luna#max`. Execute with one agent at a time. Do not commit or publish unless separately requested.

## 1. Package migration

- [x] 1.1 [low] Change namespace/application ID and move/rename app-owned production, debug, test, instrumentation, and fixture packages to `net.droopia.xmouse_ng`; update active imports, manifest/resource FQCNs, test authorities, reflection/ProGuard rules, and package assertions while preserving legacy fixture names and data/crypto formats; verify scoped stale-reference audit and focused identity/migration/crypto regressions.
- [x] 1.2 [low] Update current README and release/upgrade guidance to explain that the new ID installs separately and has no automatic access to old app data or credentials; retain historical release records and test archived/legacy package references are intentional rather than mass-rewritten.

## 2. Artifact verification

- [x] 2.1 [low] Run the full JVM/lint/debug/instrumentation build and signed optimized release build using only existing external signing configuration; inspect actual APK IDs, launcher/components, instrumentation package, SDK/version/signature and no stale app FQCNs; run focused instrumentation on an owned emulator if available, otherwise record device execution as not run; run strict OpenSpec validation and whitespace checks without publishing or committing.
- [x] 2.2 [low] Set the new-package release to version 2.1.1/code 27 and replace the README with a concise app overview, Touchpad/Actions screenshots, upstream-fork attribution, separate-install warning, and reproducible debug build instructions; update current release guidance and verify screenshot/build links, the signed release APK identity and checksum.

## 3. Commit and publish

- [ ] 3.1 [low] Commit the verified package/release changes, fast-forward and push `main` to `homoludens/xmouse-ng`, publish GitHub release `v2.1.1` with the verified signed APK and SHA-256 companion, and record exact commit/tag/release URL, artifact identity/hash/signature and verification limits; confirm remote branch, tag, release and downloaded asset checksums.
