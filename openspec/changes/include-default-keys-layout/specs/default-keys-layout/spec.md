## Purpose

Provide the original keyboard/action table as a useful, editable default profile while preserving user-selected layouts, in-progress drafts, and retained legacy data across upgrades.

## ADDED Requirements

### Requirement: Fresh installations start with the original Keys Layout
The app SHALL create and select an editable saved profile named `Keys Layout` containing the complete original 57 buttons in their original order, with exact titles, commands, widths, and colors, when no saved profiles or legacy layout sources exist.

#### Scenario: First Actions view on a clean installation
- **WHEN** a clean installation opens Actions for the first time
- **THEN** the selected saved profile is `Keys Layout` and contains the original 57-button table, rather than an empty profile or read-only shortcut preset

#### Scenario: Layout content matches the original table
- **WHEN** the default profile is created
- **THEN** every button's title, command, width, color, and order match the original built-in table exactly and the profile remains editable as a saved layout

### Requirement: Upgrade seeding preserves existing layout state
The app SHALL make the stock `Keys Layout` available additively on an eligible upgrade without overwriting, renaming, or deleting any saved profile, profile draft, selection, or retained legacy source. When legacy sources exist, seeding SHALL occur only after their migration activates successfully and SHALL preserve the migrated selection. The app SHALL preserve a selected custom profile or read-only preset; it MAY select the new `Keys Layout` only when the sole existing repository profile is the original empty automatic `Actions` profile, that profile has no pending draft, and no legacy source is present. Any old empty profile SHALL remain unchanged.

#### Scenario: Upgrade from the automatically created empty Actions profile
- **WHEN** an upgrade finds only the reserved automatic `Actions` profile with no buttons or draft and no retained legacy source
- **THEN** it adds a separate populated `Keys Layout`, may select it, and preserves the original empty profile unchanged

#### Scenario: Upgrade with saved profiles or a pending draft
- **WHEN** an upgrade finds a named, renamed, nonempty, or otherwise user-managed profile, a pending draft, or an existing profile selection
- **THEN** any added stock profile is separate, existing profile records and drafts remain byte-for-byte logically unchanged, and the prior selection is preserved

#### Scenario: Legacy layout source takes precedence
- **WHEN** a retained legacy layout source migrates successfully
- **THEN** the app may add a separate `Keys Layout` only after migration succeeds, preserves the migrated profile selection, and leaves every legacy source and checkpoint unchanged

#### Scenario: Legacy migration warning blocks seeding
- **WHEN** migration of any retained legacy layout source reports a warning
- **THEN** no stock profile is seeded and the existing read-only recovery behavior remains authoritative

### Requirement: Respect user removal or renaming of the default
The app SHALL persist that default initialization has been considered so that a user-renamed or deliberately deleted `Keys Layout` is not recreated on a later app start. An existing profile at the default profile ID SHALL never be overwritten with stock content.

#### Scenario: User edits or renames the stock profile
- **WHEN** a user changes buttons or renames `Keys Layout` and restarts the app
- **THEN** the saved changes remain intact and initialization does not restore stock content or the old name

#### Scenario: User deliberately deletes the stock profile
- **WHEN** a user deletes `Keys Layout` after creating another saved profile and later restarts the app
- **THEN** the deleted profile is not automatically recreated
