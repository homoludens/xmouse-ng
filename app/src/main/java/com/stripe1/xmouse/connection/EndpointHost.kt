package com.stripe1.xmouse.connection

import java.net.IDN
import java.util.Locale

/** A host field, never a URI or host:port pair. Construction performs no name/network lookup. */
data class EndpointHost(val original: String) {
    private val parsed = parseHost(original)

    /** Canonical trust identity; the display input remains available in [original]. */
    val normalized: String = parsed.normalized
    val kind: Kind = parsed.kind

    /** Preserves a DNS root dot and an explicitly entered IPv6 scope, unlike a resolved IP. */
    val lookupHost: String = parsed.lookupHost

    enum class Kind { DNS, IPV4, IPV6 }
}

private data class ParsedHost(
    val normalized: String,
    val kind: EndpointHost.Kind,
    val lookupHost: String = normalized,
)

private fun parseHost(original: String): ParsedHost {
    require(original.none { it.isISOControl() }) { "Host must not contain control characters" }
    val host = original.trim()
    require(host.isNotEmpty()) { "Host must not be blank" }
    require(host.none { it.isWhitespace() }) { "Host must not contain whitespace" }

    if (':' in host) {
        val literal = if (host.startsWith('[') && host.endsWith(']')) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
        return ParsedHost(normalizeIpv6(literal), EndpointHost.Kind.IPV6)
    }

    val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    val name = ascii.removeSuffix(".")
    require(name.isNotEmpty() && name.length <= 253) { "Invalid hostname length" }
    require(name.split('.').all { it.isNotEmpty() && it.length <= 63 }) { "Invalid hostname label" }
    if (name.all { it in '0'..'9' || it == '.' }) {
        return ParsedHost(parseIpv4(name).joinToString("."), EndpointHost.Kind.IPV4)
    }
    return ParsedHost(name, EndpointHost.Kind.DNS, ascii)
}

private fun parseIpv4(host: String): List<Int> {
    val parts = host.split('.')
    require(parts.size == 4) { "IPv4 requires four decimal octets" }
    return parts.map { part ->
        // Reject ambiguous octal/short-address forms rather than assigning them a different identity.
        require(part.length in 1..3 && part.all { it in '0'..'9' }) { "Invalid IPv4 octet" }
        require(part.length == 1 || part[0] != '0') { "IPv4 octets must not have leading zeroes" }
        part.toInt().also { require(it <= 255) { "IPv4 octet out of range" } }
    }
}

private fun normalizeIpv6(literal: String): String {
    val scoped = literal.split('%')
    require(scoped.size in 1..2) { "Invalid IPv6 scope" }
    val scope = if (scoped.size == 2) {
        val value = scoped[1]
        require(value.isNotEmpty() && value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.-"
        }) { "Invalid IPv6 scope" }
        if (value.all { it in '0'..'9' }) {
            val index = value.toIntOrNull()
            require(index != null && index >= 0) { "IPv6 scope index out of range" }
            "%$index"
        } else {
            // Interface names are case-sensitive. Never resolve them to a transient index here.
            "%$value"
        }
    } else {
        ""
    }

    var address = scoped[0]
    if ('.' in address) {
        val separator = address.lastIndexOf(':')
        require(separator >= 0) { "Invalid IPv6 embedded IPv4 address" }
        val octets = parseIpv4(address.substring(separator + 1))
        address = address.substring(0, separator + 1) +
            ((octets[0] shl 8) or octets[1]).toString(16) + ":" +
            ((octets[2] shl 8) or octets[3]).toString(16)
    }
    require(address.length <= 39 && address.all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':'
    }) { "Invalid IPv6 literal" }

    val halves = address.split("::")
    require(halves.size in 1..2) { "IPv6 may contain only one compression marker" }
    fun groups(part: String): List<Int> = if (part.isEmpty()) emptyList() else {
        part.split(':').map {
            require(it.length in 1..4) { "Invalid IPv6 group" }
            it.toInt(16)
        }
    }
    val left = groups(halves[0])
    val expanded = if (halves.size == 1) {
        require(left.size == 8) { "IPv6 requires eight groups without compression" }
        left
    } else {
        val right = groups(halves[1])
        require(left.size + right.size < 8) { "IPv6 compression must replace at least one group" }
        left + List(8 - left.size - right.size) { 0 } + right
    }
    // Expanded lower-case groups also unify embedded-IPv4 and hex-only spellings.
    return expanded.joinToString(":") { it.toString(16) } + scope
}
