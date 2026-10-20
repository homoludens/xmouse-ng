package com.stripe1.xmouse;

/** Java-callable shell-command boundary; implementations own any connection and I/O. */
public interface CommandTransport {
    /**
     * Sends the command unchanged, leaving shell framing to the transport.
     * The result reports local write success, not remote command completion.
     */
    boolean executeShellCommand(String command);
}
