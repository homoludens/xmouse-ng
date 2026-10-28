package net.droopia.xmouse_ng.layout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActionEditorSubmission(
    val title: String,
    val commandType: ActionCommandType,
    val commandValue: String,
    /** null means preserve the current/custom width; otherwise the selected preset width. */
    val presetWidth: Int?,
    /** null means preserve the current RGB value exactly; otherwise apply the selected/pasted RGB. */
    val colorOverride: String?,
)

data class ActionEditorSessionState(
    val isLoading: Boolean = true,
    val editorState: LayoutEditorState? = null,
    val initialCommand: String? = null,
    val initialWidth: Int? = null,
    val initialColor: String? = null,
    val needsAdvancedEditor: Boolean = false,
    val isSaving: Boolean = false,
    val retrySave: Boolean = false,
    val saved: Boolean = false,
    val commandError: String? = null,
    val statusMessage: String? = null,
)

/**
 * View/XML simple action editor adapter. All profile reads/writes are serialized off the UI thread
 * and delegated to LayoutEditorViewModel so drafts/history and atomic repository ownership remain
 * centralized there. This class has no Activity, View, command-execution, SSH, or Context reference.
 */
class ActionEditorSessionViewModel(
    private val profileId: String,
    private val buttonId: String?,
    private val repository: LayoutProfileRepository,
    private val worker: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "xmouse-action-editor").apply { isDaemon = true }
    },
) : ViewModel() {
    private val mutableState = MutableStateFlow(ActionEditorSessionState())
    val state = mutableState.asStateFlow()

    // These are touched only by worker. The delegated editor remains the sole edit/history owner.
    private var editor: LayoutEditorViewModel? = null
    private var initialCommand = ""
    private var initialWidth = 5
    private var initialColor = DEFAULT_LAYOUT_BUTTON_COLOR

    /** Rotation-only local form state. Never written to disk or an Android saved-state bundle. */
    var form: ActionEditorForm? = null

    init {
        submitWork {
            try {
                val loaded = LayoutEditorViewModel(profileId, repository)
                val current = loaded.state.value
                editor = loaded
                val saved = current.savedProfile
                val draft = current.draftProfile
                if (saved == null || draft == null) {
                    publish(current.copy(isLoading = false), statusMessage = current.errorMessage
                        ?: LOAD_ERROR)
                    return@submitWork
                }
                if (current.isDirty) {
                    publish(current, needsAdvancedEditor = true)
                    return@submitWork
                }
                if (buttonId == null) {
                    loaded.startNewButtonEdit()
                    loaded.updateButtonWidthInput("5")
                    initialCommand = ""
                    initialWidth = 5
                    initialColor = DEFAULT_LAYOUT_BUTTON_COLOR
                } else {
                    val button = saved.buttons.firstOrNull { it.id == buttonId }
                    if (button == null) {
                        publish(current.copy(isLoading = false), statusMessage = TARGET_ERROR)
                        return@submitWork
                    }
                    loaded.selectButton(buttonId)
                    loaded.startSelectedButtonEdit()
                    initialCommand = button.command
                    initialWidth = button.width
                    initialColor = button.color
                }
                publish(loaded.state.value, isLoading = false)
            } catch (_: Exception) {
                publish(null, isLoading = false, statusMessage = LOAD_ERROR)
            }
        }
    }

    fun save(submission: ActionEditorSubmission) = submitWork {
        val currentEditor = editor ?: return@submitWork publish(null, statusMessage = LOAD_ERROR)
        val current = mutableState.value
        if (current.saved || current.needsAdvancedEditor || current.isLoading) return@submitWork
        if (current.retrySave) {
            val saved = currentEditor.save()
            publish(currentEditor.state.value, isSaving = false, retrySave = !saved,
                saved = saved, statusMessage = if (saved) null else SAVE_ERROR)
            return@submitWork
        }

        val command = try {
            ActionCommandCodec.serialize(submission.commandType, submission.commandValue, initialCommand)
        } catch (failure: IllegalArgumentException) {
            publish(currentEditor.state.value, commandError = failure.message ?: INVALID_SHORTCUT)
            return@submitWork
        }
        if (currentEditor.state.value.buttonEditInput == null)
            return@submitWork publish(currentEditor.state.value, statusMessage = LOAD_ERROR)
        currentEditor.updateButtonTitleInput(submission.title)
        currentEditor.updateButtonCommandInput(command)
        currentEditor.updateButtonWidthInput((submission.presetWidth ?: initialWidth).toString())
        currentEditor.updateButtonColorInput(submission.colorOverride ?: initialColor)
        if (!currentEditor.saveButtonEdit()) {
            publish(currentEditor.state.value, commandError = null)
            return@submitWork
        }
        val saved = currentEditor.save()
        publish(currentEditor.state.value, isSaving = false, retrySave = !saved,
            saved = saved, commandError = null, statusMessage = if (saved) null else SAVE_ERROR)
    }

    fun delete() = submitWork {
        val currentEditor = editor ?: return@submitWork publish(null, statusMessage = LOAD_ERROR)
        val current = mutableState.value
        if (current.saved || current.needsAdvancedEditor || current.isLoading) return@submitWork
        if (current.retrySave) {
            val saved = currentEditor.save()
            publish(currentEditor.state.value, isSaving = false, retrySave = !saved,
                saved = saved, statusMessage = if (saved) null else SAVE_ERROR)
            return@submitWork
        }
        currentEditor.cancelButtonEdit()
        if (!currentEditor.deleteSelectedButton()) {
            publish(currentEditor.state.value, statusMessage = currentEditor.state.value.errorMessage ?: SAVE_ERROR)
            return@submitWork
        }
        val saved = currentEditor.save()
        publish(currentEditor.state.value, isSaving = false, retrySave = !saved,
            saved = saved, statusMessage = if (saved) null else SAVE_ERROR)
    }

    private fun submitWork(action: () -> Unit) {
        mutableState.value = mutableState.value.copy(isSaving = true, statusMessage = null, commandError = null)
        try {
            worker.execute(action)
        } catch (_: RuntimeException) {
            mutableState.value = mutableState.value.copy(isSaving = false, statusMessage = SAVE_ERROR)
        }
    }

    private fun publish(
        editorState: LayoutEditorState?,
        isLoading: Boolean = false,
        needsAdvancedEditor: Boolean = false,
        isSaving: Boolean = false,
        retrySave: Boolean = false,
        saved: Boolean = false,
        commandError: String? = null,
        statusMessage: String? = null,
    ) {
        mutableState.value = ActionEditorSessionState(
            isLoading = isLoading,
            editorState = editorState,
            initialCommand = initialCommand,
            initialWidth = initialWidth,
            initialColor = initialColor,
            needsAdvancedEditor = needsAdvancedEditor,
            isSaving = isSaving,
            retrySave = retrySave,
            saved = saved,
            commandError = commandError,
            statusMessage = statusMessage,
        )
    }

    private companion object {
        const val LOAD_ERROR = "The selected layout could not be opened. No changes were made."
        const val TARGET_ERROR = "That action is no longer in the selected layout. No changes were made."
        const val SAVE_ERROR = "The saved layout was not changed. Your recoverable draft remains available in the Advanced editor."
        const val INVALID_SHORTCUT = "Enter one key chord using letters, numbers, underscores, hyphens, and +."
    }

    class Factory(
        private val profileId: String,
        private val buttonId: String?,
        private val repository: LayoutProfileRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ActionEditorSessionViewModel::class.java))
            return ActionEditorSessionViewModel(profileId, buttonId, repository) as T
        }
    }

    override fun onCleared() {
        (worker as? ExecutorService)?.shutdown()
    }
}
