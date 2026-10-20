package com.stripe1.xmouse;


import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Looper;

import java.io.Closeable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;

// SQLiteOpenHelper gained AutoCloseable only at API 29; Closeable keeps callers safe on API 24–28.
public class DatabaseHandler extends SQLiteOpenHelper implements Closeable {
 
    // All Static variables
    // Database Version
    public static final int DATABASE_VERSION = 3;
    public SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    // Database Name
    private static final String DATABASE_NAME = "contactsManager";
 
    // Contacts table name
    public static final String HOST_TABLE_NAME = "contacts";
    public static final String SCRIPT_TABLE_NAME = "scripts";
    // V3 host metadata. Host/Port remain the original manual endpoint; addresses and Network
    // handles observed during discovery are never persisted as a service's logical identity.
    public static final String PASSWORD_CREDENTIAL_REF = "PasswordCredentialRef";
    public static final String LOCATOR_TYPE = "LocatorType";
    public static final String LOCATOR_MANUAL = "manual";
    public static final String LOCATOR_DISCOVERED_SERVICE = "discovered_service";
    public static final String SERVICE_INSTANCE_NAME = "ServiceInstanceName";
    public static final String SERVICE_TYPE = "ServiceType";
    public static final String SERVICE_DOMAIN = "ServiceDomain";
    public static final String STABLE_TARGET_HOSTNAME = "StableTargetHostname";
    // Contacts Table Columns names
    private static final String KEY_ID = "id";
    private static final String KEY_TIME = "time";
    private static final String[] LEGACY_HOST_FIELDS = {"Alias", "Host", "Username", "Port", "Password"};
    private static final String[] LEGACY_SCRIPT_FIELDS = {"Alias", "Script"};

    public DatabaseHandler(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        dateFormat.setLenient(false);
    }

    /**
     * Compatibility for MainActivity.initDb's legacy field lists. The helper now owns its schema:
     * empty lists or the original declarations are accepted, never caller-supplied SQL/schema drift.
     * Do not append metadata to MainActivity.hostDBKeys (legacy CRUD uses its positional fields).
     */
    public DatabaseHandler(Context context,ArrayList<ArrayList<String>> hostVars,ArrayList<ArrayList<String>> scriptVars) {
        this(context);
        validateLegacyFields(hostVars, LEGACY_HOST_FIELDS);
        validateLegacyFields(scriptVars, LEGACY_SCRIPT_FIELDS);
    }

