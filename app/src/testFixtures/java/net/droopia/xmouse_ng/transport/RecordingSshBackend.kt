package net.droopia.xmouse_ng.transport

import net.droopia.xmouse_ng.secrets.AuthenticationMaterial
import net.droopia.xmouse_ng.secrets.CredentialHost
import net.droopia.xmouse_ng.connection.Endpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic resources only: shared by real production-transport JVM and Android main-thread tests. */
class RecordingSshBackend : SshBackend {
    data class Event(val name: String, val thread: Thread)
    val events = CopyOnWriteArrayList<Event>()
    val channels = CopyOnWriteArrayList<RecordingChannel>()
    val writes = CopyOnWriteArrayList<String>()
    /** Cross-channel order observed at the provider boundary for real controller/Activity routing tests. */
    val commandOrder = CopyOnWriteArrayList<String>()
    var failAt: String? = null
    @Volatile var blockAt: String? = null
        set(value) {
            field = value
            if (value != null) release = CountDownLatch(1)
        }
    var ignoreInterrupts = false
    @Volatile var blockAllCloses = false
        set(value) {
            field = value
            if (value) release = CountDownLatch(1)
        }
    val entered = CountDownLatch(1)
    @Volatile var release = CountDownLatch(0)
    var stdout = "out one\nout two\n".toByteArray()
    var stderr = "err one\r\nerr two\n".toByteArray()
    var scp = "C0644 3 test.jpg\nabc\u0000".toByteArray()
    @Volatile var execExitStatus = 0

    fun record(name: String) {
        events.add(Event(name, Thread.currentThread()))
        if (name == blockAt || (blockAllCloses && name.endsWith("close"))) {
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                try { check(release.await(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); break }
                catch (interrupted: InterruptedException) { if (!ignoreInterrupts) throw interrupted }
            }
        }
        if (name == failAt) throw IOException("NOT_REAL_provider_password_key_typed_content")
    }

    override fun create(request: SshConnectionRequest, authentication: AuthenticationMaterial): SshBackend.Session {
        record("create")
        return object : SshBackend.Session {
            @Volatile var connected = false
            @Volatile var aborted = false
            private var openedChannel = false
            override fun connect() { record("session.connect"); if (aborted) throw IOException(); connected = true }
            override fun abort() { aborted = true; connected = false }
            override fun clearAuthentication() = record("session.clearAuthentication")
            override fun openChannel(type: String): SshBackend.Channel {
                record("session.open")
                val channelName = if (openedChannel) "exec" else "shell"
                openedChannel = true
                return RecordingChannel(channelName, type).also(channels::add)
            }
            override fun isConnected(): Boolean { record("session.isConnected"); return connected }
            override fun close() { aborted = true; connected = false; record("session.close") }
        }
    }

    inner class RecordingChannel(val name: String, val type: String) : SshBackend.Channel {
        var command: String? = null
        @Volatile var closed = false
        var timeout = -1
        private var out: OutputStream? = null
        private var err: OutputStream? = null
        val inputBytes = ByteArrayOutputStream()
        override fun command(command: String) {
            record("$name.command")
            this.command = command
            if (name == "exec") commandOrder.add("exec:$command")
        }
        override fun output(stdout: OutputStream?, stderr: OutputStream?) { record("$name.output"); out = stdout; err = stderr }
        override fun input(): OutputStream {
            record("$name.input")
            return object : OutputStream() {
                override fun write(value: Int) { record("$name.write"); inputBytes.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    record("$name.write")
                    inputBytes.write(bytes, offset, length)
                    val text = String(bytes, offset, length, Charsets.UTF_8)
                    writes.add(text)
                    if (name == "shell") commandOrder.add("shell:${text.removeSuffix("\r\n")}")
                }
                override fun flush() = record("$name.flush")
                override fun close() = record("$name.input.close")
            }
        }
        override fun output(): InputStream {
            record("$name.outputStream")
            return object : ByteArrayInputStream(scp) {
                override fun read(): Int { record("$name.read"); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    record("$name.read"); return super.read(bytes, offset, length)
                }
                override fun close() = record("$name.output.close")
            }
        }
        override fun connect(timeoutMillis: Int) {
            timeout = timeoutMillis
            record("$name.connect")
            if (name == "exec" && out != null) {
                err?.write(stderr)
                out?.write(stdout)
                closed = true
            }
        }
        override fun isClosed(): Boolean { record("$name.isClosed"); return closed }
        override fun exitStatus(): Int = if (name == "exec") execExitStatus else 0
        override fun close() { closed = true; record("$name.close") }
    }

    companion object {
        fun request(shell: String = "", initial: String? = null) =
            SshConnectionRequest(Endpoint.manual("synthetic.invalid", 2222), "192.0.2.10", shell, initial)
        fun authentication(useKey: Boolean = false, key: ByteArray = byteArrayOf()) = AuthenticationMaterial(
            CredentialHost(1, "Synthetic", "synthetic.invalid", "NOT_REAL_user", "2222", null, false),
            useKey, "NOT_REAL_password".toByteArray(), "NOT_REAL_phrase".toByteArray(), key,
        )
    }
}
