package com.stripe1.xmouse.layout

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class ActionEditorSessionViewModelTest {
    @Test
    fun titleOnlyEditKeepsStableIdsOrderRawCommandsCustomSpanAndRgbBytes() {
        val files = CountingFiles()
        val repository = LayoutProfileRepository(files)
        val original = LayoutProfile(
            id = "editor-profile",
            name = "Actions",
            buttons = listOf(
                LayoutButton("shortcut-id", "Close", "xdotool key alt+F4", 7, "#80aAbBcC"),
                LayoutButton("opaque-id", "Legacy", "xdotool key --clearmodifiers alt+F4 \n", 2, "#aBcDeF"),
            ),
        )
        repository.createProfile(original)
        val session = ActionEditorSessionViewModel(
            original.id,
            "shortcut-id",
            repository,
            Executor { it.run() },
        )

        session.save(
            ActionEditorSubmission(
                title = "Close window",
                commandType = ActionCommandType.KEYBOARD_SHORTCUT,
                commandValue = "alt+F4",
                presetWidth = null,
                colorOverride = null,
            ),
        )

        val expected = original.copy(buttons = listOf(
            original.buttons[0].copy(title = "Close window"),
            original.buttons[1],
        ))
        assertTrue(session.state.value.saved)
        assertEquals(expected, repository.loadProfile(original.id))
        assertEquals("xdotool key alt+F4", repository.loadProfile(original.id).buttons[0].command)
        assertEquals("#80aAbBcC", repository.loadProfile(original.id).buttons[0].color)
        assertEquals(7, repository.loadProfile(original.id).buttons[0].width)
    }

    @Test
    fun newShortcutUsesExistingKeyCommandAndRegularWideAndPresetColorValues() {
        val repository = LayoutProfileRepository(CountingFiles())
        val empty = LayoutProfile(id = "new-action-profile", name = "Empty")
        repository.createProfile(empty)
        val session = ActionEditorSessionViewModel(empty.id, null, repository, Executor { it.run() })

        assertEquals(5, session.state.value.initialWidth)
        session.save(
            ActionEditorSubmission(
                title = "Close window",
                commandType = ActionCommandType.KEYBOARD_SHORTCUT,
                commandValue = "alt+F4",
                presetWidth = 10,
                colorOverride = ActionEditorActivity.ACTION_COLOR_GREEN,
            ),
        )

        val created = repository.loadProfile(empty.id).buttons.single()
        assertEquals("Close window", created.title)
        assertEquals("key alt+F4", created.command)
        assertEquals(10, created.width)
        assertEquals(ActionEditorActivity.ACTION_COLOR_GREEN, created.color)
        assertTrue(created.id.isNotBlank())
    }

    @Test
    fun invalidInputAndCancelDoNotMutateRepositoryFiles() {
        val files = CountingFiles()
        val repository = LayoutProfileRepository(files)
        val profile = LayoutProfile(
            id = "invalid-action-profile",
            name = "Invalid",
            buttons = listOf(LayoutButton("keep-id", "Keep", "key alt+F4", 3, "#80ABCDEF")),
        )
        repository.createProfile(profile)
        val afterSetup = files.mutations

        val cancelled = ActionEditorSessionViewModel(profile.id, "keep-id", repository, Executor { it.run() })
        assertEquals("Opening then cancelling is draft-only", afterSetup, files.mutations)
        assertNull(repository.loadDraft(profile.id))

        cancelled.save(
            ActionEditorSubmission(
                title = "Changed",
                commandType = ActionCommandType.KEYBOARD_SHORTCUT,
                commandValue = "alt + F4",
                presetWidth = 10,
                colorOverride = null,
            ),
        )
        assertEquals("Invalid shortcut is rejected before any write", afterSetup, files.mutations)
        assertEquals(profile, repository.loadProfile(profile.id))
        assertNull(repository.loadDraft(profile.id))

        cancelled.save(
            ActionEditorSubmission(
                title = "Changed",
                commandType = ActionCommandType.ADVANCED_CUSTOM_COMMAND,
                commandValue = "key alt+F4",
                presetWidth = null,
                colorOverride = "not-rgb",
            ),
        )
        assertEquals("Invalid RGB remains form-only", afterSetup, files.mutations)
        assertEquals(profile, repository.loadProfile(profile.id))
        assertFalse(cancelled.state.value.saved)
    }

    @Test fun allLegacySpansAndOpaqueRgbCommandsSurviveTitleOrPresetEditsAndReload() {
        val files = CountingFiles()
        val repository = LayoutProfileRepository(files)
        val original = LayoutProfile(id = "all-spans", name = "Duplicate name", buttons = (1..10).map { width ->
            LayoutButton("id-$width", "Action $width", "xdotool key --repeat $width alt+F4 \n", width, "#80aAbBcC")
        })
        val peer = LayoutProfile(id = "peer", name = original.name)
        repository.createProfile(original); repository.createProfile(peer); repository.selectProfile(original.id)
        for (button in original.buttons) {
            val session = ActionEditorSessionViewModel(original.id, button.id, repository, Executor { it.run() })
            session.save(ActionEditorSubmission(button.title + " edited", ActionCommandType.ADVANCED_CUSTOM_COMMAND,
                button.command, null, null))
            assertTrue(session.state.value.saved)
        }
        val reloaded = LayoutProfileRepository(files)
        assertEquals(original.copy(buttons = original.buttons.map { it.copy(title = it.title + " edited") }),
            reloaded.loadSelectedProfile())
        assertEquals(peer, reloaded.loadProfile(peer.id))
    }

    @Test fun recoveryDraftIsNotOverwrittenAndAtomicSaveFailureCanRetryWithoutRetypingCommands() {
        val files = CountingFiles()
        val repository = LayoutProfileRepository(files)
        val original = LayoutProfile(id = "recover", name = "Recover", buttons = listOf(
            LayoutButton("keep-id", "Keep", "xdotool key alt+F4", 3, "#aBcDeF")))
        repository.createProfile(original)
        val session = ActionEditorSessionViewModel(original.id, "keep-id", repository, Executor { it.run() })
        files.failProfileWrites = true
        session.save(ActionEditorSubmission("Changed", ActionCommandType.KEYBOARD_SHORTCUT, "alt+F4", 10, "#E0EFE8"))
        assertTrue(session.state.value.retrySave)
        assertEquals(original, repository.loadProfile(original.id))
        val recoverable = repository.loadDraft(original.id)!!
        assertEquals("xdotool key alt+F4", recoverable.buttons.single().command)
        val blocked = ActionEditorSessionViewModel(original.id, "keep-id", repository, Executor { it.run() })
        assertTrue(blocked.state.value.needsAdvancedEditor)
        assertEquals(recoverable, repository.loadDraft(original.id))
        files.failProfileWrites = false
        session.save(ActionEditorSubmission("Ignored retry", ActionCommandType.KEYBOARD_SHORTCUT, "invalid space", 5, null))
        assertTrue(session.state.value.saved)
        assertEquals(recoverable, repository.loadProfile(original.id))
        assertNull(repository.loadDraft(original.id))
    }

    @Test fun newRegularDefaultsAndExplicitDeleteUseExistingEditorAuthorityOffTheCallerThread() {
        val files = CountingFiles()
        val repository = LayoutProfileRepository(files)
        val profile = LayoutProfile(id = "worker", name = "Worker")
        repository.createProfile(profile)
        val queued = ArrayDeque<Runnable>()
        val session = ActionEditorSessionViewModel(profile.id, null, repository, Executor(queued::add))
        assertTrue(session.state.value.isLoading)
        queued.remove().run()
        assertEquals("5", session.state.value.editorState!!.buttonEditInput!!.width)
        session.save(ActionEditorSubmission("Regular", ActionCommandType.KEYBOARD_SHORTCUT, "Alt+F4", null, null))
        assertTrue(repository.loadProfile(profile.id).buttons.isEmpty())
        queued.remove().run()
        val saved = repository.loadProfile(profile.id).buttons.single()
        assertEquals(5, saved.width)
        assertEquals(DEFAULT_LAYOUT_BUTTON_COLOR, saved.color)
        val deletion = ActionEditorSessionViewModel(profile.id, saved.id, repository, Executor { it.run() })
        assertEquals(listOf(saved), repository.loadProfile(profile.id).buttons)
        deletion.delete()
        assertTrue(deletion.state.value.saved)
        assertTrue(repository.loadProfile(profile.id).buttons.isEmpty())
    }

    @Test fun retryingAnInvalidFormWithCustomPreservationRestoresOriginalValuesNotRejectedPresetText() {
        val repository = LayoutProfileRepository(CountingFiles())
        val original = LayoutProfile(id = "invalid-retry", name = "Retry", buttons = listOf(
            LayoutButton("stable", "Keep", "xdotool key alt+F4", 7, "#80aAbBcC")))
        repository.createProfile(original)
        val session = ActionEditorSessionViewModel(original.id, "stable", repository, Executor { it.run() })
        session.save(ActionEditorSubmission("Changed", ActionCommandType.KEYBOARD_SHORTCUT, "alt+F4", 10, "invalid-rgb"))
        assertFalse(session.state.value.saved)
        assertEquals(original, repository.loadProfile(original.id))
        session.save(ActionEditorSubmission("Changed", ActionCommandType.KEYBOARD_SHORTCUT, "alt+F4", null, null))
        assertTrue(session.state.value.saved)
        assertEquals(original.copy(buttons = original.buttons.map { it.copy(title = "Changed") }), repository.loadProfile(original.id))
    }

    private class CountingFiles : LayoutProfileFileOps {
        private val files = mutableMapOf<String, ByteArray>()
        var mutations: Int = 0
            private set
        var failProfileWrites = false

        override fun listFileNames(): List<String> = files.keys.toList()
        override fun readAtomically(fileName: String): ByteArray? = files[fileName]?.copyOf()
        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            if (failProfileWrites && fileName.endsWith(".profile.json")) throw java.io.IOException("synthetic failure")
            mutations++
            files[fileName] = bytes.copyOf()
        }
        override fun deleteAtomically(fileName: String) {
            mutations++
            files.remove(fileName)
        }
    }
}
