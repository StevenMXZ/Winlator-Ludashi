package com.winlator.cmod.renderer;

/**
 * Cursor-following presentation-only zoom. The virtual X11 resolution is never
 * changed. A 60% safe zone avoids moving the screen on every cursor event.
 * Pan behavior adapted from the Ludashi renderer.
 */
public final class MagnifierPan {
    private static final float SAFE_FRACTION = 0.6f;
    private float panX, panY;
    private boolean initialized;
    private float lastZoom = 1f;

    public void reset() {
        panX = panY = 0f;
        initialized = false;
        lastZoom = 1f;
    }

    public void update(float cursorX, float cursorY, int width, int height, float zoom) {
        if (zoom <= 1f || width <= 0 || height <= 0) {
            reset();
            return;
        }
        if (zoom != lastZoom) initialized = false;
        lastZoom = zoom;
        cursorX = clamp(cursorX, 0f, width);
        cursorY = clamp(cursorY, 0f, height);

        final float maxX = width * (zoom - 1f);
        final float maxY = height * (zoom - 1f);
        if (!initialized) {
            panX = clamp(cursorX * zoom - width * .5f, 0f, maxX);
            panY = clamp(cursorY * zoom - height * .5f, 0f, maxY);
            initialized = true;
        }

        final float visibleW = width / zoom, visibleH = height / zoom;
        final float marginX = visibleW * (1f - SAFE_FRACTION) * .5f;
        final float marginY = visibleH * (1f - SAFE_FRACTION) * .5f;
        final float left = panX / zoom, top = panY / zoom;
        if (cursorX < left + marginX) {
            panX = (cursorX - marginX) * zoom;
        } else if (cursorX > left + visibleW - marginX) {
            panX = (cursorX - visibleW + marginX) * zoom;
        }
        if (cursorY < top + marginY) {
            panY = (cursorY - marginY) * zoom;
        } else if (cursorY > top + visibleH - marginY) {
            panY = (cursorY - visibleH + marginY) * zoom;
        }
        panX = clamp(panX, 0f, maxX);
        panY = clamp(panY, 0f, maxY);
    }

    public float panX() { return panX; }
    public float panY() { return panY; }
    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
