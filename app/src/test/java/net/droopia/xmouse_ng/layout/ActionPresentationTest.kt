package net.droopia.xmouse_ng.layout

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPresentationTest {
    @Test
    fun onlyExactCanonicalSingleChordCommandsBecomeShortcuts() {
        val canonical = listOf("key alt+F4", "xdotool key alt+F4")
        canonical.forEach { command ->
            val parsed = ActionCommandCodec.classify(command)
            assertEquals(ActionCommandType.KEYBOARD_SHORTCUT, parsed.type)
            assertEquals("alt+F4", parsed.value)
            assertEquals(command, ActionCommandCodec.serialize(parsed.type, parsed.value, command))
        }

        val opaque = listOf(
            "key alt+F4 extra",
            "key --clearmodifiers",
            "key --repeat",
            "xdotool key --clearmodifiers alt+F4",
            "xdotool  key alt+F4",
            " key alt+F4",
            "key alt+F4 ",
            "key alt+F4\n",
            "xdotool windowminimize $(xdotool getactivewindow)",
            "printf 'line one'\nsecond line  ",
        )
        opaque.forEach { command ->
            val parsed = ActionCommandCodec.classify(command)
            assertEquals(ActionCommandType.ADVANCED_CUSTOM_COMMAND, parsed.type)
            assertEquals("Untouched advanced command must be byte-for-byte preserved", command,
                ActionCommandCodec.serialize(parsed.type, parsed.value, command))
        }

        assertEquals("key alt+F4", ActionCommandCodec.serialize(
            ActionCommandType.KEYBOARD_SHORTCUT, "alt+F4", ""))
    }

    @Test
    fun realRepositoryRestartRoundTripsIdsOrderRawCommandsCustomWidthsAndRgb() {
        val directory = Files.createTempDirectory("actions-round-trip").toFile()
        try {
            val repository = LayoutProfileRepository(DirectoryFiles(directory))
            val profile = LayoutProfile(
                id = "persisted-actions",
                name = "Same name is allowed",
                buttons = listOf(
                    LayoutButton("stable-1", "Shortcut", "xdotool key alt+F4", 5, "#ffffff"),
                    LayoutButton("stable-2", "Opaque", "xdotool key --repeat 2 alt+F4\n", 2, "#80aBcDeF"),
                    LayoutButton("stable-3", "Custom wide", "echo exact  ", 7, "#112233"),
                ),
            )
            repository.createProfile(profile)
            repository.selectProfile(profile.id)

            val restarted = LayoutProfileRepository(DirectoryFiles(directory))
            assertEquals(profile, restarted.loadProfile(profile.id))
            assertEquals(profile, restarted.loadSelectedProfile())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun namedProfilesAllowDuplicateNamesMoreThanThreeAndRestartAfterDeletingInitialEmptyProfile() {
        val directory = Files.createTempDirectory("actions-named-profiles").toFile()
        try {
            val repository = LayoutProfileRepository(DirectoryFiles(directory))
            val initial = LayoutProfile(
                id = LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID,
                name = "Actions",
            )
            repository.createProfile(initial)
            repository.selectProfile(initial.id)
            repeat(6) { index -> repository.createProfile(LayoutProfile(id = "named-$index", name = "Same name")) }
            repository.selectProfile("named-4")
            repository.deleteProfile(initial.id, confirmed = true)
            val restarted = LayoutProfileRepository(DirectoryFiles(directory))
            val runtime = LayoutProfileRuntime(restarted, LegacyLayoutProfileMigrator(directory, restarted))
            val activated = runtime.activate()
            assertTrue(activated.activated)
            assertEquals("named-4", activated.profile!!.id)
            assertEquals((0..5).map { "named-$it" }, ActionPresentation.layoutChoices(restarted.listProfiles()).map { it.id })
            assertTrue(restarted.listProfiles().all { it.name == "Same name" && it.buttons.isEmpty() })
            runtime.selectProfile("named-2")
            assertNull("a queued legacy Save must not write to a later selected profile",
                runtime.saveCurrentProfileIfSelected("named-4", listOf(LayoutButton("stale", "Stale", "key Escape", 5))))
            assertTrue(restarted.loadProfile("named-2").buttons.isEmpty())
            assertTrue(restarted.loadProfile("named-4").buttons.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    /** JVM disk/restart seam. AtomicFile crash behavior is covered by repository/Android tests. */
    private class DirectoryFiles(private val directory: File) : LayoutProfileFileOps {
        override fun listFileNames() = directory.list()!!.toList()
        override fun readAtomically(fileName: String) = File(directory, fileName).takeIf(File::exists)?.readBytes()
        override fun writeAtomically(fileName: String, bytes: ByteArray) = File(directory, fileName).writeBytes(bytes)
        override fun deleteAtomically(fileName: String) { File(directory, fileName).delete() }
    }
}
