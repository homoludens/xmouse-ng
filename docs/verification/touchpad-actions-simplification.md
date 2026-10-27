# Touchpad / Actions simplification — final verification

Date: **2026-10-26**. Final review of the current uncommitted simplification. Historical OpenSpec
artifacts and prior verification records were not edited. This is not a release or live-SSH sign-off.

## Final gates

The full requested Gradle gate passed:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- **JVM:** 483 tests / 62 XML suites; 0 failures, 0 errors, 0 skipped.
- **Lint:** 220 warnings, 0 errors/fatal issues. Gradle also reports the existing deprecated
  `android.enableJetifier=true` option.
- **Build:** `BUILD SUCCESSFUL` (45 seconds; 85 tasks, 8 executed, 77 up-to-date).
- Source audit confirmed the retired `JoystickSurfaceLayout`, `MyJSView`, `KeyCatalog`, Keys tab,
  and active gyro/joystick controls/preferences/resources are absent. Compatibility exception:
  `CredentialMetadataBackup.legacyRemovedStringPreferences` retains the old gyro/joystick
  preference keys only so version-1 backups can decode and discard them; they are not captured or
  restored. Explicit local text entry and Send remain in `content_main.xml`; Actions render by saved
  grid placements. `git diff --check` passed.

## Saved-Actions refresh draft regression

The saved-Actions refresh now replaces content while retiring and reacquiring its input capability,
but does not retire local text. This fixes the confirmed behavior where a refresh cleared an unsent
Touchpad draft and hid the IME. Actual navigation to another tab/overlay and Activity pause/stop
still clear the local draft. The JVM regression is
`ShellNavigationTest.savedActionsRefreshPreservesLocalTouchpadTextButNavigationRetiresIt`; the
production Activity regression is
`SessionForegroundServiceAndroidTest.shellTabsAndSameActivityOverlaysKeepOneSessionAndRejectOldCapabilities`,
which checks draft/focus/IME through the asynchronous refresh and clearing on overlay/tab changes.
Both are included in the final gates above.

The focused instrumentation selection passed on the owned API 35 Google APIs x86_64 emulator
(`emulator-5584`; AVD `xmouse_actions_review_20261026_hLt7xF`): **24 tests, 0 failures, 0 skipped**
(`BUILD SUCCESSFUL`, 2m 11s). It covered Touchpad/Actions navigation and rendering, ten-column
geometry with preserved stored values, explicit typing/IME, insets, touch controls, retired-input
backup compatibility, and selected recording-only session command routes including saved-script
`exec` dispatch.

Reproduction used this filter (24 exact methods):

```sh
FILTER='com.stripe1.xmouse.NativeShellAndroidTest#sameActivityOverlaysBackAndRecreationRestoreNavigationButNotTypingText,com.stripe1.xmouse.NativeShellAndroidTest#narrowLargeFontHeaderAndLabeledBottomRoutesRemainReachableInBothThemes,com.stripe1.xmouse.NativeShellAndroidTest#cancellingLegacyActionReorderNeverDropsEditsOrDeletesAnItem,com.stripe1.xmouse.NativeShellAndroidTest#twoTabNavigationRetiresCapabilitiesAndKeepsInlineTypingOnTouchpad,'\
'com.stripe1.xmouse.NativeShellAndroidTest#actualActionTilesMatchTenColumnPreviewWithTitlesColorsAndNormalEditCallbacks,com.stripe1.xmouse.NativeShellAndroidTest#nativeComputerFormLayoutHasPersistentLabelsBlankPasswordToggleAndScrollableNarrowLargeFontTargets,com.stripe1.xmouse.NativeShellAndroidTest#actionsEmptyEditDoneAndIdBasedPickerShareProductionRenderingWithoutCommands,com.stripe1.xmouse.NativeShellAndroidTest#actionEditorFormKeepsPresetDefaultsAndOptionalRgbScrollableAtNarrowWidth,'\
'com.stripe1.xmouse.NativeShellAndroidTest#inlineTypingUsesMultilineImeAndExplicitQuickKeysWithoutFocusOrOverlay,com.stripe1.xmouse.NativeSpacingAndroidTest#compactLargeFontActionsMatchPreviewGeometryAndPreserveStoredValues,com.stripe1.xmouse.NativeSpacingAndroidTest#standardScaleMatchesReferenceTouchpadGuttersAndInlineTypingGeometryInBothThemes,com.stripe1.xmouse.NativeSpacingAndroidTest#savedScriptRowRemainsCompactAndKeepsExplicitRunAndEditTargets,'\
'com.stripe1.xmouse.MouseControlButtonsAndroidTest#touchpadControlsRemainAvailableAndGyroJoystickSurfaceIsAbsent,com.stripe1.xmouse.MainActivityInsetsAndroidTest#headerAndBottomNavigationStayInsideInsetsAcrossImeAndLandscapeChanges,com.stripe1.xmouse.secrets.CredentialMetadataBackupAndroidTest#failedPreferencesCommitCanRetryMarkersWithoutMarkingConflictingExistingHosts,com.stripe1.xmouse.secrets.CredentialMetadataBackupAndroidTest#sqliteCompactionFailureIsReportedAndRetryPreservesMigratedData,'\
'com.stripe1.xmouse.secrets.CredentialMetadataBackupAndroidTest#retiredInputPreferencesAreNotExportedAndAreIgnoredInExistingVersionOneBackups,com.stripe1.xmouse.secrets.CredentialMetadataBackupAndroidTest#partialMigrationBackupOmitsOldAndNewSecretsAndRestoresMetadataWithReentryOnly,com.stripe1.xmouse.secrets.CredentialMetadataBackupAndroidTest#packagedLegacyAndModernRulesAreAllowlistedForCloudAndDeviceTransfer,'\
'com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#cancelledMouseAndKeyboardButtonTouchUpsCannotBorrowResumedSurfaceButAccessibilityStillWorks,com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink,com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#shellTabsAndSameActivityOverlaysKeepOneSessionAndRejectOldCapabilities,com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#switchingToSettingsDuringTouchpadDragReleasesOnTheSameSessionAndRejectsStaleInput,com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#scrollableTouchpadDoesNotStealPointerMovement'
ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk ANDROID_SERIAL=emulator-5584 \
  ./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$FILTER"
```

