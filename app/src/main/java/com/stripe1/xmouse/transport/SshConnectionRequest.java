package com.stripe1.xmouse.transport;

import com.stripe1.xmouse.connection.Endpoint;

/** Attempt-local dial name/resolved address are separate from the logical endpoint used for trust. */
public final class SshConnectionRequest {
    public final Endpoint endpoint;
    public final String address;
    public final String shell;
    public final String initialCommand;
    public final java.net.InetAddress resolvedAddress;

    public SshConnectionRequest(Endpoint endpoint, String address, String shell, String initialCommand) {
        this(endpoint, address, shell, initialCommand, null);
    }

    private SshConnectionRequest(Endpoint endpoint, String address, String shell, String initialCommand,
                                 java.net.InetAddress resolvedAddress) {
        this.endpoint = endpoint;
        this.address = address;
        this.shell = shell == null ? "" : shell;
        this.initialCommand = initialCommand;
        this.resolvedAddress = resolvedAddress;
    }

    public SshConnectionRequest resolved(java.net.InetAddress address) {
        return new SshConnectionRequest(endpoint, this.address, shell, initialCommand, address);
    }
}
