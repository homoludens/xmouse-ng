package com.stripe1.xmouse.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutEditorDragTest {
    @Test
    fun measuredWrappedCellsPreviewAndCommitOneLogicalMoveOrCancelWithoutChangingTheProfile() {
        val profile = LayoutProfile(
            id = "drag-profile",
            name = "Drag",
            buttons = listOf(button("a", 4), button("b", 6), button("c", 3)),
        )
        // Two measured rows, deliberately not tied to any particular screen size/density.
        val bounds = mapOf(
            "a" to LayoutEditorItemBounds(13f, 31f, 149f, 87f),
            "b" to LayoutEditorItemBounds(149f, 31f, 353f, 87f),
            "c" to LayoutEditorItemBounds(13f, 95f, 115f, 151f),
        )
        val begun = LayoutEditorDragReducer.begin(profile, "a")!!
        val target = LayoutEditorDragReducer.targetAt(profile.buttons, bounds, 100f, 120f)
        assertEquals(LayoutEditorDropTarget("c", after = true), target)
        val dragging = LayoutEditorDragReducer.update(begun, target)
        val expected = profile.copy(buttons = listOf(profile.buttons[1], profile.buttons[2], profile.buttons[0]))
        assertEquals(expected, LayoutEditorDragReducer.placement(profile, dragging))
        assertEquals(expected, LayoutEditorDragReducer.commit(profile, dragging))
        assertEquals(listOf("a", "b", "c"), profile.buttons.map { it.id })
        assertEquals(
            LayoutButtonPlacement("a", row = 1, column = 0, widthColumns = 4),
            LayoutEditorGrid.placements(expected).last(),
        )

        val before = LayoutEditorDragReducer.targetAt(profile.buttons, bounds, 20f, 120f)
        assertEquals(LayoutEditorDropTarget("c", after = false), before)
        assertEquals(
            listOf("b", "a", "c"),
            LayoutEditorDragReducer.commit(profile, LayoutEditorDragReducer.update(begun, before)).buttons.map { it.id },
        )
        assertEquals(
            LayoutEditorDropTarget("c", after = true),
            LayoutEditorDragReducer.targetAt(profile.buttons, bounds, 20f, 120f, isRtl = true),
        )
        val outside = LayoutEditorDragReducer.targetAt(profile.buttons, bounds, 200f, 120f)
        assertNull(outside)
        assertEquals(profile, LayoutEditorDragReducer.commit(profile, LayoutEditorDragReducer.update(dragging, outside)))
        assertEquals(profile, LayoutEditorDragReducer.commit(profile, null)) // Pointer cancellation drops state.
        assertEquals(
            profile,
            LayoutEditorDragReducer.commit(
                profile,
                LayoutEditorDragReducer.update(begun, LayoutEditorDropTarget("a", after = true)),
            ),
        )
        val resizedMeanwhile = profile.copy(buttons = profile.buttons.map { it.copy(width = 1) })
        assertEquals(resizedMeanwhile, LayoutEditorDragReducer.commit(resizedMeanwhile, dragging))
    }

    private fun button(id: String, width: Int) = LayoutButton(
        id = id,
        title = id,
        command = "  printf '雪, \\\"quoted\\\"'\nnext command  ",
        width = width,
        color = "#123456",
    )
}
