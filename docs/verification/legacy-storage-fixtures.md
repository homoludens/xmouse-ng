# Legacy storage fixtures

`app/src/androidTest/assets/legacy/v2/` contains a synthetic `contactsManager`
version-2 SQLite fixture and the matching default SharedPreferences XML. The SQL
uses the original `DatabaseHandler.onCreate` table/column names and types plus
the host fields assembled by `MainActivity.initDb`; the preference keys and
types match `MainActivity.getPreferences`, `SettingsActivity`, and
`res/xml/pref_auth.xml`. The selected host is stored as the string ID `42`.

Every password/passphrase is explicitly synthetic and marked `NOT_REAL`. The
private-key preference is only a fixture path, no key file or key content is
included, and script rows contain harmless `printf` commands.

Run the standard-library parser/assertions from the repository root:

```sh
python3 docs/verification/verify-v2-storage-fixtures.py
```

The SQL asset can be loaded into a temporary SQLite database with `executescript`
for later migration tests; the XML asset can seed the matching preference keys
and Android value types without reading any device/user storage.
