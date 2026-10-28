package net.droopia.xmouse_ng.transport;

import net.droopia.xmouse_ng.connection.Endpoint;
import java.net.InetAddress;
import java.net.Socket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Attempt-local dial name/resolved address are separate from the logical endpoint used for trust. */
public final class SshConnectionRequest {
    public final Endpoint endpoint;
    public final String address;
    public final String shell;
    public final String initialCommand;
    /** Immutable, bounded candidate set from this attempt's current network. */
    public final List<InetAddress> resolvedAddresses;
    /** Retains attempt-local network binding/currentness, never part of the saved endpoint identity. */
    public final SshAddressResolver.Resolution addressResolution;
    /** Compatibility view for existing single-address callers. */
    public final InetAddress resolvedAddress;

    public SshConnectionRequest(Endpoint endpoint, String address, String shell, String initialCommand) {
        this(endpoint, address, shell, initialCommand, Collections.emptyList(), null);
    }

    private SshConnectionRequest(Endpoint endpoint, String address, String shell, String initialCommand,
                                 List<InetAddress> resolvedAddresses,
                                 SshAddressResolver.Resolution addressResolution) {
        this.endpoint = endpoint;
        this.address = address;
        this.shell = shell == null ? "" : shell;
        this.initialCommand = initialCommand;
        this.resolvedAddresses = Collections.unmodifiableList(new ArrayList<>(resolvedAddresses));
        this.addressResolution = addressResolution;
        this.resolvedAddress = this.resolvedAddresses.isEmpty() ? null : this.resolvedAddresses.get(0);
    }

    public SshConnectionRequest resolved(InetAddress address) {
        return resolved(Collections.singletonList(address));
    }

    public SshConnectionRequest resolved(List<InetAddress> addresses) {
        if (addresses == null) throw new IllegalArgumentException("Address candidates are required");
        List<InetAddress> bounded = new ArrayList<>(Math.min(addresses.size(), SshAddressResolver.MAX_CANDIDATES));
        for (InetAddress candidate : addresses) {
            if (candidate == null) continue;
            bounded.add(candidate);
            if (bounded.size() == SshAddressResolver.MAX_CANDIDATES) break;
        }
        if (bounded.isEmpty()) throw new IllegalArgumentException("At least one address candidate is required");
        List<InetAddress> snapshot = Collections.unmodifiableList(bounded);
        return resolved(() -> snapshot);
    }

    public SshConnectionRequest resolved(SshAddressResolver.Resolution resolution) {
        if (resolution == null) throw new IllegalArgumentException("Address candidates are required");
        List<InetAddress> candidates = resolution.candidates();
        if (candidates == null) throw new IllegalArgumentException("Address candidates are required");
        List<InetAddress> bounded = new ArrayList<>(Math.min(candidates.size(), SshAddressResolver.MAX_CANDIDATES));
        for (InetAddress candidate : candidates) {
            if (candidate == null) continue;
            bounded.add(candidate);
            if (bounded.size() == SshAddressResolver.MAX_CANDIDATES) break;
        }
        if (bounded.isEmpty()) throw new IllegalArgumentException("At least one address candidate is required");
        List<InetAddress> snapshot = Collections.unmodifiableList(new ArrayList<>(bounded));
        SshAddressResolver.Resolution boundedResolution = new SshAddressResolver.Resolution() {
            @Override public List<InetAddress> candidates() { return snapshot; }
            @Override public boolean isCurrent() { return resolution.isCurrent(); }
            @Override public void checkCurrent() throws IOException { resolution.checkCurrent(); }
            @Override public void bind(Socket socket) throws IOException { resolution.bind(socket); }
        };
        return new SshConnectionRequest(endpoint, this.address, shell, initialCommand, snapshot, boundedResolution);
    }
}
