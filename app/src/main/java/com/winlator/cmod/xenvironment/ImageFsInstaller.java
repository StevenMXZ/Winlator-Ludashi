package com.winlator.cmod.xenvironment;

import android.content.Context;
import com.ludashi.benchmark.R;
import com.winlator.cmod.MainActivity;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import java.io.File;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/* loaded from: classes12.dex */
public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 21;

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        Iterator<Container> it = manager.getContainers().iterator();
        while (it.hasNext()) {
            Container container = it.next();
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(imgVersion) <= 5) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }
            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    public static void installWineFromAssets(MainActivity activity) {
        String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
        File rootDir = ImageFs.find(activity).getRootDir();
        for (String version : versions) {
            File outFile = new File(rootDir, "/opt/" + version);
            outFile.mkdirs();
            TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, version + ".txz", outFile);
        }
    }

    public static void installDriversFromAssets(MainActivity activity) {
        AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(activity);
        String[] adrenotoolsAssetDrivers = activity.getResources().getStringArray(R.array.wrapper_graphics_driver_version_entries);
        for (String driver : adrenotoolsAssetDrivers) {
            adrenotoolsManager.extractDriverFromResources(driver);
        }
    }

    public static void installFromAssets(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        final ImageFs imageFs = ImageFs.find(activity);
        final File rootDir = imageFs.getRootDir();
        SettingsFragment.resetEmulatorsVersion(activity);
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.xenvironment.ImageFsInstaller$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ImageFsInstaller.lambda$installFromAssets$2(rootDir, activity, dialog, imageFs);
            }
        });
    }

    static /* synthetic */ void lambda$installFromAssets$2(File rootDir, final MainActivity activity, final DownloadProgressDialog dialog, ImageFs imageFs) {
        clearRootDir(rootDir);
        final long contentLength = (long) (FileUtils.getSize(activity, "imagefs.txz") * 4.5454545f);
        final AtomicLong totalSizeRef = new AtomicLong();
        boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, "imagefs.txz", rootDir, new OnExtractFileListener() { // from class: com.winlator.cmod.xenvironment.ImageFsInstaller$$ExternalSyntheticLambda2
            @Override // com.winlator.cmod.core.OnExtractFileListener
            public final File onExtractFile(File file, long j) {
                return ImageFsInstaller.lambda$installFromAssets$1(totalSizeRef, contentLength, activity, dialog, file, j);
            }
        });
        if (success) {
            installWineFromAssets(activity);
            installDriversFromAssets(activity);
            imageFs.createImgVersionFile(21);
            FileUtils.symlink("libSDL2-2.0.so", new File(imageFs.getLibDir(), "libSDL2-2.0.so.0").getAbsolutePath());
            resetContainerImgVersions(activity);
        } else {
            AppUtils.showToast(activity, R.string.unable_to_install_system_files);
        }
        dialog.closeOnUiThread();
    }

    static /* synthetic */ File lambda$installFromAssets$1(AtomicLong totalSizeRef, long contentLength, MainActivity activity, final DownloadProgressDialog dialog, File file, long size) {
        if (size > 0) {
            long totalSize = totalSizeRef.addAndGet(size);
            final int progress = (int) ((totalSize / contentLength) * 100.0f);
            activity.runOnUiThread(new Runnable() { // from class: com.winlator.cmod.xenvironment.ImageFsInstaller$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    DownloadProgressDialog.this.setProgress(progress);
                }
            });
        }
        return file;
    }

    public static void installIfNeeded(MainActivity activity) {
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < 21) {
            installFromAssets(activity);
        }
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.getName().equals("installed-wine")) {
                    FileUtils.delete(file);
                }
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        int i;
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                int length = files.length;
                while (i < length) {
                    File file = files[i];
                    if (file.isDirectory()) {
                        String name = file.getName();
                        i = name.equals("home") ? i + 1 : 0;
                    }
                    FileUtils.delete(file);
                }
                return;
            }
            return;
        }
        rootDir.mkdirs();
    }
}
