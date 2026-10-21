package com.stripe1.xmouse.transport;

/**
 * Temporary, INSECURE compatibility policy, explicitly selected by the legacy UI composition root.
 * This does not establish server trust. Replace with endpoint-keyed approval in tasks 4.3/4.4.
 */
public final class LegacySshPolicy {
    private LegacySshPolicy() { }

    public static JschSshBackend.SessionPolicy unverifiedHostKeys() {
        return (jsch, session, endpoint) -> session.setConfig("StrictHostKeyChecking", "no");
    }
}
