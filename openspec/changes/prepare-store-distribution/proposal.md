## Why

XMouse-NG is already distributed through GitHub, but official F-Droid and Google Play preparation needs a trustworthy unsigned reproduction path, shared listing assets, an accessible privacy policy, and owner-reviewed submission guidance. These materials must preserve the existing app identity and signing continuity without implying that either store has accepted or published the app.

## What Changes

- Add a strict, explicit F-Droid unsigned release-reproduction option while keeping ordinary release APK/AAB builds gated by the existing external maintainer signing configuration.
- Prepare the next source version as 2.1.2 / version code 28 without creating a release or tag; provide a draft F-Droid recipe and reproducibility/signature-copy instructions.
- Add shared English store metadata and generated listing artwork/screenshots, with validation and provenance.
- Add one accurate privacy policy available as readable native offline content and as a source-hostable web document; record that public hosting is not yet published.
- Add concise, owner-reviewed F-Droid/Google Play submission checklists, including signing continuity and policy declarations that must not be inferred locally.

## Capabilities

### New Capabilities
- `store-distribution-preparation`: Prepare signed Play artifacts and reproducible F-Droid source builds, shared store listing material, and gated submission instructions without publishing.
- `in-app-privacy-policy`: Make the current privacy practices available in a selectable, scrollable native screen that works offline.

### Modified Capabilities

None.

## Impact

- Affected Android release configuration/version and About UI/resources/tests; new distribution metadata, F-Droid draft metadata, artwork generation/validation, and owner-facing documentation.
- No runtime SDK/service addition, application ID or signature split, credential export, store submission, remote hosting configuration, release/tag, or publication.
