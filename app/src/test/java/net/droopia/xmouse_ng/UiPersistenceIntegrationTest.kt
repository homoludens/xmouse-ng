package net.droopia.xmouse_ng

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UiPersistenceIntegrationTest {
    @Test fun computerEditorUsesExternalLabelsStickyActionsAndKeepsTheBlankPasswordMetadataPath() {
        val layout = source("app/src/main/res/layout/activity_computer_editor.xml")
        val activity = source("app/src/main/java/net/droopia/xmouse_ng/ComputerEditorActivity.kt")
        for ((label, field) in listOf(
            "computer_name_label" to "new_host_alias",
            "computer_host_label" to "new_host_ip",
            "computer_user_label" to "new_host_username",
            "computer_password_label" to "new_host_password",
            "computer_port_label" to "new_host_port",
        )) {
            assertTrue("persistent label $label exists", layout.contains("@+id/$label"))
            assertTrue("editable field $field exists", layout.contains("@+id/$field"))
        }
        assertTrue(layout.indexOf("@+id/computer_editor_actions") > layout.indexOf("@+id/computer_editor_scroll"))
        assertTrue(layout.contains("app:endIconMode=\"password_toggle\""))
        assertTrue(layout.contains("android:saveEnabled=\"false\""))
        assertTrue(activity.contains("old != null && field(R.id.new_host_password).length() == 0"))
        assertTrue(activity.contains("repo.updateHostMetadata(old"))
        assertTrue(activity.contains("R.string.computer_editor_password_saved_hint"))
        assertTrue(activity.contains("setAdvancedVisible(restored?.getBoolean(\"advanced\") == true)"))
        assertTrue("delete keeps its explicit confirmation", activity.contains("AlertDialog.Builder(this).setTitle(\"Delete computer?\")"))
    }

    @Test fun quickComputersSheetAndDrawerRowsResolveToExistingRealOperations() {
        val layout = source("app/src/main/res/layout/ng_shell_overlay.xml")
        val drawer = source("app/src/main/res/layout/shell_navigation_drawer.xml")
        val main = source("app/src/main/java/net/droopia/xmouse_ng/MainActivity.java")
        assertTrue(layout.contains("@+id/computers_sheet"))
        assertTrue(layout.contains("@drawable/ng_computers_sheet_background"))
        for (id in listOf("computers_connect", "computers_add", "computers_find", "computers_manage"))
            assertTrue("sheet route $id exists", layout.contains("@+id/$id"))
        assertTrue(main.contains("selectHostAndConnect((int) host.id)"))
        assertTrue(main.contains("ComputerEditorActivity.intent(this, host.id)"))
        assertTrue(main.contains("openExternalScreen(ComputerEditorActivity.intent(this, 0))"))
        assertTrue(main.contains("putExtra(\"findComputers\", true)"))
        assertTrue(main.contains("openLegacyManager(0)"))
        assertTrue(drawer.contains("@string/ng_drawer_app"))
        assertTrue(drawer.contains("@+id/drawer_appearance"))
        assertTrue(drawer.contains("@+id/drawer_about"))
        assertTrue(main.contains("drawer_appearance).setOnClickListener(v -> runDrawerAction(this::showAppearance))"))
        assertTrue(main.contains("drawer_about).setOnClickListener(v -> runDrawerAction(this::showAbout))"))
    }

    @Test fun settingsRouteAndComposeThemesUsePersistedSemanticPreferences() {
        val settings = source("app/src/main/res/layout/activity_settings.xml")
        val activity = source("app/src/main/java/net/droopia/xmouse_ng/SharedSettingsActivity.kt")
        val fragment = source("app/src/main/java/net/droopia/xmouse_ng/UnifiedSettingsFragment.kt")
        val app = source("app/src/main/java/net/droopia/xmouse_ng/XMouseApplication.kt")
        val composeTheme = source("app/src/main/java/net/droopia/xmouse_ng/ui/XMouseNgTheme.kt")
        val editorActivity = source("app/src/main/java/net/droopia/xmouse_ng/layout/LayoutEditorActivity.kt")
        val managerActivity = source("app/src/main/java/net/droopia/xmouse_ng/layout/LayoutProfileManagerActivity.kt")
        val backup = source("app/src/main/java/net/droopia/xmouse_ng/secrets/CredentialMetadataBackup.kt")
        assertTrue(settings.contains("@+id/settings_preference_container"))
        assertTrue(settings.contains("@+id/settings_back"))
        assertTrue(fragment.contains("InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT"))
        assertTrue(fragment.contains("NativeSettingsActivity.SECTION_POINTER"))
        assertTrue(fragment.contains("NativeSettingsActivity.SECTION_KEYBOARD"))
        assertTrue(fragment.contains("AppearancePreferences.save(saved, mode)"))
        assertTrue(fragment.contains("AndroidPrivateKeys.LEGACY_PATH"))
        assertTrue(activity.contains("class SettingsActivity : SharedSettingsActivity()"))
        assertTrue(app.contains("AppearancePreferences.apply(AppearancePreferences.read(preferences))"))
        assertTrue(composeTheme.contains("colorResource(R.color.ng_background)"))
        assertTrue(editorActivity.contains("class LayoutEditorActivity : AppCompatActivity()"))
        assertTrue(managerActivity.contains("class LayoutProfileManagerActivity : AppCompatActivity()"))
        val stringAllowlist = backup.substring(backup.indexOf("private val stringPreferences"),
            backup.indexOf("private val legacyRemovedStringPreferences"))
        val booleanAllowlist = backup.substring(backup.indexOf("private val booleanPreferences"),
            backup.indexOf("private val allowedPreferences"))
        assertTrue(stringAllowlist.contains("\"appearance_mode\""))
        assertTrue(booleanAllowlist.contains("\"keyboard_send_enter_after_text\""))
        assertFalse("metadata allowlist does not include password material", stringAllowlist.contains("\"setting_pass\""))
    }

    @Test fun unifiedSettingsPreserveNonPersistentSecretsAndSectionRoutes() {
        val fragment = source("app/src/main/java/net/droopia/xmouse_ng/UnifiedSettingsFragment.kt")
        val main = source("app/src/main/java/net/droopia/xmouse_ng/MainActivity.java")
        val editor = source("app/src/main/java/net/droopia/xmouse_ng/ComputerEditorActivity.kt")
        assertTrue(fragment.contains("isPersistent = false"))
        assertTrue(fragment.contains("credentialPrompts?.editPassphrase()"))
        assertTrue(fragment.contains("PrivateKeySettingsController(requireActivity(), privateKey)"))
        assertTrue(fragment.contains("credentialPrompts?.stop()"))
        assertTrue(fragment.contains("privateKeyController?.destroy()"))
        assertTrue(main.contains("showAppearance() { openInputSettings(NativeSettingsActivity.SECTION_APPEARANCE); }"))
        assertTrue(main.contains("showAbout() { openInputSettings(NativeSettingsActivity.SECTION_ABOUT); }"))
        assertTrue(main.contains("openInputSettings(NativeSettingsActivity.SECTION_AUTHENTICATION)"))
        assertTrue(editor.contains("NativeSettingsActivity.SECTION_AUTHENTICATION"))
    }

    @Test fun actionPresetsAreVirtualTokenScopedAndDraftPrivacyBoundaryStaysDistinct() {
        val main = source("app/src/main/java/net/droopia/xmouse_ng/MainActivity.java")
        val navigation = source("app/src/main/java/net/droopia/xmouse_ng/ShellNavigation.java")
        val touchpad = source("app/src/main/res/layout/content_main.xml")
        val presetSelection = main.substring(main.indexOf("private void selectActionPreset("),
            main.indexOf("private void installSelectedActionPreset("))
        val tileDispatch = main.substring(main.indexOf("private void dispatchActionTile("),
            main.indexOf("private void openActionEditor("))
        val pause = main.substring(main.indexOf("protected void onPause()"), main.indexOf("public void onWindowFocusChanged"))
        val stop = main.substring(main.indexOf("protected void onStop()"), main.indexOf("protected void onActivityResult"))
        assertTrue(presetSelection.contains("ActionPresetCatalog.find(presetId)"))
        assertFalse("virtual selection does not call saved-profile repository operations",
            presetSelection.contains("selectProfile(") || presetSelection.contains("createProfile(") ||
                presetSelection.contains("saveProfileAs("))
        assertTrue(main.contains("selectedActionPreset == null ? selectedActionProfile : null"))
        assertTrue(main.contains("selectedActionPreset == null && !actionsLoading"))
        assertTrue(main.contains("R.string.ng_actions_read_only_hint"))
        assertTrue(tileDispatch.contains("isInputTokenActive(token)"))
        assertTrue(tileDispatch.contains("CustomCommandDispatcher.execCommand(button.getCommand(), inputTool, inputTransport)"))
        assertTrue("permission/dialog pauses suspend the draft", pause.contains("suspendInlineTypingInput()"))
        assertFalse("onPause no longer retires the composer draft", pause.contains("retireInlineTypingInput()"))
        assertTrue("background stop remains the in-memory privacy clear", stop.contains("retireInlineTypingInput()"))
        assertTrue("only pending credential-picker or system-permission continuations may retain through onStop",
            stop.contains("credentialUi.hasPicker()") &&
                stop.contains("pendingNotificationPermissionTicket != null") &&
                stop.contains("pendingLocalNetworkPermissionTicket != null") &&
                stop.contains("if (continuingConnectionPrompt) suspendInlineTypingInput()"))
        assertTrue(navigation.contains("overlay == Overlay.EXTERNAL ? LocalTextAction.CLEAR : LocalTextAction.SUSPEND"))
        assertFalse("Touchpad quick keys do not gain Super or Alt+Tab", touchpad.contains("Alt+Tab") || touchpad.contains("Super"))
    }

    private fun source(path: String) = File(root(), path).readText()

    private fun root(): File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "app/src/main/res/layout/activity_computer_editor.xml").isFile }
}
