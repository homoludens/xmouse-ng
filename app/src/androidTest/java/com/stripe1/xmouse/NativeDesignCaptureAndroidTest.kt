package com.stripe1.xmouse

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.EditorInfo
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
    @Test fun captureTouchpadComposerImeSendWithRealSystemKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureNativeDesign") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(instrumentation)
        val captureFile = File(
            context.getExternalFilesDir(null),
            "redesign-captures/fixture-touchpad-composer-ime-send-light.png",
        )
        captureFile.parentFile?.mkdirs()
        var tapX = 0
        var tapY = 0

        ActivityScenario.launch<MainInsetsFixtureActivity>(
            MainInsetsFixtureActivity.configuredIntent(context, 1.5f, false, widthDp = 320),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                assertTrue("fixture window is foregrounded before exercising the keyboard",
                    activity.window.decorView.hasWindowFocus())
                input.setText("Synthetic composer screenshot\n雪 café")
                assertTrue("shared composer can receive focus", input.requestFocus())
                val location = IntArray(2)
                input.getLocationOnScreen(location)
                tapX = location[0] + input.width / 2
                tapY = location[1] + input.height / 2
            }

            instrumentation.waitForIdleSync()
            assertTrue("a real tap focuses the composer and requests the system IME", device.click(tapX, tapY))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            var imeVisible = false
            while (!imeVisible && System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(
                        activity.findViewById(R.id.native_shell),
                    )?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
                if (!imeVisible) Thread.sleep(50)
            }
            assertTrue("real system IME is visible for the focused composer", imeVisible)
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                assertTrue("IME request leaves the shared composer focused", input.hasFocus())
                assertTrue("Android reports an active text input connection",
                    activity.getSystemService(InputMethodManager::class.java).isAcceptingText)
                val editorInfo = EditorInfo()
                assertNotNull("the focused fixture creates its real input connection",
                    input.onCreateInputConnection(editorInfo))
                assertEquals(EditorInfo.IME_ACTION_SEND,
                    editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
                assertEquals("multiline must not suppress the keyboard Send action", 0,
                    editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            Thread.sleep(500)
            assertTrue("fresh real-keyboard screenshot is retained on the emulator",
                device.takeScreenshot(captureFile))
            assertTrue(captureFile.isFile && captureFile.length() > 0)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "fixture-touchpad-composer-ime-send-light.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/XMouseNGFinal")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            )) { "MediaStore insert failed for the real-keyboard screenshot" }
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    captureFile.inputStream().use { input -> input.copyTo(output) }
                }
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                assertEquals("fresh keyboard screenshot is retained in Pictures", 1,
                    context.contentResolver.update(uri, published, null, null))
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }
    }

    @SuppressLint("SetTextI18n", "SourceLockedOrientationActivity")
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
            val screenshot = File(directory, "$name.png")
            assertTrue("screenshot $name", device.takeScreenshot(screenshot))
            assertTrue("MediaStore screenshot export requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "fixture-$name.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/XMouseNGFinal")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            )) { "MediaStore insert failed for $name" }
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    screenshot.inputStream().use { input -> input.copyTo(output) }
                }
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                assertEquals("screenshot $name is published to the owned emulator", 1,
                    context.contentResolver.update(uri, published, null, null))
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }
        fun awaitOrientation(scenario: ActivityScenario<MainInsetsFixtureActivity>, expected: Int) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            var reached = false
            while (!reached && System.nanoTime() < deadline) {
                scenario.onActivity { reached = it.resources.configuration.orientation == expected }
                if (!reached) Thread.sleep(50)
            }
            assertTrue("orientation $expected", reached)
            instrumentation.waitForIdleSync()
        }
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            ActivityScenario.launch<MainInsetsFixtureActivity>(
                MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark, widthDp = 320),
            ).use { scenario ->
                var composerTapX = 0
                var composerTapY = 0
                capture("fixture-touchpad-$theme")
                scenario.onActivity { activity ->
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                awaitOrientation(scenario, android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                capture("fixture-touchpad-landscape-$theme")
                scenario.onActivity { activity ->
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                awaitOrientation(scenario, android.content.res.Configuration.ORIENTATION_PORTRAIT)
                scenario.onActivity { activity ->
                    activity.findViewById<ScrollView>(R.id.touchpad_destination).apply {
                        isSmoothScrollingEnabled = false
                        fullScroll(View.FOCUS_DOWN)
                    }
                }
                capture("fixture-touchpad-typing-$theme")
                scenario.onActivity { activity ->
                    val input = activity.findViewById<EditText>(R.id.keyboard_input)
                    input.setText("Synthetic composer screenshot")
                    assertTrue("composer accepts focus before requesting the real IME", input.requestFocus())
                    val location = IntArray(2)
                    input.getLocationOnScreen(location)
                    composerTapX = location[0] + input.width / 2
                    composerTapY = location[1] + input.height / 2
                }
                instrumentation.waitForIdleSync()
                assertTrue("a real tap focuses the composer and requests the system IME",
                    device.click(composerTapX, composerTapY))
                val composerDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                var composerImeVisible = false
                while (!composerImeVisible && System.nanoTime() < composerDeadline) {
                    scenario.onActivity { activity ->
                        composerImeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(
                            activity.findViewById(R.id.native_shell),
                        )?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    }
                    if (!composerImeVisible) Thread.sleep(50)
                }
                assertTrue("real system IME must be visible above the shared composer", composerImeVisible)
                scenario.onActivity { activity ->
                    val input = activity.findViewById<EditText>(R.id.keyboard_input)
                    assertTrue("shared composer remains the focused IME target", input.hasFocus())
                    val editorInfo = EditorInfo()
                    assertNotNull("capture uses the real fixture input connection",
                        input.onCreateInputConnection(editorInfo))
                    assertEquals(EditorInfo.IME_ACTION_SEND,
                        editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
                    assertEquals("real IME capture must advertise Send", 0,
                        editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
                }
                capture("fixture-touchpad-composer-ime-$theme")
                scenario.onActivity { activity ->
                    activity.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.findViewById<EditText>(R.id.keyboard_input).windowToken, 0)
                    activity.findViewById<EditText>(R.id.keyboard_input).clearFocus()
                    activity.findViewById<View>(R.id.header_menu).performClick()
                }
                capture("fixture-navigation-drawer-$theme")
                scenario.onActivity { activity -> activity.findViewById<View>(R.id.drawer_computers).performClick() }
                capture("fixture-computers-sheet-$theme")
                scenario.onActivity { activity -> activity.findViewById<View>(R.id.overlay_close).performClick() }
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
                scenario.onActivity { activity ->
                    assertTrue("computer field remains the focused IME target",
                        activity.findViewById<EditText>(R.id.new_host_username).hasFocus())
                }
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
