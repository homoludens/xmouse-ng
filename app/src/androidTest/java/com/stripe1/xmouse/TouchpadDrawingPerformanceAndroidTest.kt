package com.stripe1.xmouse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchpadDrawingPerformanceAndroidTest {
    @Test fun resizedBitmapFreeTouchpadStillPaintsItsDirectPathCircleAndScrollStrip() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("production touchpad owns no backing pixel storage",
                    MyMouseView::class.java.declaredFields.any { it.type == Bitmap::class.java })
                val pointer = MyMouseView(activity, null)
                fun set(name: String, value: Any) = MyMouseView::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(pointer, value)
                val path = MyMouseView::class.java.getDeclaredField("mPath").apply { isAccessible = true }
                    .get(pointer) as Path
                set("touching", true)
                set("mX", 100f)
                set("mY", 90f)
                path.moveTo(20f, 30f)
                path.lineTo(75f, 30f)
                for ((width, height) in listOf(400 to 220, 520 to 180, 360 to 240)) {
                    pointer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    pointer.layout(0, 0, width, height)
                    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        pointer.draw(Canvas(output))
                        assertEquals("the direct gesture path survives resize", activity.getColor(R.color.ng_accent),
                            output.getPixel(45, 30))
                        val radius = 12 * activity.resources.displayMetrics.density
                        assertTrue("the current pointer circle is still drawn",
                            (85..115).any { x ->
                                val y = (90 + radius).toInt().coerceAtMost(height - 1)
                                output.getPixel(x, y).ushr(24) != 0
                            })
                        assertEquals("the scroll strip remains painted", activity.getColor(R.color.ng_surface_soft),
                            output.getPixel(width - 5, height / 2))
                        assertEquals("blank interior remains transparent for the View background", 0,
                            output.getPixel(10, height - 10))
                    } finally { output.recycle() }
                }
            }
        }
    }
}
