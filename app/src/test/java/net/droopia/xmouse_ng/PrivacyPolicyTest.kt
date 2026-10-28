package net.droopia.xmouse_ng

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyTest {
    @Test fun hostableAndBundledPolicyCopiesAreByteIdentical() {
        val root = repositoryRoot()
        assertArrayEquals(
            File(root, "docs/privacy-policy.md").readBytes(),
            File(root, "app/src/main/res/raw/privacy_policy.txt").readBytes(),
        )
    }

    @Test fun policyScreenUsesBundledNativeSelectableTextWithoutBrowserOrFetch() {
        val root = repositoryRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val activity = File(root, "app/src/main/java/net/droopia/xmouse_ng/PrivacyPolicyActivity.kt").readText()
        val layout = File(root, "app/src/main/res/layout/activity_privacy_policy.xml").readText()
        val settings = File(root, "app/src/main/java/net/droopia/xmouse_ng/UnifiedSettingsFragment.kt").readText()

        assertTrue(manifest.contains("android:name=\".PrivacyPolicyActivity\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(activity.contains("openRawResource(R.raw.privacy_policy)"))
        assertTrue(activity.contains("SystemWindowInsets.applyTo"))
        assertTrue(layout.contains("<ScrollView"))
        assertTrue(layout.contains("android:textIsSelectable=\"true\""))
        assertTrue(settings.contains("PrivacyPolicyActivity::class.java"))
        assertFalse("policy display has no WebView", activity.contains("WebView"))
        assertFalse("policy display has no browser intent", activity.contains("ACTION_VIEW"))
        assertFalse("policy display has no HTTP client", activity.contains("HttpURLConnection") || activity.contains("OkHttp"))
    }

    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "app/src/main/res/raw/privacy_policy.txt").isFile }
}
