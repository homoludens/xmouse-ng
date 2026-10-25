package com.stripe1.xmouse;

/** Quotes user-entered text as one literal POSIX-shell argument for the existing xdotool type path. */
public final class ShellTextEncoder {
    private ShellTextEncoder() { }

    /**
     * Single-quote the argument. The standard close/escaped-quote/reopen sequence protects literal
     * quotes, Unicode, newlines, backticks, and shell substitutions without changing the text.
     */
    public static String quoteForShell(String text) {
        if (text == null) throw new NullPointerException("text");
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
