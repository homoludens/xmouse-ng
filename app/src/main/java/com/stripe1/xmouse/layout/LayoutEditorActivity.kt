package com.stripe1.xmouse.layout

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.stripe1.xmouse.R
import java.io.File

/** Separate editor surface; it never receives or invokes the remote command sink. */
class LayoutEditorActivity : ComponentActivity() {
    private lateinit var editorViewModel: LayoutEditorViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            finish()
            return
        }

        val repository = LayoutProfileRepository(File(filesDir, PROFILE_DIRECTORY))
        editorViewModel = ViewModelProvider(
            this,
            LayoutEditorViewModel.Factory(profileId, repository),
        )[LayoutEditorViewModel::class.java]

        setContent {
            MaterialTheme {
                val state by editorViewModel.state.collectAsState()
                LayoutEditorScreen(
                    state = state,
                    onNameChanged = editorViewModel::updateProfileNameInput,
                    onButtonSelected = editorViewModel::selectButton,
                    onDragCommitted = editorViewModel::commitDrag,
                    onWidthChanged = editorViewModel::updateButtonWidth,
                    onNewButton = editorViewModel::startNewButtonEdit,
                    onEditButton = editorViewModel::startSelectedButtonEdit,
                    onDuplicateButton = editorViewModel::startSelectedButtonDuplicate,
                    onDeleteButton = editorViewModel::deleteSelectedButton,
                    onButtonTitleChanged = editorViewModel::updateButtonTitleInput,
                    onButtonCommandChanged = editorViewModel::updateButtonCommandInput,
                    onButtonWidthInputChanged = editorViewModel::updateButtonWidthInput,
                    onButtonColorChanged = editorViewModel::updateButtonColorInput,
                    onButtonEditSave = editorViewModel::saveButtonEdit,
                    onButtonEditCancel = editorViewModel::cancelButtonEdit,
                    onBack = {
                        if (editorViewModel.state.value.isShowingBackConfirmation) {
                            editorViewModel.resolveBack(LayoutEditorBackChoice.CANCEL)
                        } else if (editorViewModel.requestBack()) {
                            finish()
                        }
                    },
                    onSave = {
                        if (editorViewModel.save()) finish()
                    },
                    onDiscard = {
                        if (editorViewModel.discard()) finish()
                    },
                    onBackChoice = { choice ->
                        if (editorViewModel.resolveBack(choice)) finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "com.stripe1.xmouse.layout.profile_id"
        const val PROFILE_DIRECTORY = "layout-profiles"
    }
}

@Composable
private fun LayoutEditorScreen(
    state: LayoutEditorState,
    onNameChanged: (String) -> Unit,
    onButtonSelected: (String?) -> Unit,
    onDragCommitted: (LayoutEditorDrag) -> Unit,
    onWidthChanged: (String, Int) -> Unit,
    onNewButton: () -> Unit,
    onEditButton: () -> Unit,
    onDuplicateButton: () -> Unit,
    onDeleteButton: () -> Unit,
    onButtonTitleChanged: (String) -> Unit,
    onButtonCommandChanged: (String) -> Unit,
    onButtonWidthInputChanged: (String) -> Unit,
    onButtonColorChanged: (String) -> Unit,
    onButtonEditSave: () -> Boolean,
    onButtonEditCancel: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onBackChoice: (LayoutEditorBackChoice) -> Unit,
) {
    BackHandler { onBack() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.layout_editor_back)) }
                Text(stringResource(R.string.layout_editor_title), style = MaterialTheme.typography.headlineSmall)
            }

            val saved = state.savedProfile
            val draft = state.draftProfile
            when {
                state.isLoading -> Text(stringResource(R.string.layout_editor_loading))
                saved == null || draft == null -> Text(
                    state.errorMessage ?: stringResource(R.string.layout_editor_unavailable),
                )
                else -> {
                    Text(
                        stringResource(R.string.layout_editor_saved_layout, saved.name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.layout_editor_saved_buttons, draft.buttons.size))
                    ButtonEditActions(
                        hasSelection = state.selectedButton != null,
                        onNewButton = onNewButton,
                        onEditButton = onEditButton,
                        onDuplicateButton = onDuplicateButton,
                        onDeleteButton = onDeleteButton,
                    )
                    ButtonGridPreview(
                        profile = draft,
                        selectedButton = state.selectedButton,
                        selectedButtonId = state.selectedButtonId,
                        onButtonSelected = onButtonSelected,
                        onDragCommitted = onDragCommitted,
                        onWidthChanged = onWidthChanged,
                        widthValidationMessage = state.widthValidationMessage,
                    )
                    OutlinedTextField(
                        value = state.profileNameInput,
                        onValueChange = onNameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.layout_editor_profile_name)) },
                        singleLine = true,
                    )
                    Text(stringResource(R.string.layout_editor_saved_snapshot_note))
                    state.errorMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onSave, enabled = state.isDirty) {
                            Text(stringResource(R.string.layout_editor_save))
                        }
                        TextButton(onClick = onDiscard, enabled = state.isDirty) {
                            Text(stringResource(R.string.layout_editor_discard))
                        }
                    }
                }
            }
        }
    }

    if (state.isShowingBackConfirmation) {
        AlertDialog(
            onDismissRequest = { onBackChoice(LayoutEditorBackChoice.CANCEL) },
            title = { Text(stringResource(R.string.layout_editor_back_confirmation_title)) },
            text = {
                Text(state.errorMessage ?: stringResource(R.string.layout_editor_back_confirmation_message))
            },
            confirmButton = {
                TextButton(onClick = { onBackChoice(LayoutEditorBackChoice.SAVE) }) {
                    Text(stringResource(R.string.layout_editor_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onBackChoice(LayoutEditorBackChoice.DISCARD) }) {
                        Text(stringResource(R.string.layout_editor_discard))
                    }
                    TextButton(onClick = { onBackChoice(LayoutEditorBackChoice.CANCEL) }) {
                        Text(stringResource(R.string.layout_editor_cancel))
                    }
                }
            },
        )
    }

    state.buttonEditInput?.let { input ->
        ButtonEditDialog(
            input = input,
            validation = state.buttonEditValidation,
            saveError = state.buttonEditSaveError,
            onTitleChanged = onButtonTitleChanged,
            onCommandChanged = onButtonCommandChanged,
            onWidthChanged = onButtonWidthInputChanged,
            onColorChanged = onButtonColorChanged,
            onSave = onButtonEditSave,
            onCancel = onButtonEditCancel,
        )
    }
}

