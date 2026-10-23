package com.stripe1.xmouse.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutProfileJsonTest {
    @Test
    fun versionedRoundTripPreservesRawTextAndRejectsInvalidProperties() {
        val rawCommand = "printf '%s, 世界' \"quoted value\"   keep spaces\nsecond line\r\n"
        val profile = LayoutProfile(
            id = "profile-main",
            name = "Büro, \"remote\"",
            buttons = listOf(
                LayoutButton(
                    id = "button-first",
                    title = "开始, \"one\"",
                    command = rawCommand,
                    width = 1,
                    color = "#a0B1c2",
                ),
                LayoutButton(
                    id = "button-last",
                    title = "Wide button",
                    command = "echo  ten columns",
                    width = 10,
                    color = "#80ABCDEF",
                ),
            ),
        )

        val serialized = LayoutProfileJson.encode(profile)
        assertTrue(serialized.contains("\"version\":${LayoutProfileJson.CURRENT_VERSION}"))
        val restored = LayoutProfileJson.decode(serialized)
        assertEquals(profile, restored)
        assertEquals(rawCommand, restored.buttons.first().command)
        assertEquals(LAYOUT_COLUMN_COUNT, restored.columnCount)

        assertThrows(IllegalArgumentException::class.java) {
            LayoutProfileJson.decode(serialized.replace("\"version\":1", "\"version\":2"))
        }
        assertThrows(IllegalArgumentException::class.java) { LayoutProfile(name = "  ") }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutButton(title = "Blank width", command = "valid command", width = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutButton(title = "Too wide", command = "valid command", width = 11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutButton(title = "   ", command = "valid command", width = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutButton(title = "Blank command", command = "  ", width = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutButton(title = "Bad color", command = "valid command", width = 1, color = "not-a-color")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutProfile(name = "Wrong grid", columnCount = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutProfile(name = "Duplicate IDs", buttons = listOf(profile.buttons[0], profile.buttons[0]))
        }
    }
}
