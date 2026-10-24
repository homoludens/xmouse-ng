package com.stripe1.xmouse.transport

/** Fixed result codes only; clipboard content and remote stderr never cross this boundary. */
enum class ClipboardTransferResult {
    COPIED,
    MISSING_HELPER,
    DESKTOP_UNAVAILABLE,
    FAILED,
}
