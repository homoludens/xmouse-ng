## 1. Native foundation

- [ ] 1.1 [low] Add the approved semantic light/dark native theme, reusable local-vector/control styles, system-following Compose editor colors, and visible XMouse-NG branding without changing package identity or storage paths; verify resource/theme checks and debug/lint/instrumentation APK builds.
- [ ] 1.2 [high] Replace drawer/FAB primary navigation with the real computer/status header and Touchpad/Keys/Actions shell, explicitly scoping pointer/key/typing capabilities across destinations, overlays, Back, lifecycle, and recreation while retaining one service session; verify navigation-only dispatch, held-input release, stale-gesture rejection, and session-reuse regressions.

## 2. Functional destinations and editors

- [ ] 2.1 [low] Implement the large native Touchpad, scroll strip, labeled mouse/quick-key controls, real IME typing with explicit Send and Esc/Tab/Backspace/Enter, and retained gyro/joystick/clipboard access; verify gestures, literal Unicode/quote escaping, no edit-time text dispatch, alternate modes, and reachable inset-aware layouts.
- [ ] 2.2 [low] Populate Common/Navigate/Media/F1-F12 key categories with existing command meanings and relative volume/brightness controls without fake levels; verify representative backend mappings, category selection, and native accessibility/touch targets.
- [ ] 2.3 [high] Build real Computers selection/discovery and persistent-label forms with confirmed deletion, saved port/global auth, blank-password reference preservation, username validation, and a bounded cancellable unsaved-candidate SSH test isolated from active control sessions; verify no unintended saves/commands, trust-first behavior, secret preservation, stale-result rejection, and actual result classification.
- [ ] 2.4 [low] Build Actions from real named profiles and scripts, with two-column tiles, Edit/Done, simple shortcut/advanced-command editors, 5/10 Regular/Wide and preset-color conveniences, separate script execution, and accessible profile/reorder/history management; verify stable IDs, exact legacy widths/colors/commands/order, persistence across reload, and zero editor execution without seeding demo data.
- [ ] 2.5 [low] Bind the redesigned Settings surface to real persisted pointer/scroll preferences and existing gyro/joystick/authentication/keyboard options, omitting unsupported preview-only controls; verify restart persistence, input behavior, Back, both themes, and accessibility labels.

## 3. Integrated verification and distribution

- [ ] 3.1 [low] Run focused preservation/dispatch/lifecycle regressions plus the final full JVM suite, lint, debug build, and instrumentation APK build; exercise available device/emulator layouts at approximately 320dp, larger fonts, both themes, IME, and Back, and map every acceptance scenario with honest unrun SSH/device/migration checks and measurable size/dependency impact.
- [ ] 3.2 [low] Configure local-only external release signing, update coherent release version/branding documentation, build the installable release APK, and verify signature fingerprint, checksum, stable application ID/API floor, absence of debug/reference assets, and preservation guidance; block publication rather than substitute a key if signing is unavailable.
- [ ] 3.3 [low] Commit the completed redesign and verification records, preserve the original upstream remote, push source without force to the authorized homoludens/xmouse-ng repository using gh-backed GitHub authentication, and create a GitHub release containing the verified APK/checksum and truthful notes; verify remote commit and downloadable artifact metadata.

Routing: 2 high / 8 low tasks (20%). The high tasks cover the shell/input-lifetime extraction and the attempt-only SSH/credential-edit lifecycle; ordinary resource, UI, API integration, defined testing, and release procedures remain low. Do not close unrelated modernize-xmouse tasks or claim unrun runtime checks.
