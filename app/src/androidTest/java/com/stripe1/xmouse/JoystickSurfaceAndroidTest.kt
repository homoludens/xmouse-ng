package com.stripe1.xmouse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measures and draws the real joystick View in the isolated production-layout fixture. */
@RunWith(AndroidJUnit4::class)
class JoystickSurfaceAndroidTest {
    @Test
    fun realJoystickHasPaintableAreaBelowItsButtonsInPortraitAndShortLayouts() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val controls = activity.findViewById<LinearLayout>(R.id.js)
                controls.visibility = View.VISIBLE
                val joystick = (0 until controls.childCount)
                    .map { controls.getChildAt(it) }
                    .filterIsInstance<MyJSView>()
                    .single()
                val button = activity.findViewById<View>(R.id.js_firstMouseButton)
                val toolbar = activity.findViewById<View>(R.id.toolbar)
                val toolbarLocation = IntArray(2)
                val buttonLocation = IntArray(2)
                toolbar.getLocationOnScreen(toolbarLocation)
                button.getLocationOnScreen(buttonLocation)
                assertTrue(
                    "joystick button row must be beneath the toolbar",
                    buttonLocation[1] >= toolbarLocation[1] + toolbar.height,
                )

                // Exercise the laid-out screen first, then deterministic portrait and short/landscape
                // bounds. The shared production attachment helper keeps fixture and Activity params equal.
                assertSurfacePaintable(controls, joystick, button)
                measureAndLayout(controls, dp(activity, 360), dp(activity, 640))
                assertSurfacePaintable(controls, joystick, button)
                measureAndLayout(controls, dp(activity, 640), dp(activity, 220))
                assertSurfacePaintable(controls, joystick, button)
            }
        }
    }

    private fun measureAndLayout(controls: LinearLayout, width: Int, height: Int) {
        controls.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        controls.layout(0, 0, width, height)
    }

    private fun assertSurfacePaintable(
        controls: LinearLayout,
        joystick: MyJSView,
        button: View,
    ) {
        assertTrue("joystick surface has no width", joystick.width > 0)
        assertTrue("joystick surface has no drawable height", joystick.height > 0)

        val buttonBounds = Rect()
        button.getDrawingRect(buttonBounds)
        controls.offsetDescendantRectToMyCoords(button, buttonBounds)
        assertTrue(
            "joystick surface must begin below the button row",
            buttonBounds.bottom <= joystick.top,
        )
        assertTrue(
            "joystick surface extends into the bottom padding",
            joystick.bottom <= controls.height - controls.paddingBottom,
        )

        val bitmap = Bitmap.createBitmap(joystick.width, joystick.height, Bitmap.Config.ARGB_8888)
        joystick.draw(Canvas(bitmap))
        val pixels = IntArray(joystick.width * joystick.height)
        bitmap.getPixels(pixels, 0, joystick.width, 0, 0, joystick.width, joystick.height)
        var minX = joystick.width
        var minY = joystick.height
        var maxX = -1
        var maxY = -1
        var paintedPixels = 0
        pixels.forEachIndexed { index, color ->
            if (color ushr 24 != 0) {
                val x = index % joystick.width
                val y = index / joystick.width
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                paintedPixels++
            }
        }
        assertTrue("joystick draws no visible pixels", paintedPixels > 0)
        assertTrue("joystick drawable bounds have no width", maxX > minX)
        assertTrue("joystick drawable bounds have no height", maxY > minY)
    }

    private fun dp(activity: MainInsetsFixtureActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
