package com.stripe1.xmouse

import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.material.switchmaterial.SwitchMaterial

/** Shared production/isolated-fixture binding. No session, credential, or command access. */
class NativeSettingsBinding(
    private val root: View,
    private val preferences: SharedPreferences,
    back: () -> Unit,
    advanced: () -> Unit,
) {
    private var rendering = false
    private var dialog: AlertDialog? = null
    private val pointer = root.findViewById<Button>(R.id.settings_pointer_speed)
    private val natural = root.findViewById<SwitchMaterial>(R.id.settings_natural_scroll)
    private val autoClear = root.findViewById<SwitchMaterial>(R.id.settings_auto_clear)

    init {
        root.findViewById<View>(R.id.settings_back).setOnClickListener { back() }
        root.findViewById<View>(R.id.settings_advanced).setOnClickListener { advanced() }
        pointer.setOnClickListener { choosePointerSpeed() }
        natural.setOnCheckedChangeListener { _, checked ->
            if (!rendering) {
                preferences.edit {
                    putBoolean(InputPreferences.INVERT_SCROLL, InputPreferences.invertedForNaturalScrolling(checked))
                }
                reload()
            }
        }
        autoClear.setOnCheckedChangeListener { _, checked ->
            if (!rendering) preferences.edit { putBoolean(InputPreferences.KEYBOARD_AUTO_CLEAR, checked) }
        }
        reload()
    }

    fun reload() {
        val saved = InputPreferences.read(preferences.all)
        rendering = true
        val index = saved.pointerChoiceIndex()
        val scale = if (index >= 0) root.resources.getStringArray(R.array.pref_sensitivity_list_titles)[index]
            else root.context.getString(R.string.ng_settings_saved_scale, saved.pointerSpeed.toString())
        pointer.text = root.context.getString(R.string.ng_settings_pointer_value, scale)
        natural.isChecked = saved.naturalScrolling()
        autoClear.isChecked = saved.keyboardAutoClear
        root.findViewById<TextView>(R.id.settings_scroll_description).setText(
            if (natural.isChecked) R.string.ng_settings_natural_on else R.string.ng_settings_natural_off,
        )
        rendering = false
    }

    private fun choosePointerSpeed() {
        dismissDialog()
        val values = root.resources.getStringArray(R.array.pref_sensitivity_list_values)
        dialog = AlertDialog.Builder(root.context)
            .setTitle(R.string.ng_settings_pointer_speed)
            .setSingleChoiceItems(root.resources.getStringArray(R.array.pref_sensitivity_list_titles),
                InputPreferences.read(preferences.all).pointerChoiceIndex()) { selected, index ->
                preferences.edit {
                    putString(InputPreferences.POINTER_SPEED, InputPreferences.pointerChoiceForWrite(values[index]))
                }
                reload()
                selected.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { it.show() }
    }

    fun dismissDialog() { dialog?.dismiss(); dialog = null }
}
