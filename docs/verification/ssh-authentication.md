# Modern SSH authentication integration (task 4.5 — verified)

Verified **2026-10-21**. Only **4.5 [low]** is newly complete: **22/67 tasks**, with the approved
allocation still **14 high / 53 low**. This was a defined maintained-library integration/test-matrix
task; it did not require a model-routing change. Parent review is pending. No staging or commit.

## Production contract preserved

- `JschSshBackend` keeps the authentication choice explicit. Password mode sets
  `PreferredAuthentications=password,keyboard-interactive` and supplies the password. Key mode sets
  `PreferredAuthentications=publickey` and does not install a password or fall back to one if the selected
  key/passphrase/server authentication fails.
- `HostTrustPolicy` continues to require `StrictHostKeyChecking=yes` against the captured logical endpoint.
  The integration first approves the synthetic host key and then verifies it on every later attempt. No
  credentials or channel/command requests occur before first approval.
- `BoundedSshKey` remains the only production JSch identity-load boundary. Authentication receives
  attempt-local canonical memory output; original encrypted key bytes/references remain untouched.
  Password/key choice and protected credential records are not changed by transport failure.
- No global JSch configuration, provider, SHA-1 `ssh-rsa` signature, DSA, or weak algorithm was enabled.
  The generic connection-failure toast is now actionable but fixed/sanitized: “Connection or authentication
  failed. Check the selected password/key and network, then retry. Existing configuration was kept.” The
  private-key failure remains fixed and directs the user to re-import/check Optional Key Passphrase.

## JVM server-authentication matrix and wire evidence

Three new JVM cases in `JschAuthenticationIntegrationTest` drive the **production** `SshTransport`,
`JschSshBackend`, `HostTrustPolicy`, and final `BoundedSshKey.addIdentity` handoff against an isolated,
in-process **Apache MINA SSHD 2.20.0** server bound to loopback on an ephemeral port. The test-only server
uses a generated ECDSA P-256 host key and synthetic client credentials. Its command handler only records
and consumes the initial command; it never starts a process or executes shell commands.

The server signature-factory allowlist is explicitly only:
`rsa-sha2-256`, `rsa-sha2-512`, and `ecdsa-sha2-nistp256`. It contains neither SHA-1 `ssh-rsa` nor DSA.
The test server's `ServerUserAuthService` parses a copy of each `SSH_MSG_USERAUTH_REQUEST`, recording only
the auth method, public-key request algorithm, embedded signature algorithm, and public-key blob type.
It does not retain usernames, passwords, signatures, or key bytes. For RSA, the accepted 2048-bit key's
blob type is correctly `ssh-rsa`; the independently captured signed request and nested signature both
identify `rsa-sha2-256` or `rsa-sha2-512`. The MINA server accepts that signed request and the production
transport opens its channel and delivers the initial command. This is wire-level authentication evidence,
not a local `KeyPair.sign()` assertion or a check of a client config string.

| Case | Observed result and preservation check |
| --- | --- |
| Password success | Explicit first-host approval is durably completed before auth. The password authenticator accepts the synthetic password, the command recorder receives the initial command, and no public-key request is sent. |
| Wrong password | Server rejects the selected password; transport returns sanitized `CONNECTION`. No public-key request, new channel, or command occurs. The selected synthetic host object and separate source password remain unchanged, and the approved trust snapshot is byte-for-byte identical. |
| Encrypted RSA 2048 success | The production bounded handoff unlocks the generated encrypted RSA private key. MINA records an actually signed `rsa-sha2-256` or `rsa-sha2-512` request (including the nested signature algorithm), while the key blob remains `ssh-rsa`. The server has no SHA-1 RSA signature factory. |
| Encrypted RSA wrong passphrase | Transport returns `PRIVATE_KEY` before any SSH user-auth request. No password fallback/authenticator call, channel, or command occurs, even when the attempt also carries a valid synthetic password. Trust and encrypted source bytes are unchanged; attempt arrays are cleared best-effort. |
| Encrypted ECDSA P-256 success | The existing encrypted SEC1 test fixture passes through `BoundedSshKey`; the server verifies the signed `ecdsa-sha2-nistp256` request and the transport delivers the initial command. No password request is sent. |
| Encrypted ECDSA wrong passphrase | Transport returns `PRIVATE_KEY` before any user-auth packet. No password fallback, channel, or command occurs; trust and source fixture bytes remain unchanged. |
| Selected key rejected by server | A valid but unauthorized synthetic key is selected while a valid password is also present. The server sees only public-key auth; it sees no password/keyboard-interactive request, and no new channel or command is opened. |

