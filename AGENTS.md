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

- Use `[low]` for bounded, routine work with an established approach: synthetic fixtures, simple input/presentation changes, straightforward model serialization or profile CRUD, documentation, and running an established verification procedure without nontrivial diagnosis.
- Use `[high]` for architecture or cross-component changes, dependency/toolchain compatibility decisions, security/authentication/cryptography, permissions and trust policy, data migrations or recovery, atomic persistence, Android lifecycle/background services, asynchronous network/resource ownership, concurrency/cancellation, complex gestures/history, and system-level validation requiring diagnosis or safety judgments.
- Classify the entire task, including its verification, by its most demanding or risk-sensitive part. Mixed, uncertain, or security-sensitive work MUST be `[high]`.
- If a `[low]` task develops unexpected complexity, stop, promote it to `[high]`, and route the remaining work to sol6.1 xhigh before proceeding. Do not silently downgrade a `[high]` task or substitute another model/variant; obtain user approval for exceptions.

### Execution and maintenance

- Before executing an approved task, ensure the selected model and variant match its tag. When using authorized delegation, pass the exact reference above explicitly. If the assigned model/variant is unavailable or cannot be selected, ask the user rather than executing with a substitute.
- These labels and routing instructions do not by themselves authorize implementation, delegation, or parallel execution. Respect the active mode, the user's approved scope, and task dependencies.
- Keep tags on new or split tasks. Preserve existing task IDs, checkbox states, descriptions, and verification criteria when only annotating complexity; labeling a task does not complete it.
- Mark a task complete only after its implementation and required verification have actually succeeded. Record unavailable verification as not run, not passed.
