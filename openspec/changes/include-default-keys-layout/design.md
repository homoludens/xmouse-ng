## Context

See `proposal.md` for motivation and `specs/default-keys-layout/spec.md` for the behavior contract. `MainActivity.loadDefaultKeyboardLayout` is the existing canonical compatibility source for the 57 stock buttons, while `FreshActionsProfileInitializer` currently creates an empty `Actions` profile. Profile data, drafts, selected ID, and migration checkpoints are kept separately; legacy CSV migration is gated before a saved profile becomes active.

## Goals / Non-Goals

**Goals:**
- Keep one production source for the original stock button values and adapt it to both legacy rendering and the saved profile model.
- Seed an editable selected stock profile on fresh/empty repositories, and add it without replacing user state on eligible upgrades.
- Persist initialization completion so a renamed or deleted default stays that way.
- Keep all repository access in the existing Actions I/O worker and preserve the legacy migration fail-closed boundary.
- Publish the feature as GitHub release 2.1.3 / code 29 from a reviewed source commit with signed, audited APK and locally signed AAB assets.

**Non-Goals:**
- Recovering data from another Android package's private sandbox, restoring legacy CSV through new UI, or adding a restore button.
- Rewriting, renaming, or deleting existing saved profiles, drafts, legacy files, migration checkpoints, hosts, or scripts.
- Changing application ID, signer, API levels, SSH behavior, or store-account submission/enrollment/review.

## Decisions

1. **Extract the stock rows to a pure Kotlin catalog and keep the Java compatibility API as an adapter.** Move each existing title, command, width, color, and order once into immutable `LayoutButton` values. `MainActivity.loadDefaultKeyboardLayout` maps those values back to `CustomKeyboardButton`, so legacy fallbacks and new profile seeding cannot drift. Tests compare the entire ordered catalog with the original literal values.

2. **Use a stable profile ID and additive create-only seeding.** When there is no legacy source and the repository has not completed this initialization, create `Keys Layout` only if that stable ID is absent. Never save stock values over an existing ID, even if its current name/buttons differ. Select it for an empty repository; on upgrade select it only for the lone original empty `Actions` profile with no pending draft. Otherwise leave the existing selection untouched while making the stock profile available separately.

3. **Persist a backward-compatible initialization bit in the existing atomic profile catalog.** Add an optional boolean field with a false default when reading catalogs written by older app versions. After finding an existing default or successfully handling a new one, mark initialization complete. Keep the catalog format version unchanged because the field is additive and old catalogs remain readable. This marker prevents a user-deleted profile from being re-seeded; an existing profile ID is also always treated as user-owned and never overwritten.

4. **Keep retained legacy data authoritative.** With no legacy source, seed before runtime activation so a clean repository has a selected profile. When sources exist, let the existing migrator run first; seed a separate profile only after runtime activation succeeds, preserve its selected migrated profile, and never touch source/checkpoint bytes. If migration warns or fails, do not seed and leave read-only recovery authoritative. All repository I/O remains in the Actions I/O worker.

5. **Publish from an immutable source commit.** Set `versionName` to `2.1.3` and `versionCode` to `29`, retain `net.droopia.xmouse_ng`, SDK 26/37/37, and the existing owner certificate. Commit and fast-forward only the `homoludens/xmouse-ng` fork's `main`; build and audit APK/AAB from a clean checkout of that exact commit, then create annotated `v2.1.3` and publish a regular GitHub release with SHA-256 companions. The AAB is a locally signed prepared artifact, not a Play-uploaded or Play-App-Signing artifact.

6. **Keep external store status explicit.** Update the disabled F-Droid draft to the released version/source commit only after the tag and assets exist. Do not enable the recipe or perform Play/F-Droid account, submission, review, or publication actions. Record official F-Droid build-server/review, physical-device, and unrun instrumentation checks as NOT RUN.

## Risks / Trade-offs

- [A catalog marker could make an older profile catalog unreadable] → Treat the new field as optional and preserve the existing catalog version and required-field validation.
- [The reserved stock ID could collide with an orphaned user draft] → Use repository create-only collision checks; do not overwrite the draft or create data at the colliding ID.
- [An upgrade may already have a blank profile the user deliberately emptied] → Add a separate stock profile and preserve the blank profile; do not fill it. Change selection only for the strictly observable single original empty profile/no-draft state.
- [Legacy files may be malformed or intentionally retained] → Migrate first, seed only after successful activation, preserve selection, and let warnings block any new profile write.

## Migration Plan

No destructive data migration is needed. New installs write the stock profile and catalog marker on first Actions initialization. Existing catalog JSON loads with the marker defaulting to false; eligible upgrades add the profile using normal atomic repository writes, then record the marker. Existing user data and legacy sources remain in place. The feature ships as version 2.1.3/code 29; publication changes only the fork's `main`, the immutable `v2.1.3` tag, and the corresponding GitHub release/assets. The disabled F-Droid draft is updated after actual publication; no external store action is part of the plan.
