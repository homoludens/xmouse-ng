package com.stripe1.xmouse.layout

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Versioned JSON codec for one profile; file and atomic-write ownership belongs to the repository. */
object LayoutProfileJson {
    const val CURRENT_VERSION = 1

    fun encode(profile: LayoutProfile): String {
        val buttons = JSONArray()
        profile.buttons.forEach { button ->
            buttons.put(
                JSONObject()
                    .put("id", button.id)
                    .put("title", button.title)
                    .put("command", button.command)
                    .put("width", button.width)
                    .put("color", button.color),
            )
        }
        return JSONObject()
            .put("version", CURRENT_VERSION)
            .put("id", profile.id)
            .put("name", profile.name)
            .put("columnCount", profile.columnCount)
            .put("buttons", buttons)
            .toString()
    }

    /** Rejects unsupported versions and wrong JSON value types before constructing validated models. */
    fun decode(serialized: String): LayoutProfile {
        val root = try {
            JSONObject(serialized)
        } catch (failure: JSONException) {
            throw IllegalArgumentException("Invalid layout profile JSON", failure)
        }

        val version = root.requiredInt("version")
        require(version == CURRENT_VERSION) { "Unsupported layout profile version: $version" }

        val encodedButtons = root.requiredArray("buttons")
        val buttons = (0 until encodedButtons.length()).map { index ->
            val encodedButton = encodedButtons.opt(index) as? JSONObject
                ?: throw IllegalArgumentException("Invalid button at index $index")
            LayoutButton(
                id = encodedButton.requiredString("id"),
                title = encodedButton.requiredString("title"),
                command = encodedButton.requiredString("command"),
                width = encodedButton.requiredInt("width"),
                color = encodedButton.requiredString("color"),
            )
        }

        return LayoutProfile(
            id = root.requiredString("id"),
            name = root.requiredString("name"),
            columnCount = root.requiredInt("columnCount"),
            buttons = buttons,
        )
    }

    private fun JSONObject.requiredString(key: String): String =
        opt(key) as? String ?: throw IllegalArgumentException("Missing or invalid '$key'")

    private fun JSONObject.requiredInt(key: String): Int {
        val value = opt(key) as? Number ?: throw IllegalArgumentException("Missing or invalid '$key'")
        return value.toString().toIntOrNull()
            ?: throw IllegalArgumentException("Missing or invalid '$key'")
    }

    private fun JSONObject.requiredArray(key: String): JSONArray =
        opt(key) as? JSONArray ?: throw IllegalArgumentException("Missing or invalid '$key'")
}
