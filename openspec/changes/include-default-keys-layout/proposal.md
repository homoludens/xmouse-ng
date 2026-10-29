## Why

The app still contains its original 57-button keyboard/action table, but fresh Actions now opens an empty saved profile, leaving that built-in layout inaccessible in the normal native UI. Make the original table available immediately as a named, editable `Keys Layout` without overwriting saved layouts, drafts, or legacy data.

## What Changes

- Use one canonical stock-layout table for the legacy compatibility path and the saved `Keys Layout` profile.
- Seed `Keys Layout` as the selected profile on a clean install and make it available additively to eligible existing installations.
- Preserve the prior empty automatic `Actions` profile and all existing profile selections and content; persist initialization state so deleting or renaming `Keys Layout` is respected across restarts.
- Release the feature as XMouse-NG **2.1.3 / version code 29** on the existing GitHub fork using the existing signing certificate and checksummed APK/AAB assets.
- Keep Google Play and F-Droid service submissions, enrollment, review, and publication out of scope; the local F-Droid draft remains disabled.

## Capabilities

### New Capabilities

- `default-keys-layout`: Provide the original 57-button layout as a default saved profile with safe, non-destructive upgrade behavior.

### Modified Capabilities

None. The project has no main specs registered yet; this behavior is captured as a new capability.

## Impact

- Affected Android layout-profile initialization/catalog and `MainActivity`'s existing built-in table adapter.
- Adds JVM coverage, release documentation, and source/release verification. No dependency, permission, SSH, signing-key, package identity, or store-account changes; only GitHub release publication is authorized.
