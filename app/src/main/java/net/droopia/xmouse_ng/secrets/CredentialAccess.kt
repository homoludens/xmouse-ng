package net.droopia.xmouse_ng.secrets

import java.util.concurrent.atomic.AtomicReference

/**
 * Presentation boundary, not an authentication retry or a store reset. Implementations must request
 * explicit credential entry (or private-key re-import), and cancel when the user declines/leaves or
 * no UI is available. Dispatch to the UI thread as needed; the read itself runs off the main thread.
 * Never supply an empty/default credential or fall back to legacy plaintext automatically.
 */
fun interface CredentialReentryHandler {
    fun requestReentry(request: CredentialReentryRequest)
}

fun interface CredentialReadCallback {
    fun onResult(result: CredentialReadResult)
}

sealed class CredentialReadResult {
    /** Caller owns these bytes and must clear them after use. Do not log or persist this result. */
    class Available(val plaintext: ByteArray, val origin: Origin) : CredentialReadResult() {
        override fun toString(): String = "Available($origin)"
    }

    enum class Origin { STORED, REENTERED_FOR_ATTEMPT }

    /** Absence is not evidence of device-key loss. */
    object Missing : CredentialReadResult()

    /** Stop the requesting authentication attempt; do not silently retry or change auth choices. */
    object Cancelled : CredentialReadResult()

    class Failed(val reason: SecretStoreException.Reason) : CredentialReadResult()
}

/**
 * One-shot, explicitly scoped consent: use entered bytes for this attempt ONLY, without saving them,
 * overwriting unreadable records, regenerating the key, or changing configuration. The presentation
 * owns a pending request and must cancel it on dismissal/lifecycle cancellation (or attempt timeout).
 * A future durable replacement operation requires its own deliberate consent; this is not that API.
 */
class CredentialReentryRequest internal constructor(
    val id: SecretId,
    completion: CredentialReadCallback,
) {
    val reason: SecretStoreException.Reason get() = SecretStoreException.Reason.KEY_UNAVAILABLE
    private val pending = AtomicReference<CredentialReadCallback?>(completion)
    val isPending: Boolean get() = pending.get() != null

    /**
     * Returns false after cancellation/completion; late UI responses cannot revive an attempt.
     * Copies accepted bytes. The UI still owns its input and must clear it, including on rejection.
     */
    fun useForThisAttempt(plaintext: ByteArray): Boolean {
        require(plaintext.size <= MAX_SECRET_BYTES) { "Secret exceeds storage limit" }
        val completion = pending.getAndSet(null) ?: return false
        completion.onResult(
            CredentialReadResult.Available(plaintext.copyOf(), CredentialReadResult.Origin.REENTERED_FOR_ATTEMPT),
        )
        return true
    }

    fun cancel(): Boolean {
        val completion = pending.getAndSet(null) ?: return false
        completion.onResult(CredentialReadResult.Cancelled)
        return true
    }
}

/**
 * Java-callable protected-read consumer. Call off the main thread. Only KEY_UNAVAILABLE actively
 * requests re-entry; missing, damaged, unsupported, or inaccessible records remain distinct.
 * There are deliberately NO write/reset/database/preference operations at this boundary.
 *
 * The handler may complete asynchronously. The returned request can also be cancelled by the
 * attempt owner. Completion runs on the thread supplying the result; marshal as appropriate.
 * Migration/authentication wiring remains with the protected repository, not this component.
 */
class CredentialAccess(private val store: SecretStore) {
    fun read(
        id: SecretId,
        presentation: CredentialReentryHandler,
        completion: CredentialReadCallback,
    ): CredentialReentryRequest? {
        // Keep consumer callbacks outside the catch: presentation failures are not store failures.
        val result = try {
            store.read(id)?.let { CredentialReadResult.Available(it, CredentialReadResult.Origin.STORED) }
                ?: CredentialReadResult.Missing
        } catch (failure: SecretStoreException) {
            CredentialReadResult.Failed(failure.reason)
        }
        if (result is CredentialReadResult.Failed && result.reason == SecretStoreException.Reason.KEY_UNAVAILABLE) {
            val request = CredentialReentryRequest(id, completion)
            presentation.requestReentry(request)
            return request
        }
        completion.onResult(result)
        return null
    }
}
