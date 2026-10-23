package com.stripe1.xmouse.layout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
                    ButtonGridPreview(
                        profile = draft,
                        selectedButton = state.selectedButton,
                        selectedButtonId = state.selectedButtonId,
                        onButtonSelected = onButtonSelected,
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
}

@Composable
private fun ButtonGridPreview(
    profile: LayoutProfile,
    selectedButton: LayoutButton?,
    selectedButtonId: String?,
    onButtonSelected: (String?) -> Unit,
) {
    val placements = remember(profile) { LayoutEditorGrid.placements(profile) }
    val buttonsById = remember(profile.buttons) { profile.buttons.associateBy(LayoutButton::id) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.layout_editor_preview_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.layout_editor_preview_hint))
        if (placements.isEmpty()) {
            Text(stringResource(R.string.layout_editor_preview_empty))
        } else {
            placements.groupBy(LayoutButtonPlacement::row).values.forEach { rowPlacements ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    var nextColumn = 0
                    rowPlacements.forEach { placement ->
                        if (placement.column > nextColumn) {
                            Spacer(Modifier.weight((placement.column - nextColumn).toFloat()))
                        }
                        val button = buttonsById.getValue(placement.buttonId)
                        PreviewButton(
                            button = button,
                            selected = button.id == selectedButtonId,
                            onClick = { onButtonSelected(button.id) },
                            modifier = Modifier.weight(placement.widthColumns.toFloat()),
                        )
                        nextColumn = placement.column + placement.widthColumns
                    }
                    if (nextColumn < LAYOUT_COLUMN_COUNT) {
                        Spacer(Modifier.weight((LAYOUT_COLUMN_COUNT - nextColumn).toFloat()))
                    }
                }
            }
        }

        SelectedButtonProperties(selectedButton)
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
private fun SelectedButtonProperties(button: LayoutButton?) {
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
                Text(
                    stringResource(R.string.layout_editor_preview_valid),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
