package com.stripe1.xmouse.session

import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.secrets.*
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BooleanSupplier

/** Synthetic-only fixture. No Android object or user host/credential access. */
object SessionFixtures {
    fun host(name: String = "synthetic.invalid", id: Long = 1, reference: String = "synthetic_password_1", port: Int = 2222): SessionHost {
        val credentialHost = CredentialHost(id, "Synthetic", name, "NOT_REAL_user", port.toString(), reference, false)
        return SessionHost(Endpoint.manual(name, port), AuthenticationSelection(credentialHost, false,
            null, false, false, null, false, null))
    }
    fun material(host: SessionHost) = AuthenticationMaterial(host.authentication.host, host.authentication.useKey,
        "NOT_REAL_password".toByteArray(), byteArrayOf(), byteArrayOf())

    class Queue : SessionEventQueue {
        private val work = ArrayDeque<Runnable>()
        var closed = false
        override fun post(action: Runnable): Boolean = synchronized(work) {
            if (closed) false else { work.add(action); true }
        }
        fun drain() {
            repeat(1000) {
                val next = synchronized(work) { work.poll() } ?: return
                next.run()
            }
            error("Event queue did not settle")
        }
        override fun close() { closed = true }
    }
    class Credentials : SessionCredentialProvider {
        class Read(val selection: AuthenticationSelection, val prompts: CredentialPrompts,
                   val current: BooleanSupplier, val callback: AuthenticationCallback)
        val reads = CopyOnWriteArrayList<Read>()
        override fun read(selection: AuthenticationSelection, prompts: CredentialPrompts, current: BooleanSupplier, callback: AuthenticationCallback) {
            reads.add(Read(selection, prompts, current, callback))
        }
        fun succeed(index: Int, host: SessionHost): AuthenticationMaterial = material(host).also { reads[index].callback.complete(it, null) }
        fun fail(index: Int) = reads[index].callback.complete(null, "NOT_REAL_sensitive_error_not_to_publish")
    }
}
