# XMouse-NG 2.1.3 release verification

Date: **2026-10-29**. GitHub publication was authorized for the `homoludens/xmouse-ng` fork only. This record covers source commit `675bc1fd5880996ba1212ec876ca793137a4964c`, annotated tag `v2.1.3`, and the public non-prerelease release. It does not claim Google Play or F-Droid submission, review, acceptance, or availability.

## Source and publication

- Android identity: package/namespace **`net.droopia.xmouse_ng`**, version **2.1.3 (29)**, visible name **XMouse-NG**, min/target/compile SDK **26/37/37**.
- Source commit: `675bc1fd5880996ba1212ec876ca793137a4964c` (parent `b84b0cff57ab8a290d150c347ccf0ada89f9d1e5`). The source manifest SHA-256 is `c9b1edcf126d33ca711b651b1c8fa65d822948458028a1e7182f8f0eb615087d` (685 files, 15,452,189 bytes).
- The release build used a clean HTTPS clone at that exact commit. `META-INF/version-control-info.textproto` in both APK and AAB records the same revision.
- Fork `main` advanced fast-forward from `b84b0cff57ab8a290d150c347ccf0ada89f9d1e5` to the source commit. Annotated tag object `8f7b72a9b7563b74eb74cf05f69f79a14b345552` (`v2.1.3`) peels to the source commit. No push was made to upstream `bradand/XMouse`; earlier tags/releases were not changed.
- Public latest release: [XMouse-NG 2.1.3](https://github.com/homoludens/xmouse-ng/releases/tag/v2.1.3), published **2026-10-29 02:20:17 UTC**. GitHub reports tag `v2.1.3`, **not draft**, **not prerelease**, and latest. The four uploaded assets were downloaded into a fresh evidence directory; both binaries and both checksum companions matched their local release counterparts byte-for-byte, and downloaded checksums passed.

## Artifacts and signer

| Published/downloaded asset | Size | SHA-256 |
| --- | ---: | --- |
| `xmouse-ng-2.1.3.apk` | 4,029,238 bytes | `a013b081a1441870122e586cbc76ea04bc7fb0edc9033c382d363a814acf61e6` |
| `xmouse-ng-2.1.3.apk.sha256` | 86 bytes | `2809335432ff4823af2d549f26730fa85e5cff22347d643400b927579163424a` |
| `xmouse-ng-2.1.3.aab` | 7,248,134 bytes | `8c01009c0c03e207e0b3290f83c281c2971c0378bc17e2ef743607bed667d3ca` |
| `xmouse-ng-2.1.3.aab.sha256` | 86 bytes | `9b5cbe75e895d52eaa24e9d790e9ebba164c7dd0978f6c53f317bc7f82e68ab4` |

The APK verifies with APK Signature Scheme v2; the AAB passes `jarsigner` verification and bundletool 1.18.3 validation. Both identify the existing maintainer certificate SHA-256 **`c1c5faca7bfd24ce2bf5467a731b71b0218bcb503eb20f814d370925a4b88eed`**. The build used the existing external signing configuration; no signing key or password was printed, copied into the repository/evidence, or attached.

The signed APK/AAB report package `net.droopia.xmouse_ng`, version 2.1.3/code 29, min SDK 26, and target/compile SDK 37. The APK contains one optimized DEX with 4,617 class definitions and 666 ZIP entries; the AAB contains one base DEX with 4,617 class definitions and 675 ZIP entries. CRC checks passed. Static APK/AAB scans found no test or fixture paths/DEX, private signing-material names, source-icon name/hash, or original 1254×1254 artwork. The APK listing validator checked 179 packaged images and found no full-resolution source icon.

`zipalign -c -P 16 -v 4` passed for the signed APK. Every `PT_LOAD` segment in the packaged native library for each of arm64-v8a, armeabi-v7a, x86, and x86_64 is aligned to **0x4000 (16 KiB)** in both APK and AAB. Store listing, screenshot/artwork, launcher-resource, and changelog metadata validation passed; the public privacy-policy URL remains **NOT PUBLISHED**.

## Clean-source tests and local unsigned reproductions

- On the clean source commit: **560 JVM tests passed**, 0 failed/errors/skipped across 77 XML reports; lint passed with **0 errors and 289 warnings**; `assembleDebug` and `compileDebugAndroidTestSources` passed. The full instrumentation suite and emulator/physical-device smoke test were **NOT RUN**.
- Exact comparison against the prior `MainActivity.loadDefaultKeyboardLayout` implementation confirmed all **57 ordered title/command/width/color tuples** match.
- Strict OpenSpec validation, `git diff --check`, and listing/artwork validation passed.
- Two independent clean Git clones of the published source commit were built sequentially with separate isolated Gradle homes and the explicit unsigned F-Droid build mode. Each unsigned APK was **4,016,950 bytes**, SHA-256 `f5c664ea4bd7faaddcb166a3b3e0f32979bfb3070d1abe14026105baf119917c`; the outputs matched byte-for-byte. `apksigcopier compare` passed against the exact clean-source signed APK for both outputs; copying the signed APK signature onto one reproduction passed `apksigner` verification and reproduced the signed APK byte-for-byte.
- These are **local** unsigned source-build and signature-copy results only. The official F-Droid build server and fdroidserver lint/review were **NOT RUN**; no official F-Droid reproducibility claim is made.

## Store and runtime limits

- Google Play upload/publication, Play App Signing enrollment, and Google Play review were **NOT RUN**. The AAB is a locally signed prepared artifact only.
- No F-Droid application request/submission, official build-server run, review, or publication occurred. The F-Droid metadata remains an explicitly disabled preparation draft; it now points to this published version and source commit.
- Public HTTPS hosting of the privacy policy, signed install/in-place upgrade with real retained user data, physical-device testing, API 26/36/37 runtime checks, full instrumentation, human accessibility review, and live Linux/SSH integration were **NOT RUN**.
- Existing app identity and signing continuity are preserved; this package remains separate from `com.stripe1.xmouse`, with no cross-package private-data transfer.
