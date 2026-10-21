package com.stripe1.xmouse.session

import com.stripe1.xmouse.secrets.CredentialKind
import com.stripe1.xmouse.secrets.CredentialReentryRequest
import com.stripe1.xmouse.trust.HostTrustRequests
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.function.BooleanSupplier

/** One bounded, attempt-owned prompt. UI attaches weakly; detaching never shuts down a transport. */
class SessionInteractions(private val dispatcher: Executor) {
    sealed class Prompt {
        class Credential(val kind: CredentialKind, val request: CredentialReentryRequest) : Prompt()
        class Trust(val request: HostTrustRequests.Request) : Prompt()
        internal fun cancel() { when (this) {
            is Credential -> request.cancel()
            is Trust -> request.cancel()
        } }
    }
    interface Listener { fun show(prompt: Prompt); fun dismiss(prompt: Prompt) }
    private var listener = WeakReference<Listener>(null)
    private var generation = 0L
    private var owner: Any? = null
    private var pending: Prompt? = null

    fun attach(observer: Listener) { detach(); synchronized(this) { listener = WeakReference(observer) } }
    fun detach() {
        val old = synchronized(this) {
            generation++
            listener.clear()
            pending.also { pending = null; owner = null }
        }
        old?.cancel()
    }
    internal fun show(attempt: Any, prompt: Prompt, current: BooleanSupplier) {
        if (!current.asBoolean) { prompt.cancel(); return }
        val old: Prompt?
        val token: Long
        val accepted: Boolean
        synchronized(this) {
            accepted = current.asBoolean
            old = if (accepted) pending else null
            token = generation
            if (accepted) { owner = attempt; pending = prompt }
        }
        if (!accepted) { prompt.cancel(); return }
        old?.cancel()
        dispatcher.execute {
            val observer = synchronized(this) {
                if (generation == token && pending === prompt && current.asBoolean) listener.get() else null
            }
            observer?.show(prompt) ?: prompt.cancel()
        }
    }
    internal fun clear(attempt: Any, expected: Prompt? = null) {
        val old: Prompt
        val token: Long
        synchronized(this) {
            if (owner !== attempt) return
            old = pending ?: return
            if (expected != null && old !== expected) return
            owner = null; pending = null; token = generation
        }
        old.cancel()
        dispatcher.execute {
            val observer = synchronized(this) { if (generation == token) listener.get() else null }
            observer?.dismiss(old)
        }
    }
    internal fun dismissTrust(attempt: Any, request: HostTrustRequests.Request) {
        val prompt = synchronized(this) { pending?.takeIf { owner === attempt && (it as? Prompt.Trust)?.request === request } }
        if (prompt != null) clear(attempt, prompt)
    }
}
