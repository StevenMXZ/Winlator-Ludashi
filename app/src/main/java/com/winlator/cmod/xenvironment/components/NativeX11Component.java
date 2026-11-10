package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.widget.NativeX11View;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import java.io.File;

/** Exactly one display :0 owner; no Java XServerComponent is started for this backend. */
public final class NativeX11Component extends EnvironmentComponent {
    private final NativeX11View view;
    public NativeX11Component(NativeX11View view) { this.view = view; }
    @Override public void start() {
        File root = environment.getImageFs().getRootDir();
        view.start(new File(root, UnixSocketConfig.XSERVER_PATH.substring(1)).getAbsolutePath(),
                new File(root, UnixSocketConfig.SYSVSHM_SERVER_PATH.substring(1)).getAbsolutePath());
    }
    @Override public void stop() { view.close(); }
}
