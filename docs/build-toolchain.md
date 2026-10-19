# Android build toolchain selection

Verified **2026-10-20** for `modernize-xmouse` task **1.1**. This is the selection/handoff, not a build migration: task 1.3 applies the pins, 1.4 compiles the existing application, and 1.5 updates its tests/lint.

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

## Handoff constraints

- **Task 1.2:** Android commands must set both SDK variables shown above. The installed stable platform is `android-37.0`, revision 2, `PreviewSdkInt=0`; the probe accepted `compileSdk = 37`. Do not commit `sdk.dir`, `org.gradle.java.home`, or absolute machine paths in build configuration.
- **Task 1.3:** Keep application ID and namespace **`com.stripe1.xmouse`**. Use AGP 9's default [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin); do **not** also apply `org.jetbrains.kotlin.android`. Pin the higher KGP through a root `buildscript` classpath as [documented by AGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version), alongside the matching Compose compiler plugin. Keep the new DSL; use `kotlin.compilerOptions`, not `android.kotlinOptions`. Replace `proguard-android.txt` with `proguard-android-optimize.txt`, which AGP 9 requires. None of these edits were made in task 1.1.
- Wrapper distribution: `https://services.gradle.org/distributions/gradle-9.3.1-bin.zip`; [official SHA-256](https://services.gradle.org/distributions/gradle-9.3.1-bin.zip.sha256): `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`. Regenerate/verify wrapper files using compatible Gradle, not by executing 7.3.3 on JDK 21.
- **Tasks 1.4/1.5:** Preserve non-final resource IDs and adapt Java switches rather than hiding the migration. Replace blanket lint suppression and obsolete instrumentation; the selected runner is `androidx.test.runner.AndroidJUnitRunner`. Existing Mockito/JUnit and SSH/SpongyCastle dependencies were **not** validated or replaced by this probe; their migration/verification remains with the corresponding tasks.
- Do not use unsupported-compile-SDK suppression, manifest minimum overrides, a higher minimum, or API 37.1 to force dependency acceptance. Kotlin 2.4.21 and Gradle's embedded Kotlin 2.2.21 are different components. Device coverage on API 24 and 37 and the actual XMouse build/lint/instrumentation suites are **not run by task 1.1**.
