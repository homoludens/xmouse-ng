package net.droopia.xmouse_ng.layout

import android.content.Context
import net.droopia.xmouse_ng.DatabaseHandler

/** Read-only Actions adapter over the existing SQLite script rows. CRUD remains in ListEditActivity. */
class SavedActionScriptRepository(context: Context) {
    private val applicationContext = context.applicationContext

    fun listScripts(): List<SavedActionScript> = DatabaseHandler(applicationContext).use { database ->
        database.listAll(
            DatabaseHandler.SCRIPT_TABLE_NAME,
            arrayOf("Alias", "Script", "id"),
        ).mapNotNull { row ->
            val id = row.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val name = row.getOrNull(0)?.takeIf(String::isNotBlank) ?: "Unnamed script"
            SavedActionScript(id, name)
        }
    }

    /** Returns the stored command only to the existing exec route; it is never a tile preview. */
    fun loadCommand(id: Int): String? {
        if (id <= 0) return null
        return DatabaseHandler(applicationContext).use { database ->
            database.getRowWithId(
                DatabaseHandler.SCRIPT_TABLE_NAME,
                arrayOf("Alias", "Script", "id"),
                id.toString(),
            ).firstOrNull()?.getOrNull(1)?.takeIf(String::isNotBlank)
        }
    }
}
