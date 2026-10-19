## Purpose

Preserve an explicitly requested SSH session during ordinary app switching while making lifecycle changes, disconnection, and input cleanup safe and visible.

## ADDED Requirements

### Requirement: Established sessions outlive the control screen
With the device awake and the network and server available, switching to another app and returning SHALL reuse the existing SSH session without manual reconnection. Recreating the control screen SHALL NOT itself disconnect or replace that session. The application SHALL publish an ongoing system session notification with Open controls and Disconnect actions and explain when notification permission is needed to display those controls in the notification tray.

#### Scenario: Switch apps and return
- **WHEN** the user connects, opens another app, and returns while the established connection remains healthy
- **THEN** XMouse SHALL reuse the same authenticated session and show its current state
- **AND** the screen transition SHALL NOT introduce a second connection or require reauthentication

#### Scenario: Control screen recreated
- **WHEN** Android destroys and recreates only the control screen while the session-owning process remains alive
- **THEN** the recreated screen SHALL reattach to the existing connection rather than treating screen destruction as an explicit disconnect

#### Scenario: Notification visibility permission denied
- **WHEN** the user denies notification permission on a platform that requires it for notification-tray visibility
- **THEN** the application SHALL explain the visibility limitation and retain in-app Open controls and Disconnect access
- **AND** it SHALL comply with the platform's foreground-session disclosure rather than promising a hidden background session

### Requirement: Connection requests are idempotent
An automatic or repeated connect request SHALL leave a healthy session connected and SHALL NOT start parallel connection attempts for the same selected host. Disconnect SHALL be an explicit operation. Changing the selected host SHALL cancel outstanding attempts and close the previous host's session before activating the new one.

#### Scenario: Return with auto-connect enabled
- **WHEN** auto-connect runs on screen return while a session is already connected or connecting
- **THEN** it SHALL leave the existing session or attempt intact rather than toggling it off

#### Scenario: Host changed during connection
- **WHEN** the user selects another host while connection work is pending
- **THEN** the old work SHALL be cancelled or invalidated
- **AND** late completion SHALL NOT activate the previous host

### Requirement: Explicit disconnect stops background activity
Disconnect from the controls or session notification, the application's explicit Exit action, and deliberate task dismissal SHALL close the SSH session, cancel connection work, stop session-related background activity, and remove the ongoing notification. Repeated Disconnect SHALL be harmless. Merely switching apps SHALL NOT count as task dismissal.

#### Scenario: Notification disconnect
- **WHEN** the user taps Disconnect in the ongoing session notification
- **THEN** the application SHALL close the session and stop its session service
- **AND** reopening the app SHALL remain disconnected until the user deliberately requests connection again

### Requirement: Safe input when leaving controls
Leaving the remote-control surface SHALL stop gyroscope input and prevent further remote input from inactive controls. Tracked held mouse buttons and keys SHALL be released before suspension when the connection is still usable. Failed cleanup or session loss SHALL clear local held-input state without replaying stale input after reconnection.

#### Scenario: App switched while dragging
- **WHEN** the user leaves the control surface while a remote mouse button is held
- **THEN** XMouse SHALL attempt to send its release and stop further pointer/gyro input
- **AND** returning SHALL NOT resume the old drag automatically

### Requirement: Honest interrupted-session recovery
Connection attempts and keepalive checks SHALL have finite limits. Network loss, server closure, process termination, or permission loss SHALL result in a disconnected/recoverable state rather than a permanently connected indicator. Returning to the app after an involuntary interruption SHALL allow reconnection to the requested host using fresh resolution and normal authentication/trust checks. Explicit disconnection SHALL suppress automatic reconnection until the user requests connection again.

#### Scenario: Network lost while another app is open
- **WHEN** the network drops and the user later returns to XMouse
- **THEN** XMouse SHALL show the interruption and allow or initiate a foreground recovery attempt according to the retained connection intent
- **AND** it SHALL NOT resend buffered clicks, keystrokes, or custom commands

#### Scenario: Screen off or application stopped by the user
- **WHEN** device idle restrictions or a user/system stop interrupt the session
- **THEN** XMouse SHALL NOT claim that the old session remains connected
- **AND** it SHALL NOT restart itself at boot or defeat an explicit stop to preserve the session
