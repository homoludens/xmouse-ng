package com.stripe1.xmouse.discovery

import android.content.Context
import com.stripe1.xmouse.connection.ConnectionFailure
import com.stripe1.xmouse.connection.EndpointLocator
import com.stripe1.xmouse.transport.SshAddressResolver
import com.stripe1.xmouse.transport.SshServiceCandidateLookup
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier

/** Reuses the 7.1 NSD adapter and returns only fresh candidates on the attempt's current network. */
internal class AndroidSshServiceAddressLookup internal constructor(
    private val discoveryFor: (SshServiceDiscovery.Listener) -> SshServiceDiscovery,
    private val pollMillis: Long = POLL_MILLIS,
) : SshServiceCandidateLookup {
    constructor(context: Context) : this({ listener -> SshServiceDiscovery(context, listener) }, POLL_MILLIS)

    init { require(pollMillis > 0) }

    override fun lookup(
        locator: EndpointLocator.DiscoveredService,
        networkIdentity: String,
        current: BooleanSupplier,
        checkCurrent: () -> Unit,
    ): List<InetAddress> {
        val completed = CountDownLatch(1)
        val addresses = AtomicReference<List<InetAddress>?>(null)
        val failure = AtomicReference<SshServiceDiscovery.Failure?>(null)

        fun matches(candidate: SshServiceDiscovery.Candidate): Boolean =
            candidate.identity.networkIdentity == networkIdentity &&
                candidate.locator.normalizedInstanceName == locator.normalizedInstanceName &&
                candidate.locator.normalizedServiceType == locator.normalizedServiceType &&
                candidate.locator.normalizedDomain == locator.normalizedDomain

        fun accept(candidate: SshServiceDiscovery.Candidate) {
            if (!current.asBoolean || !matches(candidate) || candidate.addresses.isEmpty()) return
            addresses.compareAndSet(null, candidate.addresses.toList())
            completed.countDown()
        }

        val discovery = discoveryFor(object : SshServiceDiscovery.Listener {
            override fun onServiceFound(candidate: SshServiceDiscovery.Candidate) = accept(candidate)
            override fun onServiceUpdated(candidate: SshServiceDiscovery.Candidate) = accept(candidate)
            override fun onFailure(problem: SshServiceDiscovery.Failure) {
                // An unrelated service may fail resolution while the requested locator is still
                // available. Only a failed browse or permission gate terminates this lookup.
                if (problem.operation != SshServiceDiscovery.Operation.START &&
                    problem.operation != SshServiceDiscovery.Operation.PERMISSION
                ) return
                failure.compareAndSet(null, problem)
                completed.countDown()
            }
        })

        try {
            checkCurrent()
            if (!current.asBoolean) throw CancellationException()
            discovery.start()
            while (!completed.await(pollMillis, TimeUnit.MILLISECONDS)) {
                if (!current.asBoolean) throw CancellationException()
                checkCurrent()
            }
            checkCurrent()
            if (!current.asBoolean) throw CancellationException()
            failure.get()?.let { problem ->
                if (problem.operation == SshServiceDiscovery.Operation.PERMISSION ||
                    problem.connectionFailure is ConnectionFailure.Permission
                ) throw SshAddressResolver.AccessDeniedException()
                throw UnknownHostException()
            }
            return addresses.get() ?: throw UnknownHostException()
        } finally {
            discovery.close()
        }
    }

    private companion object { const val POLL_MILLIS = 100L }
}
