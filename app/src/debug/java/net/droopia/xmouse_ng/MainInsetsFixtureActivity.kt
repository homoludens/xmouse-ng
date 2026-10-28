package net.droopia.xmouse_ng

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import net.droopia.xmouse_ng.layout.ActionPresetCatalog
import net.droopia.xmouse_ng.layout.LayoutProfile

/** Test-only host for measuring the real main-screen XML without starting session or data code. */
class MainInsetsFixtureActivity : AppCompatActivity() {
    companion object {
        private val nextConfiguration = java.util.concurrent.atomic.AtomicReference<Configuration?>()

        /** Install an isolated base context before the framework first reads Activity resources. */
        fun configuredIntent(context: Context, fontScale: Float, dark: Boolean, widthDp: Int? = null): Intent {
            nextConfiguration.set(Configuration(context.resources.configuration).apply {
                this.fontScale = fontScale
                if (widthDp != null) screenWidthDp = widthDp
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            return Intent(context, MainInsetsFixtureActivity::class.java).putExtra("fixture.dark", dark)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val configuration = nextConfiguration.getAndSet(null)
        super.attachBaseContext(newBase)
        if (configuration != null) applyOverrideConfiguration(Configuration().apply {
            fontScale = configuration.fontScale
            screenWidthDp = configuration.screenWidthDp
            uiMode = configuration.uiMode
        })
    }

    lateinit var navigation: ShellNavigation
        private set
    val shellEvents = mutableListOf<String>()
    var actionProfiles: List<LayoutProfile> = emptyList()
    var selectedActionProfile: LayoutProfile? = null
    var selectedActionPreset: ActionPresetCatalog.Preset? = null
    var actionsEditing = false
    var actionPicker: android.app.AlertDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.hasExtra("fixture.dark")) delegate.localNightMode =
            if (intent.getBooleanExtra("fixture.dark", false)) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_main)
        val root = findViewById<android.view.View>(R.id.native_shell)
        navigation = ShellNavigation(object : ShellNavigation.Listener {
            override fun retireInput() { shellEvents += "retire" }
            override fun retireLocalText() {
                val input = findViewById<android.widget.EditText>(R.id.keyboard_input)
                val inputMethod = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                if (inputMethod != null && input.windowToken != null) {
                    inputMethod.hideSoftInputFromWindow(input.windowToken, 0)
                }
                input.clearFocus()
                input.setText("")
            }
            override fun suspendLocalText() {
                val input = findViewById<android.widget.EditText>(R.id.keyboard_input)
                val inputMethod = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                if (inputMethod != null && input.windowToken != null) {
                    inputMethod.hideSoftInputFromWindow(input.windowToken, 0)
                }
                input.clearFocus()
            }
            override fun render() {
                shellEvents += "render"
                NativeShellViews.render(root, navigation, actionPicker != null)
                renderActionsFixture()
            }
            override fun refreshInput() { shellEvents += "refresh" }
        })
        NativeShellViews.bind(root, navigation)
        findViewById<android.view.View>(R.id.drawer_computers).setOnClickListener {
            val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.main_safe_area)
            navigation.close(ShellNavigation.Overlay.MENU)
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            navigation.open(ShellNavigation.Overlay.COMPUTERS)
        }
        findViewById<android.view.View>(R.id.actions_edit).setOnClickListener {
            actionsEditing = !actionsEditing
            if (actionsEditing) navigation.open(ShellNavigation.Overlay.ACTION_EDIT)
            else navigation.close(ShellNavigation.Overlay.ACTION_EDIT)
        }
        findViewById<android.view.View>(R.id.actions_layout_picker).setOnClickListener {
            val dialog = ActionsViewRenderer.profilePicker(this, ActionPresetCatalog.presets(), actionProfiles,
                if (selectedActionPreset == null) selectedActionProfile else null, selectedActionPreset?.id(), { choice ->
                    navigation.replaceContent {
                        if (choice.isPreset) {
                            selectedActionPreset = ActionPresetCatalog.find(choice.presetId())
                            val preset = requireNotNull(selectedActionPreset)
                            selectedActionProfile = preset.toLayoutProfile("#EEF1F4", "#FBE9E7")
                        } else {
                            selectedActionPreset = null
                            selectedActionProfile = actionProfiles.single { it.id == choice.profileId() }
                        }
                        renderActionsFixture()
                    }
                }, {})
            actionPicker = dialog
            navigation.open(ShellNavigation.Overlay.ACTION_EDIT)
            NativeShellViews.render(root, navigation, true)
            dialog.setOnDismissListener {
                actionPicker = null
                if (!actionsEditing) navigation.close(ShellNavigation.Overlay.ACTION_EDIT)
                else {
                    NativeShellViews.render(root, navigation)
                    renderActionsFixture()
                }
            }
            dialog.show()
        }
        renderActionsFixture()
        if (savedInstanceState != null) navigation.restore(
            savedInstanceState.getString("destination"), savedInstanceState.getStringArrayList("overlays") ?: emptyList(),
        )
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.main_safe_area)
                if (drawer.isDrawerVisible(androidx.core.view.GravityCompat.START)) {
                    drawer.closeDrawer(androidx.core.view.GravityCompat.START)
                    return
                }
                if (actionsEditing) { actionsEditing = false; navigation.close(ShellNavigation.Overlay.ACTION_EDIT); return }
                if (!navigation.back()) finish()
            }
        })
        onBackPressedDispatcher.addCallback(this, DrawerBackCallback(findViewById(R.id.main_safe_area)))
        val mouseSurface = findViewById<FrameLayout>(R.id.mouse_surface_host)
        mouseSurface.clipToOutline = true
        mouseSurface.addView(
            MyMouseView(this, null),
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        SystemWindowInsets.applyToAndConsume(window, findViewById(R.id.main_safe_area))
    }

    fun renderActionsFixture() {
        val root = findViewById<android.view.View>(R.id.native_shell) ?: return
        val canEdit = selectedActionProfile != null && selectedActionPreset == null
        ActionsViewRenderer.renderProfileControls(root, selectedActionProfile, actionsEditing, canEdit, true,
            selectedActionPreset != null)
        if (selectedActionPreset != null) {
            ActionsViewRenderer.renderPreset(this, findViewById(R.id.actions_tiles), selectedActionPreset,
                selectedActionProfile, null)
        } else {
            ActionsViewRenderer.renderTiles(this, findViewById(R.id.actions_tiles), selectedActionProfile, actionsEditing, null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("destination", navigation.destination().name)
        outState.putStringArrayList("overlays", ArrayList(navigation.overlays().map { it.name }))
        super.onSaveInstanceState(outState)
    }
}
