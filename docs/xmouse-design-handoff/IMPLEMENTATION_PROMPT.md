# Implement the approved XMouse-NG redesign

You are working in an existing Android app forked from XMouse. It controls a Linux computer over SSH on a local network. The user approved the attached design, revised on 2026-10-26. This revision supersedes earlier three-tab designs, the separate typing sheet, and the Gyro control.

Implement this design in the app. Keep the app simple, responsive, and small. Continue through implementation and verification; do not stop after a plan or static mockup.

## First steps

- Read applicable repository instructions, including AGENTS.md.
- Read DESIGN_SPEC.md and LAYOUT_EDITOR_SPEC.md beside this prompt. The editor-specific specification and layout-editor-reference.html are the latest approved Visual/XML editor design.
- Open layout-editor-reference.html and exercise Visual, XML source, selection/editing, validation, Apply & return, and Save changes. The browser parser/sample layout are illustrative, not replacement native code.
- Open design-reference.html in a browser and exercise the tabs, computer selector, settings, typing, and action editor. Inspect its .xm-* CSS for exact styling.
- Inspect the existing UI toolkit, navigation, SSH lifecycle, input dispatch, host/authentication storage, keyboard layouts, custom buttons, scripts, and preferences before editing.

## Constraints

- Keep the current native UI stack: Java/XML if already used, or Compose if already used. No framework rewrite.
- Reuse the existing SSH, discovery, authentication, input, and script execution code.
- Preserve saved hosts/ports/credentials, layouts, custom buttons, scripts, ordering, and preferences. Make any storage changes backward compatible.
- No WebView implementation. Do not ship this HTML, its JavaScript, or its preview libraries inside the APK.
- Use system typography and local vector icons. Avoid external fonts, large images, animation libraries, and unnecessary dependencies.
- Do not add cloud services, accounts, a companion daemon, telemetry, or onboarding.
- Use XMouse-NG as the displayed app name. Preserve the package/signing setup, minimum Android version, transport behavior, and host-key checks.

## Navigation and screens

Use exactly two bottom destinations:

1. Touchpad — dominant pointer surface, scroll strip, left/middle/right click and common keys. There is no Gyro control.
2. Actions — saved buttons, layout selection, simple creation/editing, and saved scripts.

Remove the Keys tab and its separate screen. Retain useful former key groups as optional layouts/presets within Actions.

Place a visible hamburger icon at the left of the header. It opens settings/management grouped as:
- Connection: Computers.
- Input: Touchpad settings, Keyboard settings.
- Customize: Layouts & actions, Saved scripts.
- App: Appearance, About XMouse-NG.

Keep the current computer and a prominent labeled Connect/Disconnect BUTTON in the header. Connected state: Disconnect with an unplug icon, soft green fill, and accent border. Disconnected state: solid green Connect with a plug icon. These are actions, not small passive status labels. The computer name may still open a quick switch/manage sheet. Remove the separate header Settings icon.

Put an INLINE text input with placeholder Type on computer and an adjacent Send button on BOTH Touchpad and Actions, directly above the two-tab bottom navigation. Do not open a separate page, sheet, or dialog for normal typing. A shared composer/footer is suitable. Keep the same unsent draft and settings when switching pages; respect the IME and screen insets. Disable Send for blank text or while disconnected, but preserve the draft so the user can reconnect and send it.

Match the reference's calm neutral surfaces, muted green accent, hierarchy, spacing, and light/dark palettes. Use native Android system bars and IME, not the prototype's drawn device chrome.

The words Type on computer belong INSIDE the input as placeholder text, not in a visible label above it. Keep an accessibility name of Type on computer even when the placeholder disappears while typing. This applies on both Touchpad and Actions.

## Functional requirements

### Touchpad and typing

- Wire movement, tap/click, drag/hold, scroll, and existing alternative input modes to real app behavior.
- Remove Gyro from the redesigned UI, including settings/overflow. Do not add another gyro entry point. Preserve unrelated pointer/joystick behavior where applicable.
- Keep the pointer surface as large as practical and controls within reach.
- The inline text field opens the real phone keyboard in place. Send submits the field directly without navigation. Support the IME Send action and preserve non-ASCII input. Keep existing special-key actions accessible through the main controls/presets; do not reintroduce a separate typing screen.
- Preserve existing text encoding/escaping, including non-ASCII text.

