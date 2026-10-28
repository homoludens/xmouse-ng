package net.droopia.xmouse_ng;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** Tests the adapter's extracted dispatch with no Android classes or concrete SSH handler. */
@RunWith(DataProviderRunner.class)
public class CustomKeyboardButtonAdapterTest {
    @DataProvider
    public static Object[][] commandTestData() {
        return new Object[][]{
                {"xdotool key ctrl+alt+f1"  , "ydotool key 29:1 56:1 59:1 59:0 56:0 29:0" , "xdotool key ctrl+alt+f1"},
                {"xdotool windowsize 1"     , "xdotool windowsize 1"                      , "xdotool windowsize 1"},
                {"key ctrl+alt+f1"          , "ydotool key 29:1 56:1 59:1 59:0 56:0 29:0" , "xdotool key ctrl+alt+f1"},
                {"mousemoverelative -12 5"  , "ydotool mousemove -- -12 5"                , "xdotool mousemove_relative -- -12.0 5.0"},
                {"mouseclick 1"             , "ydotool click 0xC0"                        , "xdotool click 1"},
                {"mousedown 1"              , "ydotool click 0x40"                        , "xdotool mousedown 1"},
                {"mouseup 1"                , "ydotool click 0x80"                        , "xdotool mouseup 1"},
                {"mousewheelup"             , "ydotool key 103:1 103:0"                   , "xdotool click 4"},
                {"mousewheeldown"           , "ydotool key 108:1 108:0"                   , "xdotool click 5"},
                {"type hello"               , "ydotool type hello"                        , "xdotool type hello"},
                {"ssh hello@world"          , "ssh hello@world"                           , "ssh hello@world"},
                {"type hello world"         , "ydotool type hello"                        , "xdotool type hello"},
                {"type  hello"              , "ydotool type "                             , "xdotool type "},
                {"xdotool  key ctrl+t"      , "xdotool  key ctrl+t"                       , "xdotool  key ctrl+t"},
                {"XDOTOOL key ctrl+t"       , "XDOTOOL key ctrl+t"                        , "XDOTOOL key ctrl+t"},
                {"xdotool click 1"          , "xdotool click 1"                           , "xdotool click 1"},
                {"xdotool mousemove_relative -- -12 5", "xdotool mousemove_relative -- -12 5", "xdotool mousemove_relative -- -12 5"},
                {"printf 'héllo, world'\nprintf done", "printf 'héllo, world'\nprintf done", "printf 'héllo, world'\nprintf done"},
                {""                        , ""                                          , ""},
        };
    }

    @Test
    @UseDataProvider("commandTestData")
    public void execYdoTool(String command, String yDoToolCmd, String xDoToolCmd) {
        RecordingSshTransport transport = new RecordingSshTransport();
        runExecTest(command, yDoToolCmd, new YDoTool(transport), transport);
    }

    @Test
    @UseDataProvider("commandTestData")
    public void execXdoTool(String command, String yDoToolCmd, String xDoToolCmd) {
        RecordingSshTransport transport = new RecordingSshTransport();
        runExecTest(command, xDoToolCmd, new XDoTool(transport), transport);
    }

    private void runExecTest(String command, String shellCommand, IDoTool doTool, RecordingSshTransport transport) {
        CustomCommandDispatcher.execCommand(command, doTool, transport);
        assertEquals(Collections.singletonList(shellCommand), transport.getCommands());
    }
}
