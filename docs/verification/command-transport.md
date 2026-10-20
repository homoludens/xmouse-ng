# Command transport boundary (task 2.3)

Verified **2026-10-20** against the task-2.2 baseline `4de6602`.

## Scope and behavior

- `CommandTransport` exposes the existing Java `boolean executeShellCommand(String)` contract. It does not add framing, parsing, retries, connection state, or remote-execution results.
- `XDoTool` and `YDoTool` depend only on that interface. Their command generation is unchanged; their existing `void` methods still ignore the transport's boolean result.
- `MyConnectionHandler` implements the interface without changing its method bodies, Activity ownership, SSH/authentication, threading, or lifecycle. Those changes remain for tasks 4/5.
- `CustomCommandDispatcher` contains the adapter's existing normalization/tokenization/switch verbatim apart from the transport variable name. `CustomKeyboardButtonAdapter` delegates and retains its original missing-argument catch/log behavior. Numeric parsing failures still propagate; unknown commands still use the original unnormalized shell text.
- Test-only `RecordingSshTransport` records writes in order and supplies a configurable write result. It constructs no Android or network objects.

The original **22 custom-command cases and 6 ydotool key cases** retain their inputs and expected commands. Added coverage characterizes historical single-token `type` handling, repeated spaces, case-sensitive prefix handling, raw/multiline shell fallback, empty commands, all mouse-button mappings, relative-motion precision, key ordering, ignored failed writes, and adapter diagnostics. No historical command quirk was corrected.

## Pure JVM verification

Run from the repository root using **zsh**, after Gradle has cached the existing test dependencies:

```sh
set -eu
JARS="$HOME/.gradle/caches/modules-2/files-2.1"
JUNIT_JAR=("$JARS"/junit/junit/4.13.2/*/junit-4.13.2.jar)
HAMCREST_JAR=("$JARS"/org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar)
PROVIDER_JAR=("$JARS"/com.tngtech.java/junit-dataprovider/1.13.1/*/junit-dataprovider-1.13.1.jar)
CP="$JUNIT_JAR[1]:$HAMCREST_JAR[1]:$PROVIDER_JAR[1]"
OUT=$(mktemp -d /tmp/opencode/xmouse-task-2.3-pure-java.XXXXXX)
MAIN=app/src/main/java/com/stripe1/xmouse
TEST=app/src/test/java/com/stripe1/xmouse
javac --release 17 -encoding UTF-8 -cp "$CP" -d "$OUT" \
  "$MAIN"/{CommandTransport,CustomCommandDispatcher,IDoTool,XDoTool,YDoTool,InputEventCodes}.java \
  "$TEST"/{RecordingSshTransport,CustomKeyboardButtonAdapterTest,YdoToolTest,DoToolTransportTest}.java
java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
  com.stripe1.xmouse.CustomKeyboardButtonAdapterTest \
  com.stripe1.xmouse.YdoToolTest \
  com.stripe1.xmouse.DoToolTransportTest
```

**Passed: 53 tests.** The explicit compile/runtime classpath has only these pure classes and JUnit/Hamcrest/data-provider: no Android JAR, Mockito, Activity/adapter implementation, concrete connection handler, or JSch. This demonstrates translation testing does not require or construct Android UI or a real SSH connection. The separate three delegate tests use mocked Android boundaries, not an Activity or actual adapter constructor.

## Android-project verification

Both SDK variables were supplied on every Android command:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g :app:testDebugUnitTest \
  --tests com.stripe1.xmouse.CustomKeyboardButtonAdapterTest \
  --tests com.stripe1.xmouse.YdoToolTest \
  --tests com.stripe1.xmouse.DoToolTransportTest \
  --tests com.stripe1.xmouse.CustomKeyboardButtonAdapterDelegationTest
./gradlew --no-daemon --console=plain --no-build-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- Focused run: **BUILD SUCCESSFUL**, 31s; **56 tests, 0 failures/errors/skips**.
- Full run: **BUILD SUCCESSFUL**, 55s; **50 actionable tasks, 12 executed / 38 up-to-date**. Unit tests and all lint analysis tasks executed with build-cache reuse disabled; debug APK packaging succeeded.
- Full XML results: **69 tests, 0 failures/errors/skips**, across 8 suites (38 custom-command, 6 ydotool, 9 transport, 3 adapter-delegate, plus 13 existing unrelated regressions).
- Lint XML: **0 errors/fatal findings, 155 warnings**, unchanged warning total; no new suppressions or baselines. Existing Jetifier/deprecated-API and JVM CDS warnings remain visible.
- A narrow source comparison confirms the handler method bodies and X/Y generation are unchanged, and the extracted switch and normalization match the baseline. `git diff --check` passes. The old task-1.4 exact whole-source audit was not rerun as current coverage.

Full Gradle log: `/tmp/opencode/xmouse-task-2.3-full-verification.log`. Reports remain in `app/build/test-results/testDebugUnitTest/` and `app/build/reports/lint-results-debug.xml`; APK: `app/build/outputs/apk/debug/app-debug.apk`.

No device/instrumentation or live-SSH execution was run or claimed for this boundary task. Only **2.3** is completed here; no staging or commit was performed. Future service-backed routing can implement `CommandTransport` without coupling the existing backends to the session owner.
