package com.stripe1.xmouse

import com.stripe1.xmouse.layout.LayoutButton
import com.stripe1.xmouse.layout.LayoutProfile
import org.junit.Assert.*
import org.junit.Test

class ShellContentRenderCacheTest {
    @Test fun hiddenUpdatesRemainPendingAndEqualVisibleSnapshotsRetainViews() {
        val cache = ShellContentRenderCache()
        val profile = LayoutProfile("profile", "Saved", buttons = listOf(LayoutButton("button", "One", "key A", 5)))
        val original = listOf(profile, false, false)
        assertFalse(cache.shouldRender(false, original))
        assertTrue(cache.shouldRender(true, original))
        assertFalse(cache.shouldRender(true, listOf(profile.copy(), false, false)))
        val changed = listOf(profile.copy(buttons = listOf(profile.buttons[0].copy(command = "key B"))), false, false)
        assertFalse(cache.shouldRender(false, changed))
        assertTrue(cache.shouldRender(true, changed))
        assertFalse(cache.shouldRender(true, changed))
        for (state in listOf(listOf(changed[0], true, false), listOf(changed[0], false, true), original)) {
            assertTrue("loading/edit/error key changes invalidate content", cache.shouldRender(true, state))
        }
    }

    @Test fun navigationCacheHitsNeverSkipInputRetirementOrFreshRoleAcquisition() {
        val cache = ShellContentRenderCache()
        var retired = 0
        var acquired = 0
        var rendered = 0
        lateinit var navigation: ShellNavigation
        navigation = ShellNavigation(object : ShellNavigation.Listener {
            override fun retireInput() { retired++ }
            override fun retireLocalText() { }
            override fun render() {
                if (cache.shouldRender(navigation.destination() == ShellNavigation.Destination.ACTIONS, "same saved data")) rendered++
            }
            override fun refreshInput() { acquired++ }
        })
        navigation.select(ShellNavigation.Destination.ACTIONS)
        navigation.open(ShellNavigation.Overlay.MENU)
        navigation.closeTop()
        navigation.select(ShellNavigation.Destination.TOUCHPAD)
        navigation.select(ShellNavigation.Destination.ACTIONS)
        assertEquals(1, rendered)
        assertEquals(5, retired)
        assertEquals(5, acquired)
    }
}
