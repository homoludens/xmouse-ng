package com.stripe1.xmouse.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier

/** Fresh hostname lookup and socket routing on the active, permission-checked Android network. */
class AndroidNetworkAddressResolver(
    context: Context,
    private val localNetworkPermission: LocalNetworkPermissionCoordinator,
) : SshAddressResolver {
    private val connectivity = requireNotNull(
        context.applicationContext.getSystemService(ConnectivityManager::class.java),
    )

    override fun resolve(host: String, current: BooleanSupplier): SshAddressResolver.Resolution {
        checkAttempt(current)
        checkAccess()
        val network = connectivity.activeNetwork ?: throw UnknownHostException()
        val resolved = network.getAllByName(host)
        checkAttempt(current)
        checkAccess()
        if (connectivity.activeNetwork != network) throw SshAddressResolver.NetworkChangedException()

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
