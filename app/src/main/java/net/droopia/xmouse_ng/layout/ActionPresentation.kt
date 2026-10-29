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

/** Seeds the original table once without replacing saved profiles, drafts, or migration sources. */
object DefaultKeysLayoutProfileInitializer {
    @Synchronized
    fun initializeIfNeeded(repository: LayoutProfileRepository): LayoutProfile? =
        initialize(repository, preserveExistingSelection = false)

    @Synchronized
    fun initializeAfterSuccessfulMigration(
        repository: LayoutProfileRepository,
        activation: LayoutProfileActivation,
    ): LayoutProfile? {
        if (!activation.activated) return null
        return initialize(repository, preserveExistingSelection = true)
    }

    private fun initialize(
        repository: LayoutProfileRepository,
        preserveExistingSelection: Boolean,
    ): LayoutProfile? {
        if (repository.isDefaultKeysLayoutInitializationComplete()) return null

        val profiles = repository.listProfiles()
        if (profiles.any { it.id == DefaultKeysLayoutCatalog.PROFILE_ID }) {
            repository.markDefaultKeysLayoutInitializationComplete()
            return null
        }

        val selected = repository.loadSelectedProfile()
        val shouldSelectStock = !preserveExistingSelection && (profiles.isEmpty() || (
            profiles.size == 1 &&
                profiles.single().id == LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID &&
                profiles.single().name == "Actions" &&
                profiles.single().buttons.isEmpty() &&
                repository.loadDraft(profiles.single().id) == null &&
                selected?.id == LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID
            ))

        val stock = DefaultKeysLayoutCatalog.profile()
        try {
            repository.createProfile(stock)
        } catch (_: LayoutProfileAlreadyExistsException) {
            // An orphaned profile/draft at the reserved ID is user-owned; leave it untouched.
            return null
        }
        if (shouldSelectStock) repository.selectProfile(stock.id)
        repository.markDefaultKeysLayoutInitializationComplete()
        return stock
    }
}
