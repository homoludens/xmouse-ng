package com.stripe1.xmouse

import com.stripe1.xmouse.ShellNavigation.*
import org.junit.Assert.*
import org.junit.Test

class ShellNavigationTest {
    private class Fixture {
        val events = mutableListOf<String>()
        var localTextRetirements = 0
        var localTextSuspensions = 0
        var oldRole: InputRole? = null
        val state: ShellNavigation = ShellNavigation(object : Listener {
            override fun retireInput() { oldRole = state.inputRole(); events += "retire:$oldRole" }
            override fun retireLocalText() { localTextRetirements++ }
            override fun suspendLocalText() { localTextSuspensions++ }
            override fun render() { events += "render:${state.inputRole()}" }
            override fun refreshInput() { events += "acquire:${state.inputRole()}" }
        })
    }

    @Test fun threeDestinationsRetireBeforeHidingAndOnlyControlTabsAcquireRoles() {
        val f = Fixture()
        listOf(Destination.ACTIONS, Destination.SCRIPTS, Destination.TOUCHPAD, Destination.SCRIPTS)
            .forEach(f.state::select)
        assertEquals(listOf("retire:POINTER", "render:ACTIONS", "acquire:ACTIONS",
            "retire:ACTIONS", "render:NONE", "acquire:NONE",
            "retire:NONE", "render:POINTER", "acquire:POINTER",
            "retire:POINTER", "render:NONE", "acquire:NONE"), f.events)
        assertEquals(InputRole.NONE, f.state.inputRole())
        assertFalse("Scripts never borrows the Actions control capability", f.state.eligible(true, true, true))
    }

    @Test fun inlineTypingKeepsTheTouchpadRoleAndNavigationRetiresIt() {
        val f = Fixture()
        assertEquals(InputRole.POINTER, f.state.inputRole())
        assertTrue("inline text input does not create a navigation layer", f.state.overlays().isEmpty())
        f.state.select(Destination.ACTIONS)
        assertEquals(InputRole.ACTIONS, f.state.inputRole())
        assertEquals(listOf("retire:POINTER", "render:ACTIONS", "acquire:ACTIONS"), f.events)
    }

    @Test fun computerSettingsAndEditorLayersSuspendInputAndDoNotChangeDestination() {
        val f = Fixture()
        f.state.select(Destination.ACTIONS)
        listOf(Overlay.COMPUTERS, Overlay.EXTERNAL, Overlay.ACTION_EDIT, Overlay.MENU).forEach { overlay ->
            f.state.open(overlay)
            assertEquals(InputRole.NONE, f.state.inputRole())
            assertFalse(f.state.eligible(true, true, true))
            f.state.select(Destination.TOUCHPAD)
            assertEquals(Destination.ACTIONS, f.state.destination())
            assertTrue(f.state.back())
            assertEquals(InputRole.ACTIONS, f.state.inputRole())
        }
    }

    @Test fun computerPanelSuspendsTouchpadInputAndBackRestoresFreshPointerCapability() {
        val f = Fixture()
        f.state.open(Overlay.COMPUTERS)
        assertEquals(InputRole.NONE, f.state.inputRole())
        assertFalse(f.state.eligible(true, true, true))
        assertTrue(f.state.back())
        assertEquals(InputRole.POINTER, f.state.inputRole())
        assertFalse(f.state.back())
        assertEquals(listOf("retire:POINTER", "render:NONE", "acquire:NONE",
            "retire:NONE", "render:POINTER", "acquire:POINTER"), f.events)
    }

    @Test fun eligibilityRequiresResumedFocusedReadyForEveryRole() {
        val f = Fixture()
        for (destination in Destination.values()) {
            f.state.select(destination)
            assertFalse(f.state.eligible(false, true, true))
            assertFalse(f.state.eligible(true, false, true))
            assertFalse(f.state.eligible(true, true, false))
            assertEquals(destination != Destination.SCRIPTS, f.state.eligible(true, true, true))
        }
        f.state.open(Overlay.COMPUTERS)
        assertFalse(f.state.eligible(false, true, true))
        assertFalse(f.state.eligible(true, false, true))
        assertFalse(f.state.eligible(true, true, false))
        assertFalse(f.state.eligible(true, true, true))
    }

    @Test fun backClosesLocalLayerThenReturnsTouchpadFromEveryBottomTab() {
        val f = Fixture()
        f.state.select(Destination.ACTIONS)
        f.state.open(Overlay.COMPUTERS)
        assertTrue(f.state.back())
        assertEquals(Destination.ACTIONS, f.state.destination())
        assertTrue(f.state.back())
        assertEquals(Destination.TOUCHPAD, f.state.destination())
        assertFalse(f.state.back())
        f.state.select(Destination.SCRIPTS)
        assertEquals(InputRole.NONE, f.state.inputRole())
        assertTrue(f.state.back())
        assertEquals(Destination.TOUCHPAD, f.state.destination())
    }

