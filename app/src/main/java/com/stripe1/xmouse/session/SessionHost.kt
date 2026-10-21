package com.stripe1.xmouse.session

import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.connection.EndpointLocator
import com.stripe1.xmouse.secrets.AuthenticationSelection

/** A requested profile, not a dial address. Secret references are opaque, never decrypted here. */
data class SessionHost @JvmOverloads constructor(
    val endpoint: Endpoint,
    val authentication: AuthenticationSelection,
    val shell: String = "",
    val initialCommand: String? = null,
) {
    init {
        require(!authentication.host.user.isNullOrBlank())
        require(Endpoint.parsePort(requireNotNull(authentication.host.port)) == endpoint.port)
        if (endpoint.locator is EndpointLocator.Manual) {
            require(Endpoint.manual(requireNotNull(authentication.host.host), endpoint.port).identity == endpoint.identity) {
                "Endpoint must match the captured credential profile"
            }
        }
    }

    // Display spelling/alias and transient DNS answers are not connection identity. Profile ID,
    // username, credential references and auth choices ARE: two profiles can share one SSH endpoint.
    internal fun sameRequest(other: SessionHost): Boolean = endpoint.identity == other.endpoint.identity &&
        authentication.copy(host = authentication.host.copy(alias = null, host = null, port = null)) ==
        other.authentication.copy(host = other.authentication.host.copy(alias = null, host = null, port = null)) &&
        shell == other.shell && initialCommand == other.initialCommand
}
