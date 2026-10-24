package com.stripe1.xmouse

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class XMouseNgThemeResourcesTest {
    @Test
    fun lightAndDarkPalettesMatchTheApprovedSemanticValues() {
        val light = colors("app/src/main/res/values/colors.xml")
        val dark = colors("app/src/main/res/values-night/colors.xml")
        val expectedLight = mapOf(
            "ng_background" to "#F7F8FA",
            "ng_surface" to "#FFFFFF",
            "ng_surface_soft" to "#EEF1F4",
            "ng_text_primary" to "#20272E",
            "ng_text_secondary" to "#626D78",
            "ng_border" to "#DCE2E7",
            "ng_accent" to "#216A58",
            "ng_on_accent" to "#FFFFFF",
            "ng_accent_soft" to "#E0EFE8",
            "ng_danger" to "#A64041",
            "ng_danger_soft" to "#FBE9E7",
            "ng_action_blue_soft" to "#E8EFFA",
            "ng_on_action_blue" to "#345F98",
        )
        val expectedDark = mapOf(
            "ng_background" to "#15191F",
            "ng_surface" to "#1F252D",
            "ng_surface_soft" to "#272E37",
            "ng_text_primary" to "#EDF1F5",
            "ng_text_secondary" to "#A5B0BC",
            "ng_border" to "#353D47",
            "ng_accent" to "#9CD9C4",
            "ng_on_accent" to "#10372C",
            "ng_accent_soft" to "#263F37",
            "ng_danger" to "#FFB2AD",
            "ng_danger_soft" to "#462B2C",
            "ng_action_blue_soft" to "#273548",
            "ng_on_action_blue" to "#B2CEFA",
        )

        assertEquals(expectedLight, expectedLight.mapValues { light[it.key] })
        assertEquals(expectedDark, expectedDark.mapValues { dark[it.key] })
        assertEquals(expectedLight.keys, expectedDark.keys)
        assertTrue(light.keys.containsAll(expectedLight.keys))
        assertTrue(dark.keys.containsAll(expectedDark.keys))
    }

    @Test
    fun nativeThemeFollowsSystemAndKeepsApplicationIdentityAndApiFloor() {
        val styles = document("app/src/main/res/values/styles.xml")
        val appTheme = styles.getElementsByTagName("style").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .first { it.getAttribute("name") == "AppTheme" }
        }
        assertEquals("Theme.MaterialComponents.DayNight.DarkActionBar", appTheme.getAttribute("parent"))

        val lightStatusBar = styleItems(appTheme)
        assertEquals("true", lightStatusBar["android:windowLightStatusBar"])
        val v27Styles = document("app/src/main/res/values-v27/styles.xml")
        val v27Theme = (0 until v27Styles.getElementsByTagName("style").length)
            .map { v27Styles.getElementsByTagName("style").item(it) as Element }
            .first { it.getAttribute("name") == "AppTheme" }
        assertEquals("true", styleItems(v27Theme)["android:windowLightNavigationBar"])

        val nightStyles = document("app/src/main/res/values-night/styles.xml")
        val nightTheme = (0 until nightStyles.getElementsByTagName("style").length)
            .map { nightStyles.getElementsByTagName("style").item(it) as Element }
            .first { it.getAttribute("name") == "AppTheme" }
        val nightItems = styleItems(nightTheme)
        assertEquals("false", nightItems["android:windowLightStatusBar"])
        val nightV27 = document("app/src/main/res/values-night-v27/styles.xml")
        val nightV27Theme = (0 until nightV27.getElementsByTagName("style").length)
            .map { nightV27.getElementsByTagName("style").item(it) as Element }
            .first { it.getAttribute("name") == "AppTheme" }
        assertEquals("false", styleItems(nightV27Theme)["android:windowLightNavigationBar"])

        val build = source("app/build.gradle")
        assertTrue(build.contains("namespace = 'com.stripe1.xmouse'"))
        assertTrue(build.contains("applicationId \"com.stripe1.xmouse\""))
        assertTrue(build.contains("minSdk = 26"))

        val manifest = document("app/src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals(".XMouseApplication", application.getAttribute("android:name"))
        assertEquals("@string/app_name", application.getAttribute("android:label"))

        val strings = document("app/src/main/res/values/strings.xml")
        assertEquals("XMouse-NG", namedText(strings, "app_name"))
        assertTrue(namedText(strings, "app_desc").contains("XMouse-NG"))
        listOf("session_notification_channel_description", "session_notification_title", "session_notification_text")
            .forEach { assertTrue("$it must use the visible brand", namedText(strings, it).contains("XMouse-NG")) }

        // The legacy v21 style file must remain valid XML so all app themes can be built.
        document("app/src/main/res/values-v21/styles.xml")
        document("app/src/main/res/values-v27/styles.xml")
        document("app/src/main/res/values-v29/styles.xml")
    }

    @Test
    fun existingComposeEditorsBindToTheResourceBackedSystemTheme() {
        val theme = source("app/src/main/java/com/stripe1/xmouse/ui/XMouseNgTheme.kt")
        assertTrue(theme.contains("isSystemInDarkTheme()"))
        assertTrue(theme.contains("colorResource(R.color.ng_background)"))
        listOf(
            "ng_surface", "ng_surface_soft", "ng_text_primary", "ng_text_secondary", "ng_border",
            "ng_accent", "ng_on_accent", "ng_accent_soft", "ng_danger", "ng_danger_soft",
            "ng_action_blue_soft", "ng_on_action_blue",
        ).forEach { assertTrue("Compose theme is missing $it", theme.contains("R.color.$it")) }

        listOf(
            "app/src/main/java/com/stripe1/xmouse/layout/LayoutEditorActivity.kt",
            "app/src/main/java/com/stripe1/xmouse/layout/LayoutProfileManagerActivity.kt",
        ).forEach { activity ->
            assertTrue("$activity must use XMouseNgTheme", source(activity).contains("XMouseNgTheme {"))
        }
    }

    private fun colors(path: String): Map<String, String> {
        val nodes = document(path).getElementsByTagName("color")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.trim()
        }
    }

    private fun styleItems(style: Element): Map<String, String> {
        val nodes = style.getElementsByTagName("item")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.trim()
        }
    }

    private fun namedText(document: org.w3c.dom.Document, name: String): String {
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.getAttribute("name") == name }
            .textContent
            .trim()
    }

    private fun document(path: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(File(projectRoot(), path))
    }

    private fun source(path: String): String = File(projectRoot(), path).readText()

    private fun projectRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/res/values/colors.xml").isFile }
}
