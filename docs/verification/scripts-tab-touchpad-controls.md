# Scripts tab and Touchpad controls verification

Date: 2026-10-27

## Behavior covered

- The bottom navigation exposes Touchpad, Actions, and Scripts. Scripts has no input role; its destination hides the shared composer while retaining its in-memory draft. A script runs only from its explicit row action.
- Add, Edit, Manage, and confirmed Delete use the existing SQLite-backed script manager. The Actions landing page does not create actions; creation remains available in the advanced layout editor.
- The drawer contains computer, settings, and application routes only. Session controls, scripts, layout management, legacy layout tools, and clipboard transfer are not drawer entries.
- Touchpad has five quick controls: Escape, Tab, Backspace, Enter, and clipboard send. Controls expose accessible names and tooltips; each target is at least 48dp. Clipboard transfer is an explicit, readiness-gated plain-text exec/stdin operation, never keyboard typing.
- Touchpad/header/composer page edges and all measured Touchpad control gaps—including the composer field-to-Send gap—use a normalized 12dp spacing scheme. The Backspace quick key shows a left arrow inside its key outline; its Backspace accessible name/tooltip and key dispatch are unchanged. The Touchpad scroll content adds no bottom padding.

## Build and JVM results

Final gate command:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- Final result on the exact refinement tree: `BUILD SUCCESSFUL` (26 seconds; 85 actionable tasks, 1 executed, 84 up-to-date).
- `:app:testDebugUnitTest` was `UP-TO-DATE`; the 66 current JVM reports record 496 tests, 0 failures, 0 errors, 0 skipped.
- `:app:lintDebug`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed. The lint report contains 258 warnings and 0 errors; warnings include existing resource, localization, accessibility, and API/style findings.
- Gradle also emitted the project setting deprecation warning for `android.enableJetifier=true` (default is false; removal is announced for AGP 10.0).
- `git diff --check` passed after the final test changes.

## Runtime device and command scope

Earlier runtime coverage used the owned API 35 AVD `xmouse_final_api35_20261026`. After the tool-environment restart removed its temporary AVD registry, the final refinements were verified on a locally recreated API 35 Google APIs x86_64 AVD, `xmouse_final_refinements_api35_20261027`, serial `emulator-5598`, through isolated ADB server port `5049`. Only that emulator appeared in `adb devices -l`. The new AVD used the already installed system image (no SDK download); display override was 960x2160 at 480dpi (320x720dp). Narrow screenshot and layout fixtures used 320dp and font scale 1.5; system font scale remained 1.0 outside those fixtures.

All connected-test invocations used the following pinned environment and serial argument; no unscoped connected test or physical device was used:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5049
export JAVA_TOOL_OPTIONS=-DANDROID_ADB_SERVER_PORT=5049
export ANDROID_SERIAL=emulator-5598

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5598 \
  -Pandroid.testInstrumentationRunnerArguments.class=<explicit-class-or-method-filter>
