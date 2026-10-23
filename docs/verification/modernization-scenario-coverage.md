# Modernization capability scenario coverage (task 11.7)

This crosswalk accounts for every `#### Scenario:` in the five
`openspec/changes/modernize-xmouse/specs/*/spec.md` files. **50/50 scenarios are listed**;
accounting for a scenario is not a claim that it passed. Task IDs below refer to
[`tasks.md`](../../openspec/changes/modernize-xmouse/tasks.md). Test-class names identify evidence,
not a promise that an Android test ran.

## Evidence and status rules

- **Current JVM pass** means the listed host-side tests passed against the current code in task 11.1's
  403-test run, or in the task-specific JVM run cited in tasks 6–9. It can prove model, fake, or
  loopback behavior; it does not prove Android UI, device, LAN, or live-host behavior.
- **Build/compiled-only** means Android UI/instrumentation code was compiled by the cited task; it was
  not executed. Task 11.1 did not rebuild the AndroidTest APK.
- **Historical API 26/35 only** means an actual test ran on an earlier task-specific APK at the named
  API level(s). It is not runtime evidence for the latest integrated code. API 35-only evidence is
  identified as such.
- **NOT RUN/device/LAN** means the required current-device, physical-device, or live-LAN scenario was
  not executed. APK compilation and JVM fakes are not runtime passes.
- **Implementation gap** means the described user-visible behavior is not available in the current
  implementation, regardless of repository APIs or tests.

Task 11.1 anchors the current whole-suite evidence: with both `ANDROID_HOME` and `ANDROID_SDK_ROOT`
set to `/home/homoludens/Android/Sdk`, `:app:testDebugUnitTest`, `:app:lintDebug`, and
`:app:assembleDebug` passed; **403 JVM tests passed, 0 failed/errors/skipped**, and the build was
successful. Lint passed. Instrumentation was **NOT RUN** and the AndroidTest APK was not rebuilt.
This crosswalk does not rerun that suite. See task 11.1 in `tasks.md`.

Current integrated-code device/API and live-LAN gaps are anchored to tasks 10.1–10.4 and 11.2–11.5
and [`android-platform-coverage.md`](android-platform-coverage.md): current API 26, 31, 33/34, 35,
36, and 37 device checks; the five-minute physical app-switch test; live LAN/discovery; and current
device integration are **NOT RUN**. That platform matrix explicitly separates earlier API 26/35
instrumentation from current-code coverage.

## Scenario crosswalk