@Composable
@SuppressLint("HardcodedText")
private fun ButtonEditActions(
    hasSelection: Boolean,
    onNewButton: () -> Unit,
    onEditButton: () -> Unit,
    onDuplicateButton: () -> Unit,
    onDeleteButton: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewButton, modifier = Modifier.weight(1f).testTag("layout-editor-add-button")) {
                Text("Add button")
            }
            TextButton(
                onClick = onEditButton,
                enabled = hasSelection,
                modifier = Modifier.weight(1f).testTag("layout-editor-edit-button"),
            ) { Text("Edit selected") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onDuplicateButton,
                enabled = hasSelection,
                modifier = Modifier.weight(1f).testTag("layout-editor-duplicate-button"),
            ) { Text("Duplicate selected") }
            TextButton(
                onClick = onDeleteButton,
                enabled = hasSelection,
                modifier = Modifier.weight(1f).testTag("layout-editor-delete-button"),
            ) { Text("Delete selected") }
        }
    }
}

private val BUTTON_COLOR_PRESETS = listOf(
    "#FFFFFF", "#000000", "#F44336", "#FF9800",
    "#FFEB3B", "#4CAF50", "#2196F3", "#9C27B0",
)

@Composable
@SuppressLint("HardcodedText")
private fun ButtonEditDialog(
    input: ButtonEditInput,
    validation: ButtonEditValidation?,
    saveError: String?,
    onTitleChanged: (String) -> Unit,
    onCommandChanged: (String) -> Unit,
    onWidthChanged: (String) -> Unit,
    onColorChanged: (String) -> Unit,
    onSave: () -> Boolean,
    onCancel: () -> Unit,
) {
    val title = when (input.mode) {
        ButtonEditMode.CREATE -> "Add button"
        ButtonEditMode.EDIT -> "Edit button"
        ButtonEditMode.DUPLICATE -> "Duplicate button"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input.title,
                    onValueChange = onTitleChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-button-title"),
                    label = { Text("Title") },
                    singleLine = true,
                    isError = validation?.title != null,
                    supportingText = { validation?.title?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
                OutlinedTextField(
                    value = input.command,
                    onValueChange = onCommandChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-button-command"),
                    label = { Text("Raw command") },
                    minLines = 3,
                    isError = validation?.command != null,
                    supportingText = { validation?.command?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
                OutlinedTextField(
                    value = input.width,
                    onValueChange = onWidthChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-button-width"),
                    label = { Text("Width (1–10 columns)") },
                    singleLine = true,
                    isError = validation?.width != null,
                    supportingText = { validation?.width?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
                OutlinedTextField(
                    value = input.color,
                    onValueChange = onColorChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-button-color"),
                    label = { Text("Color (#RRGGBB or #AARRGGBB)") },
                    singleLine = true,
                    isError = validation?.color != null,
                    supportingText = { validation?.color?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
                Text("Color palette")
                BUTTON_COLOR_PRESETS.chunked(4).forEach { rowColors ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { preset ->
                            val selected = input.color.equals(preset, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(preset)))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape,
                                    )
                                    .clickable { onColorChanged(preset) }
                                    .semantics { contentDescription = "Select color $preset" }
                                    .testTag("layout-editor-color-$preset"),
                            )
                        }
                    }
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave() }, modifier = Modifier.testTag("layout-editor-button-save")) {
                Text("Apply to draft")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("layout-editor-button-cancel")) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ButtonGridPreview(
    profile: LayoutProfile,
    selectedButton: LayoutButton?,
    selectedButtonId: String?,
    onButtonSelected: (String?) -> Unit,
    onDragCommitted: (LayoutEditorDrag) -> Unit,
    onWidthChanged: (String, Int) -> Unit,
    widthValidationMessage: String?,
) {
    val placements = remember(profile) { LayoutEditorGrid.placements(profile) }
    val buttonsById = remember(profile.buttons) { profile.buttons.associateBy(LayoutButton::id) }
    var drag by remember(profile.buttons) { mutableStateOf<LayoutEditorDrag?>(null) }
    val itemCoordinates = remember { mutableMapOf<String, LayoutCoordinates>() }
    val currentProfile by rememberUpdatedState(profile)
    val selectButton by rememberUpdatedState(onButtonSelected)
    val commitDrag by rememberUpdatedState(onDragCommitted)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val placementProfile = LayoutEditorDragReducer.placement(profile, drag)
    val previewPlacement = drag?.takeIf { it.target != null }?.let { active ->
        LayoutEditorGrid.placements(placementProfile).firstOrNull { it.buttonId == active.buttonId }
    }

    fun updateTarget(position: Offset) {
        drag = LayoutEditorDragReducer.update(
            drag,
            LayoutEditorDragReducer.targetAt(
                currentProfile.buttons,
                itemCoordinates.filterValues { it.isAttached }.mapValues { (_, coordinates) ->
                    val rect = coordinates.boundsInRoot()
                    LayoutEditorItemBounds(rect.left, rect.top, rect.right, rect.bottom)
                },
                position.x,
                position.y,
                isRtl,
            ),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.layout_editor_preview_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.layout_editor_preview_hint))
        if (placements.isEmpty()) {
            Text(stringResource(R.string.layout_editor_preview_empty))
        } else {
            // Keep hit-test cells stationary until release. Only the destination overlay moves.
            val rowCount = maxOf(placements.last().row, previewPlacement?.row ?: 0) + 1
            val previewHeight = rowCount * LAYOUT_EDITOR_PREVIEW_BUTTON_HEIGHT_DP + (rowCount - 1) * 8
            Box(Modifier.fillMaxWidth().heightIn(min = previewHeight.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    placements.groupBy(LayoutButtonPlacement::row).values.forEach { rowPlacements ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            var nextColumn = 0
                            rowPlacements.forEach { placement ->
                                if (placement.column > nextColumn) {
                                    Spacer(Modifier.weight((placement.column - nextColumn).toFloat()))
                                }
                                val button = buttonsById.getValue(placement.buttonId)
                                key(button.id) {
                                    PreviewButton(
                                        button = button,
                                        selected = button.id == selectedButtonId,
                                        onClick = { onButtonSelected(button.id) },
                                        modifier = Modifier
                                            .weight(placement.widthColumns.toFloat())
                                            .testTag("layout-editor-button-${button.id}")
                                            .onGloballyPositioned { itemCoordinates[button.id] = it }
                                            .pointerInput(button.id, isRtl, profile.buttons) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { local ->
                                                        drag = LayoutEditorDragReducer.begin(currentProfile, button.id)
                                                        selectButton(button.id)
                                                        itemCoordinates[button.id]?.takeIf { it.isAttached }?.let {
                                                            updateTarget(it.localToRoot(local))
                                                        }
                                                    },
                                                    onDrag = { change, _ ->
                                                        itemCoordinates[button.id]?.takeIf { it.isAttached }?.let {
                                                            updateTarget(it.localToRoot(change.position))
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        val completed = drag
                                                        drag = null
                                                        if (completed != null) commitDrag(completed)
                                                    },
                                                    onDragCancel = { drag = null },
                                                )
                                            }
                                            .alpha(if (drag?.buttonId == button.id) 0.45f else 1f),
                                    )
                                }
                                nextColumn = placement.column + placement.widthColumns
                            }
                            if (nextColumn < LAYOUT_COLUMN_COUNT) {
                                Spacer(Modifier.weight((LAYOUT_COLUMN_COUNT - nextColumn).toFloat()))
                            }
                        }
                    }
                }
                if (previewPlacement != null) {
                    val tint = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.matchParentSize().testTag("layout-editor-placement-preview")) {
                        val columnWidth = size.width / LAYOUT_COLUMN_COUNT
                        val column = if (isRtl) {
                            LAYOUT_COLUMN_COUNT - previewPlacement.column - previewPlacement.widthColumns
                        } else {
                            previewPlacement.column
                        }
                        val topLeft = Offset(
                            column * columnWidth + 2.dp.toPx(),
                            previewPlacement.row * (LAYOUT_EDITOR_PREVIEW_BUTTON_HEIGHT_DP + 8).dp.toPx(),
                        )
                        val spanSize = Size(
                            (previewPlacement.widthColumns * columnWidth - 4.dp.toPx()).coerceAtLeast(0f),
                            LAYOUT_EDITOR_PREVIEW_BUTTON_HEIGHT_DP.dp.toPx(),
                        )
                        drawRoundRect(tint.copy(alpha = 0.25f), topLeft, spanSize, CornerRadius(8.dp.toPx()))
                        drawRoundRect(tint, topLeft, spanSize, CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()))
                    }
                }
            }
        }

        if (previewPlacement != null) {
            Text(
                stringResource(
                    R.string.layout_editor_drag_placement,
                    placementProfile.buttons.indexOfFirst { it.id == previewPlacement.buttonId } + 1,
                    previewPlacement.row + 1,
                    previewPlacement.widthColumns,
                ),
            )
        } else if (drag != null) {
            Text(stringResource(R.string.layout_editor_drag_no_target))
        }
        SelectedButtonProperties(selectedButton, onWidthChanged, widthValidationMessage)
    }
}

