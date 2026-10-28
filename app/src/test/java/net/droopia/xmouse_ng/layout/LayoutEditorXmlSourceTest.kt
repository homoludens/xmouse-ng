package net.droopia.xmouse_ng.layout

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditorXmlSourceTest {
    @Test fun typingValidateAndFormatNeverWriteDraftAndApplyCreatesOneUndoableButtonEdit() {
        val files = XmlMemoryFiles()
        val repository = LayoutProfileRepository(files)
        val saved = profile()
        repository.createProfile(saved)
        val vm = LayoutEditorViewModel(saved.id, repository)
        val writesBefore = files.draftWrites
        assertTrue(vm.enterXmlMode())
        val changed = saved.copy(
            name = "Renamed in XML",
            buttons = listOf(saved.buttons[1].copy(width = 8, color = "#80aBcDeF"), saved.buttons[0]),
        )
        val editedSource = LayoutProfileXml.serialize(changed).replace("<layout", "<layout\n")
        vm.updateXmlSource(editedSource)
        assertEquals(writesBefore, files.draftWrites)
        assertTrue(vm.validateXmlSource())
        assertTrue(vm.formatXmlSource())
        assertEquals(writesBefore, files.draftWrites)
        assertEquals(saved, repository.loadProfile(saved.id))
        assertNull(repository.loadDraft(saved.id))

        assertTrue(vm.applyXmlSource())
        assertEquals(changed, vm.state.value.draftProfile)
        assertEquals(changed, repository.loadDraft(saved.id))
        assertEquals(saved, repository.loadProfile(saved.id))
        assertEquals(writesBefore + 1, files.draftWrites)
        assertTrue(vm.state.value.canUndo)
        assertTrue(vm.undo())
        // Existing history intentionally covers buttons, not profile names.
        assertEquals(saved.copy(name = "Renamed in XML"), vm.state.value.draftProfile)
        assertTrue(vm.state.value.canRedo)
        assertTrue(vm.redo())
        assertEquals(changed, vm.state.value.draftProfile)
    }

    @Test fun invalidXmlApplyFormatSaveAndBackRetainRawBufferAndDoNotWrite() {
        val files = XmlMemoryFiles()
        val repository = LayoutProfileRepository(files)
        val saved = profile()
        repository.createProfile(saved)
        val vm = LayoutEditorViewModel(saved.id, repository)
        assertTrue(vm.enterXmlMode())
        val raw = "<layout version=\"1\" id=\"wrong\""
        vm.updateXmlSource(raw)
        val writesBefore = files.draftWrites

        assertFalse(vm.validateXmlSource())
        assertFalse(vm.formatXmlSource())
        assertFalse(vm.applyXmlSource())
        assertFalse(vm.save())
        assertFalse(vm.requestBack())
        assertFalse(vm.resolveBack(LayoutEditorBackChoice.CANCEL))
        assertEquals(raw, vm.state.value.xmlSource)
        assertTrue(vm.state.value.hasPendingXmlChanges)
        assertEquals(saved, vm.state.value.draftProfile)
        assertEquals(saved, repository.loadProfile(saved.id))
        assertNull(repository.loadDraft(saved.id))
        assertEquals(writesBefore, files.draftWrites)

        vm.requestXmlExit(XmlEditorExitAction.BACK_TO_VISUAL)
        assertEquals(XmlEditorExitAction.BACK_TO_VISUAL, vm.state.value.xmlExitConfirmation)
        vm.resolveXmlExit(discardXmlChanges = false)
        assertEquals(raw, vm.state.value.xmlSource)
        vm.requestXmlExit(XmlEditorExitAction.BACK_TO_VISUAL)
        vm.resolveXmlExit(discardXmlChanges = true)
        assertEquals(LayoutEditorMode.VISUAL, vm.state.value.editMode)
        assertEquals(saved, vm.state.value.draftProfile)
        assertEquals(writesBefore, files.draftWrites)
    }

    @Test fun cancelingXmlPreservesExistingVisualDraftAndResetUsesThatDraft() {
        val files = XmlMemoryFiles()
        val repository = LayoutProfileRepository(files)
        val saved = profile()
        repository.createProfile(saved)
        val vm = LayoutEditorViewModel(saved.id, repository)
        vm.updateProfileNameInput("Visual unsaved name")
        val visualDraft = saved.copy(name = "Visual unsaved name")
        assertTrue(vm.enterXmlMode())
        vm.updateXmlSource("not valid XML")
        vm.requestXmlExit(XmlEditorExitAction.BACK_TO_VISUAL)
        assertEquals(XmlEditorExitAction.BACK_TO_VISUAL, vm.state.value.xmlExitConfirmation)
        vm.resolveXmlExit(discardXmlChanges = true)
        assertEquals(visualDraft, vm.state.value.draftProfile)
        assertEquals(visualDraft, repository.loadDraft(saved.id))
        assertEquals(saved, repository.loadProfile(saved.id))

        assertTrue(vm.enterXmlMode())
        vm.updateXmlSource("another invalid buffer")
        vm.requestXmlExit(XmlEditorExitAction.RESET_SOURCE)
        vm.resolveXmlExit(discardXmlChanges = true)
        assertEquals(LayoutEditorMode.XML, vm.state.value.editMode)
        assertEquals(LayoutProfileXml.serialize(visualDraft), vm.state.value.xmlSource)
        assertFalse(vm.state.value.hasPendingXmlChanges)
        assertEquals(visualDraft, repository.loadDraft(saved.id))
    }

    @Test fun failedApplyKeepsRawSourceDraftAndHistoryUntilRetrySucceeds() {
        val files = XmlMemoryFiles()
        val repository = LayoutProfileRepository(files)
        val saved = profile()
        repository.createProfile(saved)
        val vm = LayoutEditorViewModel(saved.id, repository)
        assertTrue(vm.enterXmlMode())
        val changed = saved.copy(buttons = saved.buttons.map { it.copy(title = "Edited") })
        val raw = LayoutProfileXml.serialize(changed)
        vm.updateXmlSource(raw)
        files.failNextDraftWrite = true

        assertFalse(vm.applyXmlSource())
        assertEquals(raw, vm.state.value.xmlSource)
        assertEquals(saved, vm.state.value.draftProfile)
        assertEquals(saved, repository.loadProfile(saved.id))
        assertNull(repository.loadDraft(saved.id))
        assertFalse(vm.state.value.canUndo)
        assertFalse(vm.state.value.canRedo)

        assertTrue(vm.applyXmlSource())
        assertEquals(changed, vm.state.value.draftProfile)
        assertTrue(vm.state.value.canUndo)
        assertEquals(changed, repository.loadDraft(saved.id))
        assertEquals(saved, repository.loadProfile(saved.id))
    }

    @Test fun fullDiscardClearsXmlOnlyAfterRepositorySuccessAndFailureRetainsRawAndVisualDraft() {
        val files = XmlMemoryFiles()
        val repository = LayoutProfileRepository(files)
        val saved = profile()
        repository.createProfile(saved)
        val vm = LayoutEditorViewModel(saved.id, repository)
        vm.updateProfileNameInput("Visual draft to discard")
        val validVisualDraft = saved.copy(name = "Visual draft to discard")
        assertTrue(vm.enterXmlMode())
        val rawInvalid = "<layout version=\"1\" id=\"${saved.id}\""
        vm.updateXmlSource(rawInvalid)
        assertFalse(vm.validateXmlSource())
        vm.requestXmlExit(XmlEditorExitAction.BACK_TO_VISUAL)
        val confirmation = vm.state.value.xmlExitConfirmation

        files.failNextDraftDelete = true
        assertFalse(vm.discard())
        assertEquals(LayoutEditorMode.XML, vm.state.value.editMode)
        assertEquals(rawInvalid, vm.state.value.xmlSource)
        assertEquals(confirmation, vm.state.value.xmlExitConfirmation)
        assertEquals(validVisualDraft, vm.state.value.draftProfile)
        assertEquals(validVisualDraft, repository.loadDraft(saved.id))
        assertEquals(saved, repository.loadProfile(saved.id))

        assertTrue(vm.discard())
        assertEquals(LayoutEditorMode.VISUAL, vm.state.value.editMode)
        assertEquals("", vm.state.value.xmlSource)
        assertEquals("", vm.state.value.xmlBaseSource)
        assertNull(vm.state.value.xmlMessage)
        assertNull(vm.state.value.xmlExitConfirmation)
        assertFalse(vm.state.value.isDirty)
        assertEquals(saved, vm.state.value.draftProfile)
        assertNull(repository.loadDraft(saved.id))
        assertEquals(saved, repository.loadProfile(saved.id))
    }

    @Test fun oversizedSourceIsRejectedWithoutTruncatingTheExistingBuffer() {
        val repository = LayoutProfileRepository(XmlMemoryFiles())
        val saved = profile()
        repository.createProfile(saved)
        val vm = LayoutEditorViewModel(saved.id, repository)
        assertTrue(vm.enterXmlMode())
        val before = vm.state.value.xmlSource
        vm.updateXmlSource("x".repeat(LayoutProfileXml.MAX_SOURCE_CHARS + 1))
        assertEquals(before, vm.state.value.xmlSource)
        assertTrue(vm.state.value.xmlMessage!!.contains("limit"))
        assertTrue(vm.state.value.xmlMessageIsError)
    }

    private fun profile() = LayoutProfile(
        id = "xml-editor-profile",
        name = "XML editor",
        buttons = listOf(
            LayoutButton("first", "First", "xdotool key alt+F4", 2, "#aBcDeF"),
            LayoutButton("second", "Second", "  printf 'opaque\r\n\t'  ", 5, "#80112233"),
        ),
    )

    private class XmlMemoryFiles : LayoutProfileFileOps {
        private val files = mutableMapOf<String, ByteArray>()
        var draftWrites = 0
        var failNextDraftWrite = false
        var failNextDraftDelete = false
        override fun listFileNames(): List<String> = files.keys.toList()
        override fun readAtomically(fileName: String): ByteArray? = files[fileName]?.copyOf()
        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            if (fileName.endsWith(".draft.json")) {
                if (failNextDraftWrite) {
                    failNextDraftWrite = false
                    throw IOException("injected draft write failure")
                }
                draftWrites++
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
