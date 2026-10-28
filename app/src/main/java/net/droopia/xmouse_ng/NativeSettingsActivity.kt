package net.droopia.xmouse_ng

/** Main entry point for the shared native settings screen and its section routes. */
class NativeSettingsActivity : SharedSettingsActivity() {
    companion object {
        const val EXTRA_SECTION = "native-settings-section"
        const val SECTION_ALL = "all"
        const val SECTION_POINTER = "pointer"
        const val SECTION_KEYBOARD = "keyboard"
        const val SECTION_AUTHENTICATION = "authentication"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_ABOUT = "about"
    }
}
