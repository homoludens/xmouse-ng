package com.stripe1.xmouse.layout

/** Transient ten-column positions for preview rendering; these logical values are never persisted. */
internal data class LayoutButtonPlacement(
    val buttonId: String,
    val row: Int,
    val column: Int,
    val widthColumns: Int,
)

internal const val LAYOUT_EDITOR_PREVIEW_BUTTON_HEIGHT_DP = 56

internal object LayoutEditorGrid {
    /** Pack buttons in saved order, wrapping only when the next logical span does not fit. */
    fun placements(profile: LayoutProfile): List<LayoutButtonPlacement> {
        val placements = ArrayList<LayoutButtonPlacement>(profile.buttons.size)
        var row = 0
        var column = 0

        profile.buttons.forEach { button ->
            if (column + button.width > profile.columnCount) {
                row += 1
                column = 0
            }

            placements += LayoutButtonPlacement(
                buttonId = button.id,
                row = row,
                column = column,
                widthColumns = button.width,
            )
            column += button.width
            if (column == profile.columnCount) {
                row += 1
                column = 0
            }
        }

        return placements
    }

    /** Resolve the selection by stable identity so list reordering cannot change the selected button. */
    fun selectedButton(buttons: List<LayoutButton>, selectedButtonId: String?): LayoutButton? =
        selectedButtonId?.let { id -> buttons.firstOrNull { it.id == id } }
}
