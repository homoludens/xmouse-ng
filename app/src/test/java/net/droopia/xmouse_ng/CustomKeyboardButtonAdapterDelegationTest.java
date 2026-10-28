package net.droopia.xmouse_ng;

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
import static org.mockito.Mockito.times;

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
            String[] commands = {"key", "type", "mouseclick", "mousedown", "mouseup",
                    "mousemoverelative", "mousemoverelative 1", "   "};
            for (String command : commands) {
                adapter.execCommand(command, new XDoTool(transport), transport);
            }
            log.verify(() -> Log.d("CustomKeyboardButtonAdapter", "invalid_custom_command"), times(commands.length));
            log.verifyNoMoreInteractions();
        }
        assertTrue(transport.getCommands().isEmpty());
    }

    @Test
    public void typedCustomCommandIsDispatchedWithoutLoggingItsContent() {
        CustomKeyboardButtonAdapter adapter = mock(CustomKeyboardButtonAdapter.class, CALLS_REAL_METHODS);
        RecordingSshTransport transport = new RecordingSshTransport();
        String syntheticTypedContent = "XMouseSyntheticTypedSentinel";

        try (MockedStatic<Log> log = mockStatic(Log.class)) {
            adapter.execCommand("type " + syntheticTypedContent, new XDoTool(transport), transport);
            log.verifyNoInteractions();
        }

        assertEquals(Collections.singletonList("xdotool type " + syntheticTypedContent), transport.getCommands());
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
