package com.winlator.contents;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.winlator.core.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Stack;

public class ExternalStorageProvider extends DocumentsProvider {
    private static final String TAG = "ExternalStorageProvider";
    private File rootDir;

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
    };

    @Override
    public boolean onCreate() {
        rootDir = getContext().getFilesDir();
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "root");
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root:");
        row.add(DocumentsContract.Root.COLUMN_TITLE, getContext().getString(app.gamenative.R.string.app_name));
        row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(DocumentsContract.Root.COLUMN_ICON, app.gamenative.R.mipmap.ic_launcher);
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, rootDir.getFreeSpace());
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(parentDocumentId);
        File[] files = parent.listFiles();
        if (files != null) {
            for (File file : files) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        File file = new File(parent, displayName);
        try {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!file.mkdir()) throw new FileNotFoundException("Failed to create directory " + file.getPath());
            } else {
                if (!file.createNewFile()) throw new FileNotFoundException("Failed to create file " + file.getPath());
            }
        } catch (Exception e) {
            throw new FileNotFoundException("Failed to create document with name " + displayName + " and mimeType " + mimeType);
        }
        return getDocIdForFile(file);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (!FileUtils.delete(file)) throw new FileNotFoundException("Failed to delete " + file.getPath());
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getMimeType(file);
    }

    private File getFileForDocId(String documentId) throws FileNotFoundException {
        File target = rootDir;
        if (documentId.equals("root:")) return target;
        final int splitIndex = documentId.indexOf(':', 1);
        if (splitIndex < 0) throw new FileNotFoundException("Invalid document ID: " + documentId);
        final String path = documentId.substring(splitIndex + 1);
        target = new File(rootDir, path);
        if (!target.exists()) throw new FileNotFoundException("File not found: " + target.getPath());
        return target;
    }

    private String getDocIdForFile(File file) {
        String path = file.getAbsolutePath();
        String rootPath = rootDir.getAbsolutePath();
        if (path.startsWith(rootPath)) {
            path = path.substring(rootPath.length());
            if (path.startsWith("/")) path = path.substring(1);
        }
        return "root:" + path;
    }

    private void includeFile(MatrixCursor result, String docId, File file) throws FileNotFoundException {
        if (docId != null) file = getFileForDocId(docId);
        else docId = getDocIdForFile(file);

        int flags = 0;
        if (file.canWrite()) {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_REMOVE;
        }
        if (file.isDirectory()) flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getName().isEmpty() ? "root" : file.getName());
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, getMimeType(file));
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
        row.add(DocumentsContract.Document.COLUMN_ICON, file.isDirectory() ? app.gamenative.R.mipmap.ic_launcher : 0);
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        final int lastDot = file.getName().lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = file.getName().substring(lastDot + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }
}