The fixture uses synthetic per-attempt `CredentialHost`/`AuthenticationMaterial`, rather than opening the
Android credential database. It verifies the captured selected host and source material/trust snapshots
around failures. Actual protected-record and auth-choice preservation on Android remains independently
covered by `CredentialRepositoryAndroidTest.savedKeyWrongPassphraseFailsBeforeAuthenticationWithoutRewritingCredentials`
and `PrivateKeyImportAndroidTest.storedEncryptedKeysRejectWrongPassphraseAtReadAndFinalIdentityHandoff`.
`MyConnectionHandler` reports a fixed, actionable auth/key error and does not write host selection,
credentials, or trust on transport failure.

### Dependency and source boundaries

- Production stays on one `com.github.mwiede:jsch:2.28.8` with Bouncy Castle **1.86**. There is no
  `com.jcraft:jsch` dependency.
- `org.apache.sshd:sshd-core:2.20.0` occurs only in `debugUnitTestRuntimeClasspath`; it is absent from
  `debugRuntimeClasspath` and `debugAndroidTestRuntimeClasspath`. No test SSH server is packaged in either
  APK.
- `audit-ssh-transport.py` passes. A production-source search finds `KeyPair.load` and raw JSch
  `addIdentity` only in `BoundedSshKey`; the backend sets only the existing password/key authentication
  choices, and the trust policy still sets strict checking.

## Android production-boundary coverage

`SshAuthenticationAndroidTest` runs the actual JSch backend and bounded encrypted ECDSA key handoff on
Android. It checks password vs. selected-key `PreferredAuthentications`, strict host checking remains
`yes`, valid encrypted-key identity publication/removal, and that a wrong encrypted-key passphrase returns
`PRIVATE_KEY` before a socket attempt or trust publication. It also verifies JSch global configuration
and provider ordering are unchanged. This is deliberately not described as a network/server handshake:
the MINA runtime remains JVM-test-only. Actual Android repository/import tests above cover the saved
ciphertext, preference/auth-choice, and wrong-passphrase preservation boundary.

## Final build and device verification

Every Android tooling command used both specified SDK variables. Final uncached command:

```sh
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 docs/verification/verify-v2-storage-fixtures.py
python3 docs/verification/audit-ssh-transport.py
./gradlew --no-daemon --console=plain --max-workers=2 :app:dependencies --configuration debugRuntimeClasspath
./gradlew --no-daemon --console=plain --max-workers=2 :app:dependencies --configuration debugUnitTestRuntimeClasspath
./gradlew --no-daemon --console=plain --max-workers=2 :app:dependencies --configuration debugAndroidTestRuntimeClasspath
openspec validate modernize-xmouse --strict
git diff --check
```

The final build was **BUILD SUCCESSFUL**, 83/83 tasks executed in **3m03s**. JVM results: **260 tests / 27
suites**, zero failures/errors/skips. Lint: **0 errors/fatal, 138 warnings**; no new-source finding or
suppression/baseline. Legacy storage fixture check passed **4/4** and the SSH transport/trust source audit
passed. Final APK identity is `com.stripe1.xmouse`, version **25 / 2.0.27**, min/target/compile **26 / 37 /
37**. SHA-256:

- App APK: `68027ce832eecabd16567fd37b70c38807e6d1a858a53db6f1c207951cd9f24a`
- Instrumentation APK: `f34141af1ab17b45336a23efbc33d16f4fc8bf65c5ec7b4fff89c0f4259bddc1`

Both full instrumentation suites ran these exact APKs. Before **each** APK installation,
`/tmp/opencode/xmouse-task-4.1/verified-install.py` checked the serial, exact approved AVD name, boot state,
API, fingerprint, and both SDK variables. Only the approved disposable AVD app/test package data was
cleared.

