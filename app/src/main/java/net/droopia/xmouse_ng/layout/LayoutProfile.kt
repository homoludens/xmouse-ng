package net.droopia.xmouse_ng.layout

import java.util.UUID

const val LAYOUT_COLUMN_COUNT = 10
const val DEFAULT_LAYOUT_BUTTON_COLOR = "#FFFFFF"

private val stableIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val colorPattern = Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")

internal fun isStableLayoutId(id: String): Boolean = stableIdPattern.matches(id)

/** A custom button's command is opaque text and is never parsed or normalized by this model. */
data class LayoutButton(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val command: String,
    val width: Int,
    val color: String = DEFAULT_LAYOUT_BUTTON_COLOR,
) {
    init {
        require(isStableLayoutId(id)) { "Button ID must be a stable identifier" }
        require(title.isNotBlank()) { "Button title must not be blank" }
        require(command.isNotBlank()) { "Button command must not be blank" }
        require(width in 1..LAYOUT_COLUMN_COUNT) { "Button width must be between 1 and 10 columns" }
        require(colorPattern.matches(color)) { "Button color must be a #RRGGBB or #AARRGGBB value" }
    }
}

/** Ordered, logical grid profile. Coordinates are derived from button order and width. */
data class LayoutProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val columnCount: Int = LAYOUT_COLUMN_COUNT,
    val buttons: List<LayoutButton> = emptyList(),
) {
    init {
        require(isStableLayoutId(id)) { "Profile ID must be a stable identifier" }
        require(name.isNotBlank()) { "Profile name must not be blank" }
        require(columnCount == LAYOUT_COLUMN_COUNT) { "Layout profiles must use exactly 10 columns" }
        require(buttons.map(LayoutButton::id).toSet().size == buttons.size) {
            "Button IDs must be unique within a profile"
        }
    }
}
