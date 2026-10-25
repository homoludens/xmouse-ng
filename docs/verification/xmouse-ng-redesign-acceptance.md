# XMouse-NG redesign — integrated acceptance

Date: **2026-10-25**. Scope: `redesign-xmouse-ng` **3.1 [low]** only, using the recorded user-approved `openai/gpt-6.1-sol#xhigh` exception. Starting revision: `301e4a9`, branch `redesign/xmouse-ng`. Tasks 3.2/3.3, signing, publication, and unrelated `modernize-xmouse` statuses are not completed by this record.

**Later distribution evidence:** task 3.2 subsequently built and verified the signed **2.1.0 (26)** release; see [the signed-release record](xmouse-ng-signed-release.md). The versions, sizes, and out-of-scope distribution statuses below describe the earlier 3.1 snapshot and are not overwritten by that later verification. Task 3.3 publication and real signed-installation migration remain unrun.

## Result and evidence boundary

**Available integration checks passed:** the full JVM suite, lint, both debug APK builds, 36 selected tests actually executed on an isolated API 35 emulator, and production UI smoke. This is **not** a claim of live Linux SSH, physical-device/API 26, or signed-installation migration acceptance.

Local raw logs, reports, screenshots, and smoke scripts are retained under **`/tmp/opencode/xmouse-ng-verification/`**; they are not application assets or committed screenshots. Principal records:

- `final-full-gate.log`, `final-full-jvm.json`, `final-lint.json`, `final-test-build-lint.log`.
- `final-instrumentation-pass-36.txt`: exact class/method filter and runner results, **OK (36 tests)**, 118.634 seconds. Earlier diagnostic/failing runs remain separately recorded, not counted as passes.
- `final-production-ui-smoke.json`, `persisted-actions-ui-smoke.json`, `final-persisted-actions-ui-smoke.json`.
- `final-apk-impact.json`, `resume-dependency-impact.json`, `resume-runtime-dependencies-{baseline,final}.txt`, `resume-debug-badging.txt`.

### Build/JVM gate

The final unfiltered gate used:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

**BUILD SUCCESSFUL.** Actual `app/build/test-results/testDebugUnitTest/TEST-*.xml` totals: **63 classes, 487 tests, 0 failures, 0 errors, 0 skipped**. This includes protected-storage/legacy migration, trust/authentication/deadline, action/profile preservation, settings, text encoding, dispatch, controller, and recording pipeline regressions. Synthetic loopback MINA/JSch tests are not live Linux acceptance. The full suite was rerun after concrete production integration fixes; intermediate debugging used focused tests rather than repeated blanket runs.

Actual `app/build/reports/lint-results-debug.xml`: **217 warnings, 0 errors/fatal issues**. Warnings remain; no broad suppressions or unrelated cleanup were used. Subsequent instrumentation-only synchronization/capture edits were followed by APK compilation and lint again (`final-test-build-lint.log`); no production changes followed the final full JVM run.

### Owned emulator and executed tests

- Inspected installed image: `system-images/android-35/google_apis/x86_64`, revision **9**.
- Created only `xmouse-ng-31-sol-dugww87y`, with `ANDROID_AVD_HOME=/tmp/opencode/xmouse-ng-31-sol-dugww87y/avd`; headless `-no-window -no-audio -no-snapshot -gpu swiftshader_indirect`, explicit port **5582**.
- Every device command used SDK `platform-tools/adb -s emulator-5582`. No physical device was targeted. Emulator identity was checked before synthetic app-data resets.
- **640×1440 px / 320 dpi = 320dp width**, system font scale **1.5**. Fixture contexts also assert the requested theme/font configuration. Production MainActivity was first launched on the clean synthetic installation, creating its normal empty Actions profile, before repository/service fixtures.
- Only synthetic profiles, preferences, databases, protected records, and recording transports were used. The script-output Copy regression reads only its own synthetic emulator clipboard output. No real user's stores, clipboard, computers, credentials, or remote endpoint were accessed.

Final runtime coverage, **36 distinct tests**, not merely instrumentation compilation:

