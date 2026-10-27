# Control performance verification

Date: **2026-10-27**. Change: `optimize-control-performance`, all six approved optimizations. Used the explicitly approved **`openai/gpt-6.1-sol#xhigh` low-tag exception**. Final integration verification was serialized by one agent. **Not committed:** independent review and the requested commit remain with the parent session. This pure performance/refactor change explicitly uses `skip_specs: true`; application identity, schema, credential handling, command ordering and API floor are unchanged.

## Integrated gate

SDK variables both pointed to `/home/homoludens/Android/Sdk`, with SDK platform-tools first in PATH. Selected toolchain remains **Temurin 21.0.7+6 / Gradle 9.3.1 / AGP 9.1.1 / R8 9.1.31**, API **26 / 37 / 37**. Release mapping independently reports R8 9.1.31 / min API 26.

Actual commands used `/tmp/opencode/control-performance/safe-gradle.py`:

```sh
python3 /tmp/opencode/control-performance/safe-gradle.py \
  /tmp/opencode/control-performance/full-gate \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# After correcting only an instrumentation notification-title expectation:
python3 /tmp/opencode/control-performance/safe-gradle.py \
  /tmp/opencode/control-performance/full-gate-final \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 /tmp/opencode/control-performance/safe-gradle.py \
  /tmp/opencode/control-performance/release-constructor-final :app:assembleRelease
```

The local wrapper sets both SDK variables and runs `--no-daemon --no-configuration-cache --no-build-cache --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g`. Only allowlisted task/status/fixed guard/R8 missing-class diagnostics are retained. It never reads external Gradle properties or private-key bytes, prints signing inputs, or saves raw secret-capable Gradle output. Signing uses only the existing external owner configuration.

| Gate | Actual result |
| --- | --- |
| Initial four-task gate | **BUILD SUCCESSFUL**, 455.932s; 85 actionable tasks, 18 executed / 67 up-to-date |
| Full JVM suite | **549 tests, 74 suites, 0 failures/errors/skips**; executed in the initial gate |
| Final four-task gate | **BUILD SUCCESSFUL**, 254.370s; 85 actionable tasks, 8 executed / 77 up-to-date; unchanged JVM execution up-to-date, instrumentation recompiled/repackaged and test lint rerun |
| Final lint XML | **0 errors/fatal findings, 282 warnings**; no baseline/category disablement/severity downgrade added |
| Final signed optimized release | **BUILD SUCCESSFUL**, 429.051s; 53 actionable tasks, 9 executed / 44 up-to-date; R8 executed, resource-shrinking outputs and signed packaging present |
| Final strict/whitespace checks | `openspec validate optimize-control-performance --strict` and `git diff --check` passed |

### Task-focused coverage

- **1.1:** `ComputerMetadataSnapshotTest` **3** cases: single fresh enumeration, changed/deleted/malformed selection, fallback/defaults, metadata-only worker, no database call in `getPreferences`, generation guards. `DatabaseHandlerResourceTest` **3** cases cover all five read helpers: populated/empty cursors, read/close failures and query-creation failure, with cursor-before-database cleanup. API 35 recording tests cover auto-connect, recreation, out-of-order selection and disconnect invalidating pending metadata.
- **1.2:** `ShellContentRenderCacheTest` **2** cases cover hidden/equal/changed/loading/error snapshots and capability retirement. The new real-UI navigation regression passed: unchanged Actions/Scripts retain View identity, hidden refreshes stay lazy, actual changes rebind commands, order/accessibility descriptions remain correct, detached views cannot dispatch, and stale touch UP is canceled. Action placement uses one ordered traversal.
- **1.3:** `SessionInteractionsTest` **5** total cases include opt-out before dispatcher work, listener opt-in, FIFO results and generation suppression. Updated recording-transport instrumentation passed for touchpad, keyboard/volume/custom controls, scripts, recreation, stale screens and disconnected input. Hidden-label text is no longer the command oracle; exec/clipboard feedback stays subscribed.
- **1.4:** `TouchpadDrawingPerformanceTest` **1** source regression guards bitmap-free direct drawing. Its API 35 companion **1** test rendered the real View into test-owned canvases at three sizes and checked path, pointer circle, scroll strip and transparent interior. Existing scalar/natural-scroll and pointer/editor runtime tests passed. This is automated pixel/behavior coverage, not human visual/allocation profiling.
- **2.1:** `LayoutEditorNameInputTest` **22** deterministic queued/in-flight/failing executor/file cases passed in the full suite: only latest pending name, started-write completion, revision-based immediate form text versus acknowledged durable draft, initialization/recreation, edit/Save/Back/Discard/Cancel/XML barriers, invalid input, recovery, failure/retry and unchanged saved bytes until Save. The earlier delegated **39-test** focused pass is in `/tmp/opencode/editor-name-focused-tests-final.log`; the integrated **549-test** run is the full-tree gate. Real editor pointer/XML flows also passed.

