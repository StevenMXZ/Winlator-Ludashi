package com.winlator.cmod.core;

import android.graphics.PointF;
import android.view.animation.Interpolator;

/* loaded from: classes10.dex */
public class CubicBezierInterpolator implements Interpolator {
    private float ax;
    private float bx;
    private float cx;
    public final PointF end;
    public final PointF start;

    public CubicBezierInterpolator() {
        this(new PointF(0.0f, 0.0f), new PointF(0.0f, 0.0f));
    }

    public CubicBezierInterpolator(PointF start, PointF end) {
        this.start = start;
        this.end = end;
    }

    public CubicBezierInterpolator(float x1, float y1, float x2, float y2) {
        this(new PointF(x1, y1), new PointF(x2, y2));
    }

    public void set(float x1, float y1, float x2, float y2) {
        this.start.set(x1, y1);
        this.end.set(x2, y2);
    }

    @Override // android.animation.TimeInterpolator
    public float getInterpolation(float time) {
        return getBezierCoordinateY(getXForTime(time));
    }

    private float getBezierCoordinateY(float time) {
        float cy = this.start.y * 3.0f;
        float by = ((this.end.y - this.start.y) * 3.0f) - cy;
        float ay = (1.0f - cy) - by;
        return ((((time * ay) + by) * time) + cy) * time;
    }

    private float getXForTime(float time) {
        float x = time;
        for (int i = 1; i < 14; i++) {
            float z = getBezierCoordinateX(x) - time;
            if (Math.abs(z) < 0.001d) {
                break;
            }
            x -= z / getXDerivate(x);
        }
        return x;
    }

    private float getXDerivate(float t) {
        return this.cx + (((this.bx * 2.0f) + (this.ax * 3.0f * t)) * t);
    }

    private float getBezierCoordinateX(float time) {
        this.cx = this.start.x * 3.0f;
        this.bx = ((this.end.x - this.start.x) * 3.0f) - this.cx;
        this.ax = (1.0f - this.cx) - this.bx;
        return (this.cx + ((this.bx + (this.ax * time)) * time)) * time;
    }
}
