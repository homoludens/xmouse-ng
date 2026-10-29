package net.droopia.xmouse_ng.layout

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultKeysLayoutProfileInitializerTest {
    @Test
    fun freshRepositoryStartsWithStockProfileAndRestartDoesNotDuplicateIt() = withDirectory { directory ->
        val repository = LayoutProfileRepository(DirectoryFiles(directory))

        val seeded = DefaultKeysLayoutProfileInitializer.initializeIfNeeded(repository)

        assertEquals(DefaultKeysLayoutCatalog.profile(), seeded)
        assertEquals(seeded, repository.loadSelectedProfile())
        assertTrue(repository.isDefaultKeysLayoutInitializationComplete())

        val restarted = LayoutProfileRepository(DirectoryFiles(directory))
        assertNull(DefaultKeysLayoutProfileInitializer.initializeIfNeeded(restarted))
        assertEquals(listOf(DefaultKeysLayoutCatalog.profile()), restarted.listProfiles())
        assertEquals(DefaultKeysLayoutCatalog.profile(), restarted.loadSelectedProfile())
    }

    @Test
    fun upgradeFromOnlyAutomaticEmptyActionsAddsSeparateProfileAndSelectsIt() = withDirectory { directory ->
        val repository = LayoutProfileRepository(DirectoryFiles(directory))
        val oldEmpty = LayoutProfile(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID, "Actions")
        repository.createProfile(oldEmpty)
        repository.selectProfile(oldEmpty.id)

        val seeded = DefaultKeysLayoutProfileInitializer.initializeIfNeeded(repository)

        assertEquals(DefaultKeysLayoutCatalog.profile(), seeded)
        assertEquals(oldEmpty, repository.loadProfile(oldEmpty.id))
        assertEquals(seeded, repository.loadSelectedProfile())
        assertEquals(listOf(oldEmpty, DefaultKeysLayoutCatalog.profile()), repository.listProfiles())
    }

    @Test
    fun namedProfileSelectionSavedRecordAndDraftSurviveAdditiveSeeding() = withDirectory { directory ->
        val repository = LayoutProfileRepository(DirectoryFiles(directory))
        val saved = LayoutProfile(
            id = "user-workspace",
            name = "My named layout",
            buttons = listOf(LayoutButton("user-button", "Exact", "echo user data  ", 4, "#80ABCDEF")),
        )
        val draft = saved.copy(name = "Uncommitted title", buttons = saved.buttons +
            LayoutButton("draft-button", "Draft", "printf 'draft'", 3, "#123456"))
        repository.createProfile(saved)
        repository.selectProfile(saved.id)
        repository.saveDraft(draft)

        DefaultKeysLayoutProfileInitializer.initializeIfNeeded(repository)

        assertEquals(saved, repository.loadProfile(saved.id))
        assertEquals(draft, repository.loadDraft(saved.id))
        assertEquals(saved, repository.loadSelectedProfile())
        assertEquals(DefaultKeysLayoutCatalog.profile(), repository.loadProfile(DefaultKeysLayoutCatalog.PROFILE_ID))
    }

    @Test
    fun pendingDraftOnOriginalEmptyProfilePreventsChangingSelection() = withDirectory { directory ->
        val repository = LayoutProfileRepository(DirectoryFiles(directory))
        val oldEmpty = LayoutProfile(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID, "Actions")
        val draft = oldEmpty.copy(name = "My intentional empty draft")
        repository.createProfile(oldEmpty)
        repository.selectProfile(oldEmpty.id)
        repository.saveDraft(draft)

        DefaultKeysLayoutProfileInitializer.initializeIfNeeded(repository)

        assertEquals(oldEmpty, repository.loadProfile(oldEmpty.id))
        assertEquals(draft, repository.loadDraft(oldEmpty.id))
        assertEquals(oldEmpty, repository.loadSelectedProfile())
        assertEquals(DefaultKeysLayoutCatalog.profile(), repository.loadProfile(DefaultKeysLayoutCatalog.PROFILE_ID))
    }

    @Test
    fun renamedOriginalEmptyProfileRemainsSelectedAndUnchanged() = withDirectory { directory ->
        val repository = LayoutProfileRepository(DirectoryFiles(directory))
        val renamedEmpty = LayoutProfile(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID, "My empty layout")
        repository.createProfile(renamedEmpty)
        repository.selectProfile(renamedEmpty.id)

        DefaultKeysLayoutProfileInitializer.initializeIfNeeded(repository)

        assertEquals(renamedEmpty, repository.loadProfile(renamedEmpty.id))
        assertEquals(renamedEmpty, repository.loadSelectedProfile())
        assertEquals(DefaultKeysLayoutCatalog.profile(), repository.loadProfile(DefaultKeysLayoutCatalog.PROFILE_ID))
    }

    @Test
    fun existingProfileAtReservedIdIsNeverOverwritten() = withDirectory { directory ->
        val repository = LayoutProfileRepository(DirectoryFiles(directory))
        val userOwned = LayoutProfile(
            id = DefaultKeysLayoutCatalog.PROFILE_ID,
            name = "User renamed this",
            buttons = listOf(LayoutButton("custom-button", "Custom", "echo edited", 6, "#456789")),
        )
        repository.createProfile(userOwned)
        repository.selectProfile(userOwned.id)

        assertNull(DefaultKeysLayoutProfileInitializer.initializeIfNeeded(repository))
        assertEquals(userOwned, repository.loadProfile(userOwned.id))
        assertEquals(userOwned, repository.loadSelectedProfile())
        assertTrue(repository.isDefaultKeysLayoutInitializationComplete())
    }

    @Test
    fun completedSeedMarkerPreventsRestoringUserRenamesEditsOrDeliberateDeletion() = withDirectory { directory ->
        val repository = LayoutProfileRepository(DirectoryFiles(directory))
        val stock = DefaultKeysLayoutProfileInitializer.initializeIfNeeded(repository)!!
        val changed = stock.copy(
            name = "My keys",
            buttons = stock.buttons.drop(1),
        )
        repository.saveProfile(changed)

        val restarted = LayoutProfileRepository(DirectoryFiles(directory))
        assertNull(DefaultKeysLayoutProfileInitializer.initializeIfNeeded(restarted))
        assertEquals(changed, restarted.loadProfile(stock.id))

        val another = LayoutProfile(id = "another-profile", name = "Keep this")
        restarted.createProfile(another)
        restarted.deleteProfile(stock.id, confirmed = true)
        val afterDeletion = LayoutProfileRepository(DirectoryFiles(directory))
        assertNull(DefaultKeysLayoutProfileInitializer.initializeIfNeeded(afterDeletion))
        assertEquals(listOf(another), afterDeletion.listProfiles())
    }

    @Test
    fun successfulLegacyMigrationAddsSeparateStockProfileAndKeepsSourceAndSelection() = withDirectory { directory ->
        val source = File(directory, "keyFileOne.csv")
        val original = javaClass.getResourceAsStream("/legacy/layouts/xmousesep/keyLayoutFile.csv")!!
            .use { it.readBytes() }
        source.writeBytes(original)
        val repository = LayoutProfileRepository(DirectoryFiles(directory))
        val migrator = LegacyLayoutProfileMigrator(
            directory,
            repository,
            LegacyLayoutMigrationCheckpointStore(DirectoryFiles(File(directory, "layout-migration"))),
        )
        val activation = LayoutProfileRuntime(repository, migrator).activate()
        assertTrue(activation.warning ?: "legacy activation unexpectedly failed", activation.activated)
        val checkpoint = File(directory, "layout-migration/legacy-keyFileOne.migration.json").readBytes()

        val seeded = DefaultKeysLayoutProfileInitializer.initializeAfterSuccessfulMigration(repository, activation)

        assertNotNull(seeded)
        assertEquals(activation.profile, repository.loadSelectedProfile())
        assertEquals(DefaultKeysLayoutCatalog.profile(), repository.loadProfile(DefaultKeysLayoutCatalog.PROFILE_ID))
        assertTrue(repository.isDefaultKeysLayoutInitializationComplete())
        assertEquals(checkpoint.toList(), File(directory, "layout-migration/legacy-keyFileOne.migration.json").readBytes().toList())
        assertEquals(original.toList(), source.readBytes().toList())
    }

    @Test
    fun migrationWarningBlocksSeedingAndLeavesLegacySourceUnchanged() = withDirectory { directory ->
        val source = File(directory, "keyLayoutFile.csv")
        val original = "Broken,do-not-rewrite,not-a-span\n".toByteArray()
        source.writeBytes(original)
        val repository = LayoutProfileRepository(DirectoryFiles(directory))
        val migrator = LegacyLayoutProfileMigrator(
            directory,
            repository,
            LegacyLayoutMigrationCheckpointStore(DirectoryFiles(File(directory, "layout-migration"))),
        )
        val activation = LayoutProfileRuntime(repository, migrator).activate()

        assertFalse(activation.activated)
        assertNull(DefaultKeysLayoutProfileInitializer.initializeAfterSuccessfulMigration(repository, activation))
        assertTrue(repository.listProfiles().isEmpty())
        assertFalse(repository.isDefaultKeysLayoutInitializationComplete())
        assertEquals(original.toList(), source.readBytes().toList())
    }

    @Test
    fun oldCatalogWithoutOptionalSeedFieldRemainsReadable() = withDirectory { directory ->
        File(directory, "profiles.catalog.json").writeText(
            """{"version":1,"profileIds":[],"selectedProfileId":null,"deletingProfileIds":[]}""",
        )
        val repository = LayoutProfileRepository(DirectoryFiles(directory))

        assertFalse(repository.isDefaultKeysLayoutInitializationComplete())
        DefaultKeysLayoutProfileInitializer.initializeIfNeeded(repository)

        val restarted = LayoutProfileRepository(DirectoryFiles(directory))
        assertTrue(restarted.isDefaultKeysLayoutInitializationComplete())
        assertEquals(DefaultKeysLayoutCatalog.profile(), restarted.loadSelectedProfile())
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("keys-layout-initializer").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class DirectoryFiles(private val directory: File) : LayoutProfileFileOps {
        override fun listFileNames(): List<String> {
            if (!directory.exists()) directory.mkdirs()
            return directory.list()!!.toList()
        }

        override fun readAtomically(fileName: String) = File(directory, fileName).takeIf(File::exists)?.readBytes()
        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            if (!directory.exists()) directory.mkdirs()
            File(directory, fileName).writeBytes(bytes)
        }
        override fun deleteAtomically(fileName: String) { File(directory, fileName).delete() }
    }
}
