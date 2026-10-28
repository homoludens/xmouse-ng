package net.droopia.xmouse_ng.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import net.droopia.xmouse_ng.permissions.LocalNetworkPermissionCoordinator
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Android NSD bridge. API 26-33 resolve each legacy DiscoveryListener result; API 34+ watches it. */
internal class AndroidSshNsdPlatform(context: Context) : SshNsdPlatform {
    private val appContext = context.applicationContext
    private val manager = requireNotNull(appContext.getSystemService(NsdManager::class.java))
    private val connectivity = requireNotNull(appContext.getSystemService(ConnectivityManager::class.java))
    private val localNetworkPermission = LocalNetworkPermissionCoordinator.forContext(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    override val supportsServiceInfoCallbacks: Boolean
        get() = Build.VERSION.SDK_INT >= API_SERVICE_INFO_CALLBACK

    override fun discover(callbacks: SshNsdPlatform.DiscoveryCallbacks): AutoCloseable =
        SshDiscoveryPermissionGate.start(localNetworkPermission, callbacks) {
            startDiscovery(callbacks)
        }

    private fun startDiscovery(callbacks: SshNsdPlatform.DiscoveryCallbacks): AutoCloseable {
        val networkIdentityAtStart = SshDiscoveryNetworkIdentity.capture {
            connectivity.activeNetwork?.networkHandle?.toString()
        }
        val callbackRef = AtomicReference<SshNsdPlatform.DiscoveryCallbacks?>(callbacks)
        val stopRequested = AtomicBoolean(false)
        val stopAttempts = AtomicInteger(0)

        lateinit var discoveryListener: NsdManager.DiscoveryListener

        fun attemptStop() {
            stopAttempts.incrementAndGet()
            try {
                manager.stopServiceDiscovery(discoveryListener)
            } catch (failure: RuntimeException) {
                callbackRef.get()?.onFailure(SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.STOP, cause = failure))
                if (stopAttempts.get() >= MAX_STOP_ATTEMPTS) callbackRef.set(null)
                else attemptStop()
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                val callback = callbackRef.getAndSet(null)
                if (!stopRequested.get() && callback != null) {
                    callback.onFailure(
                        SshServiceDiscovery.Failure(
                            SshServiceDiscovery.Operation.START,
                            cause = IllegalStateException("Service discovery stopped unexpectedly"),
                        ),
                    )
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val callback = callbackRef.get() ?: return
                try {
                    callback.onServiceFound(advertisement(serviceInfo, networkIdentityAtStart))
                } catch (failure: RuntimeException) {
                    callback.onFailure(SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.SERVICE_INFO, cause = failure))
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val callback = callbackRef.get() ?: return
                try {
                    callback.onServiceLost(advertisement(serviceInfo, networkIdentityAtStart))
                } catch (failure: RuntimeException) {
                    callback.onFailure(SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.SERVICE_INFO, cause = failure))
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                callbackRef.get()?.onFailure(
                    SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.START, errorCode),
                )
                if (stopRequested.compareAndSet(false, true)) attemptStop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                callbackRef.get()?.onFailure(
                    SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.STOP, errorCode),
                )
                if (stopAttempts.get() < MAX_STOP_ATTEMPTS) attemptStop() else callbackRef.set(null)
            }
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (failure: RuntimeException) {
            callbackRef.set(null)
            try {
                manager.stopServiceDiscovery(discoveryListener)
            } catch (cleanup: RuntimeException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }

        return AutoCloseable {
            if (stopRequested.compareAndSet(false, true)) attemptStop()
        }
    }

    @Suppress("DEPRECATION")
    override fun resolve(
        advertisement: SshServiceAdvertisement,
        callbacks: SshNsdPlatform.ServiceCallbacks,
    ): AutoCloseable {
        val source = advertisement.platformHandle as? NsdServiceInfo
            ?: throw IllegalArgumentException("Missing platform service information")
        val callbackRef = AtomicReference<SshNsdPlatform.ServiceCallbacks?>(callbacks)
        val active = AtomicBoolean(true)
        lateinit var resolveListener: NsdManager.ResolveListener

        resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (!active.compareAndSet(true, false)) return
                val callback = callbackRef.getAndSet(null) ?: return
                try {
                    callback.onServiceUpdated(details(serviceInfo, advertisement.networkIdentity))
                } catch (failure: RuntimeException) {
                    callback.onFailure(SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.RESOLVE, cause = failure))
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (!active.compareAndSet(true, false)) return
                callbackRef.getAndSet(null)?.onFailure(
                    SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.RESOLVE, errorCode),
                )
            }

            override fun onResolutionStopped(serviceInfo: NsdServiceInfo) {
                active.set(false)
                callbackRef.set(null)
            }

