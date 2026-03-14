package com.winlator.cmod.contents;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes15.dex */
public class AdrenotoolsManager {
    private File adrenotoolsContentDir;
    private Context mContext;

    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(this.mContext.getFilesDir(), "contents/adrenotools");
        if (!this.adrenotoolsContentDir.exists()) {
            this.adrenotoolsContentDir.mkdirs();
        }
    }

    public String getLibraryName(String adrenoToolsDriverId) {
        File driverPath = new File(this.adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            String libraryName = jsonObject.getString("libraryName");
            return libraryName;
        } catch (JSONException e) {
            return "";
        }
    }

    public String getDriverName(String adrenoToolsDriverId) {
        File driverPath = new File(this.adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            String driverName = jsonObject.getString("name");
            return driverName;
        } catch (JSONException e) {
            return "";
        }
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        File driverPath = new File(this.adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            String driverVersion = jsonObject.getString("driverVersion");
            return driverVersion;
        } catch (JSONException e) {
            return "";
        }
    }

    public String getDriverPath(String adrenotoolsDriverId) {
        return this.adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
    }

    private void reloadContainers(String adrenoToolsDriverId) {
        ContainerManager containerManager = new ContainerManager(this.mContext);
        Iterator<Container> it = containerManager.getContainers().iterator();
        while (true) {
            boolean hasNext = it.hasNext();
            String str = DefaultVersion.WRAPPER;
            if (!hasNext) {
                break;
            }
            Container container = it.next();
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            Log.d("AdrenotoolsManager", "Checking if container driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
                if (GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, this.mContext)) {
                    str = DefaultVersion.WRAPPER_ADRENO;
                }
                config.put("version", str);
                container.setGraphicsDriverConfig(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                container.saveData();
            }
        }
        Iterator<Shortcut> it2 = containerManager.loadShortcuts().iterator();
        while (it2.hasNext()) {
            Shortcut shortcut = it2.next();
            HashMap<String, String> config2 = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
            Log.d("AdrenotoolsManager", "Checking if shortcut driver version " + config2.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config2.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for shortcut " + shortcut.name);
                config2.put("version", GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, this.mContext) ? DefaultVersion.WRAPPER_ADRENO : DefaultVersion.WRAPPER);
                shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfigDialog.toGraphicsDriverConfig(config2));
                shortcut.saveData();
            }
        }
    }

    public void removeDriver(String adrenoToolsDriverId) {
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(this.adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        for (File f : this.adrenotoolsContentDir.listFiles()) {
            boolean fromResources = isFromResources(f.getName());
            if (!fromResources && new File(f, "meta.json").exists()) {
                driversList.add(f.getName());
            }
        }
        return driversList;
    }

    public boolean isFromResources(String adrenotoolsDriverId) {
        String driver = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        AssetManager am = this.mContext.getResources().getAssets();
        try {
            InputStream is = am.open(driver);
            is.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        File dst = new File(this.adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists()) {
            return true;
        }
        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        boolean hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this.mContext, src, dst);
        if (!hasExtracted) {
            dst.delete();
        }
        return hasExtracted;
    }

    public String installDriver(Uri driverUri) {
        File tmpDir = new File(this.adrenotoolsContentDir, "tmp");
        if (tmpDir.exists()) {
            tmpDir.delete();
        }
        tmpDir.mkdirs();
        String name = "";
        try {
            InputStream is = this.mContext.getContentResolver().openInputStream(driverUri);
            ZipInputStream zis = new ZipInputStream(is);
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                File dstFile = new File(tmpDir, entry.getName());
                Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            zis.close();
            if (new File(tmpDir, "meta.json").exists()) {
                name = getDriverName(tmpDir.getName());
                File dst = new File(this.adrenotoolsContentDir, name);
                if (!dst.exists() && !name.equals("")) {
                    tmpDir.renameTo(dst);
                } else {
                    name = "";
                    FileUtils.delete(tmpDir);
                }
            } else {
                Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
                tmpDir.delete();
            }
        } catch (IOException e) {
            Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
            tmpDir.delete();
        }
        return name;
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
                if (qglConfig.exists()) {
                    envVars.put("ADRENOTOOLS_REDIRECT_DIR", winlatorDir.getAbsolutePath() + "/");
                }
            }
        }
    }
}
