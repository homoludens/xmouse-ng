package com.stripe1.xmouse.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.secrets.AndroidCredentials
import com.stripe1.xmouse.secrets.CredentialRepository
import com.stripe1.xmouse.trust.AndroidHostTrust
import java.util.concurrent.Executor

/** Composition root for the next started/bound service, NOT an Activity singleton or lifecycle hook. */
class AndroidSessionOwner private constructor(
    private val credentials: CredentialRepository,
    val interactions: SessionInteractions,
    val controller: SessionController,
) : AutoCloseable {
    /** Caller captures an auto-connect permit BEFORE this asynchronous operation when applicable.
     * Completion is worker-only; a detached screen must not retain or apply an obsolete selection. */
    fun selectedHost(shell: String, initialCommand: String?, completion: (SessionHost?) -> Unit) {
        credentials.execute {
            val host = try {
                credentials.prepare() // Preserves all existing migration/key-loss/re-entry contracts.
                val snapshot = credentials.authenticationSelection()
                SessionHost(Endpoint.manual(requireNotNull(snapshot.host.host), requireNotNull(snapshot.host.port)),
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
            val main = Handler(Looper.getMainLooper())
            val interactions = SessionInteractions(Executor { main.post(it) })
            val provider = SessionCredentialProvider { selection, prompts, current, completion ->
                repository.execute {
                    if (!current.asBoolean) completion.complete(null, null)
                    else try { repository.authenticate(selection, prompts, completion) }
                    catch (_: Exception) { completion.complete(null, null) }
                }
            }
            val factory = SshSessionAttemptFactory(provider, AndroidHostTrust.open(app), interactions)
            return AndroidSessionOwner(repository, interactions, SessionController(factory))
        }
    }
}
