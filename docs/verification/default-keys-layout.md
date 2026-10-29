# Default Keys Layout verification

Baseline source checkpoint: `b84b0cff57ab8a290d150c347ccf0ada89f9d1e5`.

The original `MainActivity.loadDefaultKeyboardLayout` table was compared with the extracted catalog as ordered title/command/width/color tuples: **57 rows, exact match**. Fresh initialization selects the editable `Keys Layout`; upgrade checks cover the untouched automatic blank profile, named/renamed profiles, saved selection and drafts, reserved-ID collisions, edits, deliberate deletion, and restart idempotence. Legacy migration tests verify successful seeding preserves selection plus source/checkpoint bytes, while a migration warning blocks seeding.

## Checks

- Focused layout/initializer JVM tests: **14 passed**.
- Full `:app:testDebugUnitTest`: **560 passed**, 0 failures, 0 skipped.
- `:app:lintDebug`: passed, **0 errors** and 289 warnings.
- `:app:assembleDebug`: passed.
- `:app:compileDebugAndroidTestSources`: passed.
- `openspec validate include-default-keys-layout --strict`: passed.
- `git diff --check`: passed.
- Emulator/physical-device UI smoke: **NOT RUN**. An emulator was listed, but its ownership and existing app state were not verified; it was not installed to, reset, or otherwise used. No private app data was inspected.

This record captures feature verification before the separately authorized 2.1.3 release work. At this feature-verification checkpoint, no release build, signing operation, version change, commit, push, or store submission had been performed.