### Computers

- Support selecting/connecting, editing, adding, finding, and disconnecting using existing capabilities.
- Use persistent field labels for name, hostname/IP, username, password/authentication, and an Advanced SSH-port field.
- Persist every entered value, not only the displayed name.
- Preserve an existing password if a blank replacement field means “unchanged.” Never overwrite a saved credential with a placeholder.
- Use actual test/discovery results. Do not show success before the operation succeeds.
- Keep existing deletion accessible separately from Save.
- Keep connection work off the UI thread and handle lifecycle/rotation appropriately.

### Key presets and media in Actions

- Organize existing key groups as optional Action layouts/presets according to DESIGN_SPEC.md. There is no Keys tab or separate Keys screen.
- Demo percentages are illustrative. If the app only sends relative volume/brightness adjustments, implement minus/plus and mute rather than inventing absolute levels.
- Use sliders only when actual levels can be read/controlled reliably through existing capabilities.

### Layout editor

- Implement the full-screen Visual/XML editor from LAYOUT_EDITOR_SPEC.md with clear bounded buttons and selected tabs.
- Keep Validate/Format separate from Apply & return and Save changes. Only the existing explicit save action persists a profile.
- Preserve actual layouts, including the supplied screenshot’s 57-button layout, legacy widths/colors/IDs, and opaque command text.
- Use the authoritative existing XML schema/parser and preserve recoverable draft behavior. The reference uses only 18 sample buttons for demonstration.

### Actions/scripts

- Creation/editing/deletion, layout selection, and script execution must work with real persisted data.
- Editor: name, action type, corresponding shortcut/command, Regular/Wide size, and preset colors.
- Preserve existing command semantics. An xdotool suffix such as “key alt+F4” must not silently become a shell command.
- Keep arbitrary old xdotool commands accessible through an Advanced editor if needed. Raw shell commands use the existing script path.
- Preserve custom legacy widths/colors and layout association. Presets are conveniences, not destructive conversions.
- Do not seed the user's saved data with demo hosts, sample buttons, scripts, or layouts.

### Settings and states

- Put all settings and management destinations in the hamburger menu, including input preferences, computers, layouts/actions, scripts, appearance, and About.
- Bind pointer/gesture and keyboard preferences to working saved settings. The preview demonstrates Clear after sending and Send Enter after text. Keep both pages consistent. Do not show inert options.
- Handle connecting, connected, disconnected, failed connection, command failure, empty lists/layouts, and invalid forms.
- The visible header Connect button is the direct reconnect path. Reflect actual connecting/connected/disconnected/error state, prevent duplicate connection requests, and do not show success before SSH connects.
- Support system light/dark mode, TalkBack labels, Android Back, font scaling, system insets, and the on-screen keyboard.

## Work sequence

1. Map each flow to existing classes/resources/storage.
2. Implement reusable theme resources and native control styles.
3. Build the hamburger menu, header connection button, shared inline text composer, and two destinations.
4. Wire pointer/keyboard behavior.
5. Implement computer, action, and settings editors; wire the inline composer in both main pages.
6. Preserve or migrate existing configuration.
7. Run the normal debug build and relevant checks; verify in an emulator/device when available.
8. Report completed changes, validation, APK/dependency impact when measurable, and unresolved limitations.

## Acceptance checks

- Normal debug build succeeds.
- Exactly two main tabs: Touchpad and Actions. No Keys tab or separate Keys screen.
- The hamburger menu opens every documented settings/management destination.
- An inline text input plus Send is visible on both main pages. No typing page/sheet/dialog opens. Drafts survive tab changes and disconnect/reconnect; blank or disconnected sends are blocked.
- No Gyro control remains anywhere in the redesigned interface.
- A prominent labeled header button switches between Connect and Disconnect and reflects actual connection state.
- Forms and visual hierarchy match the reference.
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
