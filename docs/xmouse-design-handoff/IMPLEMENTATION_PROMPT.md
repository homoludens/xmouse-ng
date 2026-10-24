# Implement the approved XMouse redesign

You are working in an existing Android app forked from XMouse. It controls a Linux computer over SSH on a local network. The user approved the attached design.

Implement this design in the app. Keep the app simple, responsive, and small. Continue through implementation and verification; do not stop after a plan or static mockup.

## First steps

- Read applicable repository instructions, including AGENTS.md.
- Read DESIGN_SPEC.md beside this prompt.
- Open design-reference.html in a browser and exercise the tabs, computer selector, settings, typing, and action editor. Inspect its .xm-* CSS for exact styling.
- Inspect the existing UI toolkit, navigation, SSH lifecycle, input dispatch, host/authentication storage, keyboard layouts, custom buttons, scripts, and preferences before editing.

## Constraints

- Keep the current native UI stack: Java/XML if already used, or Compose if already used. No framework rewrite.
- Reuse the existing SSH, discovery, authentication, input, and script execution code.
- Preserve saved hosts/ports/credentials, layouts, custom buttons, scripts, ordering, and preferences. Make any storage changes backward compatible.
- No WebView implementation. Do not ship this HTML, its JavaScript, or its preview libraries inside the APK.
- Use system typography and local vector icons. Avoid external fonts, large images, animation libraries, and unnecessary dependencies.
- Do not add cloud services, accounts, a companion daemon, telemetry, or onboarding.
- Preserve app identity, package/signing setup, minimum Android version, transport behavior, and host-key checks.

## Navigation and screens

Replace the drawer and giant mixed key grid with three bottom destinations:

1. Touchpad — dominant pointer surface, scroll strip, left/middle/right click, existing gyro mode, common keys, and a labeled typing control.
2. Keys — Common, Navigate, Media, and F1–F12 categories.
3. Actions — saved buttons, layout selection, simple creation/editing, and saved scripts.

The header contains the current computer, actual connection status, and settings. The computer name opens a switch/manage sheet.

Match the reference's calm neutral surfaces, muted green accent, hierarchy, spacing, and light/dark palettes. Use native Android system bars and IME, not the prototype's drawn device chrome.

## Functional requirements

### Touchpad and typing

- Wire movement, tap/click, drag/hold, scroll, and existing alternative input modes to real app behavior.
- Preserve gyro and joystick features if present; less frequent modes can live in settings/overflow.
- Keep the pointer surface as large as practical and controls within reach.
- “Type on computer” uses the real phone keyboard, an explicit Send action, and Esc/Tab/Backspace/Enter controls.
- Preserve existing text encoding/escaping, including non-ASCII text.

### Computers

- Support selecting/connecting, editing, adding, finding, and disconnecting using existing capabilities.
- Use persistent field labels for name, hostname/IP, username, password/authentication, and an Advanced SSH-port field.
- Persist every entered value, not only the displayed name.
- Preserve an existing password if a blank replacement field means “unchanged.” Never overwrite a saved credential with a placeholder.
- Use actual test/discovery results. Do not show success before the operation succeeds.
- Keep existing deletion accessible separately from Save.
- Keep connection work off the UI thread and handle lifecycle/rotation appropriately.

### Keys/media

- Organize the existing keys according to DESIGN_SPEC.md.
- Demo percentages are illustrative. If the app only sends relative volume/brightness adjustments, implement minus/plus and mute rather than inventing absolute levels.
- Use sliders only when actual levels can be read/controlled reliably through existing capabilities.

### Actions/scripts

- Creation/editing/deletion, layout selection, and script execution must work with real persisted data.
- Editor: name, action type, corresponding shortcut/command, Regular/Wide size, and preset colors.
- Preserve existing command semantics. An xdotool suffix such as “key alt+F4” must not silently become a shell command.
- Keep arbitrary old xdotool commands accessible through an Advanced editor if needed. Raw shell commands use the existing script path.
- Preserve custom legacy widths/colors and layout association. Presets are conveniences, not destructive conversions.
- Do not seed the user's saved data with demo hosts, sample buttons, scripts, or layouts.

### Settings and states

- Bind pointer/gesture settings to working saved preferences. Do not show inert preview-only options.
- Handle connecting, connected, disconnected, failed connection, command failure, empty lists/layouts, and invalid forms.
- Provide a direct reconnect path when disconnected.
- Support system light/dark mode, TalkBack labels, Android Back, font scaling, system insets, and the on-screen keyboard.

## Work sequence

1. Map each flow to existing classes/resources/storage.
2. Implement reusable theme resources and native control styles.
3. Build header/bottom navigation and the three destinations.
4. Wire pointer/keyboard behavior.
5. Implement computer, typing, action, and settings editors.
6. Preserve or migrate existing configuration.
7. Run the normal debug build and relevant checks; verify in an emulator/device when available.
8. Report completed changes, validation, APK/dependency impact when measurable, and unresolved limitations.

## Acceptance checks

- Normal debug build succeeds.
- The three destinations, forms, and visual hierarchy match the reference.
- Saved hosts, authentication data, layouts, buttons, and scripts remain usable after upgrade.
- New/edited settings and actions survive restart.
- Controls use real dispatch, not demo messages or fake successes.
- Legacy xdotool commands retain their meaning.
- No clipping/overlap at about 320 dp width, larger font scale, or with the keyboard open.
- Both themes are readable; effective native touch targets are at least 48 × 48 dp.
- Explain deliberate deviations, especially media controls and input modes.
- Add focused tests for changed persistence/migration/dispatch logic where useful; use device/emulator inspection for layout.
- If no Linux host is available, distinguish verified build/UI/dispatch from SSH checks still needing hardware.
- Do not claim device, migration, or SSH validation you did not perform.
