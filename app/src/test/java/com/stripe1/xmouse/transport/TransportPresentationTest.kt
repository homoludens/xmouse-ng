package com.stripe1.xmouse.transport

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class TransportPresentationTest {
    private class Listener : TransportPresentation.Listener {
        val events = mutableListOf<String>()
        override fun connected(initialCommand: String?) { events.add("connected:$initialCommand") }
        override fun recentCommand(command: String) { events.add("command:$command") }
        override fun execCompleted(command: String, output: String) { events.add("exec:$command:$output") }
        override fun execFailed(command: String, failure: SshTransport.Failure) { events.add("exec-failed:$command:$failure") }
        override fun scpCompleted() { events.add("scp") }
        override fun disconnected() { events.add("disconnected") }
        override fun failed(operation: SshTransport.Operation, failure: SshTransport.Failure) { events.add("$operation:$failure") }
    }
    private class Queue : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() { while (!pending.isEmpty()) pending.remove().run() }
    }
    @Test fun callbacksOnlyPresentThroughInjectedDispatcher() {
        val queue = Queue(); val presentation = TransportPresentation(queue); val listener = Listener()
        presentation.attach(listener)
        presentation.connection("init").success(null)
        presentation.exec("typed").success("requested output")
        presentation.completion(SshTransport.Operation.SCP).success(null)
        presentation.completion(SshTransport.Operation.WRITE).failure(SshTransport.Failure.IO)
        presentation.recentCommand("synthetic command")
        assertTrue(listener.events.isEmpty())
        queue.drain()
        assertEquals(listOf("connected:init", "exec:typed:requested output", "scp", "WRITE:IO", "command:synthetic command"), listener.events)
    }
    @Test fun detachDropsAlreadyQueuedAndLateResultsEvenAfterAttachingAnotherScreen() {
        val queue = Queue(); val presentation = TransportPresentation(queue); val old = Listener(); val replacement = Listener()
        presentation.attach(old)
        val late = presentation.exec("synthetic command")
        presentation.connection("init").success(null)
        presentation.recentCommand("typed before detach")
        presentation.detach(); presentation.attach(replacement)
        late.success("synthetic requested output")
        late.failure(SshTransport.Failure.IO)
        queue.drain()
        assertTrue(old.events.isEmpty())
        assertTrue(replacement.events.isEmpty())
        presentation.completion(SshTransport.Operation.DISCONNECT).success(null); queue.drain()
        assertEquals(listOf("disconnected"), replacement.events)
    }
}
