#!/usr/bin/env python3
"""Focused source-boundary checks; behavioral/thread evidence comes from JVM/Android tests."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
source = root / "app/src/main/java/com/stripe1/xmouse"
transport = source / "transport"
for file in transport.glob("*.java"):
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", file.read_text(), flags=re.S)
    assert not re.search(r"import\s+(android\.|androidx\.)", text), file
    assert not re.search(r"\b(MainActivity|Activity|Context|View|ProgressDialog|AlertDialog|AsyncTask)\b", text), file
    assert not re.search(r"\b(Log\.|System\.(out|err)|printStackTrace)", text), file

presenter = (source / "MyConnectionHandler.java").read_text()
assert not re.search(r"com\.jcraft|\bAsyncTask\b|Piped|FileOutputStream|BoundedSshKey", presenter)
assert "MainActivity.recentCmdTextView" not in presenter
assert "presentation.detach()" in presenter and "transport.close()" in presenter
assert "new TransportPresentation(main::post)" in presenter
assert "WeakReference<Listener>" in (transport / "TransportPresentation.java").read_text()
assert "SshScpTask" not in (source / "MyMouseView.java").read_text()

identity_calls = []
for file in source.rglob("*"):
    if file.suffix not in (".java", ".kt"):
        continue
    text = file.read_text()
    if re.search(r"\bKeyPair\.load\s*\(|\bjsch\.addIdentity\s*\(", text):
        identity_calls.append(file.relative_to(source).as_posix())
assert identity_calls == ["secrets/BoundedSshKey.kt"], identity_calls
backend = (transport / "JschSshBackend.java").read_text()
assert "BoundedSshKey.addIdentity(" in backend
assert '"StrictHostKeyChecking"' not in backend
assert not (transport / "LegacySshPolicy.java").exists()
assert "policy.configure(jsch, session, request.endpoint, current)" in backend
assert "InetAddress.getByName(request.address)" in backend and "request.resolvedAddress" in backend
assert "created.connect(new InetSocketAddress(request.resolvedAddress, port), limits.tcp)" in backend
assert "session.connect(timeoutMillis)" in backend and "session.connect()" not in backend
assert "setServerAliveCountMax(limits.keepaliveMisses)" in backend
assert "setServerAliveInterval(limits.keepaliveInterval)" in backend
assert backend.index("setServerAliveInterval(limits.keepaliveInterval)") < backend.index("session.connect(timeoutMillis)")
assert "lastInbound" in backend and "owned.close()" in backend
runtime = (transport / "SshRuntime.java").read_text()
assert "CallerRunsPolicy" not in runtime and "newCachedThreadPool" not in runtime
assert "new Semaphore(4)" in runtime and "new SynchronousQueue" in runtime
assert "pool(2, 0" in runtime and "pool(4, 0" in runtime and "pool(32, 32" in runtime
core = (transport / "SshTransport.java").read_text()
assert "Failure.OUTPUT_LIMIT" in core and "channel.exitStatus() < 0" in core
assert "queued >= 128" in core and "future.get(budget, TimeUnit.MILLISECONDS)" in core
assert "scope.token != generation" in core and "scope.expired = true" in core
assert "new HostTrustPolicy(trust)" in presenter
for file in source.rglob("*"):
    if file.suffix in (".java", ".kt"):
        assert not re.search(r'StrictHostKeyChecking"\s*,\s*"no', file.read_text()), file
for file in (source / "trust").glob("*.kt"):
    if file.name in ("AndroidHostTrust.kt", "HostTrustReplacementUi.kt"):
        continue
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", file.read_text(), flags=re.S)
    assert not re.search(r"import\s+(android\.|androidx\.)|\b(Activity|Context|View|AlertDialog|Handler)\b", text), file
    assert not re.search(r"\b(Log\.|System\.(out|err)|printStackTrace|KeyStore|SecretStore)\b", text), file
replacement_ui = (source / "trust" / "HostTrustReplacementUi.kt").read_text()
assert "class HostTrustReplacementUi" in replacement_ui
assert "Replace trusted key" in replacement_ui and "Review replacement" in replacement_ui
assert not re.search(r"import\s+com\.stripe1\.xmouse\.(transport|secrets)|\b(SshTransport|AuthenticationMaterial|CredentialRepository)\b", replacement_ui)
for file in (source / "session").glob("*.kt"):
    # These are deliberate Android composition/presentation adapters, not the pure owner pipeline.
    if file.name in (
        "AndroidSessionOwner.kt",
        "SessionForegroundService.kt",
        "SessionActivityPresenter.kt",
        "SessionCommandTransport.kt",
    ):
        continue
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", file.read_text(), flags=re.S)
    assert not re.search(r"import\s+(android\.|androidx\.)|\b(Activity|Context|View|AlertDialog|Handler|MainActivity)\b", text), file
    assert not re.search(r"\b(Log\.|System\.(out|err)|printStackTrace|KeyStore)\b", text), file
assert "closeConfirmed" in core and "while (!scope.released)" in core
controller = (source / "session" / "SessionController.kt").read_text()
assert "entry.closeCompleted" in controller and "current.cleanupUnconfirmed" in controller
assert "WeakReference(observer)" in controller
assert "repository.authenticate(selection, prompts, completion)" in (source / "session" / "AndroidSessionOwner.kt").read_text()
session_interactions = (source / "session" / "SessionInteractions.kt").read_text()
assert "WeakReference<Listener>" in session_interactions
activity_presenter = (source / "session" / "SessionActivityPresenter.kt").read_text()
assert "class SessionActivityPresenter" in activity_presenter
assert "owner.attach(this)" in activity_presenter and "interactions?.detach()" in activity_presenter
command_bridge = (source / "session" / "SessionCommandTransport.kt").read_text()
assert "context.applicationContext" in command_bridge
assert "SessionForegroundService" not in command_bridge
main_activity = (source / "MainActivity.java").read_text()
assert "new SessionCommandTransport(getApplicationContext())" in main_activity
assert not re.search(r"\b(MyConnectionHandler|SshTransport|JschSshBackend)\b|xMouseTryConnect|xMouseDisconnect", main_activity)
assert "SessionForegroundService.requestConnect" not in main_activity  # starts are ticketed and permission-gated
assert "SessionForegroundService.requestDisconnect" in main_activity
print("SSH extraction/trust source boundaries passed; no unverified-host fallback.")
