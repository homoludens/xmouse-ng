package com.stripe1.xmouse.layout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable UI snapshot for one stable profile identity. */
data class LayoutEditorState(
    val savedProfile: LayoutProfile? = null,
    val draftProfile: LayoutProfile? = null,
    val profileNameInput: String = "",
    val isLoading: Boolean = true,
    val isShowingBackConfirmation: Boolean = false,
    val errorMessage: String? = null,
) {
    val isDirty: Boolean
        get() = savedProfile != null && draftProfile != null &&
            (savedProfile != draftProfile || profileNameInput != draftProfile.name)
}

enum class LayoutEditorBackChoice {
    SAVE,
    DISCARD,
    CANCEL,
}

/** Owns a saved snapshot and a separately persisted draft for a single stable profile ID. */
class LayoutEditorViewModel(
    private val profileId: String,
    private val repository: LayoutProfileRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(loadInitialState())
    val state = mutableState.asStateFlow()

    /** Name editing is the small, non-grid edit surface for this editor foundation. */
    fun updateProfileNameInput(value: String) {
        val current = mutableState.value
        val draft = current.draftProfile ?: return
        if (value.isBlank()) {
            mutableState.value = current.copy(
                profileNameInput = value,
                errorMessage = "Profile name cannot be blank.",
            )
            return
        }

        if (value == draft.name) {
            mutableState.value = current.copy(profileNameInput = value, errorMessage = null)
            return
        }

        val changed = immutableSnapshot(draft.copy(name = value))
        try {
            repository.saveDraft(changed)
            mutableState.value = current.copy(
                draftProfile = changed,
                profileNameInput = value,
                errorMessage = null,
            )
        } catch (_: Exception) {
            // Keep the last known-good snapshot in memory and on disk; Save can retry this input.
            mutableState.value = current.copy(
                profileNameInput = value,
                errorMessage = DRAFT_SAVE_ERROR,
            )
        }
    }

    /** Returns true when Back may leave immediately; dirty state opens the explicit choice dialog. */
    fun requestBack(): Boolean {
        val current = mutableState.value
        if (!current.isDirty) return true
        mutableState.value = current.copy(isShowingBackConfirmation = true)
        return false
    }

    /** Returns true only after Save or Discard has completed successfully. */
    fun resolveBack(choice: LayoutEditorBackChoice): Boolean = when (choice) {
        LayoutEditorBackChoice.CANCEL -> {
            mutableState.value = mutableState.value.copy(isShowingBackConfirmation = false)
            false
        }
        LayoutEditorBackChoice.DISCARD -> discard()
        LayoutEditorBackChoice.SAVE -> save()
    }

    fun save(): Boolean {
        val current = mutableState.value
        val saved = current.savedProfile ?: return false
        var draft = current.draftProfile ?: return false
        if (current.profileNameInput.isBlank()) {
            mutableState.value = current.copy(errorMessage = "Profile name cannot be blank.")
            return false
        }

        if (current.profileNameInput != draft.name) {
            draft = immutableSnapshot(draft.copy(name = current.profileNameInput))
            try {
                repository.saveDraft(draft)
            } catch (_: Exception) {
                mutableState.value = current.copy(errorMessage = DRAFT_SAVE_ERROR)
                return false
            }
        }

        return try {
            repository.saveProfile(draft)
            mutableState.value = current.copy(
                savedProfile = immutableSnapshot(draft),
                draftProfile = immutableSnapshot(draft),
                profileNameInput = draft.name,
                isShowingBackConfirmation = false,
                errorMessage = null,
            )
            true
        } catch (_: Exception) {
            // saveProfile leaves a recoverable draft and the previous saved profile intact.
            mutableState.value = current.copy(
                draftProfile = immutableSnapshot(draft),
                profileNameInput = draft.name,
                errorMessage = SAVE_ERROR,
            )
            false
        }
    }

    fun discard(): Boolean {
        val current = mutableState.value
        val saved = current.savedProfile ?: return false
        return try {
            repository.discardDraft(profileId)
            mutableState.value = current.copy(
                draftProfile = immutableSnapshot(saved),
                profileNameInput = saved.name,
                isShowingBackConfirmation = false,
                errorMessage = null,
            )
            true
        } catch (_: Exception) {
            mutableState.value = current.copy(errorMessage = DISCARD_ERROR)
            false
        }
    }

    private fun loadInitialState(): LayoutEditorState = try {
        val saved = immutableSnapshot(repository.loadProfile(profileId))
        val draft = immutableSnapshot(repository.loadDraft(profileId) ?: saved)
        LayoutEditorState(
            savedProfile = saved,
            draftProfile = draft,
            profileNameInput = draft.name,
            isLoading = false,
        )
    } catch (_: Exception) {
        LayoutEditorState(
            isLoading = false,
            errorMessage = LOAD_ERROR,
        )
    }

    private fun immutableSnapshot(profile: LayoutProfile) = profile.copy(buttons = profile.buttons.toList())

    class Factory(
        private val profileId: String,
        private val repository: LayoutProfileRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LayoutEditorViewModel::class.java))
            return LayoutEditorViewModel(profileId, repository) as T
        }
    }

    private companion object {
        const val LOAD_ERROR = "The saved layout could not be loaded. No changes were made."
        const val DRAFT_SAVE_ERROR = "The draft could not be saved. Retry before leaving the editor."
        const val SAVE_ERROR = "The layout could not be saved. Your draft is still available to retry."
        const val DISCARD_ERROR = "The draft could not be discarded. The saved layout is unchanged."
    }
}
