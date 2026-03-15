package com.winlator.cmod.container;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import com.winlator.cmod.core.FileUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes14.dex */
public class Shortcut {
    private static final String COVER_ART_DIR = "app_data/cover_arts/";
    public final Container container;
    private Bitmap coverArt;
    private String customCoverArtPath;
    private final JSONObject extraData = new JSONObject();
    public final File file;
    public Bitmap icon;
    public File iconFile;
    public final String name;
    public final String path;
    public final String wmClass;

    /* JADX WARN: Removed duplicated region for block: B:66:0x01bc  */
    /* JADX WARN: Removed duplicated region for block: B:70:0x01cd  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public Shortcut(com.winlator.cmod.container.Container r21, java.io.File r22) {
        /*
            Method dump skipped, instructions count: 482
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.container.Shortcut.<init>(com.winlator.cmod.container.Container, java.io.File):void");
    }

    private void loadCoverArt() {
        if (this.customCoverArtPath != null && !this.customCoverArtPath.isEmpty()) {
            File customCoverArtFile = new File(this.customCoverArtPath);
            if (customCoverArtFile.isFile()) {
                this.coverArt = BitmapFactory.decodeFile(customCoverArtFile.getPath());
                return;
            }
        }
        File defaultCoverArtFile = new File(COVER_ART_DIR, this.name + ".png");
        if (defaultCoverArtFile.isFile()) {
            this.coverArt = BitmapFactory.decodeFile(defaultCoverArtFile.getPath());
        }
    }

    public Bitmap getCoverArt() {
        return this.coverArt;
    }

    public void setCoverArt(Bitmap coverArt) {
        this.coverArt = coverArt;
    }

    public String getCustomCoverArtPath() {
        return this.customCoverArtPath;
    }

    public void setCustomCoverArtPath(String customCoverArtPath) {
        this.customCoverArtPath = customCoverArtPath;
        putExtra("customCoverArtPath", customCoverArtPath);
        saveData();
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return this.extraData.has(name) ? this.extraData.getString(name) : fallback;
        } catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, String value) {
        try {
            if (value != null) {
                this.extraData.put(name, value);
            } else {
                this.extraData.remove(name);
            }
        } catch (JSONException e) {
        }
    }

    public void saveData() {
        String content = "[Desktop Entry]\n";
        Iterator<String> it = FileUtils.readLines(this.file).iterator();
        while (it.hasNext()) {
            String line = it.next();
            if (line.contains("[Extra Data]")) {
                break;
            } else if (!line.contains("[Desktop Entry]") && !line.isEmpty()) {
                content = content + line + "\n";
            }
        }
        if (this.extraData.length() > 0) {
            content = content + "\n[Extra Data]\n";
            Iterator<String> keys = this.extraData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    content = content + key + "=" + this.extraData.getString(key) + "\n";
                } catch (JSONException e) {
                }
            }
        }
        if (!this.file.getName().endsWith(".desktop")) {
            return;
        }
        FileUtils.writeString(this.file, content);
    }

    public void genUUID() {
        if (getExtra("uuid").equals("")) {
            putExtra("uuid", UUID.randomUUID().toString());
            saveData();
        }
    }

    public void saveCustomCoverArt(Bitmap coverArt) {
        try {
            File coverArtDir = new File(this.container.getRootDir(), COVER_ART_DIR);
            if (!coverArtDir.exists()) {
                coverArtDir.mkdirs();
            }
            File coverFile = new File(coverArtDir, this.name + ".png");
            if (FileUtils.saveBitmapToFile(coverArt, coverFile)) {
                this.coverArt = coverArt;
                setCustomCoverArtPath(coverFile.getPath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void removeCustomCoverArt() {
        if (this.customCoverArtPath != null && !this.customCoverArtPath.isEmpty()) {
            File customCoverArtFile = new File(this.customCoverArtPath);
            if (customCoverArtFile.exists()) {
                customCoverArtFile.delete();
            }
        }
        this.customCoverArtPath = null;
        this.coverArt = null;
        putExtra("customCoverArtPath", null);
        saveData();
    }

    public boolean cloneToContainer(Container newContainer) {
        try {
            File newShortcutFile = new File(newContainer.getDesktopDir(), this.file.getName());
            ArrayList<String> lines = FileUtils.readLines(this.file);
            StringBuilder updatedContent = new StringBuilder();
            boolean containerIdFound = false;
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line.startsWith("container_id:")) {
                    updatedContent.append("container_id:").append(newContainer.id).append("\n");
                    containerIdFound = true;
                } else {
                    updatedContent.append(line).append("\n");
                }
            }
            if (!containerIdFound) {
                updatedContent.append("container_id:").append(newContainer.id).append("\n");
            }
            FileUtils.writeString(newShortcutFile, updatedContent.toString());
            if (this.iconFile != null && this.iconFile.isFile()) {
                File newIconFile = new File(newContainer.getIconsDir(64), this.iconFile.getName());
                FileUtils.copy(this.iconFile, newIconFile);
                return true;
            }
            return true;
        } catch (Exception e) {
            Log.e("Shortcut", "Failed to clone shortcut to new container", e);
            return false;
        }
    }

    public int getContainerId() {
        return this.container.id;
    }

    public String getExecutable() {
        try {
            List<String> lines = Files.readAllLines(this.file.toPath());
            for (String line : lines) {
                if (line.startsWith("Exec")) {
                    String exe = line.substring(line.lastIndexOf("\\") + 1, line.length()).replaceAll("\\s+$", "");
                    return exe;
                }
            }
            return "";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean getNativeRendering() {
        return getExtra("nativeRendering", "0").equals("1");
    }

    public void setNativeRendering(boolean enabled) {
        putExtra("nativeRendering", enabled ? "1" : "0");
        saveData();
    }
}
