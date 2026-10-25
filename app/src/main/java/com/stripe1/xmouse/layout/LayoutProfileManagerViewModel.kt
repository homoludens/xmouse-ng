package com.stripe1.xmouse.layout

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class LayoutProfileNameAction {
    CREATE,
    RENAME,
    DUPLICATE,
}

data class LayoutProfileManagerState(
    val profiles: List<LayoutProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val isLoading: Boolean = true,
    val nameAction: LayoutProfileNameAction? = null,
    val nameTargetProfileId: String? = null,
    val nameInput: String = "",
    val nameError: String? = null,
    val profileToDeleteId: String? = null,
    val errorMessage: String? = null,
)

/** User-facing named-layout operations, always addressed by stable profile ID. */
class LayoutProfileManagerViewModel(
    private val repository: LayoutProfileRepository,
    autoRefresh: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LayoutProfileManagerState())
    val state = mutableState.asStateFlow()

    init {
        if (autoRefresh) refresh()
    }

    fun refresh() {
        val current = mutableState.value
        try {
            val profiles = repository.listProfiles()
            val selectedProfileId = repository.loadSelectedProfile()?.id
                ?.takeIf { selectedId -> profiles.any { it.id == selectedId } }
            mutableState.value = current.copy(
                profiles = profiles.toList(),
                selectedProfileId = selectedProfileId,
                isLoading = false,
                errorMessage = null,
            )
        } catch (_: Exception) {
            mutableState.value = current.copy(
                isLoading = false,
                errorMessage = LOAD_ERROR,
            )
        }
    }

    fun beginCreate() {
        mutableState.value = mutableState.value.copy(
            nameAction = LayoutProfileNameAction.CREATE,
            nameTargetProfileId = null,
            nameInput = "",
            nameError = null,
            errorMessage = null,
        )
    }

    fun beginRename(profileId: String) = beginNameAction(profileId, LayoutProfileNameAction.RENAME)

    fun beginDuplicate(profileId: String) = beginNameAction(profileId, LayoutProfileNameAction.DUPLICATE)

    private fun beginNameAction(profileId: String, action: LayoutProfileNameAction) {
        val current = mutableState.value
        val profile = current.profiles.firstOrNull { it.id == profileId }
        if (profile == null) {
            mutableState.value = current.copy(errorMessage = PROFILE_UNAVAILABLE_ERROR)
            return
        }
        mutableState.value = current.copy(
            nameAction = action,
            nameTargetProfileId = profile.id,
            nameInput = profile.name,
            nameError = null,
            errorMessage = null,
        )
    }

    fun updateNameInput(value: String) {
        mutableState.value = mutableState.value.copy(nameInput = value, nameError = null)
    }

    fun cancelNameAction() {
        mutableState.value = mutableState.value.copy(
            nameAction = null,
            nameTargetProfileId = null,
            nameInput = "",
            nameError = null,
        )
    }

    /** Validation and repository failures leave the dialog and entered name intact for correction. */
    fun commitNameAction(): Boolean {
        val current = mutableState.value
        val action = current.nameAction ?: return false
        if (current.nameInput.isBlank()) {
            mutableState.value = current.copy(nameError = REQUIRED_NAME_ERROR)
            return false
        }

        return try {
            when (action) {
                LayoutProfileNameAction.CREATE -> repository.createProfile(
                    LayoutProfile(id = UUID.randomUUID().toString(), name = current.nameInput),
                )
                LayoutProfileNameAction.RENAME -> repository.renameProfile(
                    requireNotNull(current.nameTargetProfileId), current.nameInput,
                )
                LayoutProfileNameAction.DUPLICATE -> repository.duplicateProfile(
                    requireNotNull(current.nameTargetProfileId), current.nameInput,
                )
            }
            refresh()
            mutableState.value = mutableState.value.copy(
                nameAction = null,
                nameTargetProfileId = null,
                nameInput = "",
                nameError = null,
            )
            true
        } catch (_: Exception) {
            mutableState.value = current.copy(nameError = SAVE_ERROR)
            false
        }
    }

    fun selectProfile(profileId: String): Boolean = try {
        repository.selectProfile(profileId)
        refresh()
        true
    } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(errorMessage = PROFILE_UPDATE_ERROR)
        false
    }

    fun requestDelete(profileId: String) {
        val current = mutableState.value
        if (current.profiles.none { it.id == profileId }) {
            mutableState.value = current.copy(errorMessage = PROFILE_UNAVAILABLE_ERROR)
            return
        }
        if (current.profiles.size <= 1) {
            mutableState.value = current.copy(errorMessage = LAST_PROFILE_ERROR)
            return
        }
        mutableState.value = current.copy(profileToDeleteId = profileId, errorMessage = null)
    }

    /** Dismissing confirmation is deliberately state-only: the repository is never touched. */
    fun cancelDelete() {
        mutableState.value = mutableState.value.copy(profileToDeleteId = null)
    }

    fun confirmDelete(): Boolean {
        val current = mutableState.value
        val profileId = current.profileToDeleteId ?: return false
        return try {
            repository.deleteProfile(profileId, confirmed = true)
            refresh()
            mutableState.value = mutableState.value.copy(profileToDeleteId = null)
            true
        } catch (_: Exception) {
            refresh()
            val refreshed = mutableState.value
            mutableState.value = refreshed.copy(
                profileToDeleteId = profileId.takeIf { targetId ->
                    refreshed.profiles.size > 1 && refreshed.profiles.any { it.id == targetId }
                },
                errorMessage = DELETE_ERROR,
            )
            false
        }
    }

    private companion object {
        const val LOAD_ERROR = "Saved layouts could not be loaded. No layout was changed."
        const val REQUIRED_NAME_ERROR = "Layout name cannot be blank."
        const val SAVE_ERROR = "The layout could not be saved. Keep the name and try again."
        const val PROFILE_UPDATE_ERROR = "The layout could not be updated. No other layout was changed."
        const val DELETE_ERROR = "The layout could not be deleted reliably. Check the saved layout list before retrying."
        const val PROFILE_UNAVAILABLE_ERROR = "That saved layout is no longer available. Refresh and try again."
        const val LAST_PROFILE_ERROR = "Keep at least one saved layout. The last layout cannot be deleted."
    }
}
