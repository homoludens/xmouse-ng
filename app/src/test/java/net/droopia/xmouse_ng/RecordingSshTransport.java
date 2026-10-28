package net.droopia.xmouse_ng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory SSH stand-in: records shell writes without constructing any UI or network object. */
final class RecordingSshTransport implements CommandTransport {
    private final List<String> commands = new ArrayList<>();
    private final boolean writeSucceeds;

    RecordingSshTransport() {
        this(true);
    }

    RecordingSshTransport(boolean writeSucceeds) {
        this.writeSucceeds = writeSucceeds;
    }

    @Override
    public boolean executeShellCommand(String command) {
        commands.add(command);
        return writeSucceeds;
    }

    List<String> getCommands() {
        return Collections.unmodifiableList(commands);
    }
}
