package com.stripe1.xmouse.transport;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
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
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** JSch/socket adaptation: Android/network policy is supplied through the attempt-local resolver seam. */
public final class JschSshBackend implements SshBackend {
    private static final String AUTH_FAILURE_PREFIX = "Auth fail for methods '";
    private static final String AUTH_CANCEL_PREFIX = "Auth cancel for methods '";
    private static final int MAX_AUTH_METHODS_MESSAGE_LENGTH = 1024;

    public interface SessionPolicy {
        void configure(JSch jsch, com.jcraft.jsch.Session session, Endpoint endpoint) throws Exception;
        default void configure(JSch jsch, com.jcraft.jsch.Session session, Endpoint endpoint,
                               java.util.function.BooleanSupplier current) throws Exception { configure(jsch, session, endpoint); }
        default void cancelPendingTrust() { }
        default void replaceHostKey(HostKeyMismatch mismatch, java.util.function.BooleanSupplier current) throws Exception { }
    }

    private final SessionPolicy policy;
    private final SshAddressResolver resolver;

    public JschSshBackend(SessionPolicy policy) { this(policy, SshAddressResolver.system()); }

    public JschSshBackend(SessionPolicy policy, SshAddressResolver resolver) {
        this.policy = policy;
        this.resolver = resolver;
    }

    /**
     * mwiede JSch 2.28.8 has no typed "credentials rejected" exception. Its pinned Session.connect()
     * source throws a fixed `Auth fail` or `Auth cancel` result after configured user-auth methods
     * are exhausted. This app installs no JSch UserInfo, so the latter is an unhandled interactive
     * challenge rather than a user cancellation. Match only those bounded templates, here at the
     * JSch boundary; never retain or forward server-controlled method-list text.
     */
    static boolean isAuthenticationRejected(JSchException failure) {
        String message = failure.getMessage();
        String prefix = message != null && message.startsWith(AUTH_FAILURE_PREFIX) ? AUTH_FAILURE_PREFIX :
                message != null && message.startsWith(AUTH_CANCEL_PREFIX) ? AUTH_CANCEL_PREFIX : null;
        if (prefix == null || !message.endsWith("'") ||
                message.length() > MAX_AUTH_METHODS_MESSAGE_LENGTH) return false;
        String methods = message.substring(prefix.length(), message.length() - 1);
        for (int i = 0; i < methods.length(); i++) {
            char value = methods.charAt(i);
            // The inserted value is an SSH name-list. Reject control characters/oversized payloads;
            // its contents are untrusted and are used only for this local recognition check.
            if (value < 0x20 || value > 0x7e) return false;
        }
        return true;
    }

    @Override public SshConnectionRequest resolve(SshConnectionRequest request) throws Exception {
        return resolve(request, () -> true);
    }

    @Override public SshConnectionRequest resolve(SshConnectionRequest request, BooleanSupplier current) throws Exception {
        if (!current.getAsBoolean()) throw new CancellationException();
        SshAddressResolver.Resolution result = resolver.resolve(request.endpoint, request.address, current);
        if (!current.getAsBoolean()) throw new CancellationException();
        result.checkCurrent();
        return request.resolved(result);
    }

    @Override public Session create(SshConnectionRequest request, AuthenticationMaterial authentication) throws Exception {
        return create(request, authentication, () -> true);
    }

    @Override public void cancelPendingTrust() { policy.cancelPendingTrust(); }
    @Override public void replaceHostKey(HostKeyMismatch mismatch, java.util.function.BooleanSupplier current) throws Exception {
        policy.replaceHostKey(mismatch, current);
    }

