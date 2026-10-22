# Bounded foreground recovery (task 5.7)

Baseline: `296e5a1`; verified 2026-10-22. One dedicated developer agent implemented only
task 5.7. No staging or commit occurred; task 5.8 and unrelated pre-existing untracked paths
remained untouched.

## Contract

- Reuse the existing finite SSH stage deadlines, 15-second keepalive / three missed-response
  policy, independent 60-second inbound-silence backstop, and 500 ms / 2-second bounded health
  checks. No new network listener, background reconnect timer, boot receiver, or sticky restart.
- An interrupted ready session retains its desired host but publishes Error, not Connected.
  Involuntary service destruction is an interruption, not explicit Disconnect; a destroyed
  service's pending host-selection callback remains invalid and cannot start background work.
- A visible return can issue one generation/intent/disconnect-epoch-scoped recovery ticket,
  independently of the saved auto-connect preference. Its foreground capability is revoked on
  Activity pause and rechecked on the controller queue. Recovery uses the retained host, the old
  physical-close barrier, fresh credential preparation/resolution, normal host trust and normal
  authentication. No queued input transfers to the new attempt.
- A failed recovery does not rearm itself. The toolbar/session controls expose Retry, with
  Disconnect still available to clear retained intent. Successful recovery permits handling a
  later, distinct interruption. Explicit Disconnect revokes recovery and saved auto-connect;
  only deliberate Connect clears that suppression in the retained process owner.
- A read-only permission prerequisite runs before credential preparation, again before SSH,
  before foreground recovery, and on the existing health cadence while an attempt is live.
  Missing/revoked access produces a typed permission failure and closes the attempt. Missing
  permission does not consume the opportunity to recover after a later grant, or trigger a
  permission prompt/network operation. Android INTERNET is checked, plus ACCESS_LOCAL_NETWORK
  on API 37. The permission declaration/request coordinator remains task 6.2; until then API 37
  fails closed rather than accessing the LAN without authorization. Notification denial remains
  a disclosure/visibility concern, not an invented SSH prerequisite.
- Detached Activities clear their cached observation, so return cannot reuse a pre-loss
  Connected indicator. No indefinite screen-off or force-stop survival is promised.

## Lean coverage and commands

No test methods were added or removed. Existing controller, factory, loopback-pipeline and
Activity/service methods were extended. The focused JVM selection covers honest loss, one-shot
recovery, foreground/stale-generation/disconnect rejection, close barriers, no replay, normal
reauthentication/fresh resolution, changed-key blocking, permission denial/revocation, finite
blackhole detection and existing input invariants.

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew :app:testDebugUnitTest --tests 'com.stripe1.xmouse.session.*' \
  --tests 'com.stripe1.xmouse.transport.SshDeadlineTest' \
  --tests 'com.stripe1.xmouse.transport.JschDeadlineIntegrationTest' \
  :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
python3 docs/verification/audit-ssh-transport.py
openspec validate modernize-xmouse --strict
git diff --check
```

Result: **73 JVM tests / 7 suites passed**, zero failures/errors/skips; lint **0 errors,
141 warnings**; debug app and instrumentation APK builds passed; SSH source audit, strict
OpenSpec validation and whitespace checks passed. Reports are under `app/build/test-results/`
and `app/build/reports/`.

Earlier verification is not substituted for these results: the first focused run exposed two
test-fixture issues (a resolution-counting decorator needed explicit Java-default-method
forwarding, and a backend-object count included objects with no network work). Both were fixed
without weakening network/credential/trust assertions. A combined build exceeded its 120-second
tool timeout; the same command was rerun with a 300-second timeout and passed.

**Not run:** Android instrumentation/device execution, API 37 permission grant/revoke UI, physical
LAN interruption, and the five-minute physical-device continuity test. SDK `adb -P 5038 devices -l`
(with both SDK variables set) reported no connected devices; no emulator was started or device
settings modified. Existing instrumentation extensions compile but are not claimed as passes.
