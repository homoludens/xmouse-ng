## Context

See `proposal.md` and the approved handoff under `docs/xmouse-design-handoff/`. The interactive HTML was exercised offline in Chromium: all three tabs, four key categories, Computers, the computer editor/port disclosure, Settings, Typing, and action creation/editing. Light/dark and narrow-width screenshots are available in the local verification workspace; browser code is reference material, not an application dependency.

The current control screen is Java/XML with overlapping mouse, keyboard, and joystick containers and drawer-driven host/script routing. The session is already owned by a Kotlin foreground service. Input uses generation-scoped surface/gesture capabilities; losing a surface cancels stale movement and releases held inputs. Existing Compose layout editors/managers own validated drafts and profile history. Profiles use stable IDs, raw commands, RGB colors, and ten-column spans. Host secrets live behind the protected credential repository; existing host editing currently decrypts a password into its field and treats a cleared field as a replacement.

## Goals / Non-Goals

**Goals:**
- Match the approved hierarchy and palette with native widgets while retaining existing command, session, and persistence owners.
- Make every displayed control operate on real state and preserve all existing user configuration.
- Make navigation/overlays explicit input-ownership boundaries and make release provenance verifiable.

**Non-Goals:**
- Framework conversion, a WebView, HTML/JavaScript in the APK, browser-library dependencies, desktop daemon, telemetry, or onboarding.
- Invented media levels, broad background clipboard access, silent command reinterpretation, application-ID or minimum-SDK changes.
- Completing or archiving unrelated pending verification in `modernize-xmouse` without actually running it.

## Decisions

### Native semantic theme and visible branding

Use the existing View toolkit and Material components already present for the control shell, bottom navigation, sheets, fields, and forms. Define semantic colors in `values`/`values-night` using the handoff palette; use system fonts, local vectors, rounded surfaces, and at least 48dp effective targets. Existing Compose editors/managers receive the same system-following color scheme. The visible brand becomes XMouse-NG; `com.stripe1.xmouse`, storage paths, preference keys, secret/trust identity, and API 26 floor remain stable. Native system bars and IME replace the reference's drawn device chrome.

Alternative rejected: a Compose or WebView rewrite of the controls would unnecessarily replace working gestures and enlarge the compatibility surface.

### Shell and input capabilities

Introduce a native header and bottom destinations without constructing a second session/transport. Header status is derived from actual session phases; selecting a computer uses the existing selected-host/session path. Replace drawer routes with explicit computer/settings/actions entry points. Separate shell/navigation presentation from command dispatch so content controllers can be tested without real user credentials.

Track destination and overlay ownership explicitly. Before a destination, computer/settings sheet, action edit mode, or typing flow changes the eligible surface, revoke the prior pointer/gesture capability and reset held view state. Typing/key controls obtain only their own fresh eligible capability. Closing a sheet or returning to a destination cannot resurrect a stale gesture or disconnect a healthy service. Back closes the most local layer first. Save/restore navigation/form state across recreation without leaking secret text through instance-state bundles.

### Touchpad, keys, and typing

Retain the actual pointer/joystick implementations and capability-based click/hold/scroll dispatch. Fit a large flexible pad with a right scroll strip, labeled left/middle/right clicks, Esc/Alt+Tab/Super, gyro, and Type on computer. Less frequent joystick/session/clipboard controls remain discoverable through settings/overflow. Categorized native key grids use an explicit static catalog of existing dispatch meanings; media/brightness use relative actions and mute rather than fake sliders or polling.

Typing uses a native IME and an explicit Send action. Preserve existing Unicode/shell-escaping behavior through a tested shared boundary, not ad hoc command concatenation. Existing batch preferences are retained, but merely entering the new typing field must not send its contents. Saved pointer/scroll settings remain authoritative; unsupported preview-only options are omitted rather than inert.

### Computers and protected edits

Build a real computer sheet and persistent-label editor over existing host/discovery/credential repositories. New host defaults remain port 22. Validate hostname, port, required username, and accidental leading/trailing username whitespace locally without logging values. Existing password fields stay blank with an unchanged hint; metadata-only Save preserves the existing opaque credential reference, without decrypting it into the form. A nonblank replacement uses the established protected write path. Global key/passphrase selection remains global and its existing UI stays available. Delete remains separate and confirmed.

Test connection must use the entered candidate without silently saving it or executing an initial command/script. Introduce a bounded, cancellable attempt-only probe using the existing SSH/trust/credential boundaries; it must not replace the active control session, bypass fingerprint approval, retain secret form content after close, or show success before actual authentication/channel readiness. Dispose its resources on completion/cancellation and scope callbacks to the current form. This is separate from the normal saved-profile Connect operation.

### Actions and scripts

Render actual selected-profile buttons in a two-column Actions presentation. Use the existing ten-unit model: new Regular/Wide presets correspond to 5/10, while arbitrary old spans/colors remain unchanged and editable through Advanced controls. Existing stable IDs, order, raw commands, profile association, drafts, and history remain authoritative. A shortcut editor serializes through the existing custom-command dispatch contract; unknown legacy commands remain Advanced commands, never silently become raw shell scripts. Raw shell commands continue to use the existing script repository/exec route. Provide explicit Edit/Done, Add, Save/Delete, profile management, and access to reorder/history. A fresh installation must not be populated with the reference's example hosts/actions/scripts.

### Verification and publication

Work one tagged task at a time and checkpoint independently. Two of ten tasks are `[high]`: the main shell/input-lifetime extraction and the attempt-only computer-test/credential-preservation integration. The remaining defined UI/library/build work is `[low]`.

Use focused tests while changing code; run the full JVM suite, lint, debug/release builds, and instrumentation compilation at the final gate. Exercise actual device/emulator UI when available, especially 320dp width, large fonts, IME, Back, and both themes. Record unrun runtime/Linux/migration checks rather than infer passes from fakes or compilation.

Use the supplied external release keystore and alias through local-only Gradle properties/environment; never commit passwords or keystores. Verify the signed APK certificate against the user-provided SHA-256 before publishing. The release build must exclude debug fixtures and browser reference assets/libraries. Keep the original upstream remote, add a separate remote for `homoludens/xmouse-ng`, avoid force pushes, and publish the source and APK through authenticated GitHub CLI operations only after verification. Include version, checksum, certificate fingerprint, size/dependency impact, and honest remaining checks in release notes.

## Risks / Trade-offs

- [Same-Activity sheets do not trigger onPause] → Explicit overlay ownership and stale-input regression tests, without replacing the controller's session state machine.
- [Blank password mistaken for a new empty credential] → Repository metadata-only preservation path, typed replacement intent, and protected-reference tests.
- [Unsaved probe accidentally mutates or replaces a saved session] → Separate bounded attempt owner, no durable Save or initial command, cancellation and trust tests.
- [Simplified action presets destroy custom data] → Defaults apply only on deliberate new choices; untouched widths/colors/commands round-trip exactly.
- [Reference preview hides real limitations] → No demo data, fake connection successes, absolute levels, or inert settings.
- [Release key differs from installed debug build] → Do not uninstall or clear a user's app automatically; document the signature/update constraint.

## Migration Plan

Retain existing tables, profile formats, preference names, and secret/trust stores. Add non-destructive repository APIs only where the new UI needs explicit edit intent. Verify retained legacy fixtures and existing saved representations before publishing. Release rollback is not accomplished by installing an older legacy APK over the modern protected-storage schema.
