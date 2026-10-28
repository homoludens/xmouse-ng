package net.droopia.xmouse_ng.layout

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun buttonCreateEditDuplicateAndDeleteChangeOnlyDraftAndPreserveRawCommandAndProperties() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val rawCommand = "  printf '%s, 雪' \"quoted\"\nnext line\t  "
        val originalButton = LayoutButton(
            id = "original",
            title = "Open",
            command = rawCommand,
            width = 3,
            color = "#a1B2c3",
        )
        val neighbor = LayoutButton(id = "neighbor", title = "Other", command = "  other  ", width = 6, color = "#123456")
        val saved = profile().copy(buttons = listOf(originalButton, neighbor))
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)

        val newCommand = "  echo 'new, command'\nsecond line  "
        viewModel.startNewButtonEdit()
        viewModel.updateButtonTitleInput("New button")
        viewModel.updateButtonCommandInput(newCommand)
        viewModel.updateButtonWidthInput("4")
        viewModel.updateButtonColorInput("#ABCDEF")
        assertTrue(viewModel.saveButtonEdit())
        val createdButton = viewModel.state.value.selectedButton!!
        assertNotEquals(originalButton.id, createdButton.id)
        assertEquals(newCommand, createdButton.command)
        assertEquals(4, createdButton.width)
        assertEquals("#ABCDEF", createdButton.color)

        viewModel.selectButton(originalButton.id)
        viewModel.startSelectedButtonEdit()
        viewModel.updateButtonTitleInput("Edited title")
        assertTrue(viewModel.saveButtonEdit())
        val editedButton = originalButton.copy(title = "Edited title")
        assertEquals(editedButton, viewModel.state.value.draftProfile!!.buttons.first())
        assertEquals(rawCommand, viewModel.state.value.draftProfile!!.buttons.first().command)

        viewModel.selectButton(originalButton.id)
        viewModel.startSelectedButtonDuplicate()
        assertTrue(viewModel.saveButtonEdit())
        val duplicatedButton = viewModel.state.value.selectedButton!!
        assertNotEquals(originalButton.id, duplicatedButton.id)
        assertEquals(editedButton.copy(id = duplicatedButton.id), duplicatedButton)
        assertEquals(listOf(editedButton.id, duplicatedButton.id), viewModel.state.value.draftProfile!!.buttons.take(2).map { it.id })

        assertTrue(viewModel.deleteSelectedButton())
        val draft = saved.copy(buttons = listOf(editedButton, neighbor, createdButton))
        assertEquals(draft, viewModel.state.value.draftProfile)
        assertEquals(draft, repository.loadDraft(saved.id))
        assertEquals(saved, repository.loadProfile(saved.id))

        assertTrue(viewModel.save())
        assertEquals(draft, repository.loadProfile(saved.id))
        assertNull(repository.loadDraft(saved.id))
    }

    @Test
    fun confirmedDeleteUsesTheCapturedButtonIdRatherThanTheCurrentSelection() {
        val repository = LayoutProfileRepository(FakeLayoutProfileFileOps())
        val saved = profile().copy(
            buttons = listOf(
                LayoutButton("confirmed", "Confirmed", "opaque one", 2, "#123456"),
                LayoutButton("new-selection", "New selection", "opaque two", 7, "#ABCDEF"),
            ),
        )
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)

        viewModel.selectButton("confirmed")
        val capturedId = viewModel.state.value.selectedButtonId!!
        viewModel.selectButton("new-selection")

        assertTrue(viewModel.deleteButton(capturedId))
        val changedDraft = saved.copy(buttons = listOf(saved.buttons.last()))
        assertEquals(changedDraft, viewModel.state.value.draftProfile)
        assertEquals("new-selection", viewModel.state.value.selectedButtonId)
        assertEquals(saved, repository.loadProfile(saved.id))
        assertFalse(viewModel.deleteButton("missing"))
        assertTrue(viewModel.undo())
        assertEquals(saved, viewModel.state.value.draftProfile)
        assertEquals("new-selection", viewModel.state.value.selectedButtonId)
    }

    @Test
    fun invalidButtonFormRetainsInputAndNeverChangesDraftOrSavedProfile() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val saved = profile()
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)
        val original = saved.buttons.single()
        viewModel.selectButton(original.id)
        viewModel.startSelectedButtonEdit()
        viewModel.updateButtonTitleInput("  ")
        viewModel.updateButtonCommandInput("")
        viewModel.updateButtonWidthInput("11")
        viewModel.updateButtonColorInput("#not-a-color")
        val invalidInput = viewModel.state.value.buttonEditInput

        assertFalse(viewModel.saveButtonEdit())
        assertEquals(invalidInput, viewModel.state.value.buttonEditInput)
        assertEquals("Title is required.", viewModel.state.value.buttonEditValidation?.title)
        assertEquals("Command is required.", viewModel.state.value.buttonEditValidation?.command)
        assertTrue(viewModel.state.value.buttonEditValidation!!.width!!.contains("1 and 10"))
        assertTrue(viewModel.state.value.buttonEditValidation!!.color!!.contains("#RRGGBB"))
        assertEquals(saved, viewModel.state.value.draftProfile)
        assertEquals(saved, repository.loadProfile(saved.id))
        assertNull(repository.loadDraft(saved.id))
        assertFalse(viewModel.save()) // Main Save cannot bypass an open, invalid button form.

        val typedCommand = "  printf '雪, \\\"quoted\\\"'\nnext\t  "
        viewModel.updateButtonTitleInput("Corrected title")
        viewModel.updateButtonCommandInput(typedCommand)
        assertFalse(viewModel.saveButtonEdit())
        assertEquals("Corrected title", viewModel.state.value.buttonEditInput?.title)
        assertEquals(typedCommand, viewModel.state.value.buttonEditInput?.command)
        assertNull(viewModel.state.value.buttonEditValidation?.title)
        assertNull(viewModel.state.value.buttonEditValidation?.command)
        assertEquals(saved, repository.loadProfile(saved.id))
        assertEquals(saved, viewModel.state.value.draftProfile)
    }

    @Test
    fun historyReducerAndViewModelRestoreAllButtonEditsWithOneEntryPerCompletedDrag() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val original = LayoutButton("original", "雪", "  printf '雪, \"raw\"'\nnext\t  ", 3, "#a1B2c3")
        val neighbor = LayoutButton("neighbor", "Other", " other ", 6, "#123456")
        val last = LayoutButton("last", "Last", "last\nline", 1, "#80123456")
        val saved = profile().copy(buttons = listOf(original, neighbor, last))
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)
        val snapshots = mutableListOf(saved.buttons)
        fun checkpoint(buttons: List<LayoutButton>) {
            snapshots += buttons
            assertEquals(buttons, viewModel.state.value.draftProfile!!.buttons)
            assertEquals(viewModel.state.value.draftProfile, repository.loadDraft(saved.id))
            assertEquals(saved, repository.loadProfile(saved.id))
            assertEquals(saved, viewModel.state.value.savedProfile)
            assertTrue(viewModel.state.value.canUndo)
            assertFalse(viewModel.state.value.canRedo)
        }

        viewModel.selectButton(original.id)
        viewModel.startSelectedButtonEdit()
        val edited = original.copy(title = "Edited 雪", command = "  echo 'new, raw'\n\"quoted\"  ", width = 5, color = "#80Ab12Cd")
        viewModel.updateButtonTitleInput(edited.title)
        viewModel.updateButtonCommandInput(edited.command)
        viewModel.updateButtonWidthInput(edited.width.toString())
        viewModel.updateButtonColorInput(edited.color)
        assertTrue(viewModel.saveButtonEdit())
        checkpoint(listOf(edited, neighbor, last))

        val beforeDrag = viewModel.state.value.draftProfile!!
        val writesBeforeDrag = files.draftWrites
        var drag = LayoutEditorDragReducer.begin(beforeDrag, original.id)!!
        listOf(
            LayoutEditorDropTarget(last.id, after = false),
            LayoutEditorDropTarget(neighbor.id, after = true),
            LayoutEditorDropTarget(last.id, after = true),
        ).forEach { target ->
            drag = LayoutEditorDragReducer.update(drag, target)!!
            LayoutEditorDragReducer.placement(beforeDrag, drag)
            assertEquals(beforeDrag, viewModel.state.value.draftProfile)
            assertEquals(writesBeforeDrag, files.draftWrites)
        }
        viewModel.commitDrag(drag)
        assertEquals(writesBeforeDrag + 1, files.draftWrites)
        checkpoint(listOf(neighbor, last, edited))
        viewModel.updateButtonWidth(original.id, 9)
        val resized = edited.copy(width = 9)
        checkpoint(listOf(neighbor, last, resized))

        viewModel.startSelectedButtonDuplicate()
        assertTrue(viewModel.saveButtonEdit())
        val duplicate = viewModel.state.value.selectedButton!!
        assertNotEquals(original.id, duplicate.id)
        assertEquals(resized.copy(id = duplicate.id), duplicate)
        checkpoint(listOf(neighbor, last, resized, duplicate))
        viewModel.selectButton(original.id)
        assertTrue(viewModel.deleteSelectedButton())
        checkpoint(listOf(neighbor, last, duplicate))

        viewModel.startNewButtonEdit()
        viewModel.updateButtonTitleInput("Created")
        viewModel.updateButtonCommandInput("  created\nraw, command  ")
        viewModel.updateButtonWidthInput("4")
        viewModel.updateButtonColorInput("#ABCDEF")
        assertTrue(viewModel.saveButtonEdit())
        val created = viewModel.state.value.selectedButton!!
        assertEquals(LayoutButton(created.id, "Created", "  created\nraw, command  ", 4, "#ABCDEF"), created)
        checkpoint(listOf(neighbor, last, duplicate, created))

        // Exercise the pure reducer and its durable ViewModel boundary against the same full snapshots.
        var reduced = snapshots.zipWithNext().fold(LayoutEditorHistory()) { history, (before, after) ->
            history.record(LayoutEditorButtonSnapshot(before, null), LayoutEditorButtonSnapshot(after, null))
        }
        viewModel.updateProfileNameInput("Independent profile name")
        snapshots.dropLast(1).asReversed().forEach { buttons ->
            val transition = reduced.undo()!!
            assertEquals(buttons, transition.snapshot.buttons)
            reduced = transition.history
            assertTrue(viewModel.undo())
            assertEquals(buttons, viewModel.state.value.draftProfile!!.buttons)
            assertEquals("Independent profile name", viewModel.state.value.draftProfile!!.name)
            assertEquals(viewModel.state.value.draftProfile, repository.loadDraft(saved.id))
            if (buttons == snapshots[4]) assertEquals(original.id, viewModel.state.value.selectedButtonId)
        }
        assertFalse(reduced.canUndo)
        assertFalse(viewModel.state.value.canUndo)
        assertTrue(viewModel.state.value.canRedo)
        assertFalse(viewModel.undo()) // Six accepted edits, including exactly one drag entry.
        snapshots.drop(1).forEach { buttons ->
            val transition = reduced.redo()!!
            assertEquals(buttons, transition.snapshot.buttons)
            reduced = transition.history
            assertTrue(viewModel.redo())
            assertEquals(buttons, viewModel.state.value.draftProfile!!.buttons)
            assertEquals(viewModel.state.value.draftProfile, repository.loadDraft(saved.id))
        }
        assertFalse(reduced.canRedo)
        assertFalse(viewModel.state.value.canRedo)
        assertFalse(viewModel.redo())
        assertEquals(created.id, viewModel.state.value.selectedButtonId)
        val reopened = LayoutEditorViewModel(saved.id, LayoutProfileRepository(files)).state.value
        assertEquals(viewModel.state.value.draftProfile, reopened.draftProfile)
        assertFalse(reopened.canUndo)
        assertFalse(reopened.canRedo) // Recovery persists content, never session history.
        assertEquals(saved, repository.loadProfile(saved.id))
        assertTrue(viewModel.undo()) // Save clears both branches, not just undo.
        val toSave = viewModel.state.value.draftProfile
        assertTrue(viewModel.save())
        assertEquals(toSave, repository.loadProfile(saved.id))
        assertNull(repository.loadDraft(saved.id))
        assertFalse(viewModel.state.value.canUndo)
        assertFalse(viewModel.state.value.canRedo)
        assertFalse(viewModel.undo())
        assertFalse(viewModel.redo())
    }

    @Test
    fun failedWritesNoOpsAndCancelledOrStaleDragsPreserveHistoryUntilANewEditOrDiscard() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val original = profile().buttons.single()
        val neighbor = LayoutButton("neighbor", "Other", " other\nraw ", 6, "#123456")
        val saved = profile().copy(buttons = listOf(original, neighbor))
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)
        viewModel.selectButton(original.id)
        val stale = LayoutEditorDragReducer.update(
            LayoutEditorDragReducer.begin(saved, original.id), LayoutEditorDropTarget(neighbor.id, after = true),
        )!!
        viewModel.updateButtonWidth(original.id, 4)
        val resized = viewModel.state.value.draftProfile!!
        viewModel.startSelectedButtonEdit()
        viewModel.updateButtonTitleInput("Future branch")
        assertTrue(viewModel.saveButtonEdit())
        val future = viewModel.state.value.draftProfile!!
        assertTrue(viewModel.undo())
        val writesBeforeNoOps = files.draftWrites
        viewModel.updateButtonWidth(original.id, 4)
        viewModel.updateButtonWidth(original.id, 11)
        viewModel.commitDrag(stale)
        val begun = LayoutEditorDragReducer.begin(resized, original.id)!!
        viewModel.commitDrag(begun) // No target.
        viewModel.commitDrag(LayoutEditorDragReducer.update(begun, LayoutEditorDropTarget(neighbor.id, after = false))!!)
        val cancelled = LayoutEditorDragReducer.update(begun, LayoutEditorDropTarget(neighbor.id, after = true))
        viewModel.commitDrag(LayoutEditorDragReducer.update(cancelled, null)!!)
        viewModel.startSelectedButtonEdit()
        assertTrue(viewModel.saveButtonEdit()) // Applying identical form values is not a new edit.
        viewModel.startNewButtonEdit()
        assertFalse(viewModel.saveButtonEdit()) // Invalid/unapplied form values have no history.
        assertFalse(viewModel.undo()) // An open form cannot be silently lost to Undo/Redo.
        assertFalse(viewModel.redo())
        viewModel.cancelButtonEdit()
        viewModel.startSelectedButtonDuplicate()
        viewModel.cancelButtonEdit()
        assertEquals(writesBeforeNoOps, files.draftWrites)
        assertEquals(resized, viewModel.state.value.draftProfile)
        assertTrue(viewModel.state.value.canUndo)
        assertTrue(viewModel.state.value.canRedo)

        viewModel.startSelectedButtonEdit()
        viewModel.updateButtonTitleInput("Failed new edit")
        val input = viewModel.state.value.buttonEditInput
        files.failNextDraftWrite = true
        assertFalse(viewModel.saveButtonEdit())
        assertEquals(input, viewModel.state.value.buttonEditInput)
        assertTrue(viewModel.state.value.buttonEditSaveError!!.contains("Retry"))
        viewModel.cancelButtonEdit()
        files.failNextDraftWrite = true
        assertFalse(viewModel.undo())
        assertEquals(resized, viewModel.state.value.draftProfile)
        assertEquals(resized, repository.loadDraft(saved.id))
        assertTrue(viewModel.state.value.canUndo)
        assertTrue(viewModel.state.value.canRedo)
        assertTrue(viewModel.redo()) // The failed new edit and failed Undo did not destroy the old branch.
        assertEquals(future, viewModel.state.value.draftProfile)
        assertTrue(viewModel.undo())
        files.failNextDraftWrite = true
        assertFalse(viewModel.redo())
        assertEquals(resized, viewModel.state.value.draftProfile)
        assertEquals(resized, repository.loadDraft(saved.id))
        files.failNextProfileWrite = true
        assertFalse(viewModel.save())
        files.failNextDraftDelete = true
        assertFalse(viewModel.discard())
        assertTrue(viewModel.state.value.canUndo)
        assertTrue(viewModel.state.value.canRedo)
        assertEquals(saved, repository.loadProfile(saved.id))

        viewModel.updateButtonWidth(original.id, 7)
        assertFalse(viewModel.state.value.canRedo)
        assertFalse(viewModel.redo())
        assertTrue(viewModel.undo())
        assertEquals(resized, viewModel.state.value.draftProfile)
        assertTrue(viewModel.undo())
        assertEquals(saved, viewModel.state.value.draftProfile)
        assertFalse(viewModel.undo()) // Failed writes and all no-ops contributed zero entries.
        assertTrue(viewModel.redo())
        assertEquals(resized, viewModel.state.value.draftProfile)
        assertTrue(viewModel.discard())
        assertEquals(saved, viewModel.state.value.draftProfile)
        assertNull(repository.loadDraft(saved.id))
        assertFalse(viewModel.state.value.canUndo)
        assertFalse(viewModel.state.value.canRedo)
        assertFalse(viewModel.undo())
        assertFalse(viewModel.redo())
    }

    @Test
    fun accessibleMoveActionsResizeAndSaveByStableIdWithoutChangingButtonProperties() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val first = LayoutButton("first", "First", "first raw", 3, "#123456")
        val target = LayoutButton("target", "Target", "  printf '雪, \"raw\"'\nnext\t  ", 2, "#a1B2c3")
        val third = LayoutButton("third", "Third", "third raw", 1, "#654321")
        val fourth = LayoutButton("fourth", "Fourth", "fourth raw", 2, "#ABCDEF")
        val saved = profile().copy(buttons = listOf(first, target, third, fourth))
        repository.createProfile(saved)
        val viewModel = LayoutEditorViewModel(saved.id, repository)

        viewModel.selectButton(target.id)
        viewModel.moveSelectedButtonEarlier()
        val movedEarlier = saved.copy(buttons = listOf(target, first, third, fourth))
        assertEquals(movedEarlier, viewModel.state.value.draftProfile)
        assertEquals(movedEarlier, repository.loadDraft(saved.id))
        assertEquals(target.id, viewModel.state.value.selectedButtonId)
        assertTrue(viewModel.state.value.canUndo)

        assertTrue(viewModel.undo()) // A non-drag move is one history entry.
        assertEquals(saved, viewModel.state.value.draftProfile)
        assertNull(repository.loadDraft(saved.id))
        assertEquals(target.id, viewModel.state.value.selectedButtonId)
        assertTrue(viewModel.redo())
        assertEquals(movedEarlier, repository.loadDraft(saved.id))

        viewModel.moveSelectedButtonLater()
        assertEquals(saved, viewModel.state.value.draftProfile)
        assertNull(repository.loadDraft(saved.id))
        viewModel.moveSelectedButtonLater()
        val movedLater = saved.copy(buttons = listOf(first, third, target, fourth))
        assertEquals(movedLater, viewModel.state.value.draftProfile)
        assertEquals(target.id, viewModel.state.value.selectedButtonId)

        val writesAtBoundary = files.draftWrites
        viewModel.selectButton(first.id)
        viewModel.moveSelectedButtonEarlier()
        assertEquals(writesAtBoundary, files.draftWrites)
        assertEquals(movedLater, repository.loadDraft(saved.id))
        assertTrue(viewModel.state.value.moveValidationMessage!!.contains("already the first"))
        assertTrue(viewModel.state.value.canUndo)

        viewModel.selectButton(target.id)
        viewModel.updateButtonWidth(target.id, 4)
        val resized = movedLater.copy(buttons = listOf(first, third, target.copy(width = 4), fourth))
        assertEquals(resized, repository.loadDraft(saved.id))
        assertEquals(target.copy(width = 4), viewModel.state.value.selectedButton)
        assertEquals(saved, repository.loadProfile(saved.id))

        assertTrue(viewModel.save())
        assertEquals(resized, repository.loadProfile(saved.id))
        assertEquals(target.id, repository.loadProfile(saved.id).buttons[2].id)
        assertEquals(target.command, repository.loadProfile(saved.id).buttons[2].command)
        assertEquals(target.title, repository.loadProfile(saved.id).buttons[2].title)
        assertEquals(target.color, repository.loadProfile(saved.id).buttons[2].color)
        assertNull(repository.loadDraft(saved.id))
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
        var failNextDraftDelete = false
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
            if (failNextDraftDelete && fileName.endsWith(".draft.json")) {
                failNextDraftDelete = false
                throw IOException("injected draft delete failure")
            }
            files.remove(fileName)
        }
    }
}
