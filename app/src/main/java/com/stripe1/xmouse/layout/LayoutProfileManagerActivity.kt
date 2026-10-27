package com.stripe1.xmouse.layout

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.stripe1.xmouse.R
import com.stripe1.xmouse.ui.XMouseNgTheme
import java.io.File

/** User-facing CRUD and selection screen for saved named layouts. */
class LayoutProfileManagerActivity : AppCompatActivity() {
    private lateinit var managerViewModel: LayoutProfileManagerWorkerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val repository = LayoutProfileRepository(File(filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))
        managerViewModel = ViewModelProvider(
            this,
            LayoutProfileManagerViewModelFactory(repository),
        )[LayoutProfileManagerWorkerViewModel::class.java]

        setContent {
            XMouseNgTheme {
                val state by managerViewModel.state.collectAsState()
                LayoutProfileManagerScreen(
                    state = state,
                    onRefresh = { managerViewModel.edit { it.refresh() } },
                    onCreate = { managerViewModel.edit { it.beginCreate() } },
                    onRename = { id -> managerViewModel.edit { it.beginRename(id) } },
                    onDuplicate = { id -> managerViewModel.edit { it.beginDuplicate(id) } },
                    onSelect = { id -> managerViewModel.edit { it.selectProfile(id) } },
                    onDelete = { id -> managerViewModel.edit { it.requestDelete(id) } },
                    onEdit = ::openEditor,
                    onNameChanged = { value -> managerViewModel.edit { it.updateNameInput(value) } },
                    onNameSave = { managerViewModel.edit { it.commitNameAction() } },
                    onNameCancel = { managerViewModel.edit { it.cancelNameAction() } },
                    onDeleteConfirm = { managerViewModel.edit { it.confirmDelete() } },
                    onDeleteCancel = { managerViewModel.edit { it.cancelDelete() } },
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::managerViewModel.isInitialized) managerViewModel.edit { it.refresh() }
    }

    private fun openEditor(profileId: String) {
        startActivity(
            Intent(this, LayoutEditorActivity::class.java)
                .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, profileId),
        )
    }
}

private class LayoutProfileManagerViewModelFactory(
    private val repository: LayoutProfileRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LayoutProfileManagerWorkerViewModel::class.java))
        return LayoutProfileManagerWorkerViewModel(repository) as T
    }
}

@Composable
private fun LayoutProfileManagerScreen(
    state: LayoutProfileManagerState,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onNameSave: () -> Unit,
    onNameCancel: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .testTag("layout-profiles-list"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LayoutEditorIconButton(
                        icon = R.drawable.ic_ng_arrow_back,
                        contentDescription = stringResource(R.string.layout_profiles_back),
                        onClick = onClose,
                        testTag = "layout-profiles-back",
                    )
                    Text(
                        stringResource(R.string.layout_profiles_title),
                        style = LayoutEditorTypography.heading,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
            }
            item {
                Text(
                    state.selectedProfileId?.let {
                        stringResource(R.string.layout_profiles_selected_id, it)
                    } ?: stringResource(R.string.layout_profiles_none_selected),
                    modifier = Modifier.testTag("layout-profiles-selected-id"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Button(
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth().managerTouchTarget().testTag("layout-profiles-create"),
                ) {
                    Text(stringResource(R.string.layout_profiles_create))
                }
            }
            if (state.isLoading) {
                item { Text(stringResource(R.string.layout_profiles_loading)) }
            } else if (state.profiles.isEmpty()) {
                item { Text(stringResource(R.string.layout_profiles_empty)) }
            } else {
                items(state.profiles, key = LayoutProfile::id) { profile ->
                    ProfileRow(
                        profile = profile,
                        isSelected = profile.id == state.selectedProfileId,
                        canDelete = state.profiles.size > 1,
                        onSelect = { onSelect(profile.id) },
                        onRename = { onRename(profile.id) },
                        onDuplicate = { onDuplicate(profile.id) },
                        onEdit = { onEdit(profile.id) },
                        onDelete = { onDelete(profile.id) },
                    )
                }
            }
            if (state.profiles.size == 1) {
                item {
                    Text(
                        stringResource(R.string.layout_profiles_last_profile),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.errorMessage?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            item {
                TextButton(onClick = onRefresh, modifier = Modifier.managerTouchTarget()) {
                    Text(stringResource(R.string.layout_profiles_refresh))
                }
            }
        }
    }

    state.nameAction?.let { action ->
        val title = when (action) {
            LayoutProfileNameAction.CREATE -> R.string.layout_profiles_create_title
            LayoutProfileNameAction.RENAME -> R.string.layout_profiles_rename_title
            LayoutProfileNameAction.DUPLICATE -> R.string.layout_profiles_duplicate_title
        }
        AlertDialog(
            onDismissRequest = onNameCancel,
            title = { Text(stringResource(title)) },
            text = {
                OutlinedTextField(
                    value = state.nameInput,
                    onValueChange = onNameChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-profiles-name-input"),
                    label = { Text(stringResource(R.string.layout_profiles_name_label)) },
                    singleLine = true,
                    isError = state.nameError != null,
                    supportingText = {
                        state.nameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onNameSave() },
                    modifier = Modifier.managerTouchTarget().testTag("layout-profiles-name-save"),
                ) { Text(stringResource(R.string.layout_profiles_save)) }
            },
            dismissButton = {
                TextButton(onClick = onNameCancel, modifier = Modifier.managerTouchTarget()) {
                    Text(stringResource(R.string.layout_profiles_cancel))
                }
            },
        )
    }

    state.profileToDeleteId?.let { profileId ->
        val name = state.profiles.firstOrNull { it.id == profileId }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = onDeleteCancel,
            title = { Text(stringResource(R.string.layout_profiles_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.layout_profiles_delete_message, name))
                    state.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onDeleteConfirm() },
                    modifier = Modifier.managerTouchTarget().testTag("layout-profiles-delete-confirm"),
                ) { Text(stringResource(R.string.layout_profiles_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDeleteCancel, modifier = Modifier.managerTouchTarget()) {
                    Text(stringResource(R.string.layout_profiles_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: LayoutProfile,
    isSelected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("layout-profile-row-${profile.id}"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.layout_profiles_profile_id, profile.id))
            Text(stringResource(R.string.layout_profiles_button_count, profile.buttons.size))
            if (isSelected) {
                Text(
                    stringResource(R.string.layout_profiles_selected),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics {
                        contentDescription = "Selected profile ID: ${profile.id}"
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = onSelect,
                    enabled = !isSelected,
                    modifier = Modifier.managerTouchTarget().testTag("layout-profile-select-${profile.id}"),
                ) {
                    Text(stringResource(if (isSelected) R.string.layout_profiles_selected else R.string.layout_profiles_select))
                }
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.managerTouchTarget().testTag("layout-profile-edit-${profile.id}"),
                ) { Text(stringResource(R.string.layout_profiles_edit)) }
                TextButton(
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier.managerTouchTarget().testTag("layout-profile-delete-${profile.id}"),
                ) { Text(stringResource(R.string.layout_profiles_delete)) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = onRename,
                    modifier = Modifier.managerTouchTarget().testTag("layout-profile-rename-${profile.id}"),
                ) { Text(stringResource(R.string.layout_profiles_rename)) }
                TextButton(
                    onClick = onDuplicate,
                    modifier = Modifier.managerTouchTarget().testTag("layout-profile-duplicate-${profile.id}"),
                ) { Text(stringResource(R.string.layout_profiles_duplicate)) }
            }
        }
    }
}

private fun Modifier.managerTouchTarget(): Modifier = heightIn(min = 48.dp)
