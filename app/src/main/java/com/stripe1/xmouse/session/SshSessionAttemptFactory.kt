package com.stripe1.xmouse.session

import com.stripe1.xmouse.connection.ConnectionFailure
import com.stripe1.xmouse.connection.ConnectionState
import com.stripe1.xmouse.connection.EndpointLocator
import com.stripe1.xmouse.secrets.*
import com.stripe1.xmouse.transport.*
import com.stripe1.xmouse.trust.*
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** Must enqueue repository I/O off caller/main; callbacks transfer ownership of material exactly once. */
fun interface SessionCredentialProvider {
    fun read(selection: AuthenticationSelection, prompts: CredentialPrompts, current: BooleanSupplier, callback: AuthenticationCallback)
}

/** Production pipeline, also exercised with the REAL SshTransport and synthetic/loopback backends. */
class SshSessionAttemptFactory @JvmOverloads constructor(
    private val credentials: SessionCredentialProvider,
    private val trustRepository: HostKeyRepository,
    val interactions: SessionInteractions,
    private val limits: SshLimits = SshLimits.production(),
    private val runtime: SshRuntime = SshRuntime.SHARED,
    private val credentialTimeoutMillis: Long = 60_000,
    private val closeTimeoutMillis: Long = 5_000,
    private val backendFactory: (HostTrustRequests, Runnable) -> SshBackend = { trust, trusted ->
        JschSshBackend(HostTrustPolicy(trust, trusted))
    },
    private val permissionFailure: () -> ConnectionFailure.Permission? = { null },
    private val diagnostics: SshDiagnostics = SshDiagnostics.NONE,
) : SessionAttemptFactory {
    init { require(credentialTimeoutMillis in 1..60_000); require(closeTimeoutMillis in 1..5_000) }

    override fun invalidateReviews() = interactions.invalidate()
    override fun accessFailure(): ConnectionFailure.Permission? = permissionFailure()

    override fun create(host: SessionHost): SessionAttempt = Attempt(host)

    private inner class Attempt(private val host: SessionHost) : SessionAttempt, HostTrustRequests.Listener {
        private val live = AtomicBoolean(true)
        private val commandResults = interactions.beginCommandResults()
        private val lock = Any()
        private var listener: SessionAttempt.Listener? = null
        private var started = false
        private var credentialsDone = false
        private var handedMaterial: AuthenticationMaterial? = null
        private var transportDone = false
        private var closeStarted = false
        private var closeFinished = false
        private var closeFailure: ConnectionFailure? = null
        private val closeCallbacks = mutableListOf<(ConnectionFailure?) -> Unit>()
        private var credentialTimer: ScheduledFuture<*>? = null
        private var closeTimer: ScheduledFuture<*>? = null
        private var permissionTimer: ScheduledFuture<*>? = null
        private var mismatchObservation: HostTrustRequests.MismatchObservation? = null
        private var mismatchReviewEpoch = -1L
        private val authMode = if (host.authentication.useKey) SshAuthenticationMode.PUBLIC_KEY
            else SshAuthenticationMode.PASSWORD
        @Volatile private var diagnosticStage = SshDiagnosticStage.PREPARING_CREDENTIALS
        private val trust = HostTrustRequests(trustRepository, Executor { it.run() }).also { it.attach(this) }
        private val transport = SshTransport(backendFactory(trust, Runnable {
            // The UI state advances here for compatibility; JSch has verified trust but may not
            // yet have sent a user-auth request, so diagnostics retain the more precise stage.
            phase(ConnectionState.Phase.AUTHENTICATING, SshDiagnosticStage.HOST_TRUSTED)
        }), SshTransport.newWorker(), limits, runtime).also { ssh ->
            ssh.setProgressListener { progress -> phase(if (progress == SshTransport.Progress.RESOLVING)
                ConnectionState.Phase.RESOLVING else ConnectionState.Phase.CONNECTING) }
            ssh.setLossListener { failure -> fail(failure.toConnectionFailure()) }
        }

        override fun start(listener: SessionAttempt.Listener) {
            synchronized(lock) {
                check(!started); started = true; this.listener = listener
                if (!live.get()) { credentialsDone = true; return }
                diagnostics.record(SshDiagnosticEvent(diagnosticStage, authMode))
                val denied = accessFailure()
                if (denied != null) { credentialsDone = true; fail(denied); return }
                permissionTimer = timers.scheduleWithFixedDelay({
                    if (live.get()) accessFailure()?.let { fail(ConnectionFailure.Permission(ConnectionFailure.PermissionReason.REVOKED)) }
                }, limits.healthInterval.toLong(), limits.healthInterval.toLong(), TimeUnit.MILLISECONDS)
                credentialTimer = timers.schedule({
                    synchronized(lock) {
                        if (!credentialsDone && live.getAndSet(false)) {
                            interactions.clear(this)
                            val failure = ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.TIMED_OUT)
                            diagnostics.record(SshDiagnosticEvent(diagnosticStage, authMode, failure = failure))
                            listener.failed(failure)
                        }
                    }
                }, credentialTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            try {
                credentials.read(host.authentication, { kind, request ->
                    interactions.show(this, SessionInteractions.Prompt.Credential(kind, request), BooleanSupplier { live.get() })
                }, BooleanSupplier { live.get() }) { material, _ -> authenticated(material) }
            } catch (_: Exception) { authenticated(null) }
        }

        private fun authenticated(material: AuthenticationMaterial?) {
            synchronized(lock) {
                // Duplicate provider callbacks cannot create another transport or wipe its active bytes.
                if (credentialsDone) { if (material !== handedMaterial) material?.close(); return }
                credentialsDone = true
                credentialTimer?.cancel(false)
                interactions.clear(this)
                if (!live.get()) { material?.close(); finishCloseIfPossible(); return }
                val denied = accessFailure()
                if (denied != null) { material?.close(); fail(denied); return }
                if (material == null || material.host != host.authentication.host || material.useKey != host.authentication.useKey) {
                    material?.close()
                    fail(ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.KEY_UNAVAILABLE))
                    return
                }
                val address = when (val locator = host.endpoint.locator) {
                    is EndpointLocator.Manual -> locator.host.lookupHost
                    // The endpoint-aware Android resolver handles service-only locators through a
                    // fresh NSD lookup. This display spelling is never sent to DNS for that case.
                    is EndpointLocator.DiscoveredService -> locator.stableTargetHostname?.lookupHost ?: locator.instanceName
                }
                handedMaterial = material
                transport.connect(SshConnectionRequest(host.endpoint, address, host.shell, host.initialCommand), material,
                    object : SshTransport.Callback<Void> {
                        override fun success(value: Void?) {
                            if (live.get()) {
                                host.initialCommand?.let { interactions.recentCommand(commandResults, it) }
                                diagnosticStage = SshDiagnosticStage.CONNECTED
                                diagnostics.record(SshDiagnosticEvent(diagnosticStage, authMode))
                                listener?.ready()
                            }
                        }
                        override fun failure(failure: SshTransport.Failure) { fail(failure.toConnectionFailure()) }
                        override fun mismatch(mismatch: HostKeyMismatch) {
                            synchronized(lock) {
                                if (live.get()) {
                                    mismatchObservation = trust.captureMismatch(mismatch, BooleanSupplier { live.get() })
                                    if (mismatchObservation != null) mismatchReviewEpoch = interactions.reviewEpoch()
                                }
                            }
                        }
                    })
            }
        }

        override fun executeShellCommand(command: String): Boolean = executeShellCommand(command, null)

        override fun executeShellCommand(
            command: String,
            presentation: SessionInteractions.PresentationToken?,
        ): Boolean {
            if (!live.get() || !transport.executeShellCommand(command)) return false
            // Preserve legacy recent-command feedback only after this live attempt accepted the write.
            // The scope and submission-time presentation token suppress loss/reattach races.
            if (presentation != null) interactions.recentCommand(commandResults, presentation, command)
            return true
        }

        override fun executeInputCommand(
            command: String,
            presentation: SessionInteractions.PresentationToken?,
            isCurrent: BooleanSupplier,
            onWriteStarting: Runnable,
        ): Boolean {
            if (!live.get() || !isCurrent.asBoolean) return false
            val currentAtTransport = BooleanSupplier { live.get() && isCurrent.asBoolean }
            if (!transport.executeInputCommand(command, currentAtTransport, onWriteStarting)) return false
            if (presentation != null) interactions.recentCommand(commandResults, presentation, command)
            return true
        }

        override fun executeInputRelease(
            command: String,
            shouldRelease: BooleanSupplier,
            onWriteStarting: Runnable,
        ): Boolean {
            if (!live.get()) return false
            val currentRelease = BooleanSupplier { live.get() && shouldRelease.asBoolean }
            return transport.executeInputRelease(command, currentRelease, onWriteStarting)
        }

        override fun executeExecCommand(
            command: String,
            presentation: SessionInteractions.PresentationToken,
        ): Boolean {
            if (!live.get()) return false
            return transport.exec(command, object : SshTransport.Callback<String> {
                override fun success(value: String) {
                    if (live.get()) interactions.execCompleted(commandResults, presentation, command, value)
                }
                override fun failure(failure: SshTransport.Failure) {
                    if (live.get()) interactions.execFailed(commandResults, presentation, command)
                }
            })
        }

        override fun canSendClipboard(presentation: SessionInteractions.PresentationToken): Boolean =
            live.get() && transport.isConnected && interactions.isCurrentPresentation(presentation)

        override fun executeClipboard(
            text: String,
            presentation: SessionInteractions.PresentationToken,
        ): Boolean {
            if (!canSendClipboard(presentation)) return false
            val accepted = transport.sendClipboard(text, object : SshTransport.Callback<ClipboardTransferResult> {
                override fun success(value: ClipboardTransferResult) {
                    if (live.get()) interactions.clipboardResult(commandResults, presentation, value)
                }
                override fun failure(failure: SshTransport.Failure) {
                    if (live.get()) interactions.clipboardResult(
                        commandResults, presentation, ClipboardTransferResult.FAILED,
                    )
                }
            })
            if (!accepted && live.get()) {
                interactions.clipboardResult(commandResults, presentation, ClipboardTransferResult.FAILED)
            }
            return accepted
        }

        private fun phase(phase: ConnectionState.Phase, diagnosticStageOverride: SshDiagnosticStage? = null) {
            if (!live.get()) return
            diagnosticStage = diagnosticStageOverride ?: when (phase) {
                ConnectionState.Phase.PREPARING_CREDENTIALS -> SshDiagnosticStage.PREPARING_CREDENTIALS
                ConnectionState.Phase.RESOLVING -> SshDiagnosticStage.RESOLVING
                ConnectionState.Phase.CONNECTING -> SshDiagnosticStage.CONNECTING
                ConnectionState.Phase.AWAITING_TRUST -> SshDiagnosticStage.AWAITING_TRUST
                ConnectionState.Phase.AUTHENTICATING -> SshDiagnosticStage.AUTHENTICATING
                ConnectionState.Phase.CONNECTED -> SshDiagnosticStage.CONNECTED
                ConnectionState.Phase.CLOSING -> SshDiagnosticStage.CLOSING
                ConnectionState.Phase.DISCONNECTED -> SshDiagnosticStage.DISCONNECTED
                ConnectionState.Phase.ERROR -> SshDiagnosticStage.FAILED
            }
            diagnostics.record(SshDiagnosticEvent(diagnosticStage, authMode))
            if (phase == ConnectionState.Phase.AUTHENTICATING) {
                // These are the configured candidates, not a claim that each method was attempted.
                if (authMode == SshAuthenticationMode.PUBLIC_KEY) {
                    diagnostics.record(SshDiagnosticEvent(stage = SshDiagnosticStage.AUTH_METHOD_CONFIGURED,
                        authMode = authMode, authMethod = SshAuthenticationMethod.PUBLIC_KEY))
                } else {
                    diagnostics.record(SshDiagnosticEvent(SshDiagnosticStage.AUTH_METHOD_CONFIGURED,
                        authMode, SshAuthenticationMethod.PASSWORD))
                    diagnostics.record(SshDiagnosticEvent(SshDiagnosticStage.AUTH_METHOD_CONFIGURED,
                        authMode, SshAuthenticationMethod.KEYBOARD_INTERACTIVE))
                }
            }
            listener?.phase(phase)
        }
        private fun fail(failure: ConnectionFailure) {
            if (!live.compareAndSet(true, false)) return
            val failureStage = if (failure == ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.REJECTED))
                SshDiagnosticStage.AUTHENTICATION_REJECTED else diagnosticStage
            diagnostics.record(SshDiagnosticEvent(failureStage, authMode, failure = failure))
            interactions.invalidateCommandResults(commandResults)
            listener?.failed(failure)
        }
        override fun show(request: HostTrustRequests.Request) {
            phase(ConnectionState.Phase.AWAITING_TRUST)
            interactions.show(this, SessionInteractions.Prompt.Trust(request), BooleanSupplier { live.get() })
        }
        override fun dismiss(request: HostTrustRequests.Request) { interactions.dismissTrust(this, request) }

        override fun cancelAndClose(completion: (ConnectionFailure?) -> Unit) {
            synchronized(lock) {
                if (closeFinished) { completion(closeFailure); return }
                closeCallbacks.add(completion)
                if (closeStarted) return
                closeStarted = true; live.set(false)
                interactions.invalidateCommandResults(commandResults)
                if (!started) credentialsDone = true
                credentialTimer?.cancel(false)
                permissionTimer?.cancel(false)
                interactions.clear(this)
                trust.detach()
                closeTimer = timers.schedule({ synchronized(lock) {
                    if (!closeFinished) finishClose(ConnectionFailure.Transport(ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED))
                } }, closeTimeoutMillis, TimeUnit.MILLISECONDS)
                transport.closeConfirmed(object : SshTransport.Callback<Void> {
                    override fun success(value: Void?) { synchronized(lock) { transportDone = true; finishCloseIfPossible() } }
                    override fun failure(failure: SshTransport.Failure) { synchronized(lock) {
                        transportDone = true
                        closeFailure = ConnectionFailure.Transport(ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED)
                        finishCloseIfPossible()
                    } }
                })
            }
        }
        private fun finishCloseIfPossible() {
            if (closeStarted && credentialsDone && transportDone && !closeFinished) {
                if (closeFailure == null) publishMismatchReviewAfterClose()
                finishClose(closeFailure)
            }
        }

        /** Trust replacement is a new bounded review owner, created only after the old close barrier. */
        private fun publishMismatchReviewAfterClose() {
            val observation = mismatchObservation ?: return
            val expectedEpoch = mismatchReviewEpoch
            val reviewLive = AtomicBoolean(true)
            val reviewCurrent = BooleanSupplier {
                reviewLive.get() && interactions.reviewEpochIsCurrent(expectedEpoch)
            }
            val mismatch = trust.reviewMismatch(observation, reviewCurrent) ?: return
            val replacementTransport = SshTransport(
                backendFactory(trust, Runnable { }), SshTransport.newWorker(), limits, runtime,
            )
            lateinit var prompt: SessionInteractions.Prompt.Mismatch
            prompt = SessionInteractions.Prompt.Mismatch(
                mismatch,
                { candidate, complete ->
                    if (candidate !== mismatch || !reviewCurrent.asBoolean) {
                        complete(false)
                    } else {
                        val completed = AtomicBoolean(false)
                        replacementTransport.replaceHostKey(candidate, object : SshTransport.Callback<Void> {
                            override fun success(value: Void?) = finishReplacementTransport(replacementTransport, completed, true, complete)
                            override fun failure(failure: SshTransport.Failure) = finishReplacementTransport(replacementTransport, completed, false, complete)
                        })
                    }
                },
                {
                    reviewLive.set(false)
                    mismatch.cancel()
                    replacementTransport.closeConfirmed(null)
                },
                { finishedPrompt, success -> interactions.finishMismatch(finishedPrompt, success) },
            )
            interactions.showReview(this, prompt, expectedEpoch, reviewCurrent)
        }

        private fun finishReplacementTransport(
            replacementTransport: SshTransport,
            completed: AtomicBoolean,
            success: Boolean,
            result: (Boolean) -> Unit,
        ) {
            if (!completed.compareAndSet(false, true)) return
            replacementTransport.closeConfirmed(object : SshTransport.Callback<Void> {
                override fun success(value: Void?) { result(success) }
                override fun failure(failure: SshTransport.Failure) { result(false) }
            })
        }

        private fun finishClose(failure: ConnectionFailure?) {
            closeFinished = true; closeFailure = failure
            closeTimer?.cancel(false)
            val callbacks = closeCallbacks.toList()
            closeCallbacks.clear(); listener = null
            handedMaterial = null
            callbacks.forEach { it(failure) }
        }
    }
    companion object {
        // One process timer; no per-attempt replacement pools or I/O on this lane.
        private val timers = ScheduledThreadPoolExecutor(1) {
            Thread(it, "xmouse-session-timer").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

internal fun SshTransport.Failure.toConnectionFailure(): ConnectionFailure = when (this) {
    SshTransport.Failure.AUTHENTICATION -> ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.REJECTED)
    SshTransport.Failure.CONNECTION_REFUSED -> ConnectionFailure.Transport(ConnectionFailure.TransportReason.CONNECTION_REFUSED)
    SshTransport.Failure.UNREACHABLE -> ConnectionFailure.Transport(ConnectionFailure.TransportReason.UNREACHABLE)
    SshTransport.Failure.PERMISSION -> ConnectionFailure.Permission(ConnectionFailure.PermissionReason.REVOKED)
    SshTransport.Failure.RESOLUTION -> ConnectionFailure.Resolution(ConnectionFailure.ResolutionReason.NOT_FOUND)
    SshTransport.Failure.RESOLUTION_TIMEOUT -> ConnectionFailure.Resolution(ConnectionFailure.ResolutionReason.TIMED_OUT)
    SshTransport.Failure.PRIVATE_KEY -> ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.KEY_UNAVAILABLE)
    SshTransport.Failure.HOST_TRUST -> ConnectionFailure.Trust(ConnectionFailure.TrustReason.REJECTED)
    SshTransport.Failure.HOST_MISMATCH -> ConnectionFailure.Trust(ConnectionFailure.TrustReason.KEY_MISMATCH)
    SshTransport.Failure.TRUST_STORAGE -> ConnectionFailure.Trust(ConnectionFailure.TrustReason.STORAGE_UNAVAILABLE)
    SshTransport.Failure.SESSION_LOST, SshTransport.Failure.CLOSED -> ConnectionFailure.Transport(ConnectionFailure.TransportReason.CONNECTION_LOST)
    SshTransport.Failure.CHANNEL_CLOSED, SshTransport.Failure.CHANNEL_TIMEOUT -> ConnectionFailure.Transport(ConnectionFailure.TransportReason.CHANNEL_UNAVAILABLE)
    SshTransport.Failure.CAPACITY -> ConnectionFailure.Transport(ConnectionFailure.TransportReason.CAPACITY)
    SshTransport.Failure.CLEANUP_TIMEOUT -> ConnectionFailure.Transport(ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED)
    SshTransport.Failure.CONNECTION_TIMEOUT, SshTransport.Failure.WRITE_TIMEOUT, SshTransport.Failure.EXEC_TIMEOUT,
    SshTransport.Failure.SCP_TIMEOUT -> ConnectionFailure.Transport(ConnectionFailure.TransportReason.TIMED_OUT)
    else -> ConnectionFailure.Transport(ConnectionFailure.TransportReason.PROTOCOL_ERROR)
}
