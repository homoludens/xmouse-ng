package com.stripe1.xmouse.layout

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.stripe1.xmouse.R
import com.stripe1.xmouse.ui.XMouseNgTheme
import java.io.File

/** Separate editor surface; it never receives or invokes the remote command sink. */
class LayoutEditorActivity : AppCompatActivity() {
    private lateinit var editorViewModel: LayoutEditorWorkerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            finish()
            return
        }

        val repository = LayoutProfileRepository(File(filesDir, PROFILE_DIRECTORY))
        editorViewModel = ViewModelProvider(
            this,
            LayoutEditorWorkerViewModel.Factory(profileId, repository),
        )[LayoutEditorWorkerViewModel::class.java]

        setContent {
            XMouseNgTheme {
                val state by editorViewModel.state.collectAsState()
                val closed by editorViewModel.closed.collectAsState()
                LaunchedEffect(closed) { if (closed) finish() }
                LayoutEditorScreen(
                    state = state,
                    onNameChanged = editorViewModel::updateProfileNameInput,
                    onButtonSelected = { id -> editorViewModel.edit { it.selectButton(id) } },
                    onDragCommitted = { drag -> editorViewModel.edit { it.commitDrag(drag) } },
                    onWidthChanged = { id, width -> editorViewModel.edit { it.updateButtonWidth(id, width) } },
                    onMoveEarlier = { editorViewModel.edit { it.moveSelectedButtonEarlier() } },
                    onMoveLater = { editorViewModel.edit { it.moveSelectedButtonLater() } },
                    onNewButton = { editorViewModel.edit { it.startNewButtonEdit() } },
                    onEditButton = { editorViewModel.edit { it.startSelectedButtonEdit() } },
                    onDuplicateButton = { editorViewModel.edit { it.startSelectedButtonDuplicate() } },
                    onDeleteButton = { buttonId -> editorViewModel.edit { it.deleteButton(buttonId) } },
                    onUndo = { editorViewModel.edit { it.undo() } },
                    onRedo = { editorViewModel.edit { it.redo() } },
                    onButtonTitleChanged = { value -> editorViewModel.edit { it.updateButtonTitleInput(value) } },
                    onButtonCommandChanged = { value -> editorViewModel.edit { it.updateButtonCommandInput(value) } },
                    onButtonWidthInputChanged = { value -> editorViewModel.edit { it.updateButtonWidthInput(value) } },
                    onButtonColorChanged = { value -> editorViewModel.edit { it.updateButtonColorInput(value) } },
                    onButtonEditSave = { editorViewModel.edit { it.saveButtonEdit() } },
                    onButtonEditCancel = { editorViewModel.edit { it.cancelButtonEdit() } },
                    onBack = editorViewModel::back,
                    onEnterXml = editorViewModel::enterXmlMode,
                    onXmlChanged = editorViewModel::updateXmlSource,
                    onXmlValidate = editorViewModel::validateXmlSource,
                    onXmlFormat = editorViewModel::formatXmlSource,
                    onXmlApply = editorViewModel::applyXmlSource,
                    onXmlExit = { editorViewModel.requestXmlExit(XmlEditorExitAction.BACK_TO_VISUAL) },
                    onXmlReset = { editorViewModel.requestXmlExit(XmlEditorExitAction.RESET_SOURCE) },
                    onXmlExitChoice = editorViewModel::resolveXmlExit,
                    onSave = editorViewModel::saveAndClose,
                    onDiscardDraft = editorViewModel::discardDraft,
                    onBackChoice = editorViewModel::backChoice,
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
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onNewButton: () -> Unit,
    onEditButton: () -> Unit,
    onDuplicateButton: () -> Unit,
    onDeleteButton: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onButtonTitleChanged: (String) -> Unit,
    onButtonCommandChanged: (String) -> Unit,
    onButtonWidthInputChanged: (String) -> Unit,
    onButtonColorChanged: (String) -> Unit,
    onButtonEditSave: () -> Unit,
    onButtonEditCancel: () -> Unit,
    onBack: () -> Unit,
    onEnterXml: () -> Unit,
    onXmlChanged: (String) -> Unit,
    onXmlValidate: () -> Unit,
    onXmlFormat: () -> Unit,
    onXmlApply: () -> Unit,
    onXmlExit: () -> Unit,
    onXmlReset: () -> Unit,
    onXmlExitChoice: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDiscardDraft: () -> Unit,
    onBackChoice: (LayoutEditorBackChoice) -> Unit,
) {
    BackHandler { onBack() }
    var showHelp by remember { mutableStateOf(false) }
    var showXmlFormatHelp by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var deleteConfirmationId by remember { mutableStateOf<String?>(null) }
    var selectedMoreExpanded by remember { mutableStateOf(false) }
    var xmlMoreExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedButtonId, state.editMode) {
        selectedMoreExpanded = false
        xmlMoreExpanded = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
            verticalArrangement = Arrangement.Top,
        ) {
            EditorHeader(onBack = onBack, onHelp = { showHelp = true })
            val saved = state.savedProfile
            val draft = state.draftProfile
            when {
                state.isLoading -> EditorEmptyContent(
                    stringResource(R.string.layout_editor_loading),
                    Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                )
                saved == null || draft == null -> EditorEmptyContent(
                    state.errorMessage ?: stringResource(R.string.layout_editor_unavailable),
                    Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                )
                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EditorLayoutSummary(state)
                        EditorModeTabs(
                            state = state,
                            onEnterXml = onEnterXml,
                            onXmlExit = onXmlExit,
                        )
                    if (state.editMode == LayoutEditorMode.XML) {
                        XmlLayoutEditor(
                            state = state,
                            onXmlChanged = onXmlChanged,
                            onValidate = onXmlValidate,
                            onFormat = onXmlFormat,
                            onReset = onXmlReset,
                            onShowFormatHelp = { showXmlFormatHelp = true },
                            xmlMoreExpanded = xmlMoreExpanded,
                            onXmlMoreExpandedChange = { xmlMoreExpanded = it },
                        )
                    } else {
                        VisualLayoutEditor(
                            state = state,
                            onNameChanged = onNameChanged,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onNewButton = onNewButton,
                            onEditButton = onEditButton,
                            onDuplicateButton = onDuplicateButton,
                            onRequestDelete = { buttonId -> deleteConfirmationId = buttonId },
                            onButtonSelected = onButtonSelected,
                            onDragCommitted = onDragCommitted,
                            onWidthChanged = onWidthChanged,
                            onMoveEarlier = onMoveEarlier,
                            onMoveLater = onMoveLater,
                            selectedMoreExpanded = selectedMoreExpanded,
                            onSelectedMoreExpandedChange = { selectedMoreExpanded = it },
                        )
                        state.errorMessage?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error, style = LayoutEditorTypography.metadata)
                        }
                    }
                    }
                    EditorFooter(
                        state = state,
                        onSave = onSave,
                        onDiscard = { showDiscardConfirmation = true },
                        onXmlExit = onXmlExit,
                        onXmlApply = onXmlApply,
                    )
                }
            }
        }
    }

    if (state.isShowingBackConfirmation) {
        AlertDialog(
            onDismissRequest = { onBackChoice(LayoutEditorBackChoice.CANCEL) },
            title = { Text(stringResource(R.string.layout_editor_back_confirmation_title)) },
            text = {
                Text(if (state.editMode == LayoutEditorMode.XML) {
                    stringResource(R.string.layout_editor_xml_back_confirmation)
                } else stringResource(R.string.layout_editor_back_confirmation_message))
            },
            confirmButton = {
                TextButton(
                    onClick = { onBackChoice(LayoutEditorBackChoice.SAVE) },
                    enabled = state.editMode != LayoutEditorMode.XML,
                    modifier = Modifier.editorTouchTarget().testTag("layout-editor-back-save"),
                ) {
                    Text(stringResource(R.string.layout_editor_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { onBackChoice(LayoutEditorBackChoice.DISCARD) },
                        modifier = Modifier.editorTouchTarget(),
                    ) {
                        Text(stringResource(R.string.layout_editor_discard))
                    }
                    TextButton(
                        onClick = { onBackChoice(LayoutEditorBackChoice.CANCEL) },
                        modifier = Modifier.editorTouchTarget().testTag("layout-editor-back-cancel"),
                    ) {
                        Text(stringResource(R.string.layout_editor_cancel))
                    }
                }
            },
        )
    }

    deleteConfirmationId?.let { targetId ->
        val target = state.draftProfile?.buttons?.firstOrNull { it.id == targetId }
        AlertDialog(
            onDismissRequest = { deleteConfirmationId = null },
            title = { Text(stringResource(R.string.layout_editor_delete_title)) },
            text = {
                Text(
                    if (target == null) stringResource(R.string.layout_editor_delete_missing)
                    else stringResource(R.string.layout_editor_delete_message, target.title),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (target != null) onDeleteButton(targetId)
                        deleteConfirmationId = null
                    },
                    enabled = target != null,
                    modifier = Modifier.editorTouchTarget().testTag("layout-editor-delete-confirm"),
                ) { Text(stringResource(R.string.layout_editor_delete_button_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteConfirmationId = null },
                    modifier = Modifier.editorTouchTarget().testTag("layout-editor-delete-cancel"),
                ) { Text(stringResource(R.string.layout_editor_cancel)) }
            },
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text(stringResource(R.string.layout_editor_discard_title)) },
            text = { Text(stringResource(R.string.layout_editor_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDiscardDraft()
                    },
                    modifier = Modifier.editorTouchTarget().testTag("layout-editor-discard-confirm"),
                ) { Text(stringResource(R.string.layout_editor_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDiscardConfirmation = false },
                    modifier = Modifier.editorTouchTarget().testTag("layout-editor-discard-cancel"),
                ) { Text(stringResource(R.string.layout_editor_cancel)) }
            },
        )
    }

    if (showHelp) EditorHelpDialog(xmlFormat = false, onDismiss = { showHelp = false })
    if (showXmlFormatHelp) EditorHelpDialog(xmlFormat = true, onDismiss = { showXmlFormatHelp = false })

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

    state.xmlExitConfirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { onXmlExitChoice(false) },
            title = { Text(stringResource(R.string.layout_editor_xml_discard_title)) },
            text = {
                Text(stringResource(if (action == XmlEditorExitAction.RESET_SOURCE)
                    R.string.layout_editor_xml_reset_confirmation else R.string.layout_editor_xml_exit_confirmation))
            },
            confirmButton = {
                TextButton(onClick = { onXmlExitChoice(true) }, modifier = Modifier.editorTouchTarget()) {
                    Text(stringResource(if (action == XmlEditorExitAction.RESET_SOURCE)
                        R.string.layout_editor_xml_reset else R.string.layout_editor_xml_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { onXmlExitChoice(false) }, modifier = Modifier.editorTouchTarget()) {
                    Text(stringResource(R.string.layout_editor_cancel))
                }
            },
        )
    }
}

