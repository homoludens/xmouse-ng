package com.stripe1.xmouse.session

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.stripe1.xmouse.connection.ConnectionFailure
import com.stripe1.xmouse.discovery.AndroidSshServiceAddressLookup
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import com.stripe1.xmouse.secrets.AndroidCredentials
import com.stripe1.xmouse.secrets.CredentialRepository
import com.stripe1.xmouse.transport.AndroidNetworkAddressResolver
import com.stripe1.xmouse.transport.JschSshBackend
import com.stripe1.xmouse.trust.AndroidHostTrust
import com.stripe1.xmouse.trust.HostTrustPolicy
import java.util.concurrent.Executor

/** Composition root for the next started/bound service, NOT an Activity singleton or lifecycle hook. */
class AndroidSessionOwner private constructor(
    private val credentials: CredentialRepository,
    override val interactions: SessionInteractions,
    override val controller: SessionController,
) : SessionServiceOwner, AutoCloseable {
    /** Caller captures an auto-connect permit BEFORE this asynchronous operation when applicable.
     * Completion is worker-only; a detached screen must not retain or apply an obsolete selection. */
    override fun selectedHost(shell: String, initialCommand: String?, completion: (SessionHost?) -> Unit) {
        credentials.execute {
            val host = try {
                credentials.prepare() // Preserves all existing migration/key-loss/re-entry contracts.
                val snapshot = credentials.authenticationSelection()
                SessionHost(snapshot.host.endpoint(),
                    snapshot, shell, initialCommand)
            } catch (_: Exception) { null }
            completion(host)
        }
    }
    override fun close() = controller.close()
    companion object {
        @JvmStatic fun create(context: Context): AndroidSessionOwner {
            val app = context.applicationContext
            val repository = AndroidCredentials.get(app)
            val localNetworkPermission = LocalNetworkPermissionCoordinator.forContext(app)
            val serviceLookup = AndroidSshServiceAddressLookup(app)
            val addressResolver = AndroidNetworkAddressResolver(app, localNetworkPermission, serviceLookup)
            val main = Handler(Looper.getMainLooper())
            val interactions = SessionInteractions(Executor { main.post(it) })
            val provider = SessionCredentialProvider { selection, prompts, current, completion ->
                repository.execute {
                    if (!current.asBoolean) completion.complete(null, null)
                    else try { repository.authenticate(selection, prompts, completion) }
                    catch (_: Exception) { completion.complete(null, null) }
                }
            }
            val factory = SshSessionAttemptFactory(provider, AndroidHostTrust.open(app), interactions,
                backendFactory = { trust, trusted -> JschSshBackend(HostTrustPolicy(trust, trusted), addressResolver) },
                diagnostics = SshDiagnostics(AndroidSshDiagnosticSink),
                permissionFailure = {
                    val internet = app.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
                    if (!internet) ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED)
                    else localNetworkPermission.accessFailure()
                })
            return AndroidSessionOwner(repository, interactions, SessionController(factory))
        }
    }
}
