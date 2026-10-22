package com.stripe1.xmouse.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class SessionInteractionsTest {
    private class Queue : Executor {
        private val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { synchronized(work) { work.addLast(command) } }
        fun drain() {
            repeat(100) {
                val next = synchronized(work) { if (work.isEmpty()) null else work.removeFirst() } ?: return
                next.run()
            }
            error("presentation queue did not settle")
        }
    }

    private class Recorder : SessionInteractions.Listener {
        val recent = mutableListOf<String>()
        val completed = mutableListOf<Pair<String, String>>()
        val failed = mutableListOf<String>()
        var invalidations = 0
        override fun show(prompt: SessionInteractions.Prompt) { }
        override fun dismiss(prompt: SessionInteractions.Prompt) { }
        override fun recentCommand(command: String) { recent.add(command) }
        override fun execCompleted(command: String, output: String) { completed.add(command to output) }
        override fun execFailed(command: String) { failed.add(command) }
        override fun commandResultsInvalidated() { invalidations++ }
    }

    @Test fun queuedAndLateExecResultsAreDroppedWhenTheirAttemptIsInvalidated() {
        val ui = Queue()
        val interactions = SessionInteractions(ui)
        val screen = Recorder()
        val presentation = interactions.attach(screen)
        val firstAttempt = interactions.beginCommandResults()

        interactions.execCompleted(firstAttempt, presentation, "old script", "old output")
        interactions.execFailed(firstAttempt, presentation, "old failure")
        interactions.invalidateCommandResults(firstAttempt)
        val secondAttempt = interactions.beginCommandResults()
        assertNotSame(firstAttempt, secondAttempt)
        ui.drain()

        assertTrue(screen.completed.isEmpty())
        assertTrue(screen.failed.isEmpty())
        assertEquals(1, screen.invalidations)

        // A callback arriving after a new attempt is ready remains scoped to the retired owner.
        interactions.execCompleted(firstAttempt, presentation, "late script", "late output")
        ui.drain()
        assertTrue(screen.completed.isEmpty())
    }

    @Test fun anOldScreenCannotReceiveExecOutputAfterRecreationOrReattachment() {
        val ui = Queue()
        val interactions = SessionInteractions(ui)
        val oldScreen = Recorder()
        val oldPresentation = interactions.attach(oldScreen)
        val attempt = interactions.beginCommandResults()

        // The exec finishes after the old Activity detached and a new presenter attached.
        interactions.detach()
        val newScreen = Recorder()
        val newPresentation = interactions.attach(newScreen)
        assertNotSame(oldPresentation, newPresentation)
        interactions.execCompleted(attempt, oldPresentation, "old script", "old screen output")
        ui.drain()

        assertTrue(oldScreen.completed.isEmpty())
        assertTrue(newScreen.completed.isEmpty())
        assertNull(oldScreen.recent.singleOrNull())
    }

    @Test fun initialCommandPresentationIsBoundToTheScreenPresentAtAttemptStart() {
        val ui = Queue()
        val interactions = SessionInteractions(ui)
        val oldScreen = Recorder()
        val oldPresentation = interactions.attach(oldScreen)
        val attempt = interactions.beginCommandResults()
        interactions.detach()
        val newScreen = Recorder()
        val newPresentation = interactions.attach(newScreen)
        assertNotSame(oldPresentation, newPresentation)

        interactions.recentCommand(attempt, "old startup")
        ui.drain()

        assertTrue(oldScreen.recent.isEmpty())
        assertTrue(newScreen.recent.isEmpty())
    }
}