```

Runtime results:

| Explicit test selection | Result |
| --- | --- |
| `NativeShellAndroidTest,NativeSpacingAndroidTest,MainActivityInsetsAndroidTest,MouseControlButtonsAndroidTest,ShellControlRefinementsAndroidTest` | 21 passed, 1 skipped. The skip was the opt-in screenshot test; it was run separately below. |
| `NativeShellAndroidTest` after the drawer-label adjustment | 13 passed, 0 failed. |
| `NativeSpacingAndroidTest` (final focused rerun after adding narrow-width measurements) | 3 passed, 0 failed. Confirms resolved gutters and 12dp gaps at standard and 320dp/1.5× fixture sizes, aligned composer edges, equal-weight quick keys, and minimum targets. |
| Final refinement rerun: `NativeSpacingAndroidTest` | 3 passed. Also verifies the exact 12dp measured gap between the composer field and Send. |
| Final refinement rerun: `ShellControlRefinementsAndroidTest` with `captureNativeDesign=true` | 4 passed, 0 skipped; captured both final Touchpad themes and passed drawer/tap regressions. |
| `NativeShellAndroidTest#inlineTypingUsesSendImeAndSharedComposerWithoutFocusOrTypingOverlay` | 1 passed; Backspace accessible naming and inline-composer semantics remain intact. |
| Final rerun: `SessionForegroundServiceAndroidTest#legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink` | 1 passed using the recording transport; script CRUD/dispatch, BackSpace command path, and clipboard safety checks remain green. |
| `SessionForegroundServiceAndroidTest#legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink` | 1 passed. Exercises synthetic SQLite Add/Edit/Manage/confirmed Delete, explicit script Run through the recording exec channel, and clipboard safety/readiness cases. |
| `SessionForegroundServiceAndroidTest#sharedComposerGatesBlankAndDisconnectedSendAndRetainsDraftAcrossReconnect, #layoutEditorNeverDispatchesAndNormalControlsUseOnlyTheSavedProfile, #shellTabsAndSameActivityOverlaysKeepOneSessionAndRejectOldCapabilities` | 3 passed. |
| `layout.NativeActionEditorAndroidTest` | 1 passed. Save/recreation/reopen preserve exact legacy values; cancel does not write. |
| `ShellControlRefinementsAndroidTest` with `captureNativeDesign=true` | 4 passed, 0 failed. Includes the 320dp/1.5× captures, wrapped drawer-label measurement, and real drawer/header tap regressions. |
| `NativeDesignCaptureAndroidTest` with `captureNativeDesign=true` | 2 passed; captured landscape layouts and a real visible system IME with the focused composer. |

The final focused set above passed 9/9 tests. The session tests used synthetic script/clipboard content and a recording SSH transport. No remote machine received commands. No dedicated instrumentation test for dispatch from the inline icon quick key was found; the existing inline semantics test and recording-transport BackSpace command-path test were run, and dispatch production code was not changed.

## Failure and rerun record

- The first spacing run expected the base 14dp gutter, but the 390dp fixture resolved the then-active 18dp `values-w360dp` override (42px expected, 54px measured). The check was changed to read the resolved resource; inspection showed the wider gutter also mixed with 8dp control gaps and 16dp header padding, so those Touchpad/header/composer values were normalized to 12dp across widths. Intermediate focused runs also corrected bounds assertions to target the visible composer fields rather than their wrapper and to use icon keys' accessible descriptions. Final spacing runs passed, including the narrow 320dp/1.5× geometry assertions.
- The first 22-test shell/layout run had 6 failures: icon-key accessible names were read from empty button text; keyboard-focus checks had not entered keyboard navigation; the outside-tap coordinate used physical rather than live app-window bounds; and destination/large-font sizes were asserted before re-layout. The test setup/assertions were corrected without dropping the accessibility, focus, outside-dismiss, or 48dp requirements. The full selection reran with 21 passed and 1 expected opt-in skip.
- Visual review then found the long drawer label was clipped by fixed-height 48dp rows at 320dp/1.5× (pre-fix [light](evidence/runtime-scripts-tab-touchpad-20261027/drawer-light.png) and [dark](evidence/runtime-scripts-tab-touchpad-20261027/drawer-dark.png) captures). Drawer actions now use `wrap_content` with a 48dp minimum, and the runtime check confirms the wrapped Authentication & SSH label has enough row height for all lines. Refreshed light/dark drawer captures show the complete label; the refinement class passed 4/4 and `NativeShellAndroidTest` passed 13/13 after this change.
- The first grouped session/script attempt had 4 failures, including cleanup trying to delete the last saved layout profile and later test cleanup encountering the prior failed session owner. Focused retries also exposed test assumptions about the exact delete-confirmation text, returning to an input-capable destination after Scripts, `performClick()`'s return value for a disabled view, and when to snapshot the recording command order. Cleanup now retains a synthetic profile anchor, and the test assertions/snapshots follow the actual UI and session lifecycle. The final script/clipboard test passed alone (1/1), and the three composer/editor/tab tests passed together (3/3).
- Every connected-test invocation emitted `[EmulatorConsole]: Failed to start Emulator console for 5598`; ADB still listed the pinned emulator, instrumentation executed, and successful reruns completed normally. Treat this as an emulator-console warning, not a test pass/failure signal.
- The opt-in screenshot test was skipped in the broad run because its capture argument was absent; the explicit opt-in screenshot run passed (1/1).
- During the final verification attempt, the tool environment restarted and the first spacing invocation was interrupted before instrumentation completed. Its partial log did not contain test results; the temporary API 35 AVD was recreated locally from the installed image and the focused reruns above completed successfully.
- The local `avdmanager` reported that it understands SDK XML through version 3 while encountering version 4; AVD creation nevertheless succeeded using the installed image. Each connected-test run also emitted the emulator-console warning recorded above, while the explicitly serial-pinned instrumentation succeeded.

