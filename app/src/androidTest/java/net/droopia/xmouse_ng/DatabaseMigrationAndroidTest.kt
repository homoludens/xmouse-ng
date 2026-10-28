package net.droopia.xmouse_ng

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.droopia.xmouse_ng.connection.Endpoint
import net.droopia.xmouse_ng.connection.EndpointHost
import net.droopia.xmouse_ng.connection.EndpointLocator
import net.droopia.xmouse_ng.secrets.IsolatedDatabaseContext
import net.droopia.xmouse_ng.secrets.SecretId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual SQLiteOpenHelper/SQLite transactions. Never opens the application's real contactsManager. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationAndroidTest {
    private lateinit var root: File
    private lateinit var context: IsolatedDatabaseContext
    private val databaseName = "contactsManager"
    private val hostFields = listOf("Alias", "Host", "Username", "Port", "Password")
    private val scriptFields = listOf("Alias", "Script")
    private val metadataFields = listOf(
        DatabaseHandler.PASSWORD_CREDENTIAL_REF, DatabaseHandler.LOCATOR_TYPE,
        DatabaseHandler.SERVICE_INSTANCE_NAME, DatabaseHandler.SERVICE_TYPE,
        DatabaseHandler.SERVICE_DOMAIN, DatabaseHandler.STABLE_TARGET_HOSTNAME,
    )
    private val metadataDefaults = listOf(null, DatabaseHandler.LOCATOR_MANUAL, null, null, null, null)

    @Before
    fun setUp() {
        val target = ApplicationProvider.getApplicationContext<Context>()
        root = File(target.noBackupFilesDir, "database-migration-test-${UUID.randomUUID()}")
        context = isolatedContext("upgrade")
    }

    @After
    fun tearDown() {
        if (root.exists()) root.walkBottomUp().forEach { assertTrue(it.delete()) }
    }

    @Test
    fun version2FixtureRetainsEveryValueIdAndUserObjectAndReopensWithoutRepeatingMigration() {
        seed().use { db ->
            // Also preserve unknown additive user columns/tables/indexes/triggers, not just the fixture.
            db.execSQL("ALTER TABLE contacts ADD COLUMN ExtraFixture TEXT DEFAULT 'retained'")
            db.execSQL("CREATE TABLE fixture_notes (id INTEGER PRIMARY KEY, note TEXT)")
            db.execSQL("INSERT INTO fixture_notes VALUES (91, 'synthetic extra data')")
            db.execSQL("CREATE INDEX fixture_alias_index ON contacts(Alias)")
            db.execSQL("CREATE TRIGGER fixture_script_delete AFTER DELETE ON scripts BEGIN SELECT 1; END")
        }
        val before = snapshot()
        assertEquals(listOf("7", "42"), before.tables.getValue("contacts").rows.map { it[0] })
        assertEquals(listOf("13", "29"), before.tables.getValue("scripts").rows.map { it[0] })
        DatabaseHandler(context, declarations(hostFields), declarations(scriptFields)).use { helper ->
            val db = helper.writableDatabase
            context.assertDatabasePath(db, databaseName)
            assertEquals(3, db.version)
            assertLegacyPreserved(before, db)
            val oldHosts = before.tables.getValue("contacts")
            assertEquals(oldHosts.columns + metadataFields, columns(db, "contacts"))
            assertEquals(oldHosts.rows.map { it + metadataDefaults }, rows(db, "contacts"))
            assertEquals("ok", scalar(db, "PRAGMA integrity_check"))
        }
        val migrated = snapshot()
        // Both open modes must leave all values/DDL/version unchanged on later opens.
        DatabaseHandler(context).use { assertEquals(3, it.readableDatabase.version) }
        DatabaseHandler(context).use { assertEquals(3, it.writableDatabase.version) }
        assertEquals(migrated, snapshot())
    }

    @Test
    fun freshSchemaMatchesUpgradeForLegacyEmptyAndSchemaOwnedConstructors() {
        seed().close()
        val upgraded = DatabaseHandler(context).use { schema(it.writableDatabase) }
        val hosts = declarations(hostFields)
        val scripts = declarations(scriptFields)
        DatabaseHandler(isolatedContext("fresh-legacy"), hosts, scripts).use { helper ->
            // Later caller mutation must not change the fixed schema or positional CRUD field list.
            hosts.clear()
            scripts.clear()
            assertEquals(upgraded, schema(helper.writableDatabase))
        }
        DatabaseHandler(isolatedContext("fresh-empty"), arrayListOf(), arrayListOf()).use {
            assertEquals(upgraded, schema(it.writableDatabase))
        }
        DatabaseHandler(isolatedContext("fresh-owned")).use {
            assertEquals(upgraded, schema(it.writableDatabase))
            assertEquals(0, scalar(it.writableDatabase, "SELECT count(*) FROM contacts")!!.toInt())
        }
    }

    @Test
    fun legacyCrudStillWorksAndOmittedMetadataDefaultsToManual() {
        seed().close()
        DatabaseHandler(context, declarations(hostFields), declarations(scriptFields)).use { helper ->
            helper.addRow("contacts", arrayListOf(
                arrayListOf("Alias", "New synthetic host"), arrayListOf("Host", "[fe80::1%eth0]"),
                arrayListOf("Username", "fixture"), arrayListOf("Port", "0022"),
                arrayListOf("Password", "NOT_REAL_new_fixture_password"),
            ))
            val saved = helper.getRowWithId("contacts", arrayOf("id", "Host", "Port", "Password"), "43").single()
            assertEquals(listOf("43", "[fe80::1%eth0]", "0022", "NOT_REAL_new_fixture_password"), saved)
            assertEquals(1, helper.updateRow("contacts", arrayOf("Alias"), arrayOf("Edited fixture"), "43"))
            helper.readableDatabase.rawQuery("SELECT ${metadataFields.joinToString()} FROM contacts WHERE id=43", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(metadataDefaults, (0 until it.columnCount).map(it::getString))
            }
            helper.addRow("scripts", arrayListOf(arrayListOf("Alias", "New script"), arrayListOf("Script", "printf fixture")))
            assertEquals(listOf("30", "New script", "printf fixture"),
                helper.getRowWithId("scripts", arrayOf("id", "Alias", "Script"), "30").single())
            assertEquals(1, helper.deleteRow("scripts", 30))
            assertEquals(1, helper.deleteRow("contacts", 43))
            assertEquals(2, helper.getTableCount("contacts"))
            assertEquals(2, helper.getTableCount("scripts"))
        }
    }

    @Test
    fun serviceMetadataAndOpaqueCredentialReferenceRoundTripWithoutAddressIdentity() {
        seed().close()
        val service = EndpointLocator.DiscoveredService("Fixture desk. SSH", "_ssh._tcp.", "local.")
        val reference = SecretId("host_42_password")
        DatabaseHandler(context).use { helper ->
            val db = helper.writableDatabase
            val values = ContentValues().apply {
                put(DatabaseHandler.PASSWORD_CREDENTIAL_REF, reference.value)
                put(DatabaseHandler.LOCATOR_TYPE, DatabaseHandler.LOCATOR_DISCOVERED_SERVICE)
                put(DatabaseHandler.SERVICE_INSTANCE_NAME, service.instanceName)
                put(DatabaseHandler.SERVICE_TYPE, service.serviceType)
                put(DatabaseHandler.SERVICE_DOMAIN, service.domain)
            }
            assertEquals(1, db.update("contacts", values, "id=?", arrayOf("42")))
        }
        fun readEndpoint(): Endpoint = DatabaseHandler(context).use { helper ->
            helper.readableDatabase.rawQuery("SELECT * FROM contacts WHERE id=42", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                fun value(name: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(name))
                assertEquals(reference, SecretId(value(DatabaseHandler.PASSWORD_CREDENTIAL_REF)!!))
                assertEquals(DatabaseHandler.LOCATOR_DISCOVERED_SERVICE, value(DatabaseHandler.LOCATOR_TYPE))
                assertEquals("SYNTHETIC_FIXTURE_PASSWORD_FOR_HOST_42_NOT_REAL", value("Password"))
                Endpoint(EndpointLocator.DiscoveredService(
                    value(DatabaseHandler.SERVICE_INSTANCE_NAME)!!, value(DatabaseHandler.SERVICE_TYPE)!!,
                    value(DatabaseHandler.SERVICE_DOMAIN)!!,
                    value(DatabaseHandler.STABLE_TARGET_HOSTNAME)?.let(::EndpointHost),
                ), Endpoint.parsePort(value("Port")!!))
            }
        }
        assertEquals(Endpoint(service, 2222), readEndpoint())
        DatabaseHandler(context).use { helper ->
            helper.writableDatabase.execSQL("UPDATE contacts SET StableTargetHostname=? WHERE id=42", arrayOf("Desk.fixture.invalid."))
        }
        assertEquals(Endpoint.manual("desk.fixture.invalid", 2222).identity, readEndpoint().identity)
        // Locator kind is explicit; unknown/null kinds fail instead of silently becoming manual.
        DatabaseHandler(context).use { helper ->
            expectFailure<SQLiteException> { helper.writableDatabase.execSQL("UPDATE contacts SET LocatorType='other'") }
            expectFailure<SQLiteException> { helper.writableDatabase.execSQL("UPDATE contacts SET LocatorType=NULL") }
        }
    }

    @Test
    fun futureVersionOpenRejectsDowngradeInBothModesWithoutChangingAnyTablesOrValues() {
        seed().close()
        DatabaseHandler(context).use { it.writableDatabase }
        raw().use { it.version = 4 }
        val before = snapshot()
        DatabaseHandler(context).use { expectFailure<SQLiteException> { it.writableDatabase } }
        assertEquals(before, snapshot())
        DatabaseHandler(context).use { expectFailure<SQLiteException> { it.readableDatabase } }
        assertEquals(before, snapshot())
    }

    @Test
    fun currentHelperDowngradeCallbackRejectsLegacyTargetInsideRealHelperTransaction() {
        seed().close()
        DatabaseHandler(context).use { it.writableDatabase }
        val before = snapshot()
        val callback = DatabaseHandler(context)
        val oldTarget = object : SQLiteOpenHelper(context, databaseName, null, 2) {
            override fun onCreate(db: SQLiteDatabase) = error("Unexpected creation")
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onDowngrade(db, oldVersion, newVersion)
        }
        callback.use {
            try {
                expectFailure<SQLiteException> { oldTarget.writableDatabase }
            } finally {
                oldTarget.close() // SQLiteOpenHelper itself is not AutoCloseable until API 29.
            }
        }
        assertEquals(before, snapshot())
    }

    @Test
    fun unknownOlderVersionAndUnknownUpgradeTargetAreRejectedWithoutChanges() {
        seed().use { it.version = 1 } // Not a claim that the synthetic v2 layout was a historical v1.
        val before = snapshot()
        DatabaseHandler(context).use { expectFailure<SQLiteException> { it.writableDatabase } }
        assertEquals(before, snapshot())
        raw().use { it.version = 2 }
        val version2 = snapshot()
        DatabaseHandler(context).use { helper ->
            raw().use { db -> expectFailure<SQLiteException> { helper.onUpgrade(db, 2, 4) } }
        }
        assertEquals(version2, snapshot())
    }

    @Test
    fun unversionedExistingTablesAreNotInitializedOrStampedAsCurrent() {
        raw().use {
            it.execSQL("CREATE TABLE fixture_recovery (id INTEGER PRIMARY KEY, note TEXT)")
            it.execSQL("INSERT INTO fixture_recovery VALUES (17, 'retained unknown schema')")
            assertEquals(0, it.version)
        }
        val before = snapshot()
        DatabaseHandler(context).use { expectFailure<SQLiteException> { it.writableDatabase } }
        assertEquals(before, snapshot())
    }

    @Test
    fun incompleteVersion2SchemaIsRejectedWithoutAddingMetadata() {
        raw().use {
            it.execSQL("CREATE TABLE contacts (id INTEGER PRIMARY KEY, time DATETIME)")
            it.execSQL("CREATE TABLE scripts (id INTEGER PRIMARY KEY, time DATETIME)")
            it.execSQL("INSERT INTO contacts VALUES (42, 'synthetic retained timestamp')")
            it.version = 2
        }
        val before = snapshot()
        DatabaseHandler(context).use { expectFailure<SQLiteException> { it.writableDatabase } }
        assertEquals(before, snapshot())
    }

    @Test
    fun failedSqlAfterEarlierAlterStatementsRollsBackAllDdlAndVersionOnEveryRetry() {
        seed().use { it.execSQL("ALTER TABLE contacts ADD COLUMN ServiceType TEXT DEFAULT 'existing collision'") }
        val before = snapshot()
        repeat(2) {
            DatabaseHandler(context).use { expectFailure<SQLiteException> { it.writableDatabase } }
            assertEquals(before, snapshot()) // PasswordCredentialRef/LocatorType/Instance additions rolled back.
        }
    }

    @Test
    fun exceptionAfterAllAdditionsRollsBackHelperTransactionAndNormalRetrySucceeds() {
        seed().close()
        val before = snapshot()
        val failing = object : DatabaseHandler(context) {
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                super.onUpgrade(db, oldVersion, newVersion)
                assertTrue(columns(db, "contacts").containsAll(metadataFields))
                throw SQLiteException("Synthetic interruption before version commit")
            }
        }
        failing.use { expectFailure<SQLiteException> { it.writableDatabase } }
        assertEquals(before, snapshot())
        DatabaseHandler(context).use { assertLegacyPreserved(before, it.writableDatabase) }
        assertEquals(3, snapshot().version)
    }

    @Test
    fun uncommittedMigrationTransactionRestoresVersion2ThenUpgradeCanRestart() {
        seed().close()
        val before = snapshot()
        DatabaseHandler(context).use { helper ->
            raw().use { db ->
                db.beginTransaction()
                helper.onUpgrade(db, 2, 3)
                db.version = 3
                // Model interruption with a transaction lacking setTransactionSuccessful, not power loss.
                db.endTransaction()
            }
        }
        assertEquals(before, snapshot())
        DatabaseHandler(context).use { assertLegacyPreserved(before, it.writableDatabase) }
    }

    @Test
    fun mismatchedCallerFieldDeclarationsFailBeforeDatabaseIsCreated() {
        val invalid = isolatedContext("invalid-fields")
        expectFailure<IllegalArgumentException> {
            DatabaseHandler(invalid, declarations(hostFields + "Unexpected"), declarations(scriptFields))
        }
        expectFailure<IllegalArgumentException> {
            val fields = declarations(hostFields).apply { this[0][1] = "TEXT); DROP TABLE contacts;--" }
            DatabaseHandler(invalid, fields, declarations(scriptFields))
        }
        assertFalse(invalid.getDatabasePath(databaseName).exists())
    }

    private fun isolatedContext(directory: String): IsolatedDatabaseContext {
        val target = ApplicationProvider.getApplicationContext<Context>()
        return IsolatedDatabaseContext(target, File(root, directory))
    }

    private fun seed(): SQLiteDatabase = raw().also { db ->
        val sql = InstrumentationRegistry.getInstrumentation().context.assets
            .open("legacy/v2/contactsManager-v2.sql").bufferedReader().use { it.readText() }
        sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.execSQL(it) }
        assertEquals(2, db.version)
    }

    private fun raw(): SQLiteDatabase = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
    private fun declarations(fields: List<String>): ArrayList<ArrayList<String>> = ArrayList(fields.map { arrayListOf(it, "TEXT") })
    private fun quoted(name: String): String = "\"${name.replace("\"", "\"\"")}\""
    private fun scalar(db: SQLiteDatabase, sql: String): String? = db.rawQuery(sql, null).use { it.moveToFirst(); it.getString(0) }
    private fun columns(db: SQLiteDatabase, table: String): List<String> = query(db, "PRAGMA table_info(${quoted(table)})").map { it[1]!! }
    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> = query(db, "SELECT * FROM ${quoted(table)} ORDER BY rowid")
    private fun query(db: SQLiteDatabase, sql: String): List<List<String?>> = db.rawQuery(sql, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getString)) }
    }

    private fun schema(db: SQLiteDatabase): List<List<List<String?>>> = listOf("contacts", "scripts").map { table ->
        val sql = scalar(db, "SELECT sql FROM sqlite_master WHERE name='$table'")!!.replace(Regex("\\s+"), "")
        query(db, "PRAGMA table_info($table)") + listOf(listOf(sql))
    }

    private fun snapshot(): Snapshot = raw().use { db ->
        val objects = query(db, "SELECT type, name, tbl_name, rootpage, sql FROM sqlite_master ORDER BY type, name")
        val tables = objects.filter { it[0] == "table" }.associate { obj ->
            obj[1]!! to Table(columns(db, obj[1]!!), rows(db, obj[1]!!))
        }
        Snapshot(db.version, objects, tables)
    }

    private fun assertLegacyPreserved(before: Snapshot, db: SQLiteDatabase) {
        before.tables.forEach { (name, old) ->
            assertEquals(old.rows, query(db, "SELECT ${old.columns.joinToString { quoted(it) }} FROM ${quoted(name)} ORDER BY rowid"))
        }
        val objects = query(db, "SELECT type, name, tbl_name, rootpage, sql FROM sqlite_master ORDER BY type, name")
        assertEquals(before.objects.map { it[1] }, objects.map { it[1] })
        before.objects.zip(objects).forEach { (old, new) ->
            if (old[1] == "contacts") assertEquals(old.take(4), new.take(4)) // Same table/rootpage, additive SQL only.
            else assertEquals(old, new)
        }
    }

    private data class Table(val columns: List<String>, val rows: List<List<String?>>)
    private data class Snapshot(val version: Int, val objects: List<List<String?>>, val tables: Map<String, Table>)

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
