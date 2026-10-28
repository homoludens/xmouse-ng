package net.droopia.xmouse_ng.transport

/** Fixed result codes only; clipboard content and remote stderr never cross this boundary. */
enum class ClipboardTransferResult {
    COPIED,
    MISSING_HELPER,
    DESKTOP_UNAVAILABLE,
    FAILED,
}
