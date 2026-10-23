package com.stripe1.xmouse

/** Pure inset combination used by the View root applier and its focused unit tests. */
data class InsetEdges(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

object InsetPaddingCalculator {
    @JvmStatic
    fun calculate(
        base: InsetEdges,
        systemBars: InsetEdges,
        displayCutout: InsetEdges,
        ime: InsetEdges,
    ) = InsetEdges(
        left = base.left + maxOf(systemBars.left, displayCutout.left, ime.left),
        top = base.top + maxOf(systemBars.top, displayCutout.top, ime.top),
        right = base.right + maxOf(systemBars.right, displayCutout.right, ime.right),
        bottom = base.bottom + maxOf(systemBars.bottom, displayCutout.bottom, ime.bottom),
    )
}
