package net.droopia.xmouse_ng.layout

/** The command surface is intentionally narrower than the legacy dispatcher. */
enum class ActionCommandType {
    KEYBOARD_SHORTCUT,
    ADVANCED_CUSTOM_COMMAND,
}

data class ParsedActionCommand(val type: ActionCommandType, val value: String)

/**
 * Converts only exact, single-chord key forms. Every other legacy command remains opaque raw
 * text, including prefixes, extra flags, whitespace, and multiline strings.
 */
object ActionCommandCodec {
    private const val CHORD = "[A-Za-z0-9_][A-Za-z0-9_-]*(?:\\+[A-Za-z0-9_][A-Za-z0-9_-]*)*"
    private val keyCommand = Regex("(?:key |xdotool key )($CHORD)")
    private val chord = Regex(CHORD)

    fun classify(command: String): ParsedActionCommand {
        val match = keyCommand.matchEntire(command)
        return if (match == null) {
            ParsedActionCommand(ActionCommandType.ADVANCED_CUSTOM_COMMAND, command)
        } else {
            ParsedActionCommand(ActionCommandType.KEYBOARD_SHORTCUT, match.groupValues[1])
        }
    }

    /** Preserve the original bytes unless the user deliberately changes the command value/type. */
    fun serialize(type: ActionCommandType, value: String, originalCommand: String): String = when (type) {
        ActionCommandType.ADVANCED_CUSTOM_COMMAND ->
            if (value == originalCommand) originalCommand else value
        ActionCommandType.KEYBOARD_SHORTCUT -> {
            require(value.length <= MAX_CHORD_LENGTH && chord.matches(value)) {
                "Enter one key chord using letters, numbers, underscores, hyphens, and +."
            }
            val original = classify(originalCommand)
            if (original.type == ActionCommandType.KEYBOARD_SHORTCUT && original.value == value) {
                originalCommand
            } else {
                "key $value"
            }
        }
    }

    const val MAX_CHORD_LENGTH = 64
}

/** Small presentation helpers shared by the native Actions screen and layout manager. */
object ActionPresentation {
    fun layoutChoices(profiles: List<LayoutProfile>): List<LayoutProfileChoice> =
        profiles.map { LayoutProfileChoice(it.id, it.name) }
}

data class LayoutProfileChoice(val id: String, val name: String)

data class SavedActionScript(val id: Int, val name: String)

/**
 * The built-in legacy grid is a compatibility fallback, not fresh-install demo data. This helper
 * only creates the single empty active profile when no source, profile, preference, host, or script
 * data exists. Existing migration inputs and saved profiles always win.
 */
object FreshActionsProfileInitializer {
    fun initializeIfFresh(
        repository: LayoutProfileRepository,
        hasLegacySource: Boolean,
        hasUserData: Boolean,
    ): LayoutProfile? {
        if (hasLegacySource || hasUserData || repository.listProfiles().isNotEmpty()) return null
        val empty = LayoutProfile(
            id = LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID,
            name = "Actions",
            buttons = emptyList(),
        )
        repository.createProfile(empty)
        repository.selectProfile(empty.id)
        return empty
    }

    /** Upgrade-only preservation of the old built-in fallback when no file/profile was ever saved. */
    fun preserveLegacyDefaultsIfNeeded(
        repository: LayoutProfileRepository,
        hasLegacySource: Boolean,
        hasUserData: Boolean,
        legacyDefaults: List<LayoutButton>,
    ): LayoutProfile? {
        if (hasLegacySource || !hasUserData || repository.listProfiles().isNotEmpty()) return null
        val retained = LayoutProfile(
            id = LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID,
            name = "Active layout",
            buttons = legacyDefaults.toList(),
        )
        repository.createProfile(retained)
        repository.selectProfile(retained.id)
        return retained
    }
}
