package com.winlator.cmod.core;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import com.ludashi.benchmark.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.xenvironment.ImageFs;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* loaded from: classes10.dex */
public class WineInfo implements Parcelable {
    private String arch;
    public final String path;
    public String subversion;
    public final String type;
    public final String version;
    public static final WineInfo MAIN_WINE_VERSION = new WineInfo("proton", "9.0", "x86_64");
    private static final Pattern pattern = Pattern.compile("^(wine|proton)\\-([0-9\\.]+)\\-?([0-9\\.]+)?\\-(x86|x86_64|arm64ec)$");
    public static final Parcelable.Creator<WineInfo> CREATOR = new Parcelable.Creator<WineInfo>() { // from class: com.winlator.cmod.core.WineInfo.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public WineInfo createFromParcel(Parcel in) {
            return new WineInfo(in);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public WineInfo[] newArray(int size) {
            return new WineInfo[size];
        }
    };

    public WineInfo(String type, String version, String arch) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = null;
    }

    public WineInfo(String type, String version, String subversion, String arch, String path) {
        this.type = type;
        this.version = version;
        this.subversion = (subversion == null || subversion.isEmpty()) ? null : subversion;
        this.arch = arch;
        this.path = path;
    }

    public WineInfo(String type, String version, String arch, String path) {
        this.type = type;
        this.version = version;
        this.arch = arch;
        this.path = path;
    }

    private WineInfo(Parcel in) {
        this.type = in.readString();
        this.version = in.readString();
        this.subversion = in.readString();
        this.arch = in.readString();
        this.path = in.readString();
    }

    public String getArch() {
        return this.arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public boolean isWin64() {
        return this.arch.equals("x86_64") || this.arch.equals("arm64ec");
    }

    public boolean isArm64EC() {
        return this.arch.equals("arm64ec");
    }

    public String identifier() {
        if (this.type.equals("proton")) {
            return "proton-" + fullVersion() + "-" + this.arch;
        }
        return "wine-" + fullVersion() + "-" + this.arch;
    }

    public String fullVersion() {
        return this.version + (this.subversion != null ? "-" + this.subversion : "");
    }

    public String toString() {
        if (this.type.equals("proton")) {
            return "Proton " + fullVersion() + (this != MAIN_WINE_VERSION ? "" : " (Custom)");
        }
        return "Wine " + fullVersion() + (this != MAIN_WINE_VERSION ? "" : " (Custom)");
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.type);
        dest.writeString(this.version);
        dest.writeString(this.subversion);
        dest.writeString(this.arch);
        dest.writeString(this.path);
    }

    public static WineInfo fromIdentifier(Context context, ContentsManager contentsManager, String identifier) {
        ImageFs imageFs = ImageFs.find(context);
        String path = "";
        Log.d("WineInfo", "Creating WineInfo from identifier " + identifier);
        if (identifier.equals(MAIN_WINE_VERSION.identifier())) {
            return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());
        }
        ContentProfile wineProfile = contentsManager.getProfileByEntryName(identifier);
        int i = 0;
        if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            identifier = identifier.substring(0, identifier.length() - 2).toLowerCase();
        }
        Matcher matcher = pattern.matcher(identifier);
        if (matcher.find()) {
            String[] wineVersions = context.getResources().getStringArray(R.array.wine_entries);
            int length = wineVersions.length;
            while (true) {
                if (i >= length) {
                    break;
                }
                String wineVersion = wineVersions[i];
                if (!wineVersion.contains(identifier)) {
                    i++;
                } else {
                    path = imageFs.getRootDir().getPath() + "/opt/" + identifier;
                    break;
                }
            }
            if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
                path = ContentsManager.getInstallDir(context, wineProfile).getPath();
            }
            return new WineInfo(matcher.group(1), matcher.group(2), matcher.group(4), path);
        }
        return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null || wineVersion.equals(MAIN_WINE_VERSION.identifier());
    }
}
