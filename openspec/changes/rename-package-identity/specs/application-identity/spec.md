## Purpose

Defines the Android identity of XMouse-NG and the user-visible isolation boundary when the app is installed under a new application ID.

## ADDED Requirements

### Requirement: XMouse-NG uses the selected Android identity
Debug and release application artifacts SHALL use `net.droopia.xmouse_ng` as their Android application ID and namespace. Android components and instrumentation SHALL resolve under that identity and its derived test package.

#### Scenario: Inspect a built application
- **WHEN** a debug or release application artifact is built
- **THEN** its application ID SHALL be `net.droopia.xmouse_ng` and its launcher SHALL resolve under the new package

### Requirement: New package identity is isolated from the previous installation
The application SHALL treat `net.droopia.xmouse_ng` as a separate Android installation from `com.stripe1.xmouse`. It SHALL NOT claim to upgrade, read, or automatically migrate the previous installation's private database, preferences, files, credentials, or Android Keystore keys.

#### Scenario: Install alongside the previous app
- **WHEN** both package identities are installed on one Android device
- **THEN** Android SHALL retain them as separate applications with independent app-private data
- **AND** installing or launching the new app SHALL NOT automatically import the previous app's private data or credentials
