package net.droopia.xmouse_ng.layout

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.SAXParseException
import org.xml.sax.XMLReader
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler

/** A schema/identity error from the editable layout XML source. */
class LayoutXmlException(
    message: String,
    val line: Int? = null,
    val column: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    fun displayMessage(): String = if (line != null && column != null) {
        "Line $line, column $column: $message"
    } else message.orEmpty()
}

/**
 * XML is only a lossless source representation of a LayoutProfile. It is never Android view XML,
 * a file reference, or executable input. DTDs, entities, processing instructions, namespaces, and
 * unknown schema fields are deliberately unsupported. Comments inside layout/button elements are
 * formatting annotations: Format retains them, but they are not saved as profile data.
 */
object LayoutProfileXml {
    const val MAX_SOURCE_CHARS = 262_144
    const val MAX_BUTTONS = 100
    private const val MAX_DEPTH = 3
    private const val VERSION = "1"
    private const val XML_VERSION = "1.0"
    private val XML_DECLARATION_VERSION = Regex(
        """^<\?xml[ \t\r\n]+version[ \t\r\n]*=[ \t\r\n]*(['"])([^'"]+)\1(?=[ \t\r\n]|\?>)""",
    )

    fun serialize(profile: LayoutProfile): String {
        return serialize(profile, emptyList())
    }

