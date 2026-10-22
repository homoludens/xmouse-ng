package com.stripe1.xmouse.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.stripe1.xmouse.connection.ConnectionFailure

/** Shared OS policy for direct local-network access; UI owners decide when it is appropriate to ask. */
class LocalNetworkPermissionCoordinator(
    private val apiLevel: () -> Int,
    private val permissionGranted: () -> Boolean,
) {
    enum class AccessState { NOT_REQUIRED, GRANTED, REQUEST_REQUIRED }

    fun state(): AccessState = when {
        apiLevel() < REQUIRED_API -> AccessState.NOT_REQUIRED
        permissionGranted() -> AccessState.GRANTED
        else -> AccessState.REQUEST_REQUIRED
    }

    fun requiresRuntimePermission(): Boolean = state() == AccessState.REQUEST_REQUIRED

    fun accessFailure(): ConnectionFailure.Permission? =
        if (requiresRuntimePermission()) ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED) else null

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val REQUIRED_API = 37

        @JvmStatic
        fun forContext(context: Context): LocalNetworkPermissionCoordinator {
            val app = context.applicationContext
            return LocalNetworkPermissionCoordinator(
                apiLevel = { Build.VERSION.SDK_INT },
                permissionGranted = {
                    app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
                },
            )
        }
    }
}
