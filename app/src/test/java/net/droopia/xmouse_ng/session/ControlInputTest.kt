package net.droopia.xmouse_ng.session

import org.junit.Assert.*
import org.junit.Test

class ControlInputTest {
    @Test fun heldParserRecognizesOnlyKnownAdapterTranslations() {
        assertEquals(
            TrackedMouseButtonCommand("xdotool:1", true, "xdotool mouseup 1"),
            TrackedInputCommand.parse("xdotool mousedown 1"),
        )
        assertEquals(
            TrackedMouseButtonCommand("ydotool:0", true, "ydotool click 0x80"),
            TrackedInputCommand.parse("ydotool click 0x40"),
        )
        assertEquals(
            TrackedMouseButtonCommand("ydotool:1", false, "ydotool click 0x81"),
            TrackedInputCommand.parse("ydotool click 0x81"),
        )
        listOf(
            "xdotool mousedown 4", "xdotool mouseup 0", "xdotool mousedown 1; arbitrary",
            "printf 'xdotool mousedown 1'", "ydotool click 0xC0", "ydotool click 0x400",
        ).forEach { assertNull("arbitrary shell and non-held commands remain opaque: $it", TrackedInputCommand.parse(it)) }
    }
}
