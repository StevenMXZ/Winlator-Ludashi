package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Process;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import org.apache.commons.compress.archivers.zip.UnixStat;

/* loaded from: classes10.dex */
public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String[] bindingPaths;
    private Container container;
    private final ContentsManager contentsManager;
    private EnvVars envVars;
    private String guestExecutable;
    private final Shortcut shortcut;
    private Callback<Integer> terminationCallback;
    private WineInfo wineInfo;
    private final ContentProfile wineProfile;
    private static int pid = -1;
    private static final Object lock = new Object();
    private String box64Preset = "COMPATIBILITY";
    private String fexcorePreset = "INTERMEDIATE";

    public void setWineInfo(WineInfo wineInfo) {
        this.wineInfo = wineInfo;
    }

    public WineInfo getWineInfo() {
        return this.wineInfo;
    }

    public Container getContainer() {
        return this.container;
    }

    public void setContainer(Container container) {
        this.container = container;
    }

    private void extractBox64Files() {
        ImageFs imageFs = this.environment.getImageFs();
        Context context = this.environment.getContext();
        String box64Version = this.container.getBox64Version();
        if (this.shortcut != null) {
            box64Version = this.shortcut.getExtra("box64Version", this.shortcut.container.getBox64Version());
        }
        Log.d("GuestProgramLauncherComponent", "box64Version: " + box64Version);
        File rootDir = imageFs.getRootDir();
        if (!box64Version.equals(this.container.getExtra("box64Version"))) {
            ContentProfile profile = this.contentsManager.getProfileByEntryName("box64-" + box64Version);
            if (profile != null) {
                this.contentsManager.applyContent(profile);
            } else {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "box64/box64-" + box64Version + ".tzst", rootDir);
            }
            this.container.putExtra("box64Version", box64Version);
            this.container.saveData();
        }
        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, UnixStat.DEFAULT_DIR_PERM);
        }
    }

    private void extractEmulatorsDlls() {
        this.environment.getContext();
        File rootDir = this.environment.getImageFs().getRootDir();
        File system32dir = new File(rootDir + "/home/xuser/.wine/drive_c/windows/system32");
        boolean containerDataChanged = false;
        String wowbox64Version = this.container.getBox64Version();
        String fexcoreVersion = this.container.getFEXCoreVersion();
        if (this.shortcut != null) {
            wowbox64Version = this.shortcut.getExtra("box64Version", this.shortcut.container.getBox64Version());
        }
        Log.d("GuestProgramLauncherComponent", "box64Version in use: " + wowbox64Version);
        Log.d("GuestProgramLauncherComponent", "fexcoreVersion in use: " + fexcoreVersion);
        if (!wowbox64Version.equals(this.container.getExtra("box64Version"))) {
            ContentProfile profile = this.contentsManager.getProfileByEntryName("wowbox64-" + wowbox64Version);
            if (profile != null) {
                this.contentsManager.applyContent(profile);
            } else {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this.environment.getContext(), "wowbox64/wowbox64-" + wowbox64Version + ".tzst", system32dir);
            }
            this.container.putExtra("box64Version", wowbox64Version);
            containerDataChanged = true;
        }
        if (!fexcoreVersion.equals(this.container.getExtra("fexcoreVersion"))) {
            ContentProfile profile2 = this.contentsManager.getProfileByEntryName("fexcore-" + fexcoreVersion);
            if (profile2 != null) {
                this.contentsManager.applyContent(profile2);
            } else {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this.environment.getContext(), "fexcore/fexcore-" + fexcoreVersion + ".tzst", system32dir);
            }
            this.container.putExtra("fexcoreVersion", fexcoreVersion);
            containerDataChanged = true;
        }
        if (containerDataChanged) {
            this.container.saveData();
        }
    }

    public GuestProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile, Shortcut shortcut) {
        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;
        this.shortcut = shortcut;
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void start() {
        synchronized (lock) {
            if (this.wineInfo.isArm64EC()) {
                extractEmulatorsDlls();
            } else {
                extractBox64Files();
            }
            checkDependencies();
            pid = execGuestProgram();
        }
    }

    private String checkDependencies() {
        String curlPath = this.environment.getImageFs().getRootDir().getPath() + "/usr/lib/libXau.so";
        String lddCommand = "ldd " + curlPath;
        StringBuilder output = new StringBuilder("Checking Curl dependencies...\n");
        try {
            Process process = Runtime.getRuntime().exec(lddCommand);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                output.append(line).append("\n");
            }
            while (true) {
                String line2 = errorReader.readLine();
                if (line2 == null) {
                    break;
                }
                output.append(line2).append("\n");
            }
            process.waitFor();
        } catch (Exception e) {
            output.append("Error running ldd: ").append(e.getMessage());
        }
        Log.d("CurlDeps", output.toString());
        return output.toString();
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return this.terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return this.guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public String[] getBindingPaths() {
        return this.bindingPaths;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public EnvVars getEnvVars() {
        return this.envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return this.box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public void setFEXCorePreset(String fexcorePreset) {
        this.fexcorePreset = fexcorePreset;
    }

    private int execGuestProgram() {
        String ld_preload;
        String command;
        Context context = this.environment.getContext();
        ImageFs imageFs = this.environment.getImageFs();
        File rootDir = imageFs.getRootDir();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean enableBox64Logs = preferences.getBoolean("enable_box64_logs", false);
        boolean openWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean shareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);
        if (openWithAndroidBrowser) {
            this.envVars.put("WINE_OPEN_WITH_ANDROID_BROWSER", "1");
        }
        if (shareAndroidClipboard) {
            this.envVars.put("WINE_FROM_ANDROID_CLIPBOARD", "1");
            this.envVars.put("WINE_TO_ANDROID_CLIPBOARD", "1");
        }
        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars, enableBox64Logs);
        envVars.putAll(FEXCorePresetManager.getEnvVars(context, this.fexcorePreset));
        String renderer = GPUInformation.getRenderer(null, null);
        if (renderer.contains("Mali")) {
            envVars.put("BOX64_MMAP32", "0");
        }
        if (envVars.get("BOX64_MMAP32").equals("1") && !this.wineInfo.isArm64EC()) {
            Log.d("GuestProgramLauncherComponent", "Disabling map memory placed");
            envVars.put("WRAPPER_DISABLE_PLACED", "1");
        }
        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
        envVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
        envVars.put("LD_LIBRARY_PATH", rootDir.getPath() + "/usr/lib:/system/lib64");
        envVars.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
        envVars.put("GST_PLUGIN_PATH", rootDir.getPath() + "/usr/lib/gstreamer-1.0");
        envVars.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");
        envVars.put("VK_LAYER_PATH", rootDir.getPath() + "/usr/share/vulkan/implicit_layer.d:" + rootDir.getPath() + "/usr/share/vulkan/explicit_layer.d");
        envVars.put("WRAPPER_LAYER_PATH", rootDir.getPath() + "/usr/lib");
        envVars.put("WRAPPER_CACHE_PATH", rootDir.getPath() + "/usr/var/cache");
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("DISPLAY", ":0");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
        envVars.put("ALSA_CONFIG_PATH", rootDir.getPath() + "/usr/share/alsa/alsa.conf:" + rootDir.getPath() + "/usr/etc/alsa/conf.d/android_aserver.conf");
        envVars.put("ALSA_PLUGIN_DIR", rootDir.getPath() + "/usr/lib/alsa-lib");
        envVars.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
        envVars.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        envVars.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
        envVars.put("WINE_X11FORCEGLX", "1");
        envVars.put("WINE_GST_NO_GL", "1");
        envVars.put("SteamGameId", "0");
        envVars.put("PROTON_AUDIO_CONVERT", "0");
        envVars.put("PROTON_VIDEO_CONVERT", "0");
        envVars.put("PROTON_DEMUX", "0");
        String winePath = imageFs.getWinePath() + "/bin";
        Log.d("GuestProgramLauncherComponent", "WinePath is " + winePath);
        envVars.put("PATH", winePath + ":" + rootDir.getPath() + "/usr/bin");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        Object primaryDNS = "8.8.4.4";
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService("connectivity");
        if (connectivityManager.getActiveNetwork() != null) {
            ArrayList<InetAddress> dnsServers = new ArrayList<>(connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork()).getDnsServers());
            primaryDNS = dnsServers.get(0).toString().substring(1);
        }
        envVars.put("ANDROID_RESOLV_DNS", primaryDNS);
        envVars.put("WINE_NEW_NDIS", "1");
        if (!new File(imageFs.getLibDir(), "libandroid-sysvshm.so").exists()) {
            ld_preload = "";
        } else {
            ld_preload = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        }
        File fakeinputDest = new File(imageFs.getLibDir(), "libfakeinput.so");
        String nativeLibDir = this.environment.getContext().getApplicationInfo().nativeLibraryDir;
        File fakeinputSrc = new File(nativeLibDir, "libfakeinput.so");
        Log.d("GuestLauncher", "nativeLibDir: " + nativeLibDir);
        Log.d("GuestLauncher", "fakeinputSrc exists: " + fakeinputSrc.exists());
        Log.d("GuestLauncher", "fakeinputDest: " + fakeinputDest.getAbsolutePath());
        if (!fakeinputDest.exists()) {
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
        }
        Log.d("GuestLauncher", "fakeinputDest exists after copy: " + fakeinputDest.exists());
        if (fakeinputDest.exists()) {
            if (!ld_preload.isEmpty()) {
                ld_preload = ld_preload + ":";
            }
            ld_preload = ld_preload + fakeinputDest.getAbsolutePath();
        }
        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        devInputDir.mkdirs();
        File event0 = new File(devInputDir, "event0");
        if (!event0.exists()) {
            try {
                event0.createNewFile();
            } catch (Exception e2) {
            }
        }
        envVars.put("FAKE_EVDEV_DIR", devInputDir.getAbsolutePath());
        Log.d("GuestLauncher", "Final LD_PRELOAD: " + ld_preload);
        envVars.put("LD_PRELOAD", ld_preload);
        if (this.envVars.has("MANGOHUD")) {
            this.envVars.remove("MANGOHUD");
        }
        if (this.envVars.has("MANGOHUD_CONFIG")) {
            this.envVars.remove("MANGOHUD_CONFIG");
        }
        if (this.envVars != null) {
            envVars.putAll(this.envVars);
        }
        String emulator = this.container.getEmulator();
        if (this.shortcut != null) {
            emulator = this.shortcut.getExtra("emulator", this.container.getEmulator());
        }
        String overriddenCommand = envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
        if (overriddenCommand.isEmpty()) {
            if (this.wineInfo.isArm64EC()) {
                command = winePath + "/" + this.guestExecutable;
                if (emulator.toLowerCase().equals("fexcore")) {
                    envVars.put("HODLL", "libwow64fex.dll");
                } else {
                    envVars.put("HODLL", "wowbox64.dll");
                }
            } else {
                command = imageFs.getBinDir() + "/box64 " + this.guestExecutable;
            }
        } else {
            String[] parts = overriddenCommand.split(";");
            int length = parts.length;
            String command2 = "";
            int i = 0;
            while (i < length) {
                int i2 = length;
                String part = parts[i];
                command2 = command2 + part + " ";
                i++;
                length = i2;
                parts = parts;
            }
            command = command2.trim();
        }
        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, UnixStat.DEFAULT_DIR_PERM);
        }
        return ProcessHelper.exec(command, envVars.toStringArray(), rootDir, new Callback() { // from class: com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent$$ExternalSyntheticLambda0
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                GuestProgramLauncherComponent.this.lambda$execGuestProgram$0((Integer) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$execGuestProgram$0(Integer status) {
        synchronized (lock) {
            pid = -1;
        }
        if (this.terminationCallback != null) {
            this.terminationCallback.call(status);
        }
    }

    private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
        envVars.put("BOX64_NOBANNER", enableLogs ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        if (enableLogs) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
        }
        envVars.putAll(Box64PresetManager.getEnvVars("box64", this.environment.getContext(), this.box64Preset));
        envVars.put("BOX64_X11GLX", "1");
        envVars.put("BOX64_NORCFILES", "1");
    }

    public void suspendProcess() {
        synchronized (lock) {
            if (pid != -1) {
                ProcessHelper.suspendProcess(pid);
            }
        }
    }

    public void resumeProcess() {
        synchronized (lock) {
            if (pid != -1) {
                ProcessHelper.resumeProcess(pid);
            }
        }
    }
}
