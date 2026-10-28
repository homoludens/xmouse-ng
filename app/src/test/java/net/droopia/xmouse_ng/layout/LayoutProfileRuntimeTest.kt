package net.droopia.xmouse_ng.layout

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutProfileRuntimeTest {
    @Test
    fun migrationGateKeepsLegacySourcesUntilRepositoryActivationAndPauseResume() {
        val cleanDirectory = Files.createTempDirectory("layout-runtime-clean").toFile()
        val damagedDirectory = Files.createTempDirectory("layout-runtime-damaged").toFile()
        try {
            val sources = listOf(
                "keyLayoutFile.csv" to "xmousesep/keyLayoutFile.csv",
                "keyFileOne.csv" to "comma/keyFileOne.csv",
                "keyFileTwo.csv" to "xmousesep/keyFileTwo.csv",
                "keyFileThree.csv" to "comma/keyFileThree.csv",
            )
            sources.forEach { (fileName, fixture) ->
                File(cleanDirectory, fileName).writeBytes(readFixture(fixture))
            }
            val originalSources = sources.associate { (fileName, _) ->
                fileName to File(cleanDirectory, fileName).readBytes()
            }
            val cleanProfileFiles = MemoryFiles()
            val cleanCheckpointFiles = MemoryFiles()
            val cleanRepository = LayoutProfileRepository(cleanProfileFiles)
            fun cleanRuntime() = LayoutProfileRuntime(
                cleanRepository,
                LegacyLayoutProfileMigrator(
                    cleanDirectory,
                    cleanRepository,
                    LegacyLayoutMigrationCheckpointStore(cleanCheckpointFiles),
                ),
            )

            val firstRuntime = cleanRuntime()
            val activated = firstRuntime.activate()
            assertTrue(activated.activated)
            assertFalse(activated.readOnlyLegacyFallback)
            val activeProfile = activated.profile!!
            assertEquals(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID, activeProfile.id)
            assertEquals("Terminal", activeProfile.buttons.first().title)
            assertTrue(firstRuntime.isRepositoryActive())

            val edited = activeProfile.copy(
                buttons = activeProfile.buttons.map { button ->
                    if (button.title == "Terminal") button.copy(command = "user-edited command") else button
                },
            )
            firstRuntime.saveCurrentProfile(edited.buttons) // Activity pause.

            val resumedRuntime = cleanRuntime()
            val resumed = resumedRuntime.activate() // Activity recreation/resume.
            assertTrue(resumed.activated)
            assertEquals(edited, resumed.profile)
            resumedRuntime.saveCurrentProfile(resumed.profile!!.buttons) // Another pause.
            originalSources.forEach { (fileName, bytes) ->
                assertArrayEquals(bytes, File(cleanDirectory, fileName).readBytes())
            }

            val malformed = "Broken,do-not-log-this,2,ambiguous\n".toByteArray(Charsets.UTF_8)
            val damagedFixtures = listOf(
                "keyLayoutFile.csv" to null,
                "keyFileOne.csv" to "comma/keyFileOne.csv",
                "keyFileTwo.csv" to "xmousesep/keyFileTwo.csv",
                "keyFileThree.csv" to "comma/keyFileThree.csv",
            )
            damagedFixtures.forEach { (fileName, fixture) ->
                File(damagedDirectory, fileName).writeBytes(
                    if (fixture == null) malformed else readFixture(fixture),
                )
            }
            val damagedSources = damagedFixtures.associate { (fileName, _) ->
                fileName to File(damagedDirectory, fileName).readBytes()
            }
            val damagedProfileFiles = MemoryFiles()
            val damagedRepository = LayoutProfileRepository(damagedProfileFiles)
            val damagedRuntime = LayoutProfileRuntime(
                damagedRepository,
                LegacyLayoutProfileMigrator(
                    damagedDirectory,
                    damagedRepository,
                    LegacyLayoutMigrationCheckpointStore(MemoryFiles()),
                ),
            )
            val blocked = damagedRuntime.activate()
            assertFalse(blocked.activated)
            assertFalse(damagedRuntime.isRepositoryActive())
            assertTrue(blocked.readOnlyLegacyFallback)
            assertNotNull(blocked.warning)
            assertTrue(blocked.warning!!.contains("read-only"))
            assertFalse(blocked.warning!!.contains("do-not-log-this"))
            assertNull(damagedRuntime.saveCurrentProfile(emptyList()))
            damagedSources.forEach { (fileName, bytes) ->
                assertArrayEquals(bytes, File(damagedDirectory, fileName).readBytes())
            }
        } finally {
            assertTrue(cleanDirectory.deleteRecursively())
            assertTrue(damagedDirectory.deleteRecursively())
        }
    }

    private fun readFixture(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/legacy/layouts/$path"))
            .use { it.readBytes() }

    private class MemoryFiles : LayoutProfileFileOps {
        private val files = mutableMapOf<String, ByteArray>()

        override fun listFileNames(): List<String> = files.keys.toList()

        override fun readAtomically(fileName: String): ByteArray? = files[fileName]?.copyOf()

        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            files[fileName] = bytes.copyOf()
        }

        override fun deleteAtomically(fileName: String) {
            files.remove(fileName)
        }
    }
}