| Capability | Exact scenario title | Evidence references | Status |
| --- | --- | --- | --- |
| `connection-continuity` | Switch apps and return | Tasks 5.4, 11.2; `SessionControllerTest`, `SessionForegroundServiceAndroidTest`; [session-activity-binding.md](session-activity-binding.md) | **NOT RUN/device** — the required five-minute physical-device/Linux-host check was not run (11.2). Emulator/recording-backend evidence is not a substitute. |
| `connection-continuity` | Control screen recreated | Task 5.4; `SessionForegroundServiceAndroidTest.activityRecreationAndReturnWithAutoConnectEnabledReattachToTheExistingSession`; [session-activity-binding.md](session-activity-binding.md) | **Historical API 26/35 only** — prior APK instrumentation passed; latest integrated-code recreation was not rerun on a device. |
| `connection-continuity` | Notification visibility permission denied | Tasks 5.3, 11.5; `SessionForegroundServiceAndroidTest.notificationPermissionDenialExplainsTaskManagerAndPreservesInAppDisconnect`; [session-notification.md](session-notification.md) | **Historical API 35 only** — denial flow passed on an earlier task APK; current integrated API 33+ UI/device behavior is not verified. |
| `connection-continuity` | Return with auto-connect enabled | Tasks 5.4, 11.2; `SessionControllerTest`, `SessionForegroundServiceAndroidTest.activityRecreationAndReturnWithAutoConnectEnabledReattachToTheExistingSession`; [session-activity-binding.md](session-activity-binding.md) | **Historical API 26/35 only** — earlier APK instrumentation passed; current integrated device/app-switch acceptance is not run. |
| `connection-continuity` | Host changed during connection | Tasks 5.1, 11.1; `SessionControllerTest` generation/cancellation cases | **Current JVM pass** — controller tests pass; no live-device connection run is claimed. |
| `connection-continuity` | Notification disconnect | Tasks 5.3, 5.8, 11.4; `SessionForegroundServiceAndroidTest`; [session-notification.md](session-notification.md), [session-cleanup.md](session-cleanup.md) | **Historical API 26/35 only** for earlier notification/session APKs; post-5.8 cleanup instrumentation was compiled, not device-run. |
| `connection-continuity` | App switched while dragging | Tasks 5.6, 10.4; `ControlInputTest`, `SessionForegroundServiceAndroidTest.switchingToSettingsDuringTouchpadDragReleasesOnTheSameSessionAndRejectsStaleInput`; [input-lifecycle.md](input-lifecycle.md) | **Historical API 26/35 only** — earlier task-specific emulator regressions passed; current integrated app-switch behavior and physical use are not verified. |
| `connection-continuity` | Network lost while another app is open | Tasks 5.7, 11.4; `SessionControllerTest`, `SshSessionAttemptFactoryTest`, `SessionPipelineIntegrationTest`; [session-recovery.md](session-recovery.md) | **Current JVM pass** — synthetic/loopback loss, recovery, and no-replay cases pass; Android permission/network recovery and live LAN are **NOT RUN**. |
| `connection-continuity` | Screen off or application stopped by the user | Tasks 5.8, 11.4; `SessionForegroundService.kt`; [session-cleanup.md](session-cleanup.md), [android-platform-coverage.md](android-platform-coverage.md) | **Build/compiled-only** — non-sticky/no-boot source checks and instrumentation compilation are recorded; current device stop/force-stop behavior was not run. |
| `layout-editor` | Select a button while editing | Tasks 9.1, 9.3, 9.7; `LayoutEditorViewModelTest`, `LayoutEditorPointerAndroidTest`, `SessionForegroundServiceAndroidTest` editor/command regression | **Current JVM pass** for draft selection/reducer behavior; Android editor/no-command integration is **build/compiled-only**, not runtime-tested. |
| `layout-editor` | Leave an unsaved draft | Tasks 9.1, 10.2; `LayoutEditorViewModelTest`; `SessionForegroundServiceAndroidTest` dirty-Back regression | **Current JVM pass** for Save/Discard/Cancel state; Android Back/dialog instrumentation is **build/compiled-only** and was not run. |
| `layout-editor` | Move and resize a button | Tasks 9.2, 9.3; `LayoutEditorGridTest`, `LayoutEditorDragTest`, `LayoutEditorViewModelTest`, `LayoutEditorPointerAndroidTest` | **Current JVM pass** for ordering, width, and draft state; pointer/UI behavior is **build/compiled-only**, not device-tested. |
| `layout-editor` | Invalid button properties | Task 9.4; `LayoutEditorViewModelTest`, `LayoutProfileJsonTest` | **Current JVM pass** — invalid fields retain the draft/form state; Android form interaction was not executed. |
| `layout-editor` | Undo a deletion and redo it | Task 9.5; `LayoutEditorViewModelTest`, `LayoutEditorHistory.kt` | **Current JVM pass** — reducer/ViewModel history tests cover restoration of button properties and position. |
| `layout-editor` | More than three layouts | Task 8.5; `LayoutProfileRepositoryTest`; `MainActivity.initMenu`, `MainActivity.openLayoutEditor` | **Implementation gap** — repository create/list/select works in JVM tests, but the UI exposes only Layout 1–3 and opens the already-selected profile. Users cannot create/select a fourth profile. |
| `layout-editor` | Rename or duplicate a profile | Tasks 8.5, 9.1; `LayoutProfileRepositoryTest`, `LayoutEditorViewModelTest`; `MainActivity.initMenu`, `MainActivity.openLayoutEditor`, `LayoutEditorActivity` | **Implementation gap** — the editor name field can rename the selected profile, and repository rename/duplicate pass JVM tests, but users have no duplicate workflow. The scenario's duplicate branch is unavailable. |
| `layout-editor` | Migrate active layout and all three presets | Task 8.3; `LegacyLayoutProfileMigrationTest`, `LegacyLayoutFixtureTest`, `LayoutProfileRepositoryTest` | **Current JVM pass** — fixture migration, distinct profile IDs/content, rerun behavior, and retained source bytes are covered; no current device migration run is claimed. |
| `layout-editor` | Damaged legacy layout | Task 8.4; `LegacyLayoutProfileMigrationTest`, `LegacyLayoutFixtureTest`; [legacy-storage-fixtures.md](legacy-storage-fixtures.md) | **Current JVM pass** — malformed-source warning/checkpoint/recovery and source preservation tests passed; UI warning presentation was not device-run. |
| `layout-editor` | Save complex command text | Task 8.1; `LayoutProfileJsonTest` | **Current JVM pass** — JSON round trips cover Unicode, quotes, commas, spaces, and multiline command text. |
| `layout-editor` | Save interrupted | Task 8.2; `LayoutProfileRepositoryTest`, `LayoutProfileJsonTest` | **Current JVM pass** — atomic-write interruption, last-saved profile, surfaced failures, and recoverable draft behavior are covered. |
| `layout-editor` | Reorder without dragging | Task 9.6; `LayoutEditorViewModelTest`, `LayoutEditorAccessibilityAndroidTest` | **Current JVM pass** for move-earlier/later behavior; labeled Compose controls/touch targets are **build/compiled-only**, not accessibility-device tested. |
| `layout-editor` | Use a saved custom button | Tasks 8.6, 9.7; `CustomKeyboardButtonAdapterTest`, `CustomKeyboardButtonAdapterDelegationTest`, `SessionForegroundServiceAndroidTest` saved-profile/command integration | **Build/compiled-only** for the end-to-end editor → saved profile → normal-control execution case (9.7); adapter JVM command-dispatch tests pass, but the Android integration was not run. |
| `local-host-discovery` | Save and connect to a local hostname | Tasks 6.1, 6.3; `EndpointHostTest`, `EndpointTest`, `ListEditActivityDialogTest`, `AndroidNetworkAddressResolverTest` | **Current JVM pass** for endpoint validation/preservation and resolver logic; host UI and live `.local`/LAN connection are **NOT RUN**. |
| `local-host-discovery` | Manual endpoint without discovery | Tasks 6.1, 6.3, 7.5; `EndpointTest`, `EndpointHostTest`, `SshSessionAttemptFactoryTest`, `AndroidNetworkAddressResolverTest` | **Current JVM pass** for DNS/IP endpoint and fake-resolver paths; no live server/LAN check is claimed. |
| `local-host-discovery` | DHCP address changes | Tasks 6.3, 7.5; `AndroidNetworkAddressResolverTest`, `SshSessionAttemptFactoryTest`, `JschHostTrustIntegrationTest` | **Current JVM pass** for fresh candidates/network-change and logical trust identity; actual DHCP change on LAN is **NOT RUN** (11.3). |
| `local-host-discovery` | First address unavailable or attempt cancelled | Tasks 6.3, 7.5; `AndroidNetworkAddressResolverTest`, `SshSessionAttemptFactoryTest`, `SessionControllerTest` | **Current JVM pass** for candidate fallback, cancellation, and stale-result rejection; device/network execution is **NOT RUN**. |
| `local-host-discovery` | Discover an advertised SSH service | Tasks 7.1, 7.2; `SshServiceDiscoveryTest`, `ListEditActivityDialogTest`; `AndroidSshNsdPlatform.kt` | **Current JVM pass** for fake NSD callbacks, ports, and result selection; on-device NSD/multicast and LAN discovery are **NOT RUN**. |
| `local-host-discovery` | Service disappears or discovery is closed | Tasks 7.1, 7.4; `SshServiceDiscoveryTest`, `ListEditActivityDialogTest`, `SessionForegroundServiceAndroidTest` lifecycle regression | **Current JVM pass** for service loss/close and session isolation; Activity lifecycle instrumentation was **build/compiled-only**, not executed. |
| `local-host-discovery` | SSH server is not advertised | Task 7.2; `ListEditActivityDialogTest`; `ListEditActivity.showFindComputers`, `strings.xml` (`ssh_discovery_searching`) | **Current JVM pass** for dialog/manual-entry behavior — displayed guidance says SSH alone may not advertise and offers manual hostname/IP entry; actual empty-LAN UI was not device-run. |
| `local-host-discovery` | Select an untrusted discovered computer | Tasks 4.3, 7.5; `SshSessionAttemptFactoryTest`, `SshServiceDiscoveryTest`, `JschHostTrustIntegrationTest` | **Current JVM pass** — discovery locator candidates use the normal trust gate and changed-key failure is terminal; live discovered-host/LAN flow is **NOT RUN**. |
| `local-host-discovery` | Name resolution fails | Task 6.6; `ConnectionStateTest`, `SessionControllerTest`, `TransportPresentationTest`; `MainActivity` resolution-failure presentation | **Current JVM pass** for typed resolution errors; the focused resolution-vs-authentication UI instrumentation was **build/compiled-only** and was not run. |
| `modern-android-runtime` | Current Android system navigation | Tasks 10.1, 10.2; `InsetPaddingCalculatorTest`, `DrawerBackCallbackTest`, `LayoutEditorViewModelTest`, `SessionForegroundServiceAndroidTest` Back regression | **Current JVM pass** for inset math/state; Compose/system Back instrumentation is **build/compiled-only**. Current API 35–37 navigation/IME device checks are **NOT RUN**. |
| `modern-android-runtime` | Minimum supported device | Tasks 10.3, 11.5; `ControlInputTest`, `AndroidManifest.xml`; [android-platform-coverage.md](android-platform-coverage.md) | **Historical API 26/35 only** for earlier task APKs; current API 26 no-gyroscope install/control-use verification is **NOT RUN**. |
| `modern-android-runtime` | First local connection on Android 17 | Tasks 6.2, 11.5; `LocalNetworkPermissionCoordinatorTest`, `SessionControllerTest`, `LocalNetworkPermissionCoordinator.kt` | **Current JVM pass** for permission policy/preflight; API 37 rationale and runtime-permission request UI/device flow is **NOT RUN**. |
| `modern-android-runtime` | Network permission denied or revoked | Tasks 6.2, 5.7, 11.4; `LocalNetworkPermissionCoordinatorTest`, `SessionControllerTest`, `SshSessionAttemptFactoryTest` | **Current JVM pass** for denial/revocation policy and recoverable session state; current API 37 denial/revocation UI and device behavior are **NOT RUN**. |
| `modern-android-runtime` | Import a key from a document provider | Tasks 4.1, 4.7; `PrivateKeyImportAndroidTest`, `SshAuthenticationAndroidTest`; [private-key-import.md](private-key-import.md), [ssh-crypto.md](ssh-crypto.md) | **Historical API 26/35 only** — earlier API instrumentation verified provider import and retained-key behavior; latest integrated code was not rerun on device. |
| `modern-android-runtime` | Cancelled or invalid import | Tasks 4.1, 4.7; `PrivateKeyImportAndroidTest`, `BoundedSshKeyTest`; [private-key-import.md](private-key-import.md), [ssh-crypto.md](ssh-crypto.md) | **Current JVM pass** for bounded validation/preservation; **historical API 26/35 only** for provider/UI behavior. Latest integrated device run is **NOT RUN**. |
| `modern-android-runtime` | Existing host after upgrading | Tasks 3.3–3.5, 11.4; `LegacyCredentialMigrationTest`, `DatabaseMigrationAndroidTest`, `CredentialMigrationAndroidTest`, `SessionPipelineIntegrationTest` | **Current JVM pass** for migration fixtures and command pipeline; actual upgrade/device integration is **historical API 26/35 only** and current task 11.4 device cases are **NOT RUN**. |
| `secure-ssh` | First connection to a host | Tasks 4.3, 11.4; `HostTrustRequestsTest`, `JschHostTrustIntegrationTest`, `HostTrustAndroidTest`; [ssh-host-trust.md](ssh-host-trust.md) | **Current JVM pass** for approval-before-auth ordering; trust-dialog Android assertions passed on **historical API 26/35** APKs, not the latest integrated code. |
| `secure-ssh` | Trusted hostname gets a new IP address | Tasks 4.3, 7.5; `HostKeyRepositoryTest`, `JschHostTrustIntegrationTest` | **Current JVM pass** — loopback `127.0.0.1`/`127.0.0.2` exercises logical-host identity; real DHCP/LAN address change is **NOT RUN** (11.3). |
| `secure-ssh` | Host key changes | Tasks 4.3, 4.4, 7.5; `HostTrustRequestsTest`, `JschHostTrustIntegrationTest`, `SshSessionAttemptFactoryTest` | **Current JVM pass** — changed keys block authentication/retry fallback; current device trust-warning UI and live-LAN behavior are **NOT RUN**. |
| `secure-ssh` | RSA key against current OpenSSH | Tasks 4.1, 4.5; `JschAuthenticationIntegrationTest`, `BoundedSshKeyTest`; [ssh-authentication.md](ssh-authentication.md), [ssh-crypto.md](ssh-crypto.md) | **Current JVM pass** — production wire path signed RSA-SHA2 to an isolated MINA server with SHA-1 RSA disabled; a real current OpenSSH/LAN server is **NOT RUN**. |
| `secure-ssh` | Password, encrypted key, and invalid passphrase | Tasks 4.1, 4.5; `JschAuthenticationIntegrationTest`, `BoundedSshKeyTest`, `SshAuthenticationAndroidTest`; [ssh-authentication.md](ssh-authentication.md) | **Current JVM pass** against synthetic MINA auth fixtures; **historical API 26/35 only** for Android key handoff/import. No live SSH host is claimed. |
| `secure-ssh` | Malformed or incorrectly decrypted key structure | Task 4.1; `BoundedSshKeyTest`, `PrivateKeyImportAndroidTest`; [ssh-crypto.md](ssh-crypto.md) | **Current JVM pass** for deterministic bounded malformed/decrypted fixtures; **historical API 26/35 only** for Android parser/import regressions. Latest device run is **NOT RUN**. |
| `secure-ssh` | Wrong passphrase for a previously imported encrypted key | Tasks 4.1, 4.5; `BoundedSshKeyTest`, `PrivateKeyRepositoryTest`, `CredentialRepositoryAndroidTest`; [ssh-crypto.md](ssh-crypto.md), [ssh-authentication.md](ssh-authentication.md) | **Current JVM pass** for bounded rejection and unchanged records; **historical API 26/35 only** for saved-key Android handoff. |
| `secure-ssh` | Malformed key supplied for one attempt after device-key loss | Tasks 3.2, 4.1; `CredentialAccessTest`, `BoundedSshKeyTest`, `CredentialAccessAndroidTest`; [credential-reentry.md](credential-reentry.md), [ssh-crypto.md](ssh-crypto.md) | **Current JVM pass** for attempt-only rejection/preservation; **historical API 26/35 only** for Android key-loss/re-entry tests. Latest integration is **NOT RUN** on device. |
| `secure-ssh` | Backup or diagnostic output | Tasks 3.6, 3.7, 11.4; `LoggingPrivacyAndroidTest`, `CredentialMetadataBackupAndroidTest`; [credential-backup.md](credential-backup.md), [logging-privacy.md](logging-privacy.md) | **Historical API 35 only** for actual local-transport backup/restore; current source/JVM privacy checks are recorded, but current Android backup/device integration is **NOT RUN** (11.4). |
| `secure-ssh` | Device encryption key unavailable | Tasks 3.2, 4.1; `CredentialAccessTest`, `CredentialRepositoryAndroidTest`, `CredentialAccessAndroidTest`; [credential-reentry.md](credential-reentry.md), [ssh-crypto.md](ssh-crypto.md) | **Current JVM pass** for failure/re-entry policy; **historical API 26/35 only** for Android key-loss instrumentation. Current device behavior is **NOT RUN**. |
| `secure-ssh` | Interrupted upgrade | Tasks 3.4, 3.5, 11.4; `LegacyCredentialMigrationTest`, `CredentialMigrationAndroidTest`, `DatabaseMigrationAndroidTest`; [credential-migration.md](credential-migration.md), [database-migrations.md](database-migrations.md) | **Current JVM pass** for checkpoint/restart cases; Android migration instrumentation is **historical API 26/35 only**. Latest upgrade/failure device matrix is **NOT RUN**. |
| `secure-ssh` | Legacy private-key path no longer readable | Tasks 4.7, 4.8; `PrivateKeyRepositoryTest`, `PrivateKeyImportAndroidTest`; [private-key-import.md](private-key-import.md) | **Current JVM pass** for unreadable-path preservation/re-import state; **historical API 26/35 only** for picker/device behavior. Latest integrated device test is **NOT RUN**. |

