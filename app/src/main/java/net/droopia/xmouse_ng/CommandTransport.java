package net.droopia.xmouse_ng;

/** Java-callable shell-command boundary; implementations own any connection and I/O. */
public interface CommandTransport {
    /**
     * Sends the command unchanged, leaving shell framing to the transport.
     * True reports local acceptance (possibly queued), not a completed write or remote execution.
     */
    boolean executeShellCommand(String command);
}
