package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.util.JsonReader;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes14.dex */
public class InputControlsManager {
    private final Context context;
    private int maxProfileId;
    private ArrayList<ControlsProfile> profiles;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) {
            profilesDir.mkdir();
        }
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!this.profilesLoaded) {
            loadProfiles(ignoreTemplates);
        }
        return this.profiles;
    }

    private void copyAssetProfilesIfNeeded() {
        AssetManager assetManager;
        File profilesDir;
        String[] assetFiles;
        SharedPreferences preferences;
        File profilesDir2 = getProfilesDir(this.context);
        if (FileUtils.isEmpty(profilesDir2)) {
            FileUtils.copy(this.context, "inputcontrols/profiles", profilesDir2);
            return;
        }
        SharedPreferences preferences2 = PreferenceManager.getDefaultSharedPreferences(this.context);
        int newVersion = AppUtils.getVersionCode(this.context);
        int i = 0;
        int oldVersion = preferences2.getInt("inputcontrols_app_version", 0);
        if (oldVersion == newVersion) {
            return;
        }
        preferences2.edit().putInt("inputcontrols_app_version", newVersion).apply();
        File[] files = profilesDir2.listFiles();
        if (files == null) {
            return;
        }
        try {
            AssetManager assetManager2 = this.context.getAssets();
            String[] assetFiles2 = assetManager2.list("inputcontrols/profiles");
            int length = assetFiles2.length;
            int i2 = 0;
            while (i2 < length) {
                String assetFile = assetFiles2[i2];
                String assetPath = "inputcontrols/profiles/" + assetFile;
                ControlsProfile originProfile = loadProfile(this.context, assetManager2.open(assetPath));
                File targetFile = null;
                int length2 = files.length;
                while (true) {
                    if (i >= length2) {
                        assetManager = assetManager2;
                        profilesDir = profilesDir2;
                        assetFiles = assetFiles2;
                        preferences = preferences2;
                        break;
                    }
                    File file = files[i];
                    assetManager = assetManager2;
                    profilesDir = profilesDir2;
                    try {
                        ControlsProfile targetProfile = loadProfile(this.context, file);
                        assetFiles = assetFiles2;
                        preferences = preferences2;
                        try {
                            if (originProfile.id != targetProfile.id || !originProfile.getName().equals(targetProfile.getName())) {
                                i++;
                                assetManager2 = assetManager;
                                assetFiles2 = assetFiles;
                                profilesDir2 = profilesDir;
                                preferences2 = preferences;
                            } else {
                                targetFile = file;
                                break;
                            }
                        } catch (IOException e) {
                            return;
                        }
                    } catch (IOException e2) {
                        return;
                    }
                }
                if (targetFile != null) {
                    FileUtils.copy(this.context, assetPath, targetFile);
                }
                i2++;
                assetManager2 = assetManager;
                assetFiles2 = assetFiles;
                profilesDir2 = profilesDir;
                preferences2 = preferences;
                i = 0;
            }
        } catch (IOException e3) {
        }
    }

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = getProfilesDir(this.context);
        copyAssetProfilesIfNeeded();
        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(this.context, file);
                if (!ignoreTemplates || !profile.isTemplate()) {
                    profiles.add(profile);
                }
                this.maxProfileId = Math.max(this.maxProfileId, profile.id);
            }
        }
        Collections.sort(profiles);
        this.profiles = profiles;
        this.profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        Context context = this.context;
        int i = this.maxProfileId + 1;
        this.maxProfileId = i;
        ControlsProfile profile = new ControlsProfile(context, i);
        profile.setName(name);
        profile.save();
        this.profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        String newName;
        int i = 1;
        while (true) {
            newName = source.getName() + " (" + i + ")";
            boolean found = false;
            Iterator<ControlsProfile> it = this.profiles.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                if (it.next().getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
            i++;
        }
        int i2 = this.maxProfileId;
        int newId = i2 + 1;
        this.maxProfileId = newId;
        File newFile = ControlsProfile.getProfileFile(this.context, newId);
        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(this.context, source.id)));
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) {
                data.remove("template");
            }
            FileUtils.writeString(newFile, data.toString());
        } catch (JSONException e) {
        }
        ControlsProfile profile = loadProfile(this.context, newFile);
        this.profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(this.context, profile.id);
        if (!file.isFile() || !file.delete()) {
            return;
        }
        this.profiles.remove(profile);
    }

    public ControlsProfile importProfile(JSONObject data) {
        try {
            if (data.has("id") && data.has("name")) {
                int newId = this.maxProfileId + 1;
                this.maxProfileId = newId;
                File newFile = ControlsProfile.getProfileFile(this.context, newId);
                data.put("id", newId);
                FileUtils.writeString(newFile, data.toString());
                ControlsProfile newProfile = loadProfile(this.context, newFile);
                int foundIndex = -1;
                int i = 0;
                while (true) {
                    if (i >= this.profiles.size()) {
                        break;
                    }
                    ControlsProfile profile = this.profiles.get(i);
                    if (!profile.getName().equals(newProfile.getName())) {
                        i++;
                    } else {
                        foundIndex = i;
                        break;
                    }
                }
                if (foundIndex != -1) {
                    this.profiles.set(foundIndex, newProfile);
                } else {
                    this.profiles.add(newProfile);
                }
                return newProfile;
            }
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        File destination;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this.context);
        String winlatorPath = sp.getString("winlator_path_uri", null);
        if (winlatorPath != null) {
            Uri winlatorUri = Uri.parse(winlatorPath);
            destination = new File(FileUtils.getFilePathFromUri(this.context, winlatorUri), "profiles/" + profile.getName() + ".icp");
        } else {
            destination = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "profiles/" + profile.getName() + ".icp");
        }
        FileUtils.copy(ControlsProfile.getProfileFile(this.context, profile.id), destination);
        MediaScannerConnection.scanFile(this.context, new String[]{destination.getAbsolutePath()}, null, null);
        if (destination.isFile()) {
            return destination;
        }
        return null;
    }

    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try {
            JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8));
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;
            int fieldsRead = 0;
            try {
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.equals("id")) {
                        profileId = reader.nextInt();
                        fieldsRead++;
                    } else if (name.equals("name")) {
                        profileName = reader.nextString();
                        fieldsRead++;
                    } else if (name.equals("cursorSpeed")) {
                        cursorSpeed = (float) reader.nextDouble();
                        fieldsRead++;
                    } else {
                        if (fieldsRead == 3) {
                            break;
                        }
                        reader.skipValue();
                    }
                }
                ControlsProfile profile = new ControlsProfile(context, profileId);
                profile.setName(profileName);
                profile.setCursorSpeed(cursorSpeed);
                reader.close();
                return profile;
            } finally {
            }
        } catch (IOException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        Iterator<ControlsProfile> it = getProfiles().iterator();
        while (it.hasNext()) {
            ControlsProfile profile = it.next();
            if (profile.id == id) {
                return profile;
            }
        }
        return null;
    }
}
