package com.stripe1.xmouse.layout

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stripe1.xmouse.DatabaseHandler
import com.stripe1.xmouse.secrets.IsolatedDatabaseContext
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the instrumentation APK's private SQLite file, never the installed app's database. */
@RunWith(AndroidJUnit4::class)
class SavedActionScriptRepositoryAndroidTest {
    @Test fun actionsListsOnlyActualScriptRowsAndReturnsRawTextOnlyForExecLookup() {
        val base: Context = InstrumentationRegistry.getInstrumentation().context
        val directory = File(base.cacheDir, "script-fixture-${UUID.randomUUID()}")
        val testContext = IsolatedDatabaseContext(base, directory)
        try {
        val repository = SavedActionScriptRepository(testContext)
        assertTrue("fresh script storage is empty, never seeded", repository.listScripts().isEmpty())
        val alias = "fixture-script-${UUID.randomUUID()}"
        val rawCommand = "printf 'script output'\nsecond line  "
        val id = DatabaseHandler(testContext).use { database ->
            testContext.assertDatabasePath(database.writableDatabase, TEST_DATABASE_NAME)
            database.addScript(alias, rawCommand).toInt()
        }

        val script = repository.listScripts().single { it.id == id }
        assertEquals(alias, script.name)
        assertFalse("raw SSH script text is not exposed in the Actions presentation model",
            script.toString().contains(rawCommand))
        assertEquals(rawCommand, repository.loadCommand(id))
        assertTrue(repository.listScripts().any { it.id == id && it.name == alias })

        DatabaseHandler(testContext).use {
            assertEquals(1, it.updateRow(DatabaseHandler.SCRIPT_TABLE_NAME,
                arrayOf("Alias", "Script"), arrayOf("Renamed", rawCommand), id.toString()))
        }
        assertEquals("Renamed", SavedActionScriptRepository(testContext).listScripts().single().name)
        assertEquals(rawCommand, SavedActionScriptRepository(testContext).loadCommand(id))

        DatabaseHandler(testContext).use { it.deleteRow(DatabaseHandler.SCRIPT_TABLE_NAME, id) }
        assertTrue(repository.listScripts().none { it.id == id })
        } finally { directory.deleteRecursively() }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "contactsManager"
    }
}