## Diagnostic attempts and boundaries

- An earlier run of the same focused 24-test selection had one timeout waiting for the synthetic
  saved-script `exec` channel. The final rerun above passed all 24. A one-method retry after clearing
  app data reached the method assertions but teardown failed because that isolated test expects a
  previously selected layout profile; no assertion was weakened.
- As an additional, non-gating diagnostic, all methods in those six test classes were selected
  (60 unique methods). It reported **5 failures and 5 permission-precondition skips** and is not
  counted as a pass. Exact failures and the read-only `git show HEAD` comparison (`e3fcea5`):

  | Failing method | Observed failure | Test-source comparison |
  | --- | --- | --- |
  | `repeatedSameHostForegroundRequestsPreserveCredentialAndFirstTrustPrompts` | Expected a first-trust prompt; the snapshot instead ended in synthetic-transport `TIMED_OUT`, with no backend events. | Method body is unchanged from HEAD. No HEAD runtime result establishes whether this failure predated the working tree; cause remains unassessed. |
  | `leavingHostDiscoveryStopsLateCallbacksWithoutClosingForegroundSession` | `NullPointerException`: `Cannot run onActivity since Activity has been destroyed already`. | Method body is unchanged from HEAD. No HEAD runtime result establishes whether this failure predated the working tree; cause remains unassessed. |
  | `visibleConnectPublishesDisclosureBeforeSelectionAndRebindAndRepeatDoNotDuplicatePipeline` | Shared disclosure assertion expected `XMouse remote session`; runtime title was `XMouse-NG remote session`. | Method and helper are unchanged from HEAD. HEAD already has the mismatch: `session_notification_title` and `app_name` are `XMouse-NG`, while the helper hard-codes `XMouse`. This establishes a pre-existing source expectation mismatch, not a prior runtime failure. |
  | `notificationActionsOpenExistingControlsAndDisconnectTransportFromBackground` | Same shared notification-title assertion mismatch. | Method and helper are unchanged from HEAD; same HEAD source mismatch above. Prior runtime failure is not established. |
  | `notificationDisconnectInvalidatesPendingMetadataBeforeItCanStartTransport` | Same shared notification-title assertion mismatch. | Method and helper are unchanged from HEAD; same HEAD source mismatch above. Prior runtime failure is not established. |

  These broader cases were not rerun or changed; no failure was waived. See
  `/tmp/opencode/xmouse-actions-review.hLt7xF/instrumentation-rerun.log`.
- Final focused output: `/tmp/opencode/xmouse-actions-review.hLt7xF/instrumentation-focused-final.log`.
  Full Gradle output: `/tmp/opencode/xmouse-touchpad-actions-full.log`.
- No full Android instrumentation suite, API 26/physical-device run, live SSH, real credentials or
  remote commands, signed/release build, or release/publication operation was performed. Emulator
  fixtures and transports used synthetic data only.

No commit or push was made. The owned `emulator-5584` was stopped after the final repository/status
check; its AVD was retained. No other device was connected at shutdown.
