package com.winlator.cmod.renderer;

public class ViewTransformation {
    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        update(outerWidth, outerHeight, innerWidth, innerHeight, false);
    }

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight, boolean integerScaling) {
        if (outerWidth <= 0 || outerHeight <= 0 || innerWidth <= 0 || innerHeight <= 0) return;
        aspect = Math.min((float)outerWidth / innerWidth, (float)outerHeight / innerHeight);
        if (integerScaling && aspect >= 1f) {
            int scale = Math.min(outerWidth / innerWidth, outerHeight / innerHeight);
            aspect = scale;
            viewWidth = innerWidth * scale;
            viewHeight = innerHeight * scale;
            viewOffsetX = (outerWidth - viewWidth) / 2;
            viewOffsetY = (outerHeight - viewHeight) / 2;
            sceneScaleX = (float)viewWidth / outerWidth;
            sceneScaleY = (float)viewHeight / outerHeight;
            sceneOffsetX = (float)viewOffsetX * innerWidth / outerWidth;
            sceneOffsetY = (float)viewOffsetY * innerHeight / outerHeight;
            return;
        }
        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
        viewOffsetY = (int)((outerHeight - innerHeight * aspect) * 0.5f);

        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
    }
}
