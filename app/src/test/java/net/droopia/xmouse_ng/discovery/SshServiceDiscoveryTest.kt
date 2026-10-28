package net.droopia.xmouse_ng.discovery

import net.droopia.xmouse_ng.connection.ConnectionFailure
import net.droopia.xmouse_ng.connection.Endpoint
import net.droopia.xmouse_ng.connection.EndpointLocator
import net.droopia.xmouse_ng.permissions.LocalNetworkPermissionCoordinator
import net.droopia.xmouse_ng.secrets.CredentialHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

private fun serviceIdentity(advertisement: SshServiceAdvertisement): SshServiceDiscovery.Identity {
    val locator = EndpointLocator.DiscoveredService(
        advertisement.instanceName,
        advertisement.serviceType,
        advertisement.domain,
    )
    return SshServiceDiscovery.Identity(
        advertisement.networkIdentity,
        locator.normalizedInstanceName,
        locator.normalizedServiceType,
        locator.normalizedDomain,
    )
}

class SshServiceDiscoveryTest {
    private class RecordingListener : SshServiceDiscovery.Listener {
        val found = mutableListOf<SshServiceDiscovery.Candidate>()
        val updated = mutableListOf<SshServiceDiscovery.Candidate>()
        val lost = mutableListOf<SshServiceDiscovery.Identity>()
        val failures = mutableListOf<SshServiceDiscovery.Failure>()

        override fun onServiceFound(candidate: SshServiceDiscovery.Candidate) { found += candidate }
        override fun onServiceUpdated(candidate: SshServiceDiscovery.Candidate) { updated += candidate }
        override fun onServiceLost(identity: SshServiceDiscovery.Identity) { lost += identity }
        override fun onFailure(failure: SshServiceDiscovery.Failure) { failures += failure }
    }

