package com.stripe1.xmouse.layout

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputLayout
import com.stripe1.xmouse.R
import com.stripe1.xmouse.SystemWindowInsets
import com.stripe1.xmouse.ListEditActivity
import java.io.File
import kotlinx.coroutines.launch

data class ActionEditorForm(
    val title: String,
    val type: ActionCommandType,
    val command: String,
    val sizeId: Int,
    val presetColor: String?,
    val colorTouched: Boolean,
    val customColor: String,
    val customColorVisible: Boolean,
)

/** Full-height native action form. It edits profiles only through LayoutEditorViewModel. */
class ActionEditorActivity : AppCompatActivity() {
    private lateinit var session: ActionEditorSessionViewModel
    private lateinit var nameField: TextInputLayout
    private lateinit var nameInput: EditText
    private lateinit var commandField: TextInputLayout
    private lateinit var commandInput: EditText
    private lateinit var typeGroup: RadioGroup
    private lateinit var sizeGroup: RadioGroup
    private lateinit var colorGroup: MaterialButtonToggleGroup
    private lateinit var customColorField: TextInputLayout
    private lateinit var customColorInput: EditText
    private lateinit var commandError: TextView
    private lateinit var status: TextView
    private var originalCommand = ""
    private var currentCommandType = ActionCommandType.ADVANCED_CUSTOM_COMMAND
    private var selectedPresetColor: String? = null
    private var colorTouched = false
    private var formInitialized = false
    private var updatingControls = false
    private var advancedPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            finish()
            return
        }
        val buttonId = intent.getStringExtra(EXTRA_BUTTON_ID)
        setContentView(R.layout.activity_action_editor)
        val root = findViewById<View>(R.id.action_editor_root)
        root.isSaveFromParentEnabled = false
        SystemWindowInsets.applyToAndConsume(window, root)

        nameField = findViewById(R.id.action_name_field)
        nameInput = findViewById(R.id.action_name)
        commandField = findViewById(R.id.action_command_field)
        commandInput = findViewById(R.id.action_command)
        typeGroup = findViewById(R.id.action_type_group)
        sizeGroup = findViewById(R.id.action_size_group)
        colorGroup = findViewById(R.id.action_color_group)
        customColorField = findViewById(R.id.action_custom_color_field)
        customColorInput = findViewById(R.id.action_custom_color)
        commandError = findViewById(R.id.action_command_error)
        status = findViewById(R.id.action_editor_status)

        val repository = LayoutProfileRepository(File(filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))
        session = ViewModelProvider(
            this,
            ActionEditorSessionViewModel.Factory(profileId, buttonId, repository),
        )[ActionEditorSessionViewModel::class.java]

        findViewById<TextView>(R.id.action_editor_title).setText(
            if (buttonId == null) R.string.ng_action_editor_title_add else R.string.ng_action_editor_title_edit,
        )
        findViewById<View>(R.id.action_editor_back).setOnClickListener { finish() }
        findViewById<View>(R.id.action_editor_save).setOnClickListener { submitForm() }
        findViewById<View>(R.id.action_editor_delete).setOnClickListener { confirmDelete() }
        (findViewById<View>(R.id.action_size_custom) as RadioButton).visibility =
            if (buttonId == null) View.GONE else View.VISIBLE
        findViewById<View>(R.id.action_editor_delete).visibility =
            if (buttonId == null) View.GONE else View.VISIBLE
        findViewById<View>(R.id.action_editor_open_advanced).setOnClickListener {
            startActivity(android.content.Intent(this, LayoutEditorActivity::class.java)
                .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, profileId))
            finish()
        }
        findViewById<View>(R.id.action_editor_new_script).setOnClickListener {
            startActivity(android.content.Intent(this, ListEditActivity::class.java)
                .putExtra("intVariableType", 1).putExtra(ListEditActivity.EXTRA_ADD_SCRIPT, true))
            finish()
        }
        setupColorPresets()
        setupTypeSelection()
        nameInput.addTextChangedListener(SimpleTextWatcher {
            nameField.error = null
            if (!session.state.value.retrySave) hideStatus()
        })
        commandInput.addTextChangedListener(SimpleTextWatcher {
            commandField.error = null
            commandError.visibility = View.GONE
            if (!session.state.value.retrySave) hideStatus()
        })
        customColorInput.addTextChangedListener(SimpleTextWatcher {
            if (!updatingControls) {
                colorTouched = true
                selectedPresetColor = null
                customColorField.error = null
                hideStatus()
            }
        })
        findViewById<View>(R.id.action_color_advanced_toggle).setOnClickListener {
            val show = customColorField.visibility != View.VISIBLE
            customColorField.visibility = if (show) View.VISIBLE else View.GONE
            if (show) customColorInput.requestFocus()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect(::render)
            }
        }
    }

    private fun setupColorPresets() {
        val colors = linkedMapOf(
            R.id.action_color_white to ACTION_COLOR_WHITE,
            R.id.action_color_green to ACTION_COLOR_GREEN,
            R.id.action_color_blue to ACTION_COLOR_BLUE,
            R.id.action_color_red to ACTION_COLOR_RED,
        )
        val labels = mapOf(
            R.id.action_color_white to R.string.ng_action_color_white,
            R.id.action_color_green to R.string.ng_action_color_green,
            R.id.action_color_blue to R.string.ng_action_color_blue,
            R.id.action_color_red to R.string.ng_action_color_red,
        )
        colors.forEach { (id, color) ->
            val button = findViewById<com.google.android.material.button.MaterialButton>(id)
            button.text = getString(labels.getValue(id))
            val background = android.graphics.Color.parseColor(color)
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(background)
            val blackContrast = ColorUtils.calculateContrast(android.graphics.Color.BLACK, background)
            val whiteContrast = ColorUtils.calculateContrast(android.graphics.Color.WHITE, background)
            button.setTextColor(if (blackContrast >= whiteContrast) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            button.contentDescription = getString(R.string.ng_action_color_accessibility, getString(labels.getValue(id)))
            button.tag = color
        }
        colorGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked && !updatingControls) {
                colorTouched = true
                selectedPresetColor = colors[checkedId]
                customColorField.error = null
                customColorField.visibility = View.GONE
                findViewById<TextView>(R.id.action_color_custom_note).visibility = View.GONE
                hideStatus()
            }
        }
    }

    private fun setupTypeSelection() {
        typeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingControls) return@setOnCheckedChangeListener
            val next = if (checkedId == R.id.action_type_shortcut) ActionCommandType.KEYBOARD_SHORTCUT
                else ActionCommandType.ADVANCED_CUSTOM_COMMAND
            if (next == currentCommandType) return@setOnCheckedChangeListener
            val visible = commandInput.text?.toString().orEmpty()
            val converted = when (currentCommandType) {
                ActionCommandType.KEYBOARD_SHORTCUT -> try {
                    ActionCommandCodec.serialize(ActionCommandType.KEYBOARD_SHORTCUT, visible, originalCommand)
                } catch (_: IllegalArgumentException) { visible }
                ActionCommandType.ADVANCED_CUSTOM_COMMAND -> {
                    val parsed = ActionCommandCodec.classify(visible)
                    if (parsed.type == ActionCommandType.KEYBOARD_SHORTCUT) parsed.value else visible
                }
            }
            currentCommandType = next
            commandInput.setText(converted)
            updateCommandField()
            commandError.visibility = View.GONE
            hideStatus()
        }
    }

    private fun render(state: ActionEditorSessionState) {
        if (state.saved) {
            setResult(RESULT_OK)
            finish()
            return
        }
        if (state.needsAdvancedEditor) {
            showAdvancedDraftPrompt()
            return
        }
        val input = state.editorState?.buttonEditInput
        if (!formInitialized && input != null && !state.retrySave) {
            initializeForm(state, input)
        }
        val saving = state.isSaving
        findViewById<View>(R.id.action_editor_save).isEnabled = !saving
        findViewById<View>(R.id.action_editor_delete).isEnabled = !saving && !state.retrySave
        findViewById<View>(R.id.action_editor_back).isEnabled = !saving
        findViewById<View>(R.id.action_editor_open_advanced).isEnabled = !saving
        findViewById<View>(R.id.action_editor_new_script).isEnabled = !saving && !state.retrySave
        nameInput.isEnabled = !saving && !state.retrySave
        commandInput.isEnabled = !saving && !state.retrySave
        typeGroup.isEnabled = !saving && !state.retrySave
        for (index in 0 until typeGroup.childCount) typeGroup.getChildAt(index).isEnabled = !saving && !state.retrySave
        sizeGroup.isEnabled = !saving && !state.retrySave
        for (index in 0 until sizeGroup.childCount) sizeGroup.getChildAt(index).isEnabled = !saving && !state.retrySave
        colorGroup.isEnabled = !saving && !state.retrySave
        for (index in 0 until colorGroup.childCount) colorGroup.getChildAt(index).isEnabled = !saving && !state.retrySave
        customColorInput.isEnabled = !saving && !state.retrySave
        findViewById<View>(R.id.action_color_advanced_toggle).isEnabled = !saving && !state.retrySave
        val saveButton = findViewById<android.widget.Button>(R.id.action_editor_save)
        saveButton.setText(if (state.retrySave) R.string.ng_action_editor_retry_save
            else if (intent.getStringExtra(EXTRA_BUTTON_ID) == null) R.string.ng_action_editor_add
            else R.string.ng_action_editor_save)
        val validation = state.editorState?.buttonEditValidation
        if (validation?.title != null) nameField.error = validation.title
        if (validation?.command != null) commandField.error = validation.command
        if (validation?.color != null) customColorField.error = validation.color
        when {
            state.commandError != null -> showCommandError(state.commandError)
            state.statusMessage != null -> showStatus(state.statusMessage)
            state.editorState?.buttonEditSaveError != null -> showStatus(state.editorState.buttonEditSaveError)
            state.editorState?.errorMessage != null -> showStatus(state.editorState.errorMessage)
            state.isLoading -> showStatus(getString(R.string.layout_editor_loading))
            else -> hideStatus()
        }
    }

    private fun initializeForm(state: ActionEditorSessionState, input: ButtonEditInput) {
        updatingControls = true
        formInitialized = true
        originalCommand = state.initialCommand ?: input.command
        nameInput.setText(input.title)
        val parsed = if (intent.getStringExtra(EXTRA_BUTTON_ID) == null)
            ParsedActionCommand(ActionCommandType.KEYBOARD_SHORTCUT, "")
        else ActionCommandCodec.classify(originalCommand)
        currentCommandType = parsed.type
        typeGroup.check(if (parsed.type == ActionCommandType.KEYBOARD_SHORTCUT)
            R.id.action_type_shortcut else R.id.action_type_advanced)
        commandInput.setText(if (parsed.type == ActionCommandType.KEYBOARD_SHORTCUT) parsed.value else originalCommand)
        updateCommandField()

        val width = state.initialWidth ?: input.width.toIntOrNull() ?: 5
        findViewById<View>(R.id.action_size_custom).visibility = if (width == 5 || width == 10) View.GONE else View.VISIBLE
        when (width) {
            5 -> sizeGroup.check(R.id.action_size_regular)
            10 -> sizeGroup.check(R.id.action_size_wide)
            else -> {
                sizeGroup.check(R.id.action_size_custom)
                findViewById<RadioButton>(R.id.action_size_custom).text =
                    getString(R.string.ng_action_editor_custom)
                findViewById<TextView>(R.id.action_size_custom_note).apply {
                    text = getString(R.string.ng_action_editor_custom_size, width)
                    visibility = View.VISIBLE
                }
            }
        }
        val color = state.initialColor ?: input.color
        val colorId = when {
            color.equals(ACTION_COLOR_WHITE, ignoreCase = true) -> R.id.action_color_white
            color.equals(ACTION_COLOR_GREEN, ignoreCase = true) -> R.id.action_color_green
            color.equals(ACTION_COLOR_BLUE, ignoreCase = true) -> R.id.action_color_blue
            color.equals(ACTION_COLOR_RED, ignoreCase = true) -> R.id.action_color_red
            else -> View.NO_ID
        }
        if (colorId != View.NO_ID) colorGroup.check(colorId)
        if (colorId == View.NO_ID) findViewById<TextView>(R.id.action_color_custom_note).apply {
            text = getString(R.string.ng_action_editor_custom_color, color)
            visibility = View.VISIBLE
            customColorInput.setText(color)
            customColorField.visibility = View.GONE
        } else {
            customColorInput.setText("")
            customColorField.visibility = View.GONE
        }
        selectedPresetColor = null
        colorTouched = false
        session.form?.let { form ->
            nameInput.setText(form.title)
            currentCommandType = form.type
            typeGroup.check(if (form.type == ActionCommandType.KEYBOARD_SHORTCUT)
                R.id.action_type_shortcut else R.id.action_type_advanced)
            updateCommandField()
            commandInput.setText(form.command)
            sizeGroup.check(form.sizeId)
            colorGroup.clearChecked()
            val presetId = when (form.presetColor) {
                ACTION_COLOR_WHITE -> R.id.action_color_white
                ACTION_COLOR_GREEN -> R.id.action_color_green
                ACTION_COLOR_BLUE -> R.id.action_color_blue
                ACTION_COLOR_RED -> R.id.action_color_red
                else -> View.NO_ID
            }
            if (presetId != View.NO_ID) colorGroup.check(presetId)
            selectedPresetColor = form.presetColor
            colorTouched = form.colorTouched
            customColorInput.setText(form.customColor)
            customColorField.visibility = if (form.customColorVisible) View.VISIBLE else View.GONE
        }
        updatingControls = false
    }

    private fun updateCommandField() {
        val shortcut = currentCommandType == ActionCommandType.KEYBOARD_SHORTCUT
        commandField.hint = getString(if (shortcut) R.string.ng_action_editor_shortcut_hint
            else R.string.ng_action_editor_command_hint)
        commandInput.inputType = if (shortcut) {
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        commandInput.maxLines = if (shortcut) 1 else 5
        commandInput.setSingleLine(shortcut)
    }

    private fun submitForm() {
        nameField.error = null
        val name = nameInput.text?.toString().orEmpty()
        if (name.isBlank()) {
            nameField.error = getString(R.string.ng_action_editor_name_error)
            nameInput.requestFocus()
            return
        }
        val presetWidth = when (sizeGroup.checkedRadioButtonId) {
            R.id.action_size_regular -> 5
            R.id.action_size_wide -> 10
            else -> null
        }
        session.save(
            ActionEditorSubmission(
                title = name,
                commandType = currentCommandType,
                commandValue = commandInput.text?.toString().orEmpty(),
                presetWidth = presetWidth,
                colorOverride = if (!colorTouched) null else (selectedPresetColor
                    ?: customColorInput.text?.toString().orEmpty()),
            ),
        )
    }

    private fun confirmDelete() {
        val title = nameInput.text?.toString().orEmpty()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.ng_action_editor_delete_title)
            .setMessage(getString(R.string.ng_action_editor_delete_message, title))
            .setNegativeButton(R.string.ng_action_editor_cancel, null)
            .setPositiveButton(R.string.ng_action_editor_delete) { _, _ -> session.delete() }
            .show()
    }

    private fun showAdvancedDraftPrompt() {
        if (advancedPromptShown) return
        advancedPromptShown = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.layout_editor_title)
            .setMessage(R.string.ng_action_editor_draft_exists)
            .setPositiveButton(R.string.ng_action_editor_open_advanced) { _, _ ->
                startActivity(android.content.Intent(this, LayoutEditorActivity::class.java)
                    .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID,
                        intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()))
                finish()
            }
            .setNegativeButton(R.string.ng_action_editor_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showCommandError(message: String) {
        commandError.text = message
        commandError.visibility = View.VISIBLE
        status.visibility = View.GONE
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() { status.visibility = View.GONE }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::session.isInitialized && formInitialized) session.form = ActionEditorForm(
            nameInput.text.toString(), currentCommandType, commandInput.text.toString(),
            sizeGroup.checkedRadioButtonId, selectedPresetColor, colorTouched,
            customColorInput.text.toString(), customColorField.visibility == View.VISIBLE,
        )
        // Form controls disable framework state saving; raw commands remain only in this ViewModel.
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_PROFILE_ID = "com.stripe1.xmouse.action.profile_id"
        const val EXTRA_BUTTON_ID = "com.stripe1.xmouse.action.button_id"
        const val PROFILE_DIRECTORY = LayoutEditorActivity.PROFILE_DIRECTORY
        const val ACTION_COLOR_WHITE = "#FFFFFF"
        const val ACTION_COLOR_GREEN = "#E0EFE8"
        const val ACTION_COLOR_BLUE = "#E8EFFA"
        const val ACTION_COLOR_RED = "#FBE9E7"

        @JvmStatic fun intent(context: android.content.Context, profileId: String, buttonId: String? = null) =
            android.content.Intent(context, ActionEditorActivity::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .also { if (buttonId != null) it.putExtra(EXTRA_BUTTON_ID, buttonId) }
    }
}

private class SimpleTextWatcher(private val changed: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed()
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
