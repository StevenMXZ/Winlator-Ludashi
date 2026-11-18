package com.winlator.cmod.xenvironment.components;


import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.InputStream;

public class ALSAComponent extends EnvironmentComponent {
    private Context context;
    private ImageFs imageFS;
    
    public ALSAComponent(Context context, ImageFs imageFS) {
        this.context = context;
        this.imageFS = imageFS;
    }
    
    private void extractFromAssets() {
        File androidALSAModule = new File(imageFS.getLibDir(), "alsa-lib/libasound_module_pcm_android_alsa.so");
        if (!androidALSAModule.exists()) {
            Log.d("ALSAComponent", "Extracting ALSA assets");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "alsa.tzst", imageFS.getRootDir());
        }    
    }

    @Override
    public void start() {
        extractFromAssets();
    }

    @Override
    public void stop() {
    }
}
