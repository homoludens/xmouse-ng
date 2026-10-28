# Maintainer checklist for future store submissions

This is preparation guidance, not a store submission. The v2.1.2 source and GitHub release do not mean that XMouse-NG is available in Google Play or F-Droid, that either service has accepted it, or that an external reproducible-build service has verified it. The privacy policy is included in the source and in the app, but it has **not** been published at a public HTTPS policy URL.

## Before any store account or upload action

- [ ] Review the exact source tag, signed artifacts, checksums, permissions, privacy-policy wording, listing text, screenshots, icon, and release notes.
- [ ] Preserve the existing XMouse-NG package identity `net.droopia.xmouse_ng` and signing certificate SHA-256 `c1c5faca7bfd24ce2bf5467a731b71b0218bcb503eb20f814d370925a4b88eed`. Do not rotate it or expose a private key, keystore, or password in source control, a ticket, or an agent session.
- [ ] Independently decide and verify the signing setup required by each service. A local AAB signed by the existing maintainer key is only a prepared artifact; it is not uploaded to Google Play and does not enroll the package in Play App Signing. Resolve certificate continuity and Google's app-signing/upload-key requirements before any enrollment or upload. No signing key was exported or uploaded for this preparation.
- [ ] Publish and HTTPS-verify the privacy policy at a stable public URL before entering that URL in a store console. Keep the in-app and hosted material materially identical and update both together if the policy changes.
- [ ] Make all required legal, account-owner, target-audience, content-rating, export/privacy, data-safety, and store-specific declarations from current verified facts. Nothing here is a prefilled legal or policy attestation.

## Google Play (future owner action; not submitted)

- [ ] Confirm developer-account eligibility, identity/organization information, current testing-track rules, and any account-specific requirements in Play Console.
- [ ] Confirm whether the existing certificate can meet the desired Play signing continuity without exporting or uploading a key. Do not proceed until the owner has resolved this explicitly.
- [ ] Review the prepared AAB's package/version/SDK identity, signature, checksum, native libraries, and release notes. Select the AAB only for a future Play upload; it is not a Play-published app.
- [ ] Enter the verified public privacy-policy URL. Complete Data safety, target audience, content rating, ads, app access, permissions, foreground-service/connected-device, and other declarations using the final app and current Play requirements.
- [ ] Provide reviewer instructions that work without exposing a personal SSH account or real credentials. If access to a Linux host is needed, arrange an isolated, disposable review environment and safe instructions; do not place secrets in listing text or source.
- [ ] Review the Play-generated APKs/signing certificate after any owner-approved upload and confirm they preserve the intended package update path before production rollout.
- [ ] Upload, submit for review, approve rollout, and publish only after the owner completes the preceding review. None of these Play actions has occurred here.

## F-Droid (future maintainer/reviewer action; not submitted)

- [ ] Review `metadata/net.droopia.xmouse_ng.yml`, its exact full source commit, Gradle subdirectory/output, `fdroidUnsigned=true` opt-in, version/code, and published versioned `Binaries` URL. The source commit and APK are now identified; keep the draft gate until official `fdroid lint`, build-server/review checks, and explicit owner approval pass.
- [ ] Confirm the unsigned build remains APK-only and isolated from signed outputs. Do not pass release signing properties or key material into the source build.
- [ ] For the exact released source commit, compare an independent clean F-Droid build with the signed upstream APK by signature copying and verify the result with `apksigner`. The local source-only checks in the accompanying record are not an official F-Droid build-server result or review.
- [ ] Confirm the F-Droid verifier's accepted signing-key fingerprint equals the existing public SHA-256 certificate fingerprint above. A mismatch is a stop condition; do not change or replace the project signing key to make a match.
- [ ] Review current F-Droid policies, licensing/dependency findings, metadata, screenshots, privacy URL, source availability, and reviewer/build-server requirements.
- [ ] Submit a metadata change or application request only through the maintainer's intended F-Droid process. No F-Droid MR, application request, review, or publication has occurred here.

## Owner checklist status

Every unchecked item is a maintainer action for a later, separately approved submission. Local builds and the GitHub release do not check these boxes or establish account eligibility, service acceptance, reviewer access, public policy hosting, or store publication.
