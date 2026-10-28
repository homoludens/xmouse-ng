package net.droopia.xmouse_ng.transport;

/** Fixed remote exec helper. Clipboard content is supplied only on the channel's stdin. */
public final class LinuxClipboardHelper {
    private static final String SCRIPT =
            "drain_stdin() { cat >/dev/null; }\n" +
            "uid=$(id -u 2>/dev/null) || uid=\n" +
            "runtime=${XDG_RUNTIME_DIR:-}\n" +
            "if [ -n \"$runtime\" ]; then\n" +
            "  if [ -d \"$runtime\" ] && [ -n \"$uid\" ]; then\n" +
            "    runtime_owner=$(stat -c %u \"$runtime\" 2>/dev/null) || runtime_owner=\n" +
            "    if [ \"$runtime_owner\" != \"$uid\" ]; then runtime=; fi\n" +
            "  else runtime=; fi\n" +
            "fi\n" +
            "if [ -z \"$runtime\" ] && [ -n \"$uid\" ]; then\n" +
            "  candidate=/run/user/$uid\n" +
            "  candidate_owner=$(stat -c %u \"$candidate\" 2>/dev/null) || candidate_owner=\n" +
            "  if [ -d \"$candidate\" ] && [ \"$candidate_owner\" = \"$uid\" ]; then runtime=$candidate; fi\n" +
            "fi\n" +
            "wayland=${WAYLAND_DISPLAY:-}\n" +
            "socket=\n" +
            "if [ -n \"$uid\" ] && [ -z \"$wayland\" ] && [ -n \"$runtime\" ] && [ -d \"$runtime\" ]; then\n" +
            "  socket_count=0\n" +
            "  for candidate in \"$runtime\"/wayland-*; do\n" +
            "    [ -S \"$candidate\" ] || continue\n" +
            "    candidate_owner=$(stat -c %u \"$candidate\" 2>/dev/null) || candidate_owner=\n" +
            "    [ \"$candidate_owner\" = \"$uid\" ] || continue\n" +
            "    socket=$candidate\n" +
            "    socket_count=$((socket_count + 1))\n" +
            "  done\n" +
            "  if [ \"$socket_count\" -eq 1 ]; then wayland=${socket##*/}; else socket=; fi\n" +
            "fi\n" +
            "if [ -n \"$uid\" ] && [ -n \"$wayland\" ]; then\n" +
            "  case \"$wayland\" in /*) socket=$wayland ;; *) if [ -n \"$runtime\" ]; then socket=$runtime/$wayland; else socket=; fi ;; esac\n" +
            "fi\n" +
            "have_wl=0\n" +
            "if command -v wl-copy >/dev/null 2>&1; then\n" +
            "  have_wl=1\n" +
            "  if [ -n \"$socket\" ] && [ -S \"$socket\" ]; then\n" +
            "    socket_owner=$(stat -c %u \"$socket\" 2>/dev/null) || socket_owner=\n" +
            "    if [ \"$socket_owner\" = \"$uid\" ]; then\n" +
            "      if [ -n \"$runtime\" ]; then XDG_RUNTIME_DIR=$runtime; export XDG_RUNTIME_DIR; fi\n" +
            "      WAYLAND_DISPLAY=$wayland; export WAYLAND_DISPLAY\n" +
            "      wl-copy >/dev/null 2>&1\n" +
            "      status=$?\n" +
            "      if [ \"$status\" -eq 0 ]; then exit 0; fi\n" +
            "      drain_stdin; exit 72\n" +
            "    fi\n" +
            "  fi\n" +
            "fi\n" +
            "if [ -z \"${DISPLAY:-}\" ]; then DISPLAY=:0; export DISPLAY; fi\n" +
            "if command -v xclip >/dev/null 2>&1; then\n" +
            "  xclip -selection clipboard -in >/dev/null 2>&1\n" +
            "  status=$?\n" +
            "  if [ \"$status\" -eq 0 ]; then exit 0; fi\n" +
            "  drain_stdin; exit 71\n" +
            "fi\n" +
            "if command -v xsel >/dev/null 2>&1; then\n" +
            "  xsel --clipboard --input >/dev/null 2>&1\n" +
            "  status=$?\n" +
            "  if [ \"$status\" -eq 0 ]; then exit 0; fi\n" +
            "  drain_stdin; exit 71\n" +
            "fi\n" +
            "drain_stdin\n" +
            "if [ \"$have_wl\" -eq 1 ]; then exit 71; fi\n" +
            "exit 70\n";

    /** No clipboard payload, environment interpolation, or user-controlled shell text is included. */
    public static final String COMMAND = "/bin/sh -c " + shellQuote(SCRIPT);

    private LinuxClipboardHelper() { }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static ClipboardTransferResult resultForExitStatus(int status) {
        switch (status) {
            case 0: return ClipboardTransferResult.COPIED;
            case 70: return ClipboardTransferResult.MISSING_HELPER;
            case 71: return ClipboardTransferResult.DESKTOP_UNAVAILABLE;
            default: return ClipboardTransferResult.FAILED;
        }
    }
}
