package net.droopia.xmouse_ng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** UI-thread shell state. No transport, persistence, or implicit command/session side effects. */
public final class ShellNavigation {
    public enum Destination { TOUCHPAD, ACTIONS, SCRIPTS }
    public enum Overlay { COMPUTERS, ACTION_EDIT, MENU, EXTERNAL }
    public enum InputRole { NONE, POINTER, ACTIONS }

    public interface Listener {
        /** Must revoke capabilities and reset held view state before any old content is hidden. */
        void retireInput();
        /** Clear only at explicit privacy boundaries; ordinary layer/destination changes suspend. */
        void retireLocalText();
        /** Hide the IME and focus while retaining an in-memory draft shared by shell destinations. */
        default void suspendLocalText() { }
        void render();
        /** Re-evaluate lifecycle/session readiness; never connect or dispatch here. */
        void refreshInput();
    }

    private final Listener listener;
    private Destination destination = Destination.TOUCHPAD;
    private final List<Overlay> overlays = new ArrayList<>();

    public ShellNavigation(Listener listener) { this.listener = listener; }
    public Destination destination() { return destination; }
    public Overlay topOverlay() { return overlays.isEmpty() ? null : overlays.get(overlays.size() - 1); }
    public List<Overlay> overlays() { return Collections.unmodifiableList(new ArrayList<>(overlays)); }

    public InputRole inputRole() {
        if (topOverlay() != null) return InputRole.NONE;
        switch (destination) {
            case TOUCHPAD: return InputRole.POINTER;
            case ACTIONS: return InputRole.ACTIONS;
            // Scripts use their separate session presentation/exec route, never a control token.
            case SCRIPTS: return InputRole.NONE;
            default: return InputRole.NONE;
        }
    }

    public boolean eligible(boolean resumed, boolean focused, boolean ready) {
        return resumed && focused && ready && inputRole() != InputRole.NONE;
    }

    public void select(Destination next) {
        if (next == destination || !overlays.isEmpty()) return;
        change(() -> destination = next, LocalTextAction.SUSPEND);
    }

    public void open(Overlay overlay) {
        if (topOverlay() == overlay) return;
        change(() -> overlays.add(overlay), localTextAction(overlay));
    }

    public void closeTop() {
        if (!overlays.isEmpty()) {
            LocalTextAction localText = localTextAction(topOverlay());
            change(() -> overlays.remove(overlays.size() - 1), localText);
        }
    }

    public void close(Overlay overlay) {
        int index = overlays.lastIndexOf(overlay);
        if (index >= 0) change(() -> overlays.remove(index), localTextAction(overlay));
    }

    /** Saved Actions can refresh in the background without hiding the inline Touchpad text field. */
    public void replaceContent(Runnable mutation) { change(mutation, LocalTextAction.KEEP); }

    /** Local layer first, then Touchpad, then normal Android Back. */
    public boolean back() {
        if (!overlays.isEmpty()) { closeTop(); return true; }
        if (destination != Destination.TOUCHPAD) { select(Destination.TOUCHPAD); return true; }
        return false;
    }

    /** Restore only navigation, never a token, command, credential, or editor text. */
    public void restore(String savedDestination, List<String> savedOverlays) {
        change(() -> {
            try { destination = Destination.valueOf(savedDestination); }
            catch (RuntimeException ignored) { destination = Destination.TOUCHPAD; }
            overlays.clear();
            for (String value : savedOverlays) {
                try {
                    Overlay overlay = Overlay.valueOf(value);
                    if (overlay == Overlay.COMPUTERS) overlays.add(overlay);
                } catch (RuntimeException ignored) { /* Unknown or external editor state is not revived. */ }
            }
        }, LocalTextAction.CLEAR);
    }

    private enum LocalTextAction { KEEP, SUSPEND, CLEAR }

    /** In-shell sheets/dialogs and system prompts retain drafts; external screens stay a clear boundary. */
    private static LocalTextAction localTextAction(Overlay overlay) {
        return overlay == Overlay.EXTERNAL ? LocalTextAction.CLEAR : LocalTextAction.SUSPEND;
    }

    private void change(Runnable mutation, LocalTextAction localTextAction) {
        listener.retireInput();
        if (localTextAction == LocalTextAction.CLEAR) listener.retireLocalText();
        else if (localTextAction == LocalTextAction.SUSPEND) listener.suspendLocalText();
        mutation.run();
        listener.render();
        listener.refreshInput();
    }
}
