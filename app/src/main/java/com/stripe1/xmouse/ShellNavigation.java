package com.stripe1.xmouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** UI-thread shell state. No transport, persistence, or implicit command/session side effects. */
public final class ShellNavigation {
    public enum Destination { TOUCHPAD, KEYS, ACTIONS }
    public enum Overlay { COMPUTERS, TYPING, ACTION_EDIT, MENU, EXTERNAL }
    public enum InputRole { NONE, POINTER, KEYS, ACTIONS, TYPING }

    public interface Listener {
        /** Must revoke capabilities and reset held view state before any old content is hidden. */
        void retireInput();
        void render();
        /** Re-evaluate lifecycle/session readiness; never connect or dispatch here. */
        void refreshInput();
    }

    private final Listener listener;
    private Destination destination = Destination.TOUCHPAD;
    private boolean joystick;
    private final List<Overlay> overlays = new ArrayList<>();

    public ShellNavigation(Listener listener) { this.listener = listener; }
    public Destination destination() { return destination; }
    public boolean joystick() { return joystick; }
    public Overlay topOverlay() { return overlays.isEmpty() ? null : overlays.get(overlays.size() - 1); }
    public List<Overlay> overlays() { return Collections.unmodifiableList(new ArrayList<>(overlays)); }

    public InputRole inputRole() {
        if (topOverlay() != null) return topOverlay() == Overlay.TYPING ? InputRole.TYPING : InputRole.NONE;
        switch (destination) {
            case TOUCHPAD: return InputRole.POINTER;
            case KEYS: return InputRole.KEYS;
            case ACTIONS: return InputRole.ACTIONS;
            default: return InputRole.NONE;
        }
    }

    public boolean eligible(boolean resumed, boolean focused, boolean ready) {
        return resumed && focused && ready && inputRole() != InputRole.NONE;
    }

    public void select(Destination next) {
        if (next == destination || !overlays.isEmpty()) return;
        change(() -> destination = next);
    }

    public void setJoystick(boolean enabled) {
        if (joystick == enabled || destination != Destination.TOUCHPAD || !overlays.isEmpty()) return;
        change(() -> joystick = enabled);
    }

    public void open(Overlay overlay) {
        if (topOverlay() == overlay) return;
        change(() -> overlays.add(overlay));
    }

    public void closeTop() {
        if (!overlays.isEmpty()) change(() -> overlays.remove(overlays.size() - 1));
    }

    public void close(Overlay overlay) {
        int index = overlays.lastIndexOf(overlay);
        if (index >= 0) change(() -> overlays.remove(index));
    }

    /** Local layer first, then pointer mode, then Touchpad, then normal Android Back. */
    public boolean back() {
        if (!overlays.isEmpty()) { closeTop(); return true; }
        if (destination != Destination.TOUCHPAD) { select(Destination.TOUCHPAD); return true; }
        if (joystick) { setJoystick(false); return true; }
        return false;
    }

    /** Restore only navigation, never a token, command, credential, or editor text. */
    public void restore(String savedDestination, boolean savedJoystick, List<String> savedOverlays) {
        change(() -> {
            try { destination = Destination.valueOf(savedDestination); }
            catch (RuntimeException ignored) { destination = Destination.TOUCHPAD; }
            joystick = savedJoystick;
            overlays.clear();
            for (String value : savedOverlays) {
                try {
                    Overlay overlay = Overlay.valueOf(value);
                    if (overlay == Overlay.COMPUTERS || overlay == Overlay.TYPING) overlays.add(overlay);
                } catch (RuntimeException ignored) { /* Unknown or external editor state is not revived. */ }
            }
        });
    }

    private void change(Runnable mutation) {
        listener.retireInput();
        mutation.run();
        listener.render();
        listener.refreshInput();
    }
}
