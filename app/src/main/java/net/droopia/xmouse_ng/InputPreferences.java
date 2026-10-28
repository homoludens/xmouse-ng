package net.droopia.xmouse_ng;

import java.util.Arrays;
import java.util.Map;

/** Non-secret input settings using the original keys and string-valued scalar format. */
public final class InputPreferences {
    public static final String POINTER_SPEED = "sensitivity_list";
    public static final String INVERT_SCROLL = "mouse_invert_scroll";
    public static final String KEYBOARD_AUTO_CLEAR = "keyboard_autoclear";
    public static final String KEYBOARD_SEND_ENTER_AFTER_TEXT = "keyboard_send_enter_after_text";
    public static final float DEFAULT_POINTER_SPEED = 1.0f;
    private static final String[] POINTER_CHOICES = {
            ".25f", ".3333f", ".5f", "1.0f", "1.5f", "2.0f", "3.0f"
    };

    public final float pointerSpeed;
    public final boolean invertScroll;
    public final boolean keyboardAutoClear;
    public final boolean keyboardSendEnterAfterText;

    private InputPreferences(float pointerSpeed, boolean invertScroll, boolean keyboardAutoClear,
                             boolean keyboardSendEnterAfterText) {
        this.pointerSpeed = pointerSpeed;
        this.invertScroll = invertScroll;
        this.keyboardAutoClear = keyboardAutoClear;
        this.keyboardSendEnterAfterText = keyboardSendEnterAfterText;
    }

    public static InputPreferences read(Map<String, ?> saved) {
        float speed = DEFAULT_POINTER_SPEED;
        Object value = saved.get(POINTER_SPEED);
        if (value instanceof String) {
            try {
                float parsed = Float.parseFloat((String) value);
                // Preserve valid custom legacy scalars, without silently quantizing or rewriting them.
                if (Float.isFinite(parsed) && parsed > 0) speed = parsed;
            } catch (NumberFormatException ignored) { /* Invalid data uses the runtime default only. */ }
        }
        return new InputPreferences(speed, bool(saved.get(INVERT_SCROLL), false),
                bool(saved.get(KEYBOARD_AUTO_CLEAR), true),
                bool(saved.get(KEYBOARD_SEND_ENTER_AFTER_TEXT), false));
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /** MyMouseView's original mapping: negative finger delta + non-inverted = wheel down. */
    public boolean naturalScrolling() { return !invertScroll; }

    public static boolean invertedForNaturalScrolling(boolean natural) { return !natural; }

    public int pointerChoiceIndex() {
        for (int i = 0; i < POINTER_CHOICES.length; i++) {
            if (Float.compare(pointerSpeed, Float.parseFloat(POINTER_CHOICES[i])) == 0) return i;
        }
        return -1;
    }

    /** New writes are restricted to the real configured scale, retaining its exact legacy strings. */
    public static String pointerChoiceForWrite(String choice) {
        if (!Arrays.asList(POINTER_CHOICES).contains(choice)) throw new IllegalArgumentException("Unknown pointer scale");
        return choice;
    }
}
