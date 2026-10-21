## Context

See `proposal.md` for motivation and the five capability specs for the behavior contracts. This design is required because the work changes lifecycle ownership, security, dependencies, and persistent data across several existing components.

Observed implementation:

| Area | Evidence and consequence |
| --- | --- |
| Build | `app/build.gradle` compiles/targets API 31 with minimum API 14, AGP 7.2.2, and Gradle 7.3.3. Root repositories include JCenter. |
| Runtime | `MainActivity.java` owns static connection/backend/UI fields; `onCreate` constructs the handler and `onDestroy` disconnects. |
| Auto-connect | `MainActivity.onResume` calls `xMouseTryConnect`; that method disconnects an already connected session. |
| Transport | `MyConnectionHandler.java` holds activities, dialogs, and direct UI references; uses `AsyncTask`; disables strict host-key checking; has no enabled keepalive policy. |
| Hosts | Host input is already arbitrary text, despite the IP label. `HostItem` and database rows store strings; no IP-only validator or discovery implementation was found. |
| Secrets/files | Host passwords and a retained key passphrase are plaintext database/preferences values. `SettingsActivity` stores a raw external key path using `FileDialog` and legacy storage permissions. |
| Database | `DatabaseHandler` uses database `contactsManager`, version 2. Both upgrade and downgrade handlers drop the host and script tables. |
| Layouts | `MainActivity` reads/writes one active file and three fixed presets. Both comma and `<xmousesep>` formats exist; button state is ordered title/command/span/color data. |
| Rendering | `CustomKeyboardButtonAdapter`, `SpanVariableGridView`, and `CoolDragAndDropGridView` combine remote execution and legacy drag behavior. The grid has ten columns and fixed-height buttons. |
| Input | Gyro sampling starts at creation without lifecycle cleanup; touch cancellation does not reliably release remote held input. |
| Tests | Existing unit tests cover selected command translations. Instrumentation consists of an obsolete application smoke test; lifecycle, permissions, trust, migrations, and editing are not covered. |

The user initially confirmed Android 7 / API 24 as the new minimum. After task 4.1 diagnostics proved that the maintained JSch ECDSA path fails there because no default `AlgorithmParameters.EC` service exists, the user approved Android 8 / API 26 instead, conditional on the unchanged dependency migration passing an actual API 26 crypto/import gate. That gate passed on 2026-10-20. The current minimum is API 26; API 24 remains a documented historical failure, not a compatibility target. This is an explicit compatibility amendment, not an implementation default. The configured SDK directory contains stable API 36 and API 37.0 platforms; the shell provides JDK 21. No Android build was run during the original planning.

## Goals / Non-Goals

**Goals:**

- Give the session, host identity, and layout data owners lifetimes independent of activity instances.
- Establish testable boundaries around blocking SSH, address resolution, persistence, and editor transformations.
- Keep the app local-only and maintain Java interoperability during incremental Kotlin adoption.
- Make migrations recoverable and failure states explicit rather than overwriting configuration with defaults.

**Non-Goals:**

- Changing desktop command semantics or replacing the existing touchpad/joystick implementation wholesale.
- Keeping Android alive indefinitely, requesting battery exemptions, or treating discovery as authentication.
- Converting unrelated Java/settings screens to Compose merely for consistency.

## Decisions

### 1. Upgrade the platform deliberately; introduce Kotlin only at new boundaries

Set minimum API 26 and compile/target API 37. Pin a stable, mutually compatible AGP/Gradle/Kotlin/Compose/AndroidX matrix that supports that range; avoid adopting a newer library that silently requires an even higher minimum or compile SDK 37.1+. Use the installed JDK 21 only with a compatible Gradle runtime, and align Java/Kotlin bytecode targets. Do not attempt the old Gradle 7.3.3 wrapper under JDK 21 as a meaningful baseline.

Replace JCenter with Google/Maven Central, define the Android namespace, and address modern AGP behavior such as non-final resource IDs in existing Java `switch` statements. Keep the application ID unchanged. Replace the obsolete instrumentation setup and restore meaningful lint checks rather than suppressing all failures.

Use Kotlin for the session owner, resolver/discovery adapters, protected storage, layout model, and editor. Use Compose for the new editor only. Existing Java control surfaces and xdotool/ydotool adapters remain behind a small Java-callable command boundary. Any temporary compatibility bridge must not store an activity or a View in the session owner.

**Alternative:** a whole-app conversion increases gesture and command regressions without delivering these features sooner. A Java-only editor is viable but less attractive for the new stateful editor after the agreed minimum-version change.

All Android tooling commands use:

