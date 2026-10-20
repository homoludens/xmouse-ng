package com.stripe1.xmouse.connection

import java.nio.charset.StandardCharsets
import java.util.Locale

/** Saved lookup intent. Resolved addresses and Android Network handles belong to an attempt, not here. */
sealed interface EndpointLocator {
    data class Manual(val host: EndpointHost) : EndpointLocator

    /** NSD instance is one literal label, including any spaces/dots; it is not a fabricated hostname. */
    data class DiscoveredService @JvmOverloads constructor(
        val instanceName: String,
        val serviceType: String,
        val domain: String,
        val stableTargetHostname: EndpointHost? = null,
    ) : EndpointLocator {
        val normalizedInstanceName: String = normalizeInstance(instanceName)
        val normalizedServiceType: String = normalizeServiceType(serviceType)
        val normalizedDomain: String = normalizeDomain(domain)

        init {
            require(stableTargetHostname == null || stableTargetHostname.kind == EndpointHost.Kind.DNS) {
                "A stable service target must be an advertised hostname, not a resolved address"
            }
        }
    }
}

/** Structured logical trust key. No credentials, current IP candidates, or network handles. */
sealed interface EndpointIdentity {
    val port: Int

    data class Host(val host: String, override val port: Int) : EndpointIdentity {
        init {
            require(EndpointHost(host).normalized == host) { "Identity host must be normalized" }
            requirePort(port)
        }
    }

    data class Service(
        val instanceName: String,
        val serviceType: String,
        val domain: String,
        override val port: Int,
    ) : EndpointIdentity {
        init {
            require(normalizeInstance(instanceName) == instanceName) { "Identity instance must be normalized" }
            require(normalizeServiceType(serviceType) == serviceType) { "Identity type must be normalized" }
            require(normalizeDomain(domain) == domain) { "Identity domain must be normalized" }
            requirePort(port)
        }
    }
}

data class Endpoint(val locator: EndpointLocator, val port: Int) {
    init {
        requirePort(port)
    }

    val identity: EndpointIdentity = when (locator) {
        is EndpointLocator.Manual -> EndpointIdentity.Host(locator.host.normalized, port)
        is EndpointLocator.DiscoveredService -> locator.stableTargetHostname?.let {
            EndpointIdentity.Host(it.normalized, port)
        } ?: EndpointIdentity.Service(
            locator.normalizedInstanceName, locator.normalizedServiceType, locator.normalizedDomain, port,
        )
    }

    companion object {
        @JvmStatic
        fun manual(host: String, port: Int): Endpoint = Endpoint(EndpointLocator.Manual(EndpointHost(host)), port)

        @JvmStatic
        fun manual(host: String, port: String): Endpoint = manual(host, parsePort(port))

        @JvmStatic
        fun parsePort(input: String): Int {
            val digits = input.trim()
            require(digits.isNotEmpty() && digits.all { it in '0'..'9' }) { "Port must be decimal digits" }
            val port = digits.toIntOrNull()
            require(port != null) { "Port out of range" }
            requirePort(port)
            return port
        }
    }
}

private fun requirePort(port: Int) {
    require(port in 1..65535) { "Port must be in 1..65535" }
}

private fun normalizeInstance(instance: String): String {
    require(instance.isNotBlank() && instance.none { it.isISOControl() }) { "Invalid service instance" }
    require(instance.toByteArray(StandardCharsets.UTF_8).size <= 63) { "Service instance exceeds one DNS label" }
    // DNS label matching folds ASCII only; do not conflate distinct Unicode instance names.
    return instance.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
}

private fun normalizeServiceType(type: String): String {
    val normalized = type.removeSuffix(".").lowercase(Locale.ROOT)
    require(normalized == "_ssh._tcp") { "Only SSH TCP service locators are supported" }
    return normalized
}

private fun normalizeDomain(domain: String): String {
    require(domain == domain.trim()) { "Service domain must not contain surrounding whitespace" }
    val host = EndpointHost(domain)
    require(host.kind == EndpointHost.Kind.DNS) { "Service domain must be a DNS name" }
    return host.normalized
}
