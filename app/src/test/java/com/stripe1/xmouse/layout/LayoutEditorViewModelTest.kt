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

    @Test
    fun dragReleaseAndWidthControlsPersistOnlyAcceptedDraftEditsAndKeepSelectionAndRawCommands() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val saved = profile().copy(buttons = listOf(
            profile().buttons.single().copy(command = "  echo '雪, \\\"raw\\\"'\nnext  ", color = "#123456"),
            LayoutButton(id = "target", title = "Target", command = " target ", width = 8),
        ))
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)
        val moving = saved.buttons.first()
        viewModel.selectButton(moving.id)
        val begun = LayoutEditorDragReducer.begin(saved, moving.id)!!
        viewModel.commitDrag(begun) // Releasing without a target is not an edit or a draft write.
        var dragging = LayoutEditorDragReducer.update(begun, LayoutEditorDropTarget("target", after = false))
        dragging = LayoutEditorDragReducer.update(dragging, LayoutEditorDropTarget("target", after = true))
        assertNull(repository.loadDraft(saved.id))
        assertEquals(0, files.draftWrites)

        viewModel.commitDrag(dragging!!)
        val moved = saved.copy(buttons = saved.buttons.reversed())
        assertEquals(moved, viewModel.state.value.draftProfile)
        assertEquals(moved, repository.loadDraft(saved.id))
        assertEquals(1, files.draftWrites)
        assertEquals(moving, viewModel.state.value.selectedButton)

        viewModel.updateButtonWidth(moving.id, 4)
        val resized = moved.copy(buttons = listOf(moved.buttons[0], moving.copy(width = 4)))
        assertEquals(resized, repository.loadDraft(saved.id))
        assertEquals(moving.copy(width = 4), viewModel.state.value.selectedButton)
        viewModel.updateButtonWidth(moving.id, 1)
        viewModel.updateButtonWidth(moving.id, 0)
        assertEquals(1, viewModel.state.value.selectedButton!!.width)
        assertTrue(viewModel.state.value.widthValidationMessage!!.contains("1 and 10"))
        viewModel.updateButtonWidth(moving.id, 10)
        val lastValid = viewModel.state.value.draftProfile
        val writesBeforeInvalid = files.draftWrites
        viewModel.updateButtonWidth(moving.id, 11)
        assertEquals(lastValid, viewModel.state.value.draftProfile)
        assertEquals(lastValid, repository.loadDraft(saved.id))
        assertEquals(writesBeforeInvalid, files.draftWrites)
        assertTrue(viewModel.state.value.widthValidationMessage!!.contains("still open"))
        assertFalse(viewModel.state.value.isShowingBackConfirmation)
        assertTrue(viewModel.state.value.isDirty)
        assertEquals(saved, repository.loadProfile(saved.id))
        assertEquals(lastValid, LayoutEditorViewModel(saved.id, repository).state.value.draftProfile)

        files.failNextDraftWrite = true
        viewModel.updateButtonWidth(moving.id, 9)
        assertEquals(lastValid, viewModel.state.value.draftProfile)
        assertEquals(lastValid, repository.loadDraft(saved.id))
        assertTrue(viewModel.state.value.errorMessage!!.contains("Retry"))
        assertNull(viewModel.state.value.widthValidationMessage)
        assertEquals(saved, repository.loadProfile(saved.id))
    }

    private fun profile() = LayoutProfile(
        id = "editor-profile",
        name = "Saved profile",
        buttons = listOf(LayoutButton(id = "editor-button", title = "Open", command = "echo open", width = 2)),
    )

    private class FakeLayoutProfileFileOps : LayoutProfileFileOps {
        private val files = mutableMapOf<String, ByteArray>()
        var failNextProfileWrite = false
        var failNextDraftWrite = false
        var draftWrites = 0

        override fun listFileNames(): List<String> = files.keys.toList()

        override fun readAtomically(fileName: String): ByteArray? = files[fileName]?.copyOf()

        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            if (fileName.endsWith(".draft.json")) {
                if (failNextDraftWrite) {
                    failNextDraftWrite = false
                    throw IOException("injected draft write failure")
                }
                draftWrites += 1
            }
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
