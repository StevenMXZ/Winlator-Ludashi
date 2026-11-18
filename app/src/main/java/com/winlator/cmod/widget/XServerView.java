package com.winlator.cmod.widget;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.winlator.cmod.R;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.CursorManager;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.InputDeviceManager;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class XServerView extends SurfaceView implements SurfaceHolder.Callback, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener, CursorManager.OnCursorModificationListener {
    static final String RENDERER_LOG_STRING = "Renderer";
    private XServer xServer;
    private Context context;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean fullscreen = false;
    private String unviewableWMClass = null;
    private boolean screenOffsetYRelativeToCursor = false;
    private boolean toggleFullscreen = false;
    private boolean magnifierEnabled = true;
    private boolean viewportNeedsUpdate = true;
    private float magnifierZoom = 1.0f;
    private boolean cursorVisible = true;
    
    public XServerView(Context context, XServer xserver) {
        super(context);
        this.xServer = xserver;
        this.context = context;
        getHolder().addCallback(this);
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.cursorManager.addOnCursorModificationListener(this);
        nativeInit(this.context, xServer);
    }
    
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        nativeCreateSurface(getHolder().getSurface());
    }
    
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeDestroySurface();
    }
    
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeChangeSurface(width, height);
    }
    
    public void onDestroy() {
        nativeStop();
    }
    
    public void onPause() {
        nativePause();
    }
    
    public void onResume() {
        nativeResume();
    }
    
    public void toggleFullscreen() {
        toggleFullscreen = true;
        nativeToggleFullscreen();
    }
    
    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        nativeSetCursorVisible(cursorVisible);
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        nativeSetScreenOffsetYRelativeToCursor(screenOffsetYRelativeToCursor);
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        nativeSetMagnifierZoom(magnifierZoom);
    }

    public void setUnviewableWMClass(String unviewableWMName) {
        this.unviewableWMClass = unviewableWMName;
        nativeSetUnviewableWMClass(this.unviewableWMClass);
    }
    
    @Override
    public void onCreateWindow(Window window, Window parent) {
        nativeCreateWindow(window, parent.id);      
    }
    
    @Override
    public void onDestroyWindow(Window window) {
        nativeDestroyWindow(window.id);
    }

    @Override
    public void onMapWindow(Window window) {
        if (unviewableWMClass != null) {
            String wmClass = window.getClassName();
            if (wmClass.contains(unviewableWMClass)) {
                if (window.attributes.isEnabled()) {
                    window.disableAllDescendants();
                }    
            }
        }
        
        nativeMapWindow(window.id);
    }

    @Override
    public void onUnmapWindow(Window window) {
        nativeUnmapWindow(window.id);
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        nativeChangeWindowZOrder(stackMode == Window.StackMode.ABOVE ? 1 : 0, window.id, (sibling != null) ? sibling.id : -1);
    }
    
    @Override
    public void onUpdateWindowContent(Window window) {
        nativeUpdateWindowContent(window.id);
    }
    
    @Override 
    public void onUpdateWindowContentDirect(Window window, Drawable drawable) {
        nativeUpdateDirectContent(window.id, drawable.id);
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        nativeUpdateWindowGeometry(window.id, window.getWidth(), window.getHeight(), window.getX(), window.getY(), resized, window);
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            Cursor cursor = window.attributes.getCursor();
            if (cursor != null)
                nativeBindCursor(window.id, cursor.id, cursor.isVisible());
        }    
    }
    
    @Override
    public void onModifyWindowProperty(Window window, Property property) {
        if (property.nameAsString().equals("WM_CLASS"))
            nativeSetWindowClassName(window.id, property.toString());
    }
    
    @Override
    public void onReparentWindow(Window window, Window newParent, short x, short y) {
        nativeReparentWindow(window.id, newParent.id, x, y);
    }
    
    @Override
    public void onPointerMove(short x, short y) {
        nativePointerMove(x, y);
    }
    
    @Override
    public void onCreateCursor(Cursor cursor) {
        nativeCreateCursor(cursor);
    }
    
    @Override
    public void onFreeCursor(Cursor cursor) {
        nativeFreeCursor(cursor.id);
    }
    
    static {
        System.loadLibrary("winlator");
    }
    
    @FastNative
    public native void nativeCreateSurface(Surface surface);
    @FastNative
    public native void nativeDestroySurface();
    @FastNative
    public native void nativeInit(Context context, XServer xserver);
    @FastNative
    public native void nativeChangeSurface(int width, int height);
    @FastNative
    public native void nativeCreateWindow(Window window, int parentId);
    @FastNative
    public native void nativeDestroyWindow(int id);
    @FastNative
    public native void nativeCreateCursor(Cursor cursor);
    @FastNative
    public native void nativeFreeCursor(int id);
    @FastNative
    public native void nativeBindCursor(int windowId, int cursorId, boolean visible);
    @FastNative
    public native void nativeMapWindow(int id);
    @FastNative
    public native void nativeUnmapWindow(int id);
    @FastNative
    public native void nativeChangeWindowZOrder(int stackMode, int id, int siblingId);
    @FastNative
    public native void nativeUpdateWindowGeometry(int id, int width, int height, int x, int y, boolean resized, Window window);
    @CriticalNative
    public native static void nativePointerMove(int x, int y);
    @FastNative
    public native void nativeToggleFullscreen();
    @FastNative
    public native void nativeSetCursorVisible(boolean visible);
    @FastNative
    public native void nativeSetScreenOffsetYRelativeToCursor(boolean cond);
    @FastNative
    public native void nativeSetMagnifierZoom(float magnifierZoom);
    @FastNative
    public native void nativeSetUnviewableWMClass(String unviewableWMName);
    @FastNative
    public native void nativeSetWindowClassName(int id, String className);
    @FastNative
    public native void nativeUpdatePointWindow(int id);
    @CriticalNative
    public native static void nativeUpdateWindowContent(int id);
    @FastNative
    public native void nativeReparentWindow(int id, int parentId, short x, short y);
    @FastNative
    public native void nativePause();
    @FastNative
    public native void nativeResume();
    @FastNative
    public native void nativeStop();
    @FastNative
    public native void nativeAddDirectContent(int windowId, Drawable drawable);
    @CriticalNative
    public native static void nativeUpdateDirectContent(int windowId, int drawableId);
    @FastNative
    public native void nativeRemoveDirectContent(int windowId, int pixmapId);
    @FastNative
    public native void nativeSetCompositeRedirected(int windowId, boolean redirected);
    @CriticalNative
    public native static void nativeCompositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY);
}