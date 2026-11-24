package com.winlator.cmod.contents;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ContentsManager {
    public static final String PROFILE_NAME = "profile.json";
    public static final String REMOTE_PROFILES = "https://raw.githubusercontent.com/StevenMXZ/Winlator-Contents/main/contents.json";
    public static final String[] DXVK_TRUST_FILES = {"${system32}/d3d8.dll", "${system32}/d3d9.dll", "${system32}/d3d10.dll", "${system32}/d3d10_1.dll",
            "${system32}/d3d10core.dll", "${system32}/d3d11.dll", "${system32}/dxgi.dll", "${syswow64}/d3d8.dll", "${syswow64}/d3d9.dll", "${syswow64}/d3d10.dll",
            "${syswow64}/d3d10_1.dll", "${syswow64}/d3d10core.dll", "${syswow64}/d3d11.dll", "${syswow64}/dxgi.dll"};
    public static final String[] VKD3D_TRUST_FILES = {"${system32}/d3d12core.dll", "${system32}/d3d12.dll",
            "${syswow64}/d3d12core.dll", "${syswow64}/d3d12.dll"};
    public static final String[] BOX64_TRUST_FILES = {"${bindir}/box64"};
    public static final String[] D7VK_TRUST_FILES = {"${system32}/ddraw.dll", "${syswow64}/ddraw.dll"};
    public static final String[] WOWBOX64_TRUST_FILES = {"${system32}/wowbox64.dll"};
    public static final String[] FEXCORE_TRUST_FILES = {
            "${system32}/libwow64fex.dll",
            "${system32}/libarm64ecfex.dll",
            "${libdir}/wine/aarch64-unix/libwow64fex.so",
            "${libdir}/wine/aarch64-unix/libarm64ecfex.so"
    };
    private Map<String, String> dirTemplateMap;
    private Map<ContentProfile.ContentType, List<String>> trustedFilesMap;

    private SharedPreferences preferences;

    public enum InstallFailedReason {
        ERROR_NOSPACE,
        ERROR_BADTAR,
        ERROR_NOPROFILE,
        ERROR_BADPROFILE,
        ERROR_MISSINGFILES,
        ERROR_EXIST,
        ERROR_UNTRUSTPROFILE,
        ERROR_UNSUPPORTED_ARCH,
        ERROR_UNKNOWN
    }

    public enum ContentDirName {
        CONTENT_MAIN_DIR_NAME("contents"),
        CONTENT_WINE_DIR_NAME("wine"),
        CONTENT_DXVK_DIR_NAME("dxvk"),
        CONTENT_VKD3D_DIR_NAME("vkd3d"),
        CONTENT_BOX64_DIR_NAME("box64");

        private String name;

        ContentDirName(String name) {
            this.name = name;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private final Context context;
    private HashMap<ContentProfile.ContentType, List<ContentProfile>> profilesMap;
    private ArrayList<ContentProfile> remoteProfiles;

    public ContentsManager(Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences("contents_manager_prefs", Context.MODE_PRIVATE);
    }

    public void setGraphicsDriverInstalled(String driverVersion, boolean installed) {
        preferences.edit().putBoolean("graphics_driver_installed_" + driverVersion, installed).apply();
    }

    public interface OnInstallFinishedCallback {
        void onFailed(InstallFailedReason reason, Exception e);
        void onSucceed(ContentProfile profile);
    }

    public interface OnInstallProgressCallback {
        void onProgress(int progress);
    }

    public void setRemoteProfiles(String json) {
        try {
            remoteProfiles = new ArrayList<>();
            JSONArray content = new JSONArray(json);
            for (int i = 0; i < content.length(); i++) {
                try {
                    JSONObject object = content.getJSONObject(i);
                    String remoteUrl = object.getString("remoteUrl");
                    if ("https://github.com/StevenMXZ/Winlator-Contents/releases/download/1.0/Proton.9.0-x86_64.wcp".equals(remoteUrl)
                            || "https://github.com/StevenMXZ/Winlator-Contents/releases/download/1.0/proton-10-arm64ec.wcp.xz".equals(remoteUrl))
                        continue;
                    ContentProfile remoteProfile = new ContentProfile();
                    remoteProfile.remoteUrl = remoteUrl;
                    remoteProfile.type = ContentProfile.ContentType.getTypeByName(object.getString("type"));
                    remoteProfile.verName = object.getString("verName");
                    remoteProfile.verCode = object.getInt("verCode");
                    remoteProfiles.add(remoteProfile);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        syncContents();
    }

    public void syncContents() {
        profilesMap = new HashMap<>();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) profilesMap.put(type, new LinkedList<>());

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> profiles = profilesMap.get(type);
            File typeFile = getContentTypeDir(context, type);
            File[] fileList = typeFile.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    File proFile = new File(file, PROFILE_NAME);
                    if (proFile.exists() && proFile.isFile()) {
                        ContentProfile profile = readProfile(proFile);
                        if (profile != null) {
                            profiles.add(profile);
                            Log.d("ContentsManager", "Local profile loaded: " + profile.verName);
                        } else Log.w("ContentsManager", "Invalid local profile at: " + proFile.getAbsolutePath());
                    }
                }
            }
            if (remoteProfiles != null) {
                for (ContentProfile remote : remoteProfiles) {
                    if (remote.type == type) {
                        boolean exists = false;
                        for (ContentProfile profile : profiles) {
                            if (profile.verName.equals(remote.verName) && profile.verCode == remote.verCode) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            profiles.add(remote);
                            Log.d("ContentsManager", "Remote profile added: " + remote.verName);
                        }
                    }
                }
            }
        }
    }

    public void extraContentFile(Uri uri, OnInstallFinishedCallback callback) {
        extraContentFile(uri, null, callback);
    }

    public void extraContentFile(Uri uri, OnInstallProgressCallback progressCallback,
                                 OnInstallFinishedCallback callback) {
        cleanTmpDir(context);
        File file = getTmpDir(context);
        TarCompressorUtils.OnExtractProgressListener listener = progressCallback == null
                ? null : progressCallback::onProgress;
        boolean ret = TarCompressorUtils.extract(
                TarCompressorUtils.Type.XZ, context, uri, file, null, listener);
        if (!ret) {
            ret = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, context, uri, file, null, listener);
        }
        if (!ret) {
            callback.onFailed(InstallFailedReason.ERROR_BADTAR, null);
            return;
        }
        File proFile = new File(file, PROFILE_NAME);
        if (!proFile.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_NOPROFILE, null);
            return;
        }
        ContentProfile profile = readProfile(proFile);
        if (profile == null) {
            callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, null);
            return;
        }
        // This ARM32 build ships its own ARMHF + Box86 + i386 Wine runtime.
        // WCP Wine/Proton packages are not interchangeable with that runtime,
        // and Proton ARM64EC cannot execute in a 32-bit Android process.
        if (!android.os.Process.is64Bit()
                && (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            FileUtils.delete(file);
            callback.onFailed(InstallFailedReason.ERROR_UNSUPPORTED_ARCH, null);
            return;
        }
        String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File tmpFile = resolveContentSource(file, contentFile.source);
            if (tmpFile == null) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }
            String realPath = getPathFromTemplate(contentFile.target);
            if (!isSubPath(imagefsPath, realPath) || isSubPath(ContentsManager.getContentDir(context).getAbsolutePath(), realPath) || realPath.contains("dosdevices")) {
                callback.onFailed(InstallFailedReason.ERROR_UNTRUSTPROFILE, null);
                return;
            }
        }
        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            File bin = new File(file, profile.wineBinPath);
            File lib = new File(file, profile.wineLibPath);
            File cp = new File(file, profile.winePrefixPack);
            if (!bin.exists() || !bin.isDirectory() || !lib.exists() || !lib.isDirectory() || !cp.exists() || !cp.isFile()) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }
        }
        callback.onSucceed(profile);
    }

    public void finishInstallContent(ContentProfile profile, OnInstallFinishedCallback callback) {
        File installPath = getInstallDir(context, profile);
        File tmpPath = getTmpDir(context);

        if (installPath.exists()) {
            File installedProfile = new File(installPath, PROFILE_NAME);
            if (installedProfile.isFile()) {
                cleanTmpDir(context);
                syncContents();
                callback.onFailed(InstallFailedReason.ERROR_EXIST, null);
                return;
            }
            FileUtils.delete(installPath);
        }

        File parent = installPath.getParentFile();
        if ((parent != null && !parent.isDirectory() && !parent.mkdirs())
                || !tmpPath.isDirectory()
                || !tmpPath.renameTo(installPath)) {
            cleanTmpDir(context);
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        syncContents();
        callback.onSucceed(profile);
    }

    public ContentProfile readProfile(File file) {
        try {
            ContentProfile profile = new ContentProfile();
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            String typeName = profileJSONObject.getString(ContentProfile.MARK_TYPE);
            String verName = profileJSONObject.getString(ContentProfile.MARK_VERSION_NAME);
            int verCode = profileJSONObject.getInt(ContentProfile.MARK_VERSION_CODE);
            String desc = profileJSONObject.getString(ContentProfile.MARK_DESC);
            JSONArray fileJSONArray = profileJSONObject.getJSONArray(ContentProfile.MARK_FILE_LIST);
            List<ContentProfile.ContentFile> fileList = new ArrayList<>();
            for (int i = 0; i < fileJSONArray.length(); i++) {
                JSONObject contentFileJSONObject = fileJSONArray.getJSONObject(i);
                ContentProfile.ContentFile contentFile = new ContentProfile.ContentFile();
                contentFile.source = contentFileJSONObject.getString(ContentProfile.MARK_FILE_SOURCE);
                contentFile.target = contentFileJSONObject.getString(ContentProfile.MARK_FILE_TARGET);
                fileList.add(contentFile);
            }
            if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString()) || typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString())) {
                JSONObject wineJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_WINE);
                profile.wineLibPath = wineJSONObject.getString(ContentProfile.MARK_WINE_LIBPATH);
                profile.wineBinPath = wineJSONObject.getString(ContentProfile.MARK_WINE_BINPATH);
                profile.winePrefixPack = wineJSONObject.getString(ContentProfile.MARK_WINE_PREFIX_PACK);
            }
            profile.type = ContentProfile.ContentType.getTypeByName(typeName);
            profile.verName = verName;
            profile.verCode = verCode;
            profile.desc = desc;
            profile.fileList = fileList;
            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    public List<ContentProfile> getProfiles(ContentProfile.ContentType type) {
        if (profilesMap != null) return profilesMap.get(type);
        return null;
    }

    public List<ContentProfile> getInstalledProfiles(ContentProfile.ContentType type) {
        List<ContentProfile> installedProfiles = new ArrayList<>();
        List<ContentProfile> profiles = getProfiles(type);
        if (profiles == null) return installedProfiles;
        for (ContentProfile profile : profiles) if (profile.remoteUrl == null) installedProfiles.add(profile);
        return installedProfiles;
    }

    public static File getInstallDir(Context context, ContentProfile profile) {
        return new File(getContentTypeDir(context, profile.type), profile.verName + "-" + profile.verCode);
    }

    public static File getContentDir(Context context) {
        return new File(context.getFilesDir(), ContentDirName.CONTENT_MAIN_DIR_NAME.toString());
    }

    public static File getContentTypeDir(Context context, ContentProfile.ContentType type) {
        return new File(getContentDir(context), type.toString());
    }

    public static File getTmpDir(Context context) {
        return new File(context.getFilesDir(), "tmp/" + ContentDirName.CONTENT_MAIN_DIR_NAME);
    }

    public static File getSourceFile(Context context, ContentProfile profile, String path) {
        return new File(getInstallDir(context, profile), path);
    }

    public static void cleanTmpDir(Context context) {
        File file = getTmpDir(context);
        FileUtils.delete(file);
        file.mkdirs();
    }

    public List<ContentProfile.ContentFile> getUnTrustedContentFiles(ContentProfile profile) {
        createTrustedFilesMap();
        List<ContentProfile.ContentFile> files = new ArrayList<>();
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            if (!trustedFilesMap.get(profile.type).contains(Paths.get(getPathFromTemplate(contentFile.target)).toAbsolutePath().normalize().toString())) files.add(contentFile);
        }
        return files;
    }

    private boolean isSubPath(String parent, String child) {
        return Paths.get(child).toAbsolutePath().normalize().startsWith(Paths.get(parent).toAbsolutePath().normalize());
    }

    private File resolveContentSource(File root, String relativePath) {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File sourceFile = new File(root, relativePath).getCanonicalFile();
            if (!sourceFile.isFile()
                    || !isSubPath(canonicalRoot.getAbsolutePath(), sourceFile.getAbsolutePath())) {
                return null;
            }
            return sourceFile;
        } catch (Exception e) {
            Log.e("ContentsManager", "Unable to resolve content source " + relativePath, e);
            return null;
        }
    }

    private void createDirTemplateMap() {
        if (dirTemplateMap == null) {
            dirTemplateMap = new HashMap<>();
            String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
            String drivecPath = imagefsPath + "/home/xuser/.wine/drive_c";
            dirTemplateMap.put("${libdir}", imagefsPath + "/usr/lib");
            dirTemplateMap.put("${system32}", drivecPath + "/windows/system32");
            dirTemplateMap.put("${syswow64}", drivecPath + "/windows/syswow64");
            dirTemplateMap.put("${bindir}", imagefsPath + "/usr/bin");
            dirTemplateMap.put("${sharedir}", imagefsPath + "/usr/share");
        }
    }

    private void createTrustedFilesMap() {
        if (trustedFilesMap == null) {
            trustedFilesMap = new HashMap<>();
            for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
                List<String> pathList = new ArrayList<>();
                trustedFilesMap.put(type, pathList);
                String[] paths = switch (type) {
                    case CONTENT_TYPE_DXVK -> DXVK_TRUST_FILES;
                    case CONTENT_TYPE_D7VK -> D7VK_TRUST_FILES;
                    case CONTENT_TYPE_VKD3D -> VKD3D_TRUST_FILES;
                    case CONTENT_TYPE_BOX64 -> BOX64_TRUST_FILES;
                    case CONTENT_TYPE_WOWBOX64 -> WOWBOX64_TRUST_FILES;
                    case CONTENT_TYPE_FEXCORE -> FEXCORE_TRUST_FILES;
                    default -> new String[0];
                };
                for (String path : paths) pathList.add(Paths.get(getPathFromTemplate(path)).toAbsolutePath().normalize().toString());
            }
        }
    }

    private String getPathFromTemplate(String path) {
        createDirTemplateMap();
        String realPath = path;
        for (String key : dirTemplateMap.keySet()) realPath = realPath.replace(key, dirTemplateMap.get(key));
        return realPath;
    }

    public void removeContent(ContentProfile profile) {
        if (profilesMap.get(profile.type).contains(profile)) {
            FileUtils.delete(getInstallDir(context, profile));
            profilesMap.get(profile.type).remove(profile);
            syncContents();
        }
    }

    public static String getEntryName(ContentProfile profile) {
        return profile.type.toString() + '-' + profile.verName + '-' + profile.verCode;
    }

    public ContentProfile getProfileByEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty() || profilesMap == null) return null;
        int firstDashIndex = entryName.indexOf('-');
        if (firstDashIndex <= 0 || firstDashIndex >= entryName.length() - 1) return null;
        ContentProfile.ContentType type = ContentProfile.ContentType.getTypeByName(entryName.substring(0, firstDashIndex));
        if (type == null) return null;
        List<ContentProfile> profiles = profilesMap.get(type);
        if (profiles == null) return null;

        for (ContentProfile profile : profiles) {
            if (entryName.equals(getEntryName(profile))) return profile;
        }

        if (type == ContentProfile.ContentType.CONTENT_TYPE_WINE || type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) return null;

        String versionName = entryName.substring(firstDashIndex + 1);
        ContentProfile bestMatch = null;
        for (ContentProfile profile : profiles) {
            if (profile.remoteUrl == null && versionName.equals(profile.verName)) {
                if (bestMatch == null || profile.verCode > bestMatch.verCode) bestMatch = profile;
            }
        }
        return bestMatch;
    }

    public boolean isContentApplied(ContentProfile profile) {
        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            return true;
        }

        File installDir = getInstallDir(context, profile);
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File sourceFile = resolveContentSource(installDir, contentFile.source);
            File targetFile = new File(getPathFromTemplate(contentFile.target));
            if (sourceFile == null || !targetFile.isFile() || targetFile.length() != sourceFile.length()) {
                return false;
            }
        }
        return true;
    }

    public boolean applyContent(ContentProfile profile) {
        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            return true;
        }

        boolean success = true;
        File installDir = getInstallDir(context, profile);
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File sourceFile = resolveContentSource(installDir, contentFile.source);
            File targetFile = new File(getPathFromTemplate(contentFile.target));

            if (sourceFile == null) {
                Log.e("ContentsManager", "Missing or unsafe content source: " + contentFile.source);
                success = false;
                continue;
            }
            if (targetFile.exists() && !targetFile.delete()) {
                Log.e("ContentsManager", "Unable to replace content target: " + targetFile.getAbsolutePath());
                success = false;
                continue;
            }

            boolean copied = FileUtils.copy(sourceFile, targetFile);
            if (!copied || !targetFile.isFile() || targetFile.length() != sourceFile.length()) {
                Log.e("ContentsManager", "Failed to apply content: "
                        + sourceFile.getAbsolutePath() + " -> " + targetFile.getAbsolutePath());
                success = false;
                continue;
            }

            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_BOX64) {
                FileUtils.chmod(targetFile, 0771);
            }
            Log.d("ContentsManager", "Applied content: "
                    + sourceFile.getAbsolutePath() + " -> " + targetFile.getAbsolutePath());
        }
        return success;
    }
}
