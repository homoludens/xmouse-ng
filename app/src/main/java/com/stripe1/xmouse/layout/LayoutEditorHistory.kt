package com.stripe1.xmouse.layout

/** Only button data and selection are undoable; profile names and durable records are independent. */
internal data class LayoutEditorButtonSnapshot(
    val buttons: List<LayoutButton>,
    val selectedButtonId: String?,
)

internal data class LayoutEditorButtonEdit(
    val before: LayoutEditorButtonSnapshot,
    val after: LayoutEditorButtonSnapshot,
)

/** Immutable, session-local reducer. The ViewModel publishes a transition only after draft persistence. */
internal data class LayoutEditorHistory(
    private val past: List<LayoutEditorButtonEdit> = emptyList(),
    private val future: List<LayoutEditorButtonEdit> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun record(before: LayoutEditorButtonSnapshot, after: LayoutEditorButtonSnapshot): LayoutEditorHistory =
        if (before.buttons == after.buttons) this else LayoutEditorHistory(
            past = past + LayoutEditorButtonEdit(
                before.copy(buttons = before.buttons.toList()),
                after.copy(buttons = after.buttons.toList()),
            ),
        )

    fun undo(): Transition? = past.lastOrNull()?.let { edit ->
        Transition(edit.before, LayoutEditorHistory(past.dropLast(1), future + edit))
    }

    fun redo(): Transition? = future.lastOrNull()?.let { edit ->
        Transition(edit.after, LayoutEditorHistory(past + edit, future.dropLast(1)))
    }

    data class Transition(val snapshot: LayoutEditorButtonSnapshot, val history: LayoutEditorHistory)
}
