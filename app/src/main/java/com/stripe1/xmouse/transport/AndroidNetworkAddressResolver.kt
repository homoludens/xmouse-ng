package com.stripe1.xmouse.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier

/** Fresh hostname lookup and socket routing on the active, permission-checked Android network. */
class AndroidNetworkAddressResolver internal constructor(
    private val connectivity: ConnectivityManager,
    private val localNetworkPermission: LocalNetworkPermissionCoordinator,
    private val fallback: ((Network, String, () -> Unit) -> Array<InetAddress>)? = null,
) : SshAddressResolver {
    constructor(context: Context, localNetworkPermission: LocalNetworkPermissionCoordinator) : this(
        requireNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java)),
        localNetworkPermission,
    )

    override fun resolve(host: String, current: BooleanSupplier): SshAddressResolver.Resolution {
        checkAttempt(current)
        checkAccess()
        val network = connectivity.activeNetwork ?: throw UnknownHostException()
        val checkCurrent = {
            checkAttempt(current)
            checkAccess()
            if (connectivity.activeNetwork != network) throw SshAddressResolver.NetworkChangedException()
        }
        val resolved = try {
            network.getAllByName(host).also { if (it.isEmpty()) throw UnknownHostException() }
        } catch (unresolved: UnknownHostException) {
            checkCurrent()
            if (MdnsAddressCodec.localName(host) == null) throw unresolved
            try {
                fallback?.invoke(network, host, checkCurrent) ?: QueryOnlyMdnsResolver(
                    open = { AndroidMdnsSocket.open(connectivity, network) },
                ).resolve(host, checkCurrent)
            } catch (failure: IOException) {
                checkCurrent()
                if (failure is SshAddressResolver.ResolutionTimeoutException ||
                    failure is SshAddressResolver.AccessDeniedException ||
                    failure is SshAddressResolver.NetworkChangedException
                ) throw failure
                throw UnknownHostException() // Socket/setup failure is still a resolution failure.
            }
        }
        checkCurrent()

        val candidates = boundedUnique(resolved)
        if (candidates.isEmpty()) throw UnknownHostException()
        return object : SshAddressResolver.Resolution {
            override fun candidates(): List<InetAddress> = candidates

            override fun isCurrent(): Boolean = try {
                current.asBoolean && localNetworkPermission.accessFailure() == null &&
                    connectivity.activeNetwork == network
            } catch (_: RuntimeException) {
                false
            }

            override fun checkCurrent() {
                checkAttempt(current)
                checkAccess()
                if (connectivity.activeNetwork != network) throw SshAddressResolver.NetworkChangedException()
            }

            override fun bind(socket: java.net.Socket) {
                checkCurrent()
                network.bindSocket(socket)
                checkCurrent()
            }
        }
    }

    private fun checkAccess() {
        if (localNetworkPermission.accessFailure() != null) {
            throw SshAddressResolver.AccessDeniedException()
        }
    }

    private fun checkAttempt(current: BooleanSupplier) {
        if (!current.asBoolean) throw CancellationException()
    }

    private fun boundedUnique(addresses: Array<InetAddress>): List<InetAddress> {
        val unique = LinkedHashMap<AddressKey, InetAddress>()
        for (address in addresses) {
            val key = AddressKey(
                address.address.toList(),
                (address as? Inet6Address)?.scopeId ?: -1,
                (address as? Inet6Address)?.scopedInterface?.name,
            )
            unique.putIfAbsent(key, address)
            if (unique.size == SshAddressResolver.MAX_CANDIDATES) break
        }
        return unique.values.toList()
    }

    private data class AddressKey(val bytes: List<Byte>, val scopeId: Int, val scopeInterface: String?)
}
