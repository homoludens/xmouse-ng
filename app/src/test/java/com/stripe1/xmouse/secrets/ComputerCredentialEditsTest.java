package com.stripe1.xmouse.secrets;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import com.stripe1.xmouse.DatabaseHandler;
import com.stripe1.xmouse.connection.Endpoint;
import java.util.Collections;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

/** Production repository with recording Android seams: no vault, files, or selected-host mutation. */
public class ComputerCredentialEditsTest {
    @Test public void metadataEditPreservesReferenceAndNeverReadsOrWritesPasswordOrPreferences() throws Exception {
        DatabaseHandler helper = mock(DatabaseHandler.class);
        SQLiteDatabase db = mock(SQLiteDatabase.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        SecretStore secrets = mock(SecretStore.class);
        when(helper.getReadableDatabase()).thenReturn(db);
        when(helper.getWritableDatabase()).thenReturn(db);
        when(preferences.getStringSet(anyString(), anySet())).thenReturn(Collections.emptySet());
        when(db.rawQuery(anyString(), isNull())).thenAnswer(ignored -> {
            Cursor cursor = mock(Cursor.class);
            when(cursor.moveToNext()).thenReturn(true, false);
            when(cursor.getLong(0)).thenReturn(7L);
            when(cursor.getString(1)).thenReturn("Original"); when(cursor.getString(2)).thenReturn("old.invalid");
            when(cursor.getString(3)).thenReturn("old-user"); when(cursor.getString(4)).thenReturn("22");
            when(cursor.getString(5)).thenReturn("opaque_existing_password");
            return cursor;
        });
        when(db.update(eq("contacts"), any(ContentValues.class), anyString(), any(String[].class))).thenReturn(1);
        CredentialRepository repository = new CredentialRepository(helper, preferences, secrets);
        CredentialHost expected = new CredentialHost(7, "Original", "old.invalid", "old-user", "22",
                "opaque_existing_password", false, false, null);
        try (MockedStatic<Looper> loopers = mockStatic(Looper.class);
             MockedConstruction<ContentValues> values = mockConstruction(ContentValues.class)) {
            loopers.when(Looper::getMainLooper).thenReturn(mock(Looper.class));
            assertEquals(7L, repository.updateHostMetadata(expected, "Renamed", Endpoint.manual("new.invalid", 2222), "new-user"));
            ContentValues edit = values.constructed().get(0);
            verify(edit).put("Alias", "Renamed"); verify(edit).put("Host", "new.invalid");
            verify(edit).put("Username", "new-user"); verify(edit).put("Port", "2222");
            verify(edit, never()).put(eq(DatabaseHandler.PASSWORD_CREDENTIAL_REF), anyString());
            verify(edit, never()).putNull("Password");
            verify(db).update(eq("contacts"), same(edit), eq("id=? AND PasswordCredentialRef IS ?"),
                    aryEq(new String[]{"7", "opaque_existing_password"}));
            verifyNoInteractions(secrets);
            verify(preferences, never()).edit();
        }
    }

    @Test public void invalidUserOrPortCannotStartSecretOrDatabaseWrites() throws Exception {
        DatabaseHandler helper = mock(DatabaseHandler.class);
        SharedPreferences preferences = mock(SharedPreferences.class); SecretStore secrets = mock(SecretStore.class);
        CredentialRepository repository = new CredentialRepository(helper, preferences, secrets);
        for (String user : new String[]{"", " user", "user ", "\tuser"}) {
            assertThrows(IllegalArgumentException.class, () -> repository.saveHost(null, "name", "fixture.invalid", user, "22", new byte[0]));
        }
        for (String port : new String[]{"0", "65536", "not-a-port"}) {
            assertThrows(IllegalArgumentException.class, () -> repository.saveHost(null, "name", "fixture.invalid", "user", port, new byte[0]));
        }
        verifyNoInteractions(helper, preferences, secrets);
    }

    @Test public void probeReplacementIsMemoryOnlyAndMissingGlobalKeyNeverImportsOrResets() {
        DatabaseHandler helper = mock(DatabaseHandler.class);
        SharedPreferences preferences = mock(SharedPreferences.class); SecretStore secrets = mock(SecretStore.class);
        CredentialRepository repository = new CredentialRepository(helper, preferences, secrets);
        CredentialHost candidate = new CredentialHost(7, "Unsaved", "candidate.invalid", "user", "2222",
                "opaque_existing_password", false, false, null);
        byte[] entered = "NOT_REAL_unsaved_password".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.concurrent.atomic.AtomicReference<AuthenticationMaterial> material = new java.util.concurrent.atomic.AtomicReference<>();
        try (MockedStatic<Looper> loopers = mockStatic(Looper.class)) {
            loopers.when(Looper::getMainLooper).thenReturn(mock(Looper.class));
            AuthenticationSelection password = new AuthenticationSelection(candidate, false, null, false, false, null, false, null);
            repository.authenticateProbe(password, entered, (kind, request) -> fail("Unexpected prompt"), (result, error) -> material.set(result));
            assertNotNull(material.get()); assertEquals(candidate, material.get().getHost());
            assertArrayEquals(entered, material.get().getPassword()); assertNotSame(entered, material.get().getPassword());
            material.get().close(); material.set(null);
            AuthenticationSelection key = new AuthenticationSelection(candidate, true, null, false, false, null, false, "/NOT_REAL/legacy/key");
            java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>();
            repository.authenticateProbe(key, entered, (kind, request) -> fail("Unexpected prompt"), (result, failure) -> {
                assertNull(result); error.set(failure);
            });
            assertNotNull(error.get()); verifyNoInteractions(helper, preferences, secrets);
        } finally { java.util.Arrays.fill(entered, (byte) 0); }
    }

    @Test public void explicitReplacementUsesNewVerifiedRecordAndFailedReadbackCannotPublishReference() throws Exception {
        DatabaseHandler helper = mock(DatabaseHandler.class); SQLiteDatabase db = mock(SQLiteDatabase.class);
        SharedPreferences preferences = mock(SharedPreferences.class); SecretStore secrets = mock(SecretStore.class);
        when(helper.getWritableDatabase()).thenReturn(db);
        when(db.update(eq("contacts"), any(ContentValues.class), anyString(), any(String[].class))).thenReturn(1);
        when(preferences.getStringSet(anyString(), anySet())).thenReturn(Collections.emptySet());
        CredentialRepository repository = spy(new CredentialRepository(helper, preferences, secrets));
        // Isolate the replacement publication boundary; migration has its own retained fault tests.
        doReturn(true).when(repository).prepare();
        java.lang.reflect.Field writable = CredentialRepository.class.getDeclaredField("isWritable");
        writable.setAccessible(true); writable.set(repository, true);
        SecretId original = new SecretId("opaque_existing_password");
        byte[] originalBytes = "NOT_REAL_original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.Map<SecretId, byte[]> records = new java.util.HashMap<>(); records.put(original, originalBytes.clone());
        java.util.concurrent.atomic.AtomicReference<SecretId> written = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean failReadback = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            SecretId id = call.getArgument(0); byte[] value = call.getArgument(1);
            records.put(id, value.clone()); written.set(id); return null;
        }).when(secrets).write(any(SecretId.class), any(byte[].class));
        when(secrets.read(any(SecretId.class))).thenAnswer(call -> {
            SecretId id = call.getArgument(0);
            return failReadback.get() && id.equals(written.get()) ? null : records.get(id).clone();
        });
        CredentialHost expected = new CredentialHost(7, "Old", "old.invalid", "user", "22", original.getValue(), false, false, null);
        try (MockedStatic<Looper> loopers = mockStatic(Looper.class);
             MockedConstruction<ContentValues> values = mockConstruction(ContentValues.class)) {
            loopers.when(Looper::getMainLooper).thenReturn(mock(Looper.class));
            byte[] replacement = "NOT_REAL_replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(7, repository.saveHost(expected, "Edited", "edited.invalid", "user", "2222", replacement));
            assertNotEquals(original, written.get()); assertArrayEquals(originalBytes, records.get(original));
            assertArrayEquals(replacement, records.get(written.get()));
            verify(values.constructed().get(0)).put(DatabaseHandler.PASSWORD_CREDENTIAL_REF, written.get().getValue());
            clearInvocations(db); failReadback.set(true);
            assertThrows(CredentialRepositoryException.class, () -> repository.saveHost(expected, "Failed", "edited.invalid", "user", "2222", replacement));
            verify(db, never()).update(anyString(), any(ContentValues.class), anyString(), any(String[].class));
            assertArrayEquals(originalBytes, records.get(original)); java.util.Arrays.fill(replacement, (byte) 0);
        } finally { records.values().forEach(value -> java.util.Arrays.fill(value, (byte) 0)); java.util.Arrays.fill(originalBytes, (byte) 0); }
    }
}
