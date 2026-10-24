## Purpose

Keep XMouse's local remote-control functions usable on supported Android releases with current navigation, permission, and file-access behavior.

## ADDED Requirements

### Requirement: Supported Android versions and usable system UI
The application SHALL support Android 8 / API 26 and later and target Android 17 / API 37. Remote controls, connection actions, and editor controls SHALL remain reachable with gesture navigation, display cutouts, system bars, and the on-screen keyboard. System Back SHALL close an open drawer or editor according to its visible state before leaving that screen.

#### Scenario: Current Android system navigation
- **WHEN** the user opens controls on Android 15, 16, or 17 and shows or hides the keyboard
- **THEN** interactive controls SHALL remain outside obscured system areas or scroll into reach
- **AND** Back SHALL dismiss the active overlay or navigate back without silently applying editor changes

#### Scenario: Minimum supported device
- **WHEN** the application runs on an Android 8 device without a gyroscope
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

### Requirement: Deliberate one-way clipboard sharing
The application SHALL offer an accessible **Send clipboard to Linux** toolbar action on the control screen. It SHALL read Android clipboard data only after a direct tap while that screen is foreground-visible and the current SSH session is ready. It SHALL accept plain text only, reject empty or non-text content and UTF-8 payloads larger than 64 KiB without truncation, and preserve accepted text exactly. It SHALL send the text as SSH exec-channel standard input to a fixed remote helper command, never as command text, environment data, simulated typing, or a paste keystroke. A transfer SHALL belong only to the active session generation and SHALL NOT be retained or replayed after disconnect, host change, or recovery. Clipboard contents SHALL NOT be logged, shown in command/output history, or persisted. The UI SHALL report success only after the remote helper exits successfully; a missing helper, unavailable desktop clipboard, or helper failure SHALL produce an actionable error without treating a nonzero helper exit alone as SSH-session loss.

#### Scenario: Explicitly send literal clipboard text
- **WHEN** the user taps **Send clipboard to Linux** while a ready session is active and Android's primary clip contains plain text
- **THEN** XMouse SHALL send the exact UTF-8 text through SSH standard input to the Linux desktop clipboard helper
- **AND** it SHALL report success only after that helper exits with status zero, without typing the text or sending a paste key

#### Scenario: Do not read clipboard without a foreground tap and ready session
- **WHEN** the user has not tapped the action, the control screen is not foreground-visible, or no ready SSH session exists
- **THEN** XMouse SHALL NOT read Android clipboard contents; a disconnected action SHALL direct the user to connect first
- **AND** an empty, non-text, malformed, or oversized clip tapped while connected SHALL be explained and SHALL NOT be sent

#### Scenario: Linux clipboard helper is unavailable or fails
- **WHEN** the remote Linux desktop has no usable clipboard helper/display, or the selected helper exits unsuccessfully
- **THEN** XMouse SHALL show an actionable failure and SHALL NOT claim the clipboard was copied
- **AND** a helper's nonzero exit alone SHALL NOT disconnect an otherwise healthy SSH session