@Composable
private fun PreviewButton(
    button: LayoutButton,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = remember(button.color) {
        Color(android.graphics.Color.parseColor(button.color))
    }
    val textColor = if (backgroundColor.luminance() > 0.5f) Color.Black else Color.White

    Surface(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(LAYOUT_EDITOR_PREVIEW_BUTTON_HEIGHT_DP.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        contentColor = textColor,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = button.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SelectedButtonProperties(
    button: LayoutButton?,
    onWidthChanged: (String, Int) -> Unit,
    widthValidationMessage: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        if (button == null) {
            Text(
                text = stringResource(R.string.layout_editor_preview_select_hint),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.layout_editor_preview_selected),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.layout_editor_preview_title, button.title))
                Text(
                    stringResource(R.string.layout_editor_preview_command, button.command.ifBlank {
                        stringResource(R.string.layout_editor_preview_empty_command)
                    }),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(
                        R.string.layout_editor_preview_width,
                        button.width,
                        LAYOUT_COLUMN_COUNT,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onWidthChanged(button.id, button.width - 1) },
                        modifier = Modifier.testTag("layout-editor-decrease-width"),
                    ) { Text(stringResource(R.string.layout_editor_decrease_width)) }
                    TextButton(
                        onClick = { onWidthChanged(button.id, button.width + 1) },
                        modifier = Modifier.testTag("layout-editor-increase-width"),
                    ) { Text(stringResource(R.string.layout_editor_increase_width)) }
                }
                widthValidationMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("layout-editor-width-error"),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(android.graphics.Color.parseColor(button.color)), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Text(
                        stringResource(R.string.layout_editor_preview_color, button.color),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (widthValidationMessage == null) {
                    Text(
                        stringResource(R.string.layout_editor_preview_valid),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