@Composable
private fun EditorHeader(onBack: () -> Unit, onHelp: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LayoutEditorIconButton(
            icon = R.drawable.ic_ng_arrow_back,
            contentDescription = stringResource(R.string.layout_editor_back),
            onClick = onBack,
            testTag = "layout-editor-back",
        )
        Text(
            stringResource(R.string.layout_editor_title),
            modifier = Modifier.weight(1f),
            style = LayoutEditorTypography.heading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LayoutEditorIconButton(
            icon = R.drawable.ic_ng_help,
            contentDescription = stringResource(R.string.layout_editor_help_accessibility),
            onClick = onHelp,
            testTag = "layout-editor-help",
        )
    }
}

@Composable
private fun EditorEmptyContent(message: String, modifier: Modifier) {
    Text(
        message,
        modifier = modifier,
        style = LayoutEditorTypography.body,
    )
}

@Composable
private fun EditorLayoutSummary(state: LayoutEditorState) {
    val actualName = state.profileNameInput.ifBlank {
        state.draftProfile?.name ?: state.savedProfile?.name.orEmpty()
    }
    val statusId = when {
        state.editMode == LayoutEditorMode.XML && state.hasPendingXmlChanges -> R.string.layout_editor_status_xml_edited
        state.isDirty -> R.string.layout_editor_status_unsaved
        else -> R.string.layout_editor_status_saved
    }
    Row(
        modifier = Modifier.fillMaxWidth().testTag("layout-editor-summary"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                actualName,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("layout-editor-layout-name"),
            )
            Text(
                stringResource(R.string.layout_editor_saved_buttons, state.draftProfile?.buttons?.size ?: 0),
                style = LayoutEditorTypography.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("layout-editor-button-count"),
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (state.isDirty) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (state.isDirty) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                stringResource(statusId),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp).testTag("layout-editor-status"),
                style = LayoutEditorTypography.metadata,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EditorModeTabs(
    state: LayoutEditorState,
    onEnterXml: () -> Unit,
    onXmlExit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LayoutEditorTabButton(
            label = stringResource(R.string.layout_editor_visual_mode),
            selected = state.editMode == LayoutEditorMode.VISUAL,
            onClick = { if (state.editMode == LayoutEditorMode.XML) onXmlExit() },
            modifier = Modifier.weight(1f),
            testTag = "layout-editor-visual-mode",
        )
        LayoutEditorTabButton(
            label = stringResource(R.string.layout_editor_xml_mode),
            selected = state.editMode == LayoutEditorMode.XML,
            onClick = { if (state.editMode == LayoutEditorMode.VISUAL) onEnterXml() },
            modifier = Modifier.weight(1f),
            testTag = "layout-editor-xml-mode",
        )
    }
}

@Composable
private fun VisualLayoutEditor(
    state: LayoutEditorState,
    onNameChanged: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNewButton: () -> Unit,
    onEditButton: () -> Unit,
    onDuplicateButton: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onButtonSelected: (String?) -> Unit,
    onDragCommitted: (LayoutEditorDrag) -> Unit,
    onWidthChanged: (String, Int) -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    selectedMoreExpanded: Boolean,
    onSelectedMoreExpandedChange: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = state.profileNameInput,
        onValueChange = onNameChanged,
        modifier = Modifier.fillMaxWidth().testTag("layout-editor-profile-name"),
        label = { Text(stringResource(R.string.layout_editor_profile_name), fontSize = 13.sp) },
        textStyle = LayoutEditorTypography.body,
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LayoutEditorIconButton(
            icon = R.drawable.ic_ng_undo,
            contentDescription = stringResource(R.string.layout_editor_undo),
            onClick = onUndo,
            enabled = state.canUndo && state.buttonEditInput == null,
            testTag = "layout-editor-undo",
        )
        LayoutEditorIconButton(
            icon = R.drawable.ic_ng_redo,
            contentDescription = stringResource(R.string.layout_editor_redo),
            onClick = onRedo,
            enabled = state.canRedo && state.buttonEditInput == null,
            testTag = "layout-editor-redo",
        )
        LayoutEditorAccentOutlineButton(
            onClick = onNewButton,
            modifier = Modifier.weight(1f),
            testTag = "layout-editor-add-button",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(painterResource(R.drawable.ic_ng_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.layout_editor_add_action), maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
    ButtonGridPreview(
        profile = state.draftProfile ?: return,
        selectedButton = state.selectedButton,
        selectedButtonId = state.selectedButtonId,
        onButtonSelected = onButtonSelected,
        onDragCommitted = onDragCommitted,
        onWidthChanged = onWidthChanged,
        onMoveEarlier = onMoveEarlier,
        onMoveLater = onMoveLater,
        onEditButton = onEditButton,
        onDuplicateButton = onDuplicateButton,
        onRequestDelete = onRequestDelete,
        selectedMoreExpanded = selectedMoreExpanded,
        onSelectedMoreExpandedChange = onSelectedMoreExpandedChange,
        widthValidationMessage = state.widthValidationMessage,
        moveValidationMessage = state.moveValidationMessage,
    )
}

@Composable
private fun EditorFooter(
    state: LayoutEditorState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onXmlExit: () -> Unit,
    onXmlApply: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val xmlMode = state.editMode == LayoutEditorMode.XML
            val note = when {
                xmlMode -> R.string.layout_editor_xml_footer_note
                state.isDirty -> R.string.layout_editor_visual_footer_dirty
                else -> R.string.layout_editor_visual_footer_saved
            }
            Text(
                stringResource(note),
                style = LayoutEditorTypography.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("layout-editor-footer-note"),
            )
            if (xmlMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-xml-footer"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LayoutEditorOutlinedButton(
                        onClick = onXmlExit,
                        modifier = Modifier.weight(1f),
                        testTag = "layout-editor-xml-cancel",
                    ) {
                        Text(stringResource(R.string.layout_editor_xml_cancel), maxLines = 2, textAlign = TextAlign.Center)
                    }
                    LayoutEditorPrimaryButton(
                        onClick = onXmlApply,
                        modifier = Modifier.weight(1.35f),
                        testTag = "layout-editor-xml-apply",
                    ) {
                        Text(stringResource(R.string.layout_editor_xml_apply), maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-visual-footer"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LayoutEditorOutlinedButton(
                        onClick = onDiscard,
                        enabled = state.isDirty,
                        modifier = Modifier.weight(1f),
                        testTag = "layout-editor-discard",
                    ) {
                        Text(stringResource(R.string.layout_editor_discard), maxLines = 2, textAlign = TextAlign.Center)
                    }
                    LayoutEditorPrimaryButton(
                        onClick = onSave,
                        enabled = state.isDirty && state.buttonEditInput == null,
                        modifier = Modifier.weight(1.35f),
                        testTag = "layout-editor-save",
                    ) {
                        Text(stringResource(R.string.layout_editor_save), maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun XmlLayoutEditor(
    state: LayoutEditorState,
    onXmlChanged: (String) -> Unit,
    onValidate: () -> Unit,
    onFormat: () -> Unit,
    onReset: () -> Unit,
    onShowFormatHelp: () -> Unit,
    xmlMoreExpanded: Boolean,
    onXmlMoreExpandedChange: (Boolean) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val transformation = remember(dark) { XmlSourceVisualTransformation(dark) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LayoutEditorOutlinedButton(
                onClick = onValidate,
                modifier = Modifier.weight(1f),
                testTag = "layout-editor-xml-validate",
            ) { Text(stringResource(R.string.layout_editor_xml_validate), maxLines = 1) }
            LayoutEditorOutlinedButton(
                onClick = onFormat,
                modifier = Modifier.weight(1f),
                testTag = "layout-editor-xml-format",
            ) { Text(stringResource(R.string.layout_editor_xml_format), maxLines = 1) }
            Box {
                LayoutEditorIconButton(
                    icon = R.drawable.ic_ng_more,
                    contentDescription = stringResource(R.string.layout_editor_xml_more_accessibility),
                    onClick = { onXmlMoreExpandedChange(true) },
                    testTag = "layout-editor-xml-more",
                )
                DropdownMenu(
                    expanded = xmlMoreExpanded,
                    onDismissRequest = { onXmlMoreExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_xml_help), style = LayoutEditorTypography.body) },
                        onClick = {
                            onXmlMoreExpandedChange(false)
                            onShowFormatHelp()
                        },
                        modifier = Modifier.testTag("layout-editor-xml-help"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_xml_reset), style = LayoutEditorTypography.body) },
                        onClick = {
                            onXmlMoreExpandedChange(false)
                            onReset()
                        },
                        modifier = Modifier.testTag("layout-editor-xml-reset"),
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.xmlSource,
            onValueChange = onXmlChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 480.dp)
                .testTag("layout-editor-xml-source"),
            label = { Text(stringResource(R.string.layout_editor_xml_source_label), fontSize = 13.sp) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
            visualTransformation = transformation,
            isError = state.xmlMessageIsError,
            supportingText = {
                state.xmlMessage?.let { message ->
                    Text(
                        compactXmlFeedback(message),
                        color = if (state.xmlMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("layout-editor-xml-message"),
                    )
                }
            },
            minLines = 10,
            maxLines = 30,
        )
        state.errorMessage?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = LayoutEditorTypography.metadata,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun compactXmlFeedback(message: String): String {
    val singleLine = message.lineSequence().firstOrNull().orEmpty().trim()
    return if (singleLine.length <= 180) singleLine else singleLine.take(177) + "…"
}

@Composable
private fun EditorHelpDialog(xmlFormat: Boolean, onDismiss: () -> Unit) {
    var detailsExpanded by remember(xmlFormat) { mutableStateOf(xmlFormat) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (xmlFormat) R.string.layout_editor_xml_help else R.string.layout_editor_help_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(if (xmlFormat) R.string.layout_editor_xml_help_summary else R.string.layout_editor_help_visual),
                    style = LayoutEditorTypography.body,
                )
                Text(
                    stringResource(R.string.layout_editor_help_xml),
                    style = LayoutEditorTypography.body,
                )
                TextButton(
                    onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.editorTouchTarget().testTag("layout-editor-help-details-toggle"),
                ) {
                    Text(stringResource(if (detailsExpanded) R.string.layout_editor_help_details_hide else R.string.layout_editor_help_details_show))
                }
                if (detailsExpanded) {
                    Text(stringResource(R.string.layout_editor_xml_help_details), style = LayoutEditorTypography.metadata)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.editorTouchTarget().testTag("layout-editor-help-close")) {
                Text(stringResource(R.string.layout_editor_cancel))
            }
        },
    )
}

internal class XmlSourceVisualTransformation(private val dark: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val element = if (dark) Color(0xFF80CFFF) else Color(0xFF005A9C)
        val attribute = if (dark) Color(0xFFFFC078) else Color(0xFF7A3E00)
        val quoted = if (dark) Color(0xFF95E5A4) else Color(0xFF176B2B)
        val comment = if (dark) Color(0xFFB1BAC5) else Color(0xFF5F6670)
        val plainText = if (dark) Color(0xFFEDF1F5) else Color(0xFF20242A)
        val markup = if (dark) Color(0xFFA5B0BC) else Color(0xFF59616D)
        val source = text.text
        val styled = buildAnnotatedString {
            append(source)
            XmlLayoutLexer.tokens(source).forEach { token ->
                val color = when (token.kind) {
                    XmlLayoutToken.Kind.ELEMENT -> element
                    XmlLayoutToken.Kind.ATTRIBUTE -> attribute
                    XmlLayoutToken.Kind.QUOTED_VALUE -> quoted
                    XmlLayoutToken.Kind.COMMENT -> comment
                    XmlLayoutToken.Kind.TEXT -> plainText
                    XmlLayoutToken.Kind.MARKUP -> markup
                }
                addStyle(SpanStyle(color = color), token.start, token.end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

private val BUTTON_COLOR_PRESETS = listOf(
    "#FFFFFF", "#000000", "#F44336", "#FF9800",
    "#FFEB3B", "#4CAF50", "#2196F3", "#9C27B0",
)

private val BUTTON_WIDTH_PRESETS = listOf(
    2 to R.string.layout_editor_width_small,
    3 to R.string.layout_editor_width_medium,
    5 to R.string.layout_editor_width_half,
    10 to R.string.layout_editor_width_full,
)

@Composable
private fun ButtonEditDialog(
    input: ButtonEditInput,
    validation: ButtonEditValidation?,
    saveError: String?,
    onTitleChanged: (String) -> Unit,
    onCommandChanged: (String) -> Unit,
    onWidthChanged: (String) -> Unit,
    onColorChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = when (input.mode) {
        ButtonEditMode.CREATE -> R.string.layout_editor_button_add_title
        ButtonEditMode.EDIT -> R.string.layout_editor_button_edit_title
        ButtonEditMode.DUPLICATE -> R.string.layout_editor_button_duplicate_title
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title), style = LayoutEditorTypography.heading) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input.title,
                    onValueChange = onTitleChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-button-title"),
                    label = { Text(stringResource(R.string.layout_editor_button_title), fontSize = 13.sp) },
                    textStyle = LayoutEditorTypography.body,
                    singleLine = true,
                    isError = validation?.title != null,
                    supportingText = {
                        validation?.title?.let { Text(it, color = MaterialTheme.colorScheme.error, style = LayoutEditorTypography.metadata) }
                    },
                )
                OutlinedTextField(
                    value = input.command,
                    onValueChange = onCommandChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-button-command"),
                    label = { Text(stringResource(R.string.layout_editor_button_command), fontSize = 13.sp) },
                    textStyle = LayoutEditorTypography.body,
                    minLines = 3,
                    isError = validation?.command != null,
                    supportingText = {
                        validation?.command?.let { Text(it, color = MaterialTheme.colorScheme.error, style = LayoutEditorTypography.metadata) }
                    },
                )
                OutlinedTextField(
                    value = input.width,
                    onValueChange = onWidthChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-button-width"),
                    label = { Text(stringResource(R.string.layout_editor_button_width), fontSize = 13.sp) },
                    textStyle = LayoutEditorTypography.body,
                    singleLine = true,
                    isError = validation?.width != null,
                    supportingText = {
                        validation?.width?.let { Text(it, color = MaterialTheme.colorScheme.error, style = LayoutEditorTypography.metadata) }
                    },
                )
                Text(stringResource(R.string.layout_editor_width_presets), style = LayoutEditorTypography.metadata)
                BUTTON_WIDTH_PRESETS.chunked(2).forEach { rowPresets ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPresets.forEach { (width, label) ->
                            val selected = input.width.toIntOrNull() == width
                            val buttonContent: @Composable () -> Unit = {
                                Text(
                                    stringResource(label),
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (selected) {
                                LayoutEditorAccentOutlineButton(
                                    onClick = { onWidthChanged(width.toString()) },
                                    modifier = Modifier.weight(1f).testTag("layout-editor-width-preset-$width"),
                                    content = buttonContent,
                                )
                            } else {
                                LayoutEditorOutlinedButton(
                                    onClick = { onWidthChanged(width.toString()) },
                                    modifier = Modifier.weight(1f).testTag("layout-editor-width-preset-$width"),
                                    content = buttonContent,
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = input.color,
                    onValueChange = onColorChanged,
                    modifier = Modifier.fillMaxWidth().testTag("layout-editor-button-color"),
                    label = { Text(stringResource(R.string.layout_editor_button_color), fontSize = 13.sp) },
                    textStyle = LayoutEditorTypography.body,
                    singleLine = true,
                    isError = validation?.color != null,
                    supportingText = {
                        validation?.color?.let { Text(it, color = MaterialTheme.colorScheme.error, style = LayoutEditorTypography.metadata) }
                    },
                )
                Text(stringResource(R.string.layout_editor_color_presets), style = LayoutEditorTypography.metadata)
                BUTTON_COLOR_PRESETS.chunked(4).forEach { rowColors ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { preset ->
                            val selected = input.color.equals(preset, ignoreCase = true)
                            val colorDescription = stringResource(R.string.layout_editor_select_color_accessibility, preset)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(preset.toColorInt()))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape,
                                    )
                                    .clickable { onColorChanged(preset) }
                                    .semantics {
                                        contentDescription = colorDescription
                                        role = Role.Button
                                        this.selected = selected
                                    }
                                    .testTag("layout-editor-color-$preset"),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Text(
                                        stringResource(R.string.layout_editor_selection_marker),
                                        color = if (Color(preset.toColorInt()).luminance() > 0.5f) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = LayoutEditorTypography.metadata) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave() },
                modifier = Modifier.editorTouchTarget().testTag("layout-editor-button-save"),
            ) {
                Text(stringResource(if (input.mode == ButtonEditMode.CREATE) R.string.layout_editor_button_add else R.string.layout_editor_button_apply))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.editorTouchTarget().testTag("layout-editor-button-cancel"),
            ) {
                Text(stringResource(R.string.layout_editor_cancel))
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
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onEditButton: () -> Unit,
    onDuplicateButton: () -> Unit,
    onRequestDelete: (String) -> Unit,
    selectedMoreExpanded: Boolean,
    onSelectedMoreExpandedChange: (Boolean) -> Unit,
    widthValidationMessage: String?,
    moveValidationMessage: String?,
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

    Column(
        modifier = Modifier.semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.layout_editor_preview_heading),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            stringResource(R.string.layout_editor_preview_hint),
            style = LayoutEditorTypography.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                                        position = profile.buttons.indexOfFirst { it.id == button.id } + 1,
                                        total = profile.buttons.size,
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
        Text(
            stringResource(R.string.layout_editor_preview_reorder_hint),
            style = LayoutEditorTypography.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
        SelectedButtonProperties(
            profile = profile,
            button = selectedButton,
            onWidthChanged = onWidthChanged,
            onMoveEarlier = onMoveEarlier,
            onMoveLater = onMoveLater,
            onClearSelection = { onButtonSelected(null) },
            onEditButton = onEditButton,
            onDuplicateButton = onDuplicateButton,
            onRequestDelete = onRequestDelete,
            moreExpanded = selectedMoreExpanded,
            onMoreExpandedChange = onSelectedMoreExpandedChange,
            widthValidationMessage = widthValidationMessage,
            moveValidationMessage = moveValidationMessage,
        )
    }
}

@Composable
private fun PreviewButton(
    button: LayoutButton,
    selected: Boolean,
    position: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = remember(button.color) {
        Color(button.color.toColorInt())
    }
    val textColor = if (backgroundColor.luminance() > 0.5f) Color.Black else Color.White
    val accessibleLabel = stringResource(
        R.string.layout_editor_preview_button_accessibility,
        button.title,
        position,
        total,
        button.width,
    )

    Surface(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(LAYOUT_EDITOR_PREVIEW_BUTTON_HEIGHT_DP.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibleLabel
                this.selected = selected
                role = Role.Button
            },
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        contentColor = textColor,
        border = BorderStroke(
            width = if (selected) 3.dp else 1.dp,
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
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.layout_editor_selection_marker),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedButtonProperties(
    profile: LayoutProfile,
    button: LayoutButton?,
    onWidthChanged: (String, Int) -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onClearSelection: () -> Unit,
    onEditButton: () -> Unit,
    onDuplicateButton: () -> Unit,
    onRequestDelete: (String) -> Unit,
    moreExpanded: Boolean,
    onMoreExpandedChange: (Boolean) -> Unit,
    widthValidationMessage: String?,
    moveValidationMessage: String?,
) {
    if (button == null) return
    val buttonPosition = profile.buttons.indexOfFirst { it.id == button.id }
    val spokenPosition = (buttonPosition + 1).coerceAtLeast(1)
    val earlierLabel = stringResource(
        if (buttonPosition <= 0) R.string.layout_editor_move_earlier_boundary_accessibility
        else R.string.layout_editor_move_earlier_accessibility,
        button.title,
        spokenPosition,
        profile.buttons.size,
    )
    val laterLabel = stringResource(
        if (buttonPosition == profile.buttons.lastIndex) R.string.layout_editor_move_later_boundary_accessibility
        else R.string.layout_editor_move_later_accessibility,
        button.title,
        spokenPosition,
        profile.buttons.size,
    )
    val decreaseLabel = stringResource(R.string.layout_editor_decrease_width_accessibility, button.title, button.width)
    val increaseLabel = stringResource(R.string.layout_editor_increase_width_accessibility, button.title, button.width)
    val atFirst = buttonPosition <= 0
    val atLast = buttonPosition == profile.buttons.lastIndex

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("layout-editor-selected-button-controls"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    button.title,
                    modifier = Modifier.weight(1f).testTag("layout-editor-selected-title"),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LayoutEditorIconButton(
                    icon = R.drawable.ic_ng_close,
                    contentDescription = stringResource(R.string.layout_editor_clear_selection),
                    onClick = onClearSelection,
                    testTag = "layout-editor-clear-selection",
                )
            }
            Text(
                stringResource(R.string.layout_editor_preview_title, button.title),
                style = LayoutEditorTypography.metadata,
            )
            Text(
                stringResource(
                    R.string.layout_editor_preview_command,
                    button.command.ifBlank { stringResource(R.string.layout_editor_preview_empty_command) },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = LayoutEditorTypography.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.layout_editor_preview_width, button.width, LAYOUT_COLUMN_COUNT),
                    modifier = Modifier.weight(1f),
                    style = LayoutEditorTypography.metadata,
                )
                LayoutEditorIconButton(
                    icon = R.drawable.ic_ng_minus,
                    contentDescription = decreaseLabel,
                    onClick = { onWidthChanged(button.id, button.width - 1) },
                    testTag = "layout-editor-decrease-width",
                )
                LayoutEditorIconButton(
                    icon = R.drawable.ic_ng_add,
                    contentDescription = increaseLabel,
                    onClick = { onWidthChanged(button.id, button.width + 1) },
                    testTag = "layout-editor-increase-width",
                )
            }
            widthValidationMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = LayoutEditorTypography.metadata,
                    modifier = Modifier.testTag("layout-editor-width-error"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                    .background(Color(button.color.toColorInt()), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Text(
                    stringResource(R.string.layout_editor_preview_color, button.color),
                    modifier = Modifier.padding(start = 8.dp),
                    style = LayoutEditorTypography.metadata,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LayoutEditorOutlinedButton(
                    onClick = onEditButton,
                    modifier = Modifier.weight(1f),
                    testTag = "layout-editor-edit-button",
                ) {
                    Text(
                        stringResource(R.string.layout_editor_edit),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        style = LayoutEditorTypography.control.copy(fontSize = 12.sp),
                    )
                }
                LayoutEditorOutlinedButton(
                    onClick = onDuplicateButton,
                    modifier = Modifier.weight(1.15f),
                    testTag = "layout-editor-duplicate-button",
                ) {
                    Text(
                        stringResource(R.string.layout_editor_duplicate),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        style = LayoutEditorTypography.control.copy(fontSize = 12.sp),
                    )
                }
                Box {
                    LayoutEditorIconButton(
                        icon = R.drawable.ic_ng_more,
                        contentDescription = stringResource(R.string.layout_editor_selection_more_accessibility),
                        onClick = { onMoreExpandedChange(true) },
                        testTag = "layout-editor-selection-more",
                    )
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { onMoreExpandedChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(if (atFirst) R.string.layout_editor_move_earlier_boundary else R.string.layout_editor_move_earlier),
                                    style = LayoutEditorTypography.body,
                                )
                            },
                            onClick = {
                                onMoreExpandedChange(false)
                                onMoveEarlier()
                            },
                            enabled = !atFirst,
                            modifier = Modifier.semantics { contentDescription = earlierLabel }
                                .testTag("layout-editor-move-earlier"),
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(if (atLast) R.string.layout_editor_move_later_boundary else R.string.layout_editor_move_later),
                                    style = LayoutEditorTypography.body,
                                )
                            },
                            onClick = {
                                onMoreExpandedChange(false)
                                onMoveLater()
                            },
                            enabled = !atLast,
                            modifier = Modifier.semantics { contentDescription = laterLabel }
                                .testTag("layout-editor-move-later"),
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.layout_editor_delete_button),
                                    color = MaterialTheme.colorScheme.error,
                                    style = LayoutEditorTypography.body,
                                )
                            },
                            onClick = {
                                onMoreExpandedChange(false)
                                onRequestDelete(button.id)
                            },
                            modifier = Modifier.testTag("layout-editor-delete-button"),
                        )
                    }
                }
            }
            moveValidationMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = LayoutEditorTypography.metadata,
                    modifier = Modifier
                        .testTag("layout-editor-move-message")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (widthValidationMessage == null) {
                Text(
                    stringResource(R.string.layout_editor_preview_valid),
                    color = MaterialTheme.colorScheme.primary,
                    style = LayoutEditorTypography.metadata,
                )
            }
        }
    }
}

private fun Modifier.editorTouchTarget(): Modifier =
    heightIn(min = 48.dp).widthIn(min = 48.dp)
