package com.stripe1.xmouse.secrets

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class StoragePermissionsAndroidTest {
    private val target get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun mergedAppManifestDoesNotRequestOrHoldBroadStoragePermissions() {
        StoragePermissionAssertions.assertNoBroadStoragePermissions(target)
    }
}

/** Checks the installed target app, not the instrumentation APK's separate test-only permissions. */
internal object StoragePermissionAssertions {
    private const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    private const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
    private const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
    private const val INTERNET = "android.permission.INTERNET"

    fun assertNoBroadStoragePermissions(context: Context) {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = packageInfo.requestedPermissions.orEmpty().toSet()

        assertFalse("App manifest requests READ_EXTERNAL_STORAGE", READ_EXTERNAL_STORAGE in requested)
        assertFalse("App manifest requests WRITE_EXTERNAL_STORAGE", WRITE_EXTERNAL_STORAGE in requested)
        assertFalse("App manifest requests MANAGE_EXTERNAL_STORAGE", MANAGE_EXTERNAL_STORAGE in requested)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(READ_EXTERNAL_STORAGE))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(WRITE_EXTERNAL_STORAGE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(MANAGE_EXTERNAL_STORAGE))
        }

        assertTrue("App manifest must retain INTERNET", INTERNET in requested)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(INTERNET))
    }
}