## Owned API 35 instrumentation

Used **`xmouse_unified_settings_api35` / `emulator-5598`**, API 35, fingerprint `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`. Absolute SDK **ADB v41**, isolated port **5049**, `ADB_SERVER_SOCKET` unset. Default 5037/user ADB/device context was untouched. Disposable AVD debug/test packages were updated under compatible debug certificates, without clearing app data.

Selected seven `SessionForegroundServiceAndroidTest` methods: `activityRecreationAndReturnWithAutoConnectEnabledReattachToTheExistingSession`, `autoConnectOffIdleBindTeardownThenEnableAndReturnConnectsExactlyOnce`, `navigationRetainsUnchangedContentButRefreshRebindsCommandsAndCancelsStaleTouches`, `legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink`, `cancelledMouseAndKeyboardButtonTouchUpsCannotBorrowResumedSurfaceButAccessibilityStillWorks`, `outOfOrderHostSelectionSuccessAndFailureCannotOverrideNewestForegroundRequest`, and `notificationDisconnectInvalidatesPendingMetadataBeforeItCanStartTransport`.

Also ran the full `TouchpadDrawingPerformanceAndroidTest`, `LayoutEditorPointerAndroidTest`, `XmlLayoutEditorAndroidTest` and `UnifiedSettingsAndroidTest` classes. Command pattern: `adb -P 5049 -s emulator-5598 shell am instrument -w -r -e class <comma-separated selections> com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner`.

**Final: 19 discovered, 17 passed, 2 existing opt-in screenshot methods assumption-skipped, 0 failures; 333.588s.** Log: `/tmp/opencode/control-performance/debug-focused-api35-recovered.log`. These are debug recording/owned-emulator tests, not release-network or real-user acceptance.

The first run had **8 failures**. Screenshot/UI hierarchy confirmed **“System UI isn't responding”** covered the app; after choosing **Wait**, affected tests passed without relaxed assertions. One independent failure was the stale literal **“XMouse remote session”** in the notification helper; it now checks the actual `session_notification_title` resource (**XMouse-NG**). No notification production behavior changed. Initial log and ANR evidence remain in the evidence directory.

## Targeted shrinking and actual optimized-code tests

Release enables **code and resource shrinking** with existing optimizing Android defaults. Cached JSch **2.28.8**, BC **1.86** and dnsjava **3.6.5** JARs had no consumer keep rules; their actual loading bytecode was inspected.

- **Input constants:** retain only `InputEventCodes.KEY_*` public static integer fields by name/value because production `YDoTool` constructs arbitrary names and calls `getDeclaredField`. The app remains shrinkable/obfuscatable.
- **JSch:** retain configuration-loaded implementation names/no-argument constructors, constrained by crypto/KDF/key-generation interfaces, `KeyExchange` and four relevant auth classes. Include **package-private** constructors. Preserve the base `com.jcraft.jsch` **package name**, not all classes/members, so relocated callers can access package-private reflective auth/cipher constructors. The backend selects password/public-key, not optional GSSAPI.
- **BC:** production creates a private provider instance only for **AES/CBC/PKCS7Padding**; PEM/key/KDF/EdDSA paths otherwise use directly referenced lightweight APIs. Retain AES **`$Mappings`, `$ECB`, `$AlgParams`** constructors and the outer AES name used to derive SPI names. No whole-BC/application keep; global providers remain unchanged.
- **dnsjava:** A/AAAA use Type constructor factories, not reflective record names; no DNS-wide keep. Resolver SPIs remain excluded. Only exact `-dontwarn lombok.Generated` (RuntimeInvisible build marker) and `-dontwarn org.slf4j.impl.StaticLoggerBinder` (absent optional SLF4J binding/NOP fallback) were added. Optimized DNS execution observes the expected NOP diagnostic and succeeds. No blanket `dontwarn`/`ignorewarnings`.
- **Android:** actual AAPT rules retain manifest components, custom XML Views and both XML onClick methods; Android defaults/AndroidX consumers retain framework entry points. The audit checks actual definitions, not only source/DEX strings.

