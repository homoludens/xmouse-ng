package com.stripe1.xmouse

import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
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
            val safeArea = activity.findViewById<FrameLayout>(R.id.main_safe_area)
            assertEquals(expected.left, safeArea.paddingLeft)
            assertEquals(expected.top, safeArea.paddingTop)
            assertEquals(expected.right, safeArea.paddingRight)
            assertEquals(expected.bottom, safeArea.paddingBottom)
        }
    }

    private fun assertMainControlsReachable(scenario: ActivityScenario<MainInsetsFixtureActivity>) {
        scenario.onActivity { activity ->
            val safeArea = activity.findViewById<FrameLayout>(R.id.main_safe_area)
            val shell = activity.findViewById<View>(R.id.native_shell)
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            val bottom = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            assertEquals(safeArea.paddingLeft, shell.left)
            assertEquals(safeArea.paddingTop, shell.top)
            assertEquals(safeArea.width - safeArea.paddingRight, shell.right)
            assertEquals(safeArea.height - safeArea.paddingBottom, shell.bottom)

            val safeLocation = IntArray(2)
            val toolbarLocation = IntArray(2)
            val bottomLocation = IntArray(2)
            safeArea.getLocationOnScreen(safeLocation)
            toolbar.getLocationOnScreen(toolbarLocation)
            bottom.getLocationOnScreen(bottomLocation)
            val safeLeft = safeLocation[0] + safeArea.paddingLeft
            val safeRight = safeLocation[0] + safeArea.width - safeArea.paddingRight
            val safeTop = safeLocation[1] + safeArea.paddingTop
            val safeBottom = safeLocation[1] + safeArea.height - safeArea.paddingBottom
            assertTrue("toolbar starts left of the safe area", toolbarLocation[0] >= safeLeft)
            assertTrue(
                "toolbar extends right of the safe area",
                toolbarLocation[0] + toolbar.width <= safeRight)
            assertTrue("toolbar begins above the safe area", toolbarLocation[1] >= safeTop)
            assertTrue(
                "toolbar extends below the safe area",
                toolbarLocation[1] + toolbar.height <= safeBottom)
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
            val safeArea = activity.findViewById<FrameLayout>(R.id.main_safe_area)
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
}
