# Android build toolchain selection

Selected and verified **2026-10-20** for `modernize-xmouse` task **1.1**, then applied and configuration-verified in **1.3**. Task 1.4 compiles the existing application, and 1.5 updates its tests/lint.

## Selected pins

| Component | Selection | Compatibility evidence |
| --- | --- | --- |
| Android SDK | `minSdk = 24`, `compileSdk = 37`, `targetSdk = 37` | Exactly Android 17 / **37.0**, not 37.1; no minimum-version increase. |
| Android Gradle plugin | `com.android.tools.build:gradle:9.1.1` | [Android's API support table](https://developer.android.com/build/releases/about-agp#api-level-support) names 9.1.1 as the minimum for 37.0. Its [release compatibility table](https://developer.android.com/build/releases/agp-9-1-0-release-notes#compatibility) supports 37.0 and specifies Gradle 9.3.1 / Build Tools 36.0.0 / minimum JDK 17. |
| Gradle wrapper | **9.3.1**, `gradle-9.3.1-bin.zip` | [Gradle 9.3.1 Java matrix](https://docs.gradle.org/9.3.1/userguide/compatibility.html#java_runtime) supports running on JDK 21; the old 7.3.3 wrapper does not. |
| Build JVM | **JDK 21 LTS** (verification used Temurin **21.0.7+6**) | Runtime/toolchain JDK, not the Android bytecode/API level. Do not commit a local JDK path. |
| Java / Kotlin bytecode | **17 / 17** | Set Java source/target compatibility and Kotlin `JvmTarget.JVM_17` explicitly. Both probe class files had major version **61**. Java library APIs still need Android API guards or separately verified desugaring. |
| SDK Build Tools | **36.0.0** | AGP's documented default/minimum; installed and used successfully with platform 37.0. No NDK is needed by this Java/Kotlin application. |
| Kotlin compiler, KGP, stdlib | **2.4.21** | [Stable bug-fix release](https://kotlinlang.org/docs/releases.html#release-history), October 8, 2026. [KGP compatibility](https://kotlinlang.org/docs/gradle-configure-project.html) lists 2.4.20–2.4.21 with Gradle 7.6.3–9.7.0 and AGP 8.5.2–9.3.1, covering both selected tools. |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose` **2.4.21** | [Official setup](https://developer.android.com/develop/ui/compose/compiler): match the Kotlin version; do not use the old `kotlinCompilerExtensionVersion` pairing. |
| Compose stable BOM | `androidx.compose:compose-bom:2026.02.01` | Its [published BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.02.01/compose-bom-2026.02.01.pom) pins UI/Foundation/Runtime/tooling/test **1.10.4**, Material3 **1.4.0**. Import the same BOM in application and instrumentation configurations. |

This deliberately uses the earliest stable AGP line officially supporting 37.0 and a previously released Compose/AndroidX set, not the latest of every library. Kotlin 2.4.21 supplies a documented supported intersection with AGP 9.1.1 and Gradle 9.3.1; older KGP compatibility rows do not cover that pair fully.

### AndroidX / UI / coroutine pins

Declare only artifacts needed by each implementation task; the inspection probe resolved all the following together, including optional editor/service/test integrations. Versions outside the Compose BOM must be explicit.

| Artifacts | Pinned version | AAR manifest minimum / required compile SDK observed |
| --- | --- | --- |
| `androidx.core:core`, `core-ktx` | **1.17.0** | 21 / 36 |
| `androidx.activity:activity`, `activity-ktx`, `activity-compose` | **1.12.4** | 23 / 36 |
| `androidx.appcompat:appcompat` | **1.7.1** | 21 / 34 |
| `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-savedstate`, `lifecycle-service` | **2.10.0** | 23 / 34–35, depending on Android variant |
| `androidx.constraintlayout:constraintlayout` | **2.2.1** | 21 / 34 |
| `androidx.drawerlayout:drawerlayout` | **1.2.0** | 14 / 33 |
| `com.google.android.material:material` (existing Views UI) | **1.13.0** | 21 / 1 (published metadata value, not its build SDK) |
| Compose UI/Foundation + UI tooling/preview/test | **1.10.4**, through BOM | 23 / at most 35 |
| Compose Runtime | **1.10.4**, through BOM | 23 / 34–35 |
| Compose Material3 (new editor only) | **1.4.0**, through BOM | 21 / 35 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `kotlinx-coroutines-test` | **1.10.2** | JVM JARs, not AARs; Android module metadata declares Java 8 bytecode. Use the [Android dispatcher module](https://github.com/Kotlin/kotlinx.coroutines/tree/1.10.2#android), not a desktop dispatcher. |
| `androidx.test:core`, `androidx.test:runner` | **1.7.0** | 21 / no AAR metadata file |
| `androidx.test.ext:junit` | **1.3.0** | 21 / no AAR metadata file |
| `androidx.test.espresso:espresso-core` | **3.7.0** | 21 / no AAR metadata file |

Stable release records: [Core](https://developer.android.com/jetpack/androidx/releases/core#1.17.0), [Activity](https://developer.android.com/jetpack/androidx/releases/activity#1.12.4), [AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.1), [Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0), [ConstraintLayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout#2.2.1), [DrawerLayout](https://developer.android.com/jetpack/androidx/releases/drawerlayout#1.2.0), [Compose UI](https://developer.android.com/jetpack/androidx/releases/compose-ui#1.10.4), [Material3](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.4.0), [Material Components](https://github.com/material-components/material-components-android/releases/tag/1.13.0), [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test).

## Published metadata and verification

Resolved from **Google Maven and Maven Central**, not JCenter or a mirror. The inspection used a separate project at `/tmp/opencode/xmouse-toolchain-task-1.1`; no repository build/source files were changed.

- Downloaded the actual plugin POMs and Gradle `.module` files. [AGP's POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.1.1/gradle-9.1.1.pom) brings KGP 2.2.10 by default; it does **not** select 2.4.21 automatically. The [KGP module](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.4.21/kotlin-gradle-plugin-2.4.21.module) and [Compose compiler module](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.4.21/compose-compiler-gradle-plugin-2.4.21.module) offer compatible `gradle813RuntimeElements` variants (plugin API 8.13, JVM 8), selectable by Gradle 9.3.1. The probe reported compiler **2.4.21**.
- Inspected `r8-version.properties` in the published `com.android.tools.build:builder:9.1.1` JAR and the running plugin: bundled **R8 9.1.31**, above the [official Kotlin 2.4 requirement of R8 9.1.29](https://developer.android.com/build/kotlin-support). No R8 override is needed.
- Read Compose BOM constraints and Android variant redirects, e.g. [`ui:1.10.4.module`](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.10.4/ui-1.10.4.module) selects **`ui-android:1.10.4`**. Checked Android AARs, not only multiplatform facade POMs.
- Resolved `debugRuntimeClasspath` and `debugAndroidTestRuntimeClasspath`: **111 unique module coordinates; 87 unique AARs**. Unzipped every AAR's `AndroidManifest.xml` and, where present, `META-INF/com/android/build/gradle/aar-metadata.properties`. Maximum declared **minSdk 23**, **minCompileSdk 36**, **minAndroidGradlePluginVersion 8.9.1**; no nonzero required SDK extension/minor and no declared core-library-desugaring requirement. Missing metadata was treated as missing, not proof of unrestricted compatibility.
- Confirmed transitive selections including SavedState **1.4.0**, NavigationEvent **1.0.2**, Coroutines **1.10.2**, and Kotlin stdlib **2.4.21**. No resolved AAR raises the floor above 24 or requires 37.1. Both app/test manifest merges retained **minSdk 24 / targetSdk 37**, without `tools:overrideLibrary`.
- The isolated fixture successfully compiled a Kotlin `@Composable` Material3 `Text` calling a Java method, with both bytecode targets set to 17. This is toolchain evidence, **not** an XMouse build, device test, or feature acceptance result.

Commands run against that probe (set `GRADLE_9_3_1` to an unpacked Gradle 9.3.1 executable):

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export PROBE=/tmp/opencode/xmouse-toolchain-task-1.1
"$GRADLE_9_3_1" --version
"$GRADLE_9_3_1" --project-dir "$PROBE" --no-daemon --console=plain \
  :app:checkDebugAarMetadata :app:checkDebugAndroidTestAarMetadata \
  :app:processDebugMainManifest :app:processDebugAndroidTestManifest \
  :app:inspectResolvedArtifacts
python3 "$PROBE/audit_metadata.py"
"$GRADLE_9_3_1" --project-dir "$PROBE" --no-daemon --console=plain \
  :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

Results: Gradle **9.3.1**, launcher JVM **21.0.7**; metadata/manifest probe **BUILD SUCCESSFUL** (6 tasks, 1m29s); metadata audit **87 AARs, no floor/ceiling violations**; mixed-source compilation **BUILD SUCCESSFUL** (8 tasks, 1m20s). Probe configuration, artifact coordinates/paths, AAR SHA-256s, downloaded metadata and logs remain in that temporary directory for review. To repeat after build migration, run the normal AAR-check/manifest tasks against XMouse and audit its resolved graph again; newly added dependencies are not covered automatically.

### SDK location detection (task 1.2)

Both SDK variables were set to the selected local SDK for Android tooling; no SDK path was written to project build configuration:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --list_installed
```

On **2026-10-20**, `sdkmanager --list_installed` exited successfully and detected `platforms;android-37.0` revision 2, `build-tools;36.0.0`, and `platform-tools` 37.0.1. It emitted an SDK XML version-compatibility warning while loading the installed package metadata, but completed the listing. An audit of tracked Gradle scripts, properties, and wrapper launchers found no `sdk.dir`, `org.gradle.java.home`, or absolute `/home/`/`/Users/` paths; `local.properties` is not tracked.

For individual AAR reinspection, use `https://dl.google.com/dl/android/maven2/<group-path>/<artifact>/<version>/<artifact>-<version>.aar` and read the two ZIP entries above. Maven Central uses `https://repo.maven.apache.org/maven2/` for Kotlin/coroutine POMs/modules/JARs. `minSdk` comes from the **manifest**, not `minCompileSdk`.

### Build migration and configuration verification (task 1.3)

Applied the selected AGP/KGP/Compose compiler, SDK, bytecode, and UI pins in the Groovy build. Existing Views dependencies now include explicit Core/AppCompat declarations; Compose UI/Foundation/Material3, Activity integration, and preview tooling are prepared for the editor. Lifecycle/service/coroutine and test integrations remain for their implementation tasks. JSch, SpongyCastle, and the existing unit-test dependencies are unchanged.

Generated both wrapper launchers, the JAR, and properties with the cached **Gradle 9.3.1** distribution, not the old wrapper. The distribution checksum is pinned in `gradle-wrapper.properties`. The generated JAR's SHA-256 is `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`, matching the [official wrapper checksum](https://services.gradle.org/distributions/gradle-9.3.1-wrapper.jar.sha256).

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
"$GRADLE_9_3_1" --no-daemon --console=plain --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g wrapper --gradle-version 9.3.1 \
  --distribution-type bin \
  --gradle-distribution-sha256-sum b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06
./gradlew --version
./gradlew --no-daemon --console=plain --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  --init-script /tmp/opencode/xmouse-build-task-1.3/verify-configuration.init.gradle \
  :app:verifyTask13Configuration :app:checkDebugAarMetadata
```

Results on **2026-10-20**: wrapper generation **BUILD SUCCESSFUL**; wrapper runtime **9.3.1 / Temurin 21.0.7+6**; configuration assertions and application AAR metadata **BUILD SUCCESSFUL** (2 executed tasks). The temporary, uncommitted assertion task checked:

- Actual AGP **9.1.1** and Kotlin compiler **2.4.21**, Compose enabled, and no `org.jetbrains.kotlin.android` application.
- Namespace/application ID **`com.stripe1.xmouse`** and unchanged version identity **25 / 2.0.27**.
- Minimum/compile/target SDK **24 / 37 / 37**, Build Tools **36.0.0**, and resolved SDK boot classpath using **`platforms/android-37.0/android.jar`**.
- Java source/target **17 / 17** and Kotlin target **17**, including the configured debug compilation tasks; only Google Maven/Maven Central repositories.

Logs and the assertion script remain in `/tmp/opencode/xmouse-build-task-1.3`. No absolute SDK/JDK location was added to tracked build configuration. AGP reports the pre-existing `android.enableJetifier=true` property as deprecated; it did not block configuration or metadata checks.

**Next-task boundary:** Java/resource-ID compatibility and source-manifest namespace cleanup remain for 1.4; instrumentation, test-library migration, and meaningful lint policy remain for 1.5. The old blanket lint policy is retained using AGP 9's `lint` DSL solely to load configuration. No app assembly, Java/Kotlin source compilation, lint, unit/instrumentation tests, or device tests were run in 1.3.

## Handoff constraints

- **Task 1.2:** Android commands must set both SDK variables shown above. The installed stable platform is `android-37.0`, revision 2, `PreviewSdkInt=0`; the probe accepted `compileSdk = 37`. Do not commit `sdk.dir`, `org.gradle.java.home`, or absolute machine paths in build configuration.
- **Task 1.3:** Keep application ID and namespace **`com.stripe1.xmouse`**. Use AGP 9's default [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin); do **not** also apply `org.jetbrains.kotlin.android`. Pin the higher KGP through a root `buildscript` classpath as [documented by AGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version), alongside the matching Compose compiler plugin. Keep the new DSL; use `kotlin.compilerOptions`, not `android.kotlinOptions`. Replace `proguard-android.txt` with `proguard-android-optimize.txt`, which AGP 9 requires. None of these edits were made in task 1.1.
- Wrapper distribution: `https://services.gradle.org/distributions/gradle-9.3.1-bin.zip`; [official SHA-256](https://services.gradle.org/distributions/gradle-9.3.1-bin.zip.sha256): `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`. Regenerate/verify wrapper files using compatible Gradle, not by executing 7.3.3 on JDK 21.
- **Tasks 1.4/1.5:** Preserve non-final resource IDs and adapt Java switches rather than hiding the migration. Replace blanket lint suppression and obsolete instrumentation; the selected runner is `androidx.test.runner.AndroidJUnitRunner`. Existing Mockito/JUnit and SSH/SpongyCastle dependencies were **not** validated or replaced by this probe; their migration/verification remains with the corresponding tasks.
- Do not use unsupported-compile-SDK suppression, manifest minimum overrides, a higher minimum, or API 37.1 to force dependency acceptance. Kotlin 2.4.21 and Gradle's embedded Kotlin 2.2.21 are different components. Device coverage on API 24 and 37 and the actual XMouse build/lint/instrumentation suites are **not run by task 1.1**.

## Existing-source compatibility verification (task 1.4)

Verified **2026-10-20**. Replaced only the two app-resource-ID switches in `MainActivity` with bounded `if` chains and removed the source manifest's obsolete `package` attribute; the Gradle namespace/application ID remain unchanged. Framework `android.R.id.home`, numeric navigation IDs, and non-resource switches remain valid and unchanged. No Java-to-Kotlin source conversion, final-resource-ID override, desktop-command change, or runtime/lifecycle feature was introduced.

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
python3 docs/verification/audit-resource-actions.py
./gradlew --no-daemon --console=plain --no-build-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g :app:clean :app:assembleDebug
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging \
  app/build/outputs/apk/debug/app-debug.apk
```

- **Action audit passed:** the committed Python/Git audit derives the permitted conversion directly from baseline `fa8b529810e5bcc0c2602313751eefc7899bd9b8`. All **10 menu actions** and **10 mouse/joystick IDs** retain their exact Java branch tokens, including grouped labels, default no-op, and superclass fallback. It also checks menu XML coverage and unchanged remaining Java/resources, including dynamic drawer mappings and desktop backends. This is a migration-specific source audit, **not** an on-device menu test; later intentional behavior changes need their own tests rather than weakening this baseline.
- **Fresh assembly passed:** `BUILD SUCCESSFUL in 54s`, **36 tasks executed**, no build-cache reuse; Java compilation, manifest merge, resource processing, dexing, and APK packaging succeeded. The real app currently has no Kotlin sources, so its `compileDebugKotlin` correctly reports **NO-SOURCE**.
- **Actual APK assertions passed:** `com.stripe1.xmouse`, version **25 / 2.0.27**, **minSdk 24 / targetSdk 37 / compileSdk 37**, and launcher `com.stripe1.xmouse.MainActivity`. `javap` confirmed all **61 app `R.id` fields remain non-final**; `MainActivity.class` is bytecode major **61** (Java 17).

### Isolated mixed-source smoke check

The temporary project `/tmp/opencode/xmouse-build-task-1.4/mixed-smoke` copies the current root Gradle files/properties and app build/proguard/main sources, **without** `local.properties`. Only that copy adds `toolchainsmoke/JavaProbe.java` and `KotlinProbe.kt`: Java calls Kotlin, Kotlin calls Java plus the real `IDoTool.MOUSE_LEFT`, and a Kotlin `@Composable` renders Material3 `Text`. No probe source or binary is added to the production app.

With both SDK variables exported as above, repeat against the retained fixture:

```sh
./gradlew --project-dir /tmp/opencode/xmouse-build-task-1.4/mixed-smoke \
  --no-daemon --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g \
  --init-script /tmp/opencode/xmouse-build-task-1.3/verify-configuration.init.gradle \
  :app:verifyTask13Configuration :app:compileDebugKotlin :app:compileDebugJavaWithJavac
python3 /tmp/opencode/xmouse-build-task-1.4/verify-artifacts.py
```

Results: **BUILD SUCCESSFUL** (1m2s); Kotlin and Java compilation both executed. Configuration assertions reconfirmed JDK **21.0.7**, Gradle **9.3.1**, AGP **9.1.1**, built-in Kotlin **2.4.21**, Java/Kotlin targets **17**, and SDK **24/37/37**. All probe class files have major **61**; `javap` finds the Compose-generated `Composer` parameter. Running the compiled Java probe on the JVM passes the Java → Kotlin → Java/`IDoTool` assertion. This proves compiler/interoperability wiring, **not Android runtime behavior**.

Successful build logs, APK badging, resource-field/Compose signatures, fixture sources, and artifact assertions remain in `/tmp/opencode/xmouse-build-task-1.4`. The pre-fix build failed on the expected 20 non-final case labels; an intermediate assemble hit the 120-second harness timeout, superseded by the successful fresh run above.

**Warnings / 1.5 handoff:** existing Jetifier deprecation and deprecated-Java-API notes remain. AGP packages `libandroidx.graphics.path.so` unstripped (nonfatal). Unit tests, lint, instrumentation APK/device tests, and on-device API 24/37 behavior were **not run**. The existing lint policy/test libraries and obsolete instrumentation are deliberately unchanged for **1.5**; SSH/SpongyCastle remain for **4.1**. Only task **1.4** was completed here.

## Test/lint migration (task 1.5 — complete)

On **2026-10-20**, updated JUnit to **4.13.2** and Mockito Core to **5.24.0**, removing the old separate `mockito-inline` declaration. Mockito 5 includes inline mocking; its [documented JDK 21+ startup-agent configuration](https://javadoc.io/static/org.mockito/mockito-core/5.24.0/org.mockito/org/mockito/Mockito.html#0.3) is supplied through a tracked-input `CommandLineArgumentProvider`, without dynamic-agent-loading overrides or machine-specific paths. The existing data-provider library and all unit-test cases/assertions remain unchanged.

Set `androidx.test.runner.AndroidJUnitRunner` with the selected AndroidX Test **Core/Runner 1.7.0** and **ext JUnit 1.3.0**. Replaced `ApplicationTestCase` with an AndroidJUnit4 application-context/package assertion. Lint now explicitly aborts on errors, checks test sources, and emits an XML report; no baseline, category disablement, severity downgrade, test-failure ignore, or default-return workaround was added.

### Initial blocked attempt (historical; superseded below)

The first attempt exposed the following blockers and correctly left 1.5 unchecked at **4/67**. Its log is retained; the generated report paths now contain the successful follow-up results documented below.

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --continue --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump xmltree \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --file AndroidManifest.xml
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

- **Combined Gradle command failed (exit 1)** in **1m45s**, solely at `:app:lintDebug`; `--continue` allowed the instrumentation APK build to finish. **61 actionable tasks:** 34 executed, 8 from cache, 19 up-to-date. Full log: `/tmp/opencode/xmouse-task-1.5-verification.log`.
- **Unit tests passed:** **29 tests, 0 failures/errors/skips** (22 custom-command translations, 6 ydotool key translations, 1 existing arithmetic smoke test). Reviewed all three XML suites under `app/build/test-results/testDebugUnitTest/`; both command backends and fallback commands retain their original coverage.
- **Instrumentation APK built:** `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. Actual APK manifest inspection confirms package `com.stripe1.xmouse.test`, runner `androidx.test.runner.AndroidJUnitRunner`, target package `com.stripe1.xmouse`, and SDK **min 24 / target 37 / compile 37**. The replacement smoke test compiled and packaged; **on-device execution was not run** and is not claimed.
- **Lint failed with 3 errors and 154 warnings**, verified in `app/build/reports/lint-results-debug.xml` (HTML alongside it; full text in `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`):

| Error | Location | Finding / task boundary |
| --- | --- | --- |
| `GestureBackNavigation` | `MainActivity.java:625` | `onBackPressed` no longer handles back gestures on target API 36+. Initially blocked 1.5 because the AndroidX Back-navigation migration was allocated to **10.2**; the user subsequently approved moving only the existing-screen drawer migration into **1.5**. |
| `MissingInflatedId` | `ListEditActivity.java:132` | Lint associates `new_script_alias` with `manage_host_item`. The existing conditional replaces both the layout and IDs for scripts (lines 125–129); the script ID exists in `manage_script_item.xml:16`. This appears to be a branch-correlation analysis finding; no behavior change or suppression was made while blocked. |
| `MissingInflatedId` | `ListEditActivity.java:133` | Same conditional-layout finding for `new_script_command`, declared in `manage_script_item.xml:28`. |

At that pause, warnings remained visible, including **9 `StaticFieldLeak`**, **5 `Recycle`**, and **2 `ScopedStorage`** findings. The task-1.4 source/action audit and `git diff --check` passed before any production-source changes. Those were historical results, not current Back/dialog feature coverage.

### User-approved resolution and successful verification

The user explicitly approved moving **only the existing-screen drawer Back migration** from 10.2 into 1.5 and fixing the two conditional-layout binding findings, without suppressing lint. The task text records this allocation. **10.2 remains pending** for integrated drawer/dialog/editor Back handling, dirty-state behavior, and API 36/37 device coverage; no future editor, remote-command, authentication/trust, or session-owner work was implemented.

- `MainActivity` now registers `DrawerBackCallback` through the lifecycle-aware `addCallback(this, ...)` overload and uses additive drawer listeners. The callback is enabled only while the START drawer is open; when closed, the real AndroidX dispatcher's ordinary Back fallback remains available. Open/close events and layout changes synchronize state, including an open drawer restored before its first layout without an open event. The old `onBackPressed` override was removed; other Activity lifecycle bodies remain unchanged.
- `ListEditActivity` inflates exactly one selected layout and binds literal IDs in its corresponding branch. Scripts bind only alias/command; absent host-only fields remain unused/null as before. Host bindings, existing record prefill, titles, and save/delete/persistence callback bodies are preserved.
- Added **6 `DrawerBackCallbackTest` cases** using the real AndroidX dispatcher/lifecycle registry with mocked drawer views: closed fallback, initially open drawer, open/close enablement, unrelated drawer events, restored-open first-layout synchronization, and START/STOP/DESTROY registration behavior.
- Added **4 `ListEditActivityDialogTest` cases** executing the production dialog method with mocked Android UI boundaries: New/Edit host and New/Edit script. They assert the exact inflated layout/lookup IDs, no extra inflation/lookups, preserved title/prefill, and no script access to host-only authentication widgets. These are JVM regressions, **not device UI or real-gesture tests**; lint also validates the actual layout resources.

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --continue --no-build-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:assembleDebug
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump xmltree \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --file AndroidManifest.xml
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging \
  app/build/outputs/apk/debug/app-debug.apk
```

**Results (2026-10-20): BUILD SUCCESSFUL, exit 0, 59s; 77 actionable tasks, 17 executed / 60 up-to-date.** Unit tests and all three lint analysis tasks executed with build-cache reuse disabled. Both APK assembly tasks succeeded; unchanged instrumentation compilation/packaging was up-to-date from the earlier successful build.

- Reviewed all **5 unit-test XML suites: 39 tests, 0 failures/errors/skips**: original 22 custom-command + 6 ydotool + 1 arithmetic cases, plus the 10 focused regressions above. Existing command-test sources/assertions remain unchanged.
- Reviewed `app/build/reports/lint-results-debug.xml`: **0 errors/fatal findings, 155 warnings**; the three original errors are absent. Warnings are not suppressed or baselined. Both pre-existing null-root inflation sites are now visible as **2 `InflateParams`** warnings (previously 1), explaining the total increase from 154. The existing 9 `StaticFieldLeak`, 5 `Recycle`, and 2 `ScopedStorage` findings remain visible for their planned lifecycle/transport/storage work.
- Reinspected the actual APKs: application `com.stripe1.xmouse`, version **25 / 2.0.27**; instrumentation `com.stripe1.xmouse.test` targets that application using **`androidx.test.runner.AndroidJUnitRunner`**. Both retain SDK **24 / 37 / 37**. **No instrumentation/device execution or API 36/37 gesture behavior is claimed.**
- A baseline comparison against `aa6f18de7394ba17f46aad9badac164171d1ff1e` confirmed that `MainActivity` differs only in the approved drawer listener/Back registration and override removal, and `ListEditActivity` differs only in dialog binding setup. Menu/command/lifecycle bodies and dialog save/delete/persistence bodies are unchanged. `git diff --check` passes.
- `docs/verification/audit-resource-actions.py` is deliberately **unchanged**. Its task-1.4 whole-source equality check no longer applies after these approved changes; it is not counted as current feature coverage or weakened to pass. The new focused tests provide Back/binding regression coverage.

Build warnings remain: deprecated Jetifier/Java APIs and the test JVM's bootstrap-classpath/CDS message; none is hidden. Full successful log: `/tmp/opencode/xmouse-task-1.5-approved-verification.log`; APK evidence: `/tmp/opencode/xmouse-task-1.5-{test-apk-manifest,app-badging}.txt`. **Only 1.5 is newly checked (overall 5/67); 10.2 is still unchecked.**
