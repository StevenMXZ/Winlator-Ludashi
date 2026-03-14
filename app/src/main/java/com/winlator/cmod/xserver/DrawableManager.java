package com.winlator.cmod.xserver;

import android.util.SparseArray;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.XResourceManager;
import java.util.Objects;

/* loaded from: classes11.dex */
public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final SparseArray<Drawable> drawables = new SparseArray<>();
    private final XServer xServer;

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        Drawable drawable = this.drawables.get(id);
        if (drawable != null && drawable.getData() == null) {
            throw new IllegalStateException("Drawable with id " + id + " has null data when fetched.");
        }
        return drawable;
    }

    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, this.xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) {
            Drawable drawable = new Drawable(id, width, height, visual);
            if (drawable.getData() == null) {
                throw new IllegalStateException("Drawable with id 0 has null data at creation.");
            }
            return drawable;
        }
        if (this.drawables.indexOfKey(id) >= 0) {
            return null;
        }
        Drawable drawable2 = new Drawable(id, width, height, visual);
        if (drawable2.getData() == null) {
            throw new IllegalStateException("Drawable with id " + id + " has null data at creation.");
        }
        this.drawables.put(id, drawable2);
        return drawable2;
    }

    public void removeDrawable(int id) {
        Drawable drawable = this.drawables.get(id);
        if (drawable == null) {
            throw new IllegalStateException("Attempting to remove non-existent Drawable with id " + id);
        }
        if (drawable.getData() == null) {
            throw new IllegalStateException("Drawable with id " + id + " has null data during removal.");
        }
        Texture texture = drawable.getTexture();
        if (texture != null) {
            XServerView xServerView = this.xServer.getRenderer().xServerView;
            Objects.requireNonNull(texture);
            xServerView.queueEvent(new DestroyTextureRunnable(texture));
        }
        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) {
            onDestroyListener.call(drawable);
        }
        drawable.setOnDrawListener(null);
        this.drawables.remove(id);
    }

    @Override // com.winlator.cmod.xserver.XResourceManager.OnResourceLifecycleListener
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            Pixmap pixmap = (Pixmap) resource;
            Drawable drawable = pixmap.drawable;
            if (drawable.getData() == null) {
                throw new IllegalStateException("Drawable for Pixmap with id " + pixmap.drawable.id + " has null data during free.");
            }
            removeDrawable(drawable.id);
        }
    }

    public Visual getVisual() {
        return this.xServer.pixmapManager.visual;
    }

    // ========== الكلاس الداخلي المُدمج من ExternalSyntheticLambda0 ==========
    private static final class DestroyTextureRunnable implements Runnable {
        private final Texture texture;

        public DestroyTextureRunnable(Texture texture) {
            this.texture = texture;
        }

        @Override
        public void run() {
            texture.destroy();
        }
    }
}