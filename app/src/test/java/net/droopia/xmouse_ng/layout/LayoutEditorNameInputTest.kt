package net.droopia.xmouse_ng.layout

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LayoutEditorNameInputTest {
    @Test fun consecutivePendingNamesWriteOnlyLatestAndNeverAcknowledgeOptimisticTextAsDurable() {
        val f = Fixture()
        val savedBytes = f.files.readAtomically(f.profileFile)
        f.worker.updateProfileNameInput("N")
        f.worker.updateProfileNameInput("Na")
        f.worker.updateProfileNameInput("Name 雪")

        assertEquals(1, f.queue.size)
        assertEquals("Name 雪", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        assertTrue(f.state.isDirty)
        assertEquals(f.saved, f.state.draftProfile)
        assertNull(f.repository.loadDraft(f.saved.id))
        assertTrue(f.files.draftWrites.isEmpty())

        f.queue.runNext()
        val expected = f.saved.copy(name = "Name 雪")
        assertEquals(listOf(expected), f.files.draftWrites)
        assertEquals(expected, f.state.draftProfile)
        assertFalse(f.state.isProfileNamePending)
        assertEquals(expected, f.recreate().state.value.draftProfile)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
        assertArrayEquals(savedBytes, f.files.readAtomically(f.profileFile))
    }

    @Test fun initialLoadCannotReplaceTextEnteredBeforeItPublishes() {
        val f = Fixture(load = false)
        f.worker.updateProfileNameInput("First")
        f.worker.updateProfileNameInput("Latest before load")
        assertEquals(2, f.queue.size) // Load plus one coalesced name job.
        f.queue.runNext()
        assertFalse(f.state.isLoading)
        assertEquals(f.saved, f.state.draftProfile)
        assertEquals("Latest before load", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        f.queue.runNext()
        assertEquals(listOf("Latest before load"), f.files.draftWrites.map { it.name })
        assertFalse(f.state.isProfileNamePending)
    }

    @Test fun startedWriteFinishesAndLaterPendingNamesCoalesceWithoutOlderPublicationRegressingText() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Started")
        f.blockNextFileOperation(write = true) {
            f.worker.updateProfileNameInput("Later")
            f.worker.updateProfileNameInput("Latest")
            assertEquals(1, f.queue.size)
            assertEquals("Latest", f.state.profileNameInput)
            assertEquals(f.saved, f.state.draftProfile)
            assertTrue(f.state.isProfileNamePending)
            assertNull(f.files.readAtomically(f.draftFile))
        }

        assertEquals("Started", f.repository.loadDraft(f.saved.id)!!.name)
        assertEquals("Started", f.state.draftProfile!!.name)
        assertEquals("Latest", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        f.queue.runNext()
        assertEquals(listOf("Started", "Latest"), f.files.draftWrites.map { it.name })
        assertEquals("Latest", f.state.draftProfile!!.name)
        assertFalse(f.state.isProfileNamePending)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun equalTextStillUsesRevisionsRatherThanAcknowledgingANewerInputFromAnOlderWrite() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Same text")
        f.blockNextFileOperation(write = true) {
            f.worker.updateProfileNameInput("Intermediate")
            f.worker.updateProfileNameInput("Same text")
        }
        assertEquals("Same text", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        f.queue.runNext()
        assertFalse(f.state.isProfileNamePending)
        assertEquals(listOf("Same text"), f.files.draftWrites.map { it.name })
    }

    @Test fun barrierDuringInFlightWriteSealsTheOlderPendingJobWithoutLosingTheNewerOne() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Started")
        f.blockNextFileOperation(write = true) {
            f.worker.updateProfileNameInput("Before barrier")
            f.worker.edit { it.selectButton("one") }
            f.worker.updateProfileNameInput("Superseded after barrier")
            f.worker.updateProfileNameInput("After barrier")
            assertEquals(3, f.queue.size)
        }
        assertEquals("After barrier", f.state.profileNameInput)
        f.queue.runNext()
        assertEquals("Before barrier", f.state.draftProfile!!.name)
        assertEquals("After barrier", f.state.profileNameInput)
        f.queue.runNext()
        assertEquals("one", f.state.selectedButtonId)
        assertTrue(f.state.isProfileNamePending)
        f.queue.runNext()
        assertEquals(listOf("Started", "Before barrier", "After barrier"), f.files.draftWrites.map { it.name })
        assertFalse(f.state.isProfileNamePending)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun returningToSavedTextWhileAWriteIsInFlightIsNotReportedAsSavedPrematurely() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Started")
        f.blockNextFileOperation(write = true) {
            f.worker.updateProfileNameInput(f.saved.name)
            assertEquals(f.saved.name, f.state.profileNameInput)
            assertEquals(f.saved, f.state.draftProfile)
            assertTrue(f.state.isProfileNamePending)
            assertTrue(f.state.isDirty)
        }
        assertTrue(f.state.isDirty)
        f.queue.runNext()
        assertFalse(f.state.isDirty)
        assertFalse(f.state.isProfileNamePending)
        assertNull(f.repository.loadDraft(f.saved.id))
    }

    @Test fun buttonEditUndoAndRedoEachSealNameBatchesAndUseThePrecedingName() {
        val f = Fixture()
        val resized = f.saved.buttons.map { it.copy(width = 4) }
        f.worker.updateProfileNameInput("Before width")
        f.worker.edit { it.updateButtonWidth("one", 4) }
        f.worker.updateProfileNameInput("Before undo")
        f.worker.edit { it.undo() }
        f.worker.updateProfileNameInput("Before redo")
        f.worker.edit { it.redo() }
        f.worker.updateProfileNameInput("After redo")
        f.queue.drain()

        assertEquals(listOf(
            f.saved.copy(name = "Before width"),
            f.saved.copy(name = "Before width", buttons = resized),
            f.saved.copy(name = "Before undo", buttons = resized),
            f.saved.copy(name = "Before undo"),
            f.saved.copy(name = "Before redo"),
            f.saved.copy(name = "Before redo", buttons = resized),
            f.saved.copy(name = "After redo", buttons = resized),
        ), f.files.draftWrites)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun evenNoOpSelectionAndButtonCancelAreOrderingBarriers() {
        val barriers: List<(LayoutEditorViewModel) -> Unit> = listOf(
            { },
            { it.selectButton(null) },
            { it.cancelButtonEdit() },
            { it.undo() },
            { it.redo() },
        )
        barriers.forEach { barrier ->
            val f = Fixture()
            f.worker.updateProfileNameInput("Before barrier")
            f.worker.edit(barrier)
            f.worker.updateProfileNameInput("After barrier")
            assertEquals(3, f.queue.size)
            f.queue.runNext()
            assertEquals("Before barrier", f.state.draftProfile!!.name)
            assertEquals("After barrier", f.state.profileNameInput)
            f.queue.runNext()
            assertEquals("After barrier", f.state.profileNameInput)
            assertTrue(f.state.isProfileNamePending)
            f.queue.runNext()
            assertEquals(listOf("Before barrier", "After barrier"), f.files.draftWrites.map { it.name })
        }
    }

    @Test fun backAndCancelKeepAllOrderingBoundariesWithoutClosingOrLosingDrafts() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Before Back")
        f.worker.back()
        f.worker.updateProfileNameInput("Before Cancel")
        f.worker.backChoice(LayoutEditorBackChoice.CANCEL)
        f.worker.updateProfileNameInput("After Cancel")
        f.queue.runNext()
        f.queue.runNext()
        assertTrue(f.state.isShowingBackConfirmation)
        f.queue.runNext()
        f.queue.runNext()
        assertFalse(f.state.isShowingBackConfirmation)
        assertEquals("After Cancel", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        f.queue.drain()
        assertEquals(listOf("Before Back", "Before Cancel", "After Cancel"), f.files.draftWrites.map { it.name })
        assertFalse(f.worker.closed.value)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun discardIsABarrierAndItsOlderPublicationCannotEraseLaterText() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Before discard")
        f.worker.discardDraft()
        f.worker.updateProfileNameInput("After discard")
        f.queue.runNext()
        f.blockNextFileOperation(write = false) {
            assertEquals("Before discard", f.state.draftProfile!!.name)
            assertEquals("After discard", f.state.profileNameInput)
        }
        assertEquals(f.saved, f.state.draftProfile)
        assertEquals("After discard", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        assertNull(f.repository.loadDraft(f.saved.id))
        f.queue.runNext()
        assertEquals(listOf("draft:Before discard", "delete", "draft:After discard"), f.files.events)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun saveAndBackSaveCommitOnlyNamesBeforeTheBarrierAndRejectAllInputAfterClosure() {
        val terminalActions: List<(LayoutEditorWorkerViewModel) -> Unit> = listOf(
            { it.saveAndClose() },
            { it.backChoice(LayoutEditorBackChoice.SAVE) },
        )
        terminalActions.forEach { close ->
            val f = Fixture()
            f.worker.updateProfileNameInput("Superseded")
            f.worker.updateProfileNameInput("Name to save")
            close(f.worker)
            f.worker.updateProfileNameInput("Must not be saved")
            f.queue.runNext()
            assertEquals("Name to save", f.state.draftProfile!!.name)
            assertEquals("Must not be saved", f.state.profileNameInput)
            assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
            assertFalse(f.worker.closed.value)
            f.queue.runNext()
            assertTrue(f.worker.closed.value)
            assertEquals("Name to save", f.state.profileNameInput)
            assertFalse(f.state.isProfileNamePending)
            f.queue.drain()
            f.worker.updateProfileNameInput("Also ignored")
            f.worker.discardDraft()
            assertEquals(0, f.queue.size)
            assertEquals(f.saved.copy(name = "Name to save"), f.repository.loadProfile(f.saved.id))
            assertNull(f.repository.loadDraft(f.saved.id))
            assertEquals(listOf("Name to save", "Name to save"), f.files.draftWrites.map { it.name })
        }
    }

    @Test fun discardAndBackDiscardFinishPrecedingNamesThenCloseWithoutWritingLaterNames() {
        val terminalActions: List<(LayoutEditorWorkerViewModel) -> Unit> = listOf(
            { it.discardAndClose() },
            { it.backChoice(LayoutEditorBackChoice.DISCARD) },
        )
        terminalActions.forEach { close ->
            val f = Fixture()
            f.worker.updateProfileNameInput("Before close")
            close(f.worker)
            f.worker.updateProfileNameInput("After close")
            f.queue.drain()
            assertTrue(f.worker.closed.value)
            assertEquals(f.saved.name, f.state.profileNameInput)
            assertFalse(f.state.isProfileNamePending)
            assertEquals(listOf("draft:Before close", "delete"), f.files.events)
            assertNull(f.repository.loadDraft(f.saved.id))
            assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
        }
    }

    @Test fun cleanBackIsTerminalAndCannotBeReopenedByAlreadyQueuedNameInput() {
        val f = Fixture()
        f.worker.back()
        f.worker.updateProfileNameInput("Queued after Back")
        f.queue.drain()
        assertTrue(f.worker.closed.value)
        assertEquals(f.saved.name, f.state.profileNameInput)
        assertFalse(f.state.isProfileNamePending)
        assertTrue(f.files.draftWrites.isEmpty())
        assertNull(f.repository.loadDraft(f.saved.id))
    }

    @Test fun xmlEntryUsesPrecedingNameAndApplyDoesNotClearTextQueuedAfterIt() {
        val f = Fixture()
        val xmlProfile = f.saved.copy(name = "XML name", buttons = f.saved.buttons.map { it.copy(width = 5) })
        f.worker.updateProfileNameInput("Visual name")
        f.worker.enterXmlMode()
        f.worker.updateXmlSource(LayoutProfileXml.serialize(xmlProfile))
        f.worker.validateXmlSource()
        f.worker.formatXmlSource()
        f.worker.applyXmlSource()
        f.worker.updateProfileNameInput("After Apply")
        f.queue.runNext()
        f.queue.runNext()
        assertEquals("Visual name", LayoutProfileXml.parse(f.state.xmlSource, f.saved.id).name)
        assertEquals("After Apply", f.state.profileNameInput)
        repeat(4) { f.queue.runNext() }
        assertEquals(LayoutEditorMode.VISUAL, f.state.editMode)
        assertEquals(xmlProfile, f.state.draftProfile)
        assertEquals("After Apply", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        f.queue.runNext()
        assertEquals(listOf(f.saved.copy(name = "Visual name"), xmlProfile, xmlProfile.copy(name = "After Apply")), f.files.draftWrites)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun xmlTransitionsIncludingCancelResetAndFailedSaveRemainOrderedBarriers() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Before XML")
        f.worker.enterXmlMode()
        f.worker.updateProfileNameInput("After entry")
        f.worker.updateXmlSource("not valid XML")
        f.worker.requestXmlExit(XmlEditorExitAction.BACK_TO_VISUAL)
        f.worker.resolveXmlExit(discardXmlChanges = false)
        f.queue.drain()
        assertEquals("not valid XML", f.state.xmlSource)
        assertNull(f.state.xmlExitConfirmation)
        assertEquals(listOf("Before XML", "After entry"), f.files.draftWrites.map { it.name })

        f.worker.updateProfileNameInput("Before failed Save")
        f.worker.saveAndClose()
        f.worker.updateProfileNameInput("After failed Save")
        f.worker.requestXmlExit(XmlEditorExitAction.RESET_SOURCE)
        f.worker.resolveXmlExit(discardXmlChanges = true)
        f.queue.drain()
        assertFalse(f.worker.closed.value)
        assertEquals("After failed Save", LayoutProfileXml.parse(f.state.xmlSource, f.saved.id).name)
        f.worker.requestXmlExit(XmlEditorExitAction.BACK_TO_VISUAL)
        f.worker.updateProfileNameInput("After XML exit")
        f.queue.drain()
        assertEquals(LayoutEditorMode.VISUAL, f.state.editMode)
        assertEquals(listOf("Before XML", "After entry", "Before failed Save", "After failed Save", "After XML exit"), f.files.draftWrites.map { it.name })
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun blankInputSupersedesPendingValidTextButIsRetainedAndCannotBeSavedOrOpenXml() {
        listOf("", " \t ").forEach { blank ->
            val f = Fixture()
            f.worker.updateProfileNameInput("Never started")
            f.worker.updateProfileNameInput(blank)
            assertEquals(blank, f.state.profileNameInput)
            f.queue.drain()
            assertEquals(blank, f.state.profileNameInput)
            assertFalse(f.state.isProfileNamePending)
            assertTrue(f.state.errorMessage!!.contains("blank"))
            assertEquals(f.saved, f.state.draftProfile)
            f.worker.saveAndClose()
            f.worker.enterXmlMode()
            f.worker.back()
            f.worker.backChoice(LayoutEditorBackChoice.CANCEL)
            f.queue.drain()
            assertFalse(f.worker.closed.value)
            assertEquals(blank, f.state.profileNameInput)
            assertEquals(LayoutEditorMode.VISUAL, f.state.editMode)
            assertTrue(f.files.draftWrites.isEmpty())
            assertNull(f.repository.loadDraft(f.saved.id))
            f.worker.updateProfileNameInput("Corrected")
            f.queue.drain()
            assertEquals("Corrected", f.state.draftProfile!!.name)
            assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
        }
    }

    @Test fun failedWriteKeepsLastGoodBytesAndInputAndSameInputCanBeRetried() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Last good")
        f.queue.drain()
        val lastGoodBytes = f.files.readAtomically(f.draftFile)
        f.files.failNextDraftWrite = true
        f.worker.updateProfileNameInput("Retry this exact input")
        f.queue.drain()

        assertEquals("Retry this exact input", f.state.profileNameInput)
        assertFalse(f.state.isProfileNamePending)
        assertEquals("Last good", f.state.draftProfile!!.name)
        assertArrayEquals(lastGoodBytes, f.files.readAtomically(f.draftFile))
        assertTrue(f.state.errorMessage!!.contains("Retry"))
        val restored = f.recreate().state.value
        assertEquals("Last good", restored.profileNameInput)
        assertEquals(f.saved.copy(name = "Last good"), restored.draftProfile)
        f.worker.updateProfileNameInput("Retry this exact input")
        f.queue.drain()
        assertEquals("Retry this exact input", f.state.draftProfile!!.name)
        assertNull(f.state.errorMessage)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun unencodableInputIsRetainedVerbatimWithoutReplacingTheLastGoodDraft() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Last good")
        f.queue.drain()
        val lastGoodBytes = f.files.readAtomically(f.draftFile)
        val invalid = "Unpaired surrogate \uD800"
        f.worker.updateProfileNameInput(invalid)
        f.queue.drain()
        assertEquals(invalid, f.state.profileNameInput)
        assertEquals("Last good", f.state.draftProfile!!.name)
        assertFalse(f.state.isProfileNamePending)
        assertNotNull(f.state.errorMessage)
        assertArrayEquals(lastGoodBytes, f.files.readAtomically(f.draftFile))
        f.worker.saveAndClose()
        f.queue.drain()
        assertFalse(f.worker.closed.value)
        assertEquals(invalid, f.state.profileNameInput)
        assertEquals("Last good", f.recreate().state.value.profileNameInput)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun inFlightFailureDoesNotClearNewerTextAndSaveRetriesFailedInputDurably() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Failed started write")
        f.blockNextFileOperation(write = true) {
            f.worker.updateProfileNameInput("Latest retryable text")
            f.files.failNextDraftWrite = true
        }
        assertEquals(f.saved, f.state.draftProfile)
        assertEquals("Latest retryable text", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        assertNull(f.repository.loadDraft(f.saved.id))

        f.files.failNextDraftWrite = true
        f.queue.runNext()
        assertEquals("Latest retryable text", f.state.profileNameInput)
        assertFalse(f.state.isProfileNamePending)
        assertEquals(f.saved, f.state.draftProfile)
        f.files.failNextDraftWrite = true
        f.worker.saveAndClose()
        f.queue.drain()
        assertFalse(f.worker.closed.value)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
        assertEquals("Latest retryable text", f.state.profileNameInput)
        f.worker.saveAndClose()
        f.queue.drain()
        assertTrue(f.worker.closed.value)
        assertEquals(f.saved.copy(name = "Latest retryable text"), f.repository.loadProfile(f.saved.id))
    }

    @Test fun failedSavedProfileReplacementLeavesDurableRecoveryAndDoesNotClearLaterText() {
        val f = Fixture()
        f.files.failNextProfileWrite = true
        f.worker.updateProfileNameInput("Before Save")
        f.worker.saveAndClose()
        f.worker.updateProfileNameInput("After failed Save")
        f.queue.runNext()
        f.queue.runNext()
        assertFalse(f.worker.closed.value)
        assertEquals("Before Save", f.state.draftProfile!!.name)
        assertEquals("After failed Save", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        assertEquals("Before Save", f.recreate().state.value.profileNameInput)
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
        f.queue.runNext()
        f.worker.saveAndClose()
        f.queue.drain()
        assertTrue(f.worker.closed.value)
        assertEquals(f.saved.copy(name = "After failed Save"), f.repository.loadProfile(f.saved.id))
    }

    @Test fun failedDiscardDoesNotCloseOrEraseDraftOrNewerTextAndAllowsRetry() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Before discard")
        f.files.failNextDraftDelete = true
        f.worker.discardAndClose()
        f.worker.updateProfileNameInput("After failed discard")
        f.queue.runNext()
        f.queue.runNext()
        assertFalse(f.worker.closed.value)
        assertEquals("Before discard", f.repository.loadDraft(f.saved.id)!!.name)
        assertEquals("After failed discard", f.state.profileNameInput)
        assertTrue(f.state.isProfileNamePending)
        f.queue.runNext()
        f.worker.discardAndClose()
        f.queue.drain()
        assertTrue(f.worker.closed.value)
        assertNull(f.repository.loadDraft(f.saved.id))
        assertEquals(f.saved, f.repository.loadProfile(f.saved.id))
    }

    @Test fun recreationRecoversOnlyAcknowledgedDraftNotQueuedOrInvalidFormText() {
        val f = Fixture()
        f.worker.updateProfileNameInput("Acknowledged")
        f.queue.drain()
        f.worker.updateProfileNameInput("Not started")
        assertEquals("Not started", f.state.profileNameInput)
        assertEquals("Acknowledged", f.recreate().state.value.profileNameInput)
        f.worker.updateProfileNameInput("  ")
        f.queue.drain()
        assertEquals("  ", f.state.profileNameInput)
        val restored = f.recreate().state.value
        assertEquals("Acknowledged", restored.profileNameInput)
        assertFalse(restored.isProfileNamePending)
        assertEquals(f.saved, restored.savedProfile)
        assertEquals(listOf("Acknowledged"), f.files.draftWrites.map { it.name })
    }

    private class Fixture(load: Boolean = true) {
        val saved = LayoutProfile("name-worker", "Saved", buttons = listOf(
            LayoutButton("one", "One", "  printf '雪'\n  ", 2, "#aBcDeF"),
        ))
        val files = MemoryFiles()
        val repository = LayoutProfileRepository(files)
        val queue = QueuedExecutor()
        val worker: LayoutEditorWorkerViewModel
        val state get() = worker.state.value
        val profileFile get() = "${saved.id}.profile.json"
        val draftFile get() = "${saved.id}.draft.json"

        init {
            repository.createProfile(saved)
            files.events.clear()
            worker = LayoutEditorWorkerViewModel(saved.id, repository, queue)
            if (load) queue.runNext()
        }

        fun recreate(): LayoutEditorWorkerViewModel {
            val executor = QueuedExecutor()
            return LayoutEditorWorkerViewModel(saved.id, LayoutProfileRepository(files), executor).also { executor.runNext() }
        }

        /** Exercise real overlap without sleeps; file bytes remain unchanged until released. */
        fun blockNextFileOperation(write: Boolean, whileBlocked: () -> Unit) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val hook: (String) -> Unit = { fileName ->
                if (fileName == draftFile) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Timed out releasing blocked file operation" }
                }
            }
            if (write) files.beforeWrite = hook else files.beforeDelete = hook
            val task = queue.take()
            val result = FutureTask<Unit> { task.run() }
            val thread = Thread(result, "test-blocked-editor-file").apply { isDaemon = true; start() }
            try {
                assertTrue("Worker never reached file operation", entered.await(5, TimeUnit.SECONDS))
                whileBlocked()
            } finally {
                release.countDown()
                result.get(5, TimeUnit.SECONDS)
                thread.join(5000)
                files.beforeWrite = null
                files.beforeDelete = null
            }
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        val size get() = tasks.size
        override fun execute(command: Runnable) { tasks.add(command) }
        fun take(): Runnable = checkNotNull(tasks.poll()) { "No worker task queued" }
        fun runNext() = take().run()
        fun drain() { while (tasks.isNotEmpty()) runNext() }
    }

    /** Atomic-file boundary: failures never replace the last successful byte array. */
    private class MemoryFiles : LayoutProfileFileOps {
        private val files = ConcurrentHashMap<String, ByteArray>()
        val draftWrites = CopyOnWriteArrayList<LayoutProfile>()
        val events = CopyOnWriteArrayList<String>()
        @Volatile var failNextDraftWrite = false
        @Volatile var failNextProfileWrite = false
        @Volatile var failNextDraftDelete = false
        @Volatile var beforeWrite: ((String) -> Unit)? = null
        @Volatile var beforeDelete: ((String) -> Unit)? = null

        override fun listFileNames() = files.keys.toList()
        override fun readAtomically(fileName: String) = files[fileName]?.copyOf()
        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            beforeWrite?.invoke(fileName)
            if (fileName.endsWith(".draft.json")) {
                if (failNextDraftWrite) {
                    failNextDraftWrite = false
                    throw IOException("Injected atomic draft write failure")
                }
                val draft = LayoutProfileJson.decode(bytes.toString(Charsets.UTF_8))
                draftWrites += draft
                events += "draft:${draft.name}"
            }
            if (fileName.endsWith(".profile.json") && failNextProfileWrite) {
                failNextProfileWrite = false
                throw IOException("Injected atomic saved-profile write failure")
            }
            files[fileName] = bytes.copyOf()
        }
        override fun deleteAtomically(fileName: String) {
            beforeDelete?.invoke(fileName)
            if (failNextDraftDelete) {
                failNextDraftDelete = false
                throw IOException("Injected draft delete failure")
            }
            events += "delete"
            files.remove(fileName)
        }
    }
}
