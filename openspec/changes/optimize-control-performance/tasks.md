## Model routing

The user explicitly requested and approved delegated `openai/gpt-6.1-sol#xhigh` for all six implementations and their verification, including the `[low]` tasks as an exception to the normal Luna assignment. Tags reflect actual complexity: one of six tasks is `[high]` because pending-name coalescing requires careful concurrency, ordering-barrier, and durability reasoning. No general unused-code cleanup is included.

## 1. Shell and pointer work

- [x] 1.1 [low] Replace resume-time synchronous selected-host database reads with generation-guarded metadata-only worker reloads, avoid duplicate host enumeration, and close cursors; verify selected-host/fallback freshness, no UI-thread database call in getPreferences, deterministic resource cleanup, and unchanged session/credential behavior with focused regressions and the integrated JVM/lint/build gate.
- [x] 1.2 [low] Avoid rebuilding unchanged Actions/Scripts content on shell navigation and traverse action placements without repeated full scans; verify retained view identity for unchanged navigation, refresh on actual data/loading/edit changes, preserved accessibility/action order, and stale-input cancellation with focused regressions and the integrated gate.
- [x] 1.3 [low] Suppress invisible native-shell recent-command presentation before dispatcher work without changing transport commands or other feedback; replace hidden-label command assertions with recording observations and verify FIFO routing, listener opt-in behavior, generation suppression, and instrumentation compilation.
- [x] 1.4 [low] Remove the never-painted full-surface touchpad bitmap and redundant draw while preserving direct gesture rendering; verify with a regression guarding bitmap-free drawing, pointer tests, lint, and app/instrumentation compilation, recording unavailable visual/device checks explicitly.

## 2. Draft write coalescing

- [x] 2.1 [high] Coalesce consecutive superseded not-yet-started name updates without timers or weakening acknowledged recovery, provide responsive name input, and preserve barriers for every other editor action; verify queued/in-flight/failing writes, latest-name ordering, Save/Discard/Cancel/XML transitions, invalid input, recreation recovery, and unchanged saved data using deterministic executor/file tests and the integrated gate.

## 3. Optimized release and integration

- [x] 3.1 [low] Enable release R8/resource shrinking with targeted reflection/provider rules; run the full JVM/lint/debug/instrumentation compilation gate, optimized signed-release assembly/signature/content/mapping checks, available optimized-release reflection/crypto/XML/migration validation, and strict OpenSpec/diff checks; record exact results, artifact size/provenance, and unavailable runtime/visual/performance measurements before committing the approved changes.
