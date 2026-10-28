package net.droopia.xmouse_ng.layout

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class LayoutEditorWorkerViewModelTest {
    @Test fun advancedReorderUndoRedoAndSaveStaySerialDraftOnlyUntilExplicitSave() {
        val files = MemoryFiles()
        val repository = LayoutProfileRepository(files)
        val original = LayoutProfile(id = "advanced-worker", name = "Advanced", buttons = listOf(
            LayoutButton("one", "One", "xdotool key alt+F4", 2, "#aBcDeF"),
            LayoutButton("two", "Two", "printf 'opaque'\n  ", 7, "#80112233"),
        ))
        repository.createProfile(original)
        val queued = ArrayDeque<Runnable>()
        val worker = LayoutEditorWorkerViewModel(original.id, repository, Executor { queued.add(it) })
        assertTrue(worker.state.value.isLoading)
        queued.remove().run()
        worker.edit { it.selectButton("two") }
        worker.edit { it.moveSelectedButtonEarlier() }
        assertNull(repository.loadDraft(original.id))
        while (queued.isNotEmpty()) queued.remove().run()
        val moved = original.copy(buttons = original.buttons.reversed())
        assertEquals(moved, repository.loadDraft(original.id))
        assertEquals(original, repository.loadProfile(original.id))
        assertTrue(worker.state.value.canUndo)
        worker.edit { it.undo() }; queued.remove().run()
        assertEquals(original, worker.state.value.draftProfile)
        assertTrue(worker.state.value.canRedo)
        worker.edit { it.redo() }; queued.remove().run()
        worker.saveAndClose()
        assertFalse(worker.closed.value)
        queued.remove().run()
        assertTrue(worker.closed.value)
        assertEquals(moved, repository.loadProfile(original.id))
        assertNull(repository.loadDraft(original.id))
    }

    private class MemoryFiles : LayoutProfileFileOps {
        private val files = mutableMapOf<String, ByteArray>()
        override fun listFileNames() = files.keys.toList()
        override fun readAtomically(fileName: String) = files[fileName]?.copyOf()
        override fun writeAtomically(fileName: String, bytes: ByteArray) { files[fileName] = bytes.copyOf() }
        override fun deleteAtomically(fileName: String) { files.remove(fileName) }
    }
}
