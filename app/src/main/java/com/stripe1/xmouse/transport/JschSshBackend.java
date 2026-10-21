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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** JSch/socket adaptation and single-address resolution seam: no Android, UI, logger or trust decision. */
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

    @Override public SshConnectionRequest resolve(SshConnectionRequest request) throws Exception {
        // Intentionally a single-address seam. Network-aware candidate resolution remains task 6.3.
        return request.resolved(InetAddress.getByName(request.address));
    }

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
            return new JschSession(jsch, session, request);
        } catch (Exception failure) {
            if (session != null) session.disconnect();
            try { jsch.removeAllIdentity(); } catch (Exception ignored) { }
            throw failure; // Consumed and sanitized inside SshTransport, never sent to presentation.
        }
    }

    private static final class JschSession implements Session {
        private final JSch jsch;
        private final com.jcraft.jsch.Session session;
        private final SshConnectionRequest request;
        private volatile Socket socket;
        private volatile boolean aborted;
        private volatile long lastInbound;
        private SshLimits limits = SshLimits.production();
        JschSession(JSch jsch, com.jcraft.jsch.Session session, SshConnectionRequest request) {
            this.jsch = jsch; this.session = session; this.request = request;
        }
        @Override public void configure(SshLimits limits) { this.limits = limits; }
        @Override public void connect() throws Exception { connect(limits.connection); }
        @Override public void connect(int timeoutMillis) throws Exception {
            session.setSocketFactory(new com.jcraft.jsch.SocketFactory() {
                @Override public Socket createSocket(String host, int port) throws IOException {
                    if (request.resolvedAddress == null) throw new IOException();
                    Socket created = new Socket();
                    socket = created; // Publish BEFORE TCP connect so cancellation can break it.
                    if (aborted) { created.close(); throw new IOException(); }
                    created.connect(new InetSocketAddress(request.resolvedAddress, port), limits.tcp);
                    if (aborted) { created.close(); throw new IOException(); }
                    return created;
                }
                @Override public InputStream getInputStream(Socket socket) throws IOException {
                    return new java.io.FilterInputStream(socket.getInputStream()) {
                        @Override public int read() throws IOException {
                            int value = in.read(); if (value >= 0) lastInbound = System.nanoTime(); return value;
                        }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            int count = in.read(bytes, offset, length);
                            if (count > 0) lastInbound = System.nanoTime();
                            return count;
                        }
                    };
                }
                @Override public OutputStream getOutputStream(Socket socket) throws IOException { return socket.getOutputStream(); }
            });
            session.setServerAliveCountMax(limits.keepaliveMisses);
            // Set the post-auth socket timeout BEFORE JSch starts its reader. Setting it afterwards
            // could leave the reader's first already-started read with an infinite timeout.
            session.setServerAliveInterval(limits.keepaliveInterval);
            session.connect(timeoutMillis); // Finite KEX/auth read timeout; outer budget also covers trust.
            lastInbound = System.nanoTime();
            if (aborted) { abort(); throw new IOException(); }
        }
        @Override public void abort() throws IOException {
            aborted = true;
            Socket owned = socket;
            if (owned != null) owned.close();
        }
        @Override public void clearAuthentication() throws Exception { jsch.removeAllIdentity(); }
        @Override public Channel openChannel(String type) throws Exception { return new JschChannel(session.openChannel(type)); }
        @Override public boolean isConnected() {
            // Independent backstop if JSch's own keepalive send is stuck in a socket write.
            long silence = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    (long) limits.keepaliveInterval * (limits.keepaliveMisses + 1L));
            return session.isConnected() && (lastInbound == 0 || System.nanoTime() - lastInbound <= silence);
        }
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
        @Override public int exitStatus() { return channel.getExitStatus(); }
        @Override public void close() { channel.disconnect(); }
    }
}
