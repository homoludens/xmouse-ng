# Connection domain values (task 2.4)

Verified **2026-10-20**, baseline `1071588`. New pure Kotlin values live in
`app/src/main/java/com/stripe1/xmouse/connection/`; existing Java runtime paths are unchanged.

## Boundary and handoff

- `Endpoint` is a validated locator plus port **1..65535**, with Java-static `manual` factories
  for integer/string ports and `parsePort`. Decimal string ports allow surrounding spaces/leading
  zeroes, not signs, fractional values, non-ASCII digits, or overflow.
- `EndpointHost.original` retains display input. `normalized` supplies an IDNA ASCII, lower-case,
  root-dot-independent DNS identity, decimal IPv4, or eight expanded lower-case IPv6 groups.
  `lookupHost` retains a DNS root dot so an absolute lookup need not become a search-domain lookup.
  IPv6 brackets are optional; embedded IPv4 and equivalent hex spellings normalize alike.
  Explicit scopes survive normalization: interface-name case is significant, numeric scopes use
  decimal. Short/ambiguous IPv4 forms and octets with leading zeroes are rejected. Blank hosts,
  invalid labels/literals, URIs, credentials, and host:port pairs are rejected. No DNS, reverse-DNS,
  interface, or other network lookup occurs during construction.
- `EndpointLocator.DiscoveredService` retains the actual NSD instance/type/domain, with optional
  **advertised** stable target hostname. A service instance is a literal UTF-8 label (maximum
  63 bytes), not a hostname assembled from a display name. ASCII instance casing, SSH TCP type
  casing/trailing dot, and domain casing/trailing dot normalize for identity. Only `_ssh._tcp`
  is supported. Numeric addresses cannot be supplied as a stable target hostname.
- Use **`Endpoint.identity` equality**, not display-bearing locator/endpoint equality, for logical
  endpoint comparison. A manual locator or saved stable service target uses host + port; an
  address-only NSD locator uses the structured service tuple + port. No IP observation, network
  handle, username, password, key, or passphrase enters that key. Resolved candidates and discovery
  deduplication by network remain attempt/flow-local. Changing/removing a **saved** target or changing
  the host/service/port changes identity; do not silently replace saved locator metadata with whatever
  a later NSD callback happens to expose. These values do not grant trust, migrate trust aliases, or
  define a persistent key encoding; do not store their diagnostic `toString()` as a database schema.
- `ConnectionState` supplies immutable resolving, connecting, awaiting-trust, authenticating,
  connected, disconnected, and error snapshots. Base `getPhase()`, `getEndpoint()`, `getFailure()`,
  and `isReady()` are callable from Java without exhaustive sealed-class matching. Only Connected
  represents a usable command channel. Disconnected can retain the intended endpoint; Error always
  retains it with a typed failure. Observation delivery/transitions belong to the future controller.
- `ConnectionFailure` separates permission, resolution, transport, trust, and authentication, with
  typed reasons including revocation, refusal/unreachable, key mismatch/rejection, and invalid
  passphrase. It carries no arbitrary exception, message, authentication material, or logging callback.
  Future adapters must classify failures without forwarding sensitive library exception text.

No session service, resolver/discovery adapter, SSH/trust implementation, persistence/schema migration,
UI wiring, or lifecycle/authentication behavior is implemented here. In particular, these snapshots do
not prove trust-before-authentication ordering or controller cancellation; those tasks remain pending.

## Verification

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging \
  app/build/outputs/apk/debug/app-debug.apk
```

Final run: **BUILD SUCCESSFUL, 1m5s**, 53 actionable tasks (16 executed / 37 up-to-date).
Kotlin production/test and Java interoperability-test compilation, all tests, all lint analysis tasks,
and APK packaging executed. No new Kotlin compiler warnings. Full XML results: **120 tests across
12 suites, 0 failures/errors/skips**; **51 new domain cases** (19 host, 20 endpoint, 9 state/failure,
3 Java interop). Lint: **0 errors/fatal findings, 155 existing warnings**, none in the new domain
sources. No suppression/baseline or SDK/toolchain/dependency changes.

### Standalone JVM check (zsh, after the build)

```sh
set -euo pipefail
JARS="$HOME/.gradle/caches/modules-2/files-2.1"
JUNIT_JAR=("$JARS"/junit/junit/4.13.2/*/junit-4.13.2.jar)
HAMCREST_JAR=("$JARS"/org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar)
KOTLIN_JAR=("$JARS"/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/*/kotlin-stdlib-2.4.21.jar)
CP="$JUNIT_JAR[1]:$HAMCREST_JAR[1]:$KOTLIN_JAR[1]"
MAIN=app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes
TEST=app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes
OUT=$(mktemp -d /tmp/opencode/xmouse-task-2.4-pure-jvm.XXXXXX)
javac --release 17 -encoding UTF-8 -cp "$MAIN:$CP" -d "$OUT" \
  app/src/test/java/com/stripe1/xmouse/connection/ConnectionDomainJavaInteropTest.java
java -cp "$MAIN:$TEST:$OUT:$CP" org.junit.runner.JUnitCore \
  com.stripe1.xmouse.connection.EndpointHostTest \
  com.stripe1.xmouse.connection.EndpointTest \
  com.stripe1.xmouse.connection.ConnectionStateTest \
  com.stripe1.xmouse.connection.ConnectionDomainJavaInteropTest
```

**Passed: 51 tests**, with only the compiled Kotlin values/tests, freshly compiled Java caller,
Kotlin stdlib, JUnit, and Hamcrest on the classpath: **no Android JAR, Mockito, JSch, or network**.
DHCP/network-change tests use test-only observations, not a claim of working resolution/discovery.
Tests also cover invalid input, normalized identity/hash equality, service-only/stable-target locators,
IPv4/IPv6/scopes, and distinct failure categories. JDK **21.0.7**; all 34 domain class files and the
Java caller have bytecode major **61** (Java 17). APK metadata remains application ID
`com.stripe1.xmouse`, version **25 / 2.0.27**, SDK **24 / 37 / 37**.

Logs: `/tmp/opencode/xmouse-task-2.4-full-verification-final.log` and
`/tmp/opencode/xmouse-task-2.4-pure-jvm.log`. Existing Jetifier and test-JVM CDS warnings remain.
No device/instrumentation, LAN, or live-SSH verification was run or claimed. Only **2.4** is completed;
no staging or commit was performed.
