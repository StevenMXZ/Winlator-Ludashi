package com.winlator.cmod.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.Executors;

/* loaded from: classes10.dex */
public abstract class FileUtils {
    private static final String TAG = "FileUtils";

    public static byte[] read(Context context, String assetFile) {
        try {
            InputStream inStream = context.getAssets().open(assetFile);
            try {
                byte[] copyToByteArray = StreamUtils.copyToByteArray(inStream);
                if (inStream != null) {
                    inStream.close();
                }
                return copyToByteArray;
            } finally {
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static byte[] read(File file) {
        try {
            InputStream inStream = new BufferedInputStream(new FileInputStream(file));
            try {
                byte[] copyToByteArray = StreamUtils.copyToByteArray(inStream);
                inStream.close();
                return copyToByteArray;
            } finally {
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static String readString(Context context, String assetFile) {
        return new String(read(context, assetFile), StandardCharsets.UTF_8);
    }

    public static String readString(File file) {
        return new String(read(file), StandardCharsets.UTF_8);
    }

    public static String readString(Context context, Uri uri) {
        StringBuilder sb = new StringBuilder();
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                while (true) {
                    try {
                        String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        sb.append(line);
                    } finally {
                    }
                }
                String sb2 = sb.toString();
                reader.close();
                if (inputStream != null) {
                    inputStream.close();
                }
                return sb2;
            } finally {
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean write(File file, byte[] data) {
        try {
            OutputStream os = new FileOutputStream(file);
            try {
                os.write(data, 0, data.length);
                os.close();
                return true;
            } finally {
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean writeString(File file, String data) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(file));
            try {
                bw.write(data);
                bw.flush();
                bw.close();
                return true;
            } finally {
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void symlink(File linkTarget, File linkFile) {
        symlink(linkTarget.getAbsolutePath(), linkFile.getAbsolutePath());
    }

    public static void symlink(String linkTarget, String linkFile) {
        try {
            new File(linkFile).delete();
            Os.symlink(linkTarget, linkFile);
        } catch (ErrnoException e) {
        }
    }

    public static boolean isSymlink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }

    public static boolean delete(File targetFile) {
        if (targetFile == null) {
            return false;
        }
        if (targetFile.isDirectory() && !isSymlink(targetFile) && !clear(targetFile)) {
            return false;
        }
        return targetFile.delete();
    }

    public static boolean clear(File targetFile) {
        File[] files;
        if (targetFile == null) {
            return false;
        }
        if (targetFile.isDirectory() && (files = targetFile.listFiles()) != null) {
            for (File file : files) {
                if (!delete(file)) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    public static boolean isEmpty(File targetFile) {
        if (targetFile == null) {
            return true;
        }
        if (!targetFile.isDirectory()) {
            return targetFile.length() == 0;
        }
        String[] files = targetFile.list();
        return files == null || files.length == 0;
    }

    public static boolean copy(File srcFile, File dstFile) {
        return copy(srcFile, dstFile, (Callback<File>) null);
    }

    public static boolean copy(File srcFile, File dstFile, Callback<File> callback) {
        if (isSymlink(srcFile)) {
            return true;
        }
        if (srcFile.isDirectory()) {
            if (!dstFile.exists() && !dstFile.mkdirs()) {
                return false;
            }
            if (callback != null) {
                callback.call(dstFile);
            }
            String[] filenames = srcFile.list();
            if (filenames != null) {
                for (String filename : filenames) {
                    if (!copy(new File(srcFile, filename), new File(dstFile, filename), callback)) {
                        Log.e(TAG, "Failed to copy directory: " + srcFile.getAbsolutePath());
                    }
                }
            }
            return true;
        }
        File parent = dstFile.getParentFile();
        if (!srcFile.exists() || (parent != null && !parent.exists() && !parent.mkdirs())) {
            return false;
        }
        try {
            FileChannel inChannel = new FileInputStream(srcFile).getChannel();
            try {
                FileChannel outChannel = new FileOutputStream(dstFile).getChannel();
                try {
                    inChannel.transferTo(0L, inChannel.size(), outChannel);
                    if (callback != null) {
                        callback.call(dstFile);
                    }
                    boolean exists = dstFile.exists();
                    if (outChannel != null) {
                        outChannel.close();
                    }
                    if (inChannel != null) {
                        inChannel.close();
                    }
                    return exists;
                } finally {
                }
            } finally {
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed to copy file: " + srcFile.getAbsolutePath() + " to " + dstFile.getAbsolutePath(), e);
            return true;
        }
    }

    public static boolean copy(Context context, Object src, File dstFile, Callback<File> callback) {
        if (src instanceof File) {
            File sourceFile = (File) src;
            if (isSymlink(sourceFile)) {
                return true;
            }
            if (sourceFile.isDirectory()) {
                if (!dstFile.exists() && !dstFile.mkdirs()) {
                    return false;
                }
                if (callback != null) {
                    callback.call(dstFile);
                }
                String[] filenames = sourceFile.list();
                if (filenames != null) {
                    for (String filename : filenames) {
                        if (!copy(context, new File(sourceFile, filename), new File(dstFile, filename), callback)) {
                            return false;
                        }
                    }
                }
            } else {
                File parent = dstFile.getParentFile();
                if (!sourceFile.exists() || (parent != null && !parent.exists() && !parent.mkdirs())) {
                    return false;
                }
                try {
                    FileChannel inChannel = new FileInputStream(sourceFile).getChannel();
                    try {
                        FileChannel outChannel = new FileOutputStream(dstFile).getChannel();
                        try {
                            inChannel.transferTo(0L, inChannel.size(), outChannel);
                            if (callback != null) {
                                callback.call(dstFile);
                            }
                            boolean exists = dstFile.exists();
                            if (outChannel != null) {
                                outChannel.close();
                            }
                            if (inChannel != null) {
                                inChannel.close();
                            }
                            return exists;
                        } finally {
                        }
                    } finally {
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                }
            }
        } else if (src instanceof Uri) {
            if (context == null) {
                throw new IllegalArgumentException("Context is required for Uri to File copying");
            }
            Uri srcUri = (Uri) src;
            try {
                InputStream inputStream = context.getContentResolver().openInputStream(srcUri);
                try {
                    OutputStream outputStream = new FileOutputStream(dstFile);
                    try {
                        byte[] buffer = new byte[1024];
                        while (true) {
                            int length = inputStream.read(buffer);
                            if (length <= 0) {
                                break;
                            }
                            outputStream.write(buffer, 0, length);
                        }
                        if (callback != null) {
                            callback.call(dstFile);
                        }
                        outputStream.close();
                        if (inputStream != null) {
                            inputStream.close();
                        }
                        return true;
                    } finally {
                    }
                } finally {
                }
            } catch (Exception e2) {
                e2.printStackTrace();
                return false;
            }
        }
        return false;
    }

    public static void copy(Context context, String assetFile, File dstFile) {
        if (isDirectory(context, assetFile)) {
            if (!dstFile.isDirectory()) {
                dstFile.mkdirs();
            }
            try {
                String[] filenames = context.getAssets().list(assetFile);
                for (String filename : filenames) {
                    String relativePath = StringUtils.addEndSlash(assetFile) + filename;
                    if (isDirectory(context, relativePath)) {
                        copy(context, relativePath, new File(dstFile, filename));
                    } else {
                        copy(context, relativePath, dstFile);
                    }
                }
                return;
            } catch (IOException e) {
                return;
            }
        }
        if (dstFile.isDirectory()) {
            dstFile = new File(dstFile, getName(assetFile));
        }
        File parent = dstFile.getParentFile();
        if (!parent.isDirectory()) {
            parent.mkdirs();
        }
        try {
            InputStream inStream = context.getAssets().open(assetFile);
            try {
                BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(dstFile), 65536);
                try {
                    StreamUtils.copy(inStream, outStream);
                    outStream.close();
                    if (inStream != null) {
                        inStream.close();
                    }
                } finally {
                }
            } finally {
            }
        } catch (IOException e2) {
        }
    }

    public static boolean copy(Context context, Uri uri, File dest) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            try {
                OutputStream outputStream = new FileOutputStream(dest);
                try {
                    byte[] buffer = new byte[1024];
                    while (true) {
                        int length = inputStream.read(buffer);
                        if (length <= 0) {
                            break;
                        }
                        outputStream.write(buffer, 0, length);
                    }
                    outputStream.close();
                    if (inputStream != null) {
                        inputStream.close();
                        return true;
                    }
                    return true;
                } finally {
                }
            } finally {
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static ArrayList<String> readLines(File file) {
        ArrayList<String> lines = new ArrayList<>();
        try {
            FileInputStream fis = new FileInputStream(file);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    lines.add(line);
                }
                fis.close();
            } finally {
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lines;
    }

    public static String getName(String path) {
        if (path == null) {
            return "";
        }
        String path2 = StringUtils.removeEndSlash(path);
        int index = Math.max(path2.lastIndexOf(47), path2.lastIndexOf(92));
        return path2.substring(index + 1);
    }

    public static String getBasename(String path) {
        return getName(path).replaceFirst("\\.[^\\.]+$", "");
    }

    public static String getDirname(String path) {
        if (path == null) {
            return "";
        }
        String path2 = StringUtils.removeEndSlash(path);
        int index = Math.max(path2.lastIndexOf(47), path2.lastIndexOf(92));
        return path2.substring(0, index);
    }

    public static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
        }
    }

    public static File createTempFile(File parent, String prefix) {
        File tempFile = null;
        boolean exists = true;
        while (exists) {
            tempFile = new File(parent, prefix + "-" + UUID.randomUUID().toString().replace("-", "") + ".tmp");
            exists = tempFile.exists();
        }
        return tempFile;
    }

    public static String getFilePathFromUriUsingSAF(Context context, Uri uri) {
        Log.d(TAG, "getFilePathFromUriUsingSAF called with URI: " + uri.toString());
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            Log.d(TAG, "Document ID: " + documentId);
            String[] split = documentId.split(":");
            String type = split[0];
            String path = split.length > 1 ? split[1] : "";
            try {
                String path2 = URLDecoder.decode(path, "UTF-8");
                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + path2;
                }
                return "/mnt/media_rw/" + type + "/" + path2;
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "Error decoding path: " + path, e);
                return null;
            }
        } catch (IllegalArgumentException e2) {
            Log.e(TAG, "Invalid URI: " + uri.toString(), e2);
            return null;
        }
    }

    public static String getFilePathFromUri(Context context, Uri uri) {
        Log.d(TAG, "getFilePathFromUri called with URI: " + uri.toString());
        String filePath = getFilePathFromUriUsingSAF(context, uri);
        Log.d(TAG, "File path obtained: " + filePath);
        return filePath;
    }

    public static boolean contentEquals(File origin, File target) {
        int data;
        if (origin.length() != target.length()) {
            return false;
        }
        try {
            InputStream inStream1 = new BufferedInputStream(new FileInputStream(origin));
            try {
                InputStream inStream2 = new BufferedInputStream(new FileInputStream(target));
                do {
                    try {
                        data = inStream1.read();
                        if (data == -1) {
                            inStream2.close();
                            inStream1.close();
                            return true;
                        }
                    } finally {
                    }
                } while (data == inStream2.read());
                inStream2.close();
                inStream1.close();
                return false;
            } finally {
            }
        } catch (IOException e) {
            return false;
        }
    }

    public static void getSizeAsync(final File file, final Callback<Long> callback) {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.core.FileUtils$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                FileUtils.getSize(file, (Callback<Long>) callback);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void getSize(File file, Callback<Long> callback) {
        if (file == null) {
            return;
        }
        if (file.isFile()) {
            callback.call(Long.valueOf(file.length()));
            return;
        }
        Stack<File> stack = new Stack<>();
        stack.push(file);
        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] files = current.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        stack.push(f);
                    } else {
                        long length = f.length();
                        if (length > 0) {
                            callback.call(Long.valueOf(length));
                        }
                    }
                }
            }
        }
    }

    public static long getSize(Context context, String assetFile) {
        try {
            InputStream inStream = context.getAssets().open(assetFile);
            try {
                long available = inStream.available();
                if (inStream != null) {
                    inStream.close();
                }
                return available;
            } finally {
            }
        } catch (IOException e) {
            return 0L;
        }
    }

    public static long getInternalStorageSize() {
        File dataDir = Environment.getDataDirectory();
        StatFs stat = new StatFs(dataDir.getPath());
        long blockSize = stat.getBlockSizeLong();
        long totalBlocks = stat.getBlockCountLong();
        return totalBlocks * blockSize;
    }

    public static boolean isDirectory(Context context, String assetFile) {
        try {
            String[] files = context.getAssets().list(assetFile);
            if (files != null) {
                return files.length > 0;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    public static String toRelativePath(String basePath, String fullPath) {
        return StringUtils.removeEndSlash((fullPath.startsWith("/") ? "/" : "") + new File(basePath).toURI().relativize(new File(fullPath).toURI()).getPath());
    }

    public static int readInt(String path) {
        int result = 0;
        try {
            RandomAccessFile reader = new RandomAccessFile(path, "r");
            try {
                String line = reader.readLine();
                result = !line.isEmpty() ? Integer.parseInt(line) : 0;
                reader.close();
            } finally {
            }
        } catch (Exception e) {
        }
        return result;
    }

    public static String readSymlink(File file) {
        try {
            return Files.readSymbolicLink(file.toPath()).toString();
        } catch (IOException e) {
            return "";
        }
    }

    public static String readAssetsFile(Context context, String fileName) {
        try {
            AssetManager assetManager = context.getAssets();
            InputStream is = assetManager.open(fileName);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            while (true) {
                String l = reader.readLine();
                if (l != null) {
                    sb.append(l);
                } else {
                    reader.close();
                    return sb.toString();
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static String getFileSuffix(File file) {
        return getFileSuffix(file.getAbsolutePath());
    }

    public static String getFileSuffix(String path) {
        try {
            int lastDotIndex = path.lastIndexOf(46);
            return path.substring(lastDotIndex + 1);
        } catch (Exception e) {
            return "";
        }
    }

    public static File getFileFromUri(Context context, Uri uri) {
        Log.d(TAG, "getFileFromUri called with URI: " + uri.toString());
        String filePath = getFilePathFromUriUsingSAF(context, uri);
        if (filePath != null) {
            File file = new File(filePath);
            if (file.exists()) {
                return file;
            }
        }
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                File tempFile = File.createTempFile("restore_", ".tmp", context.getCacheDir());
                FileOutputStream outputStream = new FileOutputStream(tempFile);
                try {
                    StreamUtils.copy(inputStream, outputStream);
                    outputStream.close();
                    return tempFile;
                } finally {
                }
            }
            return null;
        } catch (IOException e) {
            Log.e(TAG, "Failed to open URI: " + uri.toString(), e);
            return null;
        }
    }

    public static String getUriFileName(Context context, Uri uri) {
        String fileName = null;
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex("_display_name");
            if (nameIndex != -1) {
                fileName = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        return fileName;
    }

    public static boolean saveBitmapToFile(Bitmap bitmap, File file) {
        try {
            FileOutputStream out = new FileOutputStream(file);
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
                out.close();
                return true;
            } finally {
            }
        } catch (IOException e) {
            Log.e(TAG, "Error saving bitmap to file: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    public static boolean writeToBinaryFile(String filename, int position, int data) {
        try {
            RandomAccessFile file = new RandomAccessFile(filename, "rw");
            try {
                file.seek(position);
                file.write(data);
                file.close();
                return true;
            } finally {
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write data " + data + " at " + position + " to " + filename);
            return false;
        }
    }
}
