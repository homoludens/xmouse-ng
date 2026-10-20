-- Synthetic version-2 contents for the legacy contactsManager SQLite database.
-- Keep identifiers, table/column names, and declared types aligned with
-- DatabaseHandler.onCreate and MainActivity.initDb as they existed before migration.
PRAGMA user_version = 2;

CREATE TABLE contacts (
    id INTEGER PRIMARY KEY,
    time DATETIME,
    Alias TEXT,
    Host TEXT,
    Username TEXT,
    Port TEXT,
    Password TEXT
);

CREATE TABLE scripts (
    id INTEGER PRIMARY KEY,
    time DATETIME,
    Alias TEXT,
    Script TEXT
);

INSERT INTO contacts (id, time, Alias, Host, Username, Port, Password) VALUES
    (7,  '2024-05-01 08:00:00', 'Fixture desk A', 'desk-a.fixture.invalid', 'fixture-user-a', '22',   'SYNTHETIC_FIXTURE_PASSWORD_FOR_HOST_7_NOT_REAL'),
    (42, '2024-05-02 09:30:00', 'Fixture lab B',  'lab-b.fixture.invalid',  'fixture-user-b', '2222', 'SYNTHETIC_FIXTURE_PASSWORD_FOR_HOST_42_NOT_REAL');

INSERT INTO scripts (id, time, Alias, Script) VALUES
    (13, '2024-05-03 10:00:00', 'Fixture status',  "printf '%s\n' 'fixture-only: status'"),
    (29, '2024-05-04 11:15:00', 'Fixture message', "printf '%s\n' 'fixture-only: hello'");
