package com.stripe1.xmouse.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.stripe1.xmouse.discovery.AndroidSshServiceAddressLookup
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import com.stripe1.xmouse.secrets.*
import com.stripe1.xmouse.transport.AndroidNetworkAddressResolver
import com.stripe1.xmouse.transport.JschSshBackend
import com.stripe1.xmouse.trust.AndroidHostTrust
import com.stripe1.xmouse.trust.HostTrustPolicy
import java.util.concurrent.Executor

/** Activity-local composition, using application context only. No migration, key import or service. */
class AndroidComputerProbe(context: Context) {
    private val app = context.applicationContext
    private val repository = AndroidCredentials.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val dispatcher = Executor { main.post(it) }
    val interactions = SessionInteractions(dispatcher)
    private val permission = LocalNetworkPermissionCoordinator.forContext(app)
    private val resolver = AndroidNetworkAddressResolver(app, permission, AndroidSshServiceAddressLookup(app))
    private var password: ProbePassword? = null
    private val provider = SessionCredentialProvider { candidate, prompts, current, completion ->
        val pending = password
        repository.execute {
            val bytes = pending?.take()
            try {
                if (!current.asBoolean) completion.complete(null, null)
                else {
                    // Capture the real global choice on the repository worker, without selecting host.
                    val selection = repository.authenticationSelection(candidate.host)
                    // SessionHost already captures auth mode for this attempt. A changed global mode
                    // requires a new deliberate test, rather than silently using another mode.
                    if (selection.useKey != candidate.useKey) completion.complete(null, null)
                    else repository.authenticateProbe(selection, bytes, prompts, completion)
                }
            } catch (_: Exception) { completion.complete(null, null) }
            finally { bytes?.fill(0); pending?.close() }
        }
    }
    private val factory = SshSessionAttemptFactory(provider, AndroidHostTrust.open(app), interactions,
        backendFactory = { trust, trusted -> JschSshBackend(HostTrustPolicy(trust, trusted), resolver) },
        permissionFailure = { permission.accessFailure() })
    val owner = ConnectionProbe(factory, dispatcher) { password?.close() }

    fun start(host: CredentialHost, useKey: Boolean, replacement: ByteArray?): Boolean {
        if (owner.busy || owner.blocked) { replacement?.fill(0); return false }
        password?.close()
        password = ProbePassword(replacement)
        val selection = AuthenticationSelection(host, useKey, null, false, false, null, false, null)
        return owner.start(SessionHost(host.endpoint(), selection, "", null)).also { if (!it) password?.close() }
    }
    fun cancel() { password?.close(); owner.cancel() }
    fun stop() { password?.close(); owner.detach(); interactions.detach() }
}

/** Ownership transfers from the field to one queued credential read; cancel wipes an unclaimed copy. */
internal class ProbePassword(private var bytes: ByteArray?) : AutoCloseable {
    @Synchronized fun take(): ByteArray? = bytes.also { bytes = null }
    @Synchronized override fun close() { bytes?.fill(0); bytes = null }
}