## Unresolved gaps and task 11.7 decision

1. **Profile-management implementation gap (release capability gap):** task 8.5 added and tested repository
   create/rename/duplicate/select/delete operations, but user-facing support is partial. `MainActivity` builds
   only fixed `Layout 1`, `Layout 2`, and `Layout 3` entries; its editor entry point loads only the already-
   selected profile; `LayoutEditorActivity` can edit that profile's name but has no create, duplicate, delete,
   or general profile picker flow. Thus **“More than three layouts”** and the duplicate branch of **“Rename or
   duplicate a profile”** are unavailable to users. Addressing these gaps needs clarification/scope or task
   amendment; task 11.7 does not implement them.
2. **Device/LAN acceptance remains outstanding:** tasks 11.2–11.5 are explicitly **NOT RUN**. Historical API
   26/35 emulator tests do not cover the latest integrated code, API 31/33/34/36/37, the physical five-minute
   continuity check, or live LAN/discovery. See [android-platform-coverage.md](android-platform-coverage.md).

**Decision:** task **11.7 remains unchecked**. The crosswalk accounts for 50/50 scenarios and strict validation
can confirm artifact validity, but the two concrete layout capability gaps mean 11.7 cannot be marked complete.
Parent/user clarification is required before changing implementation scope. No tests, device/ADB checks, or full
suite rerun were performed for this task.
