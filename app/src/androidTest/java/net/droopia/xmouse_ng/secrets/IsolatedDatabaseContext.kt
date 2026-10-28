package net.droopia.xmouse_ng.secrets

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** ContextWrapper for database fixtures: SQLiteOpenHelper must not delegate opens to the real app context. */
internal open class IsolatedDatabaseContext(
    base: Context,
    private val databaseDirectory: File,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getDatabasePath(name: String): File = File(databaseDirectory, name).also {
        check(it.parentFile!!.isDirectory || it.parentFile!!.mkdirs())
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase = openAndCheck(name) {
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase = openAndCheck(name) {
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }

    /** Verify the helper/restore caller actually opened this fixture's file, not just the expected path. */
    fun assertDatabasePath(database: SQLiteDatabase, name: String) {
        val expected = getDatabasePath(name).canonicalFile
        val actual = File(database.path).canonicalFile
        check(actual == expected) {
            "SQLite opened $actual; expected isolated fixture database $expected"
        }
    }

    private inline fun openAndCheck(name: String, open: () -> SQLiteDatabase): SQLiteDatabase {
        val database = open()
        try {
            assertDatabasePath(database, name)
            return database
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }
}
