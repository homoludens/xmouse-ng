# Query-only `.local` fallback (task 6.4)

Baseline: `0b49433`, reviewed 2026-10-22. Scoped to **6.4 [high]**; no staging, commit,
delegation, discovery UI, or task 6.5 completion. API minimum/compile/target remain 26/37/37.

## Resolver contract

`AndroidNetworkAddressResolver` first calls the active, permission-checked Android
`Network.getAllByName`. Only `UnknownHostException` (including an empty native answer) for an
explicit `.local` hostname enables fallback. Ordinary DNS/IPs, permission/route failures, and
other native failures do not enable it. Attempt cancellation, permission, and active-network
identity are checked again before fallback, throughout it, and before publishing candidates.
The existing overall 10-second resolution budget includes native lookup and fallback; it is
not restarted. Trust/authentication failures remain terminal in the unchanged SSH candidate flow.

The fallback implements the **one-shot legacy-unicast** mechanism in RFC 6762 sections
[5.1 and 6.7](https://www.rfc-editor.org/rfc/rfc6762): send one IN A query and one IN AAAA query,
with distinct nonzero transaction IDs, from one ephemeral UDP port (never 5353) to
`224.0.0.251:5353` and/or interface-scoped `ff02::fb:5353`. Replies go directly to that port;
there is **no multicast group membership or multicast receive listener**. Outgoing hop limit
is 255. No PTR/SRV/TXT/ANY queries, `_ssh._tcp` dependency, advertisement, credentials,
SSH-service probe, subnet scan, cache, retry loop, or background thread is introduced.

The temporary socket is bound to the same Android `Network` and interface. Initial network
policy permits only active non-VPN Wi-Fi/Ethernet; it never selects an underlying network to
evade a VPN. Source UDP port must be 5353 and source address must be usable unicast and within
an interface prefix. Java's datagram API does not expose received IP hop limits; on-link
source validation and network binding are used instead (RFC 6762 section 11). The OS directs
unicast replies to the temporary socket's port; unsolicited multicast replies are not consumed.
An unavailable multicast family does not discard successfully sent queries on the other family.
Link-local AAAA candidates acquire the interface's scope ID. Results are bounded and attempt-local;
the original logical hostname, port, saved profile, and trust identity never become an IP address.
mDNS answers are **not authentication**; existing explicit SSH host-key checks still apply.

## Dependency and source review

Pinned runtime dependency: **`dnsjava:dnsjava:3.6.5`**, BSD-3-Clause, released 2026-05-23.
Its only mandatory runtime dependency is `org.slf4j:slf4j-api:1.7.36`; JNA is optional and
is not added. The published POM targets Java 8 and its Java-8 profile checks against the
Android API-26 signature. Actual API-26 device verification remains **not run** here.

Reviewed the exact Maven Central
[source artifact](https://repo.maven.apache.org/maven2/dnsjava/dnsjava/3.6.5/dnsjava-3.6.5-sources.jar),
not just API documentation: `Name`, `DNSInput`, `Record`, `ARecord`, `AAAARecord`, `Message`,
`Header`, `Type`, and `Compression`. Source JAR SHA-256:
`5ebbe5472015fcbd3ae6849e733af67442d0f8bd54c2feadb2ec0fc74895afce`.
Binary JAR SHA-256:
`d1208e39e4ba17ec643ac5832b8b17dcaf5e3ae28f8c65904a755e23523cd94a`.

- `Name(DNSInput)` decodes iteratively, rejects forward/self compression pointers and bad label
  encodings, and bounds expanded names. It does not provide our chosen eight-pointer limit or
  validate pointer targets as known label boundaries. An iterative structural guard therefore
  checks those limits and cycles **before** calling it; the guard does not decode names or
  implement recursion. dnsjava supplies name decoding and DNS case-insensitive name equality.
- `DNSInput` checks remaining input before reads/allocation. `Record.newRecord` bounds the
  active RDATA region and requires its parser to consume it exactly. Our envelope allows only
  the fixed-size A/AAAA parsers (4/16 bytes) to run. Other RDATA is skipped by checked length,
  never sent to generic record parsers. Addresses use `getByAddress`, not a new name lookup.
- `Message` is used only to serialize our tiny questions. There is **no untrusted full-message
  parse**, `Lookup`, `SimpleResolver`, DNSSEC processing, cache, or dnsjava network I/O.
  The APK excludes both bundled global InetAddress service-provider registration files.
  AGP 9.1.1's default `/META-INF/services/**` merge wins over excludes (confirmed in
  `ParsedPackagingOptions.getAction`); the build replaces that wildcard with the app's existing
  crypto/coroutine descriptor merges so the DNS-specific excludes actually take effect.
- The [upstream changelog](https://github.com/dnsjava/dnsjava/blob/master/Changelog) records
  historical name-loop fixes and the lookup/DNSSEC security fixes in 3.6.0. This integration
  uses neither lookup nor DNSSEC paths; source review plus boundary tests is not a claim that
  all library code has been independently audited.

## Explicit limits and unsupported behavior

| Boundary | Policy |
| --- | --- |
| Fallback deadline | 1,500 ms monotonic, including open/send/receive/parse; never renewed by packets |
| Cancellation/permission/route polling | At most 50 ms per blocking receive, plus bounded packet processing |
| Datagrams | 12..2,048 bytes; 2,049-byte receive buffer detects oversized/truncated UDP prefixes |
| Questions | Exactly one echoed question, exact DNS name/type/IN class and transaction ID |
| Header | Authoritative QUERY response, no error, truncation, recursion, or reserved flags (`0x8400`) |
| Resource records | At most 32 total across answer/authority/additional, checked before iteration |
| Names/compression | Labels 1..63 bytes, expanded wire name <=255 bytes, <=8 pointers per name; backward validated label targets only; cycles rejected |
| Candidate acceptance | Answer section only, exact owner/type, IN class (cache-flush bit ignored), TTL 1..2,147,483,647, exact A/AAAA size, usable unicast |
| Candidate retention | At most 8 unique scoped addresses; no cache or profile mutation |

All record envelopes/names and the complete datagram tail must validate before any address is
published. IPv4-mapped AAAA records are not converted into A answers. Malformed, over-limit,
unrelated, wrong-source/port, and unmatched datagrams are ignored within the original deadline.
No matching answer yields typed `RESOLUTION_TIMEOUT`, not a TCP/SSH timeout. Cancellation and
revocation discard already received addresses. Successful partial-family results are returned
when the collection deadline expires or the candidate bound is reached; late packets cannot erase
timely answers. Other socket/setup I/O failures remain resolution failures.

Intentionally unsupported: CNAME/DNAME following, NSEC negative caching, referrals, TCP/EDNS,
multi-question responses, pointers into opaque RDATA, multicast-only/non-legacy responders,
raw UTF-8 DNS labels outside the existing IDN hostname policy, cellular/tunnel/non-local links,
routed or isolated multicast, and receiving announcements. Use native resolution or manual
reachable DNS/IP entry on such networks. There is no claim of complete mDNS querier compliance.

The resolver's `use` closes its sole socket on success, timeout, cancellation, and exceptions;
factory setup also closes on failure. No Wi-Fi multicast lock is needed for these unicast replies
and none is acquired. **6.5 still owns dedicated lifecycle hardening/cleanup tests, any needed
Wi-Fi multicast-lock behavior, and actual API-26 network/VPN/device verification.**

## Verification

`MdnsAddressCodecTest` consolidates packet coverage into **one parameterized suite (19 cases)**:
A/AAAA, compressed/uncompressed and case-insensitive names, cache-flush masking, eight-hop
acceptance/nine-hop rejection, invalid pointers/loops/labels/names, byte/count/RDATA/tail limits,
question/owner/type/class/ID/header matching, unusable/goodbye answers, and the candidate cap.
`AndroidNetworkAddressResolverTest` adds **5 tests** with fake datagrams/monotonic time and mocked
Android native resolution: native-first selection/scoped routes, bare-host A+AAAA fallback without
an SSH advertisement, wrong-source/unrelated/malformed filtering, ordinary-DNS/IP exclusion,
silence/flood timeout classification, cancellation, permission revocation, and network changes.
Tests never depend on a real LAN, an SSH advertisement, or an Android device.

Final verification command (all Android tooling sets both SDK variables):

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew --no-daemon --console=plain --max-workers=2 \
  :app:testDebugUnitTest --rerun \
  --tests 'com.stripe1.xmouse.transport.MdnsAddressCodecTest' \
  --tests 'com.stripe1.xmouse.transport.AndroidNetworkAddressResolverTest' \
  --tests 'com.stripe1.xmouse.trust.JschHostTrustIntegrationTest' \
  --tests 'com.stripe1.xmouse.session.SshSessionAttemptFactoryTest' \
  --tests 'com.stripe1.xmouse.session.SessionControllerTest' \
  :app:lintDebug :app:assembleDebug \
  :app:dependencyInsight --dependency dnsjava --configuration debugRuntimeClasspath \
  > /tmp/opencode/xmouse-task64-final-v5.log 2>&1
openspec validate modernize-xmouse --strict
git diff --check
```

Two earlier combined invocations hit the harness's 120-second command timeout, not a code/test
failure. Final commands used a longer allowance. The final combined command passed in **31s**
(54 Gradle tasks: 3 executed / 51 up-to-date), explicitly rerunning the focused tests. Lint and
APK outputs were reused from the completed final-source build in
`/tmp/opencode/xmouse-task64-final-v4.log` (**1m49s**, 7 executed / 47 up-to-date).

| JVM suite | Tests |
| --- | ---: |
| `MdnsAddressCodecTest` | 19 |
| `AndroidNetworkAddressResolverTest` | 5 |
| `JschHostTrustIntegrationTest` | 11 |
| `SshSessionAttemptFactoryTest` | 8 |
| `SessionControllerTest` | 27 |
| **Total** | **70: zero failures/errors/skips** |

Lint: **0 errors/fatal, 145 warnings**, no findings in the five new source/test files, no
new suppressions or baseline. Dependency insight confirms `dnsjava:dnsjava:3.6.5` on
`debugRuntimeClasspath`. APK inspection confirms both DNS provider descriptors are absent,
while the existing BouncyCastle and two coroutine provider descriptors remain present.
Debug APK SHA-256: `0a7f173f32fba8857d7fde1450a6552f46a6248d382ce6e174c944ecf3976334`.

Strict OpenSpec validation, `git diff --check`, and a scope/index/new-file whitespace audit
passed. Only 6.4 is newly checked (**34/67 tasks**); 6.1/6.2 and their evidence remain unchecked
and intact, 6.3 remains complete, and 6.5+ remain unchanged. No staging or commit was performed.
Retained JVM XML: `/tmp/opencode/xmouse-task64-final-jvm/`; lint XML:
`/tmp/opencode/xmouse-task64-final-lint.xml`.

**Not run:** Android instrumentation, API-26 hardware fallback, real IPv4/IPv6 LAN/multicast,
VPN/network-policy integration, or SSH-without-advertisement device tests. No connected device
or configured AVD is available; none was provisioned and no ADB commands were run.

## Task 6.5 resource lifecycle

The fallback remains one synchronous lookup: only an eligible `.local` native-resolution failure
after the current attempt, local-network permission, and active-network checks reaches the socket
factory. `AndroidMdnsSocket.open` rejects a VPN (including a network without `NOT_VPN`) and all
transports other than Wi-Fi/Ethernet before allocating a socket. It binds one ephemeral socket to
the captured Android `Network` and interface, never joins a multicast group, and closes the raw
socket in `finally` if setup/binding fails before ownership is handed to the resolver. The resolver
owns the handed-off socket with `use`, so normal result/timeout, cancellation, revocation, network
change, send/receive failure, and parser/processing exceptions all leave through one close call.
Receive waits remain capped at 50 ms so cancellation and policy changes are rechecked promptly.

No Wi-Fi multicast lock is acquired: this implementation only accepts legacy-unicast replies to
its ephemeral port and has no multicast receive membership/listener. The one-shot query itself is
sent while the explicit lookup is active; there is no background or persistent multicast work.

`AndroidNetworkAddressResolverTest` was extended (not duplicated) to assert a single open and
exactly-once close on success/timeout/cancellation, close on first/second-query send errors and
receive errors, suppression of a close exception without retrying close, setup-failure closure of
the allocated socket, and zero socket allocations for VPN/non-local transports. Existing permission
revocation and active-network-change cases also assert closure and prohibit a subsequent open.
Together with `MdnsAddressCodecTest`, these remain deterministic JVM tests without a real LAN.

On 2026-10-22, the resolver/codec/session suites were explicitly rerun with both configured SDK
environment variables: **62 tests, zero failures/errors/skips** (`MdnsAddressCodecTest` 19,
`AndroidNetworkAddressResolverTest` 8, `SshSessionAttemptFactoryTest` 8, and `SessionControllerTest`
27). `:app:lintDebug` and `:app:assembleDebug` passed in the same Gradle invocation. Strict OpenSpec
validation passed, and `git diff --check` returned no findings.

**Device verification remains not run:** there is no connected device or configured AVD, so API 26
socket/interface behavior and real VPN/network restrictions were not verified. No AVD was
provisioned and no ADB command was run. Task 6.5 remains unchecked until the required API 26 device
behavior is verified; JVM success is not a substitute.
