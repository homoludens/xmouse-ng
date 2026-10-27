package com.stripe1.xmouse;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.mockito.InOrder;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DatabaseHandlerResourceTest {
    private interface Read { Object run(DatabaseHandler helper); }
    private static final List<Read> READS = Arrays.asList(
            helper -> helper.getLastRow("scripts", new String[]{"Alias"}),
            helper -> helper.listAll("scripts", new String[]{"Alias"}),
            helper -> helper.listWithTimeRange("scripts", new String[]{"Alias"}, "start", "end"),
            helper -> helper.getRowWithId("scripts", new String[]{"Alias"}, "1"),
            helper -> helper.getTableCount("scripts"));

    private static final class Fixture {
        final DatabaseHandler helper = mock(DatabaseHandler.class, CALLS_REAL_METHODS);
        final SQLiteDatabase database = mock(SQLiteDatabase.class);
        final Cursor cursor = mock(Cursor.class);
        Fixture() {
            doReturn(database).when(helper).getReadableDatabase();
            when(database.query(anyString(), any(String[].class), nullable(String.class), nullable(String[].class),
                    nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                    .thenReturn(cursor);
            when(database.rawQuery(anyString(), nullable(String[].class))).thenReturn(cursor);
            when(cursor.moveToFirst()).thenReturn(true);
            when(cursor.getString(0)).thenReturn("saved value");
            when(cursor.getCount()).thenReturn(1);
        }
        void assertClosedInOrder() {
            InOrder order = inOrder(cursor, database);
            order.verify(cursor).close();
            order.verify(database).close();
        }
    }

    @Test public void everyReadClosesItsCursorBeforeTheDatabaseOnSuccessAndEmptyResults() {
        for (Read read : READS) for (boolean empty : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            when(f.cursor.moveToFirst()).thenReturn(!empty);
            when(f.cursor.getCount()).thenReturn(empty ? 0 : 1);
            assertNotNull(read.run(f.helper));
            f.assertClosedInOrder();
        }
    }

    @Test public void everyReadClosesResourcesWhenReadingOrCursorCloseFails() {
        for (Read read : READS) for (boolean closeFails : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            RuntimeException failure = new IllegalStateException("synthetic cursor failure");
            if (closeFails) doThrow(failure).when(f.cursor).close();
            else {
                when(f.cursor.moveToFirst()).thenThrow(failure);
                when(f.cursor.getCount()).thenThrow(failure);
            }
            try { read.run(f.helper); fail("expected the read failure"); }
            catch (RuntimeException actual) { assertSame(failure, actual); }
            f.assertClosedInOrder();
        }
    }

    @Test public void queryCreationFailureStillClosesTheDatabase() {
        for (Read read : READS) {
            Fixture f = new Fixture();
            RuntimeException failure = new IllegalStateException("synthetic query failure");
            when(f.database.query(anyString(), any(String[].class), nullable(String.class), nullable(String[].class),
                    nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                    .thenThrow(failure);
            when(f.database.rawQuery(anyString(), nullable(String[].class))).thenThrow(failure);
            try { read.run(f.helper); fail("expected query failure"); }
            catch (RuntimeException actual) { assertSame(failure, actual); }
            verify(f.database).close();
            verify(f.cursor, never()).close();
        }
    }
}