| Selection | Tests | Result |
| --- | ---: | --- |
| `NativeShellAndroidTest` (entire class) | 9 | PASS |
| `NativeSettingsAndroidTest` (entire class) | 2 | PASS |
| `MouseControlButtonsAndroidTest`, `JoystickSurfaceAndroidTest`, `MainActivityInsetsAndroidTest` | 3 | PASS |
| `NativeActionEditorAndroidTest`, `LayoutEditorAccessibilityAndroidTest`, `LayoutEditorPointerAndroidTest`, `LayoutProfileManagerAndroidTest`, `SavedActionScriptRepositoryAndroidTest` | 5 | PASS |
| `NativeDesignCaptureAndroidTest`, explicitly enabled with `captureNativeDesign=true` | 1 | PASS |
| Four selected `CredentialRepositoryAndroidTest` cases below | 4 | PASS |
| Twelve selected `SessionForegroundServiceAndroidTest` cases below | 12 | PASS |

Computer cases: `metadataOnlyComputerEditPreservesCiphertextReferenceAndBackupFlagsWithoutAnySecretAccess`, `unsavedCandidateProtectedAuthenticationAndInvalidSavesNeverChangeHostRowsReferencesOrPreferences`, `nativeComputerEditorUsesPersistentLabelsBlankPasswordAdvancedValidationRecreationAndConfirmedDelete`, and `nativeDiscoveredComputerFormRetainsLogicalServiceAndPortWithoutSavingOnOpenOrBack`.

Service cases: `layoutEditorNeverDispatchesAndNormalControlsUseOnlyTheSavedProfile`, `shellTabsAndSameActivityOverlaysKeepOneSessionAndRejectOldCapabilities`, `legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink`, `cancelledMouseAndKeyboardButtonTouchUpsCannotBorrowResumedSurfaceButAccessibilityStillWorks`, `switchingToSettingsDuringTouchpadDragReleasesOnTheSameSessionAndRejectsStaleInput`, `ydotoolCustomMouseDownReleasesWithCapturedCodeWhenControlActivityPauses`, `touchCancelReleasesDragWithoutClickAndResetsJoystickForFreshDown`, `injectedGyroscopeRegistrationAndLateCallbacksStayOnMainAndStopWithTheSurface`, `mainActivityLifecycleStopsAndRestartsItsGyroscopeInputLease`, `activityRecreationAndReturnWithAutoConnectEnabledReattachToTheExistingSession`, `lateExecOutputFromTheOldPresentationIsNotShownAfterActivityRecreation`, and `scrollableTouchpadDoesNotStealPointerOrJoystickMovement`.

Reproduction shape (exact filter is the first line of the runtime log):

```sh
adb -s emulator-5582 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -r \
  -e captureNativeDesign true -e class "$RECORDED_FILTER" \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
```

The runner's final `INSTRUMENTATION_CODE: -1` is its normal completion marker; acceptance was based on per-test statuses and **OK (36 tests)**, not the adb process exit code alone. This was a focused selection, not the entire Android test suite.

## Acceptance scenario mapping

Paths below are relative to `app/src/main/java/com/stripe1/xmouse/` unless noted. **PASS (synthetic)** means real production code with recording/fixture data, not successful remote hardware behavior.

