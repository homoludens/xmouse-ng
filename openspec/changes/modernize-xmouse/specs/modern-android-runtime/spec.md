## Purpose

Keep XMouse's local remote-control functions usable on supported Android releases with current navigation, permission, and file-access behavior.

## ADDED Requirements

### Requirement: Supported Android versions and usable system UI
The application SHALL support Android 7 / API 24 and later and target Android 17 / API 37. Remote controls, connection actions, and editor controls SHALL remain reachable with gesture navigation, display cutouts, system bars, and the on-screen keyboard. System Back SHALL close an open drawer or editor according to its visible state before leaving that screen.

#### Scenario: Current Android system navigation
- **WHEN** the user opens controls on Android 15, 16, or 17 and shows or hides the keyboard
- **THEN** interactive controls SHALL remain outside obscured system areas or scroll into reach
- **AND** Back SHALL dismiss the active overlay or navigate back without silently applying editor changes

#### Scenario: Minimum supported device
- **WHEN** the application runs on an Android 7 device without a gyroscope
- **THEN** touchpad, keyboard, saved scripts, host management, and layout editing SHALL remain available
- **AND** unavailable gyro functionality SHALL NOT prevent installation or use

### Requirement: Explain local-network access at the point of use
The application SHALL obtain any local-network permission required by the running Android version and target SDK before local resolution, discovery, or SSH connection attempts. A denial or revocation SHALL produce an actionable permission explanation rather than an authentication error or indefinite connection attempt. Permissions SHALL NOT be requested on platform versions that do not require or support them.

#### Scenario: First local connection on Android 17
- **WHEN** the user requests a local connection and required network permission has not been granted
- **THEN** the application SHALL explain why LAN access is needed and request permission before attempting that operation

#### Scenario: Network permission denied or revoked
- **WHEN** the user denies permission or revokes it during a session
- **THEN** affected operations SHALL stop or enter a recoverable disconnected state
- **AND** the interface SHALL retain the host configuration and explain how to retry after granting access

### Requirement: Document-based private-key selection
The application SHALL allow users to select SSH private keys through Android's system document picker without requesting broad external-storage access. Successful imports SHALL remain available after the source document becomes unavailable. Cancellation or invalid content SHALL NOT replace a previously usable key.

#### Scenario: Import a key from a document provider
- **WHEN** the user selects a supported private-key document
- **THEN** the application SHALL validate and securely retain an application-private copy for future authentication
- **AND** it SHALL NOT require legacy storage permissions

#### Scenario: Cancelled or invalid import
- **WHEN** selection is cancelled or the selected document is not a supported private key
- **THEN** the existing authentication configuration SHALL remain unchanged
- **AND** invalid content SHALL produce an explanation without displaying private-key material

### Requirement: Preserve existing remote functions during modernization
The application SHALL retain touchpad/mouse control, keyboard input, saved scripts, volume-key shortcuts, and existing xdotool/ydotool selection without requiring a new desktop daemon or cloud account.

#### Scenario: Existing host after upgrading
- **WHEN** the user connects to a previously configured Linux host after completing migration and server-trust confirmation
- **THEN** the existing remote-control functions SHALL continue using that host's configured SSH and desktop-command setup