```text
ANDROID_HOME=/home/homoludens/Android/Sdk
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
```

Pass these in the environment. Do not commit these absolute paths or generate project configuration merely to record this machine's location.

### 2. Own a requested connection in a started-and-bound foreground service

Create one Kotlin session service/controller, started by a visible, user-requested Connect operation, including foreground auto-connect. It owns transport, cancellation, connection intent, and an observable state stream; activities bind/unbind and render state only. Publish the foreground notification promptly before blocking resolution/SSH work.

Use the `connectedDevice` foreground-service type for the active network-device session. Declare its general and type-specific foreground-service permissions, `INTERNET`, `ACCESS_NETWORK_STATE`, and the documented network-device prerequisite `CHANGE_WIFI_MULTICAST_STATE`, which is also used by the bounded legacy mDNS fallback below. Request notification visibility permission where applicable, explain a denial, and retain the platform's required foreground-service disclosure rather than treating notification denial as permission to hide the session. On API 37, obtain `ACCESS_LOCAL_NETWORK` before manual/direct local access. Gate all API-specific behavior on the running OS.

Do not wait until the app is already backgrounded to start the service. Use a non-sticky service, stop it on deliberate task removal, and do not register boot restart. Explicit Disconnect/Exit clears connection intent, closes transport, cancels pending work, and removes the notification. Ordinary activity destruction merely detaches its observer. An explicit disconnect suppresses auto-connect for that session until a deliberate new user connection request, even when the saved auto-connect preference remains enabled.

```text
Java control screen / Kotlin editor
                |
        bind + observe state
                v
       Session service/controller
       |          |           |
   resolver    SSH transport   notification
                  |
        ordered command sink
                  |
       existing xdotool/ydotool adapters
```

**Alternative:** a ViewModel alone survives some screen recreation but cannot own a reliable long-running background session. WorkManager is not an owner for an interactive SSH socket. A foreground service is justified for the user-requested continuing device session, not as a generic process-keepalive hack.

### 3. Separate idempotent connection intent from explicit disconnect

Replace the connect/disconnect toggle with `connectIfNeeded(host)` and `disconnect()`. Serialize ownership transitions; associate each attempt with a generation token so cancelled resolution/handshake callbacks cannot attach to a newer host. Repeated requests for the same connected/connecting endpoint are no-ops; a deliberate host change cancels and closes the old session first.

Expose resolving, connecting, awaiting trust, authenticating, connected, and disconnected/error states. Treat the session as ready only after its command channel is usable. Include bounded resolution, TCP/SSH handshake, channel setup, and command-execution timeouts. Use conservative SSH keepalives with a finite missed-response count; they detect failures but do not bypass Android idle restrictions.

Run blocking SSH and command writes off the main thread. Drain remote stdout/stderr so an unconsumed shell does not stall command delivery. Serialize commands, discard unsent interaction events on transport loss, and never replay an old click, shortcut, or script on reconnect. Keep state transitions and trust/authentication prompts separate from transport callbacks and direct UI manipulation.

On involuntary failure, retain the desired host and allow one fresh foreground recovery attempt on return, provided required permissions are available. Repeated failure leaves a visible Retry action instead of an endless background reconnect loop. Never restart after an explicit disconnect or force-stop. Screen-off survival is best effort and is not an acceptance guarantee.

Suspend remote input when the control surface loses activity: unregister gyro listeners, cancel gesture recognition, and release tracked held inputs while the transport is usable. Keep local held-input state outside any surviving View, clear it on loss, and handle `ACTION_CANCEL`. Make gyroscope hardware optional in the manifest.

**Alternative:** merely deleting the activity's disconnect call leaves static ownership, stale callbacks, and background execution limits unresolved.

### 4. Prefer native hostname resolution; supply bounded `.local` compatibility

Represent a manual endpoint as its original host string plus validated port; preserve the display string while normalizing only its trust/lookup identity. Resolve anew for each connection, retaining IPv6 scope identifiers and all useful current address candidates. Try reachable candidates within an overall deadline; authentication or host-key failures stop the attempt instead of being hidden by address retries.

Use the platform resolver first. Modern Android's resolver can resolve `.local` directly, but this cannot be assumed on every API 26 device. Isolate a query-only RFC 6762 mDNS fallback for `.local` A/AAAA lookups when native resolution is unavailable. Bind it to the intended accessible local network, make it cancellable, strictly bound packet/compression parsing and response matching, and hold a Wi-Fi multicast lock only during the lookup when needed. Use a reviewed Android-compatible DNS codec rather than implementing an unrestricted recursive resolver. The fallback must work without an SSH DNS-SD advertisement; do not misuse a service resolver as a bare-hostname resolver.

