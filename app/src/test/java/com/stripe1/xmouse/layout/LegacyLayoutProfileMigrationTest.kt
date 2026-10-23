package com.stripe1.xmouse.layout

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
            val migrator = LegacyLayoutProfileMigrator(legacyDirectory, repository)
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

        override fun readAtomically(fileName: String): ByteArray? = committed[fileName]?.copyOf()

        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            committed[fileName] = bytes.copyOf()
        }

        override fun deleteAtomically(fileName: String) {
            committed.remove(fileName)
        }

        fun savedProfileCount(): Int = committed.keys.count { it.endsWith(".profile.json") }
    }
}
