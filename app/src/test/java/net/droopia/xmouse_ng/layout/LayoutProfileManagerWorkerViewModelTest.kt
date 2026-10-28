package net.droopia.xmouse_ng.layout

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class LayoutProfileManagerWorkerViewModelTest {
    @Test fun serialManagerKeepsTypedNameAndCreatesUncappedDuplicateNamesByIdWithoutCallerIo() {
        val files = MemoryFiles()
        val repository = LayoutProfileRepository(files)
        repository.createProfile(LayoutProfile(id = "initial", name = "Same name"))
        val mutations = files.mutations
        val queued = ArrayDeque<Runnable>()
        val manager = LayoutProfileManagerWorkerViewModel(repository, Executor { queued.add(it) })
        assertTrue(manager.state.value.isLoading)
        assertEquals(mutations, files.mutations)
        queued.remove().run()
        repeat(5) {
            manager.edit { it.beginCreate() }
            manager.edit { it.updateNameInput("Same name") }
            manager.edit { it.commitNameAction() }
        }
        assertEquals(1, repository.listProfiles().size)
        while (queued.isNotEmpty()) queued.remove().run()
        val profiles = manager.state.value.profiles
        assertEquals(6, profiles.size)
        assertEquals(6, profiles.map { it.id }.toSet().size)
        assertTrue(profiles.all { it.name == "Same name" && it.buttons.isEmpty() })
        manager.edit { it.selectProfile(profiles[4].id) }; queued.remove().run()
        assertEquals(profiles[4].id, repository.loadSelectedProfile()!!.id)
        manager.edit { it.requestDelete(profiles[2].id) }; queued.remove().run()
        manager.edit { it.cancelDelete() }; queued.remove().run()
        assertEquals(profiles, repository.listProfiles())
    }

    private class MemoryFiles : LayoutProfileFileOps {
        private val files = mutableMapOf<String, ByteArray>()
        var mutations = 0
        override fun listFileNames() = files.keys.toList()
        override fun readAtomically(fileName: String) = files[fileName]?.copyOf()
        override fun writeAtomically(fileName: String, bytes: ByteArray) { mutations++; files[fileName] = bytes.copyOf() }
        override fun deleteAtomically(fileName: String) { mutations++; files.remove(fileName) }
    }
}