There is no subnet port scan, router reconfiguration, location/Wi-Fi scan request, or perpetual multicast listener. Respect the active network/VPN constraints; report local resolution unavailability rather than silently routing around a VPN policy. Multicast-isolated or routed networks remain outside reliable mDNS operation and require manual reachable DNS/IP entry.

**Alternative:** adding a general-purpose discovery library for every modern hostname lookup adds battery/dependency cost and still does not solve authentication or network isolation.

### 5. Discover SSH services on demand and retain resolvable identities

Use Android `NsdManager` for `_ssh._tcp` discovery. Keep its ownership tied to the visible discovery flow, not the session service; stop discovery on leaving/backgrounding without affecting an established session. Keep the legacy API 24 resolution-callback adapter (still needed at the API 26 floor) and newer service-info callbacks behind one interface. Use modern address lists where available and deduplicate results by network, service instance, type, and domain.

Selecting a result opens host configuration with its advertised port and available endpoint data; the user supplies/chooses authentication and explicitly saves it if desired. Prefer a stable advertised target hostname when the API exposes one. Older NSD APIs may expose only addresses: preserve the service instance/type/domain as a discoverable locator and re-resolve that service on future connections rather than presenting a DHCP address as a stable hostname. Network handles and resolved addresses are attempt-local observations, not permanent profile identities.

Add locator metadata to existing host persistence without renaming or losing existing rows. A profile's trust identity is the normalized manual host plus port, or the saved logical service identity plus port when no stable hostname exists. Explicit host-key approval remains required if that identity changes. A service instance with the same name and a different key is not trusted automatically.

Provide an empty-state explanation and manual entry. README instructions should show how to advertise SSH with Avahi and explain that SSH being installed/running does not necessarily publish `_ssh._tcp`. The Android 17 system picker is a possible future alternative, but manual names/IPs require the direct LAN-permission path in this release; do not implement two competing discovery UIs now.

**Alternative:** persist discovery's numeric address only. This fails the user's goal when DHCP changes and loses the relationship between the service selected and the endpoint trusted.

### 6. Modernize SSH trust and secret handling without rewriting desktop backends

Replace `com.jcraft:jsch:0.1.55` with a pinned maintained `com.github.mwiede:jsch` release that is verified on the minimum supported Android runtime. Keep exactly one JSch implementation. Replace the old SpongyCastle provider setup with only the maintained crypto dependencies actually needed by the supported algorithms; test RSA SHA-2, current host-key negotiation, ECDSA, passwords, and encrypted-key loading before removing the old path.

Persist approved SHA-256 host-key fingerprints/full keys in an application-private trust repository keyed by logical endpoint and port, independent of resolved IP. Unknown keys create a cancellable approval request before authentication; mismatches are hard failures with a separate explicit replacement action. A pending prompt with no visible UI waits within a deadline or cancels; it never auto-accepts from a background callback.

Use Android Keystore-backed AES-GCM envelope encryption for saved passwords, private-key bytes, and any retained passphrase; use unique nonces and versioned ciphertext. Keystore protects the wrapping key, not an assumption that arbitrary imported SSH keys can be directly imported as hardware SSH identities. Supply decrypted bytes to the SSH library in memory rather than retaining plaintext key files. Keep encrypted secrets outside automatic backup, and configure both legacy backup rules and current data-extraction rules so neither migration intermediates nor old credential sources leak into backups. Non-secret hosts/scripts/layouts can remain eligible for backup; loss of a device key requires credential re-entry. Never log typed content, authentication material, or private-key contents.

Use Android's document picker to read, validate, encrypt, and atomically commit an imported key. Cancelled/invalid imports keep the previous key. Existing raw paths that are still readable can migrate; inaccessible ones need an explicit re-import message, not a broad-storage-permission workaround.

**Approved task-4.1 hardening amendment (2026-10-21):** the user approved `[high]` routing after JSch
2.28.8 attempted an allocation from an unchecked ASN.1 length during wrong-passphrase key decryption.
The earlier API 26 minimum amendment remains valid; changing the floor or heap size is not a parser fix.
Use one `BoundedSshKey` boundary for import, saved-key reads, repository authentication, attempt-only
key re-entry, and the final legacy SSH identity handoff. It uses maintained BC 1.86 PEM decryption,
ASN.1/key parsing and OpenSSH utilities before JSch sees any key material. JSch receives only canonical,
unencrypted in-memory output produced from the parsed key, never the untrusted source or decrypted
malformed structure. Prove private-key usability by signing and verifying a synthetic message.

