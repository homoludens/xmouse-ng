package com.stripe1.xmouse.layout

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