    private class FakeHandle(private val failure: RuntimeException? = null) : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount++
            failure?.let { throw it }
        }
    }

    /** Fake NSD operations expose the same callbacks as legacy browse/resolve and API-34 watches. */
    private class FakeNsd(override val supportsServiceInfoCallbacks: Boolean) : SshNsdPlatform {
        var startFailure: RuntimeException? = null
        var stopFailure: RuntimeException? = null
        var failServiceInfoRegistration = false
        var discoveryCallbacks: SshNsdPlatform.DiscoveryCallbacks? = null
        var discoveryHandle: FakeHandle? = null
        val serviceCallbacks = linkedMapOf<SshServiceDiscovery.Identity, SshNsdPlatform.ServiceCallbacks>()
        val serviceHandles = linkedMapOf<SshServiceDiscovery.Identity, FakeHandle>()

        override fun discover(callbacks: SshNsdPlatform.DiscoveryCallbacks): AutoCloseable {
            startFailure?.let { throw it }
            discoveryCallbacks = callbacks
            return FakeHandle(stopFailure).also { discoveryHandle = it }
        }

        override fun resolve(advertisement: SshServiceAdvertisement, callbacks: SshNsdPlatform.ServiceCallbacks) =
            register(advertisement, callbacks)

        override fun watchServiceInfo(
            advertisement: SshServiceAdvertisement,
            callbacks: SshNsdPlatform.ServiceCallbacks,
        ): AutoCloseable {
            val handle = register(advertisement, callbacks)
            if (failServiceInfoRegistration) {
                callbacks.onFailure(SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.SERVICE_INFO, 17))
            }
            return handle
        }

        private fun register(
            advertisement: SshServiceAdvertisement,
            callbacks: SshNsdPlatform.ServiceCallbacks,
        ): FakeHandle {
            val identity = serviceIdentity(advertisement)
            serviceCallbacks[identity] = callbacks
            return FakeHandle().also { serviceHandles[identity] = it }
        }

        fun found(advertisement: SshServiceAdvertisement) = discoveryCallbacks!!.onServiceFound(advertisement)
        fun discoveryLost(advertisement: SshServiceAdvertisement) = discoveryCallbacks!!.onServiceLost(advertisement)
        fun serviceLost(identity: SshServiceDiscovery.Identity) = serviceCallbacks[identity]!!.onServiceLost()
        fun update(identity: SshServiceDiscovery.Identity, info: SshServiceInfo) =
            serviceCallbacks[identity]!!.onServiceUpdated(info)
    }

    @Test
    fun bothApiPathsDeduplicateResultsKeepNetworkAndAddressDetailsAndRemoveLostServices() {
        for (modernApi in listOf(false, true)) {
            val platform = FakeNsd(modernApi)
            val listener = RecordingListener()
            val discovery = SshServiceDiscovery(platform, listener)
            discovery.start()

            val first = advertisement("Office", "network-a")
            val sameServiceOnAnotherNetwork = advertisement("Office", "network-b")
            val firstKey = serviceIdentity(first)
            val secondKey = serviceIdentity(sameServiceOnAnotherNetwork)

            platform.found(first)
            platform.found(first)
            platform.found(sameServiceOnAnotherNetwork)
            assertEquals(2, platform.serviceCallbacks.size)

            val addresses = listOf(
                InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 20)),
                Inet6Address.getByAddress(null, byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), 7),
            )
            val firstInfo = SshServiceInfo(first, 2222, addresses, "office.local")
            platform.update(firstKey, firstInfo)
            platform.update(firstKey, firstInfo) // Repeated service-info callback is not another result.
            platform.update(secondKey, firstInfo.copy(advertisement = sameServiceOnAnotherNetwork))

            assertEquals(2, listener.found.size)
            assertTrue(listener.updated.isEmpty())
            val candidate = listener.found.first { it.identity == firstKey }
            assertEquals(2222, candidate.port)
            assertEquals(listOf("192.0.2.20", "fe80:0:0:0:0:0:0:1%7"), candidate.addresses.map { it.hostAddress })
            assertEquals(7, (candidate.addresses[1] as Inet6Address).scopeId)
            assertEquals("office.local", candidate.locator.stableTargetHostname?.original)
            assertFalse(listener.found[0].identity == listener.found[1].identity)

            platform.update(firstKey, firstInfo.copy(port = 2200))
            assertEquals(1, listener.updated.size)
            assertEquals(2200, listener.updated.single().port)

            if (modernApi) platform.serviceLost(firstKey) else platform.discoveryLost(first)
            assertEquals(listOf(firstKey), listener.lost)
            assertEquals(1, platform.serviceHandles[firstKey]!!.closeCount)

            discovery.close()
            assertEquals(1, platform.discoveryHandle!!.closeCount)
            assertEquals(1, platform.serviceHandles[secondKey]!!.closeCount)
            // A late result after close cannot republish a candidate.
            platform.update(secondKey, firstInfo.copy(advertisement = sameServiceOnAnotherNetwork))
            assertEquals(2, listener.found.size)
        }
    }

    @Test
    fun reopenedServiceLocatorUsesFreshAddressesWithoutChangingSavedPortOrTrustIdentity() {
        val locator = EndpointLocator.DiscoveredService("Office workstation", "_ssh._tcp", "local")
        val profile = CredentialHost(12, "Office workstation", null, "user", "2222", "opaque", false,
            serviceLocator = locator)
        val savedEndpoint = profile.endpoint()
        val platform = FakeNsd(supportsServiceInfoCallbacks = false)
        val listener = RecordingListener()
        val discovery = SshServiceDiscovery(platform, listener)
        discovery.start()

        val ad = advertisement("Office workstation", "network-after-reopen")
        val key = serviceIdentity(ad)
        platform.found(ad)
        platform.update(key, SshServiceInfo(ad, 2222, listOf(InetAddress.getByName("192.0.2.20"))))
        val initial = listener.found.single()
        platform.update(key, SshServiceInfo(ad, 2222, listOf(InetAddress.getByName("192.0.2.99"))))
        val refreshed = listener.updated.single()
        val reopenedCandidate = discovery.matchingCandidates(
            profile.endpoint().locator as EndpointLocator.DiscoveredService,
        ).single()

        assertEquals(listOf("192.0.2.20"), initial.addresses.map { it.hostAddress })
        assertEquals(listOf("192.0.2.99"), refreshed.addresses.map { it.hostAddress })
        assertEquals(refreshed, reopenedCandidate)
        assertNotEquals(initial.addresses, refreshed.addresses)
        assertEquals(locator, profile.serviceLocator)
        assertEquals(locator, profile.endpoint().locator)
        assertEquals(2222, profile.endpoint().port)
        assertEquals(savedEndpoint.identity, Endpoint(refreshed.locator, refreshed.port).identity)
        assertNull(profile.host)
        discovery.close()
    }

    @Test
    fun oneShotLookupUsesOnlyTheCurrentNetworkCandidateAndClosesDiscovery() {
        val locator = EndpointLocator.DiscoveredService("Office workstation", "_ssh._tcp", "local")
        val platform = FakeNsd(supportsServiceInfoCallbacks = false)
        val lookup = AndroidSshServiceAddressLookup(
            discoveryFor = { listener -> SshServiceDiscovery(platform, listener) },
            pollMillis = 10,
        )
        val current = AtomicBoolean(true)
        val future = FutureTask {
            lookup.lookup(locator, "network-current", BooleanSupplier { current.get() }) {
                if (!current.get()) throw CancellationException()
            }
        }
        Thread(future, "test-service-lookup").apply { isDaemon = true; start() }
        await { platform.discoveryCallbacks != null }

        val stale = advertisement(locator.instanceName, "network-stale")
        val staleIdentity = serviceIdentity(stale)
        platform.found(stale)
        platform.update(staleIdentity, SshServiceInfo(stale, 2222, listOf(InetAddress.getByName("192.0.2.1"))))
        assertFalse(future.isDone)

        val fresh = advertisement(locator.instanceName, "network-current")
        val freshIdentity = serviceIdentity(fresh)
        platform.found(fresh)
        val addresses = listOf(InetAddress.getByName("192.0.2.20"), InetAddress.getByName("2001:db8::20"))
        platform.update(freshIdentity, SshServiceInfo(fresh, 2200, addresses))

        assertEquals(addresses, future.get(2, TimeUnit.SECONDS))
        assertEquals("one-shot discovery closes after receiving a current candidate", 1, platform.discoveryHandle!!.closeCount)
    }

    @Test
    fun cancelledOneShotLookupStopsDiscoveryAndCannotReturnALateCandidate() {
        val locator = EndpointLocator.DiscoveredService("Office workstation", "_ssh._tcp", "local")
        val platform = FakeNsd(supportsServiceInfoCallbacks = false)
        val lookup = AndroidSshServiceAddressLookup(
            discoveryFor = { listener -> SshServiceDiscovery(platform, listener) },
            pollMillis = 10,
        )
        val current = AtomicBoolean(true)
        val future = FutureTask {
            lookup.lookup(locator, "network-current", BooleanSupplier { current.get() }) {
                if (!current.get()) throw CancellationException()
            }
        }
        Thread(future, "test-cancelled-service-lookup").apply { isDaemon = true; start() }
        await { platform.discoveryCallbacks != null }
        current.set(false)

        val failure = try {
            future.get(2, TimeUnit.SECONDS)
            null
        } catch (expected: java.util.concurrent.ExecutionException) {
            expected.cause
        }
        assertTrue(failure is CancellationException)
        assertEquals(1, platform.discoveryHandle!!.closeCount)

        val late = advertisement(locator.instanceName, "network-current")
        platform.found(late)
        assertTrue(future.isDone)
        assertTrue(platform.serviceCallbacks.isEmpty())
    }

    @Test
    fun startAndStopFailuresAreReportedAndCloseRemainsIdempotent() {
        val startPlatform = FakeNsd(supportsServiceInfoCallbacks = false).apply {
            startFailure = IllegalStateException("start rejected")
        }
        val startListener = RecordingListener()
        val failedStart = SshServiceDiscovery(startPlatform, startListener)
        failedStart.start()
        failedStart.close()
        assertEquals(SshServiceDiscovery.Operation.START, startListener.failures.single().operation)
        assertNotNull(startListener.failures.single().cause)
        assertEquals(null, startPlatform.discoveryHandle)

        val callbackStartPlatform = FakeNsd(supportsServiceInfoCallbacks = false)
        val callbackStartListener = RecordingListener()
        val callbackStart = SshServiceDiscovery(callbackStartPlatform, callbackStartListener)
        callbackStart.start()
        callbackStartPlatform.discoveryCallbacks!!.onFailure(
            SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.START, errorCode = 3),
        )
        assertEquals(SshServiceDiscovery.Operation.START, callbackStartListener.failures.single().operation)
        assertEquals(1, callbackStartPlatform.discoveryHandle!!.closeCount)
        callbackStart.close()

        val stopPlatform = FakeNsd(supportsServiceInfoCallbacks = false).apply {
            stopFailure = IllegalStateException("stop rejected")
        }
        val stopListener = RecordingListener()
        val active = SshServiceDiscovery(stopPlatform, stopListener)
        active.start()
        active.close()
        active.close()
        assertEquals(1, stopPlatform.discoveryHandle!!.closeCount)
        assertEquals(SshServiceDiscovery.Operation.STOP, stopListener.failures.single().operation)
        assertNotNull(stopListener.failures.single().cause)

        val registrationPlatform = FakeNsd(supportsServiceInfoCallbacks = true).apply {
            failServiceInfoRegistration = true
        }
        val registrationListener = RecordingListener()
        val registration = SshServiceDiscovery(registrationPlatform, registrationListener)
        registration.start()
        registrationPlatform.found(advertisement("Office", "network-a"))
        val identity = serviceIdentity(advertisement("Office", "network-a"))
        assertEquals(SshServiceDiscovery.Operation.SERVICE_INFO, registrationListener.failures.single().operation)
        assertEquals(1, registrationPlatform.serviceHandles[identity]!!.closeCount)
        assertTrue(registrationListener.found.isEmpty())
        registration.close()
    }

    @Test
    fun permissionGateSkipsDeniedApi37ButAllowsGrantAndEarlierAndroid() {
        val scenarios = listOf(
            Triple(37, false, 0),
            Triple(37, true, 1),
            Triple(36, false, 1),
        )
        for ((apiLevel, granted, expectedStarts) in scenarios) {
            var starts = 0
            val failures = mutableListOf<SshServiceDiscovery.Failure>()
            val permission = LocalNetworkPermissionCoordinator({ apiLevel }, { granted })
            val callbacks = object : SshNsdPlatform.DiscoveryCallbacks {
                override fun onServiceFound(advertisement: SshServiceAdvertisement) = Unit
                override fun onServiceLost(advertisement: SshServiceAdvertisement) = Unit
                override fun onFailure(failure: SshServiceDiscovery.Failure) { failures += failure }
            }

            val handle = SshDiscoveryPermissionGate.start(permission, callbacks) {
                starts++
                FakeHandle()
            }
            assertEquals(expectedStarts, starts)
            if (expectedStarts == 0) {
                assertEquals(SshServiceDiscovery.Operation.PERMISSION, failures.single().operation)
                assertEquals(
                    ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED),
                    failures.single().connectionFailure,
                )
            } else {
                assertTrue(failures.isEmpty())
            }
            handle.close()
        }
    }

    @Test
    fun legacyNetworkIdentityUsesStartSnapshotAndModernNetworkRemainsAuthoritative() {
        var activeNetwork = "network-at-start"
        val snapshot = SshDiscoveryNetworkIdentity.capture { activeNetwork }
        activeNetwork = "network-at-callback"

        assertEquals("network-at-start", snapshot.identityFor(null))
        assertEquals("service-info-network", snapshot.identityFor("service-info-network"))

        val noNetworkSnapshot = SshDiscoveryNetworkIdentity.capture { null }
        activeNetwork = "network-after-start"
        assertEquals("no-active-network", noNetworkSnapshot.identityFor(null))
    }

    private fun advertisement(name: String, network: String) =
        SshServiceAdvertisement(name, "_ssh._tcp.", "local.", network)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