Keep the original imported bytes encrypted at rest with the existing opaque reference/publication
protocol. Normalization is attempt-local only; do not replace stored records, reset a lost device key,
persist plaintext keys, alter global authentication choices, register a global provider, or enable old
SSH algorithms. A later incorrect passphrase must go through the boundary again. Temporary owned byte
arrays are cleared best-effort; immutable library objects and VM/provider copies are not guaranteed
zeroizable. No private-key/parser/provider content is retained in error messages.

The resource policy is explicit: document bytes remain capped at 1 MiB, decoded key structures at 16 KiB,
passphrases at 1024 raw bytes, definite DER at 16 constructed levels/128 nodes and 2049 bytes per INTEGER
(up to 16384-bit RSA), with length/remaining-input checks before parsing. Supported EC curves are NIST
P-256/P-384/P-521. OpenSSH outer SSH strings and the single-key envelope are checked before BC parsing;
BC checks private strings against remaining decrypted bytes before copying. Bcrypt rounds are 1..64 with
16..64-byte salts. PKCS#8 PBES2 permits AES-CBC with PBKDF2 (1..600000 rounds, SHA-1/224/256/384/512 PRF)
or scrypt (at most 32 MiB primary memory, `N*r*p <= 2^20`, `r <= 8`, `p <= 16`), and 8..64-byte salts.
Traditional RSA/SEC1 EC PEM permits plain or DES-EDE3/AES-128/192/256-CBC-encrypted input; passphrase bytes
are preserved without UTF-8 round trips. PKCS#8 and OpenSSH RSA/EC/EdDSA use the same boundary where
supported by the maintained parser; other containers/ciphers/KDFs are explicitly unsupported, not passed
through to JSch. These limits are validation errors, never permission to overwrite an existing key.

Verify deterministic malformed plain and decrypted structures without invoking the unsafe old parser or
allocating their declared lengths, fixed encrypted wrong-passphrase fixtures, previous-key preservation,
and every authentication entry point on API 26 and API 35. Keep the historical OOM evidence separate from
the new regression fixtures; the exact earlier randomized input was not retained.

**Alternatives:** disabling verification to make discovery easier exposes credentials to impersonation. Replacing SSH entirely expands desktop deployment scope without solving these user requests.

### 7. Migrate existing hosts and secrets in place, without dropping tables

Retain `contactsManager` and stable host/script IDs. Replace destructive upgrade/downgrade logic with explicit additive/versioned migrations, including locator fields and credential references. Reject unsupported downgrades safely rather than dropping tables. Do not introduce Room solely as part of this change.

Make migration restartable with durable checkpoints: first store and verify encrypted replacements, then transactionally replace database plaintext with references, then remove redundant plaintext preferences. The original credential representation remains until its replacement is durable. Suppress credential backup during migration and apply database checkpoint/compaction where needed to reduce residual plaintext; do not claim guaranteed forensic erasure from flash storage. After successful migration, no active code path writes new plaintext secrets. Handle the existing global key-auth setting and selected-host preference without inventing per-host key choices or losing global configuration.

**Alternative:** a fresh database or uninstall/reinstall is simpler but discards the user's established remote setup. Reusing current `onUpgrade` would also silently destroy scripts.

### 8. Use a versioned layout model and a pure draft editor

Introduce a layout repository with versioned, atomically written UTF-8 JSON profiles. Each profile has a stable ID, a name, ten logical columns, and an ordered button list. Buttons have stable IDs, title, raw command text, width in 1..10, and validated color. Keep fixed-height flow rendering: there are no stored pixel coordinates, explicit sparse positions, or editable touchpad/mouse-button regions.

Build the editor in Kotlin/Compose with a ViewModel holding immutable saved and draft snapshots plus session-local undo/redo. One completed drag/resize gesture is one undoable operation, not an entry per pointer movement. A new edit clears the redo branch. Provide tap selection, visible move/resize alternatives, placement preview, color selection, and validation that keeps invalid drafts open. Save is atomic; Discard restores the saved snapshot; Back with dirty state asks Save/Discard/Cancel. Preserve drafts across screen recreation and retain a recoverable draft if durable save fails.

Profiles support create, rename, duplicate, select, and confirmed delete without a fixed count. IDs, not names, determine storage identity. The editor only edits data: it never invokes the remote command sink. Normal controls consume a saved layout and retain the existing command-dispatch semantics. Keep custom commands as raw text; a typed action-builder or a command-parser overhaul is outside this change.

