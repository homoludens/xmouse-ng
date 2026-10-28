package net.droopia.xmouse_ng;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(DataProviderRunner.class)
public class DoToolTransportTest {
    @DataProvider
    public static Object[][] backends() {
        return new Object[][]{{true}, {false}};
    }

    @Test
    @UseDataProvider("backends")
    public void mouseCommandsPreserveMappingPrecisionAndOrder(boolean xdotool) {
        RecordingSshTransport transport = new RecordingSshTransport();
        IDoTool tool = tool(xdotool, transport);

        tool.mouseMoveRelative(-12.75f, 5.9f);
        for (int button : new int[]{IDoTool.MOUSE_LEFT, IDoTool.MOUSE_MIDDLE, IDoTool.MOUSE_RIGHT}) {
            tool.mouseClick(button);
            tool.mouseDown(button);
            tool.mouseUp(button);
        }
        tool.mouseWheelUp();
        tool.mouseWheelDown();

        String[] expected = xdotool ? new String[]{
                "xdotool mousemove_relative -- -12.75 5.9",
                "xdotool click 1", "xdotool mousedown 1", "xdotool mouseup 1",
                "xdotool click 2", "xdotool mousedown 2", "xdotool mouseup 2",
                "xdotool click 3", "xdotool mousedown 3", "xdotool mouseup 3",
                "xdotool click 4", "xdotool click 5"
        } : new String[]{
                "ydotool mousemove -- -12 5",
                "ydotool click 0xC0", "ydotool click 0x40", "ydotool click 0x80",
                "ydotool click 0xC2", "ydotool click 0x42", "ydotool click 0x82",
                "ydotool click 0xC1", "ydotool click 0x41", "ydotool click 0x81",
                "ydotool key 103:1 103:0", "ydotool key 108:1 108:0"
        };
        assertEquals(Arrays.asList(expected), transport.getCommands());
    }

    @Test
    @UseDataProvider("backends")
    public void directKeyAndTypeKeepExistingBackendTranslation(boolean xdotool) {
        RecordingSshTransport transport = new RecordingSshTransport();
        IDoTool tool = tool(xdotool, transport);

        tool.key("Ctrl+Alt+F1");
        tool.type("héllo 'world',\nnext");

        assertEquals(Arrays.asList(
                xdotool ? "xdotool key Ctrl+Alt+F1" : "ydotool key 29:1 56:1 59:1 59:0 56:0 29:0",
                (xdotool ? "xdotool" : "ydotool") + " type héllo 'world',\nnext"
        ), transport.getCommands());
    }

    @Test
    @UseDataProvider("backends")
    public void failedWriteStillLeavesAdaptersAndDispatchVoidWithoutRetries(boolean xdotool) {
        RecordingSshTransport transport = new RecordingSshTransport(false);
        IDoTool tool = tool(xdotool, transport);

        tool.type("hello");
        CustomCommandDispatcher.execCommand("printf unchanged", tool, transport);

        assertEquals(Arrays.asList(
                (xdotool ? "xdotool" : "ydotool") + " type hello", "printf unchanged"
        ), transport.getCommands());
    }

    @Test
    @UseDataProvider("backends")
    public void invalidMouseButtonsStillThrowWithoutWriting(boolean xdotool) {
        RecordingSshTransport transport = new RecordingSshTransport();
        IDoTool tool = tool(xdotool, transport);

        assertThrows(RuntimeException.class, () -> tool.mouseClick(7));
        assertThrows(RuntimeException.class, () -> tool.mouseDown(7));
        assertThrows(RuntimeException.class, () -> tool.mouseUp(7));
        assertTrue(transport.getCommands().isEmpty());
    }

    @Test
    public void fakePreservesTheTransportBooleanResult() {
        CommandTransport successful = new RecordingSshTransport();
        CommandTransport failed = new RecordingSshTransport(false);

        assertTrue(successful.executeShellCommand("printf fixture"));
        assertFalse(failed.executeShellCommand("printf fixture"));
    }

    private IDoTool tool(boolean xdotool, CommandTransport transport) {
        return xdotool ? new XDoTool(transport) : new YDoTool(transport);
    }
}
