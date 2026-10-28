# XMouse-NG 2.1.2 distribution preparation verification

**Status:** local preparation evidence for an uncommitted working-tree checkpoint. This record does not claim a GitHub release, Google Play acceptance/publication, F-Droid inclusion/review, or official F-Droid build-server reproducibility. After publication, a follow-up entry must bind the final artifacts to their actual source commit/tag and record remote download verification.

## Source checkpoint

- Android identity: `net.droopia.xmouse_ng`, version **2.1.2 (28)**, min/target/compile SDK **26/37/37**.
- Working-tree source manifest SHA-256: `09571854e7cd8989dae6b3abad374715fd1ad18dc7ccbfb810440f401ea3239d`.
- Manifest: **673 files**, **15,411,680 bytes**, computed by `python3 scripts/source_manifest.py --exclude openspec/changes/prepare-store-distribution/tasks.md --exclude docs/store-distribution/verification-2.1.2.md`. The exclusions keep task-checkbox updates and this self-referential record from changing the implementation/documentation checkpoint hash. The later Git commit and tree ID will bind those files too.
- The build checkouts used the then-current Git revision context `dd5059b40749c9aa16ae63816945a9fcea6e5257`; the source itself was uncommitted. This is **not** the final tag commit or a clean tagged-source claim.

## Local builds and tests

- `:app:testDebugUnitTest`: **551 passed, 0 failed, 0 errors, 0 skipped** across 75 XML report files. The final combined Gradle invocation reported the unit task up-to-date; unit-test sources had not changed since the passing run.
- `:app:lint`: passed with **0 errors and 289 warnings**. The warnings include existing project/library notices; the new privacy-screen tint error found in an earlier run was fixed to use AppCompat `app:tint` before the passing run.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:assembleRelease`, and `:app:bundleRelease`: passed using the existing external signer only for the signed release outputs. The instrumentation APK is a test artifact and is not a release attachment.
- Focused `PrivacyPolicyAndroidTest`: **1 passed** on the local API 35 emulator, including About navigation, bundled policy parity, selectable text, and scrolling after forcing a 320dp viewport. The full instrumentation suite and physical-device testing were not run.
- `openspec validate prepare-store-distribution --type change --strict --json`: passed (1 change, 0 findings). `python3 scripts/store_listing.py --validate`: passed; title **9/30**, short description **46/80**, full description **921/4000**, changelog **99/500** characters; screenshots **1080×2160 PNG**; project/support links coherent; public policy URL **NOT PUBLISHED**. The F-Droid draft YAML parsed structurally with PyYAML 6.0.3; `fdroid lint` is **NOT RUN** because fdroidserver is not installed.

## Signed artifact audits

| Artifact | Size | SHA-256 | Identity/signature |
| --- | ---: | --- | --- |
| Local signed release APK | 4,029,238 bytes | `144ac914cf9ad068a72f8a08b6ea62eff43d2148721bfd2c3af701e9f6182127` | APK Signature Scheme v2 verifies; one signer, expected certificate below |
| Local signed release AAB | 7,247,255 bytes | `f2301832c76ddbdd8dd20736f420684ad089b2026913e1bc88b5696f8e65a9d1` | `jarsigner` verification and bundletool validation pass; signer matches expected certificate |

Expected existing signer certificate SHA-256: `c1c5faca7bfd24ce2bf5467a731b71b0218bcb503eb20f814d370925a4b88eed`. No private key, keystore, signing property, or password was read or copied into the source or evidence tree. `bundletool` 1.18.3 (SHA-256 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`) validates the AAB as one base feature module. Its manifest reports the expected package, version/code, and SDKs. The AAB signer is the same certificate. The self-signed AAB has no signing timestamp; `jarsigner` reports that warning and the certificate expiry of 2054-01-26.

`zipalign -c -P 16 -v 4` passed for the signed APK. The APK and AAB each contain four `libandroidx.graphics.path.so` ABI variants (arm64-v8a, armeabi-v7a, x86, x86_64); direct `readelf -lW` inspection found every `PT_LOAD` segment aligned to **0x4000 (16 KiB)**. The APK/AAB archive scans found no test/fixture paths, signing-material filenames, source-named image, source-image hash, or 1254×1254 image. The original 1,226,542-byte icon source SHA-256 remains `cca449b33fcf8d0b329734ded024814273a663b27b67884a049af6b751c75689`; it is absent from both artifacts. The APK image audit checked 179 packaged images.

Generated artwork sizes and safe-mask/source provenance are recorded in [artwork-provenance.md](artwork-provenance.md); the validated 512×512 store icon is 199,267 bytes and the 1024×500 feature graphic is 207,237 bytes. Generated PNG metadata is empty and the 72dp adaptive foreground keeps the complete design inside its measured 36dp circular safe radius.

## F-Droid local reproduction and signature copy

- Two independent clean Git clones, `source-G` and `source-H`, were built sequentially from the same 673-file source-manifest checkpoint. Each had a separate output tree and Gradle user home; release signing properties and key material were removed from each build environment. Both `:app:assembleRelease -PfdroidUnsigned=true` builds succeeded (447s and 431s).
- Both unsigned APKs are **4,016,950 bytes** with identical SHA-256 `82e0ad42fd3e484c4fe3fb89ccbe0e4d057fdb4ff33457840d1e8afaac2a980a`.
- `apksigcopier` 1.1.1 (`click` 8.3.1; package-wheel SHA-256 values are retained in `/tmp/opencode/store-distribution/wheels/`) copied the signature from the exact local signed APK onto the reproduced unsigned APK. `apksigner verify --min-sdk-version 26` passed with v2 and the existing certificate; the copied result is byte-for-byte identical to the signed APK (SHA-256 `144ac914cf9ad068a72f8a08b6ea62eff43d2148721bfd2c3af701e9f6182127`). The verifier's `compare` operation also exited successfully.
- Diagnostic attempts without a valid VCS checkout were not counted as passes: builds A/B emitted `generate_error_reason: NO_SUPPORTED_VCS_FOUND`, and a `.git`-file-only setup C/D emitted `NO_VALID_GIT_FOUND`; signature copying then failed because `META-INF/version-control-info.textproto` differed. The final G/H builds used independent full Git clones; that embedded revision metadata matched the signed APK and no Gradle/release optimization or signing setting was weakened.
- Raw build logs, source snapshots, APKs, copied-signature APK, bundletool reports, alignment output, and native-library extracts are outside Git under `/tmp/opencode/store-distribution/`. These are local checks only: the official F-Droid build server, fdroidserver lint/review, Google Play Console, store submission, and publication were **NOT RUN** at this checkpoint.

## Publication and remaining external checks

No release/tag/push or store action had occurred when this checkpoint was recorded. Before GitHub publication, rebuild and repeat the signature-copy and signed AAB/APK audits against the actual committed/tagged source revision, then confirm the published downloads match the local artifact hashes. Google Play enrollment, key-continuity decisions, declarations, reviewer access, review, and publication remain owner actions. F-Droid application request/MR, build-server result, review, and publication remain **NOT RUN**. The public privacy-policy URL also remains unpublished.