| Accepted scenario | Code / test / runtime evidence | Status and limit |
| --- | --- | --- |
| Navigate Touchpad → Keys → Actions → Touchpad with one healthy session and no navigation-only commands | `ShellNavigation`, `MainActivity`; JVM `ShellNavigationTest`, `SessionPipelineIntegrationTest`; runtime `shellTabsAndSameActivityOverlaysKeepOneSessionAndRejectOldCapabilities` | PASS (recording service/transport); production disconnected tab smoke also passed. |
| Preparing/connecting/trust/authentication/error/disconnected header and direct reconnect path | `MainActivity.showConnectionStat`, session snapshots/presenter; full controller/pipeline tests; runtime recreation/recovery case checks Connected and Connection failed plus Retry/Disconnect | PASS (synthetic states). No real server connection claimed. |
| Light/dark, 320dp, enlarged fonts, system/cutout/IME insets, 48dp effective controls | Semantic resources, `NativeShellViews`, `SystemWindowInsets`, native forms; runtime shell/settings/mouse/inset/joystick fixtures and screenshots | PASS for exercised controls/configurations. Long content/category rows scroll; bounded long labels can ellipsize with full accessible descriptions. Not an exhaustive accessibility audit. |
| Leave during held drag; stale input cannot resume after tabs, overlays, Settings, pause, recreation | `MainActivity` role gates, retained session controller; full `ControlInputTest`/pipeline/controller suite; runtime held-release, cancel, Settings, ydotool and gyro tests | PASS (recording provider boundary). No new service/session/transport owner introduced. |
| Pointer, clicks, scrolling, hold/drag, quick keys, gyro/joystick, volume shortcuts remain reachable | `MyMouseView`, `MyJSView`, existing input adapters; runtime legacy-control route, cancellation, actual pointer-setting mapping, gyro lease and short-parent gesture tests | PASS (synthetic dispatch); production gyro toggle/joystick return smoke passed. Real sensor accuracy/Linux gestures remain unrun. |
| Literal Unicode/quotes/whitespace/shell-like typing only on Send, plus Esc/Tab/Backspace/Enter | `ShellTextEncoder`, role-scoped typing in `MainActivity`; full encoder/adapter/pipeline tests; runtime explicit Send through recording sink; production real IME editing/Back | PASS. Manual smoke typed synthetic text without Send; encoding/dispatch assertions use recording sinks. |
| Deliberate one-way clipboard export, no automatic paste/background read | Retained clipboard coordinator/Linux helper; full JVM clipboard tests and unchanged explicit route | PASS at unit/route-preservation level; live Linux clipboard export NOT RUN. Only owned synthetic output-copy clipboard behavior was exercised on Android. |
| Common/Navigate/Media/F1–F12 and supported relative volume/brightness/mute | `KeyCatalog`, `NativeShellViews`, adapters; full catalog/backend tests; runtime all-category geometry/selection; production Common/Media screenshots | PASS (synthetic). No unobserved level or decorative polling. |
| Edit computer metadata/port with blank replacement password preserving existing protected reference | `ComputerEditorActivity`, `CredentialRepository.updateHostMetadata`; full credential unit suite; actual SQLite/Keystore fixture checks reference/ciphertext/re-entry flags and no secret read for metadata | PASS (isolated synthetic records). No user's vault opened and no password prefill. |
| Invalid endpoint/port/username or accidental username whitespace leaves form/stored values intact | `ComputerDraft`, credential edit boundary, native form; full validation tests and runtime native computer editor/invalid candidate tests | PASS (synthetic), including recreation and confirmed deletion. |
| Test unsaved candidate: actual authentication/channel readiness, trust first, bounded cancel/cleanup, no Save/command/active-session replacement | `ConnectionProbe`, `AndroidComputerProbe`, existing SSH/trust/credential owners; full probe, transport, loopback trust/authentication, and `unsavedProbe*` pipeline tests; runtime candidate nonmutation fixture | PASS for JVM/recording/loopback behavior and Android repository nonmutation. Actual Android-to-Linux Test connection NOT RUN. |
| Computers selection/add/edit/find/disconnect and real discovery/empty/error presentation | Header native selection, `ListEditActivity`, `ComputerEditorActivity`, existing discovery coordinator; full discovery tests; runtime logical-service/port editor and clean production empty Computers sheet | PASS for exercised UI/data boundaries. Live LAN NSD/mDNS/permission-provider discovery NOT RUN. |
| Existing action Save preserves stable ID/profile/order/raw command/custom width/RGB after reload | `ActionCommandCodec`, `ActionEditorSessionViewModel`, `LayoutProfileRepository`, native form; full action/profile/legacy fixture tests; runtime real native metadata Save/recreate/reopen/Cancel, pointer/accessibility editor and profile-manager tests | PASS (synthetic), including width 7, exact multiline command and ARGB spelling. Production-created synthetic shortcut actions survived force-stop and both themes. |
| Explicit Edit/Done/Add/Delete, named layout selection, uncapped management, reorder/drafts/history; editing never executes | `ActionsViewRenderer`, native/Advanced editors and manager; runtime ID-based picker, five-profile manager, draft-only drag/resize, service editor/no-execution boundary; full editor/history repository tests | PASS (synthetic). Advanced history remains the retained route, not a new primary-screen feature. |
| Deliberately run saved raw script through existing exec/result route, not a custom-button command | `SavedActionScriptRepository`, `MainActivity.showSavedScripts/runSavedScript`, presenter; runtime isolated SQLite CRUD and `legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink`; full separate-route pipeline tests | PASS (recording exec/output/Copy only). No real SSH script executed. |
| Real pointer speed/natural-scroll/auto-clear preferences survive restart and affect input; unsupported preview options absent | `InputPreferences`, `NativeSettingsBinding`, retained Advanced settings; full compatibility suite; runtime production binding/actual pointer calls; manual pointer-speed and natural-scroll force-stop/relaunch | PASS on the synthetic installation. Global key/import UI is retained, not manually operated with real keys. |
| No seeded example computers/actions/scripts/media levels | Fresh empty-profile path, real repository renderers; clean production launch/screenshots before explicit synthetic action creation; full fresh-install/profile tests | PASS. Test-only profiles were explicitly created on the owned emulator, never packaged defaults. |
| Visible rename with unchanged package/API/storage identities | Semantic branding, unchanged `app/build.gradle`; debug badging: `com.stripe1.xmouse`, min API 26; retained storage/fixture tests | PASS (static/fixture). A compatible-signed in-place upgrade with real retained user data is NOT RUN. |
| Externally signed release, certificate/checksum, release-only fixture/reference exclusion | Distribution task 3.2 | NOT RUN / out of scope. Debug inspection is not release-signature verification. No external signing key/password accessed. |
| Source/APK publication to designated GitHub repository | Distribution task 3.3 | NOT RUN / out of scope. Original `origin=https://github.com/bradand/XMouse.git` remains unchanged; no staging, commit, push, force-push, archive, or GH publication. |

