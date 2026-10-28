package net.droopia.xmouse_ng.transport

enum class ClipboardTextValidity { VALID, TOO_LARGE, MALFORMED }

/** Counts UTF-8 bytes without allocating or truncating clipboard contents. */
object ClipboardTextBounds {
    const val MAX_BYTES = 64 * 1024

    @JvmStatic
    fun validate(text: String): ClipboardTextValidity {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val value = text[index]
            val width = when {
                value.code <= 0x7f -> 1
                value.code <= 0x7ff -> 2
                Character.isHighSurrogate(value) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) {
                        return ClipboardTextValidity.MALFORMED
                    }
                    index++
                    4
                }
                Character.isLowSurrogate(value) -> return ClipboardTextValidity.MALFORMED
                else -> 3
            }
            if (bytes > MAX_BYTES - width) return ClipboardTextValidity.TOO_LARGE
            bytes += width
            index++
        }
        return ClipboardTextValidity.VALID
    }
}
