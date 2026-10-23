package com.stripe1.xmouse.layout

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
    private var repositoryActive = false
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

        val activeProfile = try {
            repository.loadProfile(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID)
        } catch (_: Exception) {
            return LayoutProfileActivation(
                null,
                "The active legacy layout is missing or unreadable. Restore or repair keyLayoutFile.csv and restart; legacy layouts remain available read-only.",
            )
        }

        val selected = try {
            repository.loadSelectedProfile() ?: activeProfile.also {
                repository.selectProfile(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID)
            }
        } catch (_: Exception) {
            return LayoutProfileActivation(null, genericWarning())
        }

        selectedProfileId = selected.id
        repositoryActive = true
        return LayoutProfileActivation(selected, null)
    }

    fun isRepositoryActive(): Boolean = synchronized(this) { repositoryActive }

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