## Visual review and production smoke

Compared the native captures with `/tmp/opencode/xmouse-ng-reference/01-touchpad-light.png`, `03-actions-light.png`, and `10-touchpad-dark.png`. The semantic neutral/green palettes, dominant rounded pad/right strip, click/quick-key/typing order, labeled bottom navigation, two-column actions, and system-following surfaces match the approved hierarchy. Actual disconnected/empty state replaces reference example data and success claims. This was visual inspection, **not** a pixel-golden test or a claim that native controls equal browser pixels.

Reviewed evidence:

- `final-screenshots/production-touchpad-{light,dark}-320dp-font150.png`.
- `final-screenshots/production-keys-{light,dark}-{common,relative-media}.png`.
- `final-screenshots/production-actions-{light,dark}-empty.png` and **`final-persisted-actions-screenshots/production-actions-persisted-{light,dark}.png`**. The latter show the final icon-above-label layout and readable distinct Synthetic Alpha/Beta titles after process restart.
- `final-screenshots/production-computers-{light,dark}-empty.png`, `production-settings-{light,dark}-{top,bottom}.png`, `production-action-editor-{light,dark}-{top,bottom}.png`, and `production-joystick-{light,dark}.png`.
- `final-screenshots/production-typing-{light,dark}-{real-ime,real-ime-scrolled,ime-hidden}.png`: actual system keyboard, typed synthetic text, scroll-reachable Send/quick controls, first Back hides IME, next Back closes typing.
- `final-screenshots/production-touchpad-dark-landscape-short{,-scrolled}.png`: actual landscape/short viewport, fixed header/navigation and scroll-reachable controls. Controls need not all fit simultaneously in a short landscape viewport.
- `final-verified-fixture-screenshots/fixture-{actions,computer-editor,action-editor}-*.png`: actual production XML/renderers, synthetic/layout-only data. Computer top/bottom/real-IME captures deliberately avoid the credential Activity's `FLAG_SECURE` rather than disabling that protection. Blank layout-only action form captures do not substitute for production listener tests.

Some earlier software-compositor captures contained transient missing glyphs; they were not accepted as visual proof. Capture settling was added, and the final production persisted-action frames were separately reviewed. Native geometry/text-fit/accessibility assertions remain independent of screenshot appearance.

## Integration corrections retained in 3.1

- Qualified themes now inherit the complete `Base.XMouseNg` Material/DayNight theme, including explicit night API 29 system-bar policy; a JVM resource regression checks every qualifier. No fallback to a non-Material theme.
- Pointer is inserted beneath the scroll-strip buttons rather than covering their drawing/hit testing. Short-screen Touchpad content scrolls, retains a positive base pointer/joystick height, and uses standard parent-interception exclusion for a gesture begun on either input view. No gesture/session state machine was replaced.
- Wrapping/minimum-height controls, baseline alignment, modest quick-key typography/spacing and status allocation avoid narrow/large-font clipping. Left/right mouse icons use Material's supported icon attributes instead of an Android compound drawable that Material could clear. Action icons stack above labels to avoid losing representative long titles at 320dp/1.5×; exceptionally long titles/subtitles ellipsize explicitly and retain full accessible descriptions.
- Saved-script selection runs only after actual owned-dialog dismissal and rejects an intervening navigation generation; the recording runtime exec-route regression passed.
- Fixtures apply font/theme configuration before Activity resources are first read, wait for asynchronous repository/render/Compose-close work, distinguish an equal-to-saved order from a pending draft, use a path-checked target-context SQLite fixture, create their own synthetic controls rather than assume seeded buttons, and scroll to the loaded script section before asserting its visible label. Recreation tests target the new real header and exact Disconnect session control. No test was dropped or an assertion weakened to waive behavior.