The legacy reader handles `keyLayoutFile.csv`, `keyFileOne.csv`, `keyFileTwo.csv`, and `keyFileThree.csv`. Assign deterministic migration identities so the active layout and presets remain separate and retries do not duplicate them. Retain valid title/command/span/color values, including the white default for missing color. Do not guess how to reconstruct ambiguous historical comma records; keep malformed sources with a warning. Commit validated new profiles before recording migration success. Retain source layout files as recovery artifacts; do not let the old `onPause` autosave overwrite them after migration. Separate source-file migration status from ordinary user-created profiles.

**Alternatives:** raw text editing leaves the usability problem intact. A freeform dashboard requires a new geometry model and device-size policy the user did not request. Rewriting every command into structured actions risks altering custom shell behavior.

## Risks / Trade-offs

- [Minimum-version change] -> API 26 was explicitly approved on 2026-10-20 after the API 24 ECDSA failure and successful actual API 26 gate; document the break and do not increase it again implicitly through dependencies.
- [Foreground service is not immortal] -> make session state honest, support foreground recovery, and test app switching separately from non-guaranteed screen-off behavior.
- [Permission or notification denial] -> distinguish LAN denial from SSH failure; explain system session visibility and keep explicit controls to stop a session.
- [Legacy devices lack native mDNS] -> use a short-lived, cancellable `.local` fallback and test it on API 26; never claim service discovery alone resolves arbitrary hostnames.
- [Discovery advertisements or multicast unavailable] -> retain manual DNS/IP entry and explain host/network prerequisites.
- [Remote key/crypto compatibility changes] -> verify existing RSA/ECDSA/password flows against current OpenSSH, without globally enabling obsolete algorithms.
- [Partially migrated data or storage failure] -> use additive transactions, verified encrypted replacements, deterministic layout IDs, atomic writes, and fault-injection tests.
- [Downgrade damage] -> block unsupported schema downgrades; do not claim that installing the legacy binary after migration is a safe rollback.
- [Legacy command quirks] -> preserve existing translation regression tests and raw command text; test an SSH stub to prove editor gestures cannot execute commands.
- [Foreground input leakage or stuck drag] -> release tracked inputs, handle cancellation, unregister sensors, and clear unsent inputs on loss.

## Migration Plan

1. Add characterization fixtures and tests from the actual database/preferences/layout formats before altering storage or lifecycle behavior.
2. Modernize and pin the build for API 26..37 with the specified SDK environment. Establish an executable unit/instrumentation/lint baseline.
3. Introduce protected storage, trust handling, document import, and additive migrations behind the new repositories. Verify failure/restart behavior before enabling migrated writes.
4. Introduce the session service and adapt the existing remote controls through the command boundary. Remove activity-owned session shutdown and toggle-based auto-connect only after lifecycle regressions pass.
5. Add native/fallback hostname resolution, permissions, and visible on-demand SSH discovery. Re-resolve stable manual/service locators on new attempts.
6. Migrate layouts and introduce the independent editor/profile picker. Retire old editor entry points and autosave only after successful migration, not before.
7. Verify API 26, 31, 33/34, 35, 36, and 37 behavior as available, plus a real-device/Linux LAN matrix. Document any unavailable test environment rather than marking it passed.

Keep application identity and non-secret data paths stable. Recovery uses retained layout sources and a forward-compatible migration/restore path; encrypted secrets are not backed up in plaintext as a rollback mechanism. Any release rollback requiring an older database schema needs an explicit conversion path and is not provided by installing the old APK over migrated data.

## Verification Strategy

- Pure tests: endpoint/port validation, state-machine idempotence and cancellation, trust matching, error categories, IPv4/IPv6 candidates, bounded mDNS packet parsing, layout reducers/history/serialization, and legacy fixtures.
- Instrumentation: service ownership across activity recreation/app switching, pending trust cancellation, permission denial/revocation, notification disconnect, document picker results, key invalidation, database migration checkpoints, editor no-execution behavior, and dirty-state Back navigation.
- Real LAN: manual IPv4/IPv6/DNS/`.local`, `.local` without an SSH advertisement, advertised SSH on a non-default port, service disappearance, DHCP changes, current OpenSSH RSA/ECDSA/password auth, and host-key mismatch.
- App-switch acceptance: leave a healthy session for another foreground app for at least five minutes with the display on and network stable, return, and confirm the same authenticated session is reused. Also test auto-connect enabled, an activity recreation, leaving during a drag, and explicit notification disconnect. Screen-off persistence is not a pass/fail promise.

## Reference Constraints

- [Android resolver and native `.local` support](https://source.android.com/docs/core/ota/modular-system/dns-resolver)
- [Network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Connected-device foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 16 edge-to-edge and Back behavior](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Maintained JSch fork](https://github.com/mwiede/jsch)
