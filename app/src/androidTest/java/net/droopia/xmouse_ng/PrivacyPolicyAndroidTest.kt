package net.droopia.xmouse_ng

import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PrivacyPolicyAndroidTest {
    @Test fun aboutEntryOpensBundledScrollableSelectablePolicyOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(PrivacyPolicyActivity::class.java.name, null, false)
        val context = instrumentation.targetContext
        val aboutIntent = Intent(context, NativeSettingsActivity::class.java)
            .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_ABOUT)

        try {
            ActivityScenario.launch<NativeSettingsActivity>(aboutIntent).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager
                        .findFragmentById(R.id.settings_preference_container) as UnifiedSettingsFragment
                    val entry = fragment.findSettingPreference("ng_about_privacy_policy")
                    assertNotNull(entry)
                    assertTrue(entry!!.isSelectable)
                    assertTrue(entry.isEnabled)
                    val recycler = requireNotNull(fragment.view)
                        .findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                    val row = (0 until recycler.childCount).map(recycler::getChildAt).firstOrNull {
                        it.findViewById<TextView>(android.R.id.title)?.text?.toString() ==
                            activity.getString(R.string.ng_about_privacy_policy_title)
                    }
                    assertNotNull("privacy entry is visible in About", row)
                    assertTrue(requireNotNull(row).performClick())
                }

                val policyActivity = monitor.waitForActivityWithTimeout(5_000) as? PrivacyPolicyActivity
                assertNotNull("About opens the native policy screen", policyActivity)
                try {
                    instrumentation.runOnMainSync {
                        val screen = policyActivity!!.findViewById<ScrollView>(R.id.privacy_policy_scroll)
                        val layoutParams = screen.layoutParams as LinearLayout.LayoutParams
                        layoutParams.height = (320 * policyActivity.resources.displayMetrics.density).roundToInt()
                        layoutParams.weight = 0f
                        screen.layoutParams = layoutParams
                    }
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        val screen = policyActivity!!.findViewById<ScrollView>(R.id.privacy_policy_scroll)
                        val text = policyActivity.findViewById<TextView>(R.id.privacy_policy_text)
                        val expected = context.resources.openRawResource(R.raw.privacy_policy)
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }

                        assertTrue("policy is selectable", text.isTextSelectable)
                        assertEquals(expected, text.text.toString())
                        assertTrue("small viewport exercises scrolling", screen.canScrollVertically(1))
                        screen.scrollTo(0, text.height)
                        assertTrue("policy content extends beyond the viewport", text.height > screen.height)
                        assertTrue("policy scroll position advances", screen.scrollY > 0)
                    }
                } finally {
                    instrumentation.runOnMainSync { policyActivity?.finish() }
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
