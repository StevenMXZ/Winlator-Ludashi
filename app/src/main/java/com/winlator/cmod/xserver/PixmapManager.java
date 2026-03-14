package com.winlator.cmod.xserver;

import android.graphics.Bitmap;
import android.util.SparseArray;
import androidx.core.view.MotionEventCompat;
import com.winlator.cmod.xserver.Window;

/* loaded from: classes11.dex */
public class PixmapManager extends XResourceManager {
    private final SparseArray<Pixmap> pixmaps = new SparseArray<>();
    public final Visual visual = new Visual(IDGenerator.generate(), true, 32, 24, 16711680, MotionEventCompat.ACTION_POINTER_INDEX_MASK, 255);
    public final Visual[] supportedVisuals = {this.visual, new Visual(IDGenerator.generate(), false, 1, 1, 0, 0, 0)};
    public final PixmapFormat[] supportedPixmapFormats = {new PixmapFormat(1, 1, 32), new PixmapFormat(24, 32, 32), new PixmapFormat(32, 32, 32)};

    public Pixmap getPixmap(int id) {
        return this.pixmaps.get(id);
    }

    public Pixmap createPixmap(Drawable drawable) {
        if (this.pixmaps.indexOfKey(drawable.id) >= 0) {
            return null;
        }
        Pixmap pixmap = new Pixmap(drawable);
        this.pixmaps.put(drawable.id, pixmap);
        triggerOnCreateResourceListener(pixmap);
        return pixmap;
    }

    public void freePixmap(int id) {
        triggerOnFreeResourceListener(this.pixmaps.get(id));
        this.pixmaps.remove(id);
    }

    public Visual getVisualForDepth(byte depth) {
        if (depth == this.visual.depth) {
            return this.visual;
        }
        for (Visual visual : this.supportedVisuals) {
            if (depth == visual.depth) {
                return visual;
            }
        }
        return null;
    }

    public Visual getVisual(int id) {
        if (id == this.visual.id) {
            return this.visual;
        }
        for (Visual visual : this.supportedVisuals) {
            if (id == visual.id && visual.displayable) {
                return visual;
            }
        }
        return null;
    }

    public Bitmap getWindowIcon(Window window) {
        int colorPixmapId = window.getWMHintsValue(Window.WMHints.ICON_PIXMAP);
        int maskPixmapId = window.getWMHintsValue(Window.WMHints.ICON_MASK);
        Pixmap colorPixmap = colorPixmapId != 0 ? getPixmap(colorPixmapId) : null;
        Pixmap maskPixmap = maskPixmapId != 0 ? getPixmap(maskPixmapId) : null;
        if (colorPixmap != null) {
            return colorPixmap.toBitmap(maskPixmap);
        }
        return null;
    }
}
