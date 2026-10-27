# XMouse-NG redesign handoff

Approved UI reference and implementation instructions for the existing Android app that controls Linux over SSH on a local network.

## Give this to your coding agent

1. Extract this folder into your app repository under docs/.
2. Tell your agent:

   > Read docs/xmouse-design-handoff/IMPLEMENTATION_PROMPT.md, inspect DESIGN_SPEC.md, and open design-reference.html. Implement the approved design in the existing Android app, including LAYOUT_EDITOR_SPEC.md and layout-editor-reference.html. Keep it small and reuse the existing SSH and input code.

The agent needs your app repository. These files contain the design, not the Android application source.

## Files

The layout editor was redesigned on 2026-10-27. Its dedicated reference overrides the earlier simplified Edit flow in the main reference.

- IMPLEMENTATION_PROMPT.md — the task, constraints, workflow, and acceptance checks.
- CHANGES.md — the latest approved navigation changes.
- LAYOUT_EDITOR_SPEC.md — Visual/XML editor design and draft/save behavior.
- layout-editor-reference.html — interactive editor reference; starts in Visual and includes XML source.
- DESIGN_SPEC.md — colors, spacing, screen behavior, compatibility rules, and demo limitations.
- design-reference.html — interactive, self-contained reference; open locally in a browser.
- THIRD_PARTY_NOTICES.txt — licenses for libraries bundled only into the browser reference.

## Reference limitations

Main control-screen revision, 2026-10-26: two main tabs (Touchpad and Actions), a hamburger settings menu, an inline input showing Type on computer as placeholder text with Send beside it on both pages, no Gyro control, and a prominent Connect/Disconnect button in the header.

Try both tabs, the hamburger menu, computer selector, inline typing, Connect/Disconnect, and Add action. Former key groups are optional Action presets, not a separate destination.

The reference simulates interactions. It does not connect over SSH, scan your network, store credentials, run commands, or read actual volume/brightness. Computer names, action contents, levels, and time are examples.

Do not reproduce the browser wrapper, drawn device frame, fake system bars, or “Preview” messages in Android. Use the project's existing native UI stack and real app state.

The HTML and its libraries are design materials. They must not become Android runtime dependencies.