| Approved disposable AVD / serial | Fingerprint | Result | Duration |
| --- | --- | --- | ---: |
| API 26 `xmouse_task41_api26` / `emulator-5584` | `Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys` | 100 discovered, **98 passed / 2 assumption skips / 0 failures** | 113.712s |
| API 35 `xmouse_task31` / `emulator-5580` | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` | 100 discovered, **98 passed / 2 assumption skips / 0 failures** | 119.970s |

The two unchanged status `-4` assumption skips are the opt-in system-backup stage/verify methods in
`CredentialMetadataBackupTransportAndroidTest`; no auth, key-import, host-trust, or transport test was
skipped. The new `SshAuthenticationAndroidTest` passed on both APIs. The final API 26/API 35 AVDs were
stopped, and `adb -P 5038 devices -l` showed no devices. No personal device, user AVD, real SSH host,
system `sshd`, OS user/PAM, or host SSH configuration was accessed.

Exact reproduction commands (each command shell exports both SDK variables above):

```sh
# API 26 only
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-4.1/avd-api26
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task41_api26 -port 5584 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task45-final-v2-api26-emulator.log 2>&1 &
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 wait-for-device
while [ "$("$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task45-final-v2-api26-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5584 xmouse_task41_api26 26 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task45-final-v2-api26-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 shell am instrument --user 0 -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5584 emu kill

# API 35 only, after API 26 has stopped
export ANDROID_HOME=/home/homoludens/Android/Sdk
export ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk
export ANDROID_ADB_SERVER_PORT=5038
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME=/tmp/opencode/xmouse-task-3.1/avd
"$ANDROID_HOME/emulator/emulator" -avd xmouse_task31 -port 5580 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 \
  > /tmp/opencode/xmouse-task45-final-v2-api35-emulator.log 2>&1 &
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 wait-for-device
while [ "$("$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5580 xmouse_task31 35 \
  app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/xmouse-task45-final-v2-api35-app --clean
python3 /tmp/opencode/xmouse-task-4.1/verified-install.py emulator-5580 xmouse_task31 35 \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/opencode/xmouse-task45-final-v2-api35-test
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell am instrument --user 0 -w -r \
  com.stripe1.xmouse.test/androidx.test.runner.AndroidJUnitRunner
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 emu kill
```

### Evidence and remaining limits

- Final build: `/tmp/opencode/xmouse-task45-final-build-v2.log`.
- Final API 26/API 35 complete instrumentation: `/tmp/opencode/xmouse-task45-final-v2-api26-instrumentation.log`
  and `/tmp/opencode/xmouse-task45-final-v2-api35-instrumentation.log`.
- Per-install identities and install logs use prefixes `/tmp/opencode/xmouse-task45-final-v2-api{26,35}-{app,test}`;
  emulator logs use `/tmp/opencode/xmouse-task45-final-v2-api{26,35}-emulator.log`.
- Dependency graphs are `/tmp/opencode/xmouse-task45-dependencies-{debugRuntime,debugUnitTest,debugAndroidTest}.log`;
  JVM XML and lint reports are under `app/build/test-results/testDebugUnitTest/` and
  `app/build/reports/lint-results-debug.*`.

The integration server is **synthetic loopback MINA**, not OpenSSH, a LAN Linux host, or current-user
acceptance. No physical/API 37 device is claimed. This task does not implement comprehensive transport
deadlines (4.6), a session controller, arbitrary key formats, or an opt-in Android system backup/restore
run. The failed first API 26 iteration was not counted: an invalid underscore in this new test's synthetic
hostname was rejected by endpoint validation; after correcting the fixture, the uncached final build and
both full API suites above were rerun successfully.

## Exact task-4.5 file manifest

```text
app/src/main/java/com/stripe1/xmouse/MyConnectionHandler.java
app/src/test/java/com/stripe1/xmouse/trust/JschAuthenticationIntegrationTest.kt
app/src/androidTest/java/com/stripe1/xmouse/SshAuthenticationAndroidTest.kt
docs/verification/ssh-authentication.md
openspec/changes/modernize-xmouse/tasks.md
```

Nothing is staged or committed. Stop after 4.5 for parent review.
