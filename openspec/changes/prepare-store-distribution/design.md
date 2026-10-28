## Context

See `proposal.md` for motivation. The app currently has application ID/namespace `net.droopia.xmouse_ng`, version 2.1.1 / code 27, minSdk 26 and target/compile SDK 37. `app/build.gradle` reads external `RELEASE_*` Gradle properties and explicitly gates release build, APK, and AAB packaging; that fail-closed behavior is an existing maintainer safeguard. About is a native `PreferenceFragmentCompat`. The repo already contains synthetic Touchpad/Actions captures, source-audited backup rules, protected credential storage, and a public project GitHub remote, but no store metadata, public policy hosting configuration, or distribution CI.

## Goals / Non-Goals

**Goals:**
- Preserve the established Android package and the existing maintainer certificate while separating source-reproducible unsigned APKs from signed maintainer artifacts.
- Make the Android release source identify the next prepared version, 2.1.2 / code 28, without altering or reinterpreting v2.1.1 history.
- Deliver reviewable, validated listing content, source artwork, and accurate policy/submission material.
- Prove only local artifact properties that were actually tested; keep store and owner decisions explicit.

**Non-Goals:**
- Export, upload, create, rotate, or disclose signing credentials; enroll in Play App Signing; submit a Play or F-Droid application; publish or tag a release; configure remote hosting, CI, or publishing automation.
- Change runtime networking, permissions, storage formats, package identity, SDK floors, runtime dependencies, or add analytics/GMS/browser policy display.
- Claim official F-Droid build-server reproducibility, F-Droid inclusion, Play acceptance, a live policy URL, or user/device/runtime acceptance from local builds.

## Decisions

1. **Add a narrowly scoped unsigned Gradle opt-in instead of a store flavor or signing fallback.** Parse only the exact `fdroidUnsigned=true|false` values and reject every other supplied value. In the true mode, branch before materializing any `RELEASE_*` property, leave release signing unset, put outputs below an explicitly unsigned-only build directory, and reject AAB packaging. The ordinary mode keeps its current external-property and external-keystore checks for release APK and AAB packaging. This avoids package/signature variants and prevents unsigned output from looking like a Play artifact. A separate flavor was rejected because it would create unnecessary store-specific variants and risk package/content drift.

2. **Use source-built APK content plus signature copying for the F-Droid path.** The draft recipe points at the real `app` Gradle project and explicitly opts into the unsigned build. Its future `Binaries` URL is a naming convention, while the exact 2.1.2 source commit remains an unmistakable owner-fill placeholder until that commit and release exist. Record the existing signer by public SHA-256 and configure `AllowedAPKSigningKeys`; neither the metadata nor the build recipe receives a key. Local evidence must use two independent clean source/build directories and compare the copied signature against the exact signed APK. Repeated builds sharing an output directory/cache and official build-server checks are not substitutes.

3. **Keep listing data conventional but dependency-light.** Store the one English listing under `fastlane/metadata/android/en-US` without adding Fastlane or upload tooling. A small Pillow script uses the supplied, preserved `docs/xmouse_ng_icon.png` artwork to generate the 512×512 store icon, a 1024×500 neutral feature graphic, density-specific square and round launcher icons, and API-26 adaptive resources; it uses Lanczos downsampling followed by lossless high-compression PNG output with no ancillary metadata or color quantization. The adaptive foreground is a 108dp canvas with the complete source artwork placed on white and its non-white control disk/arrows within the 72dp circular safe zone; API-26 round-icon XML and pre-26 circular PNG fallbacks are both provided. The original 1254×1254 source is provenance only and is never copied into the APK. The script also copies the existing synthetic phone captures into store-valid 2:1 dimensions by adding neutral side margins without cropping or resampling their pixels. A validator enforces text counts, changelog/version coherence, image dimensions/type/metadata, resource references and APK exclusion of the full-resolution source, and project/policy link coherence.

4. **Keep one policy body and render it as native text.** Maintain identical plain-text policy content in the Android raw resource and `docs/privacy-policy.md`, verified by a focused parity test. The in-app entry opens a non-exported native screen with a scrollable, selectable `TextView`; there is no WebView or network fetch. Wording is grounded in the current SSH/clipboard, discovery, secret-store, logging, and backup implementations. The GitHub source document is not asserted to be a live public privacy URL until its hosting and exact HTTPS URL are owner-verified.

5. **Separate local preparation from platform declarations.** Keep Play and F-Droid steps in owner-facing docs, not Gradle/CI automation. Explain that the existing maintainer certificate and the Play upload/app-signing concepts are distinct, and that Play compatibility requires the owner to confirm the existing certificate during enrollment. Mark Data safety, audience/content rating, foreground-service connected-device declarations, reviewer instructions, account eligibility, and any tester rule for the actual account as owner review items.

## Risks / Trade-offs

- [A Gradle condition could accidentally read secrets or weaken ordinary release checks] → Evaluate the opt-in first, do not materialize signing providers in unsigned mode, keep ordinary packaging dependencies, reject bundles in unsigned mode, and exercise positive and negative paths.
- [AGP/R8/baseline-profile/native-library inputs may prevent byte-for-byte reproduction] → Compare actual APKs from isolated clean locations, investigate concrete ZIP/DEX/native differences, and report a blocker rather than disabling shrinking or changing the toolchain to force equality.
- [Android launcher masks may crop the supplied artwork, or lossless compression may not materially reduce each file] → Keep the full disk and arrows within the adaptive safe zone, inspect square/circular/mask previews, preserve source colors, validate exact dimensions and PNG metadata, and report generated byte sizes rather than making an unmeasured optimization claim.
- [Duplicated policy text can drift] → Require exact resource/document parity in tests and rerun source audits before the verification record is written.
- [Local tooling does not establish external acceptance or hosting] → State those limits beside the checklists and leave all enrollment, publication, and submission steps unchecked.

## Migration Plan

This is source preparation only. Preserve the existing v2.1.1 tag, assets, and historical reports; set the working source to 2.1.2 / code 28 and record its current source checkpoint for local evidence. Do not create a commit, tag, release, upload, submission, hosting change, or store listing. Before any later owner-led publication, review the working diff, commit the finalized source, create the corresponding release/tag and correctly signed APK, host and verify the policy URL, verify signer continuity in Play, then perform store-specific owner declarations and submission. If preparation is rejected, the owner may revise the uncommitted source while retaining v2.1.1 artifacts untouched.

## Open Questions

- The owner must choose and verify the final public HTTPS privacy-policy URL when hosting is separately approved; no local source-only URL is declared live.
- F-Droid's current review/build-server tooling availability and official reproducibility result depend on the future tagged source and external review; local draft validation is not that review.
