## Why

The existing drawer and mixed remote-control grid hide common actions and do not match the approved native design handoff. XMouse-NG needs a coherent, accessible interface while preserving the working SSH session owner, credentials, hosts, layouts, scripts, and legacy command meanings.

## What Changes

- Replace primary drawer/FAB navigation with a real computer/status header and Touchpad, Keys, and Actions bottom destinations using the existing Java/XML toolkit.
- Apply the handoff's semantic light/dark colors, system typography, local vectors, rounded controls, native insets, and accessible touch targets.
- Retain touchpad gestures, drag/hold, scrolling, gyro, joystick, volume shortcuts, and deliberate clipboard export; provide labeled IME typing with explicit Send and quick keys.
- Categorize real keys; show relative media/brightness controls rather than simulated absolute levels.
- Provide computer selection/management, persistent-label forms, truthful connection testing/discovery, and blank-password-preserves-secret editing without seeding demo data.
- Render real named layouts and scripts in Actions, with simple shortcut/advanced-command editors, Regular/Wide conveniences, preset colors, and non-destructive access to arbitrary saved values.
- Rename the visible app and documentation to **XMouse-NG**, retaining `com.stripe1.xmouse`, Android 8/API 26 minimum, protected storage, authentication choices, and host-key verification.
- Publish the completed source to the user-authorized `homoludens/xmouse-ng` repository and attach an externally signed release APK. Signing secrets and keystores remain outside Git; unavailable runtime checks are identified explicitly.

## Capabilities

### New Capabilities

- `native-remote-interface`: Approved native three-destination UI, accessible light/dark controls, actual connection/input flows, and non-destructive editors over existing data.
- `xmouse-ng-distribution`: Visible fork branding with stable application identity and a verifiable signed APK published to the designated GitHub repository.

### Modified Capabilities

None. The main specification store is empty; the still-in-progress `modernize-xmouse` change remains a separate historical baseline, and its incomplete verification is not implicitly completed here.

## Impact

- Java/XML main screen, toolbar/drawer routes, touchpad/joystick and keyboard integration, native forms, settings, action rendering, and shared theme resources.
- Existing Kotlin session/credential/profile boundaries only where needed for non-destructive UI integration; Compose remains limited to existing editors/managers.
- Focused migration/dispatch/lifecycle tests, final JVM/lint/build checks, available-device/emulator layout verification, release packaging and documentation.
- No WebView, browser-reference assets in the APK, external fonts, cloud service, companion daemon, telemetry, or new account/onboarding flow.