## Deliberate design deviations

1. **Stored widths 1–9 occupy one visual Actions cell; width 10 spans a full row.** Stored ten-unit values are unchanged. Custom values remain disclosed and precisely editable in Advanced. New Regular/Wide choices are 5/10.
2. **Volume/brightness are relative minus/plus (and mute)**, not fake sliders, percentages, or continuous polling.
3. Unsupported configurable **Tap to click/Haptic feedback** are omitted. Existing gestures/tap behavior are preserved; no new gesture algorithm was introduced.
4. Full ten-column reorder, drafts, Undo/Redo/history and arbitrary values remain under **Actions → More → Advanced editor**. Large forms use full-height native scrolling surfaces rather than undersized preview sheets.
5. Real Android bars, IME, typography, accessibility descriptions, actual connection state, empty lists, and retained gyro/joystick replace fake browser chrome/demo data. Legacy action colors are preserved, even when a saved light tile remains light in dark mode; foreground contrast is selected rather than rewriting stored colors.

## APK and dependency impact

Same-toolchain debug comparison against pre-redesign **`66e62a9`**, built from an isolated source archive with `:app:assembleDebug` and the same SDK variables (no release build/signing):

| Artifact | Bytes | MiB |
| --- | ---: | ---: |
| Pre-redesign debug APK | 20,227,118 | 19.2901 |
| Final debug APK (`app/build/outputs/apk/debug/app-debug.apk`) | **21,251,097** | **20.2666** |
| Final instrumentation APK (`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`) | **1,986,186** | **1.8942** |

Measured debug growth: **1,023,979 bytes (+5.0624%)**. This is a debug-to-debug measurement, including debug-only tooling/fixtures, not a promised release size. The separate test APK is not a production runtime dependency.

The resolved `debugRuntimeClasspath` dependency graphs are **identical** to baseline (812 dependency-tree lines, zero additions/removals). Gradle build/dependency configuration is unchanged. No browser, WebView, font, animation, or other heavy runtime library was added. APK ZIP inspection found **zero HTML/HTM/JS/MJS, handoff/reference assets, or bundled TTF/OTF files** in the final app/test APKs. Main sources contain no WebView/browser-reference implementation. Debug fixture Activities remain under `src/debug`; release APK exclusion/signature inspection still belongs to task 3.2 and is not inferred from this debug audit.

Debug badging confirms **`com.stripe1.xmouse`**, **minimum SDK 26**, target/compile SDK 37, visible **XMouse-NG**, existing version 25 / 2.0.27. Release version/signing changes were intentionally not made. Storage paths/formats, secret/trust identities, credentials, session ownership, and original upstream remote were not changed by these integration fixes.

## Explicitly not run

- Real Linux/X11/Wayland SSH control, authenticated Android Test connection, remote scripts, actual media/brightness effects, live discovery, or Linux clipboard export.
- Physical devices, API 26 runtime, API 36/37-specific permission/Back behavior, real sensor accuracy, or a human TalkBack traversal (native labels/semantics/targets were asserted on API 35).
- In-place upgrade under the user's release certificate, real-user legacy DB/vault/preferences/profile migration, backup/restore across devices, process-kill fault recovery on hardware, or differently signed update compatibility. Synthetic unit/SQLite/Keystore/profile fixtures are not these checks.
- Release build/signature/fingerprint/checksum/provenance, version rollout, release fixture exclusion inspection, GitHub authentication, staging/commit/push/release/archive. Tasks 3.2/3.3 remain unchecked.

The owned emulator/AVD and isolated baseline source/build directory are cleaned up after verification; logs/screenshots/measurement reports remain in the evidence directory. No existing/user AVD or inherited verification artifact is removed.
