## Purpose

Distribute XMouse-NG with clear visible branding, a stable application identity and protected-data upgrade path, and a verifiable signed release artifact in the user-designated repository.

## ADDED Requirements

### Requirement: Visible rename without identity or data reset
The visible application name SHALL be XMouse-NG while application ID, minimum Android version, protected-storage paths, and existing data identities remain stable. Branding SHALL NOT reset user configuration or imply that installing a differently signed build can update an existing app in place.

#### Scenario: Upgrade a compatible signed installation
- **WHEN** a user installs the redesigned app over a compatible prior installation
- **THEN** the visible name SHALL be XMouse-NG and existing computers, authentication choices, scripts, profiles, actions, and preferences SHALL remain available

### Requirement: Externally signed release artifact with provenance
The published installable release APK SHALL be signed with the user-selected external release key and verified against its expected certificate fingerprint. Keystores and signing passwords SHALL remain outside Git and published artifacts. The release build SHALL exclude debug-only fixtures and browser-reference assets/libraries.

#### Scenario: Build and verify a release
- **WHEN** release signing configuration is available locally
- **THEN** the release APK's signature, application identity, version, minimum API, and checksum SHALL be verified before publication
- **AND** missing signing configuration SHALL block signed publication rather than silently use a debug or replacement key

### Requirement: Publish to the designated GitHub repository with honest validation
Source and a release APK SHALL be published to `homoludens/xmouse-ng` using authorized GitHub access, without overwriting unrelated remote history. Release notes SHALL describe validation, measurable artifact/dependency impact, deliberate design deviations, and any runtime checks not performed.

#### Scenario: Publish the verified build
- **WHEN** implementation and available verification are complete and signed provenance is verified
- **THEN** the designated GitHub repository SHALL contain the source and a release with the APK attached
- **AND** build/fake tests SHALL NOT be described as live device, migration, or Linux SSH passes
