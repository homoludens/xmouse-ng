package com.stripe1.xmouse

import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Geometry regression for the real main-screen DrawerLayout; no app/session data is opened. */
@RunWith(AndroidJUnit4::class)
class MainActivityInsetsAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun controlsAndDrawerStayInsideInsetsAcrossImeAndLandscapeChanges() {
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
            assertDrawerReachable(scenario)
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
            val drawer = activity.findViewById<DrawerLayout>(R.id.drawer_layout)
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            val fab = activity.findViewById<FloatingActionButton>(R.id.fab)
            assertEquals(safeArea.paddingLeft, drawer.left)
            assertEquals(safeArea.paddingTop, drawer.top)
            assertEquals(safeArea.width - safeArea.paddingRight, drawer.right)
            assertEquals(safeArea.height - safeArea.paddingBottom, drawer.bottom)

            val safeLocation = IntArray(2)
            val toolbarLocation = IntArray(2)
            val fabLocation = IntArray(2)
            safeArea.getLocationOnScreen(safeLocation)
            toolbar.getLocationOnScreen(toolbarLocation)
            fab.getLocationOnScreen(fabLocation)
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
            assertTrue("bottom control starts left of the safe area", fabLocation[0] >= safeLeft)
            assertTrue(
                "bottom control extends right of the safe area",
                fabLocation[0] + fab.width <= safeRight)
            assertTrue(
                "bottom control is obscured by the navigation bar or IME",
                fabLocation[1] + fab.height <= safeBottom)
        }
    }

    private fun assertDrawerReachable(scenario: ActivityScenario<MainInsetsFixtureActivity>) {
        scenario.onActivity { activity ->
            val safeArea = activity.findViewById<FrameLayout>(R.id.main_safe_area)
            val drawer = activity.findViewById<DrawerLayout>(R.id.drawer_layout)
            val navigation = activity.findViewById<NavigationView>(R.id.nav_view)
            drawer.openDrawer(navigation, false)
            assertTrue(drawer.isDrawerOpen(navigation))

            val safeLocation = IntArray(2)
            val drawerLocation = IntArray(2)
            safeArea.getLocationOnScreen(safeLocation)
            navigation.getLocationOnScreen(drawerLocation)
            assertTrue(
                "drawer begins above the safe area",
                drawerLocation[1] >= safeLocation[1] + safeArea.paddingTop)
            assertTrue(
                "drawer extends below the safe area",
                drawerLocation[1] + navigation.height <=
                    safeLocation[1] + safeArea.height - safeArea.paddingBottom)
            drawer.closeDrawer(navigation, false)
        }
    }
}
