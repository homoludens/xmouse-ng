package com.stripe1.xmouse.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlLayoutLexerTest {
    @Test fun highlightsElementsAttributesQuotedValuesCommentsTextAndIncompleteInput() {
        val source = "text <layout name=\"value\"> <!-- note -->\n <button name=\"unfinished"
        val tokens = XmlLayoutLexer.tokens(source)
        assertEquals(source, tokens.joinToString("") { source.substring(it.start, it.end) })
        assertTrue(tokens.any { it.kind == XmlLayoutToken.Kind.TEXT })
        assertTrue(tokens.any { it.kind == XmlLayoutToken.Kind.ELEMENT })
        assertTrue(tokens.any { it.kind == XmlLayoutToken.Kind.ATTRIBUTE })
        assertTrue(tokens.any { it.kind == XmlLayoutToken.Kind.QUOTED_VALUE })
        assertTrue(tokens.any { it.kind == XmlLayoutToken.Kind.COMMENT })
        assertTrue(tokens.all { it.start >= 0 && it.end <= source.length && it.start < it.end })
    }

    @Test fun tokenizationIsBoundedForVeryManyShortTags() {
        val source = "<x/>".repeat(XmlLayoutLexer.MAX_TOKENS)
        val tokens = XmlLayoutLexer.tokens(source)
        assertTrue(tokens.size <= XmlLayoutLexer.MAX_TOKENS)
        assertEquals(source.length, tokens.last().end)
    }

    @Test fun presentationColorsAllXmlTokenKindsWithLightAndDarkPalettesWithoutChangingText() {
        val source = "text <layout name=\"value\"> <!-- note -->"
        val original = AnnotatedString(source)
        val light = XmlSourceVisualTransformation(dark = false).filter(original)
        val dark = XmlSourceVisualTransformation(dark = true).filter(original)
        assertEquals(source, light.text.text)
        assertEquals(source, dark.text.text)
        assertEquals(androidx.compose.ui.text.input.OffsetMapping.Identity, light.offsetMapping)
        assertEquals(androidx.compose.ui.text.input.OffsetMapping.Identity, dark.offsetMapping)

        listOf(
            XmlLayoutToken.Kind.TEXT,
            XmlLayoutToken.Kind.MARKUP,
            XmlLayoutToken.Kind.ELEMENT,
            XmlLayoutToken.Kind.ATTRIBUTE,
            XmlLayoutToken.Kind.QUOTED_VALUE,
            XmlLayoutToken.Kind.COMMENT,
        ).forEach { kind ->
            val token = XmlLayoutLexer.tokens(source).first { it.kind == kind }
            val lightColor = light.text.spanStyles.firstOrNull { it.start == token.start && it.end == token.end }?.item?.color
            val darkColor = dark.text.spanStyles.firstOrNull { it.start == token.start && it.end == token.end }?.item?.color
            assertNotNull("$kind must receive a presentation color", lightColor)
            assertNotNull("$kind must receive a presentation color", darkColor)
            assertNotEquals("$kind must use a contrast-adapted palette", lightColor, darkColor)
            assertNotEquals(Color.Unspecified, lightColor)
            assertNotEquals(Color.Unspecified, darkColor)
        }
    }

    @Test fun paletteUsesDistinctAccessibleTokenColorsInLightAndDarkThemes() {
        val source = "text <layout name=\"value\"> <!-- note -->"
        val kinds = XmlLayoutToken.Kind.entries
        val light = XmlSourceVisualTransformation(dark = false).filter(AnnotatedString(source)).text
        val dark = XmlSourceVisualTransformation(dark = true).filter(AnnotatedString(source)).text

        fun colorByKind(annotated: AnnotatedString): List<Color> = kinds.map { kind ->
            val token = XmlLayoutLexer.tokens(source).first { it.kind == kind }
            annotated.spanStyles.single { it.start == token.start && it.end == token.end }.item.color
        }
        val lightColors = colorByKind(light)
        val darkColors = colorByKind(dark)
        assertEquals("every token type has its own light-theme color", kinds.size, lightColors.toSet().size)
        assertEquals("every token type has its own dark-theme color", kinds.size, darkColors.toSet().size)

        fun contrastRatio(foreground: Color, background: Color): Float {
            val lighter = maxOf(foreground.luminance(), background.luminance())
            val darker = minOf(foreground.luminance(), background.luminance())
            return (lighter + 0.05f) / (darker + 0.05f)
        }
        val lightBackground = Color.White
        val darkBackground = Color(0xFF121212)
        lightColors.forEachIndexed { index, color ->
            assertTrue("${kinds[index]} light contrast is at least 4.5:1", contrastRatio(color, lightBackground) >= 4.5f)
        }
        darkColors.forEachIndexed { index, color ->
            assertTrue("${kinds[index]} dark contrast is at least 4.5:1", contrastRatio(color, darkBackground) >= 4.5f)
        }
    }
}
