package com.stripe1.xmouse.transport;

import com.stripe1.xmouse.connection.Endpoint;

/** Attempt-local lookup address is separate from the logical endpoint used by future trust policy. */
public final class SshConnectionRequest {
    public final Endpoint endpoint;
    public final String address;
    public final String shell;
    public final String initialCommand;

    public SshConnectionRequest(Endpoint endpoint, String address, String shell, String initialCommand) {
        this.endpoint = endpoint;
        this.address = address;
        this.shell = shell == null ? "" : shell;
        this.initialCommand = initialCommand;
    }
}
