# Explicit session cleanup (task 5.8)

## Implementation

- Notification Disconnect and deliberate task removal share the service's explicit cleanup path: pending start tickets and interactions are invalidated, the controller disconnects, the foreground notification is removed, and the service stops.
- Exit requests that same Disconnect before finishing `MainActivity`.
- The service records deliberate cleanup so `onDestroy` cannot reinterpret task dismissal or Exit as an involuntary interruption and re-arm task 5.7 recovery. Ordinary Activity pause/unbind remains observation-only.
- The service remains non-sticky; the app declares no boot receiver. Force-stop itself was not executed in this run.

## Verification

Android build and focused unit-test command (both Android SDK variables were set):

```sh
env ANDROID_HOME=/home/homoludens/Android/Sdk ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
  ./gradlew --no-daemon --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest --tests 'com.stripe1.xmouse.session.*' \
  :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- Build succeeded. The focused session JVM selection passed **51 tests across 5 suites** with no failures or skips.
- `lintDebug`: **0 errors, 141 warnings** (existing project warnings).
- Debug APK and instrumentation APK assembly succeeded, so the updated lifecycle instrumentation compiles.
- `git diff --check` and `openspec validate modernize-xmouse --strict` passed.
- **Instrumentation not run:** SDK-matched `adb -P 5038 devices -l` listed no devices. Force-stop was therefore not exercised on-device. Source audit found only `START_NOT_STICKY` service returns, no `BOOT_COMPLETED` receiver, and no boot/alarm/job restart path. The null-start and no-boot-receiver instrumentation assertions compile but were not executed.
- A preliminary `adb devices` invocation hit a client/server version mismatch and restarted the default host ADB server; no device was contacted. The SDK-matched `adb -P 5038 devices -l` check listed no devices.
