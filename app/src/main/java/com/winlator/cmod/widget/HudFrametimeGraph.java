package com.winlator.cmod.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/** Cached interval-average frametimes; no synthetic samples or GPU polling. */
final class HudFrametimeGraph {
    private final float[] samples = new float[48];
    private final Path path = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int count, next;
    private String label = "-- ms";

    void record(float fps) {
        if (!(fps > 0) || !Float.isFinite(fps)) return;
        float ms = 1000f / fps;
        if (ms > 500f) return;
        samples[next] = ms;
        next = (next + 1) % samples.length;
        count = Math.min(count + 1, samples.length);
        int tenths = Math.round(ms * 10);
        label = (tenths / 10) + "." + (tenths % 10) + " ms";
    }

    void clear() { count = next = 0; label = "-- ms"; }

    void draw(Canvas canvas, float x, float y, float width, float height, float textSize) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(textSize * .85f);
        paint.setColor(Color.LTGRAY);
        float labelWidth = paint.measureText("999.9 ms") + textSize;
        canvas.drawText(label, x + Math.max(0, width - labelWidth), y + height * .7f, paint);
        width -= labelWidth + textSize;
        if (count < 2 || width <= 0) return;
        float low = Float.MAX_VALUE, high = 0;
        for (int i = 0; i < count; i++) {
            float value = samples[(next - count + i + samples.length) % samples.length];
            low = Math.min(low, value); high = Math.max(high, value);
        }
        float center = (low + high) * .5f, span = Math.max(.16f, (high - low) * 1.25f);
        path.rewind();
        for (int i = 0; i < count; i++) {
            float value = samples[(next - count + i + samples.length) % samples.length];
            float px = x + width * i / (count - 1f);
            float py = y + height * (.5f - (value - center) / span * .8f);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, textSize / 10f));
        paint.setColor(Color.rgb(176, 255, 176));
        canvas.drawPath(path, paint);
    }
}
