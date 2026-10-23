package com.stripe1.xmouse.layout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ButtonEditMode {
    CREATE,
    EDIT,
    DUPLICATE,
}

/** Transient form values stay separate from the validated profile model. */
data class ButtonEditInput(
    val id: String,
    val sourceButtonId: String?,
    val mode: ButtonEditMode,
    val title: String,
    val command: String,
    val width: String,
    val color: String,
)

data class ButtonEditValidation(
    val title: String? = null,
    val command: String? = null,
    val width: String? = null,
    val color: String? = null,
) {
    val hasErrors: Boolean
        get() = title != null || command != null || width != null || color != null
}

/** Immutable UI snapshot for one stable profile identity. */
data class LayoutEditorState(
    val savedProfile: LayoutProfile? = null,
    val draftProfile: LayoutProfile? = null,
    val selectedButtonId: String? = null,
    val profileNameInput: String = "",
    val isLoading: Boolean = true,
    val isShowingBackConfirmation: Boolean = false,
    val errorMessage: String? = null,
    val widthValidationMessage: String? = null,
    val moveValidationMessage: String? = null,
    val buttonEditInput: ButtonEditInput? = null,
    val buttonEditValidation: ButtonEditValidation? = null,
    val buttonEditSaveError: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedButton: LayoutButton?
        get() = LayoutEditorGrid.selectedButton(draftProfile?.buttons.orEmpty(), selectedButtonId)

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
    private var history = LayoutEditorHistory()
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

    /** Button selection is transient and keyed by the button's stable ID, never its list position. */
    fun selectButton(buttonId: String?) {
        val current = mutableState.value
        val buttons = current.draftProfile?.buttons ?: return
        if (buttonId != null && buttons.none { it.id == buttonId }) return
        mutableState.value = current.copy(
            selectedButtonId = buttonId,
            widthValidationMessage = null,
            moveValidationMessage = null,
        )
    }

    fun startNewButtonEdit() {
        val current = mutableState.value
        val draft = current.draftProfile ?: return
        mutableState.value = current.copy(
            buttonEditInput = ButtonEditInput(
                id = newButtonId(draft),
                sourceButtonId = null,
                mode = ButtonEditMode.CREATE,
                title = "",
                command = "",
                width = "1",
                color = DEFAULT_LAYOUT_BUTTON_COLOR,
            ),
            buttonEditValidation = null,
            buttonEditSaveError = null,
        )
    }

    fun startSelectedButtonEdit() {
        val current = mutableState.value
        val button = current.selectedButton ?: return
        mutableState.value = current.copy(
            buttonEditInput = button.toEditInput(ButtonEditMode.EDIT, button.id),
            buttonEditValidation = null,
            buttonEditSaveError = null,
        )
    }

    fun startSelectedButtonDuplicate() {
        val current = mutableState.value
        val draft = current.draftProfile ?: return
        val button = current.selectedButton ?: return
        mutableState.value = current.copy(
            buttonEditInput = button.toEditInput(
                mode = ButtonEditMode.DUPLICATE,
                sourceButtonId = button.id,
                id = newButtonId(draft),
            ),
            buttonEditValidation = null,
            buttonEditSaveError = null,
        )
    }

    fun updateButtonTitleInput(value: String) = updateButtonEditInput { copy(title = value) }

    fun updateButtonCommandInput(value: String) = updateButtonEditInput { copy(command = value) }

    fun updateButtonWidthInput(value: String) = updateButtonEditInput { copy(width = value) }

    fun updateButtonColorInput(value: String) = updateButtonEditInput { copy(color = value) }

    fun cancelButtonEdit() {
        mutableState.value = mutableState.value.copy(
            buttonEditInput = null,
            buttonEditValidation = null,
            buttonEditSaveError = null,
        )
    }

    /** Invalid form text remains in state; only a fully validated button is written to the draft. */
    fun saveButtonEdit(): Boolean {
        val current = mutableState.value
        val input = current.buttonEditInput ?: return false
        val validation = validate(input)
        if (validation.hasErrors) {
            mutableState.value = current.copy(buttonEditValidation = validation, buttonEditSaveError = null)
            return false
        }

        val button = LayoutButton(
            id = if (input.mode == ButtonEditMode.EDIT) input.sourceButtonId!! else input.id,
            title = input.title,
            command = input.command,
            width = input.width.toInt(),
            color = input.color,
        )
        val draft = current.draftProfile ?: return false
        val buttons = draft.buttons.toMutableList()
        when (input.mode) {
            ButtonEditMode.CREATE -> buttons += button
            ButtonEditMode.EDIT -> {
                val index = buttons.indexOfFirst { it.id == input.sourceButtonId }
                if (index < 0) {
                    mutableState.value = current.copy(buttonEditSaveError = BUTTON_EDIT_TARGET_ERROR)
                    return false
                }
                buttons[index] = button
            }
            ButtonEditMode.DUPLICATE -> {
                val index = buttons.indexOfFirst { it.id == input.sourceButtonId }
                if (index < 0) {
                    mutableState.value = current.copy(buttonEditSaveError = BUTTON_EDIT_TARGET_ERROR)
                    return false
                }
                buttons.add(index + 1, button)
            }
        }

        return persistButtonChange(current, draft.copy(buttons = buttons), button.id, finishButtonEdit = true)
    }

    /** Removes only the selected button from the recoverable draft, never the saved profile. */
    fun deleteSelectedButton(): Boolean {
        val current = mutableState.value
        val draft = current.draftProfile ?: return false
        val selectedId = current.selectedButtonId ?: return false
        if (draft.buttons.none { it.id == selectedId }) return false
        val changed = draft.copy(buttons = draft.buttons.filterNot { it.id == selectedId })
        return persistButtonChange(current, changed, selectedButtonId = null)
    }

    private inline fun updateButtonEditInput(transform: ButtonEditInput.() -> ButtonEditInput) {
        val current = mutableState.value
        val input = current.buttonEditInput ?: return
        val changed = input.transform()
        val validation = current.buttonEditValidation?.let {
            validate(changed).takeIf { errors -> errors.hasErrors }
        }
        mutableState.value = current.copy(
            buttonEditInput = changed,
            buttonEditValidation = validation,
            buttonEditSaveError = null,
        )
    }

    private fun LayoutButton.toEditInput(
        mode: ButtonEditMode,
        sourceButtonId: String,
        id: String = this.id,
    ) = ButtonEditInput(
        id = id,
        sourceButtonId = sourceButtonId,
        mode = mode,
        title = title,
        command = command,
        width = width.toString(),
        color = color,
    )

    private fun newButtonId(profile: LayoutProfile): String {
        val existing = profile.buttons.mapTo(mutableSetOf(), LayoutButton::id)
        return generateSequence { UUID.randomUUID().toString() }.first { it !in existing }
    }

    private fun validate(input: ButtonEditInput): ButtonEditValidation = ButtonEditValidation(
        title = "Title is required.".takeIf { input.title.isBlank() },
        command = "Command is required.".takeIf { input.command.isBlank() },
        width = "Width must be between 1 and 10 columns.".takeIf {
            input.width.toIntOrNull()?.let { width -> width in 1..LAYOUT_COLUMN_COUNT } != true
        },
        color = "Use #RRGGBB or #AARRGGBB color notation.".takeIf { !BUTTON_COLOR_PATTERN.matches(input.color) },
    )

    /** One completed gesture is one durable draft edit; cancelled/stale/no-target drags do nothing. */
    internal fun commitDrag(drag: LayoutEditorDrag) {
        val current = mutableState.value
        val draft = current.draftProfile ?: return
        persistButtonChange(current, LayoutEditorDragReducer.commit(draft, drag))
    }

    /** Reorder the selected button by its stable ID without requiring a pointer gesture. */
    fun moveSelectedButtonEarlier() = moveSelectedButton(direction = -1)

    /** Reorder the selected button by its stable ID without requiring a pointer gesture. */
    fun moveSelectedButtonLater() = moveSelectedButton(direction = 1)

    private fun moveSelectedButton(direction: Int) {
        val current = mutableState.value
        val draft = current.draftProfile ?: return
        val selectedId = current.selectedButtonId
        if (selectedId == null) {
            mutableState.value = current.copy(moveValidationMessage = "Select a button before moving it.")
            return
        }

        val index = draft.buttons.indexOfFirst { it.id == selectedId }
        if (index < 0) {
            mutableState.value = current.copy(
                selectedButtonId = null,
                moveValidationMessage = "The selected button is no longer in this draft. Select it again.",
            )
            return
        }

        val targetIndex = index + direction
        if (targetIndex !in draft.buttons.indices) {
            val boundary = if (direction < 0) "first" else "last"
            val action = if (direction < 0) "move it later" else "move it earlier"
            mutableState.value = current.copy(
                moveValidationMessage = "This is already the $boundary button. Select another button or $action.",
            )
            return
        }

        val movedButtons = draft.buttons.toMutableList().apply {
            add(targetIndex, removeAt(index))
        }
        persistButtonChange(current, draft.copy(buttons = movedButtons), selectedButtonId = selectedId)
    }

    fun updateButtonWidth(buttonId: String, width: Int) {
        val current = mutableState.value
        val draft = current.draftProfile ?: return
        if (draft.buttons.none { it.id == buttonId }) return
        if (width !in 1..LAYOUT_COLUMN_COUNT) {
            mutableState.value = current.copy(widthValidationMessage = WIDTH_ERROR, moveValidationMessage = null)
            return
        }
        val changed = draft.copy(buttons = draft.buttons.map { button ->
            if (button.id == buttonId) button.copy(width = width) else button
        })
        persistButtonChange(current, changed)
    }

    private fun persistButtonChange(
        current: LayoutEditorState,
        changed: LayoutProfile,
        selectedButtonId: String? = current.selectedButtonId,
        finishButtonEdit: Boolean = false,
    ): Boolean {
        val draft = current.draftProfile ?: return false
        val nextHistory = history.record(
            LayoutEditorButtonSnapshot(draft.buttons, current.selectedButtonId),
            LayoutEditorButtonSnapshot(changed.buttons, selectedButtonId),
        )
        if (changed.buttons != draft.buttons) {
            try {
                repository.saveDraft(changed)
            } catch (_: Exception) {
                // Neither the visible snapshot nor either history branch advances on a failed write.
                mutableState.value = if (finishButtonEdit) {
                    current.copy(buttonEditSaveError = DRAFT_SAVE_ERROR)
                } else {
                    current.copy(
                        errorMessage = DRAFT_SAVE_ERROR,
                        widthValidationMessage = null,
                        moveValidationMessage = null,
                    )
                }
                return false
            }
        }
        history = nextHistory
        mutableState.value = current.copy(
            draftProfile = immutableSnapshot(changed),
            selectedButtonId = selectedButtonId,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            widthValidationMessage = null,
            moveValidationMessage = null,
            errorMessage = null,
            buttonEditInput = if (finishButtonEdit) null else current.buttonEditInput,
            buttonEditValidation = if (finishButtonEdit) null else current.buttonEditValidation,
            buttonEditSaveError = if (finishButtonEdit) null else current.buttonEditSaveError,
        )
        return true
    }

    fun undo(): Boolean = restoreButtonHistory(history.undo())

    fun redo(): Boolean = restoreButtonHistory(history.redo())

    private fun restoreButtonHistory(transition: LayoutEditorHistory.Transition?): Boolean {
        val current = mutableState.value
        val draft = current.draftProfile ?: return false
        if (transition == null || current.buttonEditInput != null) return false
        val changed = immutableSnapshot(draft.copy(buttons = transition.snapshot.buttons))
        try {
            repository.saveDraft(changed)
        } catch (_: Exception) {
            mutableState.value = current.copy(errorMessage = DRAFT_SAVE_ERROR)
            return false
        }
        history = transition.history
        mutableState.value = current.copy(
            draftProfile = changed,
            selectedButtonId = transition.snapshot.selectedButtonId,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            widthValidationMessage = null,
            moveValidationMessage = null,
            errorMessage = null,
        )
        return true
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
        if (current.buttonEditInput != null) return false
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
            history = LayoutEditorHistory()
            mutableState.value = current.copy(
                savedProfile = immutableSnapshot(draft),
                draftProfile = immutableSnapshot(draft),
                profileNameInput = draft.name,
                isShowingBackConfirmation = false,
                errorMessage = null,
                widthValidationMessage = null,
                moveValidationMessage = null,
                buttonEditInput = null,
                buttonEditValidation = null,
                buttonEditSaveError = null,
                canUndo = false,
                canRedo = false,
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
            history = LayoutEditorHistory()
            mutableState.value = current.copy(
                draftProfile = immutableSnapshot(saved),
                selectedButtonId = current.selectedButtonId?.takeIf { id -> saved.buttons.any { it.id == id } },
                profileNameInput = saved.name,
                isShowingBackConfirmation = false,
                errorMessage = null,
                widthValidationMessage = null,
                moveValidationMessage = null,
                buttonEditInput = null,
                buttonEditValidation = null,
                buttonEditSaveError = null,
                canUndo = false,
                canRedo = false,
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
        const val WIDTH_ERROR = "Button width must be between 1 and 10 columns. Your draft is still open."
        const val BUTTON_EDIT_TARGET_ERROR = "This button is no longer in the draft. Close the form and try again."
        val BUTTON_COLOR_PATTERN = Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")
    }
}
