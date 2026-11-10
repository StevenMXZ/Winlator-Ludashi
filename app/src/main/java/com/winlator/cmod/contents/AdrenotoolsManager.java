package com.winlator.cmod.contents;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {
    
    private File adrenotoolsContentDir;
    private Context mContext;
    
    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }
    
    public String getAssetPath(String adrenotoolsDriverId) {
        return "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
    }
        
    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (JSONException e) {
        }
        return libraryName;
    }
    
    public String getDriverName(String adrenoToolsDriverId) {
        String driverName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverName = jsonObject.getString("name");
        }
        catch (JSONException e) {
        }
        return driverName;
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (JSONException e) {
        }
        return driverVersion;
    }

    public String getDriverPath(String adrenotoolsDriverId) {
        return adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
    }

    private void reloadContainers(String adrenoToolsDriverId) {
        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            Log.d("AdrenotoolsManager", "Checking if container driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
                config.put("version", GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, mContext) ? DefaultVersion.WRAPPER_ADRENO : DefaultVersion.WRAPPER);
                container.setGraphicsDriverConfig(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                container.saveData();
            }     
        }
        for (Shortcut shortcut : containerManager.loadShortcuts()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
            Log.d("AdrenotoolsManager", "Checking if shortcut driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for shortcut " + shortcut.name);
                config.put("version", GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, mContext) ? DefaultVersion.WRAPPER_ADRENO : DefaultVersion.WRAPPER);
                shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                shortcut.saveData();
            }
        }
    }
    
    public void removeDriver(String adrenoToolsDriverId) {
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        
        for (File f : adrenotoolsContentDir.listFiles()) {
            boolean fromResources = isFromResources(f.getName());
            if (!f.getName().startsWith(".import-") && !fromResources && new File(f, "meta.json").exists())
                try {
                    JSONObject meta = new JSONObject(FileUtils.readString(new File(f, "meta.json")));
                    if (!"freedreno".equalsIgnoreCase(meta.optString("type"))
                            && !meta.optString("libraryName").startsWith("libGL.so")) driversList.add(f.getName());
                } catch (Exception error) {
                    Log.w("AdrenotoolsManager", "Ignoring invalid driver metadata: " + f.getName(), error);
                }
        }
        return driversList;
    }

    public ArrayList<String> enumerateRendererDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        String[] bundledDrivers = mContext.getResources().getStringArray(
                com.winlator.cmod.R.array.wrapper_graphics_driver_version_entries);

        for (String id : bundledDrivers) {
            if (!id.equalsIgnoreCase("System") && isFromResources(id) && !driversList.contains(id))
                driversList.add(id);
        }
        for (String id : enumarateInstalledDrivers()) {
            if (!driversList.contains(id)) driversList.add(id);
        }
        return driversList;
    }
    
    public boolean isFromResources(String adrenotoolsDriverId) {
        String driver = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;
        
        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }
        
        return isFromResources;
    }
        
    public boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    public boolean extractDriverFromResources(String adrenotoolsDriverId, OnExtractFileListener listener) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst, listener);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    /**
     * Install an AdrenoTools ZIP. Extract to a private staging directory first,
     * validate meta.json and the driver library, then move to the live directory.
     * Never leave a half-installed driver visible to the Vulkan selector.
     */
    public String installDriver(Uri driverUri) {
        // Extract into a private staging directory. Never accept unvalidated metadata
        // or allow ZIP paths to escape the driver's directory.
        File staging = new File(adrenotoolsContentDir,
                ".import-" + Long.toHexString(System.nanoTime()));
        final long maxUnpackedBytes = 1024L * 1024L * 1024L;
        final long maxFileBytes = 512L * 1024L * 1024L;
        try {
            if (!staging.mkdirs()) throw new IOException("Cannot create temporary driver directory");
            String safeRoot = staging.getCanonicalPath() + File.separator;
            long unpacked = 0;
            int entryCount = 0;
            try (InputStream input = mContext.getContentResolver().openInputStream(driverUri)) {
                if (input == null) throw new IOException("Driver archive cannot be opened");
                try (ZipInputStream zip = new ZipInputStream(input)) {
                    ZipEntry entry;
                    byte[] buffer = new byte[16384];
                    while ((entry = zip.getNextEntry()) != null) {
                        if (++entryCount > 4096) throw new IOException("Too many driver ZIP entries");
                        String entryName = entry.getName();
                        if (entryName == null || entryName.isEmpty()
                                || entryName.startsWith("/") || entryName.indexOf('\\') >= 0) {
                            throw new IOException("Unsafe driver ZIP path");
                        }
                        File dstFile = new File(staging, entryName).getCanonicalFile();
                        if (!dstFile.getPath().startsWith(safeRoot)) {
                            throw new IOException("Driver ZIP entry escapes installation directory");
                        }
                        if (entry.isDirectory()) {
                            if (!dstFile.isDirectory() && !dstFile.mkdirs())
                                throw new IOException("Cannot create driver subdirectory");
                        } else {
                            File parent = dstFile.getParentFile();
                            if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
                                throw new IOException("Cannot create driver archive parent");
                            long fileBytes = 0;
                            try (java.io.FileOutputStream output = new java.io.FileOutputStream(dstFile)) {
                                int read;
                                while ((read = zip.read(buffer)) != -1) {
                                    fileBytes += read;
                                    unpacked += read;
                                    if (fileBytes > maxFileBytes || unpacked > maxUnpackedBytes)
                                        throw new IOException("Driver ZIP exceeds extraction limits");
                                    output.write(buffer, 0, read);
                                }
                            }
                        }
                        zip.closeEntry();
                    }
                }
            }
            if (entryCount == 0) throw new IOException("Empty driver ZIP");
            File base = staging;
            if (!new File(base, "meta.json").isFile()) {
                File[] contents = staging.listFiles();
                if (contents != null && contents.length == 1 && contents[0].isDirectory()
                        && new File(contents[0], "meta.json").isFile()) {
                    base = contents[0];
                }
            }

            File manifest = new File(base, "meta.json");
            if (!manifest.isFile()) throw new IOException("Missing AdrenoTools meta.json");
            JSONObject metadata = new JSONObject(FileUtils.readString(manifest));
            if ("freedreno".equalsIgnoreCase(metadata.optString("type"))
                    || metadata.optString("libraryName").startsWith("libGL.so"))
                throw new IOException("Freedreno OpenGL packages must be imported through Components");
            String name = metadata.optString("name", "").trim();
            String libName = metadata.optString("libraryName", "").trim();
            if (name.isEmpty() || libName.isEmpty() || name.equals(".")
                    || name.equals("..") || name.indexOf('/') >= 0
                    || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0) {
                throw new IOException("Invalid AdrenoTools metadata");
            }
            File library = new File(base, libName).getCanonicalFile();
            if (!library.getPath().startsWith(base.getCanonicalPath() + File.separator)
                    || !library.isFile()) {
                throw new IOException("Driver library is missing or outside the package");
            }

            File destination = new File(adrenotoolsContentDir, name);
            if (destination.exists()) throw new IOException("Driver is already installed");
            if (!base.renameTo(destination)) throw new IOException("Cannot finalize driver installation");
            return name;
        } catch (Exception e) {
            Log.e("AdrenotoolsManager", "Local driver import failed", e);
            return "";
        } finally {
            FileUtils.delete(staging);
        }
    }

    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        boolean isFromResources = isFromResources(adrenotoolsDriverId);

        if (isFromResources || enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
            String driverPath = getDriverPath(adrenotoolsDriverId);

            if (!getLibraryName(adrenotoolsDriverId).equals("")) {
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", getLibraryName(adrenotoolsDriverId));

                File winlatorDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
                File qglConfig = new File(winlatorDir, "qgl_config.txt");
                if (qglConfig.exists())
                    envVars.put("ADRENOTOOLS_REDIRECT_DIR", winlatorDir.getAbsolutePath() + "/");
            }
        }
    }
 }
