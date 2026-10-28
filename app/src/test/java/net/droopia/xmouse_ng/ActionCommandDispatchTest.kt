package net.droopia.xmouse_ng

import net.droopia.xmouse_ng.layout.ActionCommandCodec
import net.droopia.xmouse_ng.layout.ActionCommandType
import org.junit.Assert.*
import org.junit.Test

class ActionCommandDispatchTest {
    @Test fun newAltF4ShortcutUsesTheKeyContractForBothBackends() {
        val command = ActionCommandCodec.serialize(ActionCommandType.KEYBOARD_SHORTCUT, "Alt+F4", "")
        for (xdotool in listOf(true, false)) {
            val transport = RecordingSshTransport()
            val tool = if (xdotool) XDoTool(transport) else YDoTool(transport)
            CustomCommandDispatcher.execCommand(command, tool, transport)
            assertEquals(listOf(if (xdotool) "xdotool key Alt+F4" else "ydotool key 56:1 62:1 62:0 56:0"),
                transport.commands)
        }
    }

    @Test fun legacyPrefixFirstKeyArgumentAndOpaqueRawFallbackAreUnchanged() {
        val transport = RecordingSshTransport()
        val tool = XDoTool(transport)
        for (command in listOf("key alt+F4", "xdotool key alt+F4", "xdotool key --clearmodifiers alt+F4"))
            CustomCommandDispatcher.execCommand(command, tool, transport)
        val opaque = "printf 'exact  raw'\nnext line  "
        val embeddedPrefix = "echo xdotool key alt+F4"
        CustomCommandDispatcher.execCommand(opaque, tool, transport)
        CustomCommandDispatcher.execCommand(embeddedPrefix, tool, transport)
        assertEquals(listOf("xdotool key alt+F4", "xdotool key alt+F4", "xdotool key --clearmodifiers",
            opaque, embeddedPrefix), transport.commands)
    }
}
