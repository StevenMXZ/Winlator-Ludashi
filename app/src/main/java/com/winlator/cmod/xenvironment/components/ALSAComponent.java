package com.winlator.cmod.xenvironment.components;

import android.content.Context;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

/** Installs the direct ALSA–Oboe plugin; no Java audio server is needed. */
public class ALSAComponent extends EnvironmentComponent {
    private static final String VERSION = "766a84a";
    private final Context context;
    private final ImageFs imageFs;

    public ALSAComponent(Context context, ImageFs imageFs) {
        this.context = context;
        this.imageFs = imageFs;
    }

    static void install(Context context, File root) {
        File module = new File(root, "usr/lib/alsa-lib/libasound_module_pcm_android_alsa.so");
        File config = new File(root, "usr/etc/alsa/conf.d/android_alsa.conf");
        File baseConfig = new File(root, "usr/share/alsa/alsa.conf");
        File marker = new File(root, "usr/lib/alsa-lib/.alsa-oboe-version");
        if (marker.isFile() && VERSION.equals(FileUtils.readString(marker)) && module.length() > 0
                && config.length() > 0 && baseConfig.length() > 0) return;

        // The upstream audio archive also contains an unrelated DisplayX binary.
        // Install only audio files so enabling sound cannot replace the graphics layer.
        boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                context, "alsa.tzst", root,
                (file, size) -> file.getName().equals("displayx_layer.so") ? null : file);
        if (!extracted || module.length() == 0 || config.length() == 0 || baseConfig.length() == 0)
            throw new IllegalStateException("Unable to install Alsa-Oboe");
        if (!FileUtils.writeString(marker, VERSION))
            throw new IllegalStateException("Unable to record Alsa-Oboe installation");

        // Retire the old socket-based module after the replacement is installed.
        FileUtils.delete(new File(root, "usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so"));
        FileUtils.delete(new File(root, "usr/etc/alsa/conf.d/android_aserver.conf"));
    }

    @Override
    public void start() {
        install(context, imageFs.getRootDir());
    }

    @Override
    public void stop() {
        // Oboe streams belong to the guest process and terminate with it.
    }
}
