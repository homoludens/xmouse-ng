package com.stripe1.xmouse.layout

/** A logical insertion anchored to a stable ID, not a screen coordinate or a changing list index. */
internal data class LayoutEditorDropTarget(val buttonId: String, val after: Boolean)

/** Session-only snapshot. Dropping this state cancels a gesture without touching the draft. */
internal data class LayoutEditorDrag(
    val buttonId: String,
    val originalButtons: List<LayoutButton>,
    val target: LayoutEditorDropTarget? = null,
)

/** Measured Compose item bounds, used only while the preview is on screen. */
internal data class LayoutEditorItemBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal object LayoutEditorDragReducer {
    fun begin(profile: LayoutProfile, buttonId: String): LayoutEditorDrag? =
        if (profile.buttons.any { it.id == buttonId }) {
            LayoutEditorDrag(buttonId, profile.buttons.toList())
        } else {
            null
        }

    fun update(drag: LayoutEditorDrag?, target: LayoutEditorDropTarget?): LayoutEditorDrag? =
        drag?.copy(target = target?.takeIf { candidate ->
            candidate.buttonId != drag.buttonId &&
                drag.originalButtons.any { it.id == candidate.buttonId }
        })

    /** Both preview and release use the same operation; movement itself never mutates a profile. */
    fun placement(profile: LayoutProfile, drag: LayoutEditorDrag?): LayoutProfile {
        if (drag == null || profile.buttons != drag.originalButtons) return profile
        val target = drag.target ?: return profile
        val moving = profile.buttons.firstOrNull { it.id == drag.buttonId } ?: return profile
        val remaining = profile.buttons.filterNot { it.id == moving.id }.toMutableList()
        val targetIndex = remaining.indexOfFirst { it.id == target.buttonId }
        if (targetIndex < 0) return profile
        remaining.add(targetIndex + if (target.after) 1 else 0, moving)
        return profile.copy(buttons = remaining.toList())
    }

    fun commit(profile: LayoutProfile, drag: LayoutEditorDrag?): LayoutProfile = placement(profile, drag)

    /** Hit actual cells across wrapped rows. Gaps/outside the grid deliberately have no target. */
    fun targetAt(
        buttons: List<LayoutButton>,
        bounds: Map<String, LayoutEditorItemBounds>,
        x: Float,
        y: Float,
        isRtl: Boolean = false,
    ): LayoutEditorDropTarget? {
        buttons.forEach { button ->
            val item = bounds[button.id] ?: return@forEach
            if (x >= item.left && x < item.right && y >= item.top && y < item.bottom) {
                val rightHalf = x >= (item.left + item.right) / 2f
                return LayoutEditorDropTarget(button.id, after = rightHalf != isRtl)
            }
        }
        return null
    }
}
