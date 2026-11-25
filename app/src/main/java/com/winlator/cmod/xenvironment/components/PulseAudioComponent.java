package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.os.Process;

import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.Sha256Util;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;

/** PulseAudio 17 with the upstream Android Oboe sink. */
public class PulseAudioComponent extends EnvironmentComponent {
    private static final String ASSET = "pulseaudio.tzst";
    private static final String MARKER = ".pulseaudio.sha256sum";
    private static final Object lock = new Object();
    private static int pid = -1;
    private final UnixSocketConfig socketConfig;
    private final EnvVars guestEnv;

    public PulseAudioComponent(UnixSocketConfig socketConfig, EnvVars guestEnv) {
        this.socketConfig = socketConfig;
        this.guestEnv = guestEnv;
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
            if (pid > 0) Process.killProcess(pid);
            pid = -1;
        }
    }

    private static boolean runtimeReady(File directory) {
        File daemon = new File(directory, "pulseaudio");
        File module = new File(directory, "modules/module-oboe-sink.so");
        return daemon.isFile() && daemon.length() > 0 && daemon.canExecute()
                && module.isFile() && module.length() > 0;
    }

    static void install(Context context, File directory) {
        try {
            byte[] digest = Sha256Util.calculate(context, ASSET);
            File marker = new File(directory, MARKER);
            if (runtimeReady(directory) && Sha256Util.matches(marker, digest)) return;

            // Isolate the new runtime from the old GN PulseAudio 13 libraries.
            FileUtils.delete(directory);
            if (!directory.mkdirs() && !directory.isDirectory())
                throw new IOException("Cannot create PulseAudio runtime");
            FileUtils.chmod(directory, 0771);
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, ASSET, directory)
                    || !runtimeReady(directory))
                throw new IOException("Incomplete PulseAudio runtime");
            Sha256Util.write(marker, digest);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to install PulseAudio 17", error);
        }
    }

    private int execPulseAudio() {
        Context context = environment.getContext();
        File workingDir = new File(context.getFilesDir(), "pulseaudio-oboe");
        install(context, workingDir);

        File configDir = new File(workingDir, ".config");
        if (configDir.exists()) FileUtils.delete(configDir);

        String api = "1".equals(guestEnv.get("PULSEAUDIO_API")) ? "1" : "0";
        File configFile = new File(workingDir, "default.pa");
        if (!FileUtils.writeString(configFile, String.join("\n",
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\"" + socketConfig.path + "\"",
                "load-module module-oboe-sink api=" + api,
                "set-default-sink OboeSink")))
            throw new IllegalStateException("Unable to write PulseAudio configuration");

        EnvVars env = new EnvVars();
        env.put("LD_LIBRARY_PATH", new File(workingDir, "modules") + ":" + workingDir.getAbsolutePath());
        env.put("HOME", workingDir);
        env.put("TMPDIR", environment.getTmpDir());
        if (guestEnv.has("PULSE_LATENCY_MSEC"))
            env.put("PULSE_LATENCY_MSEC", guestEnv.get("PULSE_LATENCY_MSEC"));

        String command = new File(workingDir, "pulseaudio").getAbsolutePath();
        command += " --system=false --disable-shm=true --fail=false";
        command += " -n --file=default.pa --daemonize=false --use-pid-file=false --exit-idle-time=-1";
        return ProcessHelper.exec(command, env.toStringArray(), workingDir);
    }
}
