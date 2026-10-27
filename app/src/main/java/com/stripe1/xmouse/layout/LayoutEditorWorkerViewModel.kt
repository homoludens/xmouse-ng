package com.stripe1.xmouse.layout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serial UI adapter; the existing editor remains the sole draft/history/repository authority. */
class LayoutEditorWorkerViewModel(
    profileId: String,
    repository: LayoutProfileRepository,
    private val worker: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "xmouse-advanced-editor").apply { isDaemon = true }
    },
) : ViewModel() {
    private var editor: LayoutEditorViewModel? = null
    // Protect queue submission and form publication, never the editor's disk operations.
    private val inputLock = Any()
    private data class NameInput(val revision: Long, val value: String)
    private class PendingName(var input: NameInput)
    private var nameRevision = 0L
    private var visibleName: NameInput? = null
    private var pendingName: PendingName? = null
    private val mutableState = MutableStateFlow(LayoutEditorState())
    val state = mutableState.asStateFlow()
    private val mutableClosed = MutableStateFlow(false)
    val closed = mutableClosed.asStateFlow()

    init {
        worker.execute {
            editor = LayoutEditorViewModel(profileId, repository)
            publish(editor!!, throughNameRevision = 0)
        }
    }

    /** Replace only the consecutive name job that has not started; there is no debounce window. */
    fun updateProfileNameInput(value: String) = synchronized(inputLock) {
        if (mutableClosed.value) return@synchronized
        val input = NameInput(++nameRevision, value)
        visibleName = input
        mutableState.value = mutableState.value.copy(profileNameInput = value, isProfileNamePending = true)
        val pending = pendingName
        if (pending != null) {
            pending.input = input
            return@synchronized
        }

        val job = PendingName(input)
        pendingName = job
        worker.execute {
            // Freeze before calling the editor: an in-flight write always finishes normally.
            val started = synchronized(inputLock) {
                if (pendingName === job) pendingName = null
                job.input
            }
            val owner = editor ?: return@execute
            if (mutableClosed.value) return@execute
            owner.updateProfileNameInput(started.value)
            publish(owner, started.revision)
        }
    }

    /** Every non-name action is a barrier, even if it turns out to be a no-op or fails. */
    fun edit(action: (LayoutEditorViewModel) -> Unit) = synchronized(inputLock) {
        if (mutableClosed.value) return@synchronized
        pendingName = null
        val throughNameRevision = nameRevision
        worker.execute {
            val owner = editor ?: return@execute
            if (mutableClosed.value) return@execute
            action(owner)
            publish(owner, throughNameRevision)
        }
    }

    private fun publish(owner: LayoutEditorViewModel, throughNameRevision: Long) = synchronized(inputLock) {
        // A barrier may reset the name (Discard/Apply XML). Only text entered AFTER that
        // barrier survives its publication. Successful terminal actions reject all later input.
        val newerInput = visibleName?.takeIf { !mutableClosed.value && it.revision > throughNameRevision }
        visibleName = newerInput
        mutableState.value = owner.state.value.let { confirmed ->
            if (newerInput == null) confirmed else confirmed.copy(
                profileNameInput = newerInput.value,
                isProfileNamePending = true,
            )
        }
    }

    fun enterXmlMode() = edit { it.enterXmlMode() }
    fun updateXmlSource(value: String) = edit { it.updateXmlSource(value) }
    fun validateXmlSource() = edit { it.validateXmlSource() }
    fun formatXmlSource() = edit { it.formatXmlSource() }
    fun applyXmlSource() = edit { it.applyXmlSource() }
    fun requestXmlExit(action: XmlEditorExitAction) = edit { it.requestXmlExit(action) }
    fun resolveXmlExit(discardXmlChanges: Boolean) = edit { it.resolveXmlExit(discardXmlChanges) }

    fun back() = edit {
        if (it.state.value.isShowingBackConfirmation) it.resolveBack(LayoutEditorBackChoice.CANCEL)
        else if (it.requestBack()) mutableClosed.value = true
    }

    fun saveAndClose() = edit { if (it.save()) mutableClosed.value = true }
    fun discardAndClose() = edit { if (it.discard()) mutableClosed.value = true }
    fun discardDraft() = edit { it.discard() }
    fun backChoice(choice: LayoutEditorBackChoice) = edit {
        if (it.resolveBack(choice)) mutableClosed.value = true
    }

    override fun onCleared() { (worker as? ExecutorService)?.shutdown() }

    class Factory(private val profileId: String, private val repository: LayoutProfileRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LayoutEditorWorkerViewModel::class.java))
            return LayoutEditorWorkerViewModel(profileId, repository) as T
        }
    }
}
