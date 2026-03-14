package com.winlator.cmod.xenvironment;

import android.content.Context;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/* loaded from: classes12.dex */
public class ImageFs {
    public static final String CACHE_PATH = "/home/xuser/.cache";
    public static final String CONFIG_PATH = "/home/xuser/.config";
    public static final String HOME_PATH = "/home/xuser";
    public static final String USER = "xuser";
    public static final String WINEPREFIX = "/home/xuser/.wine";
    public String cache_path;
    public String config_path;
    public String home_path;
    private final File rootDir;
    public String winePath;
    public String wineprefix;

    private ImageFs(File rootDir) {
        this.rootDir = rootDir;
        this.winePath = rootDir + "/opt/" + WineInfo.MAIN_WINE_VERSION.identifier();
        this.home_path = rootDir + HOME_PATH;
        this.cache_path = rootDir + CACHE_PATH;
        this.config_path = rootDir + CONFIG_PATH;
        this.wineprefix = rootDir + WINEPREFIX;
    }

    public static ImageFs find(Context context) {
        return new ImageFs(new File(context.getFilesDir(), "imagefs"));
    }

    public static ImageFs find(File rootDir) {
        return new ImageFs(rootDir);
    }

    public File getRootDir() {
        return this.rootDir;
    }

    public boolean isValid() {
        return this.rootDir.isDirectory() && getImgVersionFile().exists();
    }

    public int getVersion() {
        File imgVersionFile = getImgVersionFile();
        if (imgVersionFile.exists()) {
            return Integer.parseInt(FileUtils.readLines(imgVersionFile).get(0));
        }
        return 0;
    }

    public String getFormattedVersion() {
        return String.format(Locale.ENGLISH, "%.1f", Float.valueOf(getVersion()));
    }

    public void createImgVersionFile(int version) {
        getConfigDir().mkdirs();
        File file = getImgVersionFile();
        try {
            file.createNewFile();
            FileUtils.writeString(file, String.valueOf(version));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getWinePath() {
        return this.winePath;
    }

    public void setWinePath(String winePath) {
        this.winePath = winePath;
    }

    public File getConfigDir() {
        return new File(this.rootDir, ".winlator");
    }

    public File getImgVersionFile() {
        return new File(getConfigDir(), ".img_version");
    }

    public File getInstalledWineDir() {
        return new File(this.rootDir, "/opt/installed-wine");
    }

    public File getTmpDir() {
        return new File(this.rootDir, "/usr/tmp");
    }

    public File getLibDir() {
        return new File(this.rootDir, "/usr/lib");
    }

    public File getBinDir() {
        return new File(this.rootDir, "/usr/bin");
    }

    public File getShareDir() {
        return new File(this.rootDir, "/usr/share");
    }

    public File getEtcDir() {
        return new File(this.rootDir, "/usr/etc");
    }

    public String toString() {
        return this.rootDir.getPath();
    }
}
