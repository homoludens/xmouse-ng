package com.stripe1.xmouse.transport;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.stripe1.xmouse.connection.Endpoint;
import com.stripe1.xmouse.secrets.AuthenticationMaterial;
import com.stripe1.xmouse.secrets.BoundedSshKey;
import com.stripe1.xmouse.trust.HostKeyMismatch;
import java.io.InputStream;
import java.io.OutputStream;

/** JSch adaptation only: no Android, UI, logger, resolver, or built-in host-trust decision. */
public final class JschSshBackend implements SshBackend {
    public interface SessionPolicy {
        void configure(JSch jsch, com.jcraft.jsch.Session session, Endpoint endpoint) throws Exception;
        default void configure(JSch jsch, com.jcraft.jsch.Session session, Endpoint endpoint,
                               java.util.function.BooleanSupplier current) throws Exception { configure(jsch, session, endpoint); }
        default void cancelPendingTrust() { }
        default void replaceHostKey(HostKeyMismatch mismatch, java.util.function.BooleanSupplier current) throws Exception { }
    }

    private final SessionPolicy policy;

    public JschSshBackend(SessionPolicy policy) { this.policy = policy; }

    @Override public Session create(SshConnectionRequest request, AuthenticationMaterial authentication) throws Exception {
        return create(request, authentication, () -> true);
    }

    @Override public void cancelPendingTrust() { policy.cancelPendingTrust(); }
    @Override public void replaceHostKey(HostKeyMismatch mismatch, java.util.function.BooleanSupplier current) throws Exception {
        policy.replaceHostKey(mismatch, current);
    }

    @Override public Session create(SshConnectionRequest request, AuthenticationMaterial authentication,
                                    java.util.function.BooleanSupplier current) throws Exception {
        JSch jsch = new JSch();
        com.jcraft.jsch.Session session = null;
        try {
            session = jsch.getSession(authentication.getHost().getUser(), request.address, request.endpoint.getPort());
            policy.configure(jsch, session, request.endpoint, current);
            if (authentication.getUseKey()) {
                // All source parsing and JSch identity publication MUST stay inside this boundary.
                BoundedSshKey.addIdentity(jsch, authentication.getPrivateKey(), authentication.getPassphrase());
                session.setConfig("PreferredAuthentications", "publickey");
            } else {
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
                session.setPassword(authentication.getPassword());
            }
            session.setX11Host("127.0.0.1");
            session.setX11Port(6000);
            return new JschSession(jsch, session);
        } catch (Exception failure) {
            if (session != null) session.disconnect();
            try { jsch.removeAllIdentity(); } catch (Exception ignored) { }
            throw failure; // Consumed and sanitized inside SshTransport, never sent to presentation.
        }
    }

    private static final class JschSession implements Session {
        private final JSch jsch;
        private final com.jcraft.jsch.Session session;
        JschSession(JSch jsch, com.jcraft.jsch.Session session) { this.jsch = jsch; this.session = session; }
        @Override public void connect() throws Exception { session.connect(); }
        @Override public void clearAuthentication() throws Exception { jsch.removeAllIdentity(); }
        @Override public Channel openChannel(String type) throws Exception { return new JschChannel(session.openChannel(type)); }
        @Override public boolean isConnected() { return session.isConnected(); }
        @Override public void close() throws Exception {
            try { session.disconnect(); } finally { jsch.removeAllIdentity(); }
        }
    }

    private static final class JschChannel implements Channel {
        private final com.jcraft.jsch.Channel channel;
        JschChannel(com.jcraft.jsch.Channel channel) { this.channel = channel; }
        @Override public void command(String command) { ((ChannelExec) channel).setCommand(command); }
        @Override public void output(OutputStream stdout, OutputStream stderr) {
            channel.setOutputStream(stdout, true);
            if (channel instanceof ChannelExec) ((ChannelExec) channel).setErrStream(stderr, true);
            if (channel instanceof ChannelShell) ((ChannelShell) channel).setExtOutputStream(stderr, true);
        }
        @Override public OutputStream input() throws Exception { return channel.getOutputStream(); }
        @Override public InputStream output() throws Exception { return channel.getInputStream(); }
        @Override public void connect(int timeoutMillis) throws Exception { channel.connect(timeoutMillis); }
        @Override public boolean isClosed() { return channel.isClosed(); }
        @Override public void close() { channel.disconnect(); }
    }
}
