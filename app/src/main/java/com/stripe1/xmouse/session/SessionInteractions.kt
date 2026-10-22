package com.stripe1.xmouse.session

import com.stripe1.xmouse.secrets.CredentialKind
import com.stripe1.xmouse.secrets.CredentialReentryRequest
import com.stripe1.xmouse.trust.HostTrustRequests
import com.stripe1.xmouse.trust.HostKeyMismatch
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** One bounded, attempt-owned prompt. UI attaches weakly; detaching never shuts down a transport. */
class SessionInteractions(private val dispatcher: Executor) {
    sealed class Prompt {
        class Credential(val kind: CredentialKind, val request: CredentialReentryRequest) : Prompt()
        class Trust(val request: HostTrustRequests.Request) : Prompt()
        class Mismatch internal constructor(
            val mismatch: HostKeyMismatch,
            private val replaceAction: (HostKeyMismatch, (Boolean) -> Unit) -> Unit,
            private val cancelAction: () -> Unit,
            private val finishedAction: (Mismatch, Boolean) -> Unit,
        ) : Prompt() {
            private val cancelled = AtomicBoolean(false)
            private val replacementStarted = AtomicBoolean(false)

            /** Starts only the separately confirmed trust CAS. It never resumes the failed handshake. */
            fun replace() {
                if (cancelled.get() || !mismatch.isCurrent() || !replacementStarted.compareAndSet(false, true)) return
                try { replaceAction(mismatch) { completed -> finishedAction(this, completed) } }
                catch (_: Exception) { finishedAction(this, false) }
            }

            internal fun finish() {
                if (cancelled.compareAndSet(false, true)) {
                    mismatch.cancel()
                    cancelAction()
                }
            }

            internal override fun cancel() = finish()
        }

        internal open fun cancel() { when (this) {
            is Credential -> request.cancel()
            is Trust -> request.cancel()
            is Mismatch -> finish()
        } }
    }
    interface Listener {
        fun show(prompt: Prompt)
        fun dismiss(prompt: Prompt)
        fun recentCommand(command: String) { }
        fun execCompleted(command: String, output: String) { }
        fun execFailed(command: String) { }
        fun replacementFinished(mismatch: HostKeyMismatch, success: Boolean) { }
    }
    private var listener = WeakReference<Listener>(null)
    private var generation = 0L
    private var reviewEpoch = 0L
    private var owner: Any? = null
    private var pending: Prompt? = null

    fun attach(observer: Listener) { detach(); synchronized(this) { listener = WeakReference(observer) } }
    fun detach() {
        val old = synchronized(this) {
            generation++
            reviewEpoch++
            listener.clear()
            pending.also { pending = null; owner = null }
        }
        old?.cancel()
    }

    /** Invalidates stale UI/consent while retaining the currently attached Activity presenter. */
    fun invalidate() {
        val old: Prompt?
        val token: Long
        synchronized(this) {
            generation++
            reviewEpoch++
            old = pending
            pending = null
            owner = null
            token = generation
        }
        old?.cancel()
        if (old != null) dispatcher.execute {
            val observer = synchronized(this) { if (generation == token) listener.get() else null }
            observer?.dismiss(old)
        }
    }

    internal fun reviewEpoch(): Long = synchronized(this) { reviewEpoch }
    internal fun reviewEpochIsCurrent(epoch: Long): Boolean = synchronized(this) { reviewEpoch == epoch }

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
        if (old != null) dispatcher.execute {
            val observer = synchronized(this) { if (generation == token) listener.get() else null }
            observer?.dismiss(old)
        }
        dispatcher.execute {
            val observer = synchronized(this) {
                if (generation == token && pending === prompt && current.asBoolean) listener.get() else null
            }
            observer?.show(prompt) ?: prompt.cancel()
        }
    }

    /** Mismatch review is published only after its failed transport has confirmed cleanup. */
    internal fun showReview(attempt: Any, prompt: Prompt.Mismatch, expectedEpoch: Long, current: BooleanSupplier): Boolean {
        val token: Long
        val accepted: Boolean
        synchronized(this) {
            token = generation
            accepted = reviewEpoch == expectedEpoch && current.asBoolean && pending == null && listener.get() != null
            if (accepted) { owner = attempt; pending = prompt }
        }
        if (!accepted) { prompt.cancel(); return false }
        dispatcher.execute {
            val observer = synchronized(this) {
                if (generation == token && reviewEpoch == expectedEpoch && pending === prompt && current.asBoolean)
                    listener.get() else null
            }
            observer?.show(prompt) ?: prompt.cancel()
        }
        return true
    }

    private fun onReplacementFinished(prompt: Prompt.Mismatch, success: Boolean) {
        val token: Long
        synchronized(this) {
            if (pending !== prompt) { prompt.cancel(); return }
            if (owner == null) { prompt.cancel(); return }
            token = generation
            pending = null
            owner = null
            reviewEpoch++
        }
        prompt.finish()
        dispatcher.execute {
            val observer = synchronized(this) { if (generation == token) listener.get() else null }
            if (observer != null) {
                observer.dismiss(prompt)
                observer.replacementFinished(prompt.mismatch, success)
            }
        }
    }

    fun recentCommand(command: String) = notifyAttached { it.recentCommand(command) }
    fun execCompleted(command: String, output: String) = notifyAttached { it.execCompleted(command, output) }
    fun execFailed(command: String) = notifyAttached { it.execFailed(command) }

    private fun notifyAttached(action: (Listener) -> Unit) {
        val token = synchronized(this) { generation }
        dispatcher.execute {
            val observer = synchronized(this) { if (generation == token) listener.get() else null }
            observer?.let(action)
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

    internal fun finishMismatch(prompt: Prompt.Mismatch, success: Boolean) = onReplacementFinished(prompt, success)
}
