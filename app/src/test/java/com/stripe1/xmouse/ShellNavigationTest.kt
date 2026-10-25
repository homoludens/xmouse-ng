package com.stripe1.xmouse

import com.stripe1.xmouse.ShellNavigation.*
import org.junit.Assert.*
import org.junit.Test

class ShellNavigationTest {
    private class Fixture {
        val events = mutableListOf<String>()
        var oldRole: InputRole? = null
        val state: ShellNavigation = ShellNavigation(object : Listener {
            override fun retireInput() { oldRole = state.inputRole(); events += "retire:$oldRole" }
            override fun render() { events += "render:${state.inputRole()}" }
            override fun refreshInput() { events += "acquire:${state.inputRole()}" }
        })
    }

    @Test fun destinationsRetireBeforeHidingAndAcquireOnlyTheirOwnFreshRole() {
        val f = Fixture()
        listOf(Destination.KEYS, Destination.ACTIONS, Destination.TOUCHPAD).forEach(f.state::select)
        assertEquals(listOf("retire:POINTER", "render:KEYS", "acquire:KEYS",
            "retire:KEYS", "render:ACTIONS", "acquire:ACTIONS",
            "retire:ACTIONS", "render:POINTER", "acquire:POINTER"), f.events)
    }

    @Test fun changingKeyCategoryRetiresOldKeysBeforeRenderingAndRefreshesTheKeysRole() {
        val f = Fixture()
        f.state.select(Destination.KEYS)
        f.events.clear()

        f.state.selectKeyCategory(KeyCatalog.Category.MEDIA)

        assertEquals(KeyCatalog.Category.MEDIA, f.state.keyCategory())
        assertEquals(InputRole.KEYS, f.state.inputRole())
        assertEquals(listOf("retire:KEYS", "render:KEYS", "acquire:KEYS"), f.events)
        f.events.clear()
        f.state.selectKeyCategory(KeyCatalog.Category.MEDIA)
        assertTrue("selecting the current category keeps the active gesture", f.events.isEmpty())
    }

    @Test fun keyCategoryCannotChangeFromAHiddenOrOverlaidKeysSurface() {
        val f = Fixture()
        f.state.selectKeyCategory(KeyCatalog.Category.FUNCTION)
        assertEquals(KeyCatalog.Category.COMMON, f.state.keyCategory())
        f.state.select(Destination.KEYS)
        f.state.open(Overlay.COMPUTERS)
        f.events.clear()

        f.state.selectKeyCategory(KeyCatalog.Category.MEDIA)

        assertEquals(KeyCatalog.Category.COMMON, f.state.keyCategory())
        assertTrue(f.events.isEmpty())
    }

    @Test fun computerSettingsAndEditorLayersSuspendInputAndDoNotChangeDestination() {
        val f = Fixture()
        f.state.select(Destination.ACTIONS)
        listOf(Overlay.COMPUTERS, Overlay.EXTERNAL, Overlay.ACTION_EDIT, Overlay.MENU).forEach { overlay ->
            f.state.open(overlay)
            assertEquals(InputRole.NONE, f.state.inputRole())
            assertFalse(f.state.eligible(true, true, true))
            f.state.select(Destination.KEYS)
            assertEquals(Destination.ACTIONS, f.state.destination())
            assertTrue(f.state.back())
            assertEquals(InputRole.ACTIONS, f.state.inputRole())
        }
    }

    @Test fun typingGetsANewNonPointerRoleAndNestedBackRestoresOnlyThePriorLayer() {
        val f = Fixture()
        f.state.open(Overlay.TYPING)
        assertEquals(InputRole.TYPING, f.state.inputRole())
        f.state.open(Overlay.COMPUTERS)
        assertEquals(InputRole.NONE, f.state.inputRole())
        assertTrue(f.state.back())
        assertEquals(InputRole.TYPING, f.state.inputRole())
        assertTrue(f.state.back())
        assertEquals(InputRole.POINTER, f.state.inputRole())
        assertFalse(f.state.back())
        assertEquals(listOf("retire:POINTER", "render:TYPING", "acquire:TYPING",
            "retire:TYPING", "render:NONE", "acquire:NONE", "retire:NONE", "render:TYPING",
            "acquire:TYPING", "retire:TYPING", "render:POINTER", "acquire:POINTER"), f.events)
    }

    @Test fun eligibilityRequiresResumedFocusedReadyForEveryRole() {
        val f = Fixture()
        for (destination in Destination.values()) {
            f.state.select(destination)
            assertFalse(f.state.eligible(false, true, true))
            assertFalse(f.state.eligible(true, false, true))
            assertFalse(f.state.eligible(true, true, false))
            assertTrue(f.state.eligible(true, true, true))
        }
        f.state.open(Overlay.TYPING)
        assertFalse(f.state.eligible(false, true, true))
        assertFalse(f.state.eligible(true, false, true))
        assertFalse(f.state.eligible(true, true, false))
        assertTrue(f.state.eligible(true, true, true))
    }

    @Test fun backClosesLocalLayerThenReturnsTouchpadThenNormalAndroidBack() {
        val f = Fixture()
        f.state.select(Destination.KEYS)
        f.state.open(Overlay.COMPUTERS)
        assertTrue(f.state.back())
        assertEquals(Destination.KEYS, f.state.destination())
        assertTrue(f.state.back())
        assertEquals(Destination.TOUCHPAD, f.state.destination())
        f.state.setJoystick(true)
        assertTrue(f.state.back())
        assertFalse(f.state.joystick())
        assertFalse(f.state.back())
    }

    @Test fun recreationRestoresNavigationOnlyAndDoesNotReviveExternalOrEditCapabilities() {
        val f = Fixture()
        f.state.restore("ACTIONS", true, listOf("COMPUTERS", "TYPING", "EXTERNAL", "ACTION_EDIT", "MENU", "unknown"))
        assertEquals(Destination.ACTIONS, f.state.destination())
        assertTrue(f.state.joystick())
        assertEquals(listOf(Overlay.COMPUTERS, Overlay.TYPING), f.state.overlays())
        assertEquals(InputRole.TYPING, f.state.inputRole())
        assertEquals(listOf("retire:POINTER", "render:TYPING", "acquire:TYPING"), f.events)
        f.state.restore("invalid", false, emptyList())
        assertEquals(Destination.TOUCHPAD, f.state.destination())
        assertEquals(InputRole.POINTER, f.state.inputRole())
    }

    @Test fun unchangedSelectionDoesNotRetireAnActiveGesture() {
        val f = Fixture()
        f.state.select(Destination.TOUCHPAD)
        f.state.setJoystick(false)
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
}
