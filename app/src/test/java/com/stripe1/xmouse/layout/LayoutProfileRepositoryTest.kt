package com.stripe1.xmouse.layout

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutProfileRepositoryTest {
    @Test
    fun atomicRoundTripAndInterruptedSaveKeepSavedProfileAndRetryableDraft() {
        val files = FakeLayoutProfileFileOps()
        val firstRepository = LayoutProfileRepository(files)
        val saved = profile(name = "Büro, original", command = "printf '雪, \"raw\"'\nsecond line")
        firstRepository.saveProfile(saved)

        val recreated = LayoutProfileRepository(files)
        assertEquals(saved, recreated.loadProfile(saved.id))

        val attempted = profile(name = "改訂 draft", command = "echo  keep spaces, commas\nline two")
        files.interruptNextProfileWrite = true
        val writeFailure = assertThrows(LayoutProfileWriteException::class.java) {
            recreated.saveProfile(attempted)
        }
        assertEquals(LayoutProfileRecord.PROFILE, writeFailure.record)
        assertTrue(files.truncatedStageWasWritten)

        val afterInterruption = LayoutProfileRepository(files)
        assertEquals(saved, afterInterruption.loadProfile(saved.id))
        assertEquals(attempted, afterInterruption.loadDraft(saved.id))

        afterInterruption.saveProfile(afterInterruption.loadDraft(saved.id)!!)
        val afterRetry = LayoutProfileRepository(files)
        assertEquals(attempted, afterRetry.loadProfile(saved.id))
        assertNull(afterRetry.loadDraft(saved.id))

        files.failNextRead = true
        val readFailure = assertThrows(LayoutProfileReadException::class.java) {
            afterRetry.loadProfile(saved.id)
        }
        assertEquals(LayoutProfileRecord.PROFILE, readFailure.record)
        assertFalse(files.interruptNextProfileWrite)
    }

    @Test
    fun profileCrudUsesStableIdsAndRecoversInterruptedCatalogOperations() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val profiles = (1..5).map { index ->
            LayoutProfile(
                id = "profile-$index",
                name = if (index <= 2) "Shared name" else "Profile $index",
                buttons = listOf(
                    LayoutButton(
                        id = "button-$index-a",
                        title = "雪, first $index",
                        command = "printf 'value $index, \\\"quoted\\\"'\nnext line",
                        width = 3,
                        color = "#80ABCDEF",
                    ),
                    LayoutButton(
                        id = "button-$index-b",
                        title = "Second $index",
                        command = "echo  spaces and commas, $index",
                        width = 7,
                        color = "#123456",
                    ),
                ),
            )
        }
        profiles.forEach(repository::createProfile)
        assertEquals(profiles, repository.listProfiles())

        val renamed = repository.renameProfile(profiles.first().id, "Renamed")
        assertEquals(profiles.first().copy(name = "Renamed"), renamed)
        assertEquals(profiles[1], repository.loadProfile(profiles[1].id))

        val interruptedCreate = profiles[2].copy(id = "profile-after-catalog-failure")
        files.failNextCatalogWrite = true
        val createFailure = assertThrows(LayoutProfileWriteException::class.java) {
            repository.createProfile(interruptedCreate)
        }
        assertEquals(LayoutProfileRecord.CATALOG, createFailure.record)
        assertEquals(interruptedCreate, repository.listProfiles().single { it.id == interruptedCreate.id })

        val duplicate = repository.duplicateProfile(renamed.id)
        assertNotEquals(renamed.id, duplicate.id)
        assertEquals(renamed.name, duplicate.name)
        assertEquals(
            renamed.buttons.mapIndexed { index, button -> button.copy(id = duplicate.buttons[index].id) },
            duplicate.buttons,
        )
        assertTrue(
            renamed.buttons.map(LayoutButton::id).toSet()
                .intersect(duplicate.buttons.map(LayoutButton::id).toSet()).isEmpty(),
        )
        assertEquals(renamed, repository.loadProfile(renamed.id))

        val targetDraft = profiles.first().copy(name = "target draft")
        val unrelatedDraft = profiles[1].copy(name = "unrelated draft")
        repository.saveDraft(targetDraft)
        repository.saveDraft(unrelatedDraft)
        repository.selectProfile(duplicate.id)
        val reloaded = LayoutProfileRepository(files)
        assertEquals(duplicate, reloaded.loadSelectedProfile())

        val mutationsBeforeCancel = files.mutationCount
        assertFalse(repository.deleteProfile(profiles[1].id, confirmed = false))
        assertEquals(mutationsBeforeCancel, files.mutationCount)
        assertEquals(profiles[1], repository.loadProfile(profiles[1].id))
        assertEquals(unrelatedDraft, repository.loadDraft(profiles[1].id))

        val mutationsBeforeCollision = files.mutationCount
        assertThrows(LayoutProfileAlreadyExistsException::class.java) {
            repository.createProfile(profiles[1].copy(name = "would overwrite"))
        }
        assertEquals(mutationsBeforeCollision, files.mutationCount)
        assertEquals(profiles[1], repository.loadProfile(profiles[1].id))

        files.failNextDelete = true
        val deleteFailure = assertThrows(LayoutProfileWriteException::class.java) {
            repository.deleteProfile(profiles.first().id, confirmed = true)
        }
        assertEquals(LayoutProfileRecord.PROFILE, deleteFailure.record)
        assertTrue(repository.listProfiles().none { it.id == profiles.first().id })
        assertNull(repository.loadDraft(profiles.first().id))
        assertEquals(profiles[1], repository.loadProfile(profiles[1].id))
        assertEquals(unrelatedDraft, repository.loadDraft(profiles[1].id))
        assertEquals(duplicate, repository.loadSelectedProfile())

        assertTrue(repository.deleteProfile(profiles[2].id, confirmed = true))
        assertEquals(5, repository.listProfiles().size)
        assertEquals(profiles[3], repository.loadProfile(profiles[3].id))
        assertEquals(profiles[4], repository.loadProfile(profiles[4].id))
        assertEquals(interruptedCreate, repository.loadProfile(interruptedCreate.id))
        assertEquals(duplicate, repository.loadSelectedProfile())
    }

    @Test
    fun managerKeepsNamedProfilesIndependentAndProtectsSelectionAndDeletion() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val profiles = (1..5).map { index ->
            LayoutProfile(
                id = "manager-profile-$index",
                name = if (index <= 2) "Shared name" else "Layout $index",
                buttons = listOf(
                    LayoutButton(
                        id = "manager-button-$index",
                        title = "雪, control $index",
                        command = " printf 'raw, \"value $index\"' \nsecond line  ",
                        width = index,
                        color = "#80ABCDEF",
                    ),
                ),
            )
        }
        profiles.forEach(repository::createProfile)
        repository.selectProfile(profiles.first().id)

        val manager = LayoutProfileManagerViewModel(repository)
        assertEquals(5, manager.state.value.profiles.size)
        assertEquals(profiles.first().id, manager.state.value.selectedProfileId)

        manager.beginRename(profiles.first().id)
        manager.updateNameInput("Renamed layout")
        assertTrue(manager.commitNameAction())
        val renamed = profiles.first().copy(name = "Renamed layout")
        assertEquals(renamed, repository.loadProfile(profiles.first().id))
        assertEquals(profiles[1], repository.loadProfile(profiles[1].id))

        manager.beginDuplicate(profiles.first().id)
        manager.updateNameInput("Shared name")
        assertTrue(manager.commitNameAction())
        val duplicate = manager.state.value.profiles.single {
            it.id != profiles.first().id && it.id != profiles[1].id && it.name == "Shared name"
        }
        assertNotEquals(profiles.first().id, duplicate.id)
        assertEquals(
            renamed.buttons.mapIndexed { index, button -> button.copy(id = duplicate.buttons[index].id) },
            duplicate.buttons,
        )
        assertTrue(
            renamed.buttons.map(LayoutButton::id).toSet()
                .intersect(duplicate.buttons.map(LayoutButton::id).toSet()).isEmpty(),
        )
        assertEquals(renamed, repository.loadProfile(profiles.first().id))
        assertEquals(profiles[1], repository.loadProfile(profiles[1].id))

        manager.selectProfile(profiles[4].id)
        assertEquals(profiles[4].id, LayoutProfileRepository(files).loadSelectedProfile()?.id)

        manager.beginCreate()
        manager.updateNameInput("  ")
        val beforeInvalidCreate = files.mutationCount
        assertFalse(manager.commitNameAction())
        assertEquals("  ", manager.state.value.nameInput)
        assertEquals(beforeInvalidCreate, files.mutationCount)
        manager.updateNameInput("User layout")
        assertTrue(manager.commitNameAction())
        val created = manager.state.value.profiles.single { it.name == "User layout" }
        assertTrue(created.buttons.isEmpty())
        assertEquals(7, manager.state.value.profiles.size)

        manager.requestDelete(duplicate.id)
        val beforeCancel = files.mutationCount
        manager.cancelDelete()
        assertEquals(beforeCancel, files.mutationCount)
        assertTrue(manager.state.value.profiles.any { it.id == duplicate.id })

        manager.requestDelete(duplicate.id)
        assertTrue(manager.confirmDelete())
        assertTrue(repository.listProfiles().none { it.id == duplicate.id })
        assertEquals(renamed, repository.loadProfile(profiles.first().id))
        assertEquals(profiles[1], repository.loadProfile(profiles[1].id))
        assertEquals(created, repository.loadProfile(created.id))
        assertEquals(profiles[4].id, repository.loadSelectedProfile()?.id)

        manager.requestDelete(profiles[4].id)
        assertTrue(manager.confirmDelete())
        assertEquals(profiles.first().id, repository.loadSelectedProfile()?.id)
        assertTrue(repository.listProfiles().any { it.id == repository.loadSelectedProfile()?.id })

        repository.listProfiles().drop(1).forEach { repository.deleteProfile(it.id, confirmed = true) }
        manager.refresh()
        val lastProfile = manager.state.value.profiles.single()
        val beforeLastDelete = files.mutationCount
        manager.requestDelete(lastProfile.id)
        assertNull(manager.state.value.profileToDeleteId)
        assertEquals(beforeLastDelete, files.mutationCount)
        assertThrows(LayoutProfileLastProfileException::class.java) {
            repository.deleteProfile(lastProfile.id, confirmed = true)
        }
        assertEquals(beforeLastDelete, files.mutationCount)
        assertEquals(listOf(lastProfile), repository.listProfiles())
    }

    @Test
    fun managerRefreshesPartialDeleteAndReportsRepositoryFailure() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val profiles = listOf(
            LayoutProfile(id = "delete-failure-one", name = "First"),
            LayoutProfile(id = "delete-failure-two", name = "Second"),
        )
        profiles.forEach(repository::createProfile)
        val manager = LayoutProfileManagerViewModel(repository)

        manager.requestDelete(profiles.first().id)
        files.failNextCatalogWrite = true
        assertFalse(manager.confirmDelete())

        assertEquals(profiles.first().id, manager.state.value.profileToDeleteId)
        assertEquals(
            "The layout could not be deleted reliably. Check the saved layout list before retrying.",
            manager.state.value.errorMessage,
        )
        assertTrue(manager.state.value.profiles.any { it.id == profiles.first().id })

        manager.cancelDelete()
        manager.requestDelete(profiles.last().id)
        files.failNextDelete = true
        assertFalse(manager.confirmDelete())

        assertNull(manager.state.value.profileToDeleteId)
        assertEquals(listOf(profiles.first()), manager.state.value.profiles)
        assertEquals(manager.state.value.profiles, repository.listProfiles())
        assertEquals(
            "The layout could not be deleted reliably. Check the saved layout list before retrying.",
            manager.state.value.errorMessage,
        )
    }

    @Test
    fun catalogReconcilesAtomicBackupsAndReservesBackupOnlyDraftIds() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val saved = LayoutProfile(id = "backup-profile", name = "Backup profile")
        repository.createProfile(saved)

        files.moveToBackup("${saved.id}.profile.json")
        assertEquals(listOf(saved), LayoutProfileRepository(files).listProfiles())

        files.deleteAtomically("profiles.catalog.json")
        files.moveToBackup("${saved.id}.profile.json")
        assertEquals(listOf(saved), LayoutProfileRepository(files).listProfiles())

        val draft = LayoutProfile(id = "backup-draft-id", name = "Orphaned draft")
        repository.saveDraft(draft)
        files.moveToBackup("${draft.id}.draft.json")
        val mutationsBeforeCollision = files.mutationCount
        assertThrows(LayoutProfileAlreadyExistsException::class.java) {
            repository.createProfile(draft)
        }
        assertEquals(mutationsBeforeCollision, files.mutationCount)
    }

    @Test
    fun savingAnIndexedProfileDoesNotReportFailureForAnUnneededCatalogWrite() {
        val files = FakeLayoutProfileFileOps()
        val repository = LayoutProfileRepository(files)
        val original = LayoutProfile(id = "saved-profile", name = "Original")
        repository.createProfile(original)

        val updated = original.copy(name = "Updated")
        files.failNextCatalogWrite = true
        repository.saveProfile(updated)

        assertEquals(updated, repository.loadProfile(updated.id))
        assertTrue(files.failNextCatalogWrite)
        files.failNextCatalogWrite = false
    }

    private fun profile(name: String, command: String) = LayoutProfile(
        id = "stable-profile",
        name = name,
        buttons = listOf(
            LayoutButton(
                id = "stable-button",
                title = "按鈕, control",
                command = command,
                width = 4,
                color = "#80ABCDEF",
            ),
        ),
    )

    private class FakeLayoutProfileFileOps : LayoutProfileFileOps {
        private val committed = mutableMapOf<String, ByteArray>()
        private val staging = mutableMapOf<String, ByteArray>()
        var interruptNextProfileWrite = false
        var truncatedStageWasWritten = false
            private set
        var failNextRead = false
        var failNextCatalogWrite = false
        var failNextDelete = false
        var mutationCount = 0
            private set

        override fun listFileNames(): List<String> = committed.keys.toList()

        fun moveToBackup(fileName: String) {
            committed["$fileName.bak"] = checkNotNull(committed.remove(fileName))
        }

        override fun readAtomically(fileName: String): ByteArray? {
            if (failNextRead) {
                failNextRead = false
                throw IOException("injected read failure")
            }
            committed.remove("$fileName.bak")?.let { recovered -> committed[fileName] = recovered }
            return committed[fileName]?.copyOf()
        }

        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            mutationCount++
            if (failNextCatalogWrite && fileName == "profiles.catalog.json") {
                failNextCatalogWrite = false
                throw IOException("injected catalog write failure")
            }
            if (interruptNextProfileWrite && fileName.endsWith(".profile.json")) {
                interruptNextProfileWrite = false
                staging[fileName] = bytes.copyOfRange(0, bytes.size / 2)
                truncatedStageWasWritten = true
                throw IOException("injected interruption after truncated staging write")
            }
            committed[fileName] = bytes.copyOf()
            staging.remove(fileName)
        }

        override fun deleteAtomically(fileName: String) {
            mutationCount++
            if (failNextDelete) {
                failNextDelete = false
                throw IOException("injected delete failure")
            }
            committed.remove(fileName)
            staging.remove(fileName)
        }
    }
}
