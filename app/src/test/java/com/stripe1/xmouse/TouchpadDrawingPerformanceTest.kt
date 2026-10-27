package com.stripe1.xmouse

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TouchpadDrawingPerformanceTest {
    @Test fun touchpadDoesNotAllocateOrDrawABackingBitmapAndKeepsDirectFeedback() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "app/src/main/java/com/stripe1/xmouse/MyMouseView.java").isFile }
        val source = root.resolve("app/src/main/java/com/stripe1/xmouse/MyMouseView.java").readText()
        assertFalse(source.contains("Bitmap"))
        assertFalse(source.contains("drawBitmap"))
        assertTrue(source.contains("canvas.drawPath(mPath, mPaint)"))
        assertTrue(source.contains("canvas.drawCircle(mX, mY"))
        assertTrue(source.contains("drawTouchpadHint(canvas, stripLeft, h)"))
        assertTrue(source.contains("mPath.quadTo(mX, mY"))
        assertTrue(source.contains("activity.cancelControlInputGesture(gestureToken)"))
    }
}
