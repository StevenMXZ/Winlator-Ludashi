package com.winlator.cmod.xserver;

/* loaded from: classes11.dex */
public class Cursor extends XResource {
    public final Drawable cursorImage;
    public final int hotSpotX;
    public final int hotSpotY;
    public final Drawable maskImage;
    public final Drawable sourceImage;
    private boolean visible;

    public Cursor(int id, int hotSpotX, int hotSpotY, Drawable cursorImage, Drawable sourceImage, Drawable maskImage) {
        super(id);
        this.visible = true;
        this.hotSpotX = hotSpotX;
        this.hotSpotY = hotSpotY;
        this.cursorImage = cursorImage;
        this.sourceImage = sourceImage;
        this.maskImage = maskImage;
    }

    public boolean isVisible() {
        return this.visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
