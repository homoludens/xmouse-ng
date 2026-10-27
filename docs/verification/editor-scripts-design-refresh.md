# Native layout editor and Scripts design refresh verification

Date: 2026-10-27

## Scope

This package refreshes the existing native layout editor while retaining the app's current
Touchpad/Actions/Scripts shell. The editor has compact Visual/XML mode tabs, contextual selected
button actions, persistent mode-specific footers, help, and confirmation for visual discard and
button deletion. The Back controls in the editor, Native settings, and layout manager use arrow
icons. Editor typography is locally compact; global font scaling is unchanged.

The existing layout model, XML schema/parser, recoverable-draft behavior, button history, and
profile persistence remain the source of truth. Editor previews and actions must not truncate,
replace, or reseed saved layouts. XML **Apply & return** only updates the recoverable draft and
returns to Visual; profile persistence remains the Visual **Save changes** action.

The Scripts refresh retains the three existing tabs and shared composer behavior while updating the
Scripts destination, native manager, rows, edit form, and confirmations. Script data continues to
use the existing SQLite rows and serialized, generation-guarded worker. Add, Edit, Manage, and
Delete do not dispatch commands; the labeled Run action remains the explicit execution path with
the existing readiness and dispatch checks. Commands remain raw text and are not exposed in script
rows or saved in form view state.

## Build and JVM verification

Final gate command:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Package 1 editor checkpoint: **BUILD SUCCESSFUL** (2m 10s; 85 actionable tasks, 26 executed and
59 up-to-date). Its JVM JUnit XML reports contained **515 tests, 0 failures, 0 errors, and 0
skipped**. Lint reported **265 warnings and 0 errors**; both APKs assembled and `git diff --check`
passed.

Final full gate after runtime-driven test fixes: **BUILD SUCCESSFUL** (3m 42s; 85 actionable tasks,
8 executed and 77 up-to-date). JVM JUnit XML reports contain **515 tests, 0 failures, 0 errors, and
0 skipped**. Lint reports **263 warnings and 0 errors**. The debug app APK and Android-test APK
assembled, including the native capture tests. The final `git diff --check` passed.

Scripts regression coverage adds recording-transport assertions for cancelled/validated/successful
CRUD, stable-ID and raw-command persistence, no command dispatch during CRUD, and explicit Run
dispatch. Native UI tests cover labeled controls, script-name accessibility, row geometry, and
320dp / 1.5-font-scale layouts.

## Runtime verification

- Runtime target: the owned API 35 emulator `emulator-5598`, connected only through the isolated
  ADB server on port **5049**. No physical device or ADB port 5037 was used.
- The final combined focused instrumentation selection passed **17 tests, 0 failures, 0 errors,
  and 0 skips**. It included the editor-design suite (4), XML opt-in capture (1), native action,
  pointer, accessibility and profile-manager tests (4), Scripts design (1), the selected native
  spacing/settings/shell tests (3), the three requested session/service tests (3), and the editor /
  Scripts opt-in capture test (1).
- A separate non-capture run of the complete XML test class reported **4 tests: 3 passed, 0 failed,
  and 1 skipped**. The skipped case is the opt-in screenshot helper, intentionally disabled in that
  invocation; it passed in the combined capture-enabled run. Across the final focused runs, this is
  **20 passed, 0 failed, and 1 intentional skip**.
- The three required service/session methods passed together: saved-script Run uses the recording
  service sink, layout-editor editing never dispatches, and shell tabs/overlays preserve one session
  while rejecting stale capabilities. The native picker test scrolls its checked row back into view
  before selecting the first built-in preset; the saved-script test waits for foreground window
  focus before tapping Run.
- Captures were reviewed and saved under
  [`evidence/editor-scripts-design-refresh-20261027/`](evidence/editor-scripts-design-refresh-20261027/).
  Representative evidence: [57-button selected editor, light](evidence/editor-scripts-design-refresh-20261027/scripts/editor-visual-57-selected-light.png),
  [Scripts manager, narrow/large-font light](evidence/editor-scripts-design-refresh-20261027/scripts/scripts-manager-320-font150-light.png),
  [XML validation with keyboard and footer, light](evidence/editor-scripts-design-refresh-20261027/xml-editor-320dp-font150-validation-keyboard-footer-light.png),
  and [the same XML state, dark](evidence/editor-scripts-design-refresh-20261027/xml-editor-320dp-font150-validation-keyboard-footer-dark.png).
  The opt-in XML test asserts the IME is visible and the full footer is above it in both themes.

### Rerun record

- The initial editor/XML focused invocation ran **12 tests: 9 passed, 2 failed, and 1 opt-in
  capture skipped**. The failures exposed a last-profile cleanup bug in the fixture and stale Compose
  semantics/footer assumptions. Those were corrected; the final combined selection is green.
- A Scripts sizing run initially had **2 failures in 4 tests**: one assertion used linear
  `scaledDensity` despite Android 15 nonlinear font scaling, and one 66dp row ceiling was below its
  measured 67.4dp normal-font height. The test now uses `TypedValue.applyDimension` and a measured
  68dp ceiling; the final Scripts selection passes.
- The first session/service trio run reported **1 passed and 2 failed out of 3**: the picker row was
  outside the dialog's current scroll viewport, and Run was tapped before foreground focus was
  restored. The tests now scroll the picker and wait for focus; the trio passed **3/3** on rerun and
  in the final aggregate.
- The first post-runtime full Gradle gate had **2 JVM failures out of 515**, because a Mockito
  fixture did not expect the new script-form `TextInputLayout` lookups. The fixture now binds and
  verifies both wrappers; its focused class passed and the complete final gate passed **515/515**.
- The XML capture helper was rerun after adjusting its validation-message scroll target and using a
  multiline inert malformed source. Both light/dark keyboard/footer captures and the editor/Scripts
  capture helper passed in the final capture-enabled run.

## Reference and platform limits

- The HTML design reference was viewed in headless Firefox. Its interactive controls were not
  exercised. Reference review is not native-app runtime evidence.
- IME visibility/footer reachability is verified on the owned API 35 emulator. No claim is made here
  about TalkBack operation or behavior on a physical device.
