# Task 2.5 — native saved Settings

Scope: `redesign-xmouse-ng` **2.5 [low]**, 2026-10-25. Implementation and verification use the recorded user-approved `openai/gpt-6.1-sol#xhigh` routing exception; the complexity tag is unchanged.

## Settings and preservation contracts

- The header and overflow Settings routes now open the non-exported `NativeSettingsActivity`, a full-height native View/XML form. Back closes it. A system-following semantic palette, wrapping labels, 48dp minimum targets, scrollable content, and `SystemWindowInsets.applyToAndConsume` keep the form usable at narrow widths/enlarged fonts and around system bars/IME. No WebView, framework conversion, dependency, package/API identity, or storage change was introduced.
- **Pointer speed** selects from the existing `pref_sensitivity_list_*` resources, storing exactly `.25f`, `.3333f`, `.5f`, `1.0f`, `1.5f`, `2.0f`, or `3.0f` under **`sensitivity_list`**. It is a real touchpad multiplier, not a desktop percentage. The original runtime default is **1.0f**. Valid custom saved scalars remain exact and are disclosed as Custom instead of being quantized/clamped/replaced merely by opening the form. Invalid/non-finite/nonpositive/wrong-typed saved values fall back in memory only; no automatic repair writes occur. Deliberate new choices are restricted to the real configured list.
- **Natural scrolling** is **`!mouse_invert_scroll`**, not the raw boolean. The unchanged `MyMouseView` subtracts the preceding finger position, then maps a negative delta with `mouse_invert_scroll=false` to wheel-down. Thus On means swipe up → wheel-down/content follows fingers; Off means swipe up → wheel-up. The form explains this explicitly. Labeled up/down buttons retain their literal directions.
- **Clear text after Send** uses **`keyboard_autoclear`**, default true. Main reloads it into the existing explicit-Send typing path. Editing text never dispatches, regardless of the retained **`setting_keyboard_batch`** value. Advanced replaces the obsolete Instant Messenger checkbox with accurate noninteractive explanatory text; it does **not** erase or write the old preference.
- **Advanced settings** opens the existing `SettingsActivity` directly, without recursion. It retains global password/key authentication selection, the private-key document import controller, protected optional passphrase editing, connect-on-start choice, shell/input tool/initial command, drag/multidrag delays, gyro sensitivities/thresholds, joystick sensitivity/size/dead zone, and auto-clear. The computer editor's Settings link opens the same primary form and explains the global scope; no per-host authentication choice was invented. Advanced Up finishes back to its caller rather than navigating around the primary form. Existing credential/import instrumentation still compiles unchanged.
- Gyro remains in Touchpad and Joystick remains in the top More menu. The hidden legacy main-grid lock checkbox is replaced with an explanation pointing to Actions → More → Advanced editor: that editor is not controlled by the old grid flag. **`keyboard_layout_locked` remains stored and backed up**, not reset. The remaining legacy code/storage paths are unchanged.
- Unsupported configurable **Tap to click** and **Haptic feedback** are deliberately omitted. Existing always-available tap/click and gesture behavior is retained, not replaced with a new algorithm. No demo 55%, inert preview toggles, or seeded values are shown.

## Integration APIs and input lifetime

- `InputPreferences.read(Map<String, ?>)` is a pure immutable snapshot (`pointerSpeed`, `invertScroll`, `keyboardAutoClear`) with safe independent fallback. `naturalScrolling`, `invertedForNaturalScrolling`, `pointerChoiceIndex`, and `pointerChoiceForWrite` capture the compatibility/display/write rules without Android or dispatch access.
- `NativeSettingsBinding(root, SharedPreferences, back, advanced)` is shared by production and the isolated fixture. Rendering/reload never writes preferences; only deliberate changes persist. It has no session, command, trust, credential, or network dependency. `reload()` refreshes after Advanced; `dismissDialog()` closes transient selection when the Activity stops.
- Main continues to launch through **`openExternalScreen(...)`**, opening `ShellNavigation.Overlay.EXTERNAL` **before** `startActivity`. No same-Activity settings sheet was introduced. Main loads the three real input values independently of unrelated legacy parsing failures and before closing EXTERNAL/reacquiring eligibility. The existing pointer reads `MainActivity.setting_sensitivity` / `setting_invert_scroll`; the typing sender reads the reloaded auto-clear field. No gesture algorithm or session/controller ownership changed.
- A data-only **`shell.externalPending`** recreation flag retains the external-return policy. Returning from any external form, including Settings and its Advanced child, skips automatic foreground connect/recovery scheduling. It does not disconnect or replace the healthy service session. Fresh input capability is still controlled solely by the established role/resume/focus/readiness gate; old gestures are retired, never borrowed.

## Focused verification

The final focused command uses both `ANDROID_HOME` and `ANDROID_SDK_ROOT` set to `/home/homoludens/Android/Sdk`:

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.stripe1.xmouse.InputPreferencesTest' \
  --tests 'com.stripe1.xmouse.ShellNavigationTest' \
  --tests 'com.stripe1.xmouse.XMouseNgThemeResourcesTest' \
  --tests '*ControlInputTest' --tests '*ShellTextEncoderTest' \
  --tests '*DoToolTransportTest' --tests '*YdoToolTest' \
  --tests '*SessionPipelineIntegrationTest.shellTabsAndOverlaysRetireHeldInputWithoutDispatchOrHealthySessionReconnect' \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

- **42 JVM tests across 8 classes**, zero failures/errors/skips: new `InputPreferencesTest` 7; retained `ShellNavigationTest` 11, theme/resource checks 3, `ControlInputTest` 4, encoder 1, command adapters 15, and the single recording pipeline navigation/overlay/session-preservation case. Coverage includes original defaults, every legacy float string, non-list values, invalid independent fallback without mutation, configured write range, reload/string compatibility, scroll inversion, auto-clear, retained inert preference values, global-auth routes, and reload-before-gate-release/return scheduling source contracts. This is **not the full JVM suite**.
- `NativeSettingsAndroidTest` adds two **compiled-only** isolated production-binding/pointer fixtures: both themes, 320dp/1.5x fonts, actual selected/checked labels and persisted new choices, nonmutating reload, Back/Advanced callbacks, synthetic bars/IME insets, scrolling/48dp targets, and actual `MyMouseView` multiplier/direction calls into an inert recording `IDoTool`. Its preferences use a uniquely named fixture file only; Main and credential/settings repository lifecycles are never started. Existing `SettingsActivity`/credential/import tests remain compiled, not executed.
- Final debug APK and instrumentation APK build passed, as did lint (**0 errors/fatal issues, 213 warnings**), strict OpenSpec validation, and `git diff --check`. New text files passed a trailing-whitespace scan. Results are also recorded in `openspec/changes/redesign-xmouse-ng/tasks.md`; only 2.5 is newly checked (**7/10 complete**).

**NOT RUN:** instrumentation/device/emulator runtime, actual Android Back/IME/TalkBack/font/theme interaction, process-restart UI persistence, live Linux SSH/input, physical-device upgrade/migration, any real-user preferences/database/vault/clipboard test, or the full JVM suite. No ADB access, delegation, staging, commit, push, archive, release signing, or signing-key access occurred. Tasks 3.x and all unrelated `modernize-xmouse` pending criteria remain unchanged; HEAD remains `b421e53` on `redesign/xmouse-ng`.
