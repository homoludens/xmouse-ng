# XMouse-NG 2.1.1 release verification

Date: **2026-10-28**. GitHub release: [XMouse-NG 2.1.1](https://github.com/homoludens/xmouse-ng/releases/tag/v2.1.1). The release is published on `homoludens/xmouse-ng`; it does not replace the existing v2.1.0 release or its `com.stripe1.xmouse` APK.

## Source and package identity

- Release tag **`v2.1.1`** resolves to source commit **`794d0d636e01079630823accd7f3b5d663fbf406`** (`release: prepare XMouse-NG 2.1.1`). The fork's `main` was fast-forwarded to that commit for publication.
- The artifact reports application ID/namespace **`net.droopia.xmouse_ng`**, visible name **XMouse-NG**, version **2.1.1 (27)**, minimum/target/compile API **26/37/37**, and launcher `net.droopia.xmouse_ng.MainActivity`.
- The instrumentation APK reports **`net.droopia.xmouse_ng.test`**. It was built but not installed/run for this release.

## Build and tests

The credential-safe wrapper ran a forced full build before commit: `:app:testDebugUnitTest`, `:app:lint`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:assembleRelease`. Gradle completed **135/135 tasks**. The unit report contains **549 tests in 74 suites**, with **0 failures, errors, or skips**. Lint passed with **0 errors and 282 warnings**. The final optimized release was rebuilt from the tagged source commit with `:app:assembleRelease` (**53 tasks executed**); raw signing-capable Gradle output was not retained or printed.

## Signed APK

| Property | Verified value |
| --- | --- |
| GitHub asset | `xmouse-ng-2.1.1.apk` |
| Size | 3,727,209 bytes |
| SHA-256 | `daa9bb056913d50c0590a14a7de237f04e654095af467883b356663b273934d6` |
| Signing certificate SHA-256 | `C1:C5:FA:CA:7B:FD:24:CE:2B:F5:46:7A:73:1B:71:B0:21:8B:CB:50:3E:B2:0F:81:4D:37:09:25:A4:B8:8E:ED` |
| APK signature | One RSA-2048 signer; APK Signature Scheme v2 verified |

The optimized DEX/manifest audit passed: **4,614** class definitions, **499** input-event constants, **116** retained JSch implementations, **652** ZIP entries, and **0** test/fixture definitions. APK CRC, manifest/component checks, and 16-KiB ZIP alignment passed. The APK and `.sha256` companion were downloaded from GitHub; the checksum matched and the downloaded APK was byte-for-byte identical to the locally verified artifact.

## Installation and verification limits

`net.droopia.xmouse_ng` is a distinct Android app from `com.stripe1.xmouse`. Android does not transfer or expose the old app's private database, preferences, files, credentials, or Keystore keys. A real old/new side-by-side install and real-user data/credential migration were **not tested**. The dedicated API 26 identity smoke test is documented in the earlier [package identity check](package-identity.md); it was not repeated on the 2.1.1 APK. No physical-device test, live Linux SSH session, human TalkBack review, or old-release installation was performed.

The README now contains Touchpad and Actions screenshots with synthetic display data, a short feature summary, the original [bradand/XMouse](https://github.com/bradand/XMouse) fork attribution, and debug-build instructions. The same installation/data boundary and current release identity are documented in the [release and upgrade guide](../releasing-xmouse-ng.md).
