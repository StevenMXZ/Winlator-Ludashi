package com.winlator.cmod.core;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Objects;
import org.bouncycastle.i18n.ErrorBundle;
import org.bouncycastle.i18n.MessageBundle;

/* loaded from: classes10.dex */
public class WinlatorFilesProvider extends DocumentsProvider {
    private static final String ALL_MIME_TYPES = "*/*";
    private File BASE_DIR;
    private boolean enabled;
    private static final String[] DEFAULT_ROOT_PROJECTION = {"root_id", "mime_types", "flags", "icon", MessageBundle.TITLE_ENTRY, ErrorBundle.SUMMARY_ENTRY, "document_id", "available_bytes"};
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {"document_id", "mime_type", "_display_name", "last_modified", "flags", "_size"};

    @Override // android.provider.DocumentsProvider, android.content.ContentProvider
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        this.BASE_DIR = context.getDataDir();
        this.enabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enable_file_provider", true);
    }

    @Override // android.provider.DocumentsProvider
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        File source = new File(sourceDocumentId);
        File sourceParent = new File(sourceParentDocumentId);
        File targetParent = new File(targetParentDocumentId);
        if (!sourceParent.exists()) {
            throw new FileNotFoundException("Source parent is not found: " + sourceParentDocumentId);
        }
        if (!source.exists()) {
            throw new FileNotFoundException("Source file not found: " + sourceDocumentId);
        }
        if (Objects.equals(source.getParentFile(), sourceParent)) {
            throw new FileNotFoundException("Source has wrong parent: " + sourceDocumentId + " " + sourceParentDocumentId);
        }
        if (!targetParent.exists()) {
            throw new FileNotFoundException("Target file not found: " + targetParentDocumentId);
        }
        if (!targetParent.isDirectory()) {
            throw new FileNotFoundException("Target parent is not directory: " + targetParentDocumentId);
        }
        File target = new File(targetParentDocumentId, source.getName());
        if (target.exists()) {
            throw new FileNotFoundException("Target already exist");
        }
        boolean ret = source.renameTo(target);
        if (!ret) {
            throw new FileNotFoundException("Failed to move: " + sourceDocumentId);
        }
        return target.getAbsolutePath();
    }

    @Override // android.provider.DocumentsProvider
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        File parent = new File(parentDocumentId);
        File target = new File(documentId);
        if (!parent.exists()) {
            throw new FileNotFoundException("Parent is not exist: " + parentDocumentId);
        }
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Parent is not directory: " + parentDocumentId);
        }
        if (!target.exists()) {
            throw new FileNotFoundException("File is not found: " + documentId);
        }
        boolean ret = target.delete();
        if (!ret) {
            throw new FileNotFoundException("Failed to delete file: " + documentId);
        }
    }

    @Override // android.provider.DocumentsProvider
    public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        String applicationName = getContext().getString(R.string.app_name);
        MatrixCursor.RowBuilder row = result.newRow();
        row.add("root_id", getDocIdForFile(this.BASE_DIR));
        row.add("document_id", getDocIdForFile(this.BASE_DIR));
        row.add(ErrorBundle.SUMMARY_ENTRY, null);
        row.add("flags", 25);
        row.add(MessageBundle.TITLE_ENTRY, applicationName);
        row.add("mime_types", ALL_MIME_TYPES);
        row.add("available_bytes", Long.valueOf(this.BASE_DIR.getFreeSpace()));
        row.add("icon", Integer.valueOf(R.mipmap.ic_launcher));
        return result;
    }

    @Override // android.provider.DocumentsProvider
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override // android.provider.DocumentsProvider
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File parent = getFileForDocId(parentDocumentId);
        for (File file : parent.listFiles()) {
            includeFile(result, null, file);
        }
        return result;
    }

    @Override // android.provider.DocumentsProvider
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override // android.provider.DocumentsProvider
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, 268435456);
        return new AssetFileDescriptor(pfd, 0L, file.length());
    }

    @Override // android.content.ContentProvider
    public boolean onCreate() {
        return true;
    }

    @Override // android.provider.DocumentsProvider
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        boolean succeeded;
        File newFile = new File(parentDocumentId, displayName);
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parentDocumentId, displayName + " (" + noConflictId + ")");
            noConflictId++;
        }
        try {
            if ("vnd.android.document/directory".equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
            }
            return newFile.getPath();
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
        }
    }

    @Override // android.provider.DocumentsProvider
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (!file.delete()) {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
    }

    @Override // android.provider.DocumentsProvider
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getMimeType(file);
    }

    @Override // android.provider.DocumentsProvider
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        boolean isInsideHome;
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File parent = getFileForDocId(rootId);
        LinkedList<File> pending = new LinkedList<>();
        pending.add(parent);
        while (!pending.isEmpty() && result.getCount() < 50) {
            File file = pending.removeFirst();
            try {
                isInsideHome = file.getCanonicalPath().startsWith(this.BASE_DIR.getAbsolutePath());
            } catch (IOException e) {
                isInsideHome = true;
            }
            if (isInsideHome) {
                if (file.isDirectory()) {
                    Collections.addAll(pending, file.listFiles());
                } else if (file.getName().toLowerCase().contains(query)) {
                    includeFile(result, null, file);
                }
            }
        }
        return result;
    }

    @Override // android.provider.DocumentsProvider
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    private static String getDocIdForFile(File file) {
        return file.getAbsolutePath();
    }

    private static File getFileForDocId(String docId) throws FileNotFoundException {
        File f = new File(docId);
        if (!f.exists()) {
            throw new FileNotFoundException(f.getAbsolutePath() + " not found");
        }
        return f;
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return "vnd.android.document/directory";
        }
        String name = file.getName();
        int lastDot = name.lastIndexOf(46);
        if (lastDot >= 0) {
            String extension = name.substring(lastDot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            return mime != null ? mime : "application/octet-stream";
        }
        return "application/octet-stream";
    }

    @Override // android.provider.DocumentsProvider
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File oldFile = new File(documentId);
        if (!oldFile.exists()) {
            throw new FileNotFoundException("File not found: " + documentId);
        }
        File parentDir = oldFile.getParentFile();
        File newFile = new File(parentDir, displayName);
        if (oldFile.renameTo(newFile)) {
            return newFile.getAbsolutePath();
        }
        throw new FileNotFoundException("Failed to rename document with id " + documentId);
    }

    private void includeFile(MatrixCursor result, String docId, File file) throws FileNotFoundException {
        if (!this.enabled) {
            throw new FileNotFoundException();
        }
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }
        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) {
                flags = 0 | 8;
            }
        } else if (file.canWrite()) {
            flags = 0 | 2;
        }
        if (file.getParentFile().canWrite()) {
            flags |= 4;
        }
        if (file.canWrite()) {
            flags |= 64;
        }
        String displayName = file.getName();
        String mimeType = getMimeType(file);
        if (mimeType.startsWith("image/")) {
            flags |= 1;
        }
        MatrixCursor.RowBuilder row = result.newRow();
        row.add("document_id", docId);
        row.add("_display_name", displayName);
        row.add("_size", Long.valueOf(file.length()));
        row.add("mime_type", mimeType);
        row.add("last_modified", Long.valueOf(file.lastModified()));
        row.add("flags", Integer.valueOf(flags));
        row.add("icon", Integer.valueOf(R.mipmap.ic_launcher));
    }
}
