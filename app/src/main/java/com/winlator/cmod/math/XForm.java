package com.winlator.cmod.math;

/* loaded from: classes11.dex */
public class XForm {
    private static final float[] tmpXForm = getInstance();

    public static float[] getInstance() {
        return identity(new float[6]);
    }

    public static float[] identity(float[] xform) {
        return set(xform, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f);
    }

    public static float[] set(float[] xform, float n11, float n12, float n21, float n22, float dx, float dy) {
        xform[0] = n11;
        xform[1] = n12;
        xform[2] = n21;
        xform[3] = n22;
        xform[4] = dx;
        xform[5] = dy;
        return xform;
    }

    public static float[] set(float[] xform, float tx, float ty, float sx, float sy) {
        xform[0] = sx;
        xform[1] = 0.0f;
        xform[2] = 0.0f;
        xform[3] = sy;
        xform[4] = tx;
        xform[5] = ty;
        return xform;
    }

    public static float[] makeTransform(float[] xform, float tx, float ty, float sx, float sy, float angle) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        return set(xform, sx * c, sy * s, sx * (-s), sy * c, tx, ty);
    }

    public static float[] makeTranslation(float[] xform, float x, float y) {
        return set(xform, 1.0f, 0.0f, 0.0f, 1.0f, x, y);
    }

    public static float[] makeScale(float[] xform, float x, float y) {
        return set(xform, x, 0.0f, 0.0f, y, 0.0f, 0.0f);
    }

    public static float[] makeRotation(float[] xform, float angle) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        return set(xform, c, s, -s, c, 0.0f, 0.0f);
    }

    public static synchronized float[] translate(float[] xform, float x, float y) {
        float[] multiply;
        synchronized (XForm.class) {
            multiply = multiply(xform, xform, makeTranslation(tmpXForm, x, y));
        }
        return multiply;
    }

    public static synchronized float[] scale(float[] xform, float x, float y) {
        float[] multiply;
        synchronized (XForm.class) {
            multiply = multiply(xform, xform, makeScale(tmpXForm, x, y));
        }
        return multiply;
    }

    public static synchronized float[] rotate(float[] xform, float angle) {
        float[] multiply;
        synchronized (XForm.class) {
            multiply = multiply(xform, xform, makeRotation(tmpXForm, angle));
        }
        return multiply;
    }

    public static float[] multiply(float[] result, float[] ta, float[] tb) {
        float a0 = ta[0];
        float a3 = ta[3];
        float a1 = ta[1];
        float a4 = ta[4];
        float a2 = ta[2];
        float a5 = ta[5];
        float b0 = tb[0];
        float b3 = tb[3];
        float b1 = tb[1];
        float b4 = tb[4];
        float b2 = tb[2];
        float b5 = tb[5];
        result[0] = (a0 * b0) + (a1 * b2);
        result[1] = (a0 * b1) + (a1 * b3);
        result[2] = (a2 * b0) + (a3 * b2);
        result[3] = (a2 * b1) + (a3 * b3);
        result[4] = (a4 * b0) + (a5 * b2) + b4;
        result[5] = (a4 * b1) + (a5 * b3) + b5;
        return result;
    }

    public static float[] transformPoint(float[] xform, float x, float y) {
        return transformPoint(xform, x, y, new float[2]);
    }

    public static float[] transformPoint(float[] xform, float x, float y, float[] result) {
        result[0] = (xform[0] * x) + (xform[2] * y) + xform[4];
        result[1] = (xform[1] * x) + (xform[3] * y) + xform[5];
        return result;
    }
}
