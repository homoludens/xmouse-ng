package com.stripe1.xmouse.layout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.res.stringResource
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
