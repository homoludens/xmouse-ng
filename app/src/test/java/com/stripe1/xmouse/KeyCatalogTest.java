package com.stripe1.xmouse;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KeyCatalogTest {
    @Test public void allCategoriesHaveTheApprovedUniqueLabelsAndSections() {
        assertEquals(Arrays.asList("Common", "Navigate", "Media", "F1–F12"), categoryLabels());
        assertEquals(Arrays.asList("Window", "Editing", "File"), sectionLabels(KeyCatalog.Category.COMMON));

        assertEquals(Arrays.asList(
                "Esc", "Super", "Switch / Alt+Tab", "Minimize", "Maximize", "Close",
                "Copy", "Cut", "Paste", "Undo", "Redo", "Select all", "New", "Open", "Save", "Find", "Print"),
                labels(KeyCatalog.Category.COMMON));
        assertEquals(Arrays.asList(
                "Home", "Up", "End", "Left", "Down", "Right", "Page up", "Page down", "Tab", "Delete",
                "Backspace", "Enter", "Zoom out", "Reset zoom", "Zoom in"),
                labels(KeyCatalog.Category.NAVIGATE));
        assertEquals(Arrays.asList(
                "Previous", "Play/Pause", "Next", "Volume −", "Mute", "Volume +", "Brightness −", "Brightness +"),
                labels(KeyCatalog.Category.MEDIA));
        assertEquals(Arrays.asList("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"),
                labels(KeyCatalog.Category.FUNCTION));

        Set<String> unique = new HashSet<>();
        for (KeyCatalog.Category category : KeyCatalog.categories()) {
            for (KeyCatalog.Entry entry : entries(category)) {
                assertTrue("duplicate key label " + entry.label(), unique.add(entry.label()));
                assertFalse("percentage/fake level in key label", entry.label().contains("%"));
                assertNotNull(entry.command());
                assertFalse(entry.command().isEmpty());
            }
        }
    }

    @Test public void catalogRetainsLegacyCommandsAndUsesRelativeMediaKeys() {
        assertEquals("key Escape", entry(KeyCatalog.Category.COMMON, "Esc").command());
        assertEquals("key super", entry(KeyCatalog.Category.COMMON, "Super").command());
        assertEquals("key alt+Tab", entry(KeyCatalog.Category.COMMON, "Switch / Alt+Tab").command());
        assertEquals("xdotool windowminimize $(xdotool getactivewindow)",
                entry(KeyCatalog.Category.COMMON, "Minimize").command());
        assertEquals("xdotool windowsize $(xdotool getactivewindow) 100% 100%",
                entry(KeyCatalog.Category.COMMON, "Maximize").command());
        assertEquals("key alt+F4", entry(KeyCatalog.Category.COMMON, "Close").command());
        assertTrue(entry(KeyCatalog.Category.COMMON, "Close").destructive());
        assertEquals("key ctrl+x", entry(KeyCatalog.Category.COMMON, "Cut").command());
        assertEquals("key Prior", entry(KeyCatalog.Category.NAVIGATE, "Page up").command());
        assertEquals("key Next", entry(KeyCatalog.Category.NAVIGATE, "Page down").command());
        assertEquals("key ctrl+minus", entry(KeyCatalog.Category.NAVIGATE, "Zoom out").command());
        assertEquals("key ctrl+0", entry(KeyCatalog.Category.NAVIGATE, "Reset zoom").command());
        assertEquals("key ctrl+plus", entry(KeyCatalog.Category.NAVIGATE, "Zoom in").command());

        assertEquals("key XF86AudioPrev", entry(KeyCatalog.Category.MEDIA, "Previous").command());
        assertEquals("key XF86AudioPlay", entry(KeyCatalog.Category.MEDIA, "Play/Pause").command());
        assertEquals("key XF86AudioNext", entry(KeyCatalog.Category.MEDIA, "Next").command());
        assertEquals("key XF86AudioLowerVolume", entry(KeyCatalog.Category.MEDIA, "Volume −").command());
        assertEquals("key XF86AudioMute", entry(KeyCatalog.Category.MEDIA, "Mute").command());
        assertEquals("key XF86AudioRaiseVolume", entry(KeyCatalog.Category.MEDIA, "Volume +").command());
        assertEquals("key XF86MonBrightnessDown", entry(KeyCatalog.Category.MEDIA, "Brightness −").command());
        assertEquals("key XF86MonBrightnessUp", entry(KeyCatalog.Category.MEDIA, "Brightness +").command());
        for (KeyCatalog.Entry media : entries(KeyCatalog.Category.MEDIA)) {
            assertTrue("media controls stay discrete and do not poll", media.command().startsWith("key XF86"));
            assertFalse(media.label().toLowerCase().contains("playing"));
            assertFalse(media.label().toLowerCase().contains("percent"));
        }
        for (int key = 1; key <= 12; key++) {
            assertEquals("key F" + key, entry(KeyCatalog.Category.FUNCTION, "F" + key).command());
        }
    }

    @Test public void representativeCatalogCommandsUseBothExistingBackendAdapters() {
        List<KeyCatalog.Entry> selected = Arrays.asList(
                entry(KeyCatalog.Category.COMMON, "Switch / Alt+Tab"),
                entry(KeyCatalog.Category.COMMON, "Close"),
                entry(KeyCatalog.Category.COMMON, "Copy"),
                entry(KeyCatalog.Category.COMMON, "Minimize"),
                entry(KeyCatalog.Category.COMMON, "Maximize"),
                entry(KeyCatalog.Category.NAVIGATE, "Page up"),
                entry(KeyCatalog.Category.MEDIA, "Previous"),
                entry(KeyCatalog.Category.MEDIA, "Play/Pause"),
                entry(KeyCatalog.Category.MEDIA, "Next"),
                entry(KeyCatalog.Category.MEDIA, "Volume −"),
                entry(KeyCatalog.Category.MEDIA, "Mute"),
                entry(KeyCatalog.Category.MEDIA, "Volume +"),
                entry(KeyCatalog.Category.MEDIA, "Brightness −"),
                entry(KeyCatalog.Category.MEDIA, "Brightness +"),
                entry(KeyCatalog.Category.FUNCTION, "F12"));

        RecordingSshTransport xTransport = new RecordingSshTransport();
        dispatch(selected, new XDoTool(xTransport), xTransport);
        assertEquals(Arrays.asList(
                "xdotool key alt+Tab",
                "xdotool key alt+F4",
                "xdotool key ctrl+c",
                "xdotool windowminimize $(xdotool getactivewindow)",
                "xdotool windowsize $(xdotool getactivewindow) 100% 100%",
                "xdotool key Prior",
                "xdotool key XF86AudioPrev",
                "xdotool key XF86AudioPlay",
                "xdotool key XF86AudioNext",
                "xdotool key XF86AudioLowerVolume",
                "xdotool key XF86AudioMute",
                "xdotool key XF86AudioRaiseVolume",
                "xdotool key XF86MonBrightnessDown",
                "xdotool key XF86MonBrightnessUp",
                "xdotool key F12"), xTransport.getCommands());

        RecordingSshTransport yTransport = new RecordingSshTransport();
        dispatch(selected, new YDoTool(yTransport), yTransport);
        assertEquals(Arrays.asList(
                "ydotool key 56:1 15:1 15:0 56:0",
                "ydotool key 56:1 62:1 62:0 56:0",
                "ydotool key 29:1 46:1 46:0 29:0",
                "xdotool windowminimize $(xdotool getactivewindow)",
                "xdotool windowsize $(xdotool getactivewindow) 100% 100%",
                "ydotool key 104:1 104:0",
                "ydotool key 165:1 165:0",
                "ydotool key 164:1 164:0",
                "ydotool key 163:1 163:0",
                "ydotool key 114:1 114:0",
                "ydotool key 113:1 113:0",
                "ydotool key 115:1 115:0",
                "ydotool key 224:1 224:0",
                "ydotool key 225:1 225:0",
                "ydotool key 88:1 88:0"), yTransport.getCommands());
    }

    private static void dispatch(List<KeyCatalog.Entry> entries, IDoTool tool, CommandTransport transport) {
        for (KeyCatalog.Entry entry : entries) {
            CustomCommandDispatcher.execCommand(entry.command(), tool, transport);
        }
    }

    private static List<String> categoryLabels() {
        List<String> labels = new ArrayList<>();
        for (KeyCatalog.Category category : KeyCatalog.categories()) labels.add(category.label());
        return labels;
    }

    private static List<String> sectionLabels(KeyCatalog.Category category) {
        List<String> labels = new ArrayList<>();
        for (KeyCatalog.Section section : KeyCatalog.sections(category)) labels.add(section.label());
        return labels;
    }

    private static List<String> labels(KeyCatalog.Category category) {
        List<String> labels = new ArrayList<>();
        for (KeyCatalog.Entry entry : entries(category)) labels.add(entry.label());
        return labels;
    }

    private static List<KeyCatalog.Entry> entries(KeyCatalog.Category category) {
        List<KeyCatalog.Entry> entries = new ArrayList<>();
        for (KeyCatalog.Section section : KeyCatalog.sections(category)) entries.addAll(section.entries());
        return entries;
    }

    private static KeyCatalog.Entry entry(KeyCatalog.Category category, String label) {
        for (KeyCatalog.Entry entry : entries(category)) if (entry.label().equals(label)) return entry;
        throw new AssertionError("missing key label: " + label);
    }
}
