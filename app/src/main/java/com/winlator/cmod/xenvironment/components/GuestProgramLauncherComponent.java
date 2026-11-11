package com.winlator.cmod.xenvironment.components;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.os.Process;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private WineInfo wineInfo;
    private String box64Preset = Box64Preset.COMPATIBILITY;
    private String fexcorePreset = FEXCorePreset.INTERMEDIATE;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private final ContentsManager contentsManager;
    private final ContentProfile wineProfile;
    private Container container;
    private final Shortcut shortcut;

    public void setWineInfo(WineInfo wineInfo) {
        this.wineInfo = wineInfo;
    }
    public WineInfo getWineInfo() {
        return this.wineInfo;
    }

    public Container getContainer() { return this.container; }
    public void setContainer(Container container) { this.container = container; }

    private ContentProfile resolveInstalledRuntimeProfile(ContentProfile.ContentType type, String version) {
        ContentProfile exact = contentsManager.getProfileByEntryName(type + "-" + version);
        if (exact != null && exact.remoteUrl == null) return exact;

        ContentProfile bestMatch = null;
        for (ContentProfile profile : contentsManager.getInstalledProfiles(type)) {
            String entryName = ContentsManager.getEntryName(profile);
            int separator = entryName.indexOf('-');
            String versionId = separator >= 0 ? entryName.substring(separator + 1) : profile.verName;
            if (version.equals(versionId) || version.equals(profile.verName)) {
                if (bestMatch == null || profile.verCode > bestMatch.verCode) bestMatch = profile;
            }
        }
        return bestMatch;
    }

    private boolean applyRuntimeContent(
            ContentProfile.ContentType type,
            String version,
            Context context,
            String bundledAsset,
            File destination) {
        ContentProfile profile = resolveInstalledRuntimeProfile(type, version);
        if (profile != null) return contentsManager.applyContent(profile);
        if (type == ContentProfile.ContentType.CONTENT_TYPE_FEXCORE && "2611".equals(version))
            destination = environment.getImageFs().getRootDir();
        return TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                bundledAsset,
                destination);
    }

    private void extractBox64Files() {
        ImageFs imageFs = environment.getImageFs();
        Context context = environment.getContext();

        String box64Version = container.getBox64Version();

        if (shortcut != null)
            box64Version = shortcut.getExtra("box64Version", shortcut.container.getBox64Version());

        // The retired bundled Box64 archive is no longer shipped. Migrate the
        // standard Box64 path only; ARM64EC/WOWBox64 has its own installer.
        boolean retiredBundledBox64 = "0.4.2".equals(box64Version);
        if (retiredBundledBox64) box64Version = com.winlator.cmod.core.DefaultVersion.BOX64;

        Log.d("GuestProgramLauncherComponent", "box64Version: " + box64Version);

        File rootDir = imageFs.getRootDir();

        if (!box64Version.equals(container.getExtra("box64Version"))) {
            boolean applied = applyRuntimeContent(
                    ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                    box64Version,
                    context,
                    "box64/box64-" + box64Version + ".tzst",
                    rootDir);
            if (applied) {
                container.putExtra("box64Version", box64Version);
                container.saveData();
            } else {
                Log.e("GuestProgramLauncherComponent", "Unable to apply Box64 version " + box64Version);
            }
        }

        if (retiredBundledBox64 && box64Version.equals(container.getExtra("box64Version"))) {
            if ("0.4.2".equals(container.getBox64Version())) container.setBox64Version(box64Version);
            if (shortcut != null && "0.4.2".equals(shortcut.getExtra("box64Version", null))) {
                shortcut.putExtra("box64Version", box64Version);
                shortcut.saveData();
            }
            container.saveData();
        }

        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }
    }

    private void extractEmulatorsDlls() {;
        Context context = environment.getContext();
        File rootDir = environment.getImageFs().getRootDir();
        File system32dir = new File(rootDir + "/home/xuser/.wine/drive_c/windows/system32");
        boolean containerDataChanged = false;

        String wowbox64Version = container.getBox64Version();
        String fexcoreVersion = container.getFEXCoreVersion();

        if (shortcut != null) {
            wowbox64Version = shortcut.getExtra("box64Version", shortcut.container.getBox64Version());
            fexcoreVersion = shortcut.getExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion());
        }

        Log.d("GuestProgramLauncherComponent", "box64Version in use: " + wowbox64Version);
        Log.d("GuestProgramLauncherComponent", "fexcoreVersion in use: " + fexcoreVersion);

        if (!wowbox64Version.equals(container.getExtra("box64Version"))) {
            boolean applied = applyRuntimeContent(
                    ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
                    wowbox64Version,
                    context,
                    "wowbox64/wowbox64-" + wowbox64Version + ".tzst",
                    system32dir);
            if (applied) {
                container.putExtra("box64Version", wowbox64Version);
                containerDataChanged = true;
            } else {
                Log.e("GuestProgramLauncherComponent", "Unable to apply WOWBox64 version " + wowbox64Version);
            }
        }

        ContentProfile fexcoreProfile = resolveInstalledRuntimeProfile(
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE, fexcoreVersion);
        boolean fexcoreFilesMissing = fexcoreProfile != null
                && !contentsManager.isContentApplied(fexcoreProfile);
        boolean bundledUnixlibs = fexcoreProfile == null && "2611".equals(fexcoreVersion);
        File unixlibDir = new File(rootDir, "usr/lib/wine/aarch64-unix");
        boolean bundledUnixlibsMissing = bundledUnixlibs
                && (!"2611-unixlibs".equals(container.getExtra("fexcoreBundledRevision"))
                || !new File(unixlibDir, "libarm64ecfex.so").isFile()
                || !new File(unixlibDir, "libwow64fex.so").isFile());
        if (!fexcoreVersion.equals(container.getExtra("fexcoreVersion")) || fexcoreFilesMissing || bundledUnixlibsMissing) {
            if (fexcoreFilesMissing) {
                Log.w("GuestProgramLauncherComponent",
                        "FEXCore files are missing or incomplete; reapplying " + fexcoreVersion);
            }
            boolean applied = applyRuntimeContent(
                    ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                    fexcoreVersion,
                    context,
                    "fexcore/fexcore-" + fexcoreVersion + ".tzst",
                    system32dir);
            if (applied) {
                container.putExtra("fexcoreVersion", fexcoreVersion);
                if (bundledUnixlibs) container.putExtra("fexcoreBundledRevision", "2611-unixlibs");
                containerDataChanged = true;
            } else {
                Log.e("GuestProgramLauncherComponent", "Unable to apply FEXCore version " + fexcoreVersion);
            }
        }
        if (containerDataChanged) container.saveData();
    }

    public GuestProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile, Shortcut shortcut) {
        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;
        this.shortcut = shortcut;
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (wineInfo.isArm64EC())
                extractEmulatorsDlls();
            else
                extractBox64Files();
            checkDependencies();
            pid = execGuestProgram();
        }
    }

    private String checkDependencies() {
        String curlPath = environment.getImageFs().getRootDir().getPath() + "/usr/lib/libXau.so";
        String lddCommand = "ldd " + curlPath;

        StringBuilder output = new StringBuilder("Checking Curl dependencies...\n");

        try {
            java.lang.Process process = Runtime.getRuntime().exec(lddCommand);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
        } catch (Exception e) {
            output.append("Error running ldd: ").append(e.getMessage());
        }

        Log.d("CurlDeps", output.toString()); 
        return output.toString();
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    private static String mergePreloadValue(String baseValue, String overrideValue) {
        if (overrideValue == null || overrideValue.isEmpty()) {
            return baseValue == null ? "" : baseValue;
        }
        if (baseValue == null || baseValue.isEmpty()) {
            return overrideValue;
        }
        if (overrideValue.equals(baseValue)) {
            return baseValue;
        }
        return baseValue + ":" + overrideValue;
    }
    private static String appendFirstExistingPreload(String ldPreload, File[] candidates) {
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return mergePreloadValue(ldPreload, candidate.getAbsolutePath());
            }
        }
        return ldPreload;
   }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public String[] getBindingPaths() {
        return bindingPaths;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public void setFEXCorePreset (String fexcorePreset) { this.fexcorePreset = fexcorePreset; }

    private int execGuestProgram() {
        Context context = environment.getContext();
        ImageFs imageFs = environment.getImageFs();
        File rootDir = imageFs.getRootDir();

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean enableBox64Logs = preferences.getBoolean("enable_box64_logs", false);
        boolean openWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean shareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);

        if (openWithAndroidBrowser)
            this.envVars.put("WINE_OPEN_WITH_ANDROID_BROWSER", "1");
        if (shareAndroidClipboard) {
            this.envVars.put("WINE_FROM_ANDROID_CLIPBOARD", "1");
            this.envVars.put("WINE_TO_ANDROID_CLIPBOARD", "1");
        }

        EnvVars execEnvVars = new EnvVars();

        addBox64EnvVars(execEnvVars, enableBox64Logs);
        execEnvVars.putAll(FEXCorePresetManager.getEnvVars(context, fexcorePreset));

        String renderer = GPUInformation.getRenderer(null, null);

        if (renderer.contains("Mali")) 
            execEnvVars.put("BOX64_MMAP32", "0");

        if (execEnvVars.get("BOX64_MMAP32").equals("1") && !wineInfo.isArm64EC()) {
            Log.d("GuestProgramLauncherComponent", "Disabling map memory placed");
            execEnvVars.put("WRAPPER_DISABLE_PLACED", "1");
        }

        execEnvVars.put("HOME", imageFs.home_path);
        execEnvVars.put("USER", ImageFs.USER);
        execEnvVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
        execEnvVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
        execEnvVars.put("LD_LIBRARY_PATH", rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64");
        execEnvVars.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
        execEnvVars.put("GST_PLUGIN_PATH", rootDir.getPath() + "/usr/lib/gstreamer-1.0");
        execEnvVars.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");
        execEnvVars.put("VK_LAYER_PATH", rootDir.getPath() + "/usr/share/vulkan/implicit_layer.d" + ":" + rootDir.getPath() + "/usr/share/vulkan/explicit_layer.d");
        execEnvVars.put("WRAPPER_LAYER_PATH", rootDir.getPath() + "/usr/lib");
        execEnvVars.put("WRAPPER_CACHE_PATH", rootDir.getPath() + "/usr/var/cache");
        execEnvVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        execEnvVars.put("PREFIX", rootDir.getPath() + "/usr");
        execEnvVars.put("DISPLAY", ":0");
        execEnvVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        execEnvVars.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
        execEnvVars.put("ALSA_CONFIG_PATH", rootDir.getPath() + "/usr/share/alsa/alsa.conf" + ":" + rootDir.getPath() + "/usr/etc/alsa/conf.d/android_alsa.conf");
        execEnvVars.put("ALSA_PLUGIN_DIR", rootDir.getPath() + "/usr/lib/alsa-lib");
        execEnvVars.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
        execEnvVars.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        execEnvVars.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
        execEnvVars.put("WINE_X11FORCEGLX", "1");
        execEnvVars.put("WINE_GST_NO_GL", "1");
        execEnvVars.put("SteamGameId", "0");
        execEnvVars.put("PROTON_AUDIO_CONVERT", "0");
        execEnvVars.put("PROTON_VIDEO_CONVERT", "0");
        execEnvVars.put("PROTON_DEMUX", "0");

        String winePath = imageFs.getWinePath() + "/bin";

        Log.d("GuestProgramLauncherComponent", "WinePath is " + winePath);

        execEnvVars.put("PATH", winePath + ":" +
                rootDir.getPath() + "/usr/bin");

        execEnvVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);

        String primaryDNS = "8.8.4.4";
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        if (connectivityManager.getActiveNetwork() != null) {
            ArrayList<InetAddress> dnsServers = new ArrayList<>(connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork()).getDnsServers());
            primaryDNS = dnsServers.get(0).toString().substring(1);
        }
        execEnvVars.put("ANDROID_RESOLV_DNS", primaryDNS);
        execEnvVars.put("WINE_NEW_NDIS", "1");

        String ld_preload = "";

        if ((new File(imageFs.getLibDir(), "libandroid-sysvshm.so")).exists()){
            ld_preload = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        }

        File fakeinputDest = new File(imageFs.getLibDir(), "libfakeinput.so");
        String nativeLibDir = environment.getContext().getApplicationInfo().nativeLibraryDir;
        File fakeinputSrc = new File(nativeLibDir, "libfakeinput.so");

        Log.d("GuestLauncher", "nativeLibDir: " + nativeLibDir);
        Log.d("GuestLauncher", "fakeinputSrc exists: " + fakeinputSrc.exists());
        Log.d("GuestLauncher", "fakeinputDest: " + fakeinputDest.getAbsolutePath());

        try {
            if (fakeinputSrc.exists()) {
                FileUtils.copy(fakeinputSrc, fakeinputDest);
                Log.d("GuestLauncher", "Copied libfakeinput.so to imagefs");
            } else {
                Log.e("GuestLauncher", "libfakeinput.so NOT FOUND in APK: " + fakeinputSrc.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e("GuestLauncher", "Failed to copy libfakeinput.so: " + e.getMessage());
            e.printStackTrace();
        }

        Log.d("GuestLauncher", "fakeinputDest exists after copy: " + fakeinputDest.exists());
        if (fakeinputDest.exists()) {
            if (!ld_preload.isEmpty()) ld_preload += ":";
            ld_preload += fakeinputDest.getAbsolutePath();
        }

        File[] jpegCandidates = new File[] {
            new File("/system/lib64/libjpeg.so"),
            new File("/system_ext/lib64/libjpeg.so"),
        };
        
        ld_preload = appendFirstExistingPreload(ld_preload, jpegCandidates);

        File[] cryptoCandidates = new File[] {
            new File("/system/lib64/libcrypto.so"),
            new File("/system_ext/lib64/libcrypto.so"),
            new File(imageFs.getLibDir(), "libcrypto.so.3"),
        };
        
        ld_preload = appendFirstExistingPreload(ld_preload, cryptoCandidates);

        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        devInputDir.mkdirs();
        File event0 = new File(devInputDir, "event0");
        if (!event0.exists()) {
                try { event0.createNewFile(); } catch (Exception e) {}
        }

        execEnvVars.put("FAKE_EVDEV_DIR", devInputDir.getAbsolutePath());
        execEnvVars.put("FAKE_EVDEV_VIBRATION", "1");

        Log.d("GuestLauncher", "Final LD_PRELOAD: " + ld_preload);
        execEnvVars.put("LD_PRELOAD", ld_preload);

        if (this.envVars.has("MANGOHUD")) {
            this.envVars.remove("MANGOHUD");
        }

        if (this.envVars.has("MANGOHUD_CONFIG")) {
            this.envVars.remove("MANGOHUD_CONFIG");
        }
        
        if (this.envVars != null) {
            execEnvVars.putAll(this.envVars);
        }

        // Evaluate on every launch, even when the component DLL cache is unchanged.
        if (container != null) {
            com.winlator.cmod.core.VideoDecoderSettings.apply(execEnvVars,
                    shortcut != null ? shortcut.getBuiltinDecoder() : container.getBuiltinDecoder(),
                    shortcut != null ? shortcut.getSoftwareDecoding() : container.getSoftwareDecoding());
        }

        // Apply after custom environment variables so the saved compatibility option wins.
        // Older Wine builds simply ignore this; the correction lives in ARM64 ntdll.
        if (wineInfo.isArm64EC() && container != null) {
            boolean ssbs = com.winlator.cmod.core.SsbsSettings.resolve(container.isForceSsbs(),
                    shortcut != null ? shortcut.getExtra("forceSsbs") : "");
            execEnvVars.put("WINE_FORCE_SSBS", ssbs ? "1" : "0");
        }

        configureDisplayXEnvironment(execEnvVars, container, shortcut);

        String emulator = container.getEmulator();
        if (shortcut != null)
            emulator = shortcut.getExtra("emulator", container.getEmulator());

        String command = "";
        String overriddenCommand = execEnvVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
        if (!overriddenCommand.isEmpty()) {
            String[] parts = overriddenCommand.split(";");
            for (String part : parts)
                command += part + " ";
            command = command.trim();
        }
        else {
            if (wineInfo.isArm64EC()) {
                command = winePath + "/" + guestExecutable;
                if (emulator.toLowerCase().equals("fexcore"))
                    execEnvVars.put("HODLL", "libwow64fex.dll");
                else
                    execEnvVars.put("HODLL", "wowbox64.dll");
            } else
                command = imageFs.getBinDir() + "/box64 " + guestExecutable;
        }

        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        return ProcessHelper.exec(command, execEnvVars.toStringArray(), rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }

            if (terminationCallback != null)
                terminationCallback.call(status);
        });
    }

    static void configureDisplayXEnvironment(EnvVars execEnvVars, Container container, Shortcut shortcut) {
        boolean useDisplayX = (shortcut != null
                ? shortcut.getUseDisplayX()
                : container != null && container.getUseDisplayX());
        boolean trueDisplayX = shortcut != null
                ? shortcut.getTrueDisplayX()
                : container != null && container.getTrueDisplayX();
        String surfaceFormat = shortcut != null
                ? shortcut.getSurfaceFormat()
                : container != null ? container.getSurfaceFormat() : "rgba8";

        execEnvVars.put("WRAPPER_SURFACE_FORMAT", surfaceFormat);
        if (useDisplayX) execEnvVars.put("DISPLAYX_SURFACE_FORMAT", surfaceFormat);
        else execEnvVars.remove("DISPLAYX_SURFACE_FORMAT");
        if (useDisplayX) {
            execEnvVars.put("DISPLAYX_PRESENT_MODE", shortcut != null
                    ? shortcut.getDisplayXPresentMode() : container.getDisplayXPresentMode());
        } else {
            execEnvVars.remove("DISPLAYX_PRESENT_MODE");
        }

        final String displayXLayer = "VK_LAYER_DISPLAYX_display_x";
        String enabledLayers = execEnvVars.get("VK_INSTANCE_LAYERS");
        if (useDisplayX && trueDisplayX) {
            execEnvVars.put("VK_INSTANCE_LAYERS", displayXLayer);
            String range = shortcut != null ? shortcut.getDisplayXDynamicRange() : container.getDisplayXDynamicRange();
            if ("hdr".equals(range)) execEnvVars.put("DXVK_HDR", "1");
        } else {
            StringBuilder filteredLayers = new StringBuilder();
            if (enabledLayers != null && !enabledLayers.isEmpty()) {
                for (String layer : enabledLayers.split(":")) {
                    if (layer.isEmpty() || layer.equals(displayXLayer)) continue;
                    if (filteredLayers.length() > 0) filteredLayers.append(':');
                    filteredLayers.append(layer);
                }
            }
            if (filteredLayers.length() > 0) {
                execEnvVars.put("VK_INSTANCE_LAYERS", filteredLayers.toString());
            } else {
                execEnvVars.remove("VK_INSTANCE_LAYERS");
            }
        }

    }

    private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
        envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");

        if (enableLogs) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
        }

        envVars.putAll(Box64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
        envVars.put("BOX64_X11GLX", "1");
        envVars.put("BOX64_NORCFILES", "1");
    }

    public void suspendProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.suspendProcess(pid);
        }
    }

    public void resumeProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.resumeProcess(pid);
        }
    }
}
