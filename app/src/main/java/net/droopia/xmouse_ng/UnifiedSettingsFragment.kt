package net.droopia.xmouse_ng

import android.content.SharedPreferences
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.preference.PreferenceManager
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import net.droopia.xmouse_ng.secrets.AndroidPrivateKeys
import net.droopia.xmouse_ng.secrets.AndroidCredentials
import net.droopia.xmouse_ng.secrets.CredentialUi
import net.droopia.xmouse_ng.secrets.PrivateKeySettingsController
import java.lang.ref.WeakReference

/** Compact settings rows with explicit bindings; rendering never persists missing defaults. */
class UnifiedSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var saved: SharedPreferences
    private var credentialPrompts: CredentialUi? = null
    private var privateKeyController: PrivateKeySettingsController? = null

    val credentialUiForTesting: CredentialUi? get() = credentialPrompts
    val keyImportForTesting: PrivateKeySettingsController? get() = privateKeyController

    private val section: String
        get() = arguments?.getString(ARG_SECTION) ?: NativeSettingsActivity.SECTION_ALL

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        saved = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen

        when (section) {
            NativeSettingsActivity.SECTION_POINTER -> addTouchpadPreferences(screen)
            NativeSettingsActivity.SECTION_KEYBOARD -> addKeyboardPreferences(screen)
            NativeSettingsActivity.SECTION_AUTHENTICATION -> addAuthenticationPreferences(screen)
            NativeSettingsActivity.SECTION_APPEARANCE -> addAppearancePreferences(screen)
            NativeSettingsActivity.SECTION_ABOUT -> addAboutPreferences(screen)
            else -> addSettingsIndex(screen)
        }
        (requireActivity() as? SharedSettingsActivity)?.showSectionTitle(titleForSection())
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView {
        return super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            addItemDecoration(SettingsDivider(requireContext().resources.displayMetrics.density,
                requireContext().getColor(R.color.ng_border)))
            clipToPadding = false
        }
    }

    override fun onStart() {
        super.onStart()
        credentialPrompts?.start()
    }

    override fun onStop() {
        credentialPrompts?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        credentialPrompts?.stop()
        privateKeyController?.destroy()
        activity?.let { (it as? SharedSettingsActivity)?.apply { credentialUi = null; keyImport = null } }
        credentialPrompts = null
        privateKeyController = null
        super.onDestroy()
    }

    fun findSettingPreference(key: String): Preference? = findPreference(key)

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean =
        privateKeyController?.onActivityResult(requestCode, resultCode, data) == true ||
            credentialPrompts?.onActivityResult(requestCode, resultCode, data) == true

    @StringRes
    private fun titleForSection(): Int = when (section) {
        NativeSettingsActivity.SECTION_POINTER -> R.string.ng_settings_touchpad_title
        NativeSettingsActivity.SECTION_KEYBOARD -> R.string.ng_settings_keyboard_title
        NativeSettingsActivity.SECTION_AUTHENTICATION -> R.string.ng_settings_auth_title
        NativeSettingsActivity.SECTION_APPEARANCE -> R.string.ng_appearance_title
        NativeSettingsActivity.SECTION_ABOUT -> R.string.ng_about_title
        else -> R.string.title_activity_settings
    }

    private fun addSettingsIndex(screen: PreferenceScreen) {
        addSectionLink(screen, "settings_link_touchpad", R.string.ng_settings_touchpad_title,
            R.string.ng_settings_index_touchpad_summary, NativeSettingsActivity.SECTION_POINTER)
        addSectionLink(screen, "settings_link_keyboard", R.string.ng_settings_keyboard_title,
            R.string.ng_settings_index_keyboard_summary, NativeSettingsActivity.SECTION_KEYBOARD)
        addSectionLink(screen, "settings_link_authentication", R.string.ng_settings_auth_title,
            R.string.ng_settings_index_auth_summary, NativeSettingsActivity.SECTION_AUTHENTICATION)
        addSectionLink(screen, "settings_link_appearance", R.string.ng_appearance_title,
            R.string.ng_settings_index_appearance_summary, NativeSettingsActivity.SECTION_APPEARANCE)
        addSectionLink(screen, "settings_link_about", R.string.ng_about_title,
            R.string.ng_settings_index_about_summary, NativeSettingsActivity.SECTION_ABOUT)
    }

    private fun addSectionLink(screen: PreferenceScreen, key: String, @StringRes title: Int,
                               @StringRes summary: Int, section: String) {
        screen.addPreference(row(Preference(requireContext()).apply {
            this.key = key
            this.title = getString(title)
            this.summary = getString(summary)
            isPersistent = false
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                startActivity(Intent(requireContext(), NativeSettingsActivity::class.java)
                    .putExtra(NativeSettingsActivity.EXTRA_SECTION, section))
                true
            }
        }))
    }

    private fun addTouchpadPreferences(screen: PreferenceScreen) {
        val rawSpeed = storedString(InputPreferences.POINTER_SPEED, "1.0f")
        val speed = configuredList(
            InputPreferences.POINTER_SPEED,
            getString(R.string.pref_title_sensitivity),
            R.array.pref_sensitivity_list_titles,
            R.array.pref_sensitivity_list_values,
            rawSpeed,
            "1.0f",
            "Saved value: %s",
        )
        screen.addPreference(speed)

        val input = InputPreferences.read(saved.all)
        screen.addPreference(row(SwitchPreferenceCompat(requireContext()).apply {
            key = InputPreferences.INVERT_SCROLL
            title = getString(R.string.ng_settings_natural)
            summary = getString(if (input.naturalScrolling()) R.string.ng_settings_natural_on else R.string.ng_settings_natural_off)
            isPersistent = false
            isIconSpaceReserved = false
            isChecked = input.naturalScrolling()
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, value ->
                val natural = value as? Boolean ?: return@OnPreferenceChangeListener false
                saved.edit {
                    putBoolean(InputPreferences.INVERT_SCROLL,
                        InputPreferences.invertedForNaturalScrolling(natural))
                }
                preference.summary = getString(if (natural) R.string.ng_settings_natural_on else R.string.ng_settings_natural_off)
                true
            }
        }))
        screen.addPreference(configuredList("delay_list", getString(R.string.pref_title_delay),
            R.array.pref_delay_list_titles, R.array.pref_delay_list_values, storedString("delay_list", "350"),
            "350", "Saved value: %s"))
        screen.addPreference(configuredList("mdelay_list", getString(R.string.pref_title_mdelay),
            R.array.pref_mdelay_list_titles, R.array.pref_mdelay_list_values, storedString("mdelay_list", "0"),
            "0", "Saved value: %s"))
    }

    private fun addKeyboardPreferences(screen: PreferenceScreen) {
        val input = InputPreferences.read(saved.all)
        screen.addPreference(row(SwitchPreferenceCompat(requireContext()).apply {
            key = InputPreferences.KEYBOARD_AUTO_CLEAR
            title = getString(R.string.pref_title_keyboard_autoclear)
            summary = getString(R.string.pref_description_keyboard_autoclear)
            isPersistent = false
            isIconSpaceReserved = false
            isChecked = input.keyboardAutoClear
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val enabled = value as? Boolean ?: return@OnPreferenceChangeListener false
                saved.edit { putBoolean(InputPreferences.KEYBOARD_AUTO_CLEAR, enabled) }
                true
            }
        }))
        screen.addPreference(row(SwitchPreferenceCompat(requireContext()).apply {
            key = InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT
            title = getString(R.string.ng_settings_send_enter)
            summary = getString(R.string.ng_settings_send_enter_help)
            isPersistent = false
            isIconSpaceReserved = false
            isChecked = input.keyboardSendEnterAfterText
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val enabled = value as? Boolean ?: return@OnPreferenceChangeListener false
                saved.edit { putBoolean(InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT, enabled) }
                true
            }
        }))
        screen.addPreference(infoPreference(
            getString(R.string.ng_settings_keyboard_send_note_title),
            getString(R.string.ng_settings_explicit_send),
            "ng_settings_keyboard_send_note",
        ))
    }

    private fun addAuthenticationPreferences(screen: PreferenceScreen) {
        val useKey = row(CheckBoxPreference(requireContext()).apply {
            key = "pref_usekeyauth"
            title = getString(R.string.ng_settings_use_ssh_key)
            summary = getString(R.string.ng_settings_use_ssh_key_summary)
            isPersistent = false
            isIconSpaceReserved = false
            isChecked = storedBoolean(key, false)
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val enabled = value as? Boolean ?: return@OnPreferenceChangeListener false
                saved.edit { putBoolean(key, enabled) }
                true
            }
        })
        screen.addPreference(useKey)

        val privateKey = row(Preference(requireContext()).apply {
            key = AndroidPrivateKeys.LEGACY_PATH
            title = getString(R.string.ng_settings_private_key)
            isPersistent = false
        })
        screen.addPreference(privateKey)
        privateKey.dependency = "pref_usekeyauth"
        credentialPrompts = CredentialUi(requireActivity())
        privateKeyController = PrivateKeySettingsController(requireActivity(), privateKey)
        (requireActivity() as? SharedSettingsActivity)?.apply {
            credentialUi = credentialPrompts
            keyImport = privateKeyController
        }
        val appContext = requireContext().applicationContext
        val credentialRepository = AndroidCredentials.get(appContext)
        val prompts = requireNotNull(credentialPrompts)
        val weakPrompts = WeakReference(prompts)
        val weakFragment = WeakReference(this)
        val mainHandler = Handler(Looper.getMainLooper())
        credentialRepository.execute {
            if (!credentialRepository.prepare()) {
                mainHandler.post {
                    val fragment = weakFragment.get() ?: return@post
                    val currentPrompts = weakPrompts.get() ?: return@post
                    val host = fragment.activity ?: return@post
                    if (fragment.isAdded && !host.isFinishing && !host.isDestroyed &&
                        fragment.credentialPrompts === currentPrompts) currentPrompts.message()
                }
            }
        }

        val passphrase = row(Preference(requireContext()).apply {
            key = "pref_key_passphrase"
            title = getString(R.string.ng_settings_key_passphrase)
            summary = getString(R.string.ng_settings_passphrase_summary)
            isPersistent = false
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                credentialPrompts?.editPassphrase()
                true
            }
        })
        screen.addPreference(passphrase)
        passphrase.dependency = "pref_usekeyauth"
        screen.addPreference(row(CheckBoxPreference(requireContext()).apply {
            key = "autologin_checkbox"
            title = getString(R.string.pref_title_autologin)
            summary = getString(R.string.pref_description_autologin)
            isPersistent = false
            isChecked = storedBoolean(key, false)
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val enabled = value as? Boolean ?: return@OnPreferenceChangeListener false
                saved.edit { putBoolean(key, enabled) }
                true
            }
        }))
        screen.addPreference(editTextPreference("setting_shell", getString(R.string.pref_title_shell),
            storedString("setting_shell", "")))
        screen.addPreference(configuredList("setting_dotool_initial", getString(R.string.pref_title_dotool_initial),
            R.array.pref_dotool_list_titles, R.array.pref_dotool_list_titles,
            storedString("setting_dotool_initial", "xdotool"), "xdotool", "Saved value: %s"))
        screen.addPreference(editTextPreference("setting_xdotool_initial", getString(R.string.pref_title_xdotool_initial),
            storedString("setting_xdotool_initial", getString(R.string.pref_default_xdotool_initial))))
    }

    private fun addAppearancePreferences(screen: PreferenceScreen) {
        val modes = AppearancePreferences.Mode.values()
        val modeValues = modes.map { it.preferenceValue() }.toTypedArray()
        screen.addPreference(ListPreference(requireContext()).apply {
            key = AppearancePreferences.KEY
            title = getString(R.string.ng_appearance_mode)
            dialogTitle = getString(R.string.ng_appearance_title)
            entries = arrayOf(getString(R.string.ng_appearance_system), getString(R.string.ng_appearance_light),
                getString(R.string.ng_appearance_dark))
            entryValues = modeValues
            isPersistent = false
            isIconSpaceReserved = false
            value = AppearancePreferences.read(saved).preferenceValue()
            summaryProvider = Preference.SummaryProvider<ListPreference> { preference -> preference.entry }
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val mode = modes.firstOrNull { it.preferenceValue() == value } ?: return@OnPreferenceChangeListener false
                AppearancePreferences.save(saved, mode)
                AppearancePreferences.apply(mode)
                true
            }
        }.also(::row))
    }

    private fun addAboutPreferences(screen: PreferenceScreen) {
        screen.addPreference(infoPreference(getString(R.string.app_name), getString(R.string.ng_about_description), "ng_about_description"))
        screen.addPreference(infoPreference(getString(R.string.ng_about_version_label), BuildConfig.VERSION_NAME, "ng_about_version"))
        screen.addPreference(infoPreference(getString(R.string.ng_about_package_label), requireContext().packageName, "ng_about_package"))
        screen.addPreference(row(Preference(requireContext()).apply {
            key = "ng_about_privacy_policy"
            title = getString(R.string.ng_about_privacy_policy_title)
            summary = getString(R.string.ng_about_privacy_policy_summary)
            isPersistent = false
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                startActivity(Intent(requireContext(), PrivacyPolicyActivity::class.java))
                true
            }
        }))
    }

    private fun configuredList(
        key: String,
        title: String,
        @androidx.annotation.ArrayRes entriesResource: Int,
        @androidx.annotation.ArrayRes valuesResource: Int,
        current: String,
        defaultValue: String,
        customSummary: String,
    ): ListPreference = ListPreference(requireContext()).apply {
        this.key = key
        this.title = title
        dialogTitle = title
        entries = requireContext().resources.getStringArray(entriesResource)
        entryValues = requireContext().resources.getStringArray(valuesResource)
        isPersistent = false
        isIconSpaceReserved = false
        value = current.ifEmpty { defaultValue }
        summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
            preference.entry ?: getString(R.string.ng_settings_saved_value, customSummary.format(preference.value ?: defaultValue))
        }
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            val selected = value as? String ?: return@OnPreferenceChangeListener false
            if (entryValues?.any { it.toString() == selected } != true) return@OnPreferenceChangeListener false
            saved.edit { putString(key, selected) }
            true
        }
    }.also(::row)

    private fun editTextPreference(key: String, title: String, initial: String): EditTextPreference =
        EditTextPreference(requireContext()).apply {
            this.key = key
            this.title = title
            isPersistent = false
            isIconSpaceReserved = false
            setText(initial)
            setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                edit.setSelectAllOnFocus(true)
            }
            summary = getString(R.string.ng_settings_applies_next_connection)
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val text = value as? String ?: return@OnPreferenceChangeListener false
                saved.edit { putString(key, text) }
                true
            }
        }.also(::row)

    private fun infoPreference(title: String, summary: String, key: String? = null) = row(Preference(requireContext()).apply {
        this.key = key
        this.title = title
        this.summary = summary
        isPersistent = false
        isSelectable = false
        isEnabled = true
    })

    private fun <T : Preference> row(preference: T): T = preference.apply {
        layoutResource = R.layout.preference_settings_row
        isIconSpaceReserved = false
    }

    private fun storedString(key: String, fallback: String): String = saved.all[key] as? String ?: fallback
    private fun storedBoolean(key: String, fallback: Boolean): Boolean = saved.all[key] as? Boolean ?: fallback

    companion object {
        private const val ARG_SECTION = "settings-section"
        fun newInstance(section: String) = UnifiedSettingsFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section) }
        }
    }

    private class SettingsDivider(density: Float, color: Int) : RecyclerView.ItemDecoration() {
        private val inset = (16 * density).toInt()
        private val height = (1 * density).toInt().coerceAtLeast(1)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val bounds = Rect()

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.bottom = height
        }

        override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                parent.getDecoratedBoundsWithMargins(child, bounds)
                val y = bounds.bottom - height
                canvas.drawRect(inset.toFloat(), y.toFloat(), (parent.width - inset).toFloat(),
                    (y + height).toFloat(), paint)
            }
        }
    }
}
