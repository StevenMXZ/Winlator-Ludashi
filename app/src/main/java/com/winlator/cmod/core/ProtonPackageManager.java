package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public abstract class ProtonPackageManager {
    private static final String TAG = "ProtonPackageManager";
    public static final String DEFAULT_IDENTIFIER = "proton-9.0-arm64ec";
    private static final String RELEASE_BASE_URL = "https://github.com/Other-backup/winlator-imagefs/releases/download/protons-zst-latest/";
    private static final String RELEASE_NEW_BASE_URL = "https://github.com/Other-backup/winlator-imagefs-v2/releases/download/c/";
    private static final String RELEASE_D_BASE_URL = "https://github.com/Other-backup/winlator-imagefs-v2/releases/download/d/";
    private static final String RELEASE_PNEW_BASE_URL = "https://github.com/Other-backup/winlator-imagefs-v2/releases/download/pnew2/";
    private static final OkHttpClient HTTP = new OkHttpClient();

    public static class PackageInfo {
        public final String identifier;
        public final String title;
        public final String fileName;
        public final long[] partSizes;
        public final String directUrl;
        public final String sha256;

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes) {
            this(identifier, title, fileName, partSizes, null, null);
        }

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes, String directUrl) {
            this(identifier, title, fileName, partSizes, directUrl, null);
        }

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes,
                           String directUrl, String sha256) {
            this.identifier = identifier;
            this.title = title;
            this.fileName = fileName;
            this.partSizes = partSizes;
            this.directUrl = directUrl;
            this.sha256 = sha256;
        }
    }

    private static final List<PackageInfo> PACKAGES = Arrays.asList(
            new PackageInfo("proton-9.0-arm64ec", "Proton 9 arm64ec", "proton-wine-9.0-arm64ec.wcp",
                    new long[]{117648823L}, RELEASE_PNEW_BASE_URL + "proton-wine-9.0-arm64ec.wcp",
                    "0bf76071c02730defae8ac56ee768ddae38ba93aee16b7d10dcdc72ebe961ccb"),
            new PackageInfo("proton-10.0-6-arm64ec", "Proton 10.0-6 arm64ec", "proton-wine-10.0-6-arm64ec.wcp",
                    new long[]{105089376L}, RELEASE_PNEW_BASE_URL + "proton-wine-10.0-6-arm64ec.wcp",
                    "d603b7fc08d2fab396dc647393585d535c538922020f0e213ece03025c8e5cda"),
            new PackageInfo("proton-9.0-x86_64", "Proton 9 x86_64", "proton-9.0-x86_64.tar.zst",
                    new long[]{54043009L}, RELEASE_NEW_BASE_URL + "proton-9.0-x86_64.tar.zst",
                    "b4688130cd15818c2c46e66f2f7f7146d459a2d95bee1b3daf970f50dc4f8331"),
            new PackageInfo("proton-10.0-5-x86_64", "Proton 10.0-5 x86_64", "proton-10.0-5-x86_64.wcp",
                    new long[]{72882772L}, RELEASE_D_BASE_URL + "proton-10.0-5-x86_64.wcp",
                    "2a5759e48b5f856d36eedac3e5159054629260a792fcf9c167140e02c06d8f0c"),
            new PackageInfo("proton-11.0-7-arm64ec", "Proton 11.0-7 arm64ec", "proton-wine-11.0-7-arm64ec.wcp",
                    new long[]{112607135L}, RELEASE_PNEW_BASE_URL + "proton-wine-11.0-7-arm64ec.wcp",
                    "1ef0bb9efceb2278933b3fa2bfd374999b0d9a7c74045b67b9af8a9b5260f734"),
            new PackageInfo("proton-10-arm64ec", "Proton 10 arm64ec (Legacy)", "proton-10-arm64ec.tar.zst",
                    new long[]{52428800L, 52428800L, 52428800L, 52428800L, 7195940L})
    );

    public static List<PackageInfo> getPackages() {
        return new ArrayList<>(PACKAGES);
    }

    public static PackageInfo getPackage(String identifier) {
        for (PackageInfo packageInfo : PACKAGES)
            if (packageInfo.identifier.equals(identifier)) return packageInfo;
        return null;
    }

    public static boolean isKnownPackage(String identifier) {
        return getPackage(identifier) != null;
    }

    public static File getInstallDir(Context context, String identifier) {
        return new File(ImageFs.find(context).getRootDir(), "opt/" + identifier);
    }

    public static boolean isInstalled(Context context, String identifier) {
        File installDir = getInstallDir(context, identifier);
        File[] files = installDir.listFiles();
        return installDir.isDirectory() && files != null && files.length > 0;
    }

    public static List<String> getInstalledIdentifiers(Context context) {
        ArrayList<String> identifiers = new ArrayList<>();
        for (PackageInfo packageInfo : PACKAGES)
            if (isInstalled(context, packageInfo.identifier)) identifiers.add(packageInfo.identifier);
        return identifiers;
    }

    public static boolean downloadPackage(PackageInfo packageInfo, File output, Callback<Integer> progressCallback) {
        if (packageInfo == null || output == null || packageInfo.partSizes == null || packageInfo.partSizes.length == 0)
            return false;

        long totalSize = 0;
        for (long size : packageInfo.partSizes) totalSize += size;
        long downloadedSize = 0;
        FileUtils.delete(output);

        try (FileOutputStream outputStream = new FileOutputStream(output)) {
            for (int i = 0; i < packageInfo.partSizes.length; i++) {
                String address = packageInfo.directUrl != null
                        ? packageInfo.directUrl
                        : RELEASE_BASE_URL + packageInfo.fileName + "." + String.format("%02d", i);
                long expectedPartSize = packageInfo.partSizes[i];
                long downloadedPartSize = 0;

                Request request = new Request.Builder().url(address).build();
                try (Response response = HTTP.newCall(request).execute()) {
                    ResponseBody body = response.body();
                    if (!response.isSuccessful() || body == null) {
                        throw new IllegalStateException("HTTP " + response.code() + " while downloading " + packageInfo.fileName);
                    }

                    try (InputStream inputStream = body.byteStream()) {
                        byte[] data = new byte[64 * 1024];
                        int count;
                        while ((count = inputStream.read(data)) != -1) {
                            outputStream.write(data, 0, count);
                            downloadedPartSize += count;
                            downloadedSize += count;
                            if (progressCallback != null && totalSize > 0) {
                                progressCallback.call(Math.min(100, (int)(downloadedSize * 100 / totalSize)));
                            }
                        }
                    }
                }

                if (expectedPartSize > 0 && downloadedPartSize != expectedPartSize) {
                    throw new IllegalStateException(
                            "Size mismatch for " + packageInfo.fileName + " part " + i
                                    + ": expected " + expectedPartSize + ", got " + downloadedPartSize
                    );
                }
            }
            outputStream.flush();
        }
        catch (Exception e) {
            Log.e(TAG, "Unable to download " + packageInfo.identifier, e);
            FileUtils.delete(output);
            return false;
        }

        if (totalSize > 0 && output.length() != totalSize) {
            Log.e(TAG, "Downloaded size mismatch for " + packageInfo.identifier
                    + ": expected " + totalSize + ", got " + output.length());
            FileUtils.delete(output);
            return false;
        }

        if (packageInfo.sha256 != null && !packageInfo.sha256.isEmpty()
                && !verifySha256(output, packageInfo.sha256)) {
            Log.e(TAG, "SHA-256 mismatch for " + packageInfo.identifier);
            FileUtils.delete(output);
            return false;
        }

        if (progressCallback != null) progressCallback.call(100);
        return true;
    }

    private static boolean verifySha256(File file, String expected) {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format("%02x", value & 0xff));
            return expected.equalsIgnoreCase(hex.toString());
        }
        catch (Exception e) {
            Log.e(TAG, "Unable to verify SHA-256 for " + file, e);
            return false;
        }
    }

    public static boolean installPackage(Context context, String identifier, File archiveFile) {
        boolean installed = ImageFsInstaller.installWineArchive(context, identifier, archiveFile);
        if (!installed || !isInstalled(context, identifier)) {
            deletePackage(context, identifier);
            return false;
        }
        return true;
    }

    public static void deletePackage(Context context, String identifier) {
        FileUtils.delete(getInstallDir(context, identifier));
    }
}
