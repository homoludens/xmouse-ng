package net.droopia.xmouse_ng.layout

/** Canonical stock keyboard table shared by saved-profile seeding and the legacy adapter. */
object DefaultKeysLayoutCatalog {
    const val PROFILE_ID = "builtin-keys-layout"
    const val PROFILE_NAME = "Keys Layout"

    private val stockButtons = listOf(
        button(1, "Esc", "key Escape", 2, "#FFe6e6"),
        button(2, "Minimize", "xdotool windowminimize $(xdotool getactivewindow)", 3, "#9FFF80"),
        button(3, "Maximize", "xdotool windowsize $(xdotool getactivewindow) 100% 100%", 3, "#FFCC66"),
        button(4, "X", "key alt+F4", 2, "#FF9999"),
        button(5, "F1", "key F1", 2, "#FFFFFF"),
        button(6, "F2", "key F2", 2, "#FFFFFF"),
        button(7, "F3", "key F3", 2, "#FFFFFF"),
        button(8, "F4", "key F4", 2, "#FFFFFF"),
        button(9, "F5", "key F5", 2, "#FFFFFF"),
        button(10, "F6", "key F6", 2, "#FFFFFF"),
        button(11, "F7", "key F7", 2, "#FFFFFF"),
        button(12, "F8", "key F8", 2, "#FFFFFF"),
        button(13, "F9", "key F9", 2, "#FFFFFF"),
        button(14, "F10", "key F10", 2, "#FFFFFF"),
        button(15, "F11", "key F11", 2, "#FFFFFF"),
        button(16, "F12", "key F12", 2, "#FFFFFF"),
        button(17, "+Brightness", "key XF86MonBrightnessUp", 3, "#e6f7ff"),
        button(18, "-Brightness", "key XF86MonBrightnessDown", 3, "#e6f7ff"),
        button(19, "Super", "key super", 2, "#80d4ff"),
        button(20, "+Volume", "key XF86AudioRaiseVolume", 3, "#B3E6FF"),
        button(21, "-Volume", "key XF86AudioLowerVolume", 3, "#B3E6FF"),
        button(22, "Mute", "key XF86AudioMute", 2, "#B3E6FF"),
        button(23, "Alt|Tab", "key alt+Tab", 2, "#80d4ff"),
        button(24, "[Prev]", "key XF86AudioPrev", 3, "#B3E6FF"),
        button(25, "[Next]", "key XF86AudioNext", 3, "#B3E6FF"),
        button(26, "[Play]", "key XF86AudioPlay", 2, "#B3E6FF"),
        button(27, "New", "key ctrl+n", 2, "#E6B3FF"),
        button(28, "Open", "key ctrl+o", 2, "#E6B3FF"),
        button(29, "Save", "key ctrl+s", 2, "#E6B3FF"),
        button(30, "Find", "key ctrl+f", 2, "#E6B3FF"),
        button(31, "Print", "key ctrl+p", 2, "#E6B3FF"),
        button(32, "Select all", "key ctrl+a", 4, "#E6B3FF"),
        button(33, "+Zoom", "key ctrl+plus", 3, "#E6B3FF"),
        button(34, "-Zoom", "key ctrl+minus", 3, "#E6B3FF"),
        button(35, "Undo", "key ctrl+z", 2, "#F7E6FF"),
        button(36, "Redo", "key ctrl+y", 2, "#F7E6FF"),
        button(37, "Copy", "key ctrl+c", 2, "#F7E6FF"),
        button(38, "Crop", "key ctrl+x", 2, "#F7E6FF"),
        button(39, "Paste", "key ctrl+v", 2, "#F7E6FF"),
        button(40, "PgUp", "key Prior", 3, "#FFFFFF"),
        button(41, "PgDown", "key Next", 3, "#FFFFFF"),
        button(42, "Delete", "key Delete", 4, "#FFFFFF"),
        button(43, "Home", "key Home", 2, "#FFFFFF"),
        button(44, "Up", "key Up", 2, "#FFFFB3"),
        button(45, "End", "key End", 2, "#FFFFFF"),
        button(46, "Backspace", "key BackSpace", 4, "#FFFFFF"),
        button(47, "Left", "key Left", 2, "#FFFFB3"),
        button(48, "Down", "key Down", 2, "#FFFFB3"),
        button(49, "Right", "key Right", 2, "#FFFFB3"),
        button(50, "Enter", "key Return", 4, "#FFFFFF"),
        button(51, "<Back", "key XF86Back", 5, "#f2e5d9"),
        button(52, "Forward>", "key XF86Forward", 5, "#f2e5d9"),
        button(53, "+", "key ctrl+t", 1, "#e6ccb3"),
        button(54, "-", "key ctrl+w", 1, "#e6ccb3"),
        button(55, "Reopen Last Tab", "key ctrl+shift+t", 4, "#e6ccb3"),
        button(56, "<-", "key ctrl+shift+Tab", 2, "#e6ccb3"),
        button(57, "->", "key ctrl+Tab", 2, "#e6ccb3"),
    )

    @JvmStatic
    fun buttons(): List<LayoutButton> = stockButtons.toList()

    @JvmStatic
    fun profile(): LayoutProfile = LayoutProfile(
        id = PROFILE_ID,
        name = PROFILE_NAME,
        buttons = buttons(),
    )

    private fun button(index: Int, title: String, command: String, width: Int, color: String) =
        LayoutButton("keys-layout-${index.toString().padStart(2, '0')}", title, command, width, color)
}
