package com.stripe1.xmouse

import android.os.Bundle
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.stripe1.xmouse.layout.LayoutProfile

/** Test-only host for measuring the real main-screen XML without starting session or data code. */
class MainInsetsFixtureActivity : AppCompatActivity() {
    lateinit var navigation: ShellNavigation
        private set
    val shellEvents = mutableListOf<String>()
    var actionProfiles: List<LayoutProfile> = emptyList()
    var selectedActionProfile: LayoutProfile? = null
    var actionsEditing = false
    var actionPicker: android.app.AlertDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.hasExtra("fixture.fontScale")) {
            applyOverrideConfiguration(Configuration().apply {
                fontScale = intent.getFloatExtra("fixture.fontScale", 1f)
                uiMode = if (intent.getBooleanExtra("fixture.dark", false))
                    Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<android.view.View>(R.id.native_shell)
        navigation = ShellNavigation(object : ShellNavigation.Listener {
            override fun retireInput() { shellEvents += "retire" }
            override fun render() {
                shellEvents += "render"
                NativeShellViews.render(root, navigation)
                renderActionsFixture()
            }
            override fun refreshInput() { shellEvents += "refresh" }
        })
        NativeShellViews.bind(root, navigation)
        findViewById<android.view.View>(R.id.actions_edit).setOnClickListener {
            actionsEditing = !actionsEditing
            if (actionsEditing) navigation.open(ShellNavigation.Overlay.ACTION_EDIT)
            else navigation.close(ShellNavigation.Overlay.ACTION_EDIT)
        }
        findViewById<android.view.View>(R.id.actions_layout_picker).setOnClickListener {
            val dialog = ActionsViewRenderer.profilePicker(this, actionProfiles, selectedActionProfile, { id ->
                navigation.replaceContent { selectedActionProfile = actionProfiles.single { it.id == id } }
            }, {})
            actionPicker = dialog
            navigation.open(ShellNavigation.Overlay.ACTION_EDIT)
            dialog.setOnDismissListener {
                actionPicker = null
                if (!actionsEditing) navigation.close(ShellNavigation.Overlay.ACTION_EDIT)
            }
            dialog.show()
        }
        renderActionsFixture()
        if (savedInstanceState != null) navigation.restore(
            savedInstanceState.getString("destination"), savedInstanceState.getBoolean("joystick"),
            savedInstanceState.getStringArrayList("overlays") ?: emptyList(),
        )
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (actionsEditing) { actionsEditing = false; navigation.close(ShellNavigation.Overlay.ACTION_EDIT); return }
                if (!navigation.back()) finish()
            }
        })
        val mouseSurface = findViewById<FrameLayout>(R.id.mouse_surface_host)
        mouseSurface.clipToOutline = true
        mouseSurface.addView(
            MyMouseView(this, null),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        JoystickSurfaceLayout.addTo(
            findViewById<LinearLayout>(R.id.js),
            MyJSView(this, null),
        )
        SystemWindowInsets.applyToAndConsume(window, findViewById(R.id.main_safe_area))
    }

    fun renderActionsFixture() {
        val root = findViewById<android.view.View>(R.id.native_shell) ?: return
        ActionsViewRenderer.renderProfileControls(root, selectedActionProfile, actionsEditing, selectedActionProfile != null)
        ActionsViewRenderer.renderTiles(this, findViewById(R.id.actions_tiles), selectedActionProfile, actionsEditing, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("destination", navigation.destination().name)
        outState.putBoolean("joystick", navigation.joystick())
        outState.putStringArrayList("overlays", ArrayList(navigation.overlays().map { it.name }))
        super.onSaveInstanceState(outState)
    }
}
