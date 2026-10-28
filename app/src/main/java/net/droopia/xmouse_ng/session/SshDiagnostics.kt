package net.droopia.xmouse_ng.session

import android.util.Log
import net.droopia.xmouse_ng.BuildConfig
import net.droopia.xmouse_ng.connection.ConnectionFailure

/** Diagnostic vocabulary is deliberately enum-only: no endpoint, identity, secret, prompt, or exception data. */
enum class SshDiagnosticStage {
    PREPARING_CREDENTIALS,
    RESOLVING,
    CONNECTING,
    AWAITING_TRUST,
    HOST_TRUSTED,
    AUTHENTICATING,
    AUTH_METHOD_CONFIGURED,
    AUTHENTICATION_REJECTED,
    CONNECTED,
    CLOSING,
    DISCONNECTED,
    FAILED,
}

enum class SshAuthenticationMode { PASSWORD, PUBLIC_KEY }
enum class SshAuthenticationMethod { PASSWORD, PUBLIC_KEY, KEYBOARD_INTERACTIVE }

data class SshDiagnosticEvent(
    val stage: SshDiagnosticStage,
    val authMode: SshAuthenticationMode? = null,
    val authMethod: SshAuthenticationMethod? = null,
    val failure: ConnectionFailure? = null,
) {
    /** Stable allowlisted rendering; it never stringifies an exception or an application object. */
    fun toSafeLogMessage(): String = buildString {
        append("stage=").append(stage.name)
        authMode?.let { append(" auth_mode=").append(it.name) }
        authMethod?.let { append(" auth_method=").append(it.name) }
        failure?.let {
            append(" failure_category=").append(it.category.name)
            append(" failure_reason=").append(it.safeReasonCode())
        }
    }

    private fun ConnectionFailure.safeReasonCode(): String = when (this) {
        is ConnectionFailure.Permission -> reason.name
        is ConnectionFailure.Resolution -> reason.name
        is ConnectionFailure.Transport -> reason.name
        is ConnectionFailure.Trust -> reason.name
        is ConnectionFailure.Authentication -> reason.name
    }
}

fun interface SshDiagnosticSink {
    fun record(event: SshDiagnosticEvent)
}

/** Isolates diagnostic failures from authentication, state transitions, and cleanup. */
class SshDiagnostics(private val sink: SshDiagnosticSink) {
    fun record(event: SshDiagnosticEvent) {
        try {
            sink.record(event)
        } catch (_: RuntimeException) {
            // A logging failure must never change the connection outcome.
        }
    }

    companion object {
        @JvmField val NONE = SshDiagnostics(SshDiagnosticSink { })
    }
}

/** The only production binding: fixed tag and enum-only payload, enabled in debug builds only. */
internal object AndroidSshDiagnosticSink : SshDiagnosticSink {
    override fun record(event: SshDiagnosticEvent) {
        if (BuildConfig.DEBUG) Log.d(TAG, event.toSafeLogMessage())
    }

    private const val TAG = "XMouseSSH"
}
