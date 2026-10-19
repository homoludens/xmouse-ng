## Purpose

Provide an approachable grid-based button editor with recoverable edits and named layouts while retaining the meaning of existing custom remote commands.

## ADDED Requirements

### Requirement: Separate editing from remote execution
Users SHALL explicitly enter and leave layout edit mode. In edit mode, selecting, dragging, resizing, duplicating, or inspecting buttons SHALL NOT execute remote commands. Users SHALL be able to save a valid draft or discard it; leaving with unsaved changes SHALL offer an explicit save/discard/cancel choice.

#### Scenario: Select a button while editing
- **WHEN** the user taps or drags a button in edit mode
- **THEN** the editor SHALL select or reposition the button without sending SSH input

#### Scenario: Leave an unsaved draft
- **WHEN** the user presses Back with unsaved edits
- **THEN** the application SHALL offer Save, Discard, and Cancel
- **AND** Discard SHALL leave the saved layout unchanged

### Requirement: Predictable ten-column flow editing
The editor SHALL present an ordered, ten-column flow grid with button widths from one through ten columns. Users SHALL reorder buttons with a placement preview and resize their widths with visible controls. Wrapping SHALL be derived from order and width, not device-specific pixel coordinates. Invalid widths SHALL be rejected without losing valid edits.

#### Scenario: Move and resize a button
- **WHEN** the user moves a button and sets its width to four columns
- **THEN** the preview and saved layout SHALL reflect the new order and four-column width
- **AND** narrower displays SHALL preserve logical order and widths without storing absolute pixel positions

### Requirement: Editable button properties and validation
Users SHALL add, edit, duplicate, and delete buttons and change their title, raw command, width, and color. The editor SHALL provide color selection and visible validation of required fields, widths, and colors. Validation failures SHALL keep the draft open. Existing custom shell commands SHALL remain editable without requiring conversion to a new action system.

#### Scenario: Invalid button properties
- **WHEN** a user attempts to save an empty required field, invalid color, or width outside one through ten
- **THEN** the editor SHALL identify the invalid property and retain the draft for correction

### Requirement: Undo and redo within an editing session
The editor SHALL support undo and redo for button addition, deletion, duplication, property changes, reordering, and resizing. Undo history SHALL apply to the current editing session; a new edit after Undo SHALL invalidate the superseded redo branch. Saving SHALL persist the resulting layout rather than its undo history.

#### Scenario: Undo a deletion and redo it
- **WHEN** the user deletes a button, invokes Undo, and then invokes Redo
- **THEN** the editor SHALL restore and remove the same button with its original properties and position

### Requirement: Named layout profiles
Users SHALL create, name, rename, duplicate, select, and delete layout profiles without a fixed three-profile limit. Profiles SHALL have independent identities so renaming or duplicating one cannot overwrite another. Profile deletion SHALL require confirmation, and cancelling deletion SHALL preserve it.

#### Scenario: More than three layouts
- **WHEN** the user creates a fourth named layout and switches among profiles
- **THEN** all profiles SHALL remain independently selectable with their saved buttons intact

#### Scenario: Rename or duplicate a profile
- **WHEN** a user renames or duplicates a saved profile
- **THEN** the existing profile SHALL retain its identity or a new independent profile SHALL be created, respectively
- **AND** no unrelated layout SHALL be overwritten

### Requirement: Safe legacy layout migration
The first upgrade SHALL preserve the active legacy layout and each existing preset as independently named profiles, retaining button order, title, command text, width, and color. Supported comma-separated and `<xmousesep>` formats SHALL remain readable, including the legacy default color when absent. Migration SHALL be repeatable and commit validated replacements before marking sources migrated. Malformed source data SHALL be retained with an actionable warning instead of silently restoring defaults over it.

#### Scenario: Migrate active layout and all three presets
- **WHEN** the active legacy file and three valid preset files exist during upgrade
- **THEN** four profiles SHALL be created without losing their differing content
- **AND** restarting migration SHALL NOT duplicate them

#### Scenario: Damaged legacy layout
- **WHEN** a legacy file cannot be parsed safely
- **THEN** the original content SHALL remain available and the user SHALL receive a migration warning
- **AND** valid migrated profiles SHALL NOT be replaced by defaults

### Requirement: Command-preserving persistence and recovery
Saving and reloading layouts SHALL preserve custom command text, including spaces, quotes, commas, Unicode, and line breaks supported by the new format. Storage failure or interrupted writes SHALL retain the last valid saved profile and the user's recoverable draft. The application SHALL NOT copy its managed SSH authentication credentials into layout persistence.

#### Scenario: Save complex command text
- **WHEN** the user saves and reloads a button containing quotes, spaces, commas, or multiline command text
- **THEN** the command SHALL be restored without truncation or unintended rewriting

#### Scenario: Save interrupted
- **WHEN** writing a profile fails or is interrupted
- **THEN** the last valid saved profile SHALL remain readable
- **AND** the application SHALL not report the failed save as successful

### Requirement: Accessible editing and runtime compatibility
Grid editing SHALL expose labeled, reachable controls and non-drag alternatives for moving and resizing buttons. Saved buttons SHALL execute through the existing desktop-command behavior only in normal control mode. Display resizing SHALL NOT change the saved logical layout.

#### Scenario: Reorder without dragging
- **WHEN** the user selects a button using accessibility navigation and invokes Move earlier or Move later
- **THEN** the button SHALL move in the logical ordering without requiring a drag gesture

#### Scenario: Use a saved custom button
- **WHEN** the user leaves edit mode and activates a saved button in normal control mode
- **THEN** the application SHALL execute its configured command through the selected xdotool/ydotool or custom-shell behavior
