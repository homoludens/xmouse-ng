## 1. Default layout and safe initialization

- [x] 1.1 [low] Extract the original 57 stock rows into one immutable catalog and adapt the legacy loader from it; verify all ordered titles, commands, widths, and colors exactly match the original table.
- [x] 1.2 [low] Seed a create-only, selected `Keys Layout` on a clean repository and add it non-destructively on eligible upgrades; persist a backward-compatible initialization marker and verify fresh, blank-automatic, custom-profile, draft, legacy-source, edit/rename, and delete/restart cases without changing user data.

## 2. Integration and verification

- [x] 2.1 [low] Add a concise user-facing mention and source-checkpoint verification note; run focused and full JVM tests, lint, debug build, instrumentation compilation, strict OpenSpec validation, and `git diff --check`, recording unavailable emulator/device checks as NOT RUN.

## 3. XMouse-NG 2.1.3 GitHub release

- [x] 3.1 [low] Set version 2.1.3/code 29 and add the matching changelog; verify the 57-row layout, full JVM/lint/debug/instrumentation-compilation gates, listing/artwork metadata, OpenSpec, and diff while preserving historical verification records. Stage explicit in-scope paths (never `AGENTS.md`), commit, and fast-forward push only the fork's `main` over HTTPS.
- [x] 3.2 [low] From a clean checkout of the exact pushed source commit, build and audit the signed APK/AAB with the existing certificate; verify identity, VCS revision, archive/static content, ZIP/ELF 16 KiB alignment, and artifact checksums. Run independent clean unsigned source builds and signature-copy verification against the exact signed APK; create/push annotated `v2.1.3` only after every required gate passes.
- [x] 3.3 [low] Publish a regular, non-prerelease GitHub release with the verified APK/AAB and checksum companions; download and independently verify all assets and remote release state. Then update the F-Droid draft without enabling it, record actual publication evidence and remaining NOT RUN checks, and push a docs-only follow-up without moving the release tag. Do not submit to Google Play or F-Droid.