    @Test fun recreationRestoresNavigationOnlyAndDoesNotReviveExternalOrEditCapabilities() {
        val f = Fixture()
        f.state.restore("ACTIONS", listOf("COMPUTERS", "TYPING", "EXTERNAL", "ACTION_EDIT", "MENU", "unknown"))
        assertEquals(Destination.ACTIONS, f.state.destination())
        assertEquals(listOf(Overlay.COMPUTERS), f.state.overlays())
        assertEquals(InputRole.NONE, f.state.inputRole())
        assertEquals(listOf("retire:POINTER", "render:NONE", "acquire:NONE"), f.events)
        f.state.restore("invalid", emptyList())
        assertEquals(Destination.TOUCHPAD, f.state.destination())
        assertEquals(InputRole.POINTER, f.state.inputRole())
    }

    @Test fun legacyKeysAndTypingOverlayRestoreSafelyAndScriptsRestoresWithoutInput() {
        val f = Fixture()
        f.state.restore("KEYS", listOf("TYPING", "EXTERNAL", "ACTION_EDIT"))
        assertEquals(Destination.TOUCHPAD, f.state.destination())
        assertTrue(f.state.overlays().isEmpty())
        assertEquals(InputRole.POINTER, f.state.inputRole())

        f.state.restore("ACTIONS", listOf("TYPING"))
        assertEquals(Destination.ACTIONS, f.state.destination())
        assertTrue(f.state.overlays().isEmpty())
        assertEquals(InputRole.ACTIONS, f.state.inputRole())

        f.state.restore("SCRIPTS", emptyList())
        assertEquals(Destination.SCRIPTS, f.state.destination())
        assertEquals(InputRole.NONE, f.state.inputRole())
        assertFalse(f.state.eligible(true, true, true))
    }

    @Test fun unchangedSelectionDoesNotRetireAnActiveGesture() {
        val f = Fixture()
        f.state.select(Destination.TOUCHPAD)
        f.state.closeTop()
        assertTrue(f.events.isEmpty())
    }

    @Test fun externalReturnAndDismissOrderCannotResurrectTheUnderlyingControls() {
        val f = Fixture()
        f.state.open(Overlay.COMPUTERS)
        f.state.open(Overlay.ACTION_EDIT)
        f.state.open(Overlay.EXTERNAL)
        f.state.close(Overlay.ACTION_EDIT)
        assertEquals(InputRole.NONE, f.state.inputRole())
        f.state.close(Overlay.EXTERNAL)
        assertEquals(Overlay.COMPUTERS, f.state.topOverlay())
        assertEquals(InputRole.NONE, f.state.inputRole())
        f.state.closeTop()
        assertEquals(InputRole.POINTER, f.state.inputRole())
    }

    @Test fun savedActionsRefreshRetiresBeforeReplacingControlsEvenUnderEditOrExternalLayers() {
        val f = Fixture()
        f.state.select(Destination.ACTIONS)
        for (overlay in listOf(null, Overlay.ACTION_EDIT, Overlay.EXTERNAL)) {
            if (overlay != null) f.state.open(overlay)
            f.events.clear()
            f.state.replaceContent { f.events += "replace:saved-profile-only" }
            val role = if (overlay == null) InputRole.ACTIONS else InputRole.NONE
            assertEquals(listOf("retire:$role", "replace:saved-profile-only", "render:$role", "acquire:$role"), f.events)
            assertEquals(overlay, f.state.topOverlay())
            if (overlay != null) f.state.close(overlay)
        }
    }

    @Test fun sharedComposerDraftSurvivesTabsComputersAndActionPromptsButExternalScreensRetireIt() {
        val f = Fixture()
        f.state.replaceContent { f.events += "replace:saved-profile-only" }
        assertEquals("content refresh does not clear an unsent local draft", 0, f.localTextRetirements)
        assertEquals(listOf("retire:POINTER", "replace:saved-profile-only", "render:POINTER", "acquire:POINTER"), f.events)

        f.state.select(Destination.ACTIONS)
        assertEquals("changing tabs retains the draft", 0, f.localTextRetirements)
        assertEquals(1, f.localTextSuspensions)
        f.state.select(Destination.SCRIPTS)
        assertEquals("Scripts hides but does not clear the shared in-memory draft", 0,
            f.localTextRetirements)
        assertEquals(InputRole.NONE, f.state.inputRole())
        assertEquals(2, f.localTextSuspensions)
        f.state.select(Destination.TOUCHPAD)
        assertEquals(InputRole.POINTER, f.state.inputRole())
        assertEquals(3, f.localTextSuspensions)
        f.state.open(Overlay.MENU)
        assertEquals("opening the hamburger drawer retains the draft", 0, f.localTextRetirements)
        assertEquals(4, f.localTextSuspensions)
        f.state.closeTop()
        assertEquals("closing the hamburger drawer retains the draft", 0, f.localTextRetirements)
        assertEquals(5, f.localTextSuspensions)
        f.state.open(Overlay.COMPUTERS)
        assertEquals("opening Computers only suspends the composer", 0, f.localTextRetirements)
        assertEquals(6, f.localTextSuspensions)
        f.state.closeTop()
        assertEquals("closing Computers retains the draft", 0, f.localTextRetirements)
        f.state.open(Overlay.ACTION_EDIT)
        f.state.closeTop()
        assertEquals("trust/password/action-picker dialogs suspend instead of clearing", 0, f.localTextRetirements)
        assertEquals(9, f.localTextSuspensions)
        f.state.open(Overlay.EXTERNAL)
        assertEquals("external editor screens remain an explicit privacy boundary", 1, f.localTextRetirements)
    }
}
