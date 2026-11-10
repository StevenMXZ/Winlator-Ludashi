package com.winlator.cmod;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.LocalDriverArchive;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.OpenGLDriverDefaults;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRuntimeGuard;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.ui.onboarding.OnboardingCallbacks;
import com.winlator.cmod.ui.onboarding.OnboardingComponent;
import com.winlator.cmod.ui.onboarding.OnboardingComposeController;
import com.winlator.cmod.ui.onboarding.OnboardingComposeHost;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OnboardingActivity extends AppCompatActivity {
    public static final String PREF_ONBOARDING_COMPLETE = "winz_onboarding_complete";
    public static final String EXTRA_COMPONENT_MANAGER = "component_manager";
    public static final String EXTRA_AUTO_INSTALL_TYPE = "auto_install_type";
    public static final String EXTRA_AUTO_INSTALL_VERSION = "auto_install_version";
    public static final String EXTRA_AUTO_INSTALL_VERSION_CODE = "auto_install_version_code";

    private static final String PREF_INITIAL_WINE = "winz_initial_wine_version";
    private static final String BUNDLED_RUNTIME_ID = "bundled:" + ProtonPackageManager.DEFAULT_IDENTIFIER;
    private static final String BUNDLED_RUNTIME_NAME =
            ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER) != null
                    ? ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER).title
                    : ProtonPackageManager.DEFAULT_IDENTIFIER;
    private static final int REQUEST_STORAGE = 820;
    private static final int REQUEST_NOTIFICATIONS = 821;
    private static final int REQUEST_LOCAL_COMPONENT = 822;
    private static final int REQUEST_ALL_FILES = 823;
    private static final int REQUEST_LOCAL_DRIVER = 824;

    private final OkHttpClient http = new OkHttpClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<ComponentItem> catalog = new ArrayList<>();
    private final ArrayList<RemoteDriverCatalog.Entry> remoteDrivers = new ArrayList<>();

    private SharedPreferences preferences;
    private ContentsManager contentsManager;
    private AdrenotoolsManager adrenotoolsManager;
    private OnboardingComposeController composeController;
    private boolean componentManagerMode;
    private boolean coreReady;
    private int coreProgress;
    private boolean installBusy;
    private boolean finishing;
    private String selectedInitialWine;
    private String pendingInstallType;
    private String pendingInstallVersion;
    private int pendingInstallVersionCode;
    private boolean autoInstallDispatched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();
        adrenotoolsManager = new AdrenotoolsManager(this);

        componentManagerMode = getIntent().getBooleanExtra(EXTRA_COMPONENT_MANAGER, false);
        pendingInstallType = getIntent().getStringExtra(EXTRA_AUTO_INSTALL_TYPE);
        pendingInstallVersion = getIntent().getStringExtra(EXTRA_AUTO_INSTALL_VERSION);
        pendingInstallVersionCode = getIntent().getIntExtra(EXTRA_AUTO_INSTALL_VERSION_CODE, Integer.MIN_VALUE);
        selectedInitialWine = preferences.getString(PREF_INITIAL_WINE, "");

        ImageFs imageFs = ImageFs.find(this);
        coreReady = imageFs.isValid() && imageFs.getVersion() >= ImageFsInstaller.LATEST_VERSION;
        coreProgress = coreReady ? 100 : 0;

        composeController = OnboardingComposeHost.attach(
                this,
                coreReady,
                coreProgress,
                WineRuntimeGuard.isBundledMainInstalled(this),
                WineRuntimeGuard.isInUse(this, WineInfo.MAIN_WINE_VERSION.identifier()),
                componentManagerMode,
                new OnboardingCallbacks() {
                    @Override
                    public void onInstall(@NonNull String componentId) {
                        ComponentItem item = findComponent(componentId);
                        if (item != null) installComponent(item);
                        else if (componentId.startsWith("remote-driver:")) installRemoteDriver(componentId);
                    }

                    @Override
                    public void onRemove(@NonNull String componentId) {
                        requestRemoveComponent(componentId);
                    }

                    @Override
                    public void onInstallBundledRuntime() {
                        installBundledRuntime();
                    }

                    @Override
                    public void onRemoveBundledRuntime() {
                        requestRemoveBundledRuntime();
                    }

                    @Override
                    public void onBrowseLocal() {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQUEST_LOCAL_COMPONENT);
                    }

                    @Override
                    public void onBrowseDriver() {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQUEST_LOCAL_DRIVER);
                    }

                    @Override
                    public void onRuntimeSelected(@NonNull String runtimeIdentifier) {
                        selectedInitialWine = runtimeIdentifier;
                        preferences.edit().putString(PREF_INITIAL_WINE, runtimeIdentifier).apply();
                    }

                    @Override
                    public void onRequestPermissions() {
                        requestStoragePermission();
                    }

                    @Override
                    public void onRetryCore() {
                        if (!coreReady) startCoreInstallation();
                    }

                    @Override
                    public void onCloseComponents() {
                        finish();
                    }
                }
        );

        syncComposeCatalog();
        loadCatalog();
        loadRemoteDrivers();
        if (!coreReady) startCoreInstallation();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void startCoreInstallation() {
        if (installBusy) return;
        ImageFsInstaller.installFromAssetsSilently(this, new ImageFsInstaller.InstallationProgressListener() {
            @Override
            public void onProgress(int progress) {
                coreProgress = Math.max(0, Math.min(100, progress));
                composeController.updateCore(coreReady, coreProgress);
            }

            @Override
            public void onFinished(boolean success) {
                coreReady = success;
                coreProgress = success ? 100 : 0;
                composeController.updateCore(coreReady, coreProgress);
                refreshBundledRuntimeState();
                syncComposeCatalog();
                if (!success) {
                    Toast.makeText(OnboardingActivity.this,
                            "WinZ core installation failed. Tap retry to try again.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void refreshBundledRuntimeState() {
        if (composeController == null) return;
        composeController.updateBundledRuntime(
                WineRuntimeGuard.isBundledMainInstalled(this),
                WineRuntimeGuard.isInUse(this, WineInfo.MAIN_WINE_VERSION.identifier())
        );
    }

    private void installBundledRuntime() {
        if (installBusy || !coreReady) return;
        installBusy = true;
        composeController.setInstallBusy(BUNDLED_RUNTIME_ID, true);
        composeController.updateInstallProgress("Installing " + BUNDLED_RUNTIME_NAME, -1);
        io.execute(() -> {
            boolean success;
            try {
                ImageFsInstaller.installWineFromAssets(null, OnboardingActivity.this);
                success = WineRuntimeGuard.isBundledMainInstalled(OnboardingActivity.this);
            } catch (Exception error) {
                success = false;
            }
            final boolean installed = success;
            runOnUiThread(() -> {
                installBusy = false;
                composeController.setInstallBusy(null, false);
                refreshBundledRuntimeState();
                syncComposeCatalog();
                if (!installed) Toast.makeText(this, "Unable to install " + BUNDLED_RUNTIME_NAME + ".", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void requestRemoveBundledRuntime() {
        String using = WineRuntimeGuard.getContainerUsing(this, WineInfo.MAIN_WINE_VERSION.identifier());
        if (using != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Proton is in use")
                    .setMessage(BUNDLED_RUNTIME_NAME + " cannot be deleted because it is used by " + using + ".")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + BUNDLED_RUNTIME_NAME + "?")
                .setMessage("The bundled Proton files will be removed. You can install them again later.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (dialog, which) -> removeBundledRuntime())
                .show();
    }

    private void removeBundledRuntime() {
        if (installBusy) return;
        installBusy = true;
        composeController.setInstallBusy(BUNDLED_RUNTIME_ID, true);
        io.execute(() -> {
            boolean removed = WineRuntimeGuard.removeBundledMain(this);
            runOnUiThread(() -> {
                installBusy = false;
                composeController.setInstallBusy(null, false);
                refreshBundledRuntimeState();
                if (!removed) Toast.makeText(this, BUNDLED_RUNTIME_NAME + " could not be deleted.", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void loadCatalog() {
        io.execute(() -> {
            try {
                String url = preferences.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
                try (Response response = http.newCall(new Request.Builder().url(url).build()).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        contentsManager.setRemoteProfiles(response.body().string());
                    }
                }
            } catch (Exception ignored) {
            }
            contentsManager.syncContents();
            rebuildCatalog();
            runOnUiThread(() -> {
                syncComposeCatalog();
                maybeAutoInstall();
            });
        });
    }

    private void rebuildCatalog() {
        ArrayList<ComponentItem> rebuilt = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, ComponentItem> newest = new HashMap<>();

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            for (ContentProfile profile : contentsManager.getProfiles(type)) {
                String typeName = displayType(type);
                if (typeName.isEmpty() || profile.verName == null || profile.verName.isEmpty()) continue;
                ComponentItem item = new ComponentItem();
                item.profile = profile;
                item.type = typeName;
                item.name = profile.verName;
                item.versionCode = profile.verCode;
                item.url = profile.remoteUrl;
                item.installed = isInstalled(profile);
                item.entryName = item.installed ? installedEntryName(type, profile) : "";
                String key = typeName + ":" + profile.verCode + ":" + profile.verName;
                if (!seen.add(key)) continue;
                rebuilt.add(item);
                ComponentItem current = newest.get(typeName);
                if (current == null || item.versionCode > current.versionCode) newest.put(typeName, item);
            }
        }

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            for (ContentProfile profile : contentsManager.getInstalledProfiles(type)) {
                String typeName = displayType(type);
                if (typeName.isEmpty()) continue;
                String key = typeName + ":" + profile.verCode + ":" + profile.verName;
                if (seen.add(key)) {
                    ComponentItem item = new ComponentItem();
                    item.profile = profile;
                    item.type = typeName;
                    item.name = profile.verName;
                    item.versionCode = profile.verCode;
                    item.installed = true;
                    item.entryName = ContentsManager.getEntryName(profile);
                    rebuilt.add(item);
                }
            }
        }

        for (ProtonPackageManager.PackageInfo packageInfo : ProtonPackageManager.getPackages()) {
            if (ProtonPackageManager.DEFAULT_IDENTIFIER.equals(packageInfo.identifier)) continue;
            ComponentItem item = new ComponentItem();
            item.packageInfo = packageInfo;
            item.type = "Proton";
            item.name = packageInfo.title;
            item.versionCode = 0;
            item.installed = ProtonPackageManager.isInstalled(this, packageInfo.identifier);
            item.entryName = item.installed ? packageInfo.identifier : "";
            rebuilt.add(item);
        }

        for (ComponentItem item : newest.values()) item.recommended = true;
        rebuilt.sort(Comparator.comparing((ComponentItem i) -> i.type)
                .thenComparing((ComponentItem i) -> i.versionCode, Comparator.reverseOrder()));
        synchronized (catalog) {
            catalog.clear();
            catalog.addAll(rebuilt);
        }
    }

    private String installedEntryName(ContentProfile.ContentType type, ContentProfile candidate) {
        for (ContentProfile installed : contentsManager.getInstalledProfiles(type)) {
            if (installed.verCode == candidate.verCode && installed.verName.equals(candidate.verName)) {
                return ContentsManager.getEntryName(installed);
            }
        }
        return "";
    }

    private boolean isInstalled(ContentProfile candidate) {
        for (ContentProfile installed : contentsManager.getInstalledProfiles(candidate.type)) {
            if (installed.verCode == candidate.verCode && installed.verName.equals(candidate.verName)) return true;
        }
        return false;
    }

    private String displayType(ContentProfile.ContentType type) {
        String raw = type.toString();
        String key = raw.toLowerCase(Locale.ENGLISH).replace("content_type_", "").replace("_", "");
        if (key.contains("wowbox64")) return "WOWBox64";
        if (key.contains("box64")) return "Box64";
        if (key.contains("fexcore")) return "FEXCore";
        if (key.contains("vkd3d")) return "VKD3D";
        if (key.contains("dxvk")) return "DXVK";
        if (key.contains("proton")) return "Proton";
        if (key.contains("wine")) return "Wine";
        return "";
    }

    private String componentId(ComponentItem item) {
        if (item.packageInfo != null) return "release-proton:" + item.packageInfo.identifier;
        return item.type + ":" + item.versionCode + ":" + item.name;
    }

    private ComponentItem findComponent(String id) {
        synchronized (catalog) {
            for (ComponentItem item : catalog) if (componentId(item).equals(id)) return item;
        }
        return null;
    }

    private void syncComposeCatalog() {
        if (composeController == null) return;
        ArrayList<OnboardingComponent> ui = new ArrayList<>();
        synchronized (catalog) {
            for (ComponentItem item : catalog) {
                String runtime = (item.type.equals("Wine") || item.type.equals("Proton")) && item.installed
                        ? item.entryName : null;
                boolean inUse = runtime != null && !runtime.isEmpty() && WineRuntimeGuard.isInUse(this, runtime);
                ui.add(new OnboardingComponent(
                        componentId(item),
                        item.type,
                        item.name,
                        item.installed,
                        item.recommended,
                        item.installed,
                        runtime,
                        inUse,
                        false
                ));
            }
        }

        Set<String> existingDrivers = new HashSet<>();
        for (String id : adrenotoolsManager.enumarateInstalledDrivers()) {
            String label = adrenotoolsManager.getDriverName(id) + " " + adrenotoolsManager.getDriverVersion(id);
            ui.add(new OnboardingComponent(
                    "adrenotools:" + id, "AdrenoTools", label.trim(), true, false, true,
                    null, false, false
            ));
            existingDrivers.add(label.trim().toLowerCase(Locale.ENGLISH));
        }
        synchronized (remoteDrivers) {
            for (RemoteDriverCatalog.Entry driver : remoteDrivers) {
                if (existingDrivers.contains(driver.name.toLowerCase(Locale.ENGLISH))) continue;
                ui.add(new OnboardingComponent(
                        remoteDriverId(driver), "AdrenoTools", driver.name + " • " + driver.repository,
                        false, false, false, null, false, false
                ));
            }
        }
        composeController.setComponents(ui);
        refreshBundledRuntimeState();
    }

    private void installComponent(ComponentItem item) {
        if (item.packageInfo != null) {
            installProtonPackage(item);
            return;
        }
        if (installBusy || item.url == null || item.url.isEmpty()) return;
        installBusy = true;
        String id = componentId(item);
        composeController.setInstallBusy(id, true);
        composeController.updateInstallProgress("Preparing " + item.name, 0);
        io.execute(() -> {
            File archive = new File(getCacheDir(), "winz-component-" + System.nanoTime());
            try {
                if (!download(item.url, archive, item.name)) throw new Exception("Download failed");
                installContentArchive(Uri.fromFile(archive), item.name, 72, () -> {
                    rebuildCatalog();
                    runOnUiThread(() -> finishInstall(id, null));
                }, error -> runOnUiThread(() -> finishInstall(id, error)));
            } catch (Exception error) {
                runOnUiThread(() -> finishInstall(id, "Unable to install " + item.name + "."));
            } finally {
                archive.delete();
            }
        });
    }

    private void installProtonPackage(ComponentItem item) {
        if (installBusy || item.packageInfo == null) return;
        installBusy = true;
        String id = componentId(item);
        ProtonPackageManager.PackageInfo packageInfo = item.packageInfo;
        composeController.setInstallBusy(id, true);
        composeController.updateInstallProgress("Preparing " + item.name, 0);
        io.execute(() -> {
            File archive = new File(getCacheDir(), packageInfo.identifier + "-" + System.nanoTime());
            boolean installed = false;
            try {
                boolean downloaded = ProtonPackageManager.downloadPackage(
                        packageInfo,
                        archive,
                        progress -> postInstallProgress(
                                "Downloading " + item.name,
                                Math.min(70, progress * 70 / 100)
                        )
                );
                if (downloaded) {
                    postInstallProgress("Installing " + item.name, 72);
                    installed = ProtonPackageManager.installPackage(this, packageInfo.identifier, archive);
                }
            } finally {
                archive.delete();
            }
            if (installed) rebuildCatalog();
            final boolean success = installed;
            runOnUiThread(() -> finishInstall(
                    id,
                    success ? null : "Unable to install " + item.name + "."
            ));
        });
    }

    private interface FailureCallback { void call(String error); }

    private void installContentArchive(Uri uri, String displayName, int startProgress,
                                       Runnable success, FailureCallback failure) {
        postInstallProgress("Installing " + displayName, startProgress);
        contentsManager.extraContentFile(uri, archiveProgress -> {
            int progress = archiveProgress < 0
                    ? -1
                    : startProgress + ((92 - startProgress) * archiveProgress / 100);
            postInstallProgress("Installing " + displayName, progress);
        }, new ContentsManager.OnInstallFinishedCallback() {
            @Override
            public void onFailed(ContentsManager.InstallFailedReason reason, Exception error) {
                failure.call("Package validation failed: " + reason);
            }

            @Override
            public void onSucceed(ContentProfile extracted) {
                String installedName = extracted.verName != null && !extracted.verName.isEmpty()
                        ? extracted.verName : displayName;
                postInstallProgress("Validating " + installedName, 92);
                contentsManager.finishInstallContent(extracted, new ContentsManager.OnInstallFinishedCallback() {
                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception error) {
                        if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) {
                            postInstallProgress("Installed " + installedName, 100);
                            success.run();
                        }
                        else failure.call("Installation failed: " + reason);
                    }

                    @Override
                    public void onSucceed(ContentProfile installed) {
                        postInstallProgress("Installed " + installedName, 100);
                        success.run();
                    }
                });
            }
        });
    }

    private boolean download(String url, File out, String displayName) {
        try (Response response = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            long total = response.body().contentLength();
            long copied = 0;
            int lastProgress = -1;
            postInstallProgress("Downloading " + displayName, total > 0 ? 0 : -1);
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(out)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    copied += read;
                    if (total > 0) {
                        int progress = Math.min(70, (int)((copied * 70L) / total));
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            postInstallProgress("Downloading " + displayName, progress);
                        }
                    }
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void postInstallProgress(String label, int progress) {
        runOnUiThread(() -> {
            if (composeController != null && installBusy) {
                composeController.updateInstallProgress(label, progress);
            }
        });
    }

    private String localDisplayName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        return name == null || name.trim().isEmpty() ? "local component" : name;
    }

    private void finishInstall(String id, String error) {
        contentsManager.syncContents();
        installBusy = false;
        composeController.setInstallBusy(null, false);
        syncComposeCatalog();
        if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    private ContentProfile findInstalledProfile(String componentId) {
        ComponentItem item = findComponent(componentId);
        if (item == null) return null;
        for (ContentProfile profile : contentsManager.getInstalledProfiles(item.profile.type)) {
            if (profile.verCode == item.versionCode && profile.verName.equals(item.name)) return profile;
        }
        return null;
    }

    private void requestRemoveComponent(String componentId) {
        if (componentId.startsWith("adrenotools:")) {
            new AlertDialog.Builder(this)
                    .setTitle("Delete driver?")
                    .setMessage("The installed driver files will be removed.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Delete", (d, w) -> removeDriver(componentId.substring("adrenotools:".length())))
                    .show();
            return;
        }
        ComponentItem item = findComponent(componentId);
        if (item != null && item.packageInfo != null) {
            requestRemoveProtonPackage(item, componentId);
            return;
        }
        ContentProfile profile = findInstalledProfile(componentId);
        if (profile == null) return;
        if (!WineRuntimeGuard.canRemove(this, profile)) {
            String using = WineRuntimeGuard.getContainerUsing(this, ContentsManager.getEntryName(profile));
            new AlertDialog.Builder(this)
                    .setTitle("Runtime is in use")
                    .setMessage(profile.verName + " cannot be deleted because it is used by " + using + ".")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete component?")
                .setMessage("The installed files will be removed from WinZ.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (d, w) -> removeContent(profile, componentId))
                .show();
    }

    private void requestRemoveProtonPackage(ComponentItem item, String componentId) {
        String identifier = item.packageInfo.identifier;
        String using = WineRuntimeGuard.getContainerUsing(this, identifier);
        if (using != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Runtime is in use")
                    .setMessage(item.name + " cannot be deleted because it is used by " + using + ".")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete component?")
                .setMessage("The installed files will be removed from WinZ.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (d, w) -> {
                    if (installBusy) return;
                    installBusy = true;
                    composeController.setInstallBusy(componentId, true);
                    io.execute(() -> {
                        ProtonPackageManager.deletePackage(this, identifier);
                        rebuildCatalog();
                        runOnUiThread(() -> finishInstall(componentId, null));
                    });
                })
                .show();
    }

    private void removeContent(ContentProfile profile, String id) {
        if (installBusy || !WineRuntimeGuard.canRemove(this, profile)) return;
        installBusy = true;
        composeController.setInstallBusy(id, true);
        io.execute(() -> {
            contentsManager.removeContent(profile);
            contentsManager.syncContents();
            rebuildCatalog();
            runOnUiThread(() -> finishInstall(id, null));
        });
    }

    private void removeDriver(String id) {
        if (installBusy) return;
        installBusy = true;
        composeController.setInstallBusy("adrenotools:" + id, true);
        io.execute(() -> {
            adrenotoolsManager.removeDriver(id);
            runOnUiThread(() -> {
                installBusy = false;
                composeController.setInstallBusy(null, false);
                syncComposeCatalog();
            });
        });
    }

    private void loadRemoteDrivers() {
        io.execute(() -> {
            List<RemoteDriverCatalog.Entry> loaded = RemoteDriverCatalog.load(this);
            synchronized (remoteDrivers) {
                remoteDrivers.clear();
                remoteDrivers.addAll(loaded);
            }
            runOnUiThread(() -> {
                syncComposeCatalog();
                maybeAutoInstall();
            });
        });
    }

    private String remoteDriverId(RemoteDriverCatalog.Entry driver) {
        return "remote-driver:" + driver.name + ":" + Integer.toHexString(driver.url.hashCode());
    }

    private void installRemoteDriver(String componentId) {
        RemoteDriverCatalog.Entry found = null;
        synchronized (remoteDrivers) {
            for (RemoteDriverCatalog.Entry driver : remoteDrivers) {
                if (remoteDriverId(driver).equals(componentId)) {
                    found = driver;
                    break;
                }
            }
        }
        if (found == null || installBusy) return;
        final RemoteDriverCatalog.Entry driver = found;
        installBusy = true;
        composeController.setInstallBusy(componentId, true);
        composeController.updateInstallProgress("Installing " + driver.name, -1);
        io.execute(() -> {
            String installed = RemoteDriverCatalog.install(this, driver.url);
            runOnUiThread(() -> {
                installBusy = false;
                composeController.setInstallBusy(null, false);
                syncComposeCatalog();
                if (installed == null || installed.isEmpty()) {
                    Toast.makeText(this, "Unable to install " + driver.name + ".", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void maybeAutoInstall() {
        if (autoInstallDispatched || installBusy || pendingInstallType == null || pendingInstallType.isEmpty()) return;
        if (pendingInstallType.equalsIgnoreCase("AdrenoTools")) {
            synchronized (remoteDrivers) {
                for (RemoteDriverCatalog.Entry driver : remoteDrivers) {
                    if (pendingInstallVersion == null || driver.name.equalsIgnoreCase(pendingInstallVersion)) {
                        autoInstallDispatched = true;
                        installRemoteDriver(remoteDriverId(driver));
                        return;
                    }
                }
            }
            return;
        }
        synchronized (catalog) {
            for (ComponentItem item : catalog) {
                if (!item.type.equalsIgnoreCase(pendingInstallType)) continue;
                if (pendingInstallVersion != null && !pendingInstallVersion.isEmpty()
                        && !item.name.equalsIgnoreCase(pendingInstallVersion)) continue;
                if (pendingInstallVersionCode != Integer.MIN_VALUE && item.versionCode != pendingInstallVersionCode) continue;
                autoInstallDispatched = true;
                if (!item.installed) installComponent(item);
                return;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOCAL_COMPONENT && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            if (installBusy) return;
            installBusy = true;
            composeController.setInstallBusy("local", true);
            Uri uri = data.getData();
            String displayName = localDisplayName(uri);
            composeController.updateInstallProgress("Preparing " + displayName, 0);
            io.execute(() -> {
                // "Local package" is shared by Wine components and AdrenoTools drivers.
                // ZIPs are routed through the same validated installer as "Local driver".
                // WCP / tar.xz / tar.zst archives retain the original component importer.
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("Unable to open local package");
                    if (LocalDriverArchive.isZip(input)) {
                        postInstallProgress("Installing driver " + displayName, -1);
                        String installed = adrenotoolsManager.installDriver(uri);
                        if (installed != null && !installed.isEmpty()) {
                            postInstallProgress("Installed " + installed, 100);
                            runOnUiThread(() -> {
                                syncComposeCatalog();
                                finishInstall("local", null);
                            });
                        } else {
                            runOnUiThread(() -> finishInstall("local",
                                    "Invalid AdrenoTools driver ZIP or driver already installed."));
                        }
                        return;
                    }
                } catch (IOException | SecurityException error) {
                    runOnUiThread(() -> finishInstall("local", "Unable to read local package: " + error.getMessage()));
                    return;
                }

                installContentArchive(uri, displayName, 5, () -> {
                    rebuildCatalog();
                    runOnUiThread(() -> finishInstall("local", null));
                }, error -> runOnUiThread(() -> finishInstall("local", error)));
            });
        } else if (requestCode == REQUEST_LOCAL_DRIVER && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            if (installBusy) return;
            installBusy = true;
            composeController.setInstallBusy("driver-local", true);
            Uri uri = data.getData();
            composeController.updateInstallProgress("Installing " + localDisplayName(uri), -1);
            io.execute(() -> {
                String installed = adrenotoolsManager.installDriver(uri);
                runOnUiThread(() -> {
                    installBusy = false;
                    composeController.setInstallBusy(null, false);
                    syncComposeCatalog();
                    if (installed == null || installed.isEmpty()) {
                        Toast.makeText(this, "Unable to install the driver.", Toast.LENGTH_LONG).show();
                    }
                });
            });
        } else if (requestCode == REQUEST_ALL_FILES) {
            continuePermissionFlow();
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                && (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
            return;
        }
        continuePermissionFlow();
    }

    private void continuePermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_ALL_FILES);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        finishOnboarding();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) continuePermissionFlow();
            else Toast.makeText(this, "Storage access is required to manage games.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            finishOnboarding();
        }
    }

    private void finishOnboarding() {
        if (finishing) return;
        if (componentManagerMode) {
            finish();
            return;
        }
        finishing = true;
        contentsManager.syncContents();
        ContainerManager manager = new ContainerManager(this);
        if (!manager.getContainers().isEmpty()) {
            enterMainApp();
            return;
        }

        String runtime = resolveSelectedRuntime();
        if (runtime == null) {
            finishing = false;
            Toast.makeText(this, "Install and select a Wine or Proton layer first.", Toast.LENGTH_LONG).show();
            return;
        }

        WineInfo wineInfo = WineInfo.fromIdentifier(this, contentsManager, runtime);
        if (wineInfo.path == null || wineInfo.path.isEmpty()) {
            finishing = false;
            Toast.makeText(this, "The selected Wine/Proton layer is no longer installed.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", "Container-" + manager.getNextContainerId());
            data.put("screenSize", Container.DEFAULT_SCREEN_SIZE);
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER);
            data.put("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG);
            data.put("rendererNative", false);
            data.put("rendererPresentMode", "fifo");
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG);
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("emulator", wineInfo.isArm64EC() ? "FEXCore" : "Box64");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("box64Version", wineInfo.isArm64EC() ? DefaultVersion.WOWBOX64 : DefaultVersion.BOX64);
            data.put("box64Preset", Box64Preset.COMPATIBILITY);
            data.put("fexcoreVersion", DefaultVersion.FEXCORE);
            data.put("fexcorePreset", FEXCorePreset.INTERMEDIATE);
            data.put("wineVersion", runtime);
            OpenGLDriverDefaults.initialize(this, data);

            manager.createContainerAsync(data, contentsManager, created -> {
                if (created == null) {
                    finishing = false;
                    Toast.makeText(this, "Unable to create the first container.", Toast.LENGTH_LONG).show();
                } else {
                    enterMainApp();
                }
            });
        } catch (Exception error) {
            finishing = false;
            Toast.makeText(this, "Unable to prepare the first container.", Toast.LENGTH_LONG).show();
        }
    }

    private String resolveSelectedRuntime() {
        if (selectedInitialWine != null && !selectedInitialWine.isEmpty() && runtimeInstalled(selectedInitialWine)) {
            return selectedInitialWine;
        }
        if (WineRuntimeGuard.isBundledMainInstalled(this)) return WineInfo.MAIN_WINE_VERSION.identifier();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            String typeName = displayType(type);
            if (!typeName.equals("Wine") && !typeName.equals("Proton")) continue;
            for (ContentProfile profile : contentsManager.getInstalledProfiles(type)) {
                return ContentsManager.getEntryName(profile);
            }
        }
        for (String identifier : ProtonPackageManager.getInstalledIdentifiers(this)) {
            if (!WineInfo.MAIN_WINE_VERSION.identifier().equals(identifier)) return identifier;
        }
        return null;
    }

    private boolean runtimeInstalled(String identifier) {
        if (WineInfo.MAIN_WINE_VERSION.identifier().equals(identifier)) {
            return WineRuntimeGuard.isBundledMainInstalled(this);
        }
        if (ProtonPackageManager.isKnownPackage(identifier)) {
            return ProtonPackageManager.isInstalled(this, identifier);
        }
        ContentProfile profile = contentsManager.getProfileByEntryName(identifier);
        return profile != null && (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON);
    }

    private void enterMainApp() {
        preferences.edit()
                .putBoolean(PREF_ONBOARDING_COMPLETE, true)
                .putString(PREF_INITIAL_WINE, selectedInitialWine == null ? "" : selectedInitialWine)
                .apply();
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(0, 0);
        finish();
    }

    private static final class ComponentItem {
        ContentProfile profile;
        ProtonPackageManager.PackageInfo packageInfo;
        String type;
        String name;
        String url;
        String entryName;
        int versionCode;
        boolean installed;
        boolean recommended;
    }
}
