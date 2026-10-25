# XMouse-NG — emulator spacing follow-up

Date: **2026-10-25**. Visual-only follow-up requested after release 2.1.0, starting from `f7b977a`. This record does not update or replace the published APK, release tag, or earlier acceptance results.

## Compared and corrected

Compared the approved `.xm-*` geometry in `docs/xmouse-design-handoff/design-reference.html` and its screenshots against an isolated API 35 emulator, first at default text scale rather than only the earlier large-font configuration.

| Area | Correction |
| --- | --- |
| Page gutters | Shared **18dp** margins at widths of 360dp or more; **14dp** on narrow screens. |
| Header | Normal-scale height reduced from **112dp to 76dp**; status and icons share a row. Narrow/enlarged-text layouts retain stacked controls and a readable, unbroken Disconnected label. |
| Heading rhythm | Shared 51dp minimum heading row and 12dp separation from content. |
| Mouse buttons | **6dp gutters**, 9dp pad-to-click gap, 53dp minimum click height, 48dp middle-click target, correctly sized/centered icons. |
| Quick keys / typing | Reference-like 0.75/1.25/1 row proportions, 7dp column gaps, **14dp vertical gaps**, proper keyboard/window icons and typing chevron. |
| Controls | Regular-weight labels, no unintended elevated shadows, borderless soft controls, compact outlined mode pills, and a consistent touchpad outline. |
| Bottom navigation | **58×31dp selected pill**, 20dp icons, 11sp labels, flat semantic surface without the dark Material elevation overlay. |
| Common keys | **Three columns** at normal scale on wider screens; two-column fallback for narrow/enlarged text. 8dp gaps, 58dp minimum key height, section rhythm, neutral surfaces and local line icons. Missing final cells no longer stretch remaining buttons. |
| Actions | 10dp grid gaps, 15dp tile padding, 104dp minimum height, equal-height neighbors even when titles wrap, compact layout row and script heading, dashed Add action, single-row Run/Edit script presentation. |
| Forms / Settings | Consistent page margins, lighter secondary typography and less prominent secondary actions. |

All effective controls retain at least **48dp** targets. Stored widths/colors/raw commands, key mappings, session/input ownership, credentials, preferences, package identity and version are unchanged. The shorter visible Switch label retains its full Alt+Tab accessibility description and existing command. Test-only sample actions were created through the UI on the owned emulator; they are not packaged defaults.

## Final checks

- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:lintDebug`: **passed**. Lint: **0 errors, 217 warnings**.
- Focused JVM verification: **30 tests / 5 classes, zero failures/errors/skips** — `XMouseNgThemeResourcesTest` 3, `KeyCatalogTest` 3, `ShellNavigationTest` 11, `InputPreferencesTest` 7, `ActionPresentationTest` 6. The full JVM suite was **not rerun** for this visual follow-up.
- **22 instrumentation tests passed** on the final build: `NativeSpacingAndroidTest` 3, `NativeShellAndroidTest` 9, `NativeSettingsAndroidTest` 2, mouse/insets/joystick 3, `NativeActionEditorAndroidTest` 1, and four selected recording-only service tests for navigation/session reuse, existing control/script dispatch, Settings during drag, and scroll-parent gesture ownership.
- The debug fixture now applies font/width overrides before resources are consumed. It genuinely tests both 1.0 and 1.5 scales, rather than relying on the emulator already using the requested font size.
- Actual production-screen review at **374×764dp / 1.0**, **390×844dp / 1.0**, and **320×720dp / 1.5**, in both themes. Real IME editing, scroll-reachable full Send target, first Back hiding the IME and next Back closing typing were exercised without pressing Send or connecting to a host.
- `git diff --check` passed. No dependency or signing configuration change, release build, publication, phone access, real-user data operation, or live SSH command was performed.

The emulator was uniquely owned (`emulator-5584`) with its AVD under `/tmp/opencode/xmouse-ng-spacing-ijcjxmpd/avd`. All device commands explicitly targeted that serial. Its test data and recording transports are synthetic. The emulator was stopped and only its disposable AVD directory removed after verification; screenshots and logs remain available.

## Retained visual evidence

Local evidence root: **`/tmp/opencode/xmouse-ng-spacing-ijcjxmpd/`**.

- `before-after-{touchpad,keys,actions,settings}.png`: paired 390dp/default-text views.
- `reference-vs-native-touchpad.png`: reference and native screen at matching 374px/374dp width.
- `final/`: **22 final screenshots** and UI hierarchies, including both themes, larger text, saved synthetic actions and real IME.
- `final-verification-build.log`, `instrumentation-final.log`, `visual-smoke-final.json`, `verification-summary.json`.

This is a visual comparison with measured native geometry, **not** a pixel-golden equivalence claim. Android's real bars/IME, the extra retained controls, real disconnected/user-data state, flexible pad height and minimum 48dp targets intentionally differ from the browser's simulated phone. Physical-device/live-Linux/real-data migration checks remain outside this visual pass.
