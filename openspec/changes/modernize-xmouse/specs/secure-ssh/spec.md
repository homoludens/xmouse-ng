## Purpose

Authenticate remote Linux computers and protect SSH credentials without weakening server identity checks for discovered or manually entered hosts.

## ADDED Requirements

### Requirement: Explicit server trust before credentials or commands
The application SHALL require explicit user approval of an unknown SSH host key, displaying its fingerprint and the intended hostname/address and port before authenticating or sending remote commands. Subsequent connections SHALL verify the approved key. A changed key SHALL block authentication and require an explicit trust-management action; discovery, IP changes, and reconnects SHALL NOT silently replace trusted keys.

#### Scenario: First connection to a host
- **WHEN** an unrecognized host key is presented for a requested endpoint
- **THEN** the application SHALL display a fingerprint approval prompt
- **AND** rejecting or cancelling the prompt SHALL prevent authentication and remote command execution

#### Scenario: Trusted hostname gets a new IP address
- **WHEN** a saved hostname resolves to a different IP address but presents its approved host key
- **THEN** the application SHALL validate trust against the intended hostname and port rather than treating the transient IP address as a new trusted identity

#### Scenario: Host key changes
- **WHEN** a server presents a different key for an already trusted endpoint
- **THEN** the application SHALL block authentication and show a host-identity warning
- **AND** it SHALL NOT automatically accept the key during reconnection

### Requirement: Current SSH authentication without insecure server workarounds
The application SHALL support password and existing RSA/ECDSA private-key authentication with passphrases. It SHALL support RSA SHA-2 authentication against current OpenSSH configurations without requiring users to re-enable SHA-1 `ssh-rsa` signatures. Unsupported key formats or algorithms SHALL result in a specific error without disabling host-key verification or silently enabling insecure algorithms.

#### Scenario: RSA key against current OpenSSH
- **WHEN** the user authenticates with a valid RSA key to an OpenSSH server that disables SHA-1 RSA signatures
- **THEN** authentication SHALL use a mutually supported RSA SHA-2 signature without requiring server configuration changes

#### Scenario: Password, encrypted key, and invalid passphrase
- **WHEN** the user connects with password authentication or a supported encrypted private key
- **THEN** valid credentials SHALL authenticate successfully
- **AND** an invalid passphrase SHALL produce an authentication explanation without altering the saved host or key

### Requirement: Protected secrets and safe backups
Saved passwords, private-key material, and retained passphrases SHALL be encrypted at rest using device-protected key material and SHALL NOT appear in application logs, ordinary configuration backups, or layout files. A device-key loss SHALL require credential re-entry without discarding non-secret host metadata, scripts, or layouts.

#### Scenario: Backup or diagnostic output
- **WHEN** an application backup or diagnostic log is produced
- **THEN** it SHALL exclude authentication secrets and imported private-key contents
- **AND** restoring non-secret configuration SHALL NOT imply that unusable credentials are available

#### Scenario: Device encryption key unavailable
- **WHEN** previously encrypted credentials cannot be decrypted because the device key is unavailable
- **THEN** the application SHALL request credential re-entry
- **AND** it SHALL preserve host labels, endpoint settings, scripts, and layouts

### Requirement: Non-destructive credential and host migration
Upgrading SHALL preserve saved host IDs, aliases, endpoints, scripts, and authentication choices. Existing plaintext credentials SHALL only be removed after their encrypted replacements have been durably stored and verified. Interrupted migrations SHALL be safe to repeat without duplicating records or dropping user tables. Unreadable legacy key paths SHALL retain their configuration context and request key re-import instead of silently disabling authentication.

#### Scenario: Interrupted upgrade
- **WHEN** the application restarts after interruption at any credential-migration checkpoint
- **THEN** migration SHALL resume without losing the last usable credential representation or duplicating hosts and scripts

#### Scenario: Legacy private-key path no longer readable
- **WHEN** a migrated configuration refers to an inaccessible external-storage key
- **THEN** the application SHALL identify that configuration and request re-import through the document picker
- **AND** saved hosts, scripts, and layouts SHALL remain intact
