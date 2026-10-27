## Context

See proposal.md for motivation. The shell combines Views with a Compose layout editor, while the foreground service owns ordered SSH input. Existing generation/input tokens prevent stale UI from dispatching remote commands. Draft writes use AtomicFile and the editor worker serializes mutations. Release shrinking is currently disabled and dynamic key-name lookup and SSH/crypto providers require explicit reflection preservation.

## Goals / Non-Goals

**Goals:** Remove redundant CPU, allocation, database, durable-write, and packaging work while keeping existing user-visible behavior, input ordering, and acknowledged recovery state.

**Non-Goals:** No dependency pruning, general unused-code removal, schema migration, command batching, new debounce interval, weakening of security checks, or claims of measured runtime speedups without profiling.

## Decisions

1. **Metadata-only asynchronous reload.** Use the existing generation-guarded credential metadata worker for host selection/header data instead of synchronous database work on resume. Reuse one host snapshot rather than enumerating it twice. Close database cursors with structured cleanup. Do not decrypt credentials to render metadata. Preserve legacy preference fallbacks and automatic connection/session attachment behavior.

2. **Content invalidation distinct from shell navigation.** Cache the last rendered immutable Actions/Scripts inputs, or use equivalent explicit invalidation, so navigation only updates visibility when content is unchanged. Rendering suppression must not suppress input-token rotation, cancellation, fresh command binding when content changes, loading/error transitions, or accessibility semantics. Traverse grid placements once instead of repeatedly searching all rows and button indexes.

3. **Opt out of invisible recent-command presentation before dispatch.** The native shell does not show a recent-command label. Suppress this presentation-only work at its subscriber boundary while preserving other listeners, result scopes, exec/clipboard feedback, generation guards, and transport behavior. Replace hidden-label instrumentation assertions with recording-backend observations rather than losing coverage.

4. **Direct touchpad drawing only.** Remove the blank backing bitmap and its unused paint/import/field, leaving direct paths, circles, hints, background, and gesture handling unchanged.

5. **Coalesce pending names, not durable acknowledgements.** Add a dedicated name-input adapter that replaces consecutive not-yet-started name edits with the latest value, without a timer. A started write completes normally; every non-name editor action is an ordering barrier, including Save, Back, Discard, XML transitions, and other edits. Keep immediate form input separate from worker-confirmed durable state where necessary, retaining invalid/failing input for correction. Never report a queued or failed write as durable; preserve last-known-good AtomicFile recovery and failure retry behavior. Do not batch across barriers or change layout serialization.

6. **Targeted release shrinking.** Enable R8 and resource shrinking, retain reflection-accessed input constants, JSch algorithm implementations and crypto/provider entry points based on actual loading paths, and preserve Android XML/framework entry points. Prefer targeted keep rules over retaining the entire application or ignoring missing classes wholesale. Verify the optimized artifact and compare it with the existing historical size only with an explicit provenance caveat; any same-tree baseline must use identical signing/toolchain inputs.

## Risks / Trade-offs

- Stale host/content snapshots: generation checks, explicit data invalidation, and host-edit/navigation regressions.
- Retained controls dispatching old commands: preserve all existing capability checks and verify stale-input rejection, including navigation and lifecycle boundaries.
- Editor queue races or recovery loss: deterministic queued/delayed/failing executor/file tests, barrier ordering, no time-based durability window, and unchanged saved profile bytes until explicit Save.
- R8 breaks reflection or providers: precise keep rules, inspect mapping/configuration and actual release contents, and run optimized-release input/SSH/crypto/XML/migration checks where the environment permits.
- Runtime validation may be unavailable: record each unavailable check as not run, never infer device passes from JVM/build success, and do not mark a task complete without its required available verification.

## Migration Plan

No application-data migration or identity/version change. Run focused and full JVM/lint/build gates, validate the shrunk signed release and available owned-emulator checks, record evidence and remaining limits, then commit only this change's source/tests/build/planning/verification files. Revert this commit normally if necessary; never clear user data or change signing keys.
