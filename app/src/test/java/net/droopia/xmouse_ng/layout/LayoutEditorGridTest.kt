package net.droopia.xmouse_ng.layout

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutEditorGridTest {
    @Test
    fun previewWrapsOrderedSpansAtTenLogicalColumnsWithoutPersistingPositions() {
        val profile = profile(
            button("first", width = 4),
            button("fills-row", width = 6),
            button("third", width = 3),
            button("wide", width = 8),
            button("last", width = 2),
        )

        assertEquals(
            listOf(
                LayoutButtonPlacement("first", row = 0, column = 0, widthColumns = 4),
                LayoutButtonPlacement("fills-row", row = 0, column = 4, widthColumns = 6),
                LayoutButtonPlacement("third", row = 1, column = 0, widthColumns = 3),
                LayoutButtonPlacement("wide", row = 2, column = 0, widthColumns = 8),
                LayoutButtonPlacement("last", row = 2, column = 8, widthColumns = 2),
            ),
            LayoutEditorGrid.placements(profile),
        )
        assertEquals(56, LAYOUT_EDITOR_PREVIEW_BUTTON_HEIGHT_DP)

        val encodedButton = JSONObject(LayoutProfileJson.encode(profile))
            .getJSONArray("buttons")
            .getJSONObject(0)
        assertFalse(encodedButton.has("x"))
        assertFalse(encodedButton.has("y"))
        assertFalse(encodedButton.has("row"))
        assertFalse(encodedButton.has("column"))
    }

    @Test
    fun actualTenColumnPlacementsKeepWidthsOneThroughTenInOrderIncludingTrailingGaps() {
        val source = profile(*(1..10).map { width ->
            button("width-$width", width)
        }.toTypedArray())
        val original = source.copy(buttons = source.buttons.toList())

        assertEquals(
            listOf(
                LayoutButtonPlacement("width-1", row = 0, column = 0, widthColumns = 1),
                LayoutButtonPlacement("width-2", row = 0, column = 1, widthColumns = 2),
                LayoutButtonPlacement("width-3", row = 0, column = 3, widthColumns = 3),
                LayoutButtonPlacement("width-4", row = 0, column = 6, widthColumns = 4),
                LayoutButtonPlacement("width-5", row = 1, column = 0, widthColumns = 5),
                LayoutButtonPlacement("width-6", row = 2, column = 0, widthColumns = 6),
                LayoutButtonPlacement("width-7", row = 3, column = 0, widthColumns = 7),
                LayoutButtonPlacement("width-8", row = 4, column = 0, widthColumns = 8),
                LayoutButtonPlacement("width-9", row = 5, column = 0, widthColumns = 9),
                LayoutButtonPlacement("width-10", row = 6, column = 0, widthColumns = 10),
            ),
            LayoutEditorGrid.placements(source),
        )
        assertEquals("The render-time placement model must not rewrite stored buttons", original, source)
        assertEquals((1..10).toList(), source.buttons.map(LayoutButton::width))
        assertEquals((1..10).map { "width-$it" }, source.buttons.map(LayoutButton::id))
    }

    @Test
    fun mixedCustomWidthsRetainRowsAndLeaveEditorEquivalentTrailingGaps() {
        val source = profile(
            button("half", width = 5),
            button("custom-two", width = 2),
            button("wraps", width = 4),
            button("wide-custom", width = 7),
            button("last", width = 2),
        )
        val before = source.buttons.toList()

        val placements = LayoutEditorGrid.placements(source)
        assertEquals(
            listOf(
                LayoutButtonPlacement("half", row = 0, column = 0, widthColumns = 5),
                LayoutButtonPlacement("custom-two", row = 0, column = 5, widthColumns = 2),
                LayoutButtonPlacement("wraps", row = 1, column = 0, widthColumns = 4),
                LayoutButtonPlacement("wide-custom", row = 2, column = 0, widthColumns = 7),
                LayoutButtonPlacement("last", row = 2, column = 7, widthColumns = 2),
            ),
            placements,
        )
        assertEquals(listOf("half", "custom-two", "wraps", "wide-custom", "last"),
            placements.map(LayoutButtonPlacement::buttonId))
        assertEquals("Each partial row retains its unused columns as trailing space", listOf(3, 6, 1),
            listOf(placements[1], placements[2], placements[4]).map {
                10 - (it.column + it.widthColumns)
            })
        assertEquals(before, source.buttons)
    }

    @Test
    fun selectedButtonRemainsTheSameStableIdAfterReordering() {
        val selected = button("selected", width = 2)
        val buttons = listOf(button("before", width = 1), selected, button("after", width = 3))

        assertEquals(selected, LayoutEditorGrid.selectedButton(buttons, selected.id))
        assertEquals(selected, LayoutEditorGrid.selectedButton(buttons.reversed(), selected.id))
        assertNull(LayoutEditorGrid.selectedButton(buttons, "missing"))
    }

    private fun profile(vararg buttons: LayoutButton) = LayoutProfile(
        id = "preview-profile",
        name = "Preview",
        buttons = buttons.toList(),
    )

    private fun button(id: String, width: Int) = LayoutButton(
        id = id,
        title = id,
        command = "echo $id",
        width = width,
    )
}
