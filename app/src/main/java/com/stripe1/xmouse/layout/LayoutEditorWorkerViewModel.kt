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
    private val mutableState = MutableStateFlow(LayoutEditorState())
    val state = mutableState.asStateFlow()
    private val mutableClosed = MutableStateFlow(false)
    val closed = mutableClosed.asStateFlow()

    init {
        worker.execute {
            editor = LayoutEditorViewModel(profileId, repository)
            mutableState.value = editor!!.state.value
        }
    }

    fun edit(action: (LayoutEditorViewModel) -> Unit) {
        worker.execute {
            val owner = editor ?: return@execute
            if (mutableClosed.value) return@execute
            action(owner)
            mutableState.value = owner.state.value
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
