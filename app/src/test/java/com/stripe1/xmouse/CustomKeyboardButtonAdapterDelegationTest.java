package com.stripe1.xmouse;

import android.util.Log;

import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** Only the UI delegate needs Android stubs; its constructor/Context/Activity are never used. */
public class CustomKeyboardButtonAdapterDelegationTest {
    @Test
    public void adapterDelegatesToTheSelectedBackend() {
        CustomKeyboardButtonAdapter adapter = mock(CustomKeyboardButtonAdapter.class, CALLS_REAL_METHODS);
        RecordingSshTransport transport = new RecordingSshTransport();

        adapter.execCommand("xdotool key Ctrl+Alt+F1", new YDoTool(transport), transport);

        assertEquals(Collections.singletonList("ydotool key 29:1 56:1 59:1 59:0 56:0 29:0"),
                transport.getCommands());
    }

    @Test
    public void missingArgumentsStillLogAndReturnWithoutWritingOrFallback() {
        CustomKeyboardButtonAdapter adapter = mock(CustomKeyboardButtonAdapter.class, CALLS_REAL_METHODS);
        RecordingSshTransport transport = new RecordingSshTransport();

        try (MockedStatic<Log> log = mockStatic(Log.class)) {
            for (String command : new String[]{"key", "type", "mouseclick", "mousedown", "mouseup",
                    "mousemoverelative", "mousemoverelative 1", "   "}) {
                adapter.execCommand(command, new XDoTool(transport), transport);
                log.verify(() -> Log.d("execCommand", "invalid command: " + command));
            }
            log.verifyNoMoreInteractions();
        }
        assertTrue(transport.getCommands().isEmpty());
    }

    @Test
    public void malformedNumericArgumentsStillPropagateWithoutFallback() {
        CustomKeyboardButtonAdapter adapter = mock(CustomKeyboardButtonAdapter.class, CALLS_REAL_METHODS);
        RecordingSshTransport transport = new RecordingSshTransport();

        assertThrows(NumberFormatException.class,
                () -> adapter.execCommand("mouseclick nope", new XDoTool(transport), transport));
        assertTrue(transport.getCommands().isEmpty());
    }
}
