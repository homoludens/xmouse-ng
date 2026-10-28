package net.droopia.xmouse_ng.transport;

import net.droopia.xmouse_ng.connection.Endpoint;
import net.droopia.xmouse_ng.connection.EndpointLocator;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Resolves one attempt's dial candidates without changing its logical endpoint or trust identity. */
@FunctionalInterface
public interface SshAddressResolver {
    int MAX_CANDIDATES = 8;

    Resolution resolve(String host, BooleanSupplier current) throws Exception;

    /** Locator-aware entry point; only Android NSD adapters can resolve service-only profiles. */
    default Resolution resolve(Endpoint endpoint, String host, BooleanSupplier current) throws Exception {
        if (endpoint.getLocator() instanceof EndpointLocator.DiscoveredService &&
                ((EndpointLocator.DiscoveredService) endpoint.getLocator()).getStableTargetHostname() == null) {
            throw new UnknownHostException();
        }
        return resolve(host, current);
    }

    /** Attempt-local route information. Android implementations bind sockets and reject stale networks. */
    interface Resolution {
        List<InetAddress> candidates();

        default boolean isCurrent() { return true; }

        default void checkCurrent() throws IOException {
            if (!isCurrent()) throw new NetworkChangedException();
        }

        default void bind(Socket socket) throws IOException { }
    }

    /** Permission failures remain distinct from name/connect failures all the way to the session state. */
    final class AccessDeniedException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** An active-network change invalidated these answers; they must not be dialed on a new route. */
    final class NetworkChangedException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** A bounded lookup expired before finding any matching address, not an SSH/TCP timeout. */
    final class ResolutionTimeoutException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** JVM/default adapter for isolated tests and non-Android callers; Android injects its Network resolver. */
    static SshAddressResolver system() {
        return (host, current) -> {
            if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            List<InetAddress> bounded = new ArrayList<>(Math.min(addresses.length, MAX_CANDIDATES));
            for (InetAddress address : addresses) {
                bounded.add(address);
                if (bounded.size() == MAX_CANDIDATES) break;
            }
            List<InetAddress> result = Collections.unmodifiableList(bounded);
            if (result.isEmpty()) throw new UnknownHostException();
            return () -> result;
        };
    }

}