            override fun onStopResolutionFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                callbackRef.getAndSet(null)?.onFailure(
                    SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.RESOLVE, errorCode),
                )
            }
        }

        try {
            manager.resolveService(source, resolveListener)
        } catch (failure: RuntimeException) {
            active.set(false)
            callbackRef.set(null)
            throw failure
        }

        return AutoCloseable {
            if (active.compareAndSet(true, false)) {
                // Clear owner references even on API 26, where resolution cannot be cancelled.
                callbackRef.set(null)
            }
        }
    }

    @RequiresApi(API_SERVICE_INFO_CALLBACK)
    override fun watchServiceInfo(
        advertisement: SshServiceAdvertisement,
        callbacks: SshNsdPlatform.ServiceCallbacks,
    ): AutoCloseable {
        check(Build.VERSION.SDK_INT >= API_SERVICE_INFO_CALLBACK) { "Service-info callbacks require API 34" }
        val source = advertisement.platformHandle as? NsdServiceInfo
            ?: throw IllegalArgumentException("Missing platform service information")
        val callbackRef = AtomicReference<SshNsdPlatform.ServiceCallbacks?>(callbacks)
        val active = AtomicBoolean(true)
        val unregisterRequested = AtomicBoolean(false)
        val unregisterAttempts = AtomicInteger(0)
        lateinit var serviceInfoCallback: NsdManager.ServiceInfoCallback

        fun unregister() {
            if (unregisterRequested.compareAndSet(false, true)) {
                try {
                    manager.unregisterServiceInfoCallback(serviceInfoCallback)
                } catch (failure: RuntimeException) {
                    if (unregisterAttempts.incrementAndGet() >= MAX_UNREGISTER_ATTEMPTS) throw failure
                    unregisterRequested.set(false)
                    try {
                        unregister()
                    } catch (retry: RuntimeException) {
                        failure.addSuppressed(retry)
                    }
                    throw failure
                }
            }
        }

        serviceInfoCallback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (!active.compareAndSet(true, false)) return
                val callback = callbackRef.getAndSet(null)
                var cleanupFailure: RuntimeException? = null
                try {
                    unregister()
                } catch (failure: RuntimeException) {
                    cleanupFailure = failure
                }
                callback?.onFailure(
                    SshServiceDiscovery.Failure(
                        SshServiceDiscovery.Operation.SERVICE_INFO,
                        errorCode,
                        cleanupFailure,
                    ),
                )
            }

            override fun onServiceInfoCallbackUnregistered() {
                val wasActive = active.getAndSet(false)
                val callback = callbackRef.getAndSet(null)
                if (wasActive) callback?.onFailure(
                    SshServiceDiscovery.Failure(
                        SshServiceDiscovery.Operation.SERVICE_INFO,
                        cause = IllegalStateException("Service-info callback was unregistered"),
                    ),
                )
            }

            override fun onServiceLost() {
                if (active.get()) callbackRef.get()?.onServiceLost()
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (!active.get()) return
                val callback = callbackRef.get() ?: return
                try {
                    callback.onServiceUpdated(details(serviceInfo, advertisement.networkIdentity))
                } catch (failure: RuntimeException) {
                    callback.onFailure(SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.SERVICE_INFO, cause = failure))
                }
            }
        }

        try {
            manager.registerServiceInfoCallback(source, mainExecutor, serviceInfoCallback)
        } catch (failure: RuntimeException) {
            active.set(false)
            try {
                unregister()
            } catch (cleanup: RuntimeException) {
                failure.addSuppressed(cleanup)
            } finally {
                callbackRef.set(null)
            }
            throw failure
        }

        return AutoCloseable {
            if (active.getAndSet(false)) {
                try {
                    unregister()
                } finally {
                    callbackRef.set(null)
                }
            }
        }
    }

    private fun advertisement(info: NsdServiceInfo, networkIdentity: SshDiscoveryNetworkIdentity) =
        SshServiceAdvertisement(
            instanceName = info.serviceName,
            serviceType = info.serviceType,
            // Android's DNS-SD browse API browses the local mDNS domain and does not expose a domain field.
            domain = LOCAL_DOMAIN,
            networkIdentity = networkIdentity.identityFor(platformNetworkIdentity(info)),
            platformHandle = info,
        )

    private fun details(info: NsdServiceInfo, fallbackNetwork: String): SshServiceInfo {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= API_SERVICE_INFO_CALLBACK) {
            serviceInfoAddresses(info)
        } else {
            legacyAddress(info)
        }
        val hostname = if (Build.VERSION.SDK_INT >= API_STABLE_HOSTNAME) stableHostname(info) else null
        return SshServiceInfo(
            advertisement = advertisement(info, SshDiscoveryNetworkIdentity.fixed(fallbackNetwork)),
            port = info.port,
            addresses = addresses,
            stableTargetHostname = hostname,
        )
    }

    private fun platformNetworkIdentity(info: NsdServiceInfo?): String? {
        val scopedNetwork: Network? = if (Build.VERSION.SDK_INT >= API_NETWORK_AWARE_NSD) serviceNetwork(info) else null
        return scopedNetwork?.networkHandle?.toString()
    }

    @RequiresApi(API_SERVICE_INFO_CALLBACK)
    private fun serviceInfoAddresses(info: NsdServiceInfo): List<InetAddress> = info.hostAddresses

    @Suppress("DEPRECATION")
    private fun legacyAddress(info: NsdServiceInfo): List<InetAddress> = listOfNotNull(info.host)

    @RequiresApi(API_STABLE_HOSTNAME)
    private fun stableHostname(info: NsdServiceInfo): String? = info.hostname

    @RequiresApi(API_NETWORK_AWARE_NSD)
    private fun serviceNetwork(info: NsdServiceInfo?): Network? = info?.network

    private companion object {
        const val SERVICE_TYPE = "_ssh._tcp"
        const val LOCAL_DOMAIN = "local"
        const val API_NETWORK_AWARE_NSD = 33
        const val API_SERVICE_INFO_CALLBACK = 34
        const val API_STABLE_HOSTNAME = 36
        const val MAX_STOP_ATTEMPTS = 2
        const val MAX_UNREGISTER_ATTEMPTS = 2
    }
}