### Exact signed-release DEX, without test-generated keeps

Helpers under `docs/verification/control-performance/`:

- `audit-release.py` decodes DEX class/member **definitions**, checking **499** constants, **116** non-GSSAPI JSch names **and constructors**, reflection-access packages, Android/XML entries, absence of test/fixture payloads, mapping/configuration, signature, identity, CRC and alignment. It emits an artifact-specific runtime map.
- `ReleaseDexProbe.java` / `run-release-dex-probe.py` compile an **external Android shell probe**, never an app source/R8 input: **no test-derived keep rules**. Its application classpath is the exact signed optimized APK plus probe DEX; harness dependencies are Android platform APIs only. Mapping resolves surviving private/obfuscated production entries. No unoptimized app/JSch/BC classes are supplied. Owned AVD identity/API/boot and device-side APK SHA-256 are checked before execution.

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
python3 docs/verification/control-performance/audit-release.py \
  --apk app/build/outputs/apk/release/app-release.apk \
  --mapping app/build/outputs/mapping/release \
  --output /tmp/opencode/control-performance/release-audit
python3 docs/verification/control-performance/run-release-dex-probe.py \
  --serial emulator-5600 --avd xmouse_control_performance_api26 --api 26 \
  --apk app/build/outputs/apk/release/app-release.apk \
  --evidence /tmp/opencode/control-performance --log release-dex-api26-final
