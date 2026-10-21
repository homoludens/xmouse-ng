package com.stripe1.xmouse.trust

/** Fixed, bounded diagnostics only. Never retain parser, filesystem, or SSH exception content. */
class HostTrustException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { INVALID_KEY, INVALID_REPOSITORY, STORAGE, MISMATCH, REJECTED, CANCELLED, EXPIRED }
}

internal fun trustFailure(reason: HostTrustException.Reason): Nothing = throw HostTrustException(reason)
