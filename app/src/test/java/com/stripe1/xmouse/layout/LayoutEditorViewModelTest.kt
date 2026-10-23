package com.stripe1.xmouse.layout

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditorViewModelTest {
    @Test
    fun dirtyBackCancelKeepsEditingAndDiscardRestoresTheSavedSnapshot() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val saved = profile()
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)
        val draft = saved.copy(name = "Draft name")

        viewModel.updateProfileNameInput(draft.name)
        assertFalse(viewModel.requestBack())
        assertTrue(viewModel.state.value.isShowingBackConfirmation)
        assertFalse(viewModel.resolveBack(LayoutEditorBackChoice.CANCEL))
        assertTrue(viewModel.state.value.isDirty)
        assertEquals(draft, repository.loadDraft(saved.id))

        assertFalse(viewModel.requestBack())
        assertTrue(viewModel.resolveBack(LayoutEditorBackChoice.DISCARD))
        assertEquals(saved, viewModel.state.value.savedProfile)
        assertEquals(saved, viewModel.state.value.draftProfile)
        assertNull(repository.loadDraft(saved.id))
    }

    @Test
    fun failedSaveKeepsRecoverableDraftAndAllowsRetry() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val saved = profile()
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)
        val draft = saved.copy(name = "Retry me")
        viewModel.updateProfileNameInput(draft.name)
        files.failNextProfileWrite = true

        assertFalse(viewModel.requestBack())
        assertFalse(viewModel.resolveBack(LayoutEditorBackChoice.SAVE))
        assertEquals(saved, repository.loadProfile(saved.id))
        assertEquals(draft, repository.loadDraft(saved.id))
        assertEquals(saved, viewModel.state.value.savedProfile)
        assertEquals(draft, viewModel.state.value.draftProfile)
        assertTrue(viewModel.state.value.isDirty)
        assertTrue(viewModel.state.value.errorMessage!!.contains("retry"))

        assertTrue(viewModel.resolveBack(LayoutEditorBackChoice.SAVE))
        assertEquals(draft, repository.loadProfile(saved.id))
        assertNull(repository.loadDraft(saved.id))
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun recreatedViewModelRestoresPersistedDraftByStableProfileId() {
        val files = FakeLayoutProfileFileOps()
        val savedRepository = LayoutProfileRepository(files)
        val saved = profile()
        savedRepository.createProfile(saved)
        val firstViewModel = LayoutEditorViewModel(saved.id, savedRepository)
        val draft = saved.copy(name = "Restored draft")
        firstViewModel.updateProfileNameInput(draft.name)

        val recreatedViewModel = LayoutEditorViewModel(saved.id, LayoutProfileRepository(files))

        assertEquals(saved, recreatedViewModel.state.value.savedProfile)
        assertEquals(draft, recreatedViewModel.state.value.draftProfile)
        assertEquals(draft.name, recreatedViewModel.state.value.profileNameInput)
        assertTrue(recreatedViewModel.state.value.isDirty)
    }

    private fun profile() = LayoutProfile(
        id = "editor-profile",
        name = "Saved profile",
        buttons = listOf(LayoutButton(id = "editor-button", title = "Open", command = "echo open", width = 2)),
    )

    private class FakeLayoutProfileFileOps : LayoutProfileFileOps {
        private val files = mutableMapOf<String, ByteArray>()
        var failNextProfileWrite = false

        override fun listFileNames(): List<String> = files.keys.toList()

        override fun readAtomically(fileName: String): ByteArray? = files[fileName]?.copyOf()

        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            if (failNextProfileWrite && fileName.endsWith(".profile.json")) {
                failNextProfileWrite = false
                throw IOException("injected profile write failure")
            }
            files[fileName] = bytes.copyOf()
        }

        override fun deleteAtomically(fileName: String) {
            files.remove(fileName)
        }
    }
}
