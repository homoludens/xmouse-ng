## Purpose

Prepare verifiable Android release artifacts and truthful, reusable store material while leaving all store enrollment, review, release, and publication decisions with the maintainer.

## ADDED Requirements

### Requirement: Preserve package identity and gate signed release artifacts
Release APKs and Play app bundles SHALL retain the existing `net.droopia.xmouse_ng` application identity and prepared version 2.1.2 / version code 28. Ordinary release packaging SHALL require the complete existing external maintainer signing configuration and SHALL NOT fall back to an unsigned or debug-signed artifact. A distinct, explicitly enabled F-Droid reproduction path SHALL produce only unsigned APK output, SHALL not read or use maintainer signing values or key material, and SHALL keep its outputs separate from ordinary signed artifacts. Invalid opt-in values SHALL fail closed. An unsigned output SHALL never be presented as a publishable maintainer artifact.

#### Scenario: Ordinary release APK or bundle requires the maintainer signer
- **WHEN** a maintainer requests release APK or app-bundle packaging without all valid external signing values and an external keystore
- **THEN** packaging fails at the explicit signing guard without producing a publishable APK or AAB

#### Scenario: Explicit F-Droid source reproduction is isolated and unsigned
- **WHEN** the maintainer explicitly enables the supported unsigned-reproduction option for an APK build
- **THEN** Gradle does not read or use release signing values or key material, emits the unsigned APK only in its isolated output location, and rejects malformed option values

#### Scenario: F-Droid mode cannot create an ambiguous app bundle
- **WHEN** an app-bundle task is requested with unsigned-reproduction mode enabled
- **THEN** the task fails rather than creating an AAB that could be mistaken for a signed Play artifact

#### Scenario: Signed Play bundle retains the existing package identity
- **WHEN** a locally authorized Play AAB is built with the existing external signing configuration
- **THEN** it contains the same application ID, version, SDK bounds, and maintainer signing certificate as the signed APK, without introducing a store-specific package or flavor

### Requirement: Prepare reproducible F-Droid source metadata and signature-copy evidence
The distribution preparation SHALL include an accurately structured, clearly gated draft F-Droid recipe for the real Android Gradle subproject and version conventions. The recipe SHALL identify the existing developer certificate using its verified SHA-256 fingerprint and document the future upstream binary pattern for signature copying. A reproduction claim SHALL require two independent clean builds from the same recorded source checkpoint and successful comparison of the unsigned APKs; signature-copy verification SHALL compare the reproduced unsigned bytes with the exact developer-signed APK. A source-only or repeated in-place build SHALL NOT be described as official F-Droid build-server verification or as a completed F-Droid review.

#### Scenario: Draft recipe cannot imply a not-yet-existing release
- **WHEN** the prepared 2.1.2 source has not yet been committed/tagged and no matching upstream signed APK has been published
- **THEN** the recipe is visibly gated with an unresolved exact source revision and future binary URL pattern, and does not claim that a tag, APK, F-Droid inclusion, or review exists

#### Scenario: Reproduction evidence is based on independent clean locations
- **WHEN** local F-Droid reproduction is reported as successful
- **THEN** both outputs were built from the same source checkpoint in separate clean build locations without sharing Gradle output/cache state, and their unsigned APK bytes compare equal

#### Scenario: Signature-copy check binds the exact source build and developer certificate
- **WHEN** local signature-copy verification is reported as successful
- **THEN** the signed APK is the exact developer-signed APK for that source version, its certificate matches the recorded existing signer, and verified unsigned content matches the independently reproduced APK

### Requirement: Supply shared, validated store listing material and owner-reviewed submission guidance
The project SHALL provide one shared English listing description, a valid title and short description, a version-matched changelog, and provenance-documented store artwork/screenshots based only on the existing brand and synthetic captures. Automated validation SHALL enforce the platform text-length and image-dimension/type constraints and coherent links/version. Submission instructions SHALL distinguish local preparation from account enrollment, signing-key enrollment, policy declarations, review, upload, release, and publication; SHALL preserve the existing signing certificate; and SHALL not assert platform acceptance, account eligibility, hosted policy availability, or reviewer tests that were not performed. No store availability badge or automatic publishing step SHALL be added.

#### Scenario: Listing content and artwork pass local metadata checks
- **WHEN** the listing metadata validator runs
- **THEN** it checks title and description limits, changelog length and prepared version, image dimensions and format, and coherent project/privacy URLs without claiming the app is live in a store

#### Scenario: Store submission remains a maintainer-controlled future action
- **WHEN** an owner follows the submission guide
- **THEN** unresolved enrollment, signer continuity, public policy hosting, Data safety, foreground-service declaration, reviewer access, and store-specific review requirements are explicit owner checks rather than pre-filled factual attestations

### Requirement: Derive optimized launcher and listing icons from the supplied artwork
Launcher resources and store artwork SHALL be generated from the unchanged `docs/xmouse_ng_icon.png` source, not the previous launcher mark. The app SHALL contain density-appropriate conventional icons and API-26+ adaptive/round icon resources whose foreground keeps the complete circular pad and directional arrows inside the mask-safe area. The generated store icon SHALL be 512×512 PNG; any prepared feature graphic SHALL be 1024×500 and use the same artwork without adding store-availability claims. Generated PNGs SHALL be losslessly optimized after high-quality downsampling and contain no unnecessary source metadata. The full-resolution source artwork SHALL remain outside the APK.

#### Scenario: Adaptive and round launchers retain the complete supplied design
- **WHEN** Android resolves the application icon on supported launchers and adaptive masks
- **THEN** the user artwork remains legible with all four arrows and its center control visible, while the density-specific and adaptive/round resources are correctly referenced

#### Scenario: Generated listing artwork is compact and excludes the source file
- **WHEN** the store-art generation and APK resource audit run
- **THEN** the 512×512 icon and 1024×500 feature graphic derive from the preserved source, output PNG metadata is stripped, per-density sizes and byte counts are recorded, and the 1254×1254 source file is absent from the APK
