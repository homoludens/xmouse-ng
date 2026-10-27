# Native shell control refinements — verification

Date: **2026-10-26**. Verification of the compact Touchpad/Actions pages, menu touch behavior,
Actions editor route, and text-only Send control. Historical OpenSpec and verification records were
left unchanged.

## Results

- `ShellControlRefinementsAndroidTest`: **3 tests passed** for the compact page layout, one-tap
  hamburger/Computers interactions (including after composer focus and during nonmodal quick edit),
  and modal picker gating. Its optional screenshot test was then run separately with
  `captureNativeDesign=true` and **passed** in both themes.
- Focused existing Android regressions: **12 tests passed** across `NativeShellAndroidTest` (7),
  `NativeSpacingAndroidTest` (2), `MainActivityInsetsAndroidTest` (1),
  `NativeActionEditorAndroidTest` (1), and
  `SessionForegroundServiceAndroidTest.layoutEditorNeverDispatchesAndNormalControlsUseOnlyTheSavedProfile`
  (1). The session integration confirms Actions Edit opens the advanced editor, editor operations
  stay draft-only and do not dispatch, and normal controls use only the saved profile.
- Full Gradle gate passed:

  ```sh
  ANDROID_HOME=/home/homoludens/Android/Sdk \
  ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
  ```

  Result: **496 unit tests / 66 suites, 0 failures, 0 errors, 0 skipped**; lint **0 errors, 234
  warnings**; debug and Android-test APKs built successfully. `git diff --check` passed.

## Focused instrumentation coverage

The 12-test selector included:

- `NativeShellAndroidTest`: `narrowLargeFontHeaderAndLabeledBottomRoutesRemainReachableInBothThemes`,
  `twoTabNavigationRetiresCapabilitiesAndKeepsSharedInlineComposerAvailable`,
  `inlineTypingUsesSendImeAndSharedComposerWithoutFocusOrTypingOverlay`,
  `hamburgerDrawerSuspendsInputAndBackRestoresTheSharedDraftAndDestination`,
  `drawerSelectionKeepsFocusOnTheOpenedComputersSheet`,
  `actionsEmptyEditDoneAndIdBasedPickerShareProductionRenderingWithoutCommands`, and
  `virtualPresetPickerRendersReadOnlyTenColumnTilesAndRetainsComposerAcrossIdCollision`.
- Both `NativeSpacingAndroidTest` geometry/persistence cases and
  `MainActivityInsetsAndroidTest.headerAndBottomNavigationStayInsideInsetsAcrossImeAndLandscapeChanges`.
- `NativeActionEditorAndroidTest.metadataSaveRecreationAndReopenKeepExactLegacyValuesAndCancelDoesNotWrite`.
- The recording-transport layout-editor/session test named above.

The drawer focus test explicitly sends Tab before checking focus restoration, so it exercises the
keyboard-navigation path rather than relying on touch mode left by a preceding real-touch test.

## Visual evidence

Fresh **960×2160** captures were produced on the owned `xmouse_final_api35_20261026` emulator
(`emulator-5598`, Android 15/API 35, 320dp width, 1.5 font scale):

| Page | Light | Dark |
| --- | --- | --- |
| Touchpad | [touchpad-light.png](evidence/shell-control-refinements/touchpad-light.png) | [touchpad-dark.png](evidence/shell-control-refinements/touchpad-dark.png) |
| Actions | [actions-light.png](evidence/shell-control-refinements/actions-light.png) | [actions-dark.png](evidence/shell-control-refinements/actions-dark.png) |

The captures retain labeled bottom tabs, omit the in-page Touchpad/Actions headings and separate
Layout label, show Edit beside the picker, and show Send as text without an icon. The fixture data is
synthetic.

## Environment and scope

The initial real-touch attempt was intercepted by an emulator **“System UI isn’t responding”**
dialog; the target hamburger received no touch event. After restarting the owned emulator and waiting
for its UI to recover, the real `UiDevice` tap test passed, as did the full focused selections above.
No physical device was used. Editor/session checks used synthetic profiles and a recording transport;
no live SSH host, credentials, remote command, release build, commit, or push was involved.
