package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.os.Process;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
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

/* loaded from: classes10.dex */
public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private static int pid = -1;
    private static final Object lock = new Object();

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void start() {
        synchronized (lock) {
            stop();
            pid = execPulseAudio();
        }
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

    private void copyFromLibraryDir(File dst) {
        String[] libs = {"libltdl.so", "libpulseaudio.so", "libpulse.so", "libpulsecommon-13.0.so", "libpulsecore-13.0.so", "libsndfile.so"};
        for (int i = 0; i < libs.length; i++) {
            String path = "lib/arm64-v8a/" + libs[i];
            ClassLoader loader = PulseAudioComponent.class.getClassLoader();
            InputStream is = null;
            URL res = loader != null ? loader.getResource(path) : null;
            Path dstDir = Paths.get(dst.getAbsolutePath() + "/" + libs[i], new String[0]);
            if (res != null) {
                try {
                    is = res.openStream();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (is != null) {
                Files.copy(is, dstDir, StandardCopyOption.REPLACE_EXISTING);
                FileUtils.chmod(dstDir.toFile(), 505);
            }
        }
    }

    private int execPulseAudio() {
        Context context = this.environment.getContext();
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 505);
        }
        File configFile = new File(workingDir, "default.pa");
        FileUtils.writeString(configFile, String.join("\n", "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\"" + this.socketConfig.path + "\"", "load-module module-aaudio-sink", "set-default-sink AAudioSink"));
        String archName = AppUtils.getArchName();
        File modulesDir = new File(workingDir, "modules/" + archName);
        String systemLibPath = archName.equals("arm64") ? "/system/lib64" : "system/lib";
        ArrayList<String> envVars = new ArrayList<>();
        envVars.add("LD_LIBRARY_PATH=" + systemLibPath + ":" + modulesDir + ":" + workingDir.getAbsolutePath());
        envVars.add("HOME=" + workingDir);
        envVars.add("TMPDIR=" + this.environment.getTmpDir());
        copyFromLibraryDir(workingDir);
        String command = workingDir.getAbsolutePath() + "/libpulseaudio.so";
        return ProcessHelper.exec(((((((command + " --system=false") + " --disable-shm=true") + " --fail=false") + " -n --file=default.pa") + " --daemonize=false") + " --use-pid-file=false") + " --exit-idle-time=-1", (String[]) envVars.toArray(new String[0]), workingDir);
    }
}