## Screenshot evidence

Fifteen captures are retained in `docs/verification/evidence/runtime-scripts-tab-touchpad-20261027/`, including pre-fix drawer images for comparison and the fresh final Touchpad light/dark pair; existing historical evidence was not overwritten or removed.

| Capture | Evidence |
| --- | --- |
| Touchpad, final left-arrow/12dp refinement, light / dark | [final light](evidence/runtime-scripts-tab-touchpad-20261027/touchpad-final-light.png) · [final dark](evidence/runtime-scripts-tab-touchpad-20261027/touchpad-final-dark.png) |
| Actions, light / dark | [light](evidence/runtime-scripts-tab-touchpad-20261027/actions-light.png) · [dark](evidence/runtime-scripts-tab-touchpad-20261027/actions-dark.png) |
| Scripts, light / dark | [light](evidence/runtime-scripts-tab-touchpad-20261027/scripts-light.png) · [dark](evidence/runtime-scripts-tab-touchpad-20261027/scripts-dark.png) |
| Drawer, light / dark, wrapped label at 1.5× | [light](evidence/runtime-scripts-tab-touchpad-20261027/drawer-light-wrapped-label.png) · [dark](evidence/runtime-scripts-tab-touchpad-20261027/drawer-dark-wrapped-label.png) |
| Touchpad landscape, light / dark | [light](evidence/runtime-scripts-tab-touchpad-20261027/touchpad-landscape-light.png) · [dark](evidence/runtime-scripts-tab-touchpad-20261027/touchpad-landscape-dark.png) |
| Touchpad with real system IME | [real IME](evidence/runtime-scripts-tab-touchpad-20261027/touchpad-real-ime.png) |

Final portrait captures are 960×2160; earlier landscape captures are 2160×960. The final Touchpad captures visibly show the Backspace left arrow and composer Send spacing. All shell-page screenshots use synthetic data. Landscape uses the test fixture's requested orientation; the separate insets test also exercises landscape-shaped safe-area geometry.

## Warnings and limitations

- Screenshot instrumentation publishes images to the emulator's existing `Pictures/XMouseNGShellControlRefinements` and `Pictures/XMouseNGFinal` MediaStore paths, adding suffixed names on reruns. Those device files were not deleted; the selected fresh captures were copied to the new evidence directory above.
- The recreated AVD was stopped after capture and focused tests; no physical device was attached. The isolated ADB daemon may respawn under PID 1 with no device, and was not repeatedly terminated.
- Accessibility names, focus, and targets were asserted by instrumentation; TalkBack was not manually operated. Runtime clipboard cases used synthetic Android clipboard contents and the recording transport; the real Linux clipboard helper and a live SSH host were not exercised.
- No physical device was connected or tested. OpenSpec artifacts and prior verification/evidence records were left unchanged. No source changes were committed or pushed.
