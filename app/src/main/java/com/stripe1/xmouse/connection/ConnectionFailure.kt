package com.stripe1.xmouse.connection

/** Safe observable error codes, not exception messages/causes (which may contain authentication data). */
sealed class ConnectionFailure(val category: Category) {
    enum class Category { PERMISSION, RESOLUTION, TRANSPORT, TRUST, AUTHENTICATION }

    data class Permission(val reason: PermissionReason) : ConnectionFailure(Category.PERMISSION)
    data class Resolution(val reason: ResolutionReason) : ConnectionFailure(Category.RESOLUTION)
    data class Transport(val reason: TransportReason) : ConnectionFailure(Category.TRANSPORT)
    data class Trust(val reason: TrustReason) : ConnectionFailure(Category.TRUST)
    data class Authentication(val reason: AuthenticationReason) : ConnectionFailure(Category.AUTHENTICATION)

    enum class PermissionReason { DENIED, REVOKED }
    enum class ResolutionReason { NOT_FOUND, TIMED_OUT, UNAVAILABLE }
    enum class TransportReason {
        CONNECTION_REFUSED, UNREACHABLE, TIMED_OUT, CONNECTION_LOST, CHANNEL_UNAVAILABLE, PROTOCOL_ERROR,
    }
    enum class TrustReason { REJECTED, KEY_MISMATCH, APPROVAL_TIMED_OUT, UNSUPPORTED_HOST_KEY }
    enum class AuthenticationReason { REJECTED, INVALID_PASSPHRASE, KEY_UNAVAILABLE, UNSUPPORTED_KEY, TIMED_OUT }
}
