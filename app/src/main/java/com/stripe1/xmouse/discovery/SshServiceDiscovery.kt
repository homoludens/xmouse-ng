package com.stripe1.xmouse.discovery

import android.content.Context
import com.stripe1.xmouse.connection.ConnectionFailure
import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.connection.EndpointHost
import com.stripe1.xmouse.connection.EndpointLocator
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import java.net.Inet6Address
import java.net.InetAddress
import java.util.LinkedHashMap

/** An in-memory SSH advertisement observed on one network. */
internal class SshServiceAdvertisement(
    val instanceName: String,
    val serviceType: String,
    val domain: String,
    val networkIdentity: String,
    /** Opaque platform-only handle; never persisted or exposed to clients. */
    internal val platformHandle: Any? = null,
)

/** A resolved advertisement. Addresses are observations, not endpoint identity. */
internal data class SshServiceInfo(
    val advertisement: SshServiceAdvertisement,
    val port: Int,
    val addresses: List<InetAddress>,
    val stableTargetHostname: String? = null,
)

internal interface SshNsdPlatform {
    val supportsServiceInfoCallbacks: Boolean

    fun discover(callbacks: DiscoveryCallbacks): AutoCloseable
    fun resolve(advertisement: SshServiceAdvertisement, callbacks: ServiceCallbacks): AutoCloseable
    fun watchServiceInfo(advertisement: SshServiceAdvertisement, callbacks: ServiceCallbacks): AutoCloseable

    interface DiscoveryCallbacks {
        fun onServiceFound(advertisement: SshServiceAdvertisement)
        fun onServiceLost(advertisement: SshServiceAdvertisement)
        fun onFailure(failure: SshServiceDiscovery.Failure)
    }

    interface ServiceCallbacks {
        fun onServiceUpdated(service: SshServiceInfo)
        fun onServiceLost()
        fun onFailure(failure: SshServiceDiscovery.Failure)
    }
}

/** Snapshot a default-network identity once for legacy NSD callbacks that carry no Network. */
internal class SshDiscoveryNetworkIdentity private constructor(val fallbackIdentity: String) {
    fun identityFor(platformNetworkIdentity: String?): String =
        platformNetworkIdentity?.takeIf(String::isNotBlank) ?: fallbackIdentity

    companion object {
        private const val NO_ACTIVE_NETWORK = "no-active-network"

        fun capture(activeNetworkIdentity: () -> String?): SshDiscoveryNetworkIdentity =
            SshDiscoveryNetworkIdentity(activeNetworkIdentity()?.takeIf(String::isNotBlank) ?: NO_ACTIVE_NETWORK)

        fun fixed(fallbackIdentity: String): SshDiscoveryNetworkIdentity =
            SshDiscoveryNetworkIdentity(fallbackIdentity.takeIf(String::isNotBlank) ?: NO_ACTIVE_NETWORK)
    }
}

/** Fails closed without presenting UI; a foreground owner may request permission and retry. */
internal object SshDiscoveryPermissionGate {
    fun start(
        permission: LocalNetworkPermissionCoordinator,
        callbacks: SshNsdPlatform.DiscoveryCallbacks,
        startDiscovery: () -> AutoCloseable,
    ): AutoCloseable {
        val permissionFailure = try {
            permission.accessFailure()
        } catch (failure: RuntimeException) {
            return denied(
                callbacks,
                ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED),
                failure,
            )
        }
        return if (permissionFailure == null) startDiscovery()
        else denied(callbacks, permissionFailure)
    }

    private fun denied(
        callbacks: SshNsdPlatform.DiscoveryCallbacks,
        permissionFailure: ConnectionFailure.Permission,
        cause: RuntimeException? = null,
    ): AutoCloseable {
        callbacks.onFailure(
            SshServiceDiscovery.Failure(
                operation = SshServiceDiscovery.Operation.PERMISSION,
                cause = cause,
                connectionFailure = permissionFailure,
            ),
        )
        return AutoCloseable { }
    }
}

/**
 * Lifecycle-scoped `_ssh._tcp` discovery. Construct it for a visible discovery flow, call
 * [start], and close it when that flow leaves. This class owns no session, credentials, or
 * persistent profile state.
 */
