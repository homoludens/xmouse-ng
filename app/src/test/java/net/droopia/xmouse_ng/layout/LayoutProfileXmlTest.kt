package net.droopia.xmouse_ng.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutProfileXmlTest {
    @Test fun roundTripPreservesEveryEditableValueAndOpaqueTextExactly() {
        val profile = LayoutProfile(
            id = "xml-profile",
            name = "  Profile\tname\r\n<&>\"😀  ",
            buttons = (1..10).map { index ->
                LayoutButton(
                    id = "custom$index",
                    title = "  Title $index\t\r\n<&>\"😀  ",
                    command = "  xdotool key alt+F4\r\n\tprintf '%s' \"<&>😀]]>\"  ",
                    width = index,
                    color = if (index % 2 == 0) "#80aBcDeF" else "#aBcDeF",
                )
            },
        )

        val source = LayoutProfileXml.serialize(profile)
        assertTrue(source.contains("&#xD;"))
        assertTrue(source.contains("&gt;"))
        assertTrue(source.contains("&quot;"))
        assertEquals(profile, LayoutProfileXml.parse(source, profile.id))
        assertEquals(source, LayoutProfileXml.format(source, profile.id))
    }

    @Test fun formatKeepsCommentsInTheirElementOrderWithoutIncludingThemInProfileValues() {
        val profile = profile()
        val source = LayoutProfileXml.serialize(profile)
            .replace("  <button", "  <!-- root note -->\n  <button")
            .replace("    <title>", "    <!-- button note -->\n    <title>")
            .replace("    <command>", "    <!-- between fields -->\n    <command>")
        val formatted = LayoutProfileXml.format(source, profile.id)
        assertTrue(formatted.contains("<!-- root note -->"))
        assertTrue(formatted.contains("<!-- button note -->"))
        assertTrue(formatted.contains("<!-- between fields -->"))
        assertEquals(profile, LayoutProfileXml.parse(formatted, profile.id))
    }

    @Test fun cdataIsDecodedAsTextAndFormattedAsEscapedOpaqueContent() {
        val expected = profile().copy(buttons = listOf(profile().buttons.single().copy(command = "raw <&> value")))
        val source = LayoutProfileXml.serialize(profile()).replace(
            "<command>xdotool key alt+F4</command>",
            "<command><![CDATA[raw <&> value]]></command>",
        )
        assertEquals(expected, LayoutProfileXml.parse(source, expected.id))
        val formatted = LayoutProfileXml.format(source, expected.id)
        assertFalse(formatted.contains("<![CDATA["))
        assertEquals(expected, LayoutProfileXml.parse(formatted, expected.id))
    }

    @Test fun schemaRejectsWrongIdentityUnknownFieldsDuplicatesAndInvalidData() {
        val valid = LayoutProfileXml.serialize(profile())
        assertRejected(valid, "other-profile")
        assertRejected(valid.replace("version=\"1\"", "version=\"2\""), "xml-test")
        assertRejected(valid.replace("columns=\"10\"", "columns=\"9\""), "xml-test")
        assertRejected(valid.replace("width=\"2\"", "width=\"0\""), "xml-test")
        assertRejected(valid.replace("color=\"#aBcDeF\"", "color=\"red\""), "xml-test")
        assertRejected(valid.replace("<title>", "<unknown>ignored</unknown><title>"), "xml-test")
        assertRejected(valid.replace("</command>", "</command><command>duplicate</command>"), "xml-test")
        assertRejected(valid.replace("id=\"button\"", "id=\"bad id\""), "xml-test")
        assertRejected(valid.replace("<command>xdotool key alt+F4</command>", "<command>   </command>"), "xml-test")
        assertRejected(valid.replace("id=\"button\"", "id=\"button\" extra=\"value\""), "xml-test")
        assertRejected(valid.replace(" width=\"2\"", ""), "xml-test")
        assertRejected(valid.replace("<title>Open</title>", ""), "xml-test")
        assertRejected(valid.replace("name=\"Test\"", "name=\"   \""), "xml-test")
        assertRejected(valid.replace("width=\"2\"", "width=\"2\" width=\"3\""), "xml-test")
        val duplicateId = "<button id=\"button\" width=\"1\" color=\"#FFFFFF\"><title>Second</title><command>go</command></button>"
        assertRejected(valid.replace("</layout>", "$duplicateId</layout>"), "xml-test")
    }

    @Test fun dtdExternalAndInternalEntityExpansionAreRejectedBeforeResolution() {
        val external = """
            <?xml version="1.0"?>
            <!DOCTYPE layout [<!ENTITY x SYSTEM "urn:xmouse:test-fixture:external-entity">]>
            <layout version="1" id="xml-test" name="Test" columns="10"><button id="button" width="2" color="#aBcDeF"><title>Open</title><command>&x;</command></button></layout>
        """.trimIndent()
        val internal = """
            <!DOCTYPE layout [<!ENTITY x "expanded">]>
            <layout version="1" id="xml-test" name="Test" columns="10"><button id="button" width="2" color="#aBcDeF"><title>Open</title><command>&x;</command></button></layout>
        """.trimIndent()
        val billionLaughs = """
            <!DOCTYPE layout [
              <!ENTITY lol "lol">
              <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <layout version="1" id="xml-test" name="Test" columns="10"><button id="button" width="2" color="#aBcDeF"><title>Open</title><command>&lol3;</command></button></layout>
        """.trimIndent()
        listOf(external, internal, billionLaughs).forEach { source ->
            val failure = runCatching { LayoutProfileXml.parse(source, "xml-test") }.exceptionOrNull()
            assertTrue("Expected safe rejection, got $failure", failure is LayoutXmlException)
            assertTrue(failure!!.message!!.contains("DTD"))
        }
    }

    @Test fun rejectsInvalidXmlScalarsAndBoundsSourceAndButtonCounts() {
        val profile = profile().copy(buttons = listOf(profile().buttons.single().copy(command = "not blank\u0001")))
        assertTrue(runCatching { LayoutProfileXml.serialize(profile) }.exceptionOrNull() is LayoutXmlException)
        val unpaired = profile().copy(buttons = listOf(profile().buttons.single().copy(command = "not blank\uD800")))
        assertTrue(runCatching { LayoutProfileXml.serialize(unpaired) }.exceptionOrNull() is LayoutXmlException)
        val invalidSource = LayoutProfileXml.serialize(profile()).replace("<title>Open</title>", "<title>bad\u0001</title>")
        val invalidSourceFailure = runCatching { LayoutProfileXml.parse(invalidSource, "xml-test") }.exceptionOrNull()
        assertTrue(invalidSourceFailure is LayoutXmlException)
        assertTrue((invalidSourceFailure as LayoutXmlException).line != null)
        assertTrue(runCatching { LayoutProfileXml.parse("x".repeat(LayoutProfileXml.MAX_SOURCE_CHARS + 1), "xml-test") }
            .exceptionOrNull() is LayoutXmlException)
        val hugeEscapedValue = profile().copy(buttons = listOf(profile().buttons.single().copy(command = "&".repeat(60_000))))
        assertTrue(runCatching { LayoutProfileXml.serialize(hugeEscapedValue) }.exceptionOrNull() is LayoutXmlException)
        assertRejected(LayoutProfileXml.serialize(profile()).replace("</layout>",
            (1..LayoutProfileXml.MAX_BUTTONS).joinToString("") {
                "<button id=\"extra$it\" width=\"1\" color=\"#FFFFFF\"><title>Extra</title><command>run</command></button>"
            } + "</layout>"), "xml-test")
    }

    @Test fun acceptsOnlyXml10AndRejectsUnrepresentableDecodedFieldValues() {
        val original = LayoutProfileXml.serialize(profile())
        assertEquals(profile(), LayoutProfileXml.parse(original, "xml-test"))

        val xml11 = original.replaceFirst("<?xml version=\"1.0\"", "<?xml version=\"1.1\"")
        val versionFailure = runCatching { LayoutProfileXml.parse(xml11, "xml-test") }.exceptionOrNull()
        assertTrue("XML 1.1 declarations must be rejected before SAX parsing", versionFailure is LayoutXmlException)
        assertTrue(versionFailure!!.message!!.contains("Only XML 1.0"))
        assertTrue((versionFailure as LayoutXmlException).line != null)
        val bomXml11 = "\uFEFF$xml11"
        val bomVersionFailure = runCatching { LayoutProfileXml.parse(bomXml11, "xml-test") }.exceptionOrNull()
        assertTrue("a leading BOM must not hide an XML 1.1 declaration", bomVersionFailure is LayoutXmlException)
        assertTrue(bomVersionFailure!!.message!!.contains("Only XML 1.0"))

        val invalidTextReference = original.replace(
            "<command>xdotool key alt+F4</command>", "<command>&#x1;</command>",
        )
        assertTrue(runCatching { LayoutProfileXml.parse(invalidTextReference, "xml-test") }
            .exceptionOrNull() is LayoutXmlException)
        val invalidAttributeReference = original.replace("id=\"xml-test\"", "id=\"xml&#x1;-test\"")
        assertTrue(runCatching { LayoutProfileXml.parse(invalidAttributeReference, "xml-test") }
            .exceptionOrNull() is LayoutXmlException)

        val nelProfile = profile().copy(buttons = listOf(
            profile().buttons.single().copy(command = "before\u0085after"),
        ))
        val nelSource = LayoutProfileXml.serialize(nelProfile)
        assertEquals("XML 1.0 must preserve NEL in opaque text", nelProfile,
            LayoutProfileXml.parse(nelSource, nelProfile.id))
    }

    @Test fun parseErrorsCarryLineAndColumnWhereAvailable() {
        val failure = runCatching {
            LayoutProfileXml.parse("<layout version=\"1\" id=\"xml-test\" name=\"Test\" columns=\"10\">\n <button\n", "xml-test")
        }.exceptionOrNull() as LayoutXmlException
        assertTrue(failure.line?.let { it >= 2 } == true)
        assertTrue(failure.column?.let { it > 0 } == true)
        assertTrue(failure.displayMessage().contains("Line"))
    }

    private fun assertRejected(source: String, id: String) {
        assertTrue("Expected rejection", runCatching { LayoutProfileXml.parse(source, id) }
            .exceptionOrNull() is LayoutXmlException)
    }

    private fun profile() = LayoutProfile(
        id = "xml-test",
        name = "Test",
        buttons = listOf(LayoutButton("button", "Open", "xdotool key alt+F4", 2, "#aBcDeF")),
    )
}
