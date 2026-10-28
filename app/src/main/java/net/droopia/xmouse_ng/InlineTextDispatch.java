package net.droopia.xmouse_ng;

/** Sends composer text through an already destination-scoped input tool. */
public final class InlineTextDispatch {
    private InlineTextDispatch() { }

    /** @return true when non-blank text was submitted. */
    public static boolean send(IDoTool inputTool, String text, boolean sendEnterAfterText) {
        if (inputTool == null || text == null || text.trim().isEmpty()) return false;
        inputTool.type(ShellTextEncoder.quoteForShell(text));
        if (sendEnterAfterText) inputTool.key("Enter");
        return true;
    }
}
