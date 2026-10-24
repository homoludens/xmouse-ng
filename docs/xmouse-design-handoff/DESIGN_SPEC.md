# XMouse UI specification

## Goal and source of truth

A small native Android remote mouse/keyboard app for Linux over local-network SSH.

The approved appearance is in design-reference.html. Its .xm-* CSS defines product components; enclosing iframe/preview machinery only displays the reference in a browser.

Keep existing app behavior and stored data. The demo is not a backend specification. Real Android system bars, IME, connection state, and error handling replace the prototype's fake system bars, sample data, and “Preview” feedback.

## App structure

| Location | Content |
| --- | --- |
| Header left | Computer icon, small app name, current computer name and chevron |
| Header right | Connection dot plus text; Settings icon |
| Main | Current destination |
| Bottom navigation | Touchpad / Keys / Actions |

The computer name opens the Computers sheet, replacing drawer-based host management.

Use concise labels and neutral surfaces. Green identifies the primary action/selected navigation. Color is always paired with labels or icons.

## Palette

Create native semantic resources for both themes.

| Purpose | Light | Dark |
| --- | --- | --- |
| App background | #F7F8FA | #15191F |
| Surface/fields | #FFFFFF | #1F252D |
| Soft controls | #EEF1F4 | #272E37 |
| Primary text | #20272E | #EDF1F5 |
| Secondary text | #626D78 | #A5B0BC |
| Borders/dividers | #DCE2E7 | #353D47 |
| Primary accent | #216A58 | #9CD9C4 |
| Text on primary accent | #FFFFFF | #10372C |
| Soft accent surface | #E0EFE8 | #263F37 |
| Destructive text | #A64041 | #FFB2AD |
| Destructive soft surface | #FBE9E7 | #462B2C |
| Optional blue action surface | #E8EFFA | #273548 |
| Text on blue action surface | #345F98 | #B2CEFA |

No imagery or gradients are necessary. A faint touchpad dot texture is optional.

## Typography and geometry

- System font, regular and medium weights.
- Screen title: about 23–24 sp. Computer name: 19–20 sp.
- Main labels: about 14 sp; editable fields: 16 sp; secondary text: about 12 sp.
- Horizontal padding: about 18 dp; 14–16 dp on narrow screens.
- Gaps: 8–12 dp; section spacing: 20–24 dp.
- Button/field corners: 10–14 dp. Touchpad: 18–20 dp. Bottom-sheet top corners: about 24 dp.
- Icons: 20–24 dp; consistent line weight.
- Effective Android touch targets: at least 48 × 48 dp.
- Subtle borders, little shadow, native press/focus feedback.
- Respect user font size, screen insets, and keyboard visibility.

The demo's 390 px device width and sample touchpad height are illustrative. Native layouts must fill actual screens. Use a flexible touchpad and scrollable long forms/keys lists while keeping app navigation stable. Do not reproduce the drawn phone outline or gesture bar.

## Touchpad

Order:

1. Touchpad title and compact Gyro toggle, if supported.
2. Large rounded touchpad with a right-hand scroll strip.
3. Left click, middle click, right click.
4. Esc, Alt + Tab, Super.
5. Full-width “Type on computer” control in the soft accent color.
6. Bottom navigation.

The pad may show a small icon, “Move pointer”, and “Tap to click · two fingers to scroll”; deemphasize the hint during use.

Keep all existing gestures and hold/drag behavior. Preserve gyro/joystick access even though the demo only illustrates Gyro. Less frequent modes may use settings/overflow.

Typing uses a real IME and a labeled text area with Send plus Esc, Tab, Backspace, Enter. Its sheet/screen must adapt when the keyboard opens.

## Keys

Four local categories:

| Category | Controls |
| --- | --- |
| Common | Window: Esc, Super, Switch/Alt+Tab, Minimize, Maximize, Close. Editing: Copy, Cut, Paste, Undo, Redo, Select all. File: New, Open, Save, Find, Print. |
| Navigate | Directional grid with Home/End; Page up/down, Tab, Delete, Backspace, Enter; zoom out/reset/in. |
| Media | Previous, Play/Pause, Next; volume/mute and brightness. |
| F1–F12 | Labeled function-key grid. |

Most buttons are neutral. Close has a subdued red surface.

The volume/brightness percentages in the reference are sample values. If the app only sends relative adjustments, show minus/plus and mute without a made-up percentage. Use sliders only when real state is available and controllable. Avoid continuous SSH polling solely for visual decoration.

Use “Super” as the clear label; a suitable platform icon may replace the demo's command-like symbol.

## Actions

- Heading with explicit Edit/Done control.
- Layout picker preserving actual layout names/order.
- Two-column custom-action grid: icon, title, optional subtitle.
- Regular = one cell; Wide = full row.
- Add action below the grid.
- Saved scripts section with labeled entries and explicit Run/Edit controls.

Edit mode makes editing discoverable without depending on long press. Preserve reorder/layout management if already supported via a compact contextual control.

Terminal, Close window, File manager, Show desktop, “gg”, and Layout 1–3 are examples. Render actual user data and a simple empty state.

### Editor

A bottom sheet when it fits; otherwise a full-height scrollable form.

- Name.
- Type: keyboard shortcut or supported command/script type.
- Matching shortcut/command input.
- Regular/Wide size.
- A few preset colors.
- Primary Add/Save.
- Separate Delete when editing.

Normal creation must not require entering a width “out of 10” or a color hex code.

### Legacy compatibility

Preserve arbitrary saved widths and RGB colors. If storage uses a ten-unit width, new Regular/Wide presets can map to 5/10 only after checking actual layout semantics. Existing custom widths should stay intact and accessible as an advanced/custom value.

Preserve the distinction between a key combination, xdotool arguments, and a raw SSH command. “key alt+F4” must keep its original dispatch meaning. An Advanced “xdotool command” editor is acceptable. Raw SSH commands should use existing script execution.

## Computers

The header sheet supports selecting/connecting, editing, adding, finding, and disconnecting using the app's existing capabilities.

Use a full-screen editor with persistent labels:

1. Optional display name.
2. Required hostname/IP.
3. Required username.
4. Password visibility toggle, or existing authentication UI.
5. Advanced section with actual saved SSH port.
6. Test connection.
7. Save computer / Save changes.

Keep all currently supported authentication methods. The demo's port 222 is example data; new-host defaults follow existing behavior, normally 22.

A saved password placeholder must not become an empty replacement on Save. Preserve untouched fields. Keep deletion separately accessible using normal app confirmation behavior.

Discovery and connection testing use real results. Never turn a demo toast into a fake success. Handle validation, failures, cancellation, and Back/keyboard behavior.

## Settings

The reference shows pointer speed, Tap to click, Natural scrolling, and Haptic feedback. Bind them to working preferences, or omit unsupported preview-only settings until implemented. Load actual saved values and persist changes.

Follow system light/dark appearance by default.

## Real states missing from the simulation

Implement appropriate native states for:

- No saved computer, connecting, disconnecting, connected, disconnected.
- Reconnect, authentication/connection failure, command failure.
- Empty layout, no scripts, incomplete/invalid input.
- Rotation/configuration changes, background/foreground, keyboard open.
- Large fonts and accessibility focus.

Preserve SSH host-key verification and credential handling.

## Size and scope

Reuse the existing native toolkit and app architecture. Use system fonts and local vector resources. The browser reference's bundled libraries are not Android dependencies.

No cloud accounts, desktop daemon, telemetry, new onboarding, or unrelated screens belong to this redesign.
