package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.os.Process;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public class PulseAudioComponent extends EnvironmentComponent {
    // GN binaries retain the original SONAMEs inside their private runtime.
    private static final String[] LIBRARY_NAMES = {
            "libltdl.so",
            "libpulseaudio.so",
            "libpulse.so",
            "libpulsecommon-13.0.so",
            "libpulsecore-13.0.so",
            "libsndfile.so"
    };
    private static final String[] GN_LIBRARY_NAMES = {
            "libgn_ltdl.so",
            "libgn_pulseaudio.so",
            "libgn_pulse.so",
            "libgn_pulsecommon-13.0.so",
            "libgn_pulsecore-13.0.so",
            "libgn_sndfile.so"
    };

    private final UnixSocketConfig socketConfig;
    private static int pid = -1;
    private static final Object lock = new Object();

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            pid = execPulseAudio();
        }
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

    private void copyFromLibraryDir(File dst) {
        String[] sourceNames = GN_LIBRARY_NAMES;
        for (int i = 0; i < LIBRARY_NAMES.length; i++) {
            String path = "lib/arm64-v8a/" + sourceNames[i];
            ClassLoader loader = PulseAudioComponent.class.getClassLoader();
            URL resource = loader != null ? loader.getResource(path) : null;
            Path destination = Paths.get(dst.getAbsolutePath(), LIBRARY_NAMES[i]);
            try (InputStream input = resource != null ? resource.openStream() : null) {
                if (input == null) {
                    throw new IllegalStateException("Missing PulseAudio library: " + sourceNames[i]);
                }
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                FileUtils.chmod(destination.toFile(), 0771);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }
    }

    private int execPulseAudio() {
        Context context = environment.getContext();
        String runtimeName = "pulseaudio-gn";
        String assetName = "pulseaudio-gamenative-20260612.tzst";
        String markerName = ".gamenative-20260612";

        File workingDir = new File(context.getFilesDir(), runtimeName);
        File versionMarker = new File(workingDir, markerName);
        if (!versionMarker.isFile()) {
            FileUtils.delete(workingDir);
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
            boolean extracted = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    assetName,
                    workingDir);
            if (!extracted) {
                throw new IllegalStateException("Unable to extract " + assetName);
            }
            FileUtils.writeString(versionMarker, assetName);
        }

        File configDir = new File(workingDir, ".config");
        if (configDir.exists()) FileUtils.delete(configDir);

        File configFile = new File(workingDir, "default.pa");
        FileUtils.writeString(configFile, String.join("\n",
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\"" + socketConfig.path + "\"",
                "load-module module-aaudio-sink volume=1.0 performance_mode=1 low_latency=true"
        ));

        String archName = AppUtils.getArchName();
        File modulesDir = new File(workingDir, "modules");
        String systemLibPath = archName.equals("arm64") ? "/system/lib64" : "/system/lib";

        ArrayList<String> envVars = new ArrayList<>();
        envVars.add("LD_LIBRARY_PATH=" + systemLibPath + ":" + modulesDir + ":" + workingDir.getAbsolutePath());
        envVars.add("HOME=" + workingDir);
        envVars.add("TMPDIR=" + environment.getTmpDir());

        copyFromLibraryDir(workingDir);

        String command = workingDir.getAbsolutePath() + "/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=false";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        return ProcessHelper.exec(command, envVars.toArray(new String[0]), workingDir);
    }
}