    private fun serialize(profile: LayoutProfile, comments: List<XmlComment>): String {
        validateXmlString(profile.id, "profile ID")
        validateXmlString(profile.name, "profile name")
        profile.buttons.forEachIndexed { index, button ->
            validateXmlString(button.id, "button ${index + 1} ID")
            validateXmlString(button.title, "button ${index + 1} title")
            validateXmlString(button.command, "button ${index + 1} command")
            validateXmlString(button.color, "button ${index + 1} color")
        }
        if (profile.buttons.size > MAX_BUTTONS) {
            throw LayoutXmlException("A layout can contain at most $MAX_BUTTONS buttons.")
        }

        val output = StringBuilder()
        fun append(value: String) {
            if (value.length > MAX_SOURCE_CHARS - output.length) {
                throw LayoutXmlException("XML source exceeds the $MAX_SOURCE_CHARS character limit.")
            }
            output.append(value)
        }
        fun appendCharacter(value: Char) {
            if (output.length >= MAX_SOURCE_CHARS) {
                throw LayoutXmlException("XML source exceeds the $MAX_SOURCE_CHARS character limit.")
            }
            output.append(value)
        }
        fun appendEscaped(value: String, attribute: Boolean) {
            value.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    '\t' -> if (attribute) append("&#x9;") else appendCharacter(char)
                    '\n' -> if (attribute) append("&#xA;") else appendCharacter(char)
                    '\r' -> append("&#xD;")
                    else -> appendCharacter(char)
                }
            }
        }
        fun appendComment(text: String, indent: String) {
            append(indent)
            append("<!--")
            append(text)
            append("-->\n")
        }

        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<layout version=\"$VERSION\" id=\"")
        appendEscaped(profile.id, attribute = true)
        append("\" name=\"")
        appendEscaped(profile.name, attribute = true)
        append("\" columns=\"$LAYOUT_COLUMN_COUNT\">\n")
        profile.buttons.forEachIndexed { buttonIndex, button ->
            comments.filter { it.buttonIndex == null && it.slot == buttonIndex }.forEach { comment ->
                appendComment(comment.text, "  ")
            }
            append("  <button id=\"")
            appendEscaped(button.id, attribute = true)
            append("\" width=\"")
            append(button.width.toString())
            append("\" color=\"")
            appendEscaped(button.color, attribute = true)
            append("\">\n")
            comments.filter { it.buttonIndex == buttonIndex && it.slot == 0 }.forEach { comment ->
                appendComment(comment.text, "    ")
            }
            append("    <title>")
            appendEscaped(button.title, attribute = false)
            append("</title>\n")
            comments.filter { it.buttonIndex == buttonIndex && it.slot == 1 }.forEach { comment ->
                appendComment(comment.text, "    ")
            }
            append("    <command>")
            appendEscaped(button.command, attribute = false)
            append("</command>\n")
            comments.filter { it.buttonIndex == buttonIndex && it.slot == 2 }.forEach { comment ->
                appendComment(comment.text, "    ")
            }
            append("  </button>\n")
        }
        comments.filter { it.buttonIndex == null && it.slot == profile.buttons.size }.forEach { comment ->
            appendComment(comment.text, "  ")
        }
        append("</layout>\n")
        return output.toString()
    }

    /** Parses only the version-1 profile schema and requires its stable ID to match the editor. */
    @Throws(LayoutXmlException::class)
    fun parse(source: String, expectedProfileId: String): LayoutProfile {
        return parseDocument(source, expectedProfileId).profile
    }

    private fun parseDocument(source: String, expectedProfileId: String): ParsedLayout {
        if (source.length > MAX_SOURCE_CHARS) {
            val (line, column) = locationAt(source, MAX_SOURCE_CHARS)
            throw LayoutXmlException("XML source exceeds the $MAX_SOURCE_CHARS character limit.", line, column)
        }
        val invalidCharacter = firstInvalidXmlCharacter(source)
        if (invalidCharacter != null) {
            val (line, column) = locationAt(source, invalidCharacter)
            throw LayoutXmlException("XML 1.0 cannot represent this character.", line, column)
        }
        // A String-backed parser may encounter an initial BOM as a character rather than a byte
        // signature; ignore it only for locating the declaration version.
        val declarationOffset = if (source.startsWith('\uFEFF')) 1 else 0
        val declaration = XML_DECLARATION_VERSION.find(source.substring(declarationOffset))
        if (declaration != null && declaration.groupValues[2] != XML_VERSION) {
            val offset = (declaration.groups[2]?.range?.first ?: 0) + declarationOffset
            val (line, column) = locationAt(source, offset)
            throw LayoutXmlException("Only XML $XML_VERSION declarations are supported.", line, column)
        }
        // Declarations cannot appear in profile data; reject before any parser sees the source.
        // This is also fail-closed on Android SAX implementations without the Xerces DOCTYPE flag.
        listOf("<!DOCTYPE", "<!ENTITY").forEach { forbidden ->
            val index = source.indexOf(forbidden, ignoreCase = true)
            if (index >= 0) {
                val (line, column) = locationAt(source, index)
                throw LayoutXmlException("DTD and entity declarations are not supported.", line, column)
            }
        }
        if (expectedProfileId.isBlank()) throw LayoutXmlException("The target layout identity is unavailable.")

        val handler = ProfileHandler(expectedProfileId)
        try {
            newSafeReader(handler).parse(InputSource(StringReader(source)))
            return ParsedLayout(handler.profile(), handler.comments.toList())
        } catch (failure: LayoutXmlException) {
            throw failure
        } catch (failure: Exception) {
            findLayoutFailure(failure)?.let { throw it }
            val parseFailure = findParseLocation(failure)
            throw LayoutXmlException(
                safeMessage(failure),
                parseFailure?.lineNumber,
                parseFailure?.columnNumber,
                failure,
            )
        }
    }

    /** Canonical, indented markup. Text fields are escaped but never trimmed or reindented. */
    fun format(source: String, expectedProfileId: String): String =
        parseDocument(source, expectedProfileId).let { serialize(it.profile, it.comments) }

    private fun newSafeReader(handler: ProfileHandler): XMLReader {
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                try {
                    isXIncludeAware = false
                } catch (_: UnsupportedOperationException) {
                    // JAXP factories default to XInclude-disabled; Android Expat does not implement
                    // this optional switch. We never enable it, and the schema rejects namespaces
                    // and unknown elements (including xi:include) before any include can be used.
                }
                try {
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                } catch (_: Exception) {
                    // Some Android SAX providers omit this optional JAXP feature. The enforced
                    // no-DTD preflight, bounded source, depth/button limits, and strict schema
                    // below prevent entity expansion and bound all parser-controlled input.
                }
            }
            val reader = factory.newSAXParser().xmlReader
            // Enforce the switches when a provider supports them. The preflight rejects every DTD
            // declaration before parser construction, so unsupported entity-feature switches do
            // not permit external access or expansion on minimal Android SAX implementations.
            listOf(
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            ).forEach { feature ->
                try {
                    reader.setFeature(feature, false)
                } catch (_: Exception) {
                    // No DTDs are admitted; the resolver below also rejects any external request.
                }
            }
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ ->
                throw SAXException("External XML entities are not supported.")
            }
            reader.errorHandler = object : DefaultHandler() {
                override fun error(exception: SAXParseException) = throw exception
                override fun fatalError(exception: SAXParseException) = throw exception
            }
            reader.contentHandler = handler
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            return reader
        } catch (failure: SAXNotRecognizedException) {
            throw LayoutXmlException("This platform XML parser cannot safely disable external XML features.", cause = failure)
        } catch (failure: SAXNotSupportedException) {
            throw LayoutXmlException("This platform XML parser cannot safely disable external XML features.", cause = failure)
        } catch (failure: Exception) {
            throw LayoutXmlException("A safe XML parser is unavailable; source was not applied.", cause = failure)
        }
    }

    private class ProfileHandler(private val expectedProfileId: String) : DefaultHandler(), LexicalHandler {
        private var locator: org.xml.sax.Locator? = null
        private val elements = ArrayDeque<String>()
        private val buttons = mutableListOf<LayoutButton>()
        val comments = mutableListOf<XmlComment>()
        private var rootSeen = false
        private var rootClosed = false
        private var profileId: String? = null
        private var profileName: String? = null
        private var columns: Int? = null
        private var buttonId: String? = null
        private var buttonWidth: Int? = null
        private var buttonColor: String? = null
        private var buttonTitle: String? = null
        private var buttonCommand: String? = null
        private var textField: String? = null
        private var buttonChildSlot = 0
        private val text = StringBuilder()

        override fun setDocumentLocator(locator: org.xml.sax.Locator?) { this.locator = locator }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            val depth = elements.size + 1
            if (depth > MAX_DEPTH) fail("XML nesting is too deep.")
            if (uri.orEmpty().isNotEmpty() || qName.contains(':')) fail("XML namespaces are not supported.")
            when (depth) {
                1 -> {
                    if (rootSeen || qName != "layout") fail("Expected one <layout> root element.")
                    requireAttributes(attributes, setOf("version", "id", "name", "columns"))
                    if (attributes.getValue("version") != VERSION) fail("Only layout XML version $VERSION is supported.")
                    profileId = requiredAttribute(attributes, "id")
                    if (profileId != expectedProfileId) fail("This XML belongs to a different layout ID.")
                    profileName = requiredAttribute(attributes, "name")
                    columns = requiredAttribute(attributes, "columns").toIntOrNull()
                    if (columns != LAYOUT_COLUMN_COUNT) fail("Layout columns must be exactly $LAYOUT_COLUMN_COUNT.")
                    rootSeen = true
                }
                2 -> {
                    if (elements.lastOrNull() != "layout" || qName != "button") fail("Expected <button> inside <layout>.")
                    if (buttons.size >= MAX_BUTTONS) fail("A layout can contain at most $MAX_BUTTONS buttons.")
                    requireAttributes(attributes, setOf("id", "width", "color"))
                    buttonId = requiredAttribute(attributes, "id")
                    buttonWidth = requiredAttribute(attributes, "width").toIntOrNull()
                    if (buttonWidth !in 1..LAYOUT_COLUMN_COUNT) fail("Button width must be between 1 and $LAYOUT_COLUMN_COUNT.")
                    buttonColor = requiredAttribute(attributes, "color")
                    if (!COLOR_PATTERN.matches(buttonColor.orEmpty())) fail("Button color must be #RRGGBB or #AARRGGBB.")
                    buttonTitle = null
                    buttonCommand = null
                    buttonChildSlot = 0
                }
                3 -> {
                    if (elements.lastOrNull() != "button" || qName !in setOf("title", "command")) {
                        fail("Only <title> and <command> are supported inside <button>.")
                    }
                    requireAttributes(attributes, emptySet())
                    if (qName == "title" && buttonTitle != null) fail("Each button must have exactly one <title>.")
                    if (qName == "command" && buttonCommand != null) fail("Each button must have exactly one <command>.")
                    textField = qName
                    text.setLength(0)
                }
                else -> fail("Unsupported XML element <$qName>.")
            }
            elements.addLast(qName)
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (length == 0) return
            if (textField != null) {
                text.append(chars, start, length)
            } else if ((0 until length).any { !chars[start + it].isXmlWhitespace() }) {
                fail("Only whitespace is allowed between layout elements.")
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (elements.size) {
                3 -> when (qName) {
                    "title" -> {
                        buttonTitle = checkedXml10Value(text.toString(), "Title")
                        textField = null
                        buttonChildSlot = 1
                    }
                    "command" -> {
                        buttonCommand = checkedXml10Value(text.toString(), "Command")
                        textField = null
                        buttonChildSlot = 2
                    }
                    else -> fail("Unsupported XML element <$qName>.")
                }
                2 -> {
                    val title = buttonTitle ?: fail("Each button requires one <title>.")
                    val command = buttonCommand ?: fail("Each button requires one <command>.")
                    try {
                        buttons += LayoutButton(
                            id = buttonId ?: fail("Button ID is required."),
                            title = title,
                            command = command,
                            width = buttonWidth ?: fail("Button width is required."),
                            color = buttonColor ?: fail("Button color is required."),
                        )
                    } catch (failure: IllegalArgumentException) {
                        fail(failure.message ?: "Invalid button.")
                    }
                    buttonId = null
                    buttonWidth = null
                    buttonColor = null
                    buttonTitle = null
                    buttonCommand = null
                }
                1 -> rootClosed = true
            }
            elements.removeLast()
        }

        override fun processingInstruction(target: String?, data: String?) = fail("Processing instructions are not supported.")
        override fun comment(ch: CharArray?, start: Int, length: Int) {
            if (textField != null) fail("Comments cannot split title or command text.")
            val value = String(ch ?: return, start, length)
            when (elements.lastOrNull()) {
                "layout" -> comments += XmlComment(buttonIndex = null, slot = buttons.size, text = value)
                "button" -> comments += XmlComment(buttonIndex = buttons.size, slot = buttonChildSlot, text = value)
                else -> fail("XML comments are supported only inside layout and button elements.")
            }
        }
        override fun startDTD(name: String?, publicId: String?, systemId: String?) = fail("DTD declarations are not supported.")
        override fun startEntity(name: String?) {
            if (name !in setOf("[dtd]", "amp", "lt", "gt", "apos", "quot")) {
                fail("Entity references other than XML's built-in and numeric references are not supported.")
            }
        }
        override fun startCDATA() = Unit
        override fun endDTD() = Unit
        override fun endEntity(name: String?) = Unit
        override fun endCDATA() = Unit

        fun profile(): LayoutProfile {
            if (!rootSeen || !rootClosed || elements.isNotEmpty()) fail("A complete <layout> root element is required.")
            return try {
                LayoutProfile(
                    id = profileId ?: fail("Layout ID is required."),
                    name = profileName ?: fail("Layout name is required."),
                    columnCount = columns ?: fail("Layout columns are required."),
                    buttons = buttons.toList(),
                )
            } catch (failure: IllegalArgumentException) {
                fail(failure.message ?: "Invalid layout profile.")
            }
        }

        private fun requiredAttribute(attributes: Attributes, name: String): String =
            checkedXml10Value(
                attributes.getValue(name) ?: fail("Attribute '$name' is required."),
                "Attribute '$name'",
            )

        private fun checkedXml10Value(value: String, field: String): String {
            if (LayoutProfileXml.firstInvalidXmlCharacter(value) != null) {
                val current = locator
                throw SAXException(LayoutXmlException(
                    "$field contains a character that XML 1.0 cannot represent.",
                    current?.lineNumber,
                    current?.columnNumber,
                ))
            }
            return value
        }

        private fun requireAttributes(attributes: Attributes, required: Set<String>) {
            val actual = (0 until attributes.length).mapTo(mutableSetOf()) { attributes.getQName(it) }
            if (actual != required) {
                val unknown = actual - required
                val missing = required - actual
                when {
                    unknown.isNotEmpty() -> fail("Unsupported attribute '${unknown.first()}'.")
                    missing.isNotEmpty() -> fail("Attribute '${missing.first()}' is required.")
                    else -> fail("Duplicate XML attributes are not supported.")
                }
            }
        }

        private fun fail(message: String): Nothing {
            val current = locator
            throw SAXException(LayoutXmlException(message, current?.lineNumber, current?.columnNumber))
        }

        private fun Char.isXmlWhitespace() = this == ' ' || this == '\t' || this == '\n' || this == '\r'
    }

    private fun validateXmlString(value: String, field: String) {
        val invalid = firstInvalidXmlCharacter(value)
        if (invalid != null) throw LayoutXmlException("$field contains a character that XML 1.0 cannot represent.")
    }

    private fun firstInvalidXmlCharacter(value: String): Int? {
        var index = 0
        while (index < value.length) {
            val first = value[index]
            val codePoint: Int
            val width: Int
            when {
                Character.isHighSurrogate(first) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return index
                    codePoint = Character.toCodePoint(first, value[index + 1])
                    width = 2
                }
                Character.isLowSurrogate(first) -> return index
                else -> { codePoint = first.code; width = 1 }
            }
            val valid = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
                codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF
            if (!valid) return index
            index += width
        }
        return null
    }

    private fun locationAt(source: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var column = 1
        var index = 0
        while (index < offset.coerceAtMost(source.length)) {
            when (source[index]) {
                '\r' -> {
                    line++
                    column = 1
                    if (index + 1 < offset && source[index + 1] == '\n') index++
                }
                '\n' -> { line++; column = 1 }
                else -> column++
            }
            index++
        }
        return line to column
    }

    private fun findLayoutFailure(failure: Throwable): LayoutXmlException? {
        var current: Throwable? = failure
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is LayoutXmlException) return current
            current = when (current) {
                is SAXException -> current.exception ?: current.cause
                else -> current.cause
            }
        }
        return null
    }

    private fun findParseLocation(failure: Throwable): SAXParseException? {
        var current: Throwable? = failure
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is SAXParseException) return current
            current = when (current) {
                is SAXException -> current.exception ?: current.cause
                else -> current.cause
            }
        }
        return null
    }

    private fun safeMessage(failure: Throwable): String = when {
        failure is SAXParseException -> failure.message?.substringBefore('\n') ?: "Malformed XML source."
        failure.message.isNullOrBlank() -> "Malformed or unsupported XML source."
        else -> failure.message!!.substringBefore('\n')
    }

    private val COLOR_PATTERN = Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")

    private data class ParsedLayout(val profile: LayoutProfile, val comments: List<XmlComment>)
    private data class XmlComment(val buttonIndex: Int?, val slot: Int, val text: String)
}
