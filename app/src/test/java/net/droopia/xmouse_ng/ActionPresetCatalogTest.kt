package net.droopia.xmouse_ng

import net.droopia.xmouse_ng.layout.ActionPresetCatalog
import net.droopia.xmouse_ng.layout.LAYOUT_COLUMN_COUNT
import org.junit.Assert.*
import org.junit.Test

class ActionPresetCatalogTest {
    @Test fun catalogHasStableUniqueVirtualIdsAndTheApprovedCategoryOrder() {
        val presets = ActionPresetCatalog.presets()
        assertEquals(listOf("builtin-common", "builtin-navigate", "builtin-media", "builtin-function-keys"),
            presets.map { it.id() })
        assertEquals(listOf("Common", "Navigate", "Media", "F1–F12"), presets.map { it.title() })
        assertEquals(presets.map { it.id() }.size, presets.map { it.id() }.toSet().size)
        val entries = presets.flatMap { preset -> preset.sections().flatMap { it.entries() } }
        assertEquals(entries.map { it.id() }.size, entries.map { it.id() }.toSet().size)
        entries.forEach { entry ->
            assertTrue("stable virtual button ID: ${entry.id()}", entry.id().matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            assertTrue(entry.command().isNotBlank())
            assertEquals(5, entry.width())
        }
    }

    @Test fun catalogUsesTheLegacyRealCommandsAndOnlyRelativeMediaAdjustments() {
        val expected = mapOf(
            "common-esc" to ("Esc" to "key Escape"),
            "common-super" to ("Super" to "key super"),
            "common-alt-tab" to ("Switch / Alt+Tab" to "key alt+Tab"),
            "common-minimize" to ("Minimize" to "xdotool windowminimize $(xdotool getactivewindow)"),
            "common-maximize" to ("Maximize" to "xdotool windowsize $(xdotool getactivewindow) 100% 100%"),
            "common-close" to ("Close" to "key alt+F4"),
            "common-copy" to ("Copy" to "key ctrl+c"),
            "common-cut" to ("Cut" to "key ctrl+x"),
            "common-paste" to ("Paste" to "key ctrl+v"),
            "common-undo" to ("Undo" to "key ctrl+z"),
            "common-redo" to ("Redo" to "key ctrl+y"),
            "common-select-all" to ("Select all" to "key ctrl+a"),
            "common-new" to ("New" to "key ctrl+n"),
            "common-open" to ("Open" to "key ctrl+o"),
            "common-save" to ("Save" to "key ctrl+s"),
            "common-find" to ("Find" to "key ctrl+f"),
            "common-print" to ("Print" to "key ctrl+p"),
            "navigate-home" to ("Home" to "key Home"),
            "navigate-up" to ("Up" to "key Up"),
            "navigate-end" to ("End" to "key End"),
            "navigate-left" to ("Left" to "key Left"),
            "navigate-down" to ("Down" to "key Down"),
            "navigate-right" to ("Right" to "key Right"),
            "navigate-page-up" to ("Page up" to "key Prior"),
            "navigate-page-down" to ("Page down" to "key Next"),
            "navigate-tab" to ("Tab" to "key Tab"),
            "navigate-delete" to ("Delete" to "key Delete"),
            "navigate-backspace" to ("Backspace" to "key BackSpace"),
            "navigate-enter" to ("Enter" to "key Return"),
            "navigate-zoom-out" to ("Zoom out" to "key ctrl+minus"),
            "navigate-zoom-reset" to ("Reset zoom" to "key ctrl+0"),
            "navigate-zoom-in" to ("Zoom in" to "key ctrl+plus"),
            "media-previous" to ("Previous" to "key XF86AudioPrev"),
            "media-play-pause" to ("Play/Pause" to "key XF86AudioPlay"),
            "media-next" to ("Next" to "key XF86AudioNext"),
            "media-volume-down" to ("Volume −" to "key XF86AudioLowerVolume"),
            "media-mute" to ("Mute" to "key XF86AudioMute"),
            "media-volume-up" to ("Volume +" to "key XF86AudioRaiseVolume"),
            "media-brightness-down" to ("Brightness −" to "key XF86MonBrightnessDown"),
            "media-brightness-up" to ("Brightness +" to "key XF86MonBrightnessUp"),
        ).toMutableMap().apply {
            for (index in 1..12) put("function-f$index", "F$index" to "key F$index")
        }
        val allEntries = ActionPresetCatalog.presets().flatMap { preset ->
            preset.sections().flatMap { section -> section.entries() }
        }
        val entries = allEntries.associateBy { it.id() }
        assertEquals(expected.keys, entries.keys)
        assertEquals(expected.keys.toList(), allEntries.map { it.id() }.take(expected.size))
        expected.forEach { (id, values) ->
            assertEquals("title for $id", values.first, entries.getValue(id).title())
            assertEquals("command for $id", values.second, entries.getValue(id).command())
        }
        assertEquals(listOf("Window", "Editing", "File"), ActionPresetCatalog.presets()[0].sections().map { it.title() })
        assertEquals(listOf("Directions", "Movement", "Zoom"), ActionPresetCatalog.presets()[1].sections().map { it.title() })
        assertEquals(listOf("Playback", "Volume", "Brightness"), ActionPresetCatalog.presets()[2].sections().map { it.title() })
        assertEquals(listOf(""), ActionPresetCatalog.presets()[3].sections().map { it.title() })
        assertTrue(entries.getValue("common-close").destructive())
        assertFalse(entries.getValue("common-minimize").destructive())
        assertFalse(entries.getValue("common-maximize").destructive())
        val media = entries.filterKeys { it.startsWith("media-") }.values
        assertEquals(8, media.size)
        assertTrue(media.all { it.command().startsWith("key XF86") })
        assertFalse(media.any { it.title().contains("%") || it.command().contains("set-volume") || it.command().contains("get-") })
        for (index in 1..12) {
            assertEquals("F$index", entries.getValue("function-f${index}").title())
            assertEquals("key F$index", entries.getValue("function-f${index}").command())
        }
    }

    @Test fun presetProfilesAreInMemoryRenderingModelsOnly() {
        val profiles = ActionPresetCatalog.presets().map { it.toLayoutProfile("#EEF1F4", "#FBE9E7") }
        profiles.forEachIndexed { index, profile ->
            val preset = ActionPresetCatalog.presets()[index]
            assertEquals(preset.id(), profile.id)
            assertEquals(preset.title(), profile.name)
            assertEquals(LAYOUT_COLUMN_COUNT, profile.columnCount)
            assertEquals(preset.sections().sumOf { it.entries().size }, profile.buttons.size)
            assertTrue(profile.buttons.all { it.width == 5 })
        }
    }

    @Test fun catalogCommandsStillFlowThroughTheExistingXdotoolAndYdotoolAdapters() {
        val entries = ActionPresetCatalog.presets().flatMap { it.sections().flatMap { section -> section.entries() } }
            .associateBy { it.id() }
        val selected = listOf("common-alt-tab", "common-close", "common-copy", "common-minimize",
            "common-maximize", "navigate-page-up", "media-previous", "media-play-pause", "media-next",
            "media-volume-down", "media-mute", "media-volume-up", "media-brightness-down",
            "media-brightness-up", "function-f12").map(entries::getValue)

        val xTransport = RecordingSshTransport()
        selected.forEach { CustomCommandDispatcher.execCommand(it.command(), XDoTool(xTransport), xTransport) }
        assertEquals(listOf(
            "xdotool key alt+Tab", "xdotool key alt+F4", "xdotool key ctrl+c",
            "xdotool windowminimize $(xdotool getactivewindow)",
            "xdotool windowsize $(xdotool getactivewindow) 100% 100%", "xdotool key Prior",
            "xdotool key XF86AudioPrev", "xdotool key XF86AudioPlay", "xdotool key XF86AudioNext",
            "xdotool key XF86AudioLowerVolume", "xdotool key XF86AudioMute", "xdotool key XF86AudioRaiseVolume",
            "xdotool key XF86MonBrightnessDown", "xdotool key XF86MonBrightnessUp", "xdotool key F12",
        ), xTransport.commands)

        val yTransport = RecordingSshTransport()
        selected.forEach { CustomCommandDispatcher.execCommand(it.command(), YDoTool(yTransport), yTransport) }
        assertEquals(listOf(
            "ydotool key 56:1 15:1 15:0 56:0", "ydotool key 56:1 62:1 62:0 56:0",
            "ydotool key 29:1 46:1 46:0 29:0", "xdotool windowminimize $(xdotool getactivewindow)",
            "xdotool windowsize $(xdotool getactivewindow) 100% 100%", "ydotool key 104:1 104:0",
            "ydotool key 165:1 165:0", "ydotool key 164:1 164:0", "ydotool key 163:1 163:0",
            "ydotool key 114:1 114:0", "ydotool key 113:1 113:0", "ydotool key 115:1 115:0",
            "ydotool key 224:1 224:0", "ydotool key 225:1 225:0", "ydotool key 88:1 88:0",
        ), yTransport.commands)
    }
}
