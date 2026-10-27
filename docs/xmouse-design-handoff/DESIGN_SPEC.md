# XMouse-NG UI specification

Revision: 2026-10-26, latest. This two-tab design uses inline typing, removes Gyro, and adds a prominent connection button. It supersedes earlier typing-sheet and gyro variants.

## Goal and source of truth

A small native Android remote mouse/keyboard app for Linux over local-network SSH.

The approved appearance is in design-reference.html. Its .xm-* CSS defines product components; enclosing iframe/preview machinery only displays the reference in a browser.

Keep existing app behavior and stored data. The demo is not a backend specification. Real Android system bars, IME, connection state, and error handling replace the prototype's fake system bars, sample data, and “Preview” feedback.

## App structure

| Location | Content |
| --- | --- |
| Header left | Hamburger icon, small XMouse-NG app name, current computer name and chevron |
| Header right | Prominent Connect/Disconnect button with plug icon; settings live in the hamburger menu |
| Main | Current destination |
| Shared footer | Inline text field with placeholder Type on computer plus adjacent Send button on both pages |
| Bottom navigation | Touchpad / Actions |

The hamburger menu contains all settings and management. The computer name also opens a convenient quick Computers sheet. There is no Keys tab or separate Keys screen.

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

The words Type on computer belong INSIDE the input as placeholder text, not in a visible label above it. Keep an accessibility name of Type on computer even when the placeholder disappears while typing. This applies on both Touchpad and Actions.

## Touchpad

Order:

1. Touchpad title, with no Gyro control.
2. Large rounded touchpad with a right-hand scroll strip.
3. Left click, middle click, right click.
4. Esc, Alt + Tab, Super.
5. Inline “Type on computer” input and an adjacent filled Send button, in a shared footer also shown on Actions.
6. Bottom navigation.

The pad may show a small icon, “Move pointer”, and “Tap to click · two fingers to scroll”; deemphasize the hint during use.

Keep existing touch gestures and hold/drag behavior. Remove Gyro from the UI rather than moving it into the menu. Preserve unrelated joystick/pointer behavior where applicable.

Typing is inline on the current control page. Use a real text field showing Type on computer as its placeholder, and an adjacent Send button with a send icon and visible label. Tapping the field opens the native IME in place. Neither focusing nor sending opens another page, sheet, or dialog. Support IME Send and non-ASCII text. A shared composer preserves the unsent draft across Touchpad/Actions. Disable Send for blank text or while disconnected; keep the field editable and retain the draft. Show a small Disconnected — connect to send hint when appropriate. Clear after sending and Send Enter after text follow Keyboard settings. Adapt the footer to keyboard/system insets.

## Control presets within Actions

The old Keys tab is removed. Its controls remain available through optional layouts/presets in the Actions layout picker, alongside the user’s own saved layouts. Do not create a separate Keys destination. The reference provides:

| Category | Controls |
| --- | --- |
| Common | Window: Esc, Super, Switch/Alt+Tab, Minimize, Maximize, Close. Editing: Copy, Cut, Paste, Undo, Redo, Select all. File: New, Open, Save, Find, Print. |
| Navigate | Directional grid with Home/End; Page up/down, Tab, Delete, Backspace, Enter; zoom out/reset/in. |
| Media | Previous, Play/Pause, Next; volume/mute and brightness. |
| F1–F12 | Labeled function-key grid. |

Most buttons are neutral. Close has a subdued red surface.

The media preset’s volume/brightness percentages in the reference are sample values. If the app only sends relative adjustments, show minus/plus and mute without a made-up percentage. Use sliders only when real state is available and controllable. Avoid continuous SSH polling solely for visual decoration.

Use “Super” as the clear label; a suitable platform icon may replace the demo's command-like symbol.

## Actions

- Heading with explicit Edit/Done control.
- Layout picker preserving actual layout names/order.
- Two-column custom-action grid: icon, title, optional subtitle.
- Regular = one cell; Wide = full row.
- Add action below the grid.
- Saved scripts section with labeled entries and explicit Run/Edit controls.
- The same inline text input plus Send as Touchpad, directly above bottom navigation. No typing navigation or modal. The draft remains available for custom layouts, empty layouts, and built-in presets.
- Optional Common keys, Navigation, Media, and F1–F12 presets in the layout picker, preserving those controls after removing the Keys tab.

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

## Header connection button

Use an actual, clearly bounded action button at the header’s right, not a small status label.

- Connected: visible Disconnect label, unplug icon, soft accent fill, and green accent border.
- Disconnected: visible Connect label, plug icon, and solid accent fill with contrasting text.
- Minimum effective native touch target: 48 dp. Keep label and icon readable at 320 dp width.
- Connecting: use an in-progress state and prevent duplicate requests; show actual failures clearly.
- The control remains available on both main pages.
- Toggling the connection must not discard an unsent text draft.
- Update state only from real SSH lifecycle results in the native app. The browser’s immediate state change is a simulation.

## Computers

Computers is available from the hamburger menu and the header’s computer name. Its sheet supports selecting/connecting, editing, adding, finding, and disconnecting using the app's existing capabilities.

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

## Hamburger menu and settings

Use a left navigation drawer opened by a visible hamburger button. The drawer is for settings/management; the two main control pages remain in bottom navigation. Close the drawer on selection, outside tap, or Android Back. Return accessibility focus appropriately.

| Group | Destinations |
| --- | --- |
| Connection | Computers |
| Input | Touchpad settings, Keyboard settings |
| Customize | Layouts & actions, Saved scripts |
| App | Appearance, About XMouse-NG |

Touchpad settings holds pointer/gesture preferences. Keyboard settings applies to typing from either main page; the reference demonstrates Clear after sending and Send Enter after text. Layouts & actions can select a layout or jump into editing user actions. Saved scripts keeps script management discoverable. Appearance offers System, Light, and Dark.

Remove the old header Settings button so settings have one consistent entry point. The computer-name shortcut may remain for quick switching.

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
