# XMouse redesign handoff

Approved UI reference and implementation instructions for the existing Android app that controls Linux over SSH on a local network.

## Give this to your coding agent

1. Extract this folder into your app repository under docs/.
2. Tell your agent:

   > Read docs/xmouse-design-handoff/IMPLEMENTATION_PROMPT.md, inspect DESIGN_SPEC.md, and open design-reference.html. Implement the approved design in the existing Android app. Keep it small and reuse the existing SSH and input code.

The agent needs your app repository. These files contain the design, not the Android application source.

## Files

- IMPLEMENTATION_PROMPT.md — the task, constraints, workflow, and acceptance checks.
- DESIGN_SPEC.md — colors, spacing, screen behavior, compatibility rules, and demo limitations.
- design-reference.html — interactive, self-contained reference; open locally in a browser.
- THIRD_PARTY_NOTICES.txt — licenses for libraries bundled only into the browser reference.

## Reference limitations

Try Touchpad, Keys, Actions, the computer name, Settings, typing, and Add action.

The reference simulates interactions. It does not connect over SSH, scan your network, store credentials, run commands, or read actual volume/brightness. Computer names, action contents, levels, and time are examples.

Do not reproduce the browser wrapper, drawn device frame, fake system bars, or “Preview” messages in Android. Use the project's existing native UI stack and real app state.

The HTML and its libraries are design materials. They must not become Android runtime dependencies.
