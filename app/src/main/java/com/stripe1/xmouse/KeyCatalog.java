package com.stripe1.xmouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure catalog of the native Keys destination and its existing command meanings. */
public final class KeyCatalog {
    public enum Category {
        COMMON("Common"),
        NAVIGATE("Navigate"),
        MEDIA("Media"),
        FUNCTION("F1–F12");

        private final String label;

        Category(String label) { this.label = label; }

        public String label() { return label; }
    }

    public static final class Entry {
        private final String label;
        private final String command;
        private final boolean destructive;

        private Entry(String label, String command, boolean destructive) {
            this.label = label;
            this.command = command;
            this.destructive = destructive;
        }

        public String label() { return label; }
        /** Existing CustomCommandDispatcher input: `key ...` or an unchanged legacy raw command. */
        public String command() { return command; }
        public boolean destructive() { return destructive; }
    }

    public static final class Section {
        private final String label;
        private final int columns;
        private final List<Entry> entries;

        private Section(String label, int columns, List<Entry> entries) {
            this.label = label;
            this.columns = columns;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        public String label() { return label; }
        public int columns() { return columns; }
        public List<Entry> entries() { return entries; }
    }

    private KeyCatalog() { }

    public static List<Category> categories() {
        List<Category> categories = new ArrayList<>();
        Collections.addAll(categories, Category.values());
        return Collections.unmodifiableList(categories);
    }

    public static List<Section> sections(Category category) {
        if (category == null) throw new IllegalArgumentException("category is required");
        switch (category) {
            case COMMON:
                return common();
            case NAVIGATE:
                return navigate();
            case MEDIA:
                return media();
            case FUNCTION:
                return functions();
            default:
                throw new IllegalArgumentException("unsupported category");
        }
    }

    private static List<Section> common() {
        List<Section> sections = new ArrayList<>();
        sections.add(section("Window", 2,
                key("Esc", "Escape"),
                key("Super", "super"),
                key("Switch / Alt+Tab", "alt+Tab"),
                raw("Minimize", "xdotool windowminimize $(xdotool getactivewindow)"),
                raw("Maximize", "xdotool windowsize $(xdotool getactivewindow) 100% 100%"),
                key("Close", "alt+F4", true)));
        sections.add(section("Editing", 2,
                key("Copy", "ctrl+c"),
                key("Cut", "ctrl+x"),
                key("Paste", "ctrl+v"),
                key("Undo", "ctrl+z"),
                key("Redo", "ctrl+y"),
                key("Select all", "ctrl+a")));
        sections.add(section("File", 2,
                key("New", "ctrl+n"),
                key("Open", "ctrl+o"),
                key("Save", "ctrl+s"),
                key("Find", "ctrl+f"),
                key("Print", "ctrl+p")));
        return Collections.unmodifiableList(sections);
    }

    private static List<Section> navigate() {
        List<Section> sections = new ArrayList<>();
        sections.add(section("Directions", 3,
                key("Home", "Home"),
                key("Up", "Up"),
                key("End", "End"),
                key("Left", "Left"),
                key("Down", "Down"),
                key("Right", "Right")));
        sections.add(section("Movement", 2,
                key("Page up", "Prior"),
                key("Page down", "Next"),
                key("Tab", "Tab"),
                key("Delete", "Delete"),
                key("Backspace", "BackSpace"),
                key("Enter", "Return")));
        sections.add(section("Zoom", 3,
                key("Zoom out", "ctrl+minus"),
                key("Reset zoom", "ctrl+0"),
                key("Zoom in", "ctrl+plus")));
        return Collections.unmodifiableList(sections);
    }

    private static List<Section> media() {
        List<Section> sections = new ArrayList<>();
        sections.add(section("Playback", 3,
                key("Previous", "XF86AudioPrev"),
                key("Play/Pause", "XF86AudioPlay"),
                key("Next", "XF86AudioNext")));
        sections.add(section("Volume", 3,
                key("Volume −", "XF86AudioLowerVolume"),
                key("Mute", "XF86AudioMute"),
                key("Volume +", "XF86AudioRaiseVolume")));
        sections.add(section("Brightness", 2,
                key("Brightness −", "XF86MonBrightnessDown"),
                key("Brightness +", "XF86MonBrightnessUp")));
        return Collections.unmodifiableList(sections);
    }

    private static List<Section> functions() {
        List<Entry> entries = new ArrayList<>();
        for (int key = 1; key <= 12; key++) entries.add(key("F" + key, "F" + key));
        return Collections.singletonList(new Section("", 4, entries));
    }

    private static Section section(String label, int columns, Entry... entries) {
        List<Entry> list = new ArrayList<>();
        Collections.addAll(list, entries);
        return new Section(label, columns, list);
    }

    private static Entry key(String label, String key) { return key(label, key, false); }

    private static Entry key(String label, String key, boolean destructive) {
        return new Entry(label, "key " + key, destructive);
    }

    private static Entry raw(String label, String command) {
        return new Entry(label, command, false);
    }
}
