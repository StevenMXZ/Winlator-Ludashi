package com.winlator.cmod.renderer;

/* loaded from: classes12.dex */
public class ViewTransformation {
    public float aspect;
    public float sceneOffsetX;
    public float sceneOffsetY;
    public float sceneScaleX;
    public float sceneScaleY;
    public int viewHeight;
    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        this.aspect = Math.min(outerWidth / innerWidth, outerHeight / innerHeight);
        this.viewWidth = (int) Math.ceil(innerWidth * this.aspect);
        this.viewHeight = (int) Math.ceil(innerHeight * this.aspect);
        this.viewOffsetX = (int) ((outerWidth - (innerWidth * this.aspect)) * 0.5f);
        this.viewOffsetY = (int) ((outerHeight - (innerHeight * this.aspect)) * 0.5f);
        this.sceneScaleX = (innerWidth * this.aspect) / outerWidth;
        this.sceneScaleY = (innerHeight * this.aspect) / outerHeight;
        this.sceneOffsetX = (innerWidth - (innerWidth * this.sceneScaleX)) * 0.5f;
        this.sceneOffsetY = (innerHeight - (innerHeight * this.sceneScaleY)) * 0.5f;
    }
}
