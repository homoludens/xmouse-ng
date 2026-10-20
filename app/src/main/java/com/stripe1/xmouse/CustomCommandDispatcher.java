package com.stripe1.xmouse;

/** Existing custom-button routing, independent of Android views and SSH implementation. */
public final class CustomCommandDispatcher {
    private CustomCommandDispatcher() {
    }

    /** Missing arguments are reported by the UI caller using its existing diagnostic. */
    public static void execCommand(String command, IDoTool doTool, CommandTransport transport) {
        // Remove only the legacy prefix; keep tokenization and raw-shell fallback unchanged.
        String commandNormalized = command.replaceAll("^xdotool ", "");
        String[] args = commandNormalized.split(" ");
        switch (args[0]) {
            case "mousemoverelative": doTool.mouseMoveRelative(Float.parseFloat(args[1]), Float.parseFloat(args[2])); break;
            case "mouseclick": doTool.mouseClick(Integer.parseInt(args[1])); break;
            case "mousedown": doTool.mouseDown(Integer.parseInt(args[1])); break;
            case "mouseup": doTool.mouseUp(Integer.parseInt(args[1])); break;
            case "mousewheelup": doTool.mouseWheelUp(); break;
            case "mousewheeldown": doTool.mouseWheelDown(); break;
            case "key": doTool.key(args[1]); break;
            case "type": doTool.type(args[1]); break;
            default: transport.executeShellCommand(command); break;
        }
    }
}
