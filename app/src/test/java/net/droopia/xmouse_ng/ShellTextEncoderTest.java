package net.droopia.xmouse_ng;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShellTextEncoderTest {
    @Test
    public void quotingIsLiteralAndDoesNotDispatchUntilExplicitTypeSubmission() {
        String entered = "Grüße 'world' \"quoted\" `literal` $(touch /tmp/not-run)\nnext line";
        String encoded = ShellTextEncoder.quoteForShell(entered);

        assertEquals("'" + entered.replace("'", "'\\''") + "'", encoded);

        RecordingSshTransport transport = new RecordingSshTransport();
        assertTrue("encoding while editing must not dispatch", transport.getCommands().isEmpty());

        new XDoTool(transport).type(encoded);
        assertEquals(Collections.singletonList("xdotool type " + encoded), transport.getCommands());
    }
}
