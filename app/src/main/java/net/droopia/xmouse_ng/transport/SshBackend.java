package net.droopia.xmouse_ng.transport;

import net.droopia.xmouse_ng.secrets.AuthenticationMaterial;
import net.droopia.xmouse_ng.trust.HostKeyMismatch;
import net.droopia.xmouse_ng.trust.HostTrustException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Blocking resource seam. Calls run on bounded background lanes; abort/close may race timed-out I/O.
 * Implementations must make status reads thread safe and abort/close idempotent.
 */
public interface SshBackend {
    /** Backend-only typed outcome for JSch's verified user-authentication rejection signal. */
    final class AuthenticationRejectedException extends Exception {
        private static final long serialVersionUID = 1L;
        public AuthenticationRejectedException() { super(); }
    }

    /** Safe channel-setup outcome; contains no provider message, endpoint, or authentication data. */
    final class ChannelSetupException extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean timedOut;
        public ChannelSetupException(boolean timedOut) { this.timedOut = timedOut; }
    }

    default SshConnectionRequest resolve(SshConnectionRequest request) throws Exception { return request; }
    default SshConnectionRequest resolve(SshConnectionRequest request, BooleanSupplier current) throws Exception {
        if (!current.getAsBoolean()) throw new CancellationException();
        return resolve(request);
    }
    Session create(SshConnectionRequest request, AuthenticationMaterial authentication) throws Exception;
    default Session create(SshConnectionRequest request, AuthenticationMaterial authentication,
                           java.util.function.BooleanSupplier current) throws Exception { return create(request, authentication); }
    /** Non-blocking cancellation of pending trust only; resource close stays on background lanes. */
    default void cancelPendingTrust() { }
    /** Trust changes are worker-only and unavailable to backends that do not own the trust policy. */
    default void replaceHostKey(HostKeyMismatch mismatch, java.util.function.BooleanSupplier current) throws Exception {
        throw new HostTrustException(HostTrustException.Reason.CANCELLED);
    }

    interface Session {
        void connect() throws Exception;
        default void configure(SshLimits limits) throws Exception { }
        default void connect(int timeoutMillis) throws Exception { connect(); }
        /** Break blocking socket I/O without waiting for provider/channel locks. Background only. */
        default void abort() throws Exception { }
        void clearAuthentication() throws Exception;
        Channel openChannel(String type) throws Exception;
        boolean isConnected();
        void close() throws Exception;
    }

    interface Channel {
        void command(String command) throws Exception;
        void output(OutputStream stdout, OutputStream stderr) throws Exception;
        OutputStream input() throws Exception;
        InputStream output() throws Exception;
        void connect(int timeoutMillis) throws Exception;
        boolean isClosed();
        default int exitStatus() { return 0; }
        void close() throws Exception;
    }
}
