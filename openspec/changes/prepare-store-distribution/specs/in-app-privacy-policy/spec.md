## Purpose

Give users an in-app explanation of XMouse-NG data handling that is reachable without a network connection and remains readable with assistive technology and text selection.

## ADDED Requirements

### Requirement: Display the privacy policy in a native offline screen
The About section SHALL expose a clearly labeled privacy-policy entry that opens a native, scrollable, selectable text screen. The policy SHALL be available without a browser, WebView, HTTP request, or network permission change, and its material statements SHALL match the hostable project privacy document.

#### Scenario: User reads the policy while offline
- **WHEN** the user opens About and selects the privacy-policy entry with no network connection
- **THEN** the complete policy remains readable in the app, scrollable on a small display, and selectable for assistive and user access

#### Scenario: Privacy policy does not cause network activity
- **WHEN** the privacy screen is opened or read
- **THEN** the app makes no automatic HTTP request, opens no browser, and performs no background tracking or telemetry for the policy

### Requirement: Explain actual data flows, storage, backup, retention, and contact boundaries
The privacy policy SHALL accurately identify the project and explain locally retained host/configuration/layout/script/history metadata; SSH authentication, commands and explicitly initiated one-way clipboard transfer to the user's selected remote computer; optional foreground local-network discovery and hostname resolution; encryption and Android backup exclusions for secrets; allowlisted metadata backup; deletion and retention limitations; the absence of an app-provider account; and a project contact route with a warning not to post secrets. It SHALL not claim that no data ever leaves the device, imply that remote SSH accounts are project accounts, promise forensic erasure, or present an unpublished web URL as live.

#### Scenario: User can distinguish local data from deliberate network transfers
- **WHEN** the user reads the policy before connecting or using discovery/clipboard sharing
- **THEN** the policy distinguishes locally stored configuration and protected secrets from SSH data sent to a user-selected computer and optional local-network discovery/DNS traffic

#### Scenario: User can understand backup, removal, and project contact limits
- **WHEN** the user considers deleting data or contacting the project
- **THEN** the policy describes the allowlisted Android backup metadata, secret exclusions, local deletion/uninstall limits, the lack of remote project-side deletion, and the public GitHub issue route's warning against posting secrets or personal data
