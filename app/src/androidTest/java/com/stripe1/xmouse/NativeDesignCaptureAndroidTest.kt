package com.stripe1.xmouse

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.stripe1.xmouse.layout.LayoutButton
import com.stripe1.xmouse.layout.LayoutProfile
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in screenshots of production XML only, not a credential/probe Activity or saved user data. */
@RunWith(AndroidJUnit4::class)
class NativeDesignCaptureAndroidTest {
    @Test fun captureNarrowLargeFontNativeFormsInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureNativeDesign") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "redesign-captures").apply { mkdirs() }
        val device = UiDevice.getInstance(instrumentation)
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            // UI idleness can precede the software emulator's RenderThread/compositor.
            // Let the newly replaced View tree reach the display before taking evidence.
            Thread.sleep(500)
            assertTrue("screenshot $name", device.takeScreenshot(File(directory, "$name.png")))
        }
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            ActivityScenario.launch<MainInsetsFixtureActivity>(MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark)).use { scenario ->
                capture("fixture-touchpad-$theme")
                scenario.onActivity { it.navigation.select(ShellNavigation.Destination.KEYS) }
                capture("fixture-keys-$theme")
                scenario.onActivity { activity ->
                    activity.selectedActionProfile = LayoutProfile(id = "capture-only", name = "Synthetic layout", buttons = listOf(
                        LayoutButton("shortcut", "Fixture shortcut", "key Alt+F4", 5, "#E0EFE8"),
                        LayoutButton("advanced", "Fixture custom", "xdotool mousemove 7 9", 7, "#E8EFFA"),
                        LayoutButton("wide", "Fixture wide", "key Super_L", 10, "#FFFFFF"),
                    ))
                    activity.navigation.select(ShellNavigation.Destination.ACTIONS)
                }
                capture("fixture-actions-$theme")
                scenario.onActivity { activity ->
                    activity.setContentView(R.layout.activity_computer_editor)
                    SystemWindowInsets.applyToAndConsume(activity.window, activity.findViewById(R.id.computer_editor_root))
                    activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.computer_editor_toolbar).title = "Computer form fixture"
                    activity.findViewById<View>(R.id.computer_port_field).visibility = View.VISIBLE
                    assertEquals("", activity.findViewById<EditText>(R.id.new_host_password).text.toString())
                }
                capture("fixture-computer-editor-$theme-top")
                scenario.onActivity { activity ->
                    val root = activity.findViewById<android.view.ViewGroup>(R.id.computer_editor_root)
                    (root.getChildAt(1) as ScrollView).apply { isSmoothScrollingEnabled = false; fullScroll(View.FOCUS_DOWN) }
                }
                capture("fixture-computer-editor-$theme-bottom")
                scenario.onActivity { activity ->
                    val input = activity.findViewById<EditText>(R.id.new_host_username)
                    input.requestFocus()
                    activity.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                var imeVisible = false
                while (!imeVisible && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.findViewById(R.id.computer_editor_root))
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    }
                    if (!imeVisible) Thread.sleep(50)
                }
                assertTrue("real system IME must be visible in the IME capture", imeVisible)
                capture("fixture-computer-editor-$theme-ime")
                device.pressBack()
            }
            ActivityScenario.launch<MainInsetsFixtureActivity>(MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContentView(R.layout.activity_action_editor)
                    SystemWindowInsets.applyToAndConsume(activity.window, activity.findViewById(R.id.action_editor_root))
                }
                capture("fixture-action-editor-$theme-top")
                scenario.onActivity { activity ->
                    activity.findViewById<ScrollView>(R.id.action_editor_scroll).apply {
                        isSmoothScrollingEnabled = false; fullScroll(View.FOCUS_DOWN)
                    }
                }
                capture("fixture-action-editor-$theme-bottom")
            }
        }
    }
}
