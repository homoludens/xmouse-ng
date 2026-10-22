package com.stripe1.xmouse.trust

import com.stripe1.xmouse.connection.Endpoint
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Pure pending-request owner. No Android/UI reference; only the attached weak observer can present. */
class HostTrustRequests @JvmOverloads constructor(
    private val repository: HostKeyRepository,
    private val dispatcher: Executor,
    private val timeoutMillis: Long = 60_000,
) {
    interface Listener { fun show(request: Request); fun dismiss(request: Request) }
    private var listener = WeakReference<Listener>(null)
    private var generation = 0L
    private var pending: Request? = null
    init { require(timeoutMillis in 1..60_000) }

    @Synchronized fun attach(observer: Listener) { detach(); listener = WeakReference(observer) }
    @Synchronized fun detach() { generation++; listener.clear(); cancelPending() }
    @Synchronized fun cancelPending() { pending?.cancel() }

    /** Safe public-key observation captured while the failed transport token is still valid. */
    class MismatchObservation internal constructor(
        internal val owner: HostTrustRequests,
        internal val endpoint: Endpoint,
        internal val trustedKey: ApprovedHostKey,
        internal val presentedKey: ApprovedHostKey,
        internal val capturedNanos: Long,
    )

    fun captureMismatch(mismatch: HostKeyMismatch, current: BooleanSupplier): MismatchObservation? {
        if (!mismatch.belongsTo(this) || !current.asBoolean || !mismatch.isCurrent()) return null
        return MismatchObservation(this, mismatch.endpoint, mismatch.trustedKey, mismatch.presentedKey, System.nanoTime())
    }

    /** Creates a distinct review token only after the caller has confirmed failed-attempt cleanup. */
    fun reviewMismatch(observation: MismatchObservation, current: BooleanSupplier): HostKeyMismatch? {
        if (observation.owner !== this || System.nanoTime() - observation.capturedNanos >= TimeUnit.SECONDS.toNanos(60) ||
            !current.asBoolean) return null
        return HostKeyMismatch(this, observation.endpoint, observation.trustedKey, observation.presentedKey, current)
    }

    fun verify(endpoint: Endpoint, key: ApprovedHostKey, current: BooleanSupplier) {
        if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
        repository.find(endpoint.identity)?.let {
            if (!it.matches(key)) {
                if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
                throw HostTrustException(
                    HostTrustException.Reason.MISMATCH,
                    HostKeyMismatch(this, endpoint, it, key, current),
                )
            }
            if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
            return
        }
        val request: Request
        val token: Long
        synchronized(this) {
            pending?.cancel()
            request = Request(endpoint, key, current, timeoutMillis)
            pending = request
            token = generation
            if (listener.get() == null) request.cancel()
        }
        try {
            dispatcher.execute {
                synchronized(this) {
                    if (generation == token && pending === request && request.isPending) listener.get()?.show(request) ?: request.cancel()
                }
            }
            request.awaitApproval()
            repository.approve(endpoint.identity, key, BooleanSupplier { request.isAccepted && current.asBoolean })
            if (!request.isAccepted || !current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
        } finally {
            request.finish()
            synchronized(this) { if (pending === request) pending = null }
            dispatcher.execute { synchronized(this) { if (token == generation) listener.get()?.dismiss(request) } }
        }
    }

    /** Called on the transport worker only after the user explicitly confirmed a mismatch review. */
    fun replace(mismatch: HostKeyMismatch, current: BooleanSupplier) {
        if (!mismatch.claimReplacement(this)) trustFailure(HostTrustException.Reason.CANCELLED)
        try {
            val publicationCurrent = BooleanSupplier { current.asBoolean && mismatch.publicationCurrent() }
            if (!publicationCurrent.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
            repository.replace(
                mismatch.endpoint.identity,
                mismatch.trustedKey,
                mismatch.presentedKey,
                publicationCurrent,
            )
            if (!publicationCurrent.asBoolean || !mismatch.finishReplacement()) {
                trustFailure(HostTrustException.Reason.CANCELLED)
            }
        } finally {
            mismatch.finishReplacement()
        }
    }

    class Request internal constructor(
        val endpoint: Endpoint,
        val key: ApprovedHostKey,
        private val current: BooleanSupplier,
        timeoutMillis: Long,
    ) {
        private val lock = Object()
        private var decision: HostTrustException.Reason? = null
        private var accepted = false
        private var finished = false
        private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val isPending: Boolean get() = synchronized(lock) { !finished && !accepted && decision == null && current.asBoolean && System.nanoTime() < deadline }
        internal val isAccepted: Boolean get() = synchronized(lock) { accepted && decision == null && !finished && current.asBoolean }
        fun accept() = synchronized(lock) {
            if (isPending) { accepted = true; lock.notifyAll() }
        }
        fun reject() = decide(HostTrustException.Reason.REJECTED)
        fun cancel() = decide(HostTrustException.Reason.CANCELLED)
        private fun decide(reason: HostTrustException.Reason) = synchronized(lock) {
            if (!finished && decision == null) { decision = reason; lock.notifyAll() }
        }
        internal fun finish() = synchronized(lock) { finished = true; lock.notifyAll() }
        internal fun awaitApproval() = synchronized(lock) {
            while (true) {
                if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
                decision?.let { trustFailure(it) }
                if (accepted) return@synchronized
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) trustFailure(HostTrustException.Reason.EXPIRED)
                try { TimeUnit.NANOSECONDS.timedWait(lock, minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100))) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); trustFailure(HostTrustException.Reason.CANCELLED) }
            }
        }
    }
}