    /**
     * Rewrites the database after credential migration has committed, so old row/page contents
     * and SQLite sidecars do not remain eligible for a later file-level backup. Call only from
     * the credential repository worker and never from a transaction.
     *
     * This is logical SQLite compaction, not a promise of secure erasure from flash storage.
     */
    public synchronized void checkpointAndCompactAfterCredentialMigration() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Database compaction requires a background worker");
        }
        SQLiteDatabase db = getWritableDatabase();
        if (db.inTransaction()) {
            throw new SQLiteException("Cannot compact contactsManager during a transaction");
        }
        boolean wal = isWalJournal(db);
        if (wal) checkpointWal(db);
        db.execSQL("VACUUM");
        if (wal) checkpointWal(db);
    }

    private static boolean isWalJournal(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA journal_mode", null)) {
            return cursor.moveToFirst() && "wal".equalsIgnoreCase(cursor.getString(0));
        }
    }

    private static void checkpointWal(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            if (!cursor.moveToFirst() || cursor.getInt(0) != 0) {
                throw new SQLiteException("SQLite WAL checkpoint did not complete");
            }
        }
    }

    private static void validateLegacyFields(ArrayList<ArrayList<String>> fields, String[] expected) {
        if (fields == null) {
            throw new IllegalArgumentException("Database field declarations must not be null");
        }
        if (fields.isEmpty()) return;
        if (fields.size() != expected.length) {
            throw new IllegalArgumentException("Unsupported database field declarations");
        }
        for (int i = 0; i < expected.length; i++) {
            ArrayList<String> field = fields.get(i);
            if (field == null || field.size() != 2 || !expected[i].equals(field.get(0))
                    || !"TEXT".equals(field.get(1))) {
                throw new IllegalArgumentException("Unsupported database field declarations");
            }
        }
    }

    // SQLiteOpenHelper runs creation/version changes, including user_version, in one transaction.
    @Override
    public void onCreate(SQLiteDatabase db) {
        try (Cursor tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*'",
                null)) {
            // Android creates this internal locale table even for a new database.
            while (tables.moveToNext()) {
                if (!"android_metadata".equals(tables.getString(0))) {
                    throw new SQLiteException("Cannot initialize an existing unversioned database");
                }
            }
        }
        createLegacyTable(db, HOST_TABLE_NAME, LEGACY_HOST_FIELDS);
        createLegacyTable(db, SCRIPT_TABLE_NAME, LEGACY_SCRIPT_FIELDS);
        migrate2To3(db);
    }

    private static void createLegacyTable(SQLiteDatabase db, String table, String[] fields) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table)
                .append(" (id INTEGER PRIMARY KEY, time DATETIME");
        for (String field : fields) sql.append(", ").append(field).append(" TEXT");
        db.execSQL(sql.append(")").toString());
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Available history establishes only v2. Never infer older/different schemas or skip steps.
        if (oldVersion != 2 || newVersion != 3) {
            throw new SQLiteException("Unsupported database upgrade " + oldVersion + " -> " + newVersion);
        }
        requireLegacyTable(db, HOST_TABLE_NAME, LEGACY_HOST_FIELDS);
        requireLegacyTable(db, SCRIPT_TABLE_NAME, LEGACY_SCRIPT_FIELDS);
        migrate2To3(db);
    }

    private static void requireLegacyTable(SQLiteDatabase db, String table, String[] fields) {
        // Refuse incomplete/mismatched v2 schemas rather than recording a successful migration.
        ArrayList<String> names = new ArrayList<>();
        try (Cursor columns = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (columns.moveToNext()) {
                String name = columns.getString(1);
                String type = columns.getString(2);
                boolean id = KEY_ID.equals(name) && "INTEGER".equalsIgnoreCase(type) && columns.getInt(5) == 1;
                boolean time = KEY_TIME.equals(name) && "DATETIME".equalsIgnoreCase(type);
                boolean text = false;
                for (String field : fields) {
                    if (field.equals(name) && "TEXT".equalsIgnoreCase(type)) text = true;
                }
                if (id || time || text) names.add(name);
            }
        }
        if (!names.contains(KEY_ID) || !names.contains(KEY_TIME)) {
            throw new SQLiteException("Unsupported version-2 table structure");
        }
        for (String field : fields) {
            if (!names.contains(field)) throw new SQLiteException("Unsupported version-2 table structure");
        }
    }

    private static void migrate2To3(SQLiteDatabase db) {
        // Add only. In particular, Password and all preference credentials stay untouched until
        // task 3.4 has durably stored AND verified their encrypted replacements.
        db.execSQL("ALTER TABLE contacts ADD COLUMN " + PASSWORD_CREDENTIAL_REF + " TEXT");
        db.execSQL("ALTER TABLE contacts ADD COLUMN " + LOCATOR_TYPE + " TEXT NOT NULL DEFAULT '"
                + LOCATOR_MANUAL + "' CHECK (" + LOCATOR_TYPE + " IN ('" + LOCATOR_MANUAL + "', '"
                + LOCATOR_DISCOVERED_SERVICE + "'))");
        db.execSQL("ALTER TABLE contacts ADD COLUMN " + SERVICE_INSTANCE_NAME + " TEXT");
        db.execSQL("ALTER TABLE contacts ADD COLUMN " + SERVICE_TYPE + " TEXT");
        db.execSQL("ALTER TABLE contacts ADD COLUMN " + SERVICE_DOMAIN + " TEXT");
        db.execSQL("ALTER TABLE contacts ADD COLUMN " + STABLE_TARGET_HOSTNAME + " TEXT");
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Unsupported database downgrade " + oldVersion + " -> " + newVersion);
    }
 
    /**
     * ArrayList<ArrayList<String>> items
     * keyboard_button=items.get(i)
     * keyboard_button.get(0)= key
     * keyboard_button.get(1)= value
     */
    public void addRow(String TABLE_NAME,ArrayList<ArrayList<String>> items) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        Calendar date1 = Calendar.getInstance();
        values.put(KEY_TIME,dateFormat.format(date1.getTime())); 
        
        for(int i=0;i<items.size();i++){
        	ArrayList<String> item = items.get(i);
	        values.put(item.get(0), item.get(1)); 
	        
        }
        // Inserting Row
        db.insert(TABLE_NAME, null, values);
        db.close(); // Closing database connection
    }
    
    public String[] getLastRow(String TABLE_NAME,String[] columnKeys) {
    	
        SQLiteDatabase db = this.getReadableDatabase();
       
        Cursor cursor = db.query(	TABLE_NAME, //String table
        							columnKeys, //new String[] { KEY_ID, KEY_NAME, KEY_PH_NO }, //String[] columns
        							null,//KEY_ID + "=?", //String selection
        							null,//new String[] { String.valueOf(id) }, //String[] selectionArgs
        							null, //String groupBy
        							null, //String having
        							KEY_ID + " DESC",//null, //String orderBy
        							"1"//null //String limit
        						);
        
        
        if (cursor.moveToFirst()) {
            do {
            
            	for(int i=0;i<columnKeys.length;i++){
    	        	
    	        	columnKeys[i] = cursor.getString(i);
    	        }
            } while (cursor.moveToNext());
        }else{
        	return new String[columnKeys.length]; //return empty array
        }
        
        db.close();
        
        return columnKeys;
    }
    public ArrayList<ArrayList<String>> listAll(String TABLE_NAME,String[] strings) {
		// TODO Auto-generated method stub
    	SQLiteDatabase db = this.getReadableDatabase();
        
        Cursor cursor = db.query(	TABLE_NAME, //String table
        							strings, //new String[] { KEY_ID, KEY_NAME, KEY_PH_NO }, //String[] columns
        							null,//KEY_TIME + ">=? AND " + KEY_TIME + "<=?", //String selection
        							null,//new String[] { timeLow, timeHigh }, //String[] selectionArgs
        							null, //String groupBy
        							null, //String having
        							KEY_ID + " DESC",//null, //String orderBy
        							null //String limit
        						);
    	
    	ArrayList<ArrayList<String>> results = new ArrayList<ArrayList<String>>();
        // looping through all rows and adding to list
        if (cursor.moveToFirst()) {
            do {
                
            	ArrayList<String> temp = new ArrayList<String>();
            	for(int i=0;i<strings.length;i++){
            		temp.add(cursor.getString(i));
                	
                }
            	
            	results.add(temp);
            	
            	
            } while (cursor.moveToNext());
        }
        db.close();
        // return contact list
        return results;
	}
    // Getting All Contacts
    public ArrayList<ArrayList<String>> listWithTimeRange(String TABLE_NAME,String[] columnKeys,String timeLow,String timeHigh) {
        
    	//List<Contact> contactList = new ArrayList<Contact>();
        // Select All Query
        //String selectQuery = "SELECT  * FROM " + TABLE_NAME;
 
        //SQLiteDatabase db = this.getWritableDatabase();
        //Cursor cursor = db.rawQuery(selectQuery, null);
    	
    	
    	SQLiteDatabase db = this.getReadableDatabase();
        
        Cursor cursor = db.query(	TABLE_NAME, //String table
        							columnKeys, //new String[] { KEY_ID, KEY_NAME, KEY_PH_NO }, //String[] columns
        							KEY_TIME + ">=? AND " + KEY_TIME + "<=?", //String selection
        							new String[] { timeLow, timeHigh }, //String[] selectionArgs
        							null, //String groupBy
        							null, //String having
        							KEY_ID + " DESC",//null, //String orderBy
        							null //String limit
        						);
    	
    	ArrayList<ArrayList<String>> results = new ArrayList<ArrayList<String>>();
        // looping through all rows and adding to list
        if (cursor.moveToFirst()) {
            do {
                
            	ArrayList<String> temp = new ArrayList<String>();
            	for(int i=0;i<columnKeys.length;i++){
            		temp.add(cursor.getString(i));
                	
                }
            	
            	results.add(temp);
            	
            	
            } while (cursor.moveToNext());
        }
        db.close();
        // return contact list
        return results;
    }
 
    // Updating single contact
    public int updateRow(String TABLE_NAME,String[] columnKeys, String[] columnValues,String id) {
        SQLiteDatabase db = this.getWritableDatabase();
 
        ContentValues values = new ContentValues();
        
        for(int i=0;i<columnKeys.length;i++){
        	values.put(columnKeys[i], columnValues[i]);
        }
        // updating row
        return db.update(	TABLE_NAME,
        					values,
        					KEY_ID + " = ?",
        					new String[] { id }
        		);
        
    }
 
    // Deleting single contact
    public int deleteRow(String TABLE_NAME,int id) {
        SQLiteDatabase db = this.getWritableDatabase();
        int rowsAffected = db.delete(	TABLE_NAME,
        			KEY_ID + " = ?",
        			new String[] { String.valueOf(id) });
        db.close();
        return rowsAffected;
    }
 
 
    // Getting contacts Count
    public int getTableCount(String TABLE_NAME) {
        String countQuery = "SELECT  "+ KEY_ID +" FROM " + TABLE_NAME;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(countQuery, null);
        
 
        // return count
        return cursor.getCount();
    }

    public ArrayList<ArrayList<String>> getRowWithId(String TABLE_NAME,String[] columnKeys, String id) {
		SQLiteDatabase db = this.getReadableDatabase();
        
        Cursor cursor = db.query(	TABLE_NAME, //String table
        							columnKeys, //new String[] { KEY_ID, KEY_NAME, KEY_PH_NO }, //String[] columns
        							KEY_ID + "=?", //String selection
        							new String[] { id }, //String[] selectionArgs
        							null, //String groupBy
        							null, //String having
        							KEY_ID + " DESC",//null, //String orderBy
        							null //String limit
        						);
    	
    	ArrayList<ArrayList<String>> results = new ArrayList<ArrayList<String>>();
        // looping through all rows and adding to list
        if (cursor.moveToFirst()) {
            do {
                
            	ArrayList<String> temp = new ArrayList<String>();
            	for(int i=0;i<columnKeys.length;i++){
            		temp.add(cursor.getString(i));
                	
                }
            	
            	results.add(temp);
            	
            	
            } while (cursor.moveToNext());
        }
        db.close();
        // return contact list
        return results;
		
	}
 
}
