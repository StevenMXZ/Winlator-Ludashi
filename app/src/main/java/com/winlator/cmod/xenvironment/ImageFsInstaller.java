package com.winlator.cmod.xenvironment;

import android.content.Context;
import android.os.SystemClock;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 22;
    
    public abstract interface onInstallationFinish {
        public void call();
    }

    public interface InstallationProgressListener {
        void onProgress(int progress);
        void onFinished(boolean success);
    }

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    private static void installOptionalGoldberg(Context context) {
        Thread worker = new Thread(() -> {
            try {
                com.winlator.cmod.steam.SteamGoldberg.INSTANCE.ensureInstalled(context.getApplicationContext());
            } catch (Exception error) {
                android.util.Log.w("ImageFsInstaller", "Optional Goldberg installation failed", error);
            }
        }, "GoldbergComponentInstall");
        worker.start();
    }

    public static boolean installWineArchive(final Context context, String version, File archiveFile) {
        File rootDir = ImageFs.find(context).getRootDir();
        File outFile = new File(rootDir, "opt/" + version);
        FileUtils.delete(outFile);
        outFile.mkdirs();
        boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archiveFile, outFile);
        if (!success) success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archiveFile, outFile);
        if (!success) FileUtils.delete(outFile);
        return success;
    }

    public static void installWineFromAssets(final DownloadProgressDialog dialog, final AppCompatActivity activity) {
        String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
        File rootDir = ImageFs.find(activity).getRootDir();
        final byte compressionRatio = 22;

        if (dialog != null) activity.runOnUiThread(() -> dialog.setMessage(R.string.installing_wine_files));

        for (String version : versions) {
            File outFile = new File(rootDir, "opt/" + version);
            outFile.mkdirs();
            final long contentLength = (long)(FileUtils.getSize(activity, version + ".tar.zst") * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();

            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, version + ".tar.zst", outFile, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    if (dialog != null) activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });
         }
    }

    public static void installDriversFromAssets(final DownloadProgressDialog dialog, final AppCompatActivity activity) {
        
        if (dialog != null) activity.runOnUiThread(() -> dialog.setMessage(R.string.installing_drivers_files));
        AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(activity);
        String[] adrenotoolsAssetDrivers = activity.getResources().getStringArray(R.array.wrapper_graphics_driver_version_entries);

        for (String driver : adrenotoolsAssetDrivers) {
            if (driver.equalsIgnoreCase("System") || !adrenotoolsManager.isFromResources(driver))
                continue;
            final byte compressionRatio = 22;
            final long contentLength = (long)(FileUtils.getSize(activity, adrenotoolsManager.getAssetPath(driver)) * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();
            adrenotoolsManager.extractDriverFromResources(driver, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    if (dialog != null) activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });
         }   
    }

    public static void installFromAssets(final MainActivity activity, onInstallationFinish callback) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final byte compressionRatio = 22;
            final long contentLength = (long)(FileUtils.getSize(activity, "imagefs.tar.zst") * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "imagefs.tar.zst", rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                installWineFromAssets(dialog, activity);
                installDriversFromAssets(dialog, activity);
                installOptionalGoldberg(activity);
                imageFs.createImgVersionFile(LATEST_VERSION);
                FileUtils.symlink("libSDL2-2.0.so", new File(imageFs.getLibDir(), "libSDL2-2.0.so.0").getAbsolutePath());
                resetContainerImgVersions(activity);
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);
            
            dialog.closeOnUiThread();
            activity.runOnUiThread(() -> {if (callback != null) callback.call();});
        });
    }

    public static void installFromAssetsSilently(final AppCompatActivity activity,
                                                   final InstallationProgressListener listener) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();
        SettingsFragment.resetEmulatorsVersion(activity);

        Executors.newSingleThreadExecutor().execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            clearRootDir(rootDir);
            final byte compressionRatio = 22;
            final long contentLength = (long)(FileUtils.getSize(activity, "imagefs.tar.zst") * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();
            AtomicLong lastProgressDispatch = new AtomicLong(0L);
            AtomicInteger lastPublishedProgress = new AtomicInteger(-1);

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity,
                    "imagefs.tar.zst", rootDir, (file, size) -> {
                        if (size > 0 && contentLength > 0) {
                            long totalSize = totalSizeRef.addAndGet(size);
                            int progress = Math.min(75, (int)(((float)totalSize / contentLength) * 75));
                            long now = SystemClock.uptimeMillis();
                            int previous = lastPublishedProgress.get();
                            if (progress > previous
                                    && (progress >= 75 || progress - previous >= 2)
                                    && now - lastProgressDispatch.get() >= 120L) {
                                lastProgressDispatch.set(now);
                                lastPublishedProgress.set(progress);
                                if (listener != null) {
                                    activity.runOnUiThread(() -> listener.onProgress(progress));
                                }
                            }
                        }
                        return file;
                    });

            if (success) {
                installWineFromAssets(null, activity);
                if (listener != null) activity.runOnUiThread(() -> listener.onProgress(88));
                installDriversFromAssets(null, activity);
                installOptionalGoldberg(activity);
                if (listener != null) activity.runOnUiThread(() -> listener.onProgress(96));
                imageFs.createImgVersionFile(LATEST_VERSION);
                FileUtils.symlink("libSDL2-2.0.so",
                        new File(imageFs.getLibDir(), "libSDL2-2.0.so.0").getAbsolutePath());
                resetContainerImgVersions(activity);
            } else {
                AppUtils.showToast(activity, R.string.unable_to_install_system_files);
            }

            final boolean completed = success;
            activity.runOnUiThread(() -> {
                if (listener != null && completed) listener.onProgress(100);
                if (listener != null) listener.onFinished(completed);
            });
        });
    }

    public static boolean installIfNeeded(final MainActivity activity, onInstallationFinish callback) {
        ImageFs imageFs = ImageFs.find(activity);
        
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) {
            installFromAssets(activity, callback);
            return true;
        }    
        
        return false;
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) {
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }
}