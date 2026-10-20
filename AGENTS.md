# Project Agent Instructions

## OpenSpec task complexity and model routing

Every actionable task in an OpenSpec `tasks.md` MUST carry exactly one complexity tag immediately after its numeric task ID. Preserve the OpenSpec checkbox format:

```markdown
- [ ] 1.1 [low] Task description and verification criteria
- [ ] 1.2 [high] Task description and verification criteria
```

Use this mandatory model assignment for implementation and the task's associated verification:

| Tag | Assigned model | Exact model/variant reference |
| --- | --- | --- |
| `[low]` | luna6 max (GPT-6 Luna, max) | `openai/gpt-6-luna#max` |
| `[high]` | sol6.1 xhigh (GPT-6.1 Sol, xhigh) | `openai/gpt-6.1-sol#xhigh` |

### Classification

- Default to `[low]` for work with a clear design and an established implementation approach, including normal feature implementation, API/library integration, schema additions, permissions, UI work, documentation, and defined verification procedures. `[low]` does not mean trivial or lower-quality work.
- Reserve `[high]` for the most complex tasks: foundational security design, difficult architectural extraction, multi-stage recovery under partial failure, subtle concurrency/state-machine behavior, or substantial networking/gesture/history algorithms.
- Target approximately **20% `[high]` tasks** across a change, with the remainder `[low]`. Compare tasks by their actual implementation and verification difficulty; security sensitivity, multiple files, platform APIs, or integration tests alone do not make a task `[high]`. Do not weaken scope, tests, or safety requirements to meet the target.
- If a `[low]` task reveals substantial unexpected complexity, stop and reassess its tag with a concrete reason before routing the remaining work to sol6.1 xhigh. Routine debugging or a failed command is not an automatic promotion. Explain any material increase beyond the approximate 20% target rather than broadly relabeling categories.
- Do not silently downgrade a `[high]` task or substitute another model/variant; obtain user approval for exceptions. Both models must meet the same correctness, security, and verification standards.

### Execution and maintenance

- Before executing an approved task, ensure the selected model and variant match its tag. When using authorized delegation, pass the exact reference above explicitly. If the assigned model/variant is unavailable or cannot be selected, ask the user rather than executing with a substitute.
- These labels and routing instructions do not by themselves authorize implementation, delegation, or parallel execution. Respect the active mode, the user's approved scope, and task dependencies.
- Keep tags on new or split tasks. Preserve existing task IDs, checkbox states, descriptions, and verification criteria when only annotating complexity; labeling a task does not complete it.
- Mark a task complete only after its implementation and required verification have actually succeeded. Record unavailable verification as not run, not passed.
