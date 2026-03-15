package com.winlator.cmod.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.widget.Toast;
import com.ludashi.benchmark.R;
import com.winlator.cmod.XrActivity;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.material.CursorMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import com.winlator.cmod.renderer.material.WindowMaterial;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import java.util.ArrayList;
import java.util.Iterator;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import org.apache.commons.compress.archivers.tar.TarConstants;

/* loaded from: classes12.dex */
public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    private static final String TAG = "GLRenderer";
    private FrameRating frameRating;
    public int surfaceHeight;
    public int surfaceWidth;
    private final XServer xServer;
    public final XServerView xServerView;
    public final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private boolean fullscreen = false;
    public boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private String[] unviewableWMClasses = null;
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    private boolean screenOffsetYRelativeToCursor = false;
    private boolean cpuSaverMode = false;
    private int currentFpsLimit = 0;
    private boolean wasDirectMode = false;
    private final EffectComposer effectComposer = new EffectComposer(this);
    private final Drawable rootCursorDrawable = createRootCursorDrawable();

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        this.quadVertices.put(new float[]{0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f});
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    public void setFrameRating(FrameRating frameRating) {
        this.frameRating = frameRating;
    }

    public FrameRating getFrameRating() {
        return this.frameRating;
    }

    public void setNativeMode(boolean enable) {
        if (this.cpuSaverMode != enable) {
            this.cpuSaverMode = enable;
            this.viewportNeedsUpdate = true;
            final String msg = enable ? "Direct Rendering+ Enabled" : "Direct Rendering+ Disabled";
            this.xServerView.post(new Runnable() { // from class: com.winlator.cmod.renderer.GLRenderer$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    GLRenderer.this.lambda$setNativeMode$0(msg);
                }
            });
            this.xServerView.setRenderMode(1);
            this.xServerView.requestRender();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setNativeMode$0(String msg) {
        Toast.makeText(this.xServerView.getContext(), msg, 0).show();
    }

    public boolean isNativeMode() {
        return this.cpuSaverMode;
    }

    public void setFpsLimit(int fps) {
        this.currentFpsLimit = fps;
    }

    public int getFpsLimit() {
        return this.currentFpsLimit;
    }

    private void requestRenderSafe() {
        this.xServerView.requestRender();
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        requestRenderSafe();
    }

    public boolean isCursorVisible() {
        return this.cursorVisible;
    }

    public boolean isScreenOffsetYRelativeToCursor() {
        return this.screenOffsetYRelativeToCursor;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        requestRenderSafe();
    }

    public boolean isFullscreen() {
        return this.fullscreen;
    }

    public float getMagnifierZoom() {
        return this.magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        requestRenderSafe();
    }

    public int getSurfaceWidth() {
        return this.surfaceWidth;
    }

    public int getSurfaceHeight() {
        return this.surfaceHeight;
    }

    public boolean isViewportNeedsUpdate() {
        return this.viewportNeedsUpdate;
    }

    public void setViewportNeedsUpdate(boolean viewportNeedsUpdate) {
        this.viewportNeedsUpdate = viewportNeedsUpdate;
    }

    public VertexAttribute getQuadVertices() {
        return this.quadVertices;
    }

    public EffectComposer getEffectComposer() {
        return this.effectComposer;
    }

    public void setUnviewableWMClasses(String... unviewableWMNames) {
        this.unviewableWMClasses = unviewableWMNames;
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GPUImage.checkIsSupported();
        GLES20.glFrontFace(2305);
        GLES20.glDisable(2884);
        GLES20.glDisable(2929);
        GLES20.glDepthMask(false);
        GLES20.glEnable(3042);
        GLES20.glBlendFunc(770, 771);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (XrActivity.isEnabled(null)) {
            XrActivity activity = XrActivity.getInstance();
            activity.init();
            width = activity.getWidth();
            height = activity.getHeight();
            GLES20.glViewport(0, 0, width, height);
            this.magnifierEnabled = false;
        }
        this.surfaceWidth = width;
        this.surfaceHeight = height;
        this.viewTransformation.update(width, height, this.xServer.screenInfo.width, this.xServer.screenInfo.height);
        this.viewportNeedsUpdate = true;
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onDrawFrame(GL10 gl) {
        if (this.cpuSaverMode) {
            drawFrameOptimized();
            return;
        }
        if (this.frameRating != null) {
            this.frameRating.setIsNative(false);
        }
        if (this.effectComposer != null && this.effectComposer.hasEffects() && this.surfaceWidth > 0 && this.surfaceHeight > 0) {
            try {
                this.effectComposer.render();
                return;
            } catch (Exception e) {
                drawFrame();
                return;
            }
        }
        drawFrame();
    }

    private void drawFrameOptimized() {
        RenderableWindow directCandidate = null;
        int screenW = this.xServer.screenInfo.width;
        int screenH = this.xServer.screenInfo.height;
        XLock lock = this.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER);
        try {
            int i = this.renderableWindows.size() - 1;
            while (true) {
                if (i < 0) {
                    break;
                }
                RenderableWindow rWin = this.renderableWindows.get(i);
                if (rWin.content == null || rWin.content.width < screenW * 0.95f || rWin.content.height < screenH * 0.95f) {
                    i--;
                } else {
                    directCandidate = rWin;
                    break;
                }
            }
            if (lock != null) {
                lock.close();
            }
            boolean isDirect = directCandidate != null;
            if (isDirect != this.wasDirectMode) {
                this.viewportNeedsUpdate = true;
                this.wasDirectMode = isDirect;
            }
            if (isDirect) {
                if (this.viewportNeedsUpdate) {
                    if (!this.fullscreen) {
                        GLES20.glViewport(this.viewTransformation.viewOffsetX, this.viewTransformation.viewOffsetY, this.viewTransformation.viewWidth, this.viewTransformation.viewHeight);
                    } else {
                        GLES20.glViewport(0, 0, this.surfaceWidth, this.surfaceHeight);
                    }
                    this.viewportNeedsUpdate = false;
                }
                GLES20.glClear(16384);
                if (this.frameRating != null) {
                    this.frameRating.setIsNative(true);
                }
                GLES20.glDisable(3042);
                if (this.magnifierEnabled) {
                    float pointerX = 0.0f;
                    float pointerY = 0.0f;
                    float currentZoom = !this.screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;
                    if (currentZoom != 1.0f) {
                        pointerX = Mathf.clamp((this.xServer.pointer.getX() * currentZoom) - (this.xServer.screenInfo.width * 0.5f), 0.0f, this.xServer.screenInfo.width * Math.abs(1.0f - currentZoom));
                    }
                    if (this.screenOffsetYRelativeToCursor || currentZoom != 1.0f) {
                        float scaleY = currentZoom != 1.0f ? Math.abs(1.0f - currentZoom) : 0.5f;
                        float offsetY = this.xServer.screenInfo.height * (this.screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                        pointerY = Mathf.clamp((this.xServer.pointer.getY() * currentZoom) - offsetY, 0.0f, this.xServer.screenInfo.height * scaleY);
                    }
                    XForm.makeTransform(this.tmpXForm2, -pointerX, -pointerY, currentZoom, currentZoom, 0.0f);
                } else if (!this.fullscreen) {
                    int pointerY2 = 0;
                    if (this.screenOffsetYRelativeToCursor) {
                        short halfScreenHeight = (short) (this.xServer.screenInfo.height / 2);
                        pointerY2 = Mathf.clamp(this.xServer.pointer.getY() - (halfScreenHeight / 2), 0, (int) halfScreenHeight);
                    }
                    XForm.makeTransform(this.tmpXForm2, this.viewTransformation.sceneOffsetX, this.viewTransformation.sceneOffsetY - pointerY2, this.viewTransformation.sceneScaleX, this.viewTransformation.sceneScaleY, 0.0f);
                    GLES20.glEnable(3089);
                    GLES20.glScissor(this.viewTransformation.viewOffsetX, this.viewTransformation.viewOffsetY, this.viewTransformation.viewWidth, this.viewTransformation.viewHeight);
                } else {
                    XForm.identity(this.tmpXForm2);
                }
                this.windowMaterial.use();
                GLES20.glUniform2f(this.windowMaterial.getUniformLocation("viewSize"), this.xServer.screenInfo.width, this.xServer.screenInfo.height);
                this.quadVertices.bind(this.windowMaterial.programId);
                renderDrawable(directCandidate.content, directCandidate.rootX, directCandidate.rootY, this.windowMaterial);
                if (this.cursorVisible) {
                    GLES20.glEnable(3042);
                    GLES20.glBlendFunc(770, 771);
                    renderCursor();
                }
                if (!this.magnifierEnabled && !this.fullscreen) {
                    GLES20.glDisable(3089);
                }
                this.quadVertices.disable();
                return;
            }
            if (this.frameRating != null) {
                this.frameRating.setIsNative(false);
            }
            if (this.viewportNeedsUpdate && this.magnifierEnabled) {
                if (!this.fullscreen) {
                    GLES20.glViewport(this.viewTransformation.viewOffsetX, this.viewTransformation.viewOffsetY, this.viewTransformation.viewWidth, this.viewTransformation.viewHeight);
                } else {
                    GLES20.glViewport(0, 0, this.surfaceWidth, this.surfaceHeight);
                }
                this.viewportNeedsUpdate = false;
            }
            GLES20.glClear(16384);
            GLES20.glEnable(3042);
            GLES20.glBlendFunc(770, 771);
            if (this.magnifierEnabled) {
                float pointerX2 = 0.0f;
                float pointerY3 = 0.0f;
                float currentZoom2 = !this.screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;
                if (currentZoom2 != 1.0f) {
                    pointerX2 = Mathf.clamp((this.xServer.pointer.getX() * currentZoom2) - (this.xServer.screenInfo.width * 0.5f), 0.0f, this.xServer.screenInfo.width * Math.abs(1.0f - currentZoom2));
                }
                if (this.screenOffsetYRelativeToCursor || currentZoom2 != 1.0f) {
                    float scaleY2 = currentZoom2 != 1.0f ? Math.abs(1.0f - currentZoom2) : 0.5f;
                    float offsetY2 = this.xServer.screenInfo.height * (this.screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                    pointerY3 = Mathf.clamp((this.xServer.pointer.getY() * currentZoom2) - offsetY2, 0.0f, this.xServer.screenInfo.height * scaleY2);
                }
                XForm.makeTransform(this.tmpXForm2, -pointerX2, -pointerY3, currentZoom2, currentZoom2, 0.0f);
            } else if (!this.fullscreen) {
                int pointerY4 = 0;
                if (this.screenOffsetYRelativeToCursor) {
                    short halfScreenHeight2 = (short) (this.xServer.screenInfo.height / 2);
                    pointerY4 = Mathf.clamp(this.xServer.pointer.getY() - (halfScreenHeight2 / 2), 0, (int) halfScreenHeight2);
                }
                XForm.makeTransform(this.tmpXForm2, this.viewTransformation.sceneOffsetX, this.viewTransformation.sceneOffsetY - pointerY4, this.viewTransformation.sceneScaleX, this.viewTransformation.sceneScaleY, 0.0f);
                GLES20.glEnable(3089);
                GLES20.glScissor(this.viewTransformation.viewOffsetX, this.viewTransformation.viewOffsetY, this.viewTransformation.viewWidth, this.viewTransformation.viewHeight);
            } else {
                XForm.identity(this.tmpXForm2);
            }
            this.windowMaterial.use();
            GLES20.glUniform2f(this.windowMaterial.getUniformLocation("viewSize"), screenW, screenH);
            this.quadVertices.bind(this.windowMaterial.programId);
            lock = this.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER);
            try {
                Iterator<RenderableWindow> it = this.renderableWindows.iterator();
                while (it.hasNext()) {
                    RenderableWindow window = it.next();
                    renderDrawable(window.content, window.rootX, window.rootY, this.windowMaterial);
                }
                if (lock != null) {
                    lock.close();
                }
                if (this.cursorVisible) {
                    renderCursor();
                }
                if (!this.magnifierEnabled && !this.fullscreen) {
                    GLES20.glDisable(3089);
                }
                this.quadVertices.disable();
            } finally {
            }
        } finally {
        }
    }

    public void drawFrame() {
        boolean xrFrame = false;
        if (XrActivity.isEnabled(null)) {
            boolean xrImmersive = XrActivity.getImmersive();
            xrFrame = XrActivity.getInstance().beginFrame(xrImmersive, XrActivity.getSBS());
        }
        if (this.viewportNeedsUpdate && this.magnifierEnabled) {
            if (this.fullscreen) {
                GLES20.glViewport(0, 0, this.surfaceWidth, this.surfaceHeight);
            } else {
                GLES20.glViewport(this.viewTransformation.viewOffsetX, this.viewTransformation.viewOffsetY, this.viewTransformation.viewWidth, this.viewTransformation.viewHeight);
            }
            this.viewportNeedsUpdate = false;
        }
        GLES20.glClear(16384);
        if (this.magnifierEnabled) {
            float pointerX = 0.0f;
            float pointerY = 0.0f;
            float magnifierZoom = !this.screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;
            if (magnifierZoom != 1.0f) {
                pointerX = Mathf.clamp((this.xServer.pointer.getX() * magnifierZoom) - (this.xServer.screenInfo.width * 0.5f), 0.0f, this.xServer.screenInfo.width * Math.abs(1.0f - magnifierZoom));
            }
            if (this.screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
                float scaleY = magnifierZoom != 1.0f ? Math.abs(1.0f - magnifierZoom) : 0.5f;
                float offsetY = this.xServer.screenInfo.height * (this.screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                pointerY = Mathf.clamp((this.xServer.pointer.getY() * magnifierZoom) - offsetY, 0.0f, this.xServer.screenInfo.height * scaleY);
            }
            XForm.makeTransform(this.tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0.0f);
        } else if (!this.fullscreen) {
            int pointerY2 = 0;
            if (this.screenOffsetYRelativeToCursor) {
                short halfScreenHeight = (short) (this.xServer.screenInfo.height / 2);
                pointerY2 = Mathf.clamp(this.xServer.pointer.getY() - (halfScreenHeight / 2), 0, (int) halfScreenHeight);
            }
            XForm.makeTransform(this.tmpXForm2, this.viewTransformation.sceneOffsetX, this.viewTransformation.sceneOffsetY - pointerY2, this.viewTransformation.sceneScaleX, this.viewTransformation.sceneScaleY, 0.0f);
            GLES20.glEnable(3089);
            GLES20.glScissor(this.viewTransformation.viewOffsetX, this.viewTransformation.viewOffsetY, this.viewTransformation.viewWidth, this.viewTransformation.viewHeight);
        } else {
            XForm.identity(this.tmpXForm2);
        }
        renderWindows();
        if (this.cursorVisible) {
            GLES20.glEnable(3042);
            GLES20.glBlendFunc(770, 771);
            renderCursor();
        }
        if (!this.magnifierEnabled && !this.fullscreen) {
            GLES20.glDisable(3089);
        }
        if (xrFrame) {
            XrActivity.getInstance().endFrame();
            XrActivity.updateControllers();
            this.xServerView.requestRender();
        }
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onUpdateWindowContent(Window window) {
        this.xServerView.requestRender();
    }

    @Override // com.winlator.cmod.xserver.Pointer.OnPointerMotionListener
    public void onPointerMove(short x, short y) {
        requestRenderSafe();
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onMapWindow(Window window) {
        this.xServerView.queueEvent(new UpdateSceneRunnable(this));
        requestRenderSafe();
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onUnmapWindow(Window window) {
        this.xServerView.queueEvent(new UpdateSceneRunnable(this));
        requestRenderSafe();
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onChangeWindowZOrder(Window window) {
        this.xServerView.queueEvent(new UpdateSceneRunnable(this));
        requestRenderSafe();
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        if (resized) {
            this.xServerView.queueEvent(new UpdateSceneRunnable(this));
        } else {
            this.xServerView.queueEvent(new Runnable() { // from class: com.winlator.cmod.renderer.GLRenderer$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    GLRenderer.this.lambda$onUpdateWindowGeometry$1(window);
                }
            });
        }
        requestRenderSafe();
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(16384)) {
            requestRenderSafe();
        }
    }

    public void toggleFullscreen() {
        this.fullscreen = !this.fullscreen;
        this.viewportNeedsUpdate = true;
        requestRenderSafe();
    }

    private Drawable createRootCursorDrawable() {
        Context context = this.xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateScene() {
        XLock lock = this.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER);
        try {
            this.renderableWindows.clear();
            collectRenderableWindows(this.xServer.windowManager.rootWindow, this.xServer.windowManager.rootWindow.getX(), this.xServer.windowManager.rootWindow.getY());
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (window.attributes.isMapped()) {
            if (window != this.xServer.windowManager.rootWindow) {
                boolean viewable = true;
                if (this.unviewableWMClasses != null) {
                    String wmClass = window.getClassName();
                    String[] strArr = this.unviewableWMClasses;
                    int length = strArr.length;
                    int i = 0;
                    while (true) {
                        if (i >= length) {
                            break;
                        }
                        String unviewableWMClass = strArr[i];
                        if (!wmClass.contains(unviewableWMClass)) {
                            i++;
                        } else {
                            if (window.attributes.isEnabled()) {
                                window.disableAllDescendants();
                            }
                            viewable = false;
                        }
                    }
                }
                if (viewable) {
                    this.renderableWindows.add(new RenderableWindow(window.getContent(), x, y));
                }
            }
            for (Window child : window.getChildren()) {
                collectRenderableWindows(child, child.getX() + x, child.getY() + y);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: updateWindowPosition, reason: merged with bridge method [inline-methods] */
    public void lambda$onUpdateWindowGeometry$1(Window window) {
        Iterator<RenderableWindow> it = this.renderableWindows.iterator();
        while (it.hasNext()) {
            RenderableWindow renderableWindow = it.next();
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                return;
            }
        }
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        if (drawable == null) {
            return;
        }
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);
            GLES20.glBindTexture(3553, texture.getTextureId());
            GLES20.glTexParameteri(3553, 10241, 9729);
            GLES20.glTexParameteri(3553, TarConstants.DEFAULT_BLKSIZE, 9729);
            XForm.set(this.tmpXForm1, x, y, drawable.width, drawable.height);
            XForm.multiply(this.tmpXForm1, this.tmpXForm1, this.tmpXForm2);
            GLES20.glActiveTexture(33984);
            GLES20.glBindTexture(3553, texture.getTextureId());
            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), this.tmpXForm1.length, this.tmpXForm1, 0);
            GLES20.glDrawArrays(5, 0, this.quadVertices.count());
            GLES20.glBindTexture(3553, 0);
        }
    }

    private void renderWindows() {
        this.windowMaterial.use();
        GLES20.glUniform2f(this.windowMaterial.getUniformLocation("viewSize"), this.xServer.screenInfo.width, this.xServer.screenInfo.height);
        this.quadVertices.bind(this.windowMaterial.programId);
        XLock lock = this.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER);
        int startIndex = 0;
        try {
            int screenWidth = this.xServer.screenInfo.width;
            int screenHeight = this.xServer.screenInfo.height;
            int i = this.renderableWindows.size() - 1;
            while (true) {
                if (i < 0) {
                    break;
                }
                RenderableWindow rWin = this.renderableWindows.get(i);
                if (rWin.content != null && rWin.content.width >= screenWidth && rWin.content.height >= screenHeight) {
                    startIndex = i;
                    break;
                }
                i--;
            }
            for (int i2 = startIndex; i2 < this.renderableWindows.size(); i2++) {
                RenderableWindow window = this.renderableWindows.get(i2);
                renderDrawable(window.content, window.rootX, window.rootY, this.windowMaterial);
            }
            if (lock != null) {
                lock.close();
            }
            this.quadVertices.disable();
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private void renderCursor() {
        this.cursorMaterial.use();
        GLES20.glUniform2f(this.cursorMaterial.getUniformLocation("viewSize"), this.xServer.screenInfo.width, this.xServer.screenInfo.height);
        this.quadVertices.bind(this.cursorMaterial.programId);
        XLock lock = this.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER);
        try {
            Window pointWindow = this.xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = this.xServer.pointer.getClampedX();
            short y = this.xServer.pointer.getClampedY();
            if (cursor != null) {
                if (cursor.isVisible()) {
                    renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, this.cursorMaterial);
                }
            } else {
                renderDrawable(this.rootCursorDrawable, x, y, this.cursorMaterial);
            }
            if (lock != null) {
                lock.close();
            }
            this.quadVertices.disable();
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private static class RenderableWindow {
        public final Drawable content;
        public int rootX;
        public int rootY;

        public RenderableWindow(Drawable content, int rootX, int rootY) {
            this.content = content;
            this.rootX = rootX;
            this.rootY = rootY;
        }
    }

    // ========== الكلاس الداخلي المُدمج من ExternalSyntheticLambda0 ==========
    private static final class UpdateSceneRunnable implements Runnable {
        private final GLRenderer renderer;

        public UpdateSceneRunnable(GLRenderer renderer) {
            this.renderer = renderer;
        }

        @Override
        public void run() {
            renderer.updateScene();
        }
    }
}