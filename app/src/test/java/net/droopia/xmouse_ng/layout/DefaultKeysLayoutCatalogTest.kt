package net.droopia.xmouse_ng.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultKeysLayoutCatalogTest {
    @Test
    fun stockCatalogMatchesEveryOriginalButtonInOrder() {
        val expected = listOf(
            Expected("Esc", "key Escape", 2, "#FFe6e6"),
            Expected("Minimize", "xdotool windowminimize $(xdotool getactivewindow)", 3, "#9FFF80"),
            Expected("Maximize", "xdotool windowsize $(xdotool getactivewindow) 100% 100%", 3, "#FFCC66"),
            Expected("X", "key alt+F4", 2, "#FF9999"),
            Expected("F1", "key F1", 2, "#FFFFFF"),
            Expected("F2", "key F2", 2, "#FFFFFF"),
            Expected("F3", "key F3", 2, "#FFFFFF"),
            Expected("F4", "key F4", 2, "#FFFFFF"),
            Expected("F5", "key F5", 2, "#FFFFFF"),
            Expected("F6", "key F6", 2, "#FFFFFF"),
            Expected("F7", "key F7", 2, "#FFFFFF"),
            Expected("F8", "key F8", 2, "#FFFFFF"),
            Expected("F9", "key F9", 2, "#FFFFFF"),
            Expected("F10", "key F10", 2, "#FFFFFF"),
            Expected("F11", "key F11", 2, "#FFFFFF"),
            Expected("F12", "key F12", 2, "#FFFFFF"),
            Expected("+Brightness", "key XF86MonBrightnessUp", 3, "#e6f7ff"),
            Expected("-Brightness", "key XF86MonBrightnessDown", 3, "#e6f7ff"),
            Expected("Super", "key super", 2, "#80d4ff"),
            Expected("+Volume", "key XF86AudioRaiseVolume", 3, "#B3E6FF"),
            Expected("-Volume", "key XF86AudioLowerVolume", 3, "#B3E6FF"),
            Expected("Mute", "key XF86AudioMute", 2, "#B3E6FF"),
            Expected("Alt|Tab", "key alt+Tab", 2, "#80d4ff"),
            Expected("[Prev]", "key XF86AudioPrev", 3, "#B3E6FF"),
            Expected("[Next]", "key XF86AudioNext", 3, "#B3E6FF"),
            Expected("[Play]", "key XF86AudioPlay", 2, "#B3E6FF"),
            Expected("New", "key ctrl+n", 2, "#E6B3FF"),
            Expected("Open", "key ctrl+o", 2, "#E6B3FF"),
            Expected("Save", "key ctrl+s", 2, "#E6B3FF"),
            Expected("Find", "key ctrl+f", 2, "#E6B3FF"),
            Expected("Print", "key ctrl+p", 2, "#E6B3FF"),
            Expected("Select all", "key ctrl+a", 4, "#E6B3FF"),
            Expected("+Zoom", "key ctrl+plus", 3, "#E6B3FF"),
            Expected("-Zoom", "key ctrl+minus", 3, "#E6B3FF"),
            Expected("Undo", "key ctrl+z", 2, "#F7E6FF"),
            Expected("Redo", "key ctrl+y", 2, "#F7E6FF"),
            Expected("Copy", "key ctrl+c", 2, "#F7E6FF"),
            Expected("Crop", "key ctrl+x", 2, "#F7E6FF"),
            Expected("Paste", "key ctrl+v", 2, "#F7E6FF"),
            Expected("PgUp", "key Prior", 3, "#FFFFFF"),
            Expected("PgDown", "key Next", 3, "#FFFFFF"),
            Expected("Delete", "key Delete", 4, "#FFFFFF"),
            Expected("Home", "key Home", 2, "#FFFFFF"),
            Expected("Up", "key Up", 2, "#FFFFB3"),
            Expected("End", "key End", 2, "#FFFFFF"),
            Expected("Backspace", "key BackSpace", 4, "#FFFFFF"),
            Expected("Left", "key Left", 2, "#FFFFB3"),
            Expected("Down", "key Down", 2, "#FFFFB3"),
            Expected("Right", "key Right", 2, "#FFFFB3"),
            Expected("Enter", "key Return", 4, "#FFFFFF"),
            Expected("<Back", "key XF86Back", 5, "#f2e5d9"),
            Expected("Forward>", "key XF86Forward", 5, "#f2e5d9"),
            Expected("+", "key ctrl+t", 1, "#e6ccb3"),
            Expected("-", "key ctrl+w", 1, "#e6ccb3"),
            Expected("Reopen Last Tab", "key ctrl+shift+t", 4, "#e6ccb3"),
            Expected("<-", "key ctrl+shift+Tab", 2, "#e6ccb3"),
            Expected("->", "key ctrl+Tab", 2, "#e6ccb3"),
        )

        val actual = DefaultKeysLayoutCatalog.buttons().map { Expected(it.title, it.command, it.width, it.color) }
        assertEquals(57, actual.size)
        assertEquals(expected, actual)

        val profile = DefaultKeysLayoutCatalog.profile()
        assertEquals("builtin-keys-layout", profile.id)
        assertEquals("Keys Layout", profile.name)
        assertEquals(DefaultKeysLayoutCatalog.buttons(), profile.buttons)
    }

    private data class Expected(val title: String, val command: String, val width: Int, val color: String)
}
