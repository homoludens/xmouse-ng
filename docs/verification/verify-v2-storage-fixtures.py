#!/usr/bin/env python3
"""Parse and assert the synthetic legacy v2 database and SharedPreferences fixtures."""

import sqlite3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = ROOT / "app/src/androidTest/assets/legacy/v2"
DATABASE_FIXTURE = FIXTURE_DIR / "contactsManager-v2.sql"
PREFERENCES_FIXTURE = FIXTURE_DIR / "com.stripe1.xmouse_preferences.xml"


def read_preferences(path):
    root = ET.parse(path).getroot()
    if root.tag != "map":
        raise ValueError("Expected Android SharedPreferences <map>")

    values = {}
    for entry in root:
        name = entry.attrib["name"]
        if name in values:
            raise ValueError(f"Duplicate preference: {name}")
        if entry.tag == "string":
            values[name] = ("string", entry.text or "")
        elif entry.tag == "boolean":
            raw_value = entry.attrib["value"]
            if raw_value not in ("true", "false"):
                raise ValueError(f"Invalid boolean preference: {name}")
            values[name] = ("boolean", raw_value == "true")
        else:
            raise ValueError(f"Unsupported preference type: {entry.tag}")
    return values


class Version2StorageFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.database = sqlite3.connect(":memory:")
        cls.database.executescript(DATABASE_FIXTURE.read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.database.close()

    def test_contacts_manager_v2_schema(self):
        self.assertEqual(2, self.database.execute("PRAGMA user_version").fetchone()[0])
        tables = {
            row[0]
            for row in self.database.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            )
        }
        self.assertEqual({"contacts", "scripts"}, tables)

        self.assertEqual(
            [("id", "INTEGER", 1), ("time", "DATETIME", 0),
             ("Alias", "TEXT", 0), ("Host", "TEXT", 0),
             ("Username", "TEXT", 0), ("Port", "TEXT", 0),
             ("Password", "TEXT", 0)],
            [(row[1], row[2], row[5]) for row in self.database.execute("PRAGMA table_info(contacts)")],
        )
        self.assertEqual(
            [("id", "INTEGER", 1), ("time", "DATETIME", 0),
             ("Alias", "TEXT", 0), ("Script", "TEXT", 0)],
            [(row[1], row[2], row[5]) for row in self.database.execute("PRAGMA table_info(scripts)")],
        )

    def test_host_ids_aliases_endpoints_and_synthetic_passwords(self):
        hosts = self.database.execute(
            "SELECT id, time, Alias, Host, Username, Port, Password "
            "FROM contacts ORDER BY id"
        ).fetchall()
        self.assertEqual(
            [
                (7, "2024-05-01 08:00:00", "Fixture desk A", "desk-a.fixture.invalid",
                 "fixture-user-a", "22", "SYNTHETIC_FIXTURE_PASSWORD_FOR_HOST_7_NOT_REAL"),
                (42, "2024-05-02 09:30:00", "Fixture lab B", "lab-b.fixture.invalid",
                 "fixture-user-b", "2222", "SYNTHETIC_FIXTURE_PASSWORD_FOR_HOST_42_NOT_REAL"),
            ],
            hosts,
        )
        self.assertTrue(all("SYNTHETIC_FIXTURE_" in host[6] and host[6].endswith("_NOT_REAL") for host in hosts))

    def test_script_ids_aliases_and_harmless_command_text(self):
        scripts = self.database.execute(
            "SELECT id, time, Alias, Script FROM scripts ORDER BY id"
        ).fetchall()
        self.assertEqual(
            [
                (13, "2024-05-03 10:00:00", "Fixture status", "printf '%s\\n' 'fixture-only: status'"),
                (29, "2024-05-04 11:15:00", "Fixture message", "printf '%s\\n' 'fixture-only: hello'"),
            ],
            scripts,
        )

    def test_selected_host_and_global_key_auth_preferences(self):
        preferences = read_preferences(PREFERENCES_FIXTURE)
        self.assertEqual(
            {
                "hostPreferenceList": ("string", "42"),
                "pref_usekeyauth": ("boolean", True),
                "pref_addkeybutton": ("string", "/fixture-only/NOT_A_REAL_PRIVATE_KEY"),
                "pref_key_passphrase": ("string", "SYNTHETIC_FIXTURE_PASSPHRASE_NOT_REAL"),
            },
            preferences,
        )

        selected_host = self.database.execute(
            "SELECT id, Alias, Host, Username, Port, Password FROM contacts WHERE id = ?",
            (preferences["hostPreferenceList"][1],),
        ).fetchone()
        self.assertEqual(
            (42, "Fixture lab B", "lab-b.fixture.invalid", "fixture-user-b", "2222",
             "SYNTHETIC_FIXTURE_PASSWORD_FOR_HOST_42_NOT_REAL"),
            selected_host,
        )
        self.assertIn("SYNTHETIC_FIXTURE_", preferences["pref_key_passphrase"][1])
        self.assertTrue(preferences["pref_key_passphrase"][1].endswith("_NOT_REAL"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
