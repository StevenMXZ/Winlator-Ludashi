package com.winlator.cmod.widget;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.widget.FrameLayout;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.xserver.XServer;

/* loaded from: classes14.dex */
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        this.renderer = new GLRenderer(this, xServer);
        setRenderer(this.renderer);
        setRenderMode(0);
    }

    public GLRenderer getRenderer() {
        return this.renderer;
    }
}
