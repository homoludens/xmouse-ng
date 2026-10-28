package net.droopia.xmouse_ng.secrets;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Arrays;

/** Test-only administration of GENERATED SYNTHETIC bytes. Never installed in the production APK.
 * Separate from the real MANAGE_DOCUMENTS-protected provider: shell lacks that signature permission.
 * Grants/revokes individual URIs as the picker would, without broad storage access.
 */
public class SyntheticKeyFixtureControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String id, Bundle extras) {
        Uri uri = SyntheticKeyDocumentsProvider.uri(id);
        if (method.equals("fixture.put")) {
            byte[] bytes = extras.getBoolean("oversize") ? new byte[1024 * 1024 + 1] : extras.getByteArray("bytes");
            SyntheticKeyDocumentsProvider.DOCUMENTS.put(id, bytes);
            getContext().grantUriPermission("net.droopia.xmouse_ng", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (method.equals("fixture.remove")) {
            byte[] bytes = SyntheticKeyDocumentsProvider.DOCUMENTS.remove(id);
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            getContext().revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else throw new UnsupportedOperationException();
        return new Bundle();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
