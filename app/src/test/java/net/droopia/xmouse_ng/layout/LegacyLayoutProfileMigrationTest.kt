package net.droopia.xmouse_ng.layout

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyLayoutProfileMigrationTest {
    @Test
    fun commaRecordsKeepTheLegacyMissingColorDefaultEvenWithExtraColumns() {
        val record = LegacyLayoutReader.parseRecord("Button,command,2,#FF0000", "#FFFFFF")

        assertEquals("Button", record.title)
        assertEquals("command", record.command)
        assertEquals(2, record.span)
        assertEquals("#FFFFFF", record.color)
    }

    @Test
    fun migratesAllFourSourcesWithStableDistinctProfilesAndPreservesSources() {
        val legacyDirectory = Files.createTempDirectory("legacy-layout-migration").toFile()
        try {
            val fixtures = listOf(
                FixtureSource("keyLayoutFile.csv", "xmousesep/keyLayoutFile.csv"),
                FixtureSource("keyFileOne.csv", "comma/keyFileOne.csv"),
                FixtureSource("keyFileTwo.csv", "xmousesep/keyFileTwo.csv"),
                FixtureSource("keyFileThree.csv", "comma/keyFileThree.csv"),
            )
            fixtures.forEach { source ->
                File(legacyDirectory, source.fileName).writeText(readFixture(source.fixturePath), Charsets.UTF_8)
            }
            val originalSources = fixtures.associate { source ->
                source.fileName to File(legacyDirectory, source.fileName).readBytes()
            }

            val files = InMemoryProfileFiles()
            val repository = LayoutProfileRepository(files)
            val migrator = LegacyLayoutProfileMigrator(
                legacyDirectory,
                repository,
                LegacyLayoutMigrationCheckpointStore(InMemoryProfileFiles()),
            )
            val profiles = migrator.migrate()

            assertEquals(listOf("Active layout", "Preset 1", "Preset 2", "Preset 3"), profiles.map { it.name })
            assertEquals(4, profiles.map { it.id }.toSet().size)
            assertEquals(4, profiles.map { it.name }.toSet().size)
            assertEquals(4, profiles.map { it.buttons }.toSet().size)
            assertButtons(
                profiles[0],
                listOf(
                    ExpectedButton("Terminal", "xdotool key ctrl+alt+t", 4, "#DDEEFF"),
                    ExpectedButton("Files", "xdotool key super+e", 3, "#FFFFFF"),
                ),
            )
            assertButtons(
                profiles[1],
                listOf(
                    ExpectedButton("Address bar", "xdotool key ctrl+l", 3, "#FFFFFF"),
                    ExpectedButton("Reload", "xdotool key ctrl+r", 2, "#FFFFFF"),
                ),
            )
            assertButtons(
                profiles[2],
                listOf(
                    ExpectedButton("Next desktop", "xdotool set_desktop 1", 4, "#D8EAD2"),
                    ExpectedButton("Previous desktop", "xdotool set_desktop 0", 4, "#FFE599"),
                ),
            )
            assertButtons(
                profiles[3],
                listOf(
                    ExpectedButton("Play", "xdotool key XF86AudioPlay", 3, "#FFFFFF"),
                    ExpectedButton("Mute", "xdotool key XF86AudioMute", 2, "#FFFFFF"),
                ),
            )

            profiles.forEach { assertEquals(it, repository.loadProfile(it.id)) }
            assertEquals(4, files.savedProfileCount())

            val repeatedProfiles = migrator.migrate()
            assertEquals(profiles, repeatedProfiles)
            assertEquals(4, files.savedProfileCount())
            originalSources.forEach { (fileName, bytes) ->
                assertArrayEquals(bytes, File(legacyDirectory, fileName).readBytes())
            }
        } finally {
            assertTrue(legacyDirectory.deleteRecursively())
        }
    }

    @Test
    fun interruptedCheckpointAndMalformedSourceRecoverAcrossRestartsWithoutDefaultsOrDuplicates() {
        val directory = Files.createTempDirectory("legacy-layout-recovery").toFile()
        try {
            val activeFile = File(directory, "keyLayoutFile.csv")
            val damagedFile = File(directory, "keyFileOne.csv")
            val activeBytes = readFixture("xmousesep/keyLayoutFile.csv").toByteArray(Charsets.UTF_8)
            val damagedBytes = "Valid,first command,2\nBroken,do-not-log-this,2,ambiguous\n"
                .toByteArray(Charsets.UTF_8)
            activeFile.writeBytes(activeBytes)
            damagedFile.writeBytes(damagedBytes)
            val profileFiles = InMemoryProfileFiles()
            val checkpointFiles = InMemoryProfileFiles()
            fun checkpoints() = LegacyLayoutMigrationCheckpointStore(checkpointFiles)
            fun repository() = LayoutProfileRepository(profileFiles)
            fun migrator() = LegacyLayoutProfileMigrator(directory, repository(), checkpoints())
            val userProfile = LayoutProfile(id = "user-layout", name = "Independent user profile")
            repository().saveProfile(userProfile)

            // Deterministic interruption AFTER the valid profile is durable, BEFORE its checkpoint.
            checkpointFiles.interruptNextWriteSuffix = ".migration.json"
            val interrupted = migrator().migrateWithReport()
            val active = interrupted.profiles.single()
            assertEquals(
                listOf(LegacyLayoutMigrationFailure.CHECKPOINT_WRITE_FAILED, LegacyLayoutMigrationFailure.AMBIGUOUS_SOURCE),
                interrupted.warnings.map { it.reason },
            )
            assertNull(checkpoints().read(active.id))
            assertEquals(active, repository().loadProfile(active.id))
            assertEquals(2, profileFiles.savedProfileCount()) // One import plus the unrelated user profile.
            val error = checkpoints().read("legacy-keyFileOne")!!
            assertEquals(LegacyLayoutMigrationFailure.AMBIGUOUS_SOURCE, error.failureReason)
            assertEquals(2, error.recordNumber)
            assertEquals(sha256(damagedBytes), error.fingerprint)
            assertFalse(interrupted.warnings.any { it.message.contains("do-not-log-this") })

            // New repository/store/migrator instances resume the same identity; the list API also
            // surfaces the still-malformed source instead of silently returning a partial success.
            val retried = assertThrows(LegacyLayoutMigrationException::class.java) { migrator().migrate() }.result
            assertEquals(listOf(active), retried.profiles)
            assertEquals(listOf(interrupted.warnings.last()), retried.warnings)
            val success = checkpoints().read(active.id)!!
            assertTrue(success.succeededFor(sha256(activeBytes), success.parserVersion))
            assertFalse(success.succeededFor(sha256(activeBytes), success.parserVersion + 1))
            val edited = active.copy(name = "User renamed after import")
            repository().saveProfile(edited)
            val writesBeforeRepeat = profileFiles.profileWrites
            assertEquals(listOf(edited), migrator().migrateWithReport().profiles)
            assertEquals(writesBeforeRepeat, profileFiles.profileWrites)
            assertEquals(error, checkpoints().read("legacy-keyFileOne"))
            assertArrayEquals(activeBytes, activeFile.readBytes())
            assertArrayEquals(damagedBytes, damagedFile.readBytes())

            val repairedBytes = readFixture("comma/keyFileOne.csv").toByteArray(Charsets.UTF_8)
            damagedFile.writeBytes(repairedBytes) // Only this simulated user repair changes source bytes.
            profileFiles.interruptNextWriteSuffix = ".profile.json"
            val failedSave = migrator().migrateWithReport()
            assertEquals(LegacyLayoutMigrationFailure.PROFILE_SAVE_FAILED, failedSave.warnings.single().reason)
            assertEquals(2, profileFiles.savedProfileCount())
            assertEquals(LegacyLayoutMigrationFailure.PROFILE_SAVE_FAILED, checkpoints().read("legacy-keyFileOne")!!.failureReason)
            assertThrows(LayoutProfileNotFoundException::class.java) { repository().loadProfile("legacy-keyFileOne") }

            val recovered = migrator().migrateWithReport()
            assertTrue(recovered.warnings.isEmpty())
            assertEquals(listOf(edited.id, "legacy-keyFileOne"), recovered.profiles.map { it.id })
            val repairedStatus = checkpoints().read("legacy-keyFileOne")!!
            assertNull(repairedStatus.failureReason)
            assertEquals(sha256(repairedBytes), repairedStatus.fingerprint)
            assertNotEquals(error.fingerprint, repairedStatus.fingerprint)
            assertEquals(recovered.profiles.last(), repository().loadProfile("legacy-keyFileOne"))
            assertNull(repository().loadDraft("legacy-keyFileOne"))
            val writesAfterRecovery = profileFiles.profileWrites
            assertEquals(recovered.profiles, migrator().migrate())
            assertEquals(writesAfterRecovery, profileFiles.profileWrites)
            assertEquals(3, profileFiles.savedProfileCount())
            assertEquals(userProfile, repository().loadProfile(userProfile.id))
            assertArrayEquals(activeBytes, activeFile.readBytes())
            assertArrayEquals(repairedBytes, damagedFile.readBytes())

            // A previous success must not hide newly damaged bytes or overwrite the saved profile.
            damagedFile.writeBytes(damagedBytes)
            assertEquals(LegacyLayoutMigrationFailure.AMBIGUOUS_SOURCE, migrator().migrateWithReport().warnings.single().reason)
            assertEquals(recovered.profiles.last(), repository().loadProfile("legacy-keyFileOne"))
            assertArrayEquals(damagedBytes, damagedFile.readBytes())
            damagedFile.writeBytes(repairedBytes)
            assertTrue(migrator().migrateWithReport().warnings.isEmpty())
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun restoredSuccessfulSourceDoesNotOverwriteUserEditsAfterSourceErrors() {
        val directory = Files.createTempDirectory("legacy-layout-success-recovery").toFile()
        try {
            val source = File(directory, "keyLayoutFile.csv")
            val originalBytes = readFixture("xmousesep/keyLayoutFile.csv").toByteArray(Charsets.UTF_8)
            source.writeBytes(originalBytes)
            val profileFiles = InMemoryProfileFiles()
            val checkpointFiles = InMemoryProfileFiles()
            fun repository() = LayoutProfileRepository(profileFiles)
            fun checkpoints() = LegacyLayoutMigrationCheckpointStore(checkpointFiles)
            fun migrator() = LegacyLayoutProfileMigrator(directory, repository(), checkpoints())
            val imported = migrator().migrate().single()
            val edited = imported.copy(
                name = "User edits must survive source recovery",
                buttons = imported.buttons.map { it.copy(command = "user-edited command") },
            )
            repository().saveProfile(edited)
            val writesAfterEdit = profileFiles.profileWrites

            source.writeText("Broken,do-not-log-this,2,ambiguous\n", Charsets.UTF_8)
            assertEquals(LegacyLayoutMigrationFailure.AMBIGUOUS_SOURCE, migrator().migrateWithReport().warnings.single().reason)
            // A second, unreadable attempt must retain provenance from before the first error.
            assertTrue(source.delete())
            assertTrue(source.mkdir())
            assertEquals(LegacyLayoutMigrationFailure.UNREADABLE_SOURCE, migrator().migrateWithReport().warnings.single().reason)
            assertTrue(source.isDirectory)
            assertTrue(source.delete())
            source.writeBytes(originalBytes)

            val recovered = migrator().migrateWithReport()
            assertTrue(recovered.warnings.isEmpty())
            assertEquals(listOf(edited), recovered.profiles)
            assertEquals(writesAfterEdit, profileFiles.profileWrites)
            assertNull(checkpoints().read(imported.id)!!.failureReason)
            assertArrayEquals(originalBytes, source.readBytes())
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertButtons(profile: LayoutProfile, expected: List<ExpectedButton>) {
        assertEquals(expected.map { it.title }, profile.buttons.map { it.title })
        assertEquals(expected.map { it.command }, profile.buttons.map { it.command })
        assertEquals(expected.map { it.width }, profile.buttons.map { it.width })
        assertEquals(expected.map { it.color }, profile.buttons.map { it.color })
    }

    private fun readFixture(path: String): String =
        checkNotNull(javaClass.getResourceAsStream("/legacy/layouts/$path")) {
            "Missing legacy layout fixture: $path"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private data class FixtureSource(val fileName: String, val fixturePath: String)

    private data class ExpectedButton(
        val title: String,
        val command: String,
        val width: Int,
        val color: String,
    )

    private class InMemoryProfileFiles : LayoutProfileFileOps {
        private val committed = mutableMapOf<String, ByteArray>()
        var interruptNextWriteSuffix: String? = null
        var profileWrites = 0
            private set

        override fun listFileNames(): List<String> = committed.keys.toList()

        override fun readAtomically(fileName: String): ByteArray? = committed[fileName]?.copyOf()

        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            if (interruptNextWriteSuffix?.let { fileName.endsWith(it) } == true) {
                interruptNextWriteSuffix = null
                // AtomicFile's contract: a truncated staging write never replaces committed bytes.
                throw IOException("injected interruption before atomic commit")
            }
            committed[fileName] = bytes.copyOf()
            if (fileName.endsWith(".profile.json")) profileWrites++
        }

        override fun deleteAtomically(fileName: String) {
            committed.remove(fileName)
        }

        fun savedProfileCount(): Int = committed.keys.count { it.endsWith(".profile.json") }
    }
}