    @Override public Session create(SshConnectionRequest request, AuthenticationMaterial authentication,
                                    BooleanSupplier current) throws Exception {
        JSch jsch = new JSch();
        com.jcraft.jsch.Session session = null;
        try {
            session = jsch.getSession(authentication.getHost().getUser(), request.address, request.endpoint.getPort());
            BooleanSupplier routeCurrent = () -> current.getAsBoolean() &&
                    (request.addressResolution == null || request.addressResolution.isCurrent());
            policy.configure(jsch, session, request.endpoint, routeCurrent);
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
            return new JschSession(jsch, session, request, routeCurrent);
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
        private final BooleanSupplier current;
        private volatile Socket socket;
        private volatile boolean aborted;
        private volatile long lastInbound;
        private SshLimits limits = SshLimits.production();
        JschSession(JSch jsch, com.jcraft.jsch.Session session, SshConnectionRequest request,
                    BooleanSupplier current) {
            this.jsch = jsch; this.session = session; this.request = request; this.current = current;
        }
        @Override public void configure(SshLimits limits) { this.limits = limits; }
        @Override public void connect() throws Exception { connect(limits.connection); }
        @Override public void connect(int timeoutMillis) throws Exception {
            session.setSocketFactory(new com.jcraft.jsch.SocketFactory() {
                @Override public Socket createSocket(String host, int port) throws IOException {
                    if (request.resolvedAddresses.isEmpty()) throw new UnknownHostException();
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                    IOException lastFailure = null;
                    for (InetAddress candidate : request.resolvedAddresses) {
                        Socket created = new Socket();
                        socket = created; // Publish BEFORE route binding/TCP connect so cancellation can break it.
                        try {
                            ensureCurrent();
                            if (request.addressResolution != null) {
                                request.addressResolution.checkCurrent();
                                request.addressResolution.bind(created);
                                request.addressResolution.checkCurrent();
                            }
                            int remaining = (int) Math.min(limits.tcp,
                                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                            if (remaining <= 0) throw new SocketTimeoutException();
                            created.connect(new InetSocketAddress(candidate, port), Math.max(1, remaining));
                            ensureCurrent();
                            if (request.addressResolution != null) request.addressResolution.checkCurrent();
                            return created;
                        } catch (IOException failure) {
                            try { created.close(); } catch (IOException ignored) { }
                            if (socket == created) socket = null;
                            if (aborted) throw failure;
                            if (request.addressResolution != null) request.addressResolution.checkCurrent();
                            if (!isCurrent()) throw failure;
                            lastFailure = failure;
                        }
                    }
                    throw lastFailure == null ? new UnknownHostException() : lastFailure;
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
            try {
                session.connect(timeoutMillis); // Finite KEX/auth read timeout; outer budget also covers trust.
            } catch (JSchException failure) {
                if (isAuthenticationRejected(failure)) throw new SshBackend.AuthenticationRejectedException();
                throw failure;
            }
            lastInbound = System.nanoTime();
            if (aborted) { abort(); throw new IOException(); }
        }
        @Override public void abort() throws IOException {
            aborted = true;
            Socket owned = socket;
            if (owned != null) owned.close();
        }
        private void ensureCurrent() throws IOException {
            if (aborted || !isCurrent()) throw new IOException();
        }
        private boolean isCurrent() {
            try { return current.getAsBoolean(); } catch (RuntimeException ignored) { return false; }
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
        @Override public void connect(int timeoutMillis) throws Exception {
            long start = System.nanoTime();
            try { channel.connect(timeoutMillis); }
            catch (JSchException ignored) {
                // JSch's channel deadline is often an untyped "not opened" exception. Its own
                // finite timer may beat the transport future by a scheduler tick. Classify using
                // elapsed monotonic time only, allowing one millisecond of provider clock rounding;
                // an early explicit channel rejection is NOT labelled a timeout.
                boolean timedOut = System.nanoTime() - start >=
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis - 1));
                throw new SshBackend.ChannelSetupException(timedOut);
            }
        }
        @Override public boolean isClosed() { return channel.isClosed(); }
        @Override public int exitStatus() { return channel.getExitStatus(); }
        @Override public void close() { channel.disconnect(); }
    }
}
