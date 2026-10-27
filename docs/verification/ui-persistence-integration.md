# UI and persistence integration package verification

Date: 2026-10-26

## Scope

- Matched the native computer editor to the handoff form hierarchy with persistent external labels, neutral rounded fields, optional display name, password visibility control, saved SSH port disclosure, sticky Test/Save actions, and separately confirmed Delete. Existing validation, credential preservation, global authentication, permission requests, and test-connection lifecycle remain in `ComputerEditorActivity`.
- Reworked quick Computers into a modal rounded bottom card with saved-host selection/connect/edit, Add, Find, Manage, and the existing live Connect/Disconnect route.
- Routed Touchpad and Keyboard settings to distinct sections while retaining the all-settings default. Added the persisted Send Enter after text switch.
- Added System/Light/Dark appearance via AppCompatDelegate, About app/version information, and made the Compose layout editor screens AppCompat-aware so their resource-backed palette follows the selected appearance.
- Extended the credential-free metadata backup allowlist for appearance and Send Enter preferences.

## Verification

The Android SDK was available at `/home/homoludens/Android/Sdk`; Gradle commands were run with `ANDROID_HOME` and `ANDROID_SDK_ROOT` set to that path.

Passed:

- Targeted `:app:testDebugUnitTest`: **34 tests**, 0 failures, 0 errors.
  - `InputPreferencesTest`
  - `ShellNavigationTest`
  - `AppearancePreferencesTest`
  - `UiPersistenceIntegrationTest`
  - `ComputerDraftTest`
  - `ComputerCredentialEditsTest`
  - `InlineTextDispatchTest`
  - `ShellTextEncoderTest`
- `:app:assembleDebug`
- `:app:assembleDebugAndroidTest` (instrumentation sources compiled into the test APK; not run)
- `git diff --check`

No emulator/device installation or instrumentation execution was performed.

## Recommended emulator filters for later integration

- `NativeShellAndroidTest#nativeComputerFormLayoutHasPersistentLabelsBlankPasswordToggleAndScrollableNarrowLargeFontTargets`
- `NativeShellAndroidTest#quickComputersUsesModalBottomSheetCardAndKeepsRealManagementRoutesLabeled`
- `NativeSettingsAndroidTest#drawerSectionsShowOnlyTheirOwnControlsAndSendEnterPersistsAcrossReopen`
- `NativeSettingsAndroidTest#savedBindingsChoicesBackAndAdvancedRemainReachableWithInsetsAndLargeFontsInBothThemes`
- `NativeSettingsAndroidTest#actualPointerViewUsesRestoredScalarAndOriginalNaturalScrollingMapping`
- `AppearancePreferencesAndroidTest` (both preference application and Compose palette checks)
- `CredentialRepositoryAndroidTest` (saved-password preservation, editor save/delete, authentication/test lifecycle)
- `CredentialMetadataBackupAndroidTest` (new preference allowlist and old-backup compatibility)

The virtual Actions preset continuation and full emulator/device integration verification are tracked below.

## Virtual Actions presets and reconnect draft continuation

- Added read-only virtual **Common**, **Navigate**, **Media**, and **F1–F12** choices before saved layouts in the Actions picker. Presets use the former key catalog's actual commands, ten-column title-only action tiles, and explicit read-only editing controls; they do not seed saved profiles.
- Kept preset/saved picker choices separately tagged so a user profile can safely have the same ID as a built-in preset. Existing profile ordering and stored fields remain unchanged.
- Relative volume, mute, and brightness controls use the existing supported XF86 key commands; no absolute values or polling were added.
- Preserved the in-memory composer draft across Computers open/close, disconnect/reconnect, in-app prompts, and pending system connection-permission/private-key picker continuations. Ordinary app backgrounding remains a clear boundary; drafts are not serialized.

Passed:

- Targeted `:app:testDebugUnitTest`: **27 tests**, 0 failures, 0 errors.
  - `ActionPresetCatalogTest` (stable IDs/order, all legacy labels/commands, in-memory models, XDoTool/YDoTool dispatch)
  - `ActionCommandDispatchTest`
  - `ShellNavigationTest`
  - `UiPersistenceIntegrationTest`
  - `ActionPresentationTest`
- `:app:assembleDebug`
- `:app:assembleDebugAndroidTest` (instrumentation sources compiled into the test APK; not run)
- `git diff --check`

No emulator/device installation or instrumentation execution was performed in this package.

Recommended filters for the next emulator integration package:

- `com.stripe1.xmouse.NativeShellAndroidTest#virtualPresetPickerRendersReadOnlyTenColumnTilesAndRetainsComposerAcrossIdCollision`
- `com.stripe1.xmouse.NativeShellAndroidTest#quickComputersUsesModalBottomSheetCardAndKeepsRealManagementRoutesLabeled`
- `com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#activityControlsHandCommandsToTheServiceOwnedProductionTransport`
- `com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#sharedComposerGatesBlankAndDisconnectedSendAndRetainsDraftAcrossReconnect`
- `com.stripe1.xmouse.AppearancePreferencesAndroidTest` (appearance and Compose palette checks)
- `com.stripe1.xmouse.secrets.CredentialRepositoryAndroidTest` (credential preservation and connection/editor lifecycle)
- `com.stripe1.xmouse.secrets.CredentialMetadataBackupAndroidTest` (backup compatibility)
