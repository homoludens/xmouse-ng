package com.stripe1.xmouse;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class InlineTextDispatchTest {
    private static final class RecordingTool implements IDoTool {
        final List<String> calls = new ArrayList<>();
        @Override public void mouseMoveRelative(float dx, float dy) { fail("unexpected pointer movement"); }
        @Override public void mouseClick(int btn) { fail("unexpected mouse click"); }
        @Override public void mouseDown(int btn) { fail("unexpected mouse down"); }
        @Override public void mouseUp(int btn) { fail("unexpected mouse up"); }
        @Override public void mouseWheelUp() { fail("unexpected wheel event"); }
        @Override public void mouseWheelDown() { fail("unexpected wheel event"); }
        @Override public void key(String key) { calls.add("key:" + key); }
        @Override public void type(String text) { calls.add("type:" + text); }
    }

    @Test public void optionalEnterIsSentAfterShellEscapedUnicodeText() {
        RecordingTool tool = new RecordingTool();
        assertTrue(InlineTextDispatch.send(tool, "héllo '世界'\n", true));
        assertEquals(List.of("type:'héllo '\\''世界'\\''\n'", "key:Enter"), tool.calls);
    }

    @Test public void explicitSendDoesNotSendEnterForBlankTextOrWhenPreferenceIsOff() {
        RecordingTool blank = new RecordingTool();
        assertFalse(InlineTextDispatch.send(blank, " \n\t", true));
        assertTrue(blank.calls.isEmpty());

        RecordingTool textOnly = new RecordingTool();
        assertTrue(InlineTextDispatch.send(textOnly, "non-ASCII: ñ", false));
        assertEquals(List.of("type:'non-ASCII: ñ'"), textOnly.calls);
    }

    @Test public void unavailableDestinationScopedToolDoesNotDispatch() {
        assertFalse(InlineTextDispatch.send(null, "must not escape its input surface", true));
    }
}
