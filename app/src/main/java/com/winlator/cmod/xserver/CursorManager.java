package com.winlator.cmod.xserver;

import android.util.SparseArray;
import java.nio.IntBuffer;

/* loaded from: classes11.dex */
public class CursorManager extends XResourceManager {
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final DrawableManager drawableManager;

    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public Cursor getCursor(int id) {
        return this.cursors.get(id);
    }

    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (this.cursors.indexOfKey(id) >= 0) {
            return null;
        }
        Drawable drawable = this.drawableManager.createDrawable(0, sourcePixmap.drawable.width, sourcePixmap.drawable.height, sourcePixmap.drawable.visual);
        Cursor cursor = new Cursor(id, x, y, drawable, sourcePixmap.drawable, maskPixmap != null ? maskPixmap.drawable : null);
        this.cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    public void freeCursor(int id) {
        triggerOnFreeResourceListener(this.cursors.get(id));
        this.cursors.remove(id);
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.getData().asIntBuffer();
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0) {
                return false;
            }
        }
        return true;
    }

    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue) {
        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) {
                cursor.cursorImage.drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, cursor.sourceImage, cursor.maskImage);
            }
        }
    }
}
