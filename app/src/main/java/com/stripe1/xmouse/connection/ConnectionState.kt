package com.stripe1.xmouse.connection

/** Immutable connection observations; desired intent is owned separately by SessionController. */
sealed class ConnectionState(val phase: Phase) {
    abstract val endpoint: EndpointIdentity?
    open val failure: ConnectionFailure? = null

    /** Connected means the command channel is usable, not merely that TCP or authentication succeeded. */
    val isReady: Boolean get() = phase == Phase.CONNECTED

    enum class Phase { PREPARING_CREDENTIALS, RESOLVING, CONNECTING, AWAITING_TRUST, AUTHENTICATING, CONNECTED, CLOSING, DISCONNECTED, ERROR }

    data class PreparingCredentials(override val endpoint: EndpointIdentity) : ConnectionState(Phase.PREPARING_CREDENTIALS)
    data class Resolving(override val endpoint: EndpointIdentity) : ConnectionState(Phase.RESOLVING)
    data class Connecting(override val endpoint: EndpointIdentity) : ConnectionState(Phase.CONNECTING)
    data class AwaitingTrust(override val endpoint: EndpointIdentity) : ConnectionState(Phase.AWAITING_TRUST)
    data class Authenticating(override val endpoint: EndpointIdentity) : ConnectionState(Phase.AUTHENTICATING)
    data class Connected(override val endpoint: EndpointIdentity) : ConnectionState(Phase.CONNECTED)
    data class Closing(override val endpoint: EndpointIdentity) : ConnectionState(Phase.CLOSING)
    data class Disconnected @JvmOverloads constructor(override val endpoint: EndpointIdentity? = null) :
        ConnectionState(Phase.DISCONNECTED)

    data class Error(override val endpoint: EndpointIdentity, override val failure: ConnectionFailure) :
        ConnectionState(Phase.ERROR)
}