# Same probe: emulator-5598 / xmouse_unified_settings_api35 / --api 35
# --log release-dex-api35-final
```

**38/38 checks passed independently on API 26 and API 35:** all 499 reflective constants/aliases; nine actual YDoTool translations (chords/media/brightness/rare macro/unknown fallback); 116 JSch constructor lookups; Android BC EdDSA/XDH routing; private BC AES JCA mapping/SPI encryption/decryption; production bounded normalization plus **real JSch sign/verify** for RSA/EC plain/encrypted fixtures, encrypted PKCS#8, Ed25519 and Ed448; six wrong/missing-passphrase rejections and malformed-key rejection; unchanged global crypto/JSch configuration; SAX round-trip and four invalid identity/DTD/schema/width rejections; production legacy migration reader; compressed A/AAAA decoding and malformed-tail rejection.

The 116 lookups comprise **110 constructions + 6 exact expected Java-only stub rejections** (Java 11/15/24 multi-release JCE alternatives); BC routes provide actual Android implementations. These are explicitly unsupported alternatives, not skipped required crypto tests.

API 26 used newly created **`xmouse_control_performance_api26` / `emulator-5600`**, fingerprint `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys`, under `/tmp/opencode/control-performance/runtime/`. Additionally installed the **same signed APK** after asserting its package was absent, launched MainActivity, and opened Touchpad settings through its menu. **Fresh signed install, launcher/custom-View and real native settings/XML inflation passed**, with saved UI hierarchies/screenshots. No Connect, key import, user data or SSH endpoint was used. This is not an upgrade test. Both owned AVDs and isolated ADB server 5049 were stopped after verification; default server 5037 remained untouched.

### Pre-acceptance failures/fixes

The first R8 build failed on the two optional references above. The first artifact audit caught four missing reflective KDF implementations: **`KDF`**, not the older `PBKDF` interface, was needed. Optimized execution exposed public-constructor-only rules dropping package-private cipher/auth constructors; rules now retain those constructors and their caller package, with a DEX audit guarding both. Probe development corrected expected superclass messages for deliberate Java-only stubs, an invalid underscore-containing synthetic DNS host and ad-hoc UI-label assumptions. No production behavior/assertion was weakened to mask failures. Final successful evidence supersedes intermediate artifacts/probe runs.

## Signed artifact/content/provenance

| Field | Actual final result |
| --- | --- |
| Package / version / name | `com.stripe1.xmouse`, **26 / 2.1.0**, **XMouse-NG** |
| Minimum / target / compile SDK | **26 / 37 / 37** |
| Release APK | `app/build/outputs/apk/release/app-release.apk`; retained `/tmp/opencode/control-performance/xmouse-ng-2.1.0-optimized.apk` |
| Release bytes / SHA-256 | **3,727,197**; `36cc4f4927a3cdcea35d966b4b6936cc298093798652d91d0fb2d8cd2e135db7` |
| Signature | `apksigner verify --verbose --print-certs` passed; **one signer, v2 verified** |
| Certificate SHA-256 | **`C1:C5:FA:CA:7B:FD:24:CE:2B:F5:46:7A:73:1B:71:B0:21:8B:CB:50:3E:B2:0F:81:4D:37:09:25:A4:B8:8E:ED`**, exact expected match |
| Mapping SHA-256 | `1da487011f33667771d375c68c287e81146ab640ae02a0e5e59f3a3cced9e3ab` |
| Actual contents | **1 DEX / 4,614 class definitions / 652 ZIP entries**; CRC and 16-KiB-compatible ZIP alignment passed |
| Debug APK bytes / SHA-256 | **21,073,777**; `ddc94318e7d7a2aa2f6afbf38ff36beb1d202a84daeb43c2c5a1e29da6ade940` |
| Test APK bytes / SHA-256 | **1,960,754**; `77c3cf613591d93bc2b30f442a85e7c7d151c5de56112f70ae8731ec219f346e` |

Actual manifest has no instrumentation, debuggability or `testOnly=true`; manifest/custom-XML classes/onClick methods are defined. ZIP/definition/mapping checks found no source-derived debug/test/fixture classes, AndroidX Test/JUnit/Mockito/MINA SSHD, DNS resolver SPIs, fixture keys/data, signing material, nested APKs, HTML/JavaScript/browser payloads or external fonts. Only assets are generated `assets/dexopt/baseline.prof` / `baseline.profm`. Merged SPI entries reference defined optimized classes. Mapping confirms app obfuscation; effective configuration has no global shrinking/optimization/obfuscation disablement or blanket warning ignore.

**Signing safeguards:** seven expected failures passed: each required property blank individually; nonexistent external store; existing in-repository file; direct `packageRelease` with all four blank. Every case failed with the intended guard, not missing tasks. No unsigned/debug/replacement-key fallback, private property/key read, password argument, signing-input dump or signing change.

Built from the **uncommitted working tree** over HEAD **`9b717d1d78889d00adb35a61088f6e409b43c308`**, not HEAD alone. `release-source-inputs.json` hashes **310 production/build/wrapper inputs**. **No same-tree unshrunk baseline was built.** The historical **15,144,342-byte** October 25 APK is a different source snapshot, **not a baseline**; no size-reduction percentage/attribution against it is claimed. Current size is an artifact measurement, not a runtime benchmark.

Evidence is outside Git under `/tmp/opencode/control-performance/`: safe Gradle logs/metadata, JVM counts, signing-negative results, APK/checksum, copied mapping/configuration/resource/usage reports, signature/badging/manifest/alignment/audit JSON, source hashes, per-AVD identities, release DEX logs, synthetic fixtures and UI/ANR evidence. No APK/private key/synthetic key fixture was added to production sources.

## Not run / remaining limits

- **Not run:** CPU/frame/allocation/battery/latency profiling, human visual/TalkBack review, API 36/37/physical devices, production-key/live Linux SSH/X11/Wayland/control, full optimized-release SSH handshake/authentication, full release migration/checkpoint/credential-repository suites, real-user retained-data upgrade, process-kill/device-to-device recovery, publication or commit.
- Optimized migration runtime evidence is the **actual retained CSV reader**, not full persistence/upgrade acceptance; full JVM migration/recovery regressions passed. Release DEX checks are not Activity lifecycle/session-networking tests; fresh API 26 UI smoke covers real framework/XML/resources, not all screens.
- The **282 lint warnings** remain visible. The two screenshot methods were **skipped**, not passed. Unavailable checks above are **not run**, never inferred from builds/signatures/debug runtime/DEX success.