class SshServiceDiscovery internal constructor(
    private val platform: SshNsdPlatform,
    private val listener: Listener,
) : AutoCloseable {
    constructor(context: Context, listener: Listener) : this(AndroidSshNsdPlatform(context), listener)

    enum class Operation { START, STOP, RESOLVE, SERVICE_INFO, PERMISSION }

    data class Failure(
        val operation: Operation,
        val errorCode: Int? = null,
        val cause: Throwable? = null,
        val connectionFailure: ConnectionFailure? = null,
    )

    /** Includes the network so identical advertisements on distinct networks remain distinct. */
    data class Identity(
        val networkIdentity: String,
        val instanceName: String,
        val serviceType: String,
        val domain: String,
    )

    data class Candidate(
        val identity: Identity,
        val locator: EndpointLocator.DiscoveredService,
        val port: Int,
        val addresses: List<InetAddress>,
    )

    interface Listener {
        fun onServiceFound(candidate: Candidate) = Unit
        fun onServiceUpdated(candidate: Candidate) = Unit
        fun onServiceLost(identity: Identity) = Unit
        fun onFailure(failure: Failure) = Unit
    }

    private enum class Lifecycle { NEW, STARTING, ACTIVE, FAILED, CLOSED }

    private class ServiceEntry(
        val identity: Identity,
        val operation: Operation,
        var handle: AutoCloseable? = null,
        var candidate: Candidate? = null,
    )

    private val lock = Any()
    private var lifecycle = Lifecycle.NEW
    private var discoveryHandle: AutoCloseable? = null
    private val services = LinkedHashMap<Identity, ServiceEntry>()

    /** Starts at most once. A synchronous platform failure is reported through [Listener]. */
    fun start() {
        synchronized(lock) {
            if (lifecycle != Lifecycle.NEW) return
            lifecycle = Lifecycle.STARTING
        }

        val handle = try {
            platform.discover(object : SshNsdPlatform.DiscoveryCallbacks {
                override fun onServiceFound(advertisement: SshServiceAdvertisement) = found(advertisement)
                override fun onServiceLost(advertisement: SshServiceAdvertisement) = lost(advertisement)
                override fun onFailure(failure: Failure) = discoveryFailure(failure)
            })
        } catch (failure: RuntimeException) {
            discoveryFailure(Failure(Operation.START, cause = failure))
            return
        }

        val closeImmediately = synchronized(lock) {
            if (lifecycle == Lifecycle.STARTING) {
                lifecycle = Lifecycle.ACTIVE
                discoveryHandle = handle
                false
            } else {
                // close() or an asynchronous start failure may win before discover() returns.
                true
            }
        }
        if (closeImmediately) closeHandle(handle, Operation.STOP)
    }

    override fun close() {
        val handles = synchronized(lock) {
            if (lifecycle == Lifecycle.CLOSED) return
            lifecycle = Lifecycle.CLOSED
            buildList {
                services.values.forEach { entry -> entry.handle?.let { add(it to entry.operation) } }
                services.clear()
                discoveryHandle?.let { add(it to Operation.STOP) }
                discoveryHandle = null
            }
        }
        // Service callbacks are detached before browsing stops.
        handles.forEach { (handle, operation) -> closeHandle(handle, operation) }
    }

    private fun found(advertisement: SshServiceAdvertisement) {
        val identity = try {
            identity(advertisement)
        } catch (failure: RuntimeException) {
            report(Failure(Operation.SERVICE_INFO, cause = failure))
            return
        }
        val entry = synchronized(lock) {
            if (!isDiscovering() || services.containsKey(identity)) return
            ServiceEntry(
                identity = identity,
                operation = if (platform.supportsServiceInfoCallbacks) Operation.SERVICE_INFO else Operation.RESOLVE,
            ).also { services[identity] = it }
        }

        val callbacks = object : SshNsdPlatform.ServiceCallbacks {
            override fun onServiceUpdated(service: SshServiceInfo) = updated(entry, service)
            override fun onServiceLost() = lost(entry)
            override fun onFailure(failure: Failure) = serviceFailure(entry, failure)
        }
        val handle = try {
            if (platform.supportsServiceInfoCallbacks) {
                platform.watchServiceInfo(advertisement, callbacks)
            } else {
                platform.resolve(advertisement, callbacks)
            }
        } catch (failure: RuntimeException) {
            serviceFailure(entry, Failure(entry.operation, cause = failure))
            return
        }

        val closeImmediately = synchronized(lock) {
            if (isDiscovering() && services[identity] === entry) {
                entry.handle = handle
                false
            } else {
                // The service may have disappeared, or the owner may have closed, during setup.
                true
            }
        }
        if (closeImmediately) closeHandle(handle, entry.operation)
    }

    private fun lost(advertisement: SshServiceAdvertisement) {
        val key = try {
            identity(advertisement)
        } catch (failure: RuntimeException) {
            report(Failure(Operation.SERVICE_INFO, cause = failure))
            return
        }
        val entry = synchronized(lock) { services.remove(key) } ?: return
        remove(entry)
    }

    private fun lost(entry: ServiceEntry) {
        val removed = synchronized(lock) {
            if (services[entry.identity] !== entry) false
            else {
                services.remove(entry.identity)
                true
            }
        }
        if (removed) remove(entry)
    }

    private fun remove(entry: ServiceEntry) {
        entry.handle?.let { closeHandle(it, entry.operation) }
        synchronized(lock) {
            if (isDiscovering()) entry.candidate?.let { safely { listener.onServiceLost(it.identity) } }
        }
    }

    private fun updated(entry: ServiceEntry, service: SshServiceInfo) {
        val candidate = try {
            candidate(entry, service)
        } catch (failure: RuntimeException) {
            serviceFailure(entry, Failure(entry.operation, cause = failure))
            return
        }
        synchronized(lock) {
            if (!isDiscovering() || services[entry.identity] !== entry) return
            val previous = entry.candidate
            if (previous == candidate) return
            entry.candidate = candidate
            safely {
                if (previous == null) listener.onServiceFound(candidate)
                else listener.onServiceUpdated(candidate)
            }
        }
    }

    private fun serviceFailure(entry: ServiceEntry, failure: Failure) {
        val removed = synchronized(lock) {
            if (services[entry.identity] !== entry) false
            else {
                services.remove(entry.identity)
                true
            }
        }
        if (!removed) return
        entry.handle?.let { closeHandle(it, entry.operation) }
        report(failure)
    }

    private fun discoveryFailure(failure: Failure) {
        if (failure.operation != Operation.START && failure.operation != Operation.PERMISSION) {
            report(failure)
            return
        }
        val handles = synchronized(lock) {
            if (lifecycle == Lifecycle.CLOSED || lifecycle == Lifecycle.FAILED) return
            lifecycle = Lifecycle.FAILED
            buildList {
                services.values.forEach { entry -> entry.handle?.let { add(it to entry.operation) } }
                services.clear()
                discoveryHandle?.let { add(it to Operation.STOP) }
                discoveryHandle = null
            }
        }
        handles.forEach { (handle, operation) -> closeHandle(handle, operation) }
        report(failure)
    }

    private fun candidate(entry: ServiceEntry, service: SshServiceInfo): Candidate {
        val serviceIdentity = identity(service.advertisement)
        require(serviceIdentity == entry.identity) { "Resolved service identity changed" }
        val stableTarget = service.stableTargetHostname?.let { hostname ->
            try {
                EndpointHost(hostname).takeIf { it.kind == EndpointHost.Kind.DNS }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val locator = EndpointLocator.DiscoveredService(
            service.advertisement.instanceName,
            service.advertisement.serviceType,
            service.advertisement.domain,
            stableTarget,
        )
        // Reuse the existing endpoint contract for port validation; discovery adds no identity rules.
        Endpoint(locator, service.port)
        return Candidate(entry.identity, locator, service.port, uniqueAddresses(service.addresses))
    }

    private fun identity(advertisement: SshServiceAdvertisement): Identity {
        require(advertisement.networkIdentity.isNotBlank()) { "Missing discovery network identity" }
        val locator = EndpointLocator.DiscoveredService(
            advertisement.instanceName,
            advertisement.serviceType,
            advertisement.domain,
        )
        return Identity(
            advertisement.networkIdentity,
            locator.normalizedInstanceName,
            locator.normalizedServiceType,
            locator.normalizedDomain,
        )
    }

    private fun uniqueAddresses(addresses: List<InetAddress>): List<InetAddress> {
        val unique = LinkedHashMap<AddressKey, InetAddress>()
        addresses.forEach { address ->
            val scoped = address as? Inet6Address
            unique.putIfAbsent(
                AddressKey(address.address.toList(), scoped?.scopeId ?: -1, scoped?.scopedInterface?.name),
                address,
            )
        }
        return unique.values.toList()
    }

    private fun closeHandle(handle: AutoCloseable, operation: Operation) {
        try {
            handle.close()
        } catch (failure: Exception) {
            report(Failure(operation, cause = failure))
        }
    }

    private fun report(failure: Failure) {
        safely { listener.onFailure(failure) }
    }

    private inline fun safely(callback: () -> Unit) {
        try {
            callback()
        } catch (_: RuntimeException) {
            // Client callbacks must not escape into Android's NSD callback thread.
        }
    }

    private fun isDiscovering() = lifecycle == Lifecycle.STARTING || lifecycle == Lifecycle.ACTIVE

    private data class AddressKey(val bytes: List<Byte>, val scopeId: Int, val scopeInterface: String?)
}
