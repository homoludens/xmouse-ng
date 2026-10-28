package net.droopia.xmouse_ng.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Read-only virtual Actions presets. This catalog never seeds or writes repository profiles. */
public final class ActionPresetCatalog {
    private static final int PRESET_COLUMNS = 10;
    public static final class Entry {
        private final String id;
        private final String title;
        private final String command;
        private final boolean destructive;

        private Entry(String id, String title, String command, boolean destructive) {
            this.id = id;
            this.title = title;
            this.command = command;
            this.destructive = destructive;
        }

        public String id() { return id; }
        public String title() { return title; }
        public String command() { return command; }
        public boolean destructive() { return destructive; }
        public int width() { return 5; }
    }

    public static final class Section {
        private final String id;
        private final String title;
        private final List<Entry> entries;

        private Section(String id, String title, List<Entry> entries) {
            this.id = id;
            this.title = title;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        public String id() { return id; }
        public String title() { return title; }
        public List<Entry> entries() { return entries; }
    }

    public static final class Preset {
        private final String id;
        private final String title;
        private final List<Section> sections;

        private Preset(String id, String title, List<Section> sections) {
            this.id = id;
            this.title = title;
            this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        }

        public String id() { return id; }
        public String title() { return title; }
        public List<Section> sections() { return sections; }

        /** Materialize an in-memory rendering model only; callers must not save it as user data. */
        public LayoutProfile toLayoutProfile(String neutralColor, String destructiveColor) {
            List<LayoutButton> buttons = new ArrayList<>();
            for (Section section : sections) {
                for (Entry entry : section.entries) {
                    buttons.add(new LayoutButton(entry.id, entry.title, entry.command, entry.width(),
                            entry.destructive ? destructiveColor : neutralColor));
                }
            }
            return new LayoutProfile(id, title, PRESET_COLUMNS, buttons);
        }
    }

    private static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("builtin-common", "Common", Arrays.asList(
                    section("common-window", "Window",
                            key("common-esc", "Esc", "Escape"),
                            key("common-super", "Super", "super"),
                            key("common-alt-tab", "Switch / Alt+Tab", "alt+Tab"),
                            raw("common-minimize", "Minimize", "xdotool windowminimize $(xdotool getactivewindow)"),
                            raw("common-maximize", "Maximize", "xdotool windowsize $(xdotool getactivewindow) 100% 100%"),
                            key("common-close", "Close", "alt+F4", true)),
                    section("common-editing", "Editing",
                            key("common-copy", "Copy", "ctrl+c"),
                            key("common-cut", "Cut", "ctrl+x"),
                            key("common-paste", "Paste", "ctrl+v"),
                            key("common-undo", "Undo", "ctrl+z"),
                            key("common-redo", "Redo", "ctrl+y"),
                            key("common-select-all", "Select all", "ctrl+a")),
                    section("common-file", "File",
                            key("common-new", "New", "ctrl+n"),
                            key("common-open", "Open", "ctrl+o"),
                            key("common-save", "Save", "ctrl+s"),
                            key("common-find", "Find", "ctrl+f"),
                            key("common-print", "Print", "ctrl+p")))),
            new Preset("builtin-navigate", "Navigate", Arrays.asList(
                    section("navigate-directions", "Directions",
                            key("navigate-home", "Home", "Home"),
                            key("navigate-up", "Up", "Up"),
                            key("navigate-end", "End", "End"),
                            key("navigate-left", "Left", "Left"),
                            key("navigate-down", "Down", "Down"),
                            key("navigate-right", "Right", "Right")),
                    section("navigate-movement", "Movement",
                            key("navigate-page-up", "Page up", "Prior"),
                            key("navigate-page-down", "Page down", "Next"),
                            key("navigate-tab", "Tab", "Tab"),
                            key("navigate-delete", "Delete", "Delete"),
                            key("navigate-backspace", "Backspace", "BackSpace"),
                            key("navigate-enter", "Enter", "Return")),
                    section("navigate-zoom", "Zoom",
                            key("navigate-zoom-out", "Zoom out", "ctrl+minus"),
                            key("navigate-zoom-reset", "Reset zoom", "ctrl+0"),
                            key("navigate-zoom-in", "Zoom in", "ctrl+plus")))),
            new Preset("builtin-media", "Media", Arrays.asList(
                    section("media-playback", "Playback",
                            key("media-previous", "Previous", "XF86AudioPrev"),
                            key("media-play-pause", "Play/Pause", "XF86AudioPlay"),
                            key("media-next", "Next", "XF86AudioNext")),
                    section("media-volume", "Volume",
                            key("media-volume-down", "Volume −", "XF86AudioLowerVolume"),
                            key("media-mute", "Mute", "XF86AudioMute"),
                            key("media-volume-up", "Volume +", "XF86AudioRaiseVolume")),
                    section("media-brightness", "Brightness",
                            key("media-brightness-down", "Brightness −", "XF86MonBrightnessDown"),
                            key("media-brightness-up", "Brightness +", "XF86MonBrightnessUp")))),
            new Preset("builtin-function-keys", "F1–F12", Collections.singletonList(functionKeys()))
    ));

    private ActionPresetCatalog() { }

    public static List<Preset> presets() { return PRESETS; }

    public static Preset find(String id) {
        for (Preset preset : PRESETS) if (preset.id.equals(id)) return preset;
        return null;
    }

    private static Section functionKeys() {
        List<Entry> entries = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            String key = "F" + index;
            entries.add(key("function-" + key.toLowerCase(java.util.Locale.ROOT), key, key));
        }
        return new Section("function-keys", "", entries);
    }

    private static Section section(String id, String title, Entry... entries) {
        return new Section(id, title, Arrays.asList(entries));
    }

    private static Entry key(String id, String title, String key) {
        return key(id, title, key, false);
    }

    private static Entry key(String id, String title, String key, boolean destructive) {
        return new Entry(id, title, "key " + key, destructive);
    }

    private static Entry raw(String id, String title, String command) {
        return new Entry(id, title, command, false);
    }
}
