# Layout editor redesign — 2026-10-27

## Approved scope and reference

Redesign the existing full-screen layout editor and its Visual/XML source modes using layout-editor-reference.html.

The current app screenshots show a layout with 57 buttons. The interactive reference uses 18 sample buttons so the states are easy to inspect. The native app must load and display the actual layout and its actual button count; never truncate, replace, or reseed the user's layout.

The reference begins inside the editor. Back returns to the main remote-control design. In the reference, Actions → Edit or the hamburger menu's Layouts & actions → Edit my actions reopens this editor. Implement equivalent native navigation from the actual existing entry points.

This editor-specific reference takes precedence over the earlier simplified Edit interaction in design-reference.html. Keep the previously approved main app design, two tabs, inline typing placeholder, visible Connect/Disconnect control, and removal of Gyro.

## Problem being addressed

The current text-like buttons, weak tab distinction, many disabled controls, and long technical paragraphs make the available actions hard to identify. Separate navigation, selection actions, XML tools, and saving through placement and clearly bounded native controls.

Keep the existing working storage, validation, draft recovery, undo/history, command handling, and save semantics. This task changes the interface; it does not authorize replacing the XML schema or parser.

## Shared editor shell

- Native Back icon, Layout editor title, and labeled Help icon.
- Compact layout name and actual button count; Saved, Unsaved changes, or XML edited status.
- A visible segmented Visual / XML source tab control. The selected segment has the solid green accent and contrasting text. The other segment remains an enabled, outlined button, not gray disabled text.
- Keep focus, accessibility selection state, and keyboard navigation consistent.
- Primary actions are persistent in a bottom action area in the native app, respecting system bars and the IME.
- Only the relevant footer is present: Visual has Discard / Save changes; XML has Cancel XML / Apply & return.
- Scroll the actual editor content rather than allowing actions to disappear behind the system navigation bar or keyboard.

Use the existing app colors and system font from DESIGN_SPEC.md. Controls should have clear borders/fills, roughly 10–14 dp corners, and at least 48 dp effective touch targets where possible. Keep disabled controls visibly shaped and distinguish their state without making normal enabled tabs look disabled.

## Visual mode

### Toolbar

- Undo and Redo are bounded icon buttons with accessible names.
- Add action is a clearly bounded green-accent secondary button with icon and text.
- Preserve existing undo/redo availability. Selecting a button alone must not create an undo step.

### Preview

- Show the actual ten-column layout geometry and ordered buttons.
- Preserve each stored width, color, title, ID, and command.
- The screenshot's legacy colors and arbitrary widths remain user data, even when different from the reference palette.
- A compact label and one short hint replace the large explanatory paragraphs: Button layout; Tap a button to select; Hold to reorder · preview only.
- Selecting a button gives it a strong accent outline plus a visible selection marker. Selection must be recognizable on every custom color.
- Preview interactions select or move buttons; they must never run remote commands.
- Keep existing hold-and-drag insertion behavior. Maintain native accessible alternatives for reordering, especially where a legacy one-column button is too narrow to edit comfortably.
- Do not silently expand or shrink saved widths to fit a new tile system.

### Selection controls

Show a contextual control area only when a button is selected:

- Selected title and an explicit Clear selection control.
- Edit and Duplicate as visibly bounded buttons with labels.
- More opens Move earlier, Move later, and Delete button.
- Disable unavailable moves at the boundaries or explain the result.
- Deleting requires the app's appropriate confirmation and remains undoable within the draft.
- Preserve selection/focus when reasonable after edits or reordering.

This avoids showing a wall of disabled Edit/Duplicate/Delete controls before a selection.

### Button editor

Use a well-spaced native sheet/screen:

- Button title.
- Command, preserving its existing opaque meaning.
- Button size, with readable presets where useful.
- Color choices, preserving custom values.
- Cancel and Apply changes when editing; Cancel and Add action when adding.

The reference's size examples map to the ten-column grid: Small 2, Medium 3, Half row 5, Full row 10. These are convenience examples. Keep any existing width from 1 through 10 intact and editable. Likewise preserve arbitrary existing RGB colors; do not force them into four presets.

Applying an individual button edit updates the recoverable draft, not the saved profile. The main Save changes button completes persistence. Do not confuse raw shell commands with xdotool arguments such as key alt+F4.

### Visual footer

- Save changes is the only high-emphasis persistence action.
- Discard is an outlined secondary action.
- Both reflect actual dirty state; no-change state can disable them while retaining recognizable button shapes.
- Brief status: Your saved layout is up to date, or Changes are in your draft. Save to finish.
- Discard restores the saved layout and follows the existing appropriate confirmation/recovery behavior.
- Back protects unsaved work using the app's current draft and confirmation rules.

## XML source mode

### Tools

- Validate and Format are outlined, labeled buttons, not free-floating green text.
- More holds XML format help and Reset XML.
- The XML field is a sufficiently large monospace editor, with useful native scrolling, selection, and keyboard behavior.
- Avoid the large permanent schema explanation above the editor.
- Show short validation feedback next to the field. Invalid XML gets an error outline/message and a useful line number if available. Do not show raw multi-paragraph parser output.

### Apply and return

The footer primary action is Apply & return:

1. Validate using the existing authoritative version-1 parser.
2. If invalid, remain in XML, identify the error, and leave the visual draft/saved layout unchanged.
3. If valid, atomically update the recoverable visual draft.
4. Return to Visual for review.
5. Do not save the profile until Save changes is explicitly used in Visual.

A one-line note is enough: Apply to your draft, then save in Visual.

The reference's XML schema and parser are illustrative browser code. Do not copy them into Android. Preserve the app's real version-1 schema, field constraints, current layout ID/name/ten-column rules, and all valid supported data.

### Validation, formatting, reset, and cancellation

- Typing changes the XML buffer only.
- Validate checks without saving or applying.
- Format keeps comments and opaque command text according to the app's existing behavior. It must not save or unexpectedly alter command content.
- Applying follows existing semantics for comments: the screenshot says comments survive formatting but are not persisted layout data.
- Reset XML restores the current visual draft's serialization, not an unrelated original preset. Protect unapplied edits appropriately.
- Cancel XML or switching back to Visual with unapplied edits offers a clear way to keep editing or discard only those XML edits. Do not discard the already-applied visual draft.
- Keep Save changes out of the XML footer rather than displaying a second disabled, ambiguous save action.
- Keep XML recovery consistent with existing app behavior across configuration changes and process recreation.

## Help

Put technical guidance behind the Help icon and XML format help.

Explain succinctly:

- Visual operations update a draft.
- Save changes persists the layout.
- XML Apply & return updates the draft.
- Validate and Format do not save.
- Version-1 layout structure and comment rules belong in expandable format details.

Do not reproduce implementation explanations in the normal control flow unless they help the user make a decision.

## Validation and delivery

Use actual existing app code and real user configuration. In addition to the main prompt's build checks:

- Verify full 57-button and larger existing layouts, not only the sample.
- Check Visual/XML tab state, selected/unselected controls, undo/redo, add/edit/duplicate/delete, and reorder.
- Verify malformed XML, invalid supported-schema fields, duplicate IDs, unsupported versions, and valid custom layouts using the authoritative parser.
- Confirm invalid or canceled XML cannot partially mutate the draft or saved layout.
- Verify Format preserves supported comments and exact opaque command semantics.
- Confirm Apply updates only the draft, Save persists, Discard restores, and recovery behavior remains intact.
- Check narrow screens around 320 dp, larger font scale, light/dark themes, TalkBack focus, and IME visibility.
- In the final implementation report, separate actual device/build/storage checks from browser-reference demonstrations.
