package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

public final class WineRuntimeGuard {
    private WineRuntimeGuard() {}

    public static String getContainerUsing(Context context, String runtimeIdentifier) {
        if (runtimeIdentifier == null || runtimeIdentifier.isEmpty()) return null;
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            if (runtimeIdentifier.equals(container.getWineVersion())) return container.getName();
        }
        return null;
    }

    public static boolean isInUse(Context context, String runtimeIdentifier) {
        return getContainerUsing(context, runtimeIdentifier) != null;
    }

    public static boolean isBundledMainInstalled(Context context) {
        if (!android.os.Process.is64Bit()) return Arm32RuntimeInstaller.isInstalled(context);
        String identifier = WineInfo.MAIN_WINE_VERSION.identifier();
        if (ProtonPackageManager.isKnownPackage(identifier)) {
            return ProtonPackageManager.isInstalled(context, identifier);
        }
        File runtime = new File(ImageFs.find(context).getRootDir(), "opt/" + identifier);
        File[] files = runtime.listFiles();
        return runtime.isDirectory() && files != null && files.length > 0;
    }

    public static boolean removeBundledMain(Context context) {
        if (!android.os.Process.is64Bit()) return false;
        String identifier = WineInfo.MAIN_WINE_VERSION.identifier();
        if (isInUse(context, identifier)) return false;
        File runtime = new File(ImageFs.find(context).getRootDir(), "opt/" + identifier);
        return !runtime.exists() || FileUtils.delete(runtime);
    }

    public static String identifierFor(ContentProfile profile) {
        return profile == null ? "" : ContentsManager.getEntryName(profile);
    }

    public static boolean canRemove(Context context, ContentProfile profile) {
        if (profile == null) return true;
        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE
                && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) return true;
        return !isInUse(context, identifierFor(profile));
    }
}
