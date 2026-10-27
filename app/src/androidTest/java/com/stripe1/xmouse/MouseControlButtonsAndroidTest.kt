package com.stripe1.xmouse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.pm.PackageManager
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Inflates the real control rows without constructing MainActivity or opening session/user data. */
@RunWith(AndroidJUnit4::class)
class MouseControlButtonsAndroidTest {
    @Test
    fun touchpadControlsRemainAvailableAndGyroJoystickSurfaceIsAbsent() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val mouseOrder = listOf(R.id.firstMouseButton, R.id.secondMouseButton, R.id.thirdMouseButton)
                assertPhysicalOrder(activity, mouseOrder)
                val surface = activity.findViewById<FrameLayout>(R.id.mouse_surface_host)
                val mouse = (0 until surface.childCount)
                    .map { surface.getChildAt(it) }.filterIsInstance<MyMouseView>().single()
                assertTrue("real touchpad has positive width", mouse.width > 0)
                assertTrue("real touchpad has positive height", mouse.height > 0)
                mouseOrder.forEach { id ->
                    val button = activity.findViewById<Button>(id)
                    val buttonLocation = IntArray(2)
                    val surfaceLocation = IntArray(2)
                    button.getLocationOnScreen(buttonLocation)
                    surface.getLocationOnScreen(surfaceLocation)
                    assertTrue("$id is below the real touch surface", buttonLocation[1] >= surfaceLocation[1] + surface.height)
                }

                val scrollUp = activity.findViewById<Button>(R.id.fourthMouseButton)
                val scrollDown = activity.findViewById<Button>(R.id.fifthMouseButton)
                assertAccessibleIconControl(scrollUp)
                assertAccessibleIconControl(scrollDown)
                val surfaceLocation = IntArray(2)
                val upLocation = IntArray(2)
                val downLocation = IntArray(2)
                surface.getLocationOnScreen(surfaceLocation)
                scrollUp.getLocationOnScreen(upLocation)
                scrollDown.getLocationOnScreen(downLocation)
                assertTrue("scroll actions remain inside the right strip", upLocation[0] + scrollUp.width == surfaceLocation[0] + surface.width)
                assertTrue("scroll up is above scroll down", upLocation[1] < downLocation[1])

                val actionButtonIds = listOf(
                    R.id.firstMouseButton,
                    R.id.secondMouseButton,
                    R.id.thirdMouseButton,
                    R.id.fourthMouseButton,
                    R.id.fifthMouseButton,
                )
                actionButtonIds.forEach { id ->
                    val button = activity.findViewById<Button>(id)
                    assertAccessibleIconControl(button)
                    assertTrue("mouse dispatch callback missing for $id", button.hasOnClickListeners())
                }

                assertDistinctIcons(
                    activity,
                    listOf(R.id.firstMouseButton, R.id.secondMouseButton, R.id.thirdMouseButton),
                )
                assertDistinctIcons(activity, listOf(R.id.fourthMouseButton, R.id.fifthMouseButton))
                listOf("gyroToggle", "jsToggle", "js_jsToggle", "js").forEach { name ->
                    assertEquals("removed control id $name must not be packaged", 0,
                        activity.resources.getIdentifier(name, "id", activity.packageName))
                }
                listOf("action_joystick").forEach { name ->
                    assertEquals("removed menu id $name must not be packaged", 0,
                        activity.resources.getIdentifier(name, "id", activity.packageName))
                }
                listOf("pref_gyro", "pref_js").forEach { name ->
                    assertEquals("removed Settings resource $name must not be packaged", 0,
                        activity.resources.getIdentifier(name, "xml", activity.packageName))
                }
                listOf("ic_mouse_gyro_state", "ic_mouse_joystick", "ic_mouse_touchpad").forEach { name ->
                    assertEquals("removed feature icon $name must not be packaged", 0,
                        activity.resources.getIdentifier(name, "drawable", activity.packageName))
                }

                val packageInfo = activity.packageManager.getPackageInfo(
                    activity.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS,
                )
                assertTrue("gyroscope permission is not requested",
                    packageInfo.requestedPermissions.orEmpty().none { it == "android.permission.HIGH_SAMPLING_RATE_SENSORS" })
                assertTrue("gyroscope hardware is not declared",
                    packageInfo.reqFeatures.orEmpty().none { it.name == "android.hardware.sensor.gyroscope" })
            }
        }
    }

    private fun assertPhysicalOrder(activity: MainInsetsFixtureActivity, expectedIds: List<Int>) {
        val ordered = expectedIds.sortedBy { id ->
            val location = IntArray(2)
            activity.findViewById<View>(id).getLocationOnScreen(location)
            location[0]
        }
        assertEquals("control IDs retain their physical left-to-right order", expectedIds, ordered)
        expectedIds.forEach { id ->
            val button = activity.findViewById<View>(id)
            assertTrue("control $id keeps a useful touch height", button.height >= dp(activity, 48))
            assertTrue("control $id keeps a useful touch width", button.width >= dp(activity, 48))
        }
    }

    private fun assertAccessibleIconControl(view: View) {
        assertTrue(
            "control ${view.id} has a nonblank accessible name",
            view.contentDescription?.toString()?.isNotBlank() == true,
        )
        assertTrue(
            "control ${view.id} has a nonblank tooltip",
            view.tooltipText?.toString()?.isNotBlank() == true,
        )

        val textView = view as? TextView ?: error("control ${view.id} is not a text control")
        val icon = textView.compoundDrawablesRelative.firstOrNull { it != null }
            ?: error("missing icon for ${view.id}")
        assertTrue("control ${view.id} has a measurable icon", icon.intrinsicWidth > 0 && icon.intrinsicHeight > 0)
        assertTrue(
            "control ${view.id} does not clip its icon horizontally",
            icon.intrinsicWidth <= view.width,
        )
    }

    private fun assertDistinctIcons(activity: MainInsetsFixtureActivity, ids: List<Int>) {
        val rendered = ids.map { id -> iconPixels(activity.findViewById(id)) }
        assertEquals("icons for different actions are visually distinct", rendered.size, rendered.toSet().size)
    }

    private fun iconPixels(view: View): List<Int> {
        val textView = view as? TextView ?: error("control ${view.id} is not a text control")
        val source = textView.compoundDrawablesRelative.firstOrNull { it != null }
            ?: error("missing icon for ${view.id}")
        val drawable = source.constantState?.newDrawable(view.resources)?.mutate() ?: source.mutate()
        drawable.state = view.drawableState
        // Apply the same solid tint used by the row so the state regression catches geometry,
        // rather than relying on path colors surviving a compound-drawable tint.
        DrawableCompat.setTint(drawable, Color.BLACK)
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }.toList()
    }

    private fun dp(activity: MainInsetsFixtureActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
