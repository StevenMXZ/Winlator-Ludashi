package com.winlator.cmod.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.math.Mathf;
import java.text.DecimalFormat;

/* loaded from: classes14.dex */
public class SeekBar extends AppCompatImageView {
    private final float barHeight;
    private final int colorPrimary;
    private final int colorSecondary;
    private DecimalFormat decimalFormat;
    private LinearGradient glossyEffectGradient;
    private float maxValue;
    private float minValue;
    private float normalizedValue;
    private OnValueChangeListener onValueChangeListener;
    private final Paint paint;
    private final RectF rect;
    private float step;
    private String suffix;
    private final int textColor;
    private float textSize;
    private final float thumbRadius;
    private final float thumbSize;

    public interface OnValueChangeListener {
        void onValueChangeListener(SeekBar seekBar, float f);
    }

    public SeekBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SeekBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.paint = new Paint(1);
        this.rect = new RectF();
        this.maxValue = 100.0f;
        this.minValue = 0.0f;
        this.normalizedValue = 0.0f;
        this.step = 1.0f;
        this.barHeight = UnitUtils.dpToPx(6.0f);
        this.thumbSize = UnitUtils.dpToPx(20.0f);
        this.thumbRadius = this.thumbSize / 2.0f;
        this.textSize = UnitUtils.dpToPx(16.0f);
        this.textColor = -9211021;
        this.colorPrimary = -2631721;
        this.colorSecondary = ContextCompat.getColor(context, R.color.colorPrimary);
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs, com.winlator.cmod.R.styleable.SeekBar, 0, 0);
            try {
                this.minValue = ta.getFloat(2, this.minValue);
                this.maxValue = ta.getFloat(1, this.maxValue);
                this.suffix = ta.getString(6);
                this.textSize = ta.getDimension(4, this.textSize);
                setStep(ta.getFloat(3, this.step));
                setValue(ta.getFloat(5, this.minValue));
            } finally {
                ta.recycle();
            }
        }
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public float getMaxValue() {
        return this.maxValue;
    }

    public void setMaxValue(float maxValue) {
        synchronized (this) {
            this.maxValue = maxValue;
        }
    }

    public float getMinValue() {
        return this.minValue;
    }

    public void setMinValue(float minValue) {
        synchronized (this) {
            this.minValue = minValue;
        }
    }

    public float getStep() {
        return this.step;
    }

    public void setStep(float step) {
        synchronized (this) {
            this.step = step;
            this.decimalFormat = new DecimalFormat(step == 1.0f ? "0" : "0.##");
        }
    }

    public String getSuffix() {
        return this.suffix;
    }

    public void setSuffix(String suffix) {
        synchronized (this) {
            this.suffix = suffix;
        }
    }

    public float getValue() {
        return this.minValue + (this.normalizedValue * (this.maxValue - this.minValue));
    }

    public void setValue(float value) {
        synchronized (this) {
            float normalized = Mathf.roundTo(value, this.step);
            this.normalizedValue = Mathf.clamp((normalized - this.minValue) / (this.maxValue - this.minValue), 0.0f, 1.0f);
            postInvalidate();
        }
    }

    public OnValueChangeListener getOnValueChangeListener() {
        return this.onValueChangeListener;
    }

    public void setOnValueChangeListener(OnValueChangeListener onValueChangeListener) {
        this.onValueChangeListener = onValueChangeListener;
    }

    @Override // android.widget.ImageView, android.view.View
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Log.d("SeekBar", "onDraw called with value: " + getValue());
        float centerY = getHeight() / 2.0f;
        this.paint.setColor(this.colorPrimary);
        this.paint.setStyle(Paint.Style.FILL);
        float left = this.thumbRadius;
        float right = getWidth() - this.thumbRadius;
        this.rect.set(left, centerY - (this.barHeight / 2.0f), right, (this.barHeight / 2.0f) + centerY);
        canvas.drawRoundRect(this.rect, this.barHeight / 2.0f, this.barHeight / 2.0f, this.paint);
        float progressWidth = (this.normalizedValue * (right - left)) + left;
        this.paint.setColor(this.colorSecondary);
        this.rect.set(left, centerY - (this.barHeight / 2.0f), progressWidth, (this.barHeight / 2.0f) + centerY);
        canvas.drawRoundRect(this.rect, this.barHeight / 2.0f, this.barHeight / 2.0f, this.paint);
        this.paint.setColor(-1);
        canvas.drawCircle(progressWidth, centerY, this.thumbRadius, this.paint);
        this.paint.setColor(getThumbHoleColor());
        canvas.drawCircle(progressWidth, centerY, this.thumbRadius * 0.5f, this.paint);
        String valueText = this.decimalFormat.format(getValue()) + (this.suffix != null ? this.suffix : "");
        this.paint.setColor(this.textColor);
        this.paint.setTextSize(this.textSize);
        this.paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(valueText, progressWidth, (centerY - this.thumbRadius) - (this.textSize / 2.0f), this.paint);
        if (this.glossyEffectGradient == null) {
            this.glossyEffectGradient = new LinearGradient(0.0f, 0.0f, 0.0f, getHeight(), new int[]{872415231, ViewCompat.MEASURED_SIZE_MASK}, new float[]{0.5f, 1.0f}, Shader.TileMode.CLAMP);
        }
        this.paint.setShader(this.glossyEffectGradient);
        canvas.drawRoundRect(this.rect, this.barHeight / 2.0f, this.barHeight / 2.0f, this.paint);
        this.paint.setShader(null);
    }

    @Override // android.view.View
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getAction()) {
            case 0:
                setPressed(true);
                setNormalizedValue(event.getX());
                break;
            case 1:
                setPressed(false);
                if (this.onValueChangeListener != null) {
                    this.onValueChangeListener.onValueChangeListener(this, getValue());
                    break;
                }
                break;
            case 2:
                setNormalizedValue(event.getX());
                break;
            case 3:
                setPressed(false);
                break;
        }
        invalidate();
        return true;
    }

    private void setNormalizedValue(float x) {
        int width = getWidth();
        float newValue = (x - this.thumbRadius) / (width - (this.thumbRadius * 2.0f));
        this.normalizedValue = Mathf.roundTo(Mathf.clamp(newValue, 0.0f, 1.0f), this.step / (this.maxValue - this.minValue));
    }

    public int getThumbHoleColor() {
        int r = Mathf.clamp(Color.red(this.colorSecondary) - 30, 0, 255);
        int g = Mathf.clamp(Color.green(this.colorSecondary) - 30, 0, 255);
        int b = Mathf.clamp(Color.blue(this.colorSecondary) - 30, 0, 255);
        return Color.rgb(r, g, b);
    }
}
