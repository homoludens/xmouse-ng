package net.droopia.xmouse_ng

import org.junit.Assert.assertEquals
import org.junit.Test

class InsetPaddingCalculatorTest {
    @Test
    fun combinesCutoutSystemBarsAndImeWithoutCompoundingBasePadding() {
        val result = InsetPaddingCalculator.calculate(
            base = InsetEdges(left = 6, top = 8, right = 10, bottom = 12),
            systemBars = InsetEdges(top = 24, bottom = 30),
            displayCutout = InsetEdges(left = 18, top = 30, right = 12),
            ime = InsetEdges(left = 2, bottom = 280),
        )

        assertEquals(InsetEdges(left = 24, top = 38, right = 22, bottom = 292), result)
    }

    @Test
    fun keepsNavigationBarInsetWhenKeyboardIsHidden() {
        val result = InsetPaddingCalculator.calculate(
            base = InsetEdges(bottom = 12),
            systemBars = InsetEdges(bottom = 30),
            displayCutout = InsetEdges(),
            ime = InsetEdges(),
        )

        assertEquals(InsetEdges(bottom = 42), result)
    }
}
