package com.winlator.cmod.xserver;

import android.util.SparseArray;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.renderer.GPUImage;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable != null && drawable.getGPUImage().getAHB() == 0) {
            throw new IllegalStateException("Drawable with id " + id + " has null data when fetched.");
        }
        return drawable;
    }


    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }
    
    public Drawable createDrawable(int id, short width, short height, byte depth, GPUImage gpuImage) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth), gpuImage);
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) {
            Drawable drawable = new Drawable(id, width, height, visual, xServer.getSurfaceFormat());
            if (drawable.getGPUImage().getAHB() == 0) {
                throw new IllegalStateException("Drawable with id 0 has null data at creation.");
            }
            return drawable;
        }
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual, xServer.getSurfaceFormat());
        if (drawable.getGPUImage().getAHB() == 0) {
            throw new IllegalStateException("Drawable with id " + id + " has null data at creation.");
        }
        drawables.put(id, drawable);
        return drawable;
    }
    
    public Drawable createDrawable(int id, short width, short height, Visual visual, GPUImage gpuImage) {
        if (id == 0) {
            Drawable drawable = new Drawable(id, width, height, visual, gpuImage);
            if (drawable.getGPUImage().getAHB() == 0) {
                throw new IllegalStateException("Drawable with id 0 has null data at creation.");
            }
            return drawable;
        }
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual, gpuImage);
        if (drawable.getGPUImage().getAHB() == 0) {
            throw new IllegalStateException("Drawable with id " + id + " has null data at creation.");
        }
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable == null) {
            throw new IllegalStateException("Attempting to remove non-existent Drawable with id " + id);
        }
        if (drawable.getGPUImage().getAHB() == 0) {
            throw new IllegalStateException("Drawable with id " + id + " has null data during removal.");
        }

        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) onDestroyListener.call(drawable);

        drawable.setOnDrawListener(null);
        drawables.remove(id);
    }


    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            Pixmap pixmap = (Pixmap) resource;
            Drawable drawable = pixmap.drawable;
            if (drawable.getGPUImage().getAHB() == 0) {
                throw new IllegalStateException("Drawable for Pixmap with id " + pixmap.drawable.id + " has null data during free.");
            }
            removeDrawable(drawable.id);
        }
    }


    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }
}