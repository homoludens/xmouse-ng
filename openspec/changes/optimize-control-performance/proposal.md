## Why

A source audit found avoidable UI-thread database reads, repeated hidden-view rebuilding, per-command invisible UI feedback, a blank full-surface bitmap, repeated queued draft writes, and an unshrunk release. Remove this work without changing remote-input ordering, saved data, recovery guarantees, or supported Android behavior.

## What Changes

- Load selected-computer metadata off-main and close query resources deterministically.
- Render Actions and Scripts only when their content/state actually changes, preserving input revocation boundaries.
- Avoid recent-command presentation work when the production shell has no visible consumer; retain recording-based command assertions.
- Remove the never-painted touchpad backing bitmap, preserving direct gesture feedback.
- Coalesce only superseded, not-yet-started profile-name updates, with ordered barriers for all other editor actions and no weakening of acknowledged durable state.
- Enable release code/resource shrinking with targeted reflection/provider keep rules and optimized-release verification.

## Capabilities

### New Capabilities

None. These are implementation/build optimizations of existing behavior; this change explicitly sets `skip_specs: true`.

### Modified Capabilities

None. Existing migration, command routing, recovery, security, and support-floor requirements remain unchanged.

## Impact

MainActivity, destination rendering, database query resource handling, session presentation, MyMouseView, the advanced editor worker/UI adapter, release Gradle/ProGuard configuration, focused regression tests, and a verification record. No schema/version/application-identity changes, credential handling changes, dependency removals, or broad dead-code cleanup. The user requested all six optimizations and a commit, with Sol 6.1 xhigh for implementation and verification.
