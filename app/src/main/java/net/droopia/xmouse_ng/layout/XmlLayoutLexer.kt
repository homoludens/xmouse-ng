package net.droopia.xmouse_ng.layout

/** Presentation-only XML tokens; ranges are UTF-16 offsets into the untouched editor source. */
data class XmlLayoutToken(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind { TEXT, MARKUP, ELEMENT, ATTRIBUTE, QUOTED_VALUE, COMMENT }
}

/** Bounded, single-pass scanner for incomplete XML. It does not parse, rewrite, or normalize input. */
object XmlLayoutLexer {
    const val MAX_TOKENS = 32_768

    fun tokens(source: String): List<XmlLayoutToken> {
        if (source.isEmpty()) return emptyList()
        val result = ArrayList<XmlLayoutToken>(minOf(source.length / 3 + 1, 512))
        var index = 0

        fun emit(start: Int, end: Int, kind: XmlLayoutToken.Kind) {
            if (end <= start) return
            if (result.size >= MAX_TOKENS - 1) {
                if (start < source.length) result += XmlLayoutToken(start, source.length, XmlLayoutToken.Kind.TEXT)
                index = source.length
            } else {
                result += XmlLayoutToken(start, end, kind)
            }
        }

        while (index < source.length) {
            if (source.startsWith("<!--", index)) {
                val start = index
                val close = source.indexOf("-->", index + 4)
                index = if (close < 0) source.length else close + 3
                emit(start, index, XmlLayoutToken.Kind.COMMENT)
                continue
            }
            if (source[index] != '<') {
                val start = index
                val nextTag = source.indexOf('<', index)
                index = if (nextTag < 0) source.length else nextTag
                emit(start, index, XmlLayoutToken.Kind.TEXT)
                continue
            }

            val open = index
            emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
            index++
            if (index < source.length && (source[index] == '/' || source[index] == '?' || source[index] == '!')) {
                emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                index++
            }
            val nameStart = index
            while (index < source.length && !source[index].isWhitespace() && source[index] !in "/>?<>=" ) index++
            if (index > nameStart) emit(nameStart, index, XmlLayoutToken.Kind.ELEMENT)

            while (index < source.length && source[index] != '>') {
                if (source[index].isWhitespace() || source[index] == '/') {
                    emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                    index++
                    continue
                }
                if (source[index] == '?') {
                    emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                    index++
                    continue
                }
                if (source[index] == '=') {
                    emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                    index++
                    continue
                }
                val attributeStart = index
                while (index < source.length && !source[index].isWhitespace() && source[index] !in "=/?>") index++
                if (index > attributeStart) {
                    emit(attributeStart, index, XmlLayoutToken.Kind.ATTRIBUTE)
                } else {
                    emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                    index++
                }
                while (index < source.length && source[index].isWhitespace()) {
                    emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                    index++
                }
                if (index < source.length && source[index] == '=') {
                    emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                    index++
                    while (index < source.length && source[index].isWhitespace()) {
                        emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                        index++
                    }
                    if (index < source.length && (source[index] == '\'' || source[index] == '"')) {
                        val quote = source[index]
                        val valueStart = index
                        index++
                        while (index < source.length && source[index] != quote) index++
                        if (index < source.length) index++
                        emit(valueStart, index, XmlLayoutToken.Kind.QUOTED_VALUE)
                    }
                }
            }
            if (index < source.length && source[index] == '>') {
                emit(index, index + 1, XmlLayoutToken.Kind.MARKUP)
                index++
            }
            // A lone '<' is markup, not a dropped character.
            if (index == open + 1 && index < source.length) index++
        }
        return result
    }
}
