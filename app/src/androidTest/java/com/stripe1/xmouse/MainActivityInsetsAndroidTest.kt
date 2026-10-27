package com.stripe1.xmouse

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Geometry regression for the real native shell; no app/session data is opened. */
@RunWith(AndroidJUnit4::class)
class MainActivityInsetsAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun headerAndBottomNavigationStayInsideInsetsAcrossImeAndLandscapeChanges() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()

            // Portrait: a cutout extends the top safe area and a visible IME extends the bottom.
            val portraitBars = Insets.of(0, 40, 0, 48)
            val portraitCutout = Insets.of(0, 72, 0, 0)
            val visibleIme = Insets.of(0, 0, 0, 360)
            dispatch(scenario, portraitBars, portraitCutout, visibleIme)
            assertPadding(scenario, Insets.of(0, 72, 0, 360))
            assertMainControlsReachable(scenario)

            // Re-delivery must use the original padding baseline rather than compound padding.
            dispatch(scenario, portraitBars, portraitCutout, visibleIme)
            assertPadding(scenario, Insets.of(0, 72, 0, 360))
            assertMainControlsReachable(scenario)

            // Hidden IME: retain the navigation-bar inset while restoring the smaller bottom edge.
            dispatch(scenario, portraitBars, portraitCutout, Insets.NONE)
            assertPadding(scenario, Insets.of(0, 72, 0, 48))
            assertMainControlsReachable(scenario)
            assertTouchpadControlsReachable(scenario)

            dispatch(scenario, portraitBars, portraitCutout, visibleIme)
            assertInlineTypingControlsReachable(scenario)
            dispatch(scenario, portraitBars, portraitCutout, Insets.NONE)

            // Landscape-shaped system/cutout insets exercise the physical left and right edges.
            val landscapeBars = Insets.of(24, 0, 32, 24)
            val landscapeCutout = Insets.of(48, 0, 16, 0)
            dispatch(scenario, landscapeBars, landscapeCutout, Insets.NONE)
            assertPadding(scenario, Insets.of(48, 0, 32, 24))
            assertMainControlsReachable(scenario)
            scenario.onActivity { it.navigation.open(ShellNavigation.Overlay.COMPUTERS) }
            instrumentation.waitForIdleSync()
            assertOverlayReachable(scenario)
        }
    }

    private fun dispatch(
        scenario: ActivityScenario<MainInsetsFixtureActivity>,
        bars: Insets,
        cutout: Insets,
        ime: Insets,
    ) {
        scenario.onActivity { activity ->
            val gestureInsets = Insets.of(16, 0, 20, 0)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
                .setInsets(WindowInsetsCompat.Type.ime(), ime)
                .setInsets(WindowInsetsCompat.Type.systemGestures(), gestureInsets)
                .build()
            val remaining = ViewCompat.dispatchApplyWindowInsets(
                activity.findViewById(R.id.main_safe_area), insets)
            assertEquals(Insets.NONE, remaining.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                assertEquals(
                    gestureInsets,
                    remaining.getInsets(WindowInsetsCompat.Type.systemGestures()))
            }
            activity.findViewById<View>(R.id.main_safe_area).requestLayout()
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertPadding(
        scenario: ActivityScenario<MainInsetsFixtureActivity>,
        expected: Insets,
    ) {
        scenario.onActivity { activity ->
            val safeArea = activity.findViewById<DrawerLayout>(R.id.main_safe_area)
            assertEquals(expected.left, safeArea.paddingLeft)
            assertEquals(expected.top, safeArea.paddingTop)
            assertEquals(expected.right, safeArea.paddingRight)
            assertEquals(expected.bottom, safeArea.paddingBottom)
        }
    }

    private fun assertMainControlsReachable(scenario: ActivityScenario<MainInsetsFixtureActivity>) {
        scenario.onActivity { activity ->
            val safeArea = activity.findViewById<DrawerLayout>(R.id.main_safe_area)
            val shell = activity.findViewById<View>(R.id.native_shell)
            val bottom = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            assertEquals(safeArea.paddingLeft, shell.left)
            assertEquals(safeArea.paddingTop, shell.top)
            assertEquals(safeArea.width - safeArea.paddingRight, shell.right)
            assertEquals(safeArea.height - safeArea.paddingBottom, shell.bottom)

            val safeLocation = IntArray(2)
            val bottomLocation = IntArray(2)
            val header = activity.findViewById<View>(R.id.shell_header)
            safeArea.getLocationOnScreen(safeLocation)
            bottom.getLocationOnScreen(bottomLocation)
            val safeLeft = safeLocation[0] + safeArea.paddingLeft
            val safeRight = safeLocation[0] + safeArea.width - safeArea.paddingRight
            val safeTop = safeLocation[1] + safeArea.paddingTop
            val safeBottom = safeLocation[1] + safeArea.height - safeArea.paddingBottom
            val headerLocation = IntArray(2)
            header.getLocationOnScreen(headerLocation)
            assertTrue("header stays inside the safe height", headerLocation[1] >= safeTop &&
                headerLocation[1] + header.height <= safeBottom)
            for (id in listOf(R.id.header_menu, R.id.computer_selector, R.id.header_connection_button)) {
                val control = activity.findViewById<View>(id)
                val location = IntArray(2)
                control.getLocationOnScreen(location)
                assertTrue("header control $id stays inside the safe width", location[0] >= safeLeft &&
                    location[0] + control.width <= safeRight)
                assertTrue("header control $id stays inside the safe height", location[1] >= safeTop &&
                    location[1] >= headerLocation[1] &&
                    location[1] + control.height <= headerLocation[1] + header.height)
            }
            assertTrue("bottom control starts left of the safe area", bottomLocation[0] >= safeLeft)
            assertTrue(
                "bottom control extends right of the safe area",
                bottomLocation[0] + bottom.width <= safeRight)
            assertTrue(
                "bottom control is obscured by the navigation bar or IME",
                bottomLocation[1] + bottom.height <= safeBottom)
        }
    }

    private fun assertOverlayReachable(scenario: ActivityScenario<MainInsetsFixtureActivity>) {
        scenario.onActivity { activity ->
            val safeArea = activity.findViewById<DrawerLayout>(R.id.main_safe_area)
            val overlay = activity.findViewById<View>(R.id.shell_overlay)
            assertTrue(overlay.isShown)

            val safeLocation = IntArray(2)
            val overlayLocation = IntArray(2)
            safeArea.getLocationOnScreen(safeLocation)
            overlay.getLocationOnScreen(overlayLocation)
            assertTrue(
                "overlay begins above the safe area",
                overlayLocation[1] >= safeLocation[1] + safeArea.paddingTop)
            assertTrue(
                "overlay extends below the safe area",
                overlayLocation[1] + overlay.height <=
                    safeLocation[1] + safeArea.height - safeArea.paddingBottom)
            activity.navigation.closeTop()
        }
    }

    private fun assertTouchpadControlsReachable(scenario: ActivityScenario<MainInsetsFixtureActivity>) {
        scenario.onActivity { activity ->
            val safeArea = activity.findViewById<DrawerLayout>(R.id.main_safe_area)
            val bottom = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            val safeLocation = IntArray(2)
            val bottomLocation = IntArray(2)
            safeArea.getLocationOnScreen(safeLocation)
            bottom.getLocationOnScreen(bottomLocation)
            for (id in listOf(
                R.id.mouse_surface_host, R.id.firstMouseButton, R.id.secondMouseButton, R.id.thirdMouseButton,
                R.id.fourthMouseButton, R.id.fifthMouseButton,
                R.id.typing_key_escape, R.id.typing_key_tab, R.id.typing_key_backspace, R.id.typing_key_enter,
            )) {
                val control = activity.findViewById<View>(id)
                val location = IntArray(2)
                control.getLocationOnScreen(location)
                assertTrue("touchpad control $id remains inside the safe width",
                    location[0] >= safeLocation[0] + safeArea.paddingLeft &&
                        location[0] + control.width <= safeLocation[0] + safeArea.width - safeArea.paddingRight)
                assertTrue("touchpad control $id remains above bottom navigation",
                    location[1] >= safeLocation[1] + safeArea.paddingTop && location[1] + control.height <= bottomLocation[1])
                assertTrue("touchpad control $id keeps a 48dp target",
                    control.height >= 48 * activity.resources.displayMetrics.density - 1)
            }
        }
    }

    private fun assertInlineTypingControlsReachable(scenario: ActivityScenario<MainInsetsFixtureActivity>) {
        scenario.onActivity { activity ->
            activity.findViewById<ScrollView>(R.id.touchpad_destination).apply {
                isSmoothScrollingEnabled = false
                fullScroll(View.FOCUS_DOWN)
            }
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val typing = activity.findViewById<ScrollView>(R.id.touchpad_destination)
            val root = activity.findViewById<ViewGroup>(R.id.native_shell)
            val input = activity.findViewById<View>(R.id.keyboard_input)
            val send = activity.findViewById<View>(R.id.keyboard_send)
            val quickKeys = activity.findViewById<View>(R.id.touchpad_quick_keys)
            val composer = activity.findViewById<View>(R.id.typing_dock)
            val bottom = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            val typingLocation = IntArray(2)
            val bottomLocation = IntArray(2)
            val quickKeysLocation = IntArray(2)
            val inputLocation = IntArray(2)
            val composerLocation = IntArray(2)
            typing.getLocationOnScreen(typingLocation)
            bottom.getLocationOnScreen(bottomLocation)
            quickKeys.getLocationOnScreen(quickKeysLocation)
            input.getLocationOnScreen(inputLocation)
            composer.getLocationOnScreen(composerLocation)
            assertTrue("inline panel stays above the IME-adjusted navigation",
                typingLocation[1] + typing.height <= bottomLocation[1])
            assertTrue("the shared multiline field remains visible after IME resize", input.isShown && input.height > 0)
            assertTrue("quick keys are above the shared composer", quickKeysLocation[1] < inputLocation[1])
            assertTrue("shared composer stays above the IME-adjusted navigation",
                composerLocation[1] + composer.height <= bottomLocation[1])
            val sendBounds = android.graphics.Rect()
            send.getDrawingRect(sendBounds)
            root.offsetDescendantRectToMyCoords(send, sendBounds)
            assertTrue("Send remains visible beside the field when the IME is open", sendBounds.bottom <= root.height)
            for (id in listOf(
                R.id.typing_key_escape, R.id.typing_key_tab, R.id.typing_key_backspace,
                R.id.typing_key_enter, R.id.keyboard_send,
            )) {
                assertTrue("typing action $id retains a 48dp target",
                    activity.findViewById<View>(id).height >= 48 * activity.resources.displayMetrics.density - 1)
            }
        }
    }
}
