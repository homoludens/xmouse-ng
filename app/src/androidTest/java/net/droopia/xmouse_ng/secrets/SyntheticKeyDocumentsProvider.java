package net.droopia.xmouse_ng.secrets;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Test APK only. Pure platform Java because this separate provider process has no target libraries. */
public class SyntheticKeyDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "net.droopia.xmouse_ng.test.synthetic.keys";
    static final ConcurrentHashMap<String, byte[]> DOCUMENTS = new ConcurrentHashMap<>();

    public static Uri uri(String id) { return DocumentsContract.buildDocumentUri(AUTHORITY, id); }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
                DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS };
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID, "synthetic")
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
                .add(DocumentsContract.Root.COLUMN_TITLE, "NOT_REAL synthetic key fixtures")
                .add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY);
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = documentCursor(projection);
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        MatrixCursor cursor = documentCursor(projection);
        for (String id : DOCUMENTS.keySet()) addDocument(cursor, id);
        return cursor;
    }

    private MatrixCursor documentCursor(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE });
    }

    private void addDocument(MatrixCursor cursor, String id) {
        byte[] bytes = DOCUMENTS.get(id);
        cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "NOT_REAL_synthetic_private_key.pem")
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE, id.equals("root")
                        ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream")
                .add(DocumentsContract.Document.COLUMN_SIZE, bytes == null ? 0 : bytes.length);
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        byte[] source = DOCUMENTS.get(documentId);
        if (!mode.equals("r") || source == null) throw new FileNotFoundException();
        byte[] bytes = source.clone();
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException ignored) { throw new FileNotFoundException(); }
        new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(bytes);
            } catch (IOException ignored) {
                // Cancellation or the bounded reader closes the pipe.
            } finally { Arrays.fill(bytes, (byte) 0); }
        }).start();
        return pipe[0];
    }
}
