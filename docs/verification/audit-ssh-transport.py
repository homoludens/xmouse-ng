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
assert '"StrictHostKeyChecking", "no"' in (transport / "LegacySshPolicy.java").read_text()
assert "policy.configure(jsch, session, request.endpoint)" in backend
print("SSH extraction source boundaries passed; legacy unverified-host policy remains isolated and INSECURE.")
