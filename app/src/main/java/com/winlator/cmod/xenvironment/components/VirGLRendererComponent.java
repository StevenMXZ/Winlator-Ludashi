package com.winlator.cmod.xenvironment.components;

import android.util.Log;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.XServer;
import java.io.IOException;

/* loaded from: classes10.dex */
public class VirGLRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    private XConnectorEpoll connector;
    private long sharedEGLContextPtr;
    private final UnixSocketConfig socketConfig;
    private final XServer xServer;

    private native void destroyClient(long j);

    private native void destroyRenderer(long j);

    private native long getCurrentEGLContextPtr();

    private native long handleNewConnection(int i);

    private native void handleRequest(long j);

    static {
        System.loadLibrary("virglrenderer");
    }

    public VirGLRendererComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void start() {
        if (this.connector != null) {
            return;
        }
        this.connector = new XConnectorEpoll(this.socketConfig, this, this);
        this.connector.start();
    }

    @Override // com.winlator.cmod.xenvironment.EnvironmentComponent
    public void stop() {
        if (this.connector != null) {
            this.connector.stop();
            this.connector = null;
        }
    }

    private void killConnection(int fd) {
        this.connector.killConnection(this.connector.getClient(fd));
    }

    private long getSharedEGLContext() {
        if (this.sharedEGLContextPtr != 0) {
            return this.sharedEGLContextPtr;
        }
        final Thread thread = Thread.currentThread();
        try {
            GLRenderer renderer = this.xServer.getRenderer();
            renderer.xServerView.queueEvent(new Runnable() { // from class: com.winlator.cmod.xenvironment.components.VirGLRendererComponent$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    VirGLRendererComponent.this.lambda$getSharedEGLContext$0(thread);
                }
            });
            synchronized (thread) {
                thread.wait();
            }
            return this.sharedEGLContextPtr;
        } catch (Exception e) {
            return 0L;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$getSharedEGLContext$0(Thread thread) {
        this.sharedEGLContextPtr = getCurrentEGLContextPtr();
        synchronized (thread) {
            thread.notify();
        }
    }

    @Override // com.winlator.cmod.xconnector.ConnectionHandler
    public void handleConnectionShutdown(Client client) {
        long clientPtr = ((Long) client.getTag()).longValue();
        destroyClient(clientPtr);
    }

    @Override // com.winlator.cmod.xconnector.ConnectionHandler
    public void handleNewConnection(Client client) {
        getSharedEGLContext();
        long clientPtr = handleNewConnection(client.clientSocket.fd);
        client.setTag(Long.valueOf(clientPtr));
    }

    @Override // com.winlator.cmod.xconnector.RequestHandler
    public boolean handleRequest(Client client) throws IOException {
        long clientPtr = ((Long) client.getTag()).longValue();
        handleRequest(clientPtr);
        return true;
    }

    private void flushFrontbuffer(int drawableId, int framebuffer) {
        Drawable drawable = this.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            Log.e("VirGLRendererComponent", "Drawable not found for drawableId=" + drawableId);
            return;
        }
        synchronized (drawable.renderLock) {
            if (framebuffer == 0) {
                Log.e("VirGLRendererComponent", "Framebuffer is invalid for drawableId=" + drawableId);
                return;
            }
            Texture texture = drawable.getTexture();
            if (texture == null) {
                Log.e("VirGLRendererComponent", "Texture is null for drawableId=" + drawableId);
                return;
            }
            try {
                texture.copyFromFramebuffer(framebuffer, drawable.width, drawable.height);
                Runnable onDrawListener = drawable.getOnDrawListener();
                if (onDrawListener != null) {
                    onDrawListener.run();
                }
            } catch (Exception e) {
                Log.e("VirGLRendererComponent", "Error during framebuffer copy: " + e.getMessage(), e);
            }
        }
    }
}
