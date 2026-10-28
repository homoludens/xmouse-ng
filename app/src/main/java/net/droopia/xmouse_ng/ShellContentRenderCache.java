package net.droopia.xmouse_ng;

import java.util.Objects;

/** UI-thread content invalidation only. Navigation still retires input even on a cache hit. */
final class ShellContentRenderCache {
    private Object renderedKey;
    private boolean rendered;

    /** Call with an immutable content/state key. Hidden changes remain pending until displayed. */
    boolean shouldRender(boolean visible, Object key) {
        if (!visible || (rendered && Objects.equals(renderedKey, key))) return false;
        renderedKey = key;
        rendered = true;
        return true;
    }
}
