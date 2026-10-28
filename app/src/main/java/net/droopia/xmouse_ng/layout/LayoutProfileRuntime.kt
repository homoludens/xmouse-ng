package net.droopia.xmouse_ng.layout

/** The result of attempting to cross the legacy-source migration boundary. */
data class LayoutProfileActivation(
    val profile: LayoutProfile?,
    val warning: String?,
) {
    val activated: Boolean
        get() = profile != null && warning == null

    /** Failed activation leaves legacy data available for display, but never safe to overwrite. */
    val readOnlyLegacyFallback: Boolean
        get() = !activated
}

/**
 * Activates repository layouts only after every present legacy source has migrated cleanly and
 * the required active profile is readable. Until then callers may use the legacy renderer only as
 * a read-only recovery view.
 */
class LayoutProfileRuntime(
    private val repository: LayoutProfileRepository,
    private val migrator: LegacyLayoutProfileMigrator,
) {
    @Volatile private var repositoryActive = false
    private var selectedProfileId: String? = null

    @Synchronized
    fun activate(): LayoutProfileActivation {
        repositoryActive = false
        selectedProfileId = null

        val migration = try {
            migrator.migrateWithReport()
        } catch (_: Exception) {
            return LayoutProfileActivation(null, genericWarning())
        }
        if (migration.warnings.isNotEmpty()) {
            val safeWarning = migration.warnings.first().message
            return LayoutProfileActivation(
                null,
                "Layout migration is incomplete. $safeWarning Legacy layouts remain available read-only until the source is repaired.",
            )
        }

        val selected = try {
            // Named profiles remain valid when the initial empty Actions profile was deliberately
            // deleted in the manager. Migration warnings above still fail closed for damaged sources.
            repository.loadSelectedProfile() ?: repository.listProfiles().firstOrNull()?.also {
                repository.selectProfile(it.id)
            }
        } catch (_: Exception) {
            return LayoutProfileActivation(null, genericWarning())
        } ?: return LayoutProfileActivation(null, genericWarning())

        selectedProfileId = selected.id
        repositoryActive = true
        return LayoutProfileActivation(selected, null)
    }

    fun isRepositoryActive(): Boolean = repositoryActive

    @Synchronized
    fun selectProfile(profileId: String): LayoutProfile {
        check(repositoryActive) { "Repository layouts are not active" }
        val profile = repository.loadProfile(profileId)
        repository.selectProfile(profileId)
        selectedProfileId = profileId
        return profile
    }

    /** Reload the repository selection after a manager/editor return without writing the catalog. */
    @Synchronized
    fun loadSelectedProfile(): LayoutProfile? {
        check(repositoryActive) { "Repository layouts are not active" }
        val selected = repository.loadSelectedProfile() ?: return null
        selectedProfileId = selected.id
        return selected
    }

    /** Saves the currently selected runtime profile without ever writing a legacy source file. */
    @Synchronized
    fun saveCurrentProfile(buttons: List<LayoutButton>): LayoutProfile? {
        if (!repositoryActive) return null
        val profileId = selectedProfileId ?: return null
        val saved = repository.loadProfile(profileId).copy(buttons = buttons.toList())
        repository.saveProfile(saved)
        return saved
    }

    /** Legacy UI Save captures its profile before queuing; a later selection cannot redirect it. */
    @Synchronized
    fun saveCurrentProfileIfSelected(profileId: String, buttons: List<LayoutButton>): LayoutProfile? {
        if (selectedProfileId != profileId) return null
        return saveCurrentProfile(buttons)
    }

    /** Save-as targets use stable IDs; missing legacy preset IDs are created only on user action. */
    @Synchronized
    fun saveProfileAs(profileId: String, name: String, buttons: List<LayoutButton>): LayoutProfile {
        check(repositoryActive) { "Repository layouts are not active" }
        val independentButtons = buttons.map { button -> button.copy(id = java.util.UUID.randomUUID().toString()) }
        val existing = try {
            repository.loadProfile(profileId)
        } catch (_: LayoutProfileNotFoundException) {
            null
        }
        val profile = existing?.copy(buttons = independentButtons)
            ?: LayoutProfile(id = profileId, name = name, buttons = independentButtons)
        if (existing == null) repository.createProfile(profile) else repository.saveProfile(profile)
        return profile
    }

    private fun genericWarning() =
        "Layout profiles could not be activated. Check retained layout files and storage, then restart; legacy layouts remain available read-only."
}
