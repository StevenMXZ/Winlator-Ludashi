package com.winlator.cmod.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import com.ludashi.benchmark.R;
import com.winlator.cmod.math.Mathf;

/* loaded from: classes14.dex */
public class NumberPicker extends FrameLayout implements View.OnTouchListener {
    private final EditText editText;
    private int maxValue;
    private int minValue;
    private OnValueChangeListener onValueChangeListener;
    private int step;
    private int value;

    public interface OnValueChangeListener {
        void onValueChange(NumberPicker numberPicker, int i);
    }

    public NumberPicker(Context context) {
        this(context, null);
    }

    public NumberPicker(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NumberPicker(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.value = 0;
        this.minValue = 0;
        this.maxValue = 100;
        this.step = 1;
        LayoutInflater.from(context).inflate(R.layout.number_picker, (ViewGroup) this, true);
        this.editText = (EditText) findViewById(R.id.EditText);
        findViewById(R.id.BTDecrement).setOnTouchListener(this);
        findViewById(R.id.BTIncrement).setOnTouchListener(this);
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs, com.winlator.cmod.R.styleable.NumberPicker);
            this.minValue = ta.getInt(1, this.minValue);
            this.maxValue = ta.getInt(0, this.maxValue);
            setStep(ta.getInt(2, this.step));
            int value = ta.getInt(4, 0);
            ta.recycle();
            setValue(value);
            return;
        }
        setStep(this.step);
    }

    public void setValue(int value) {
        this.value = Mathf.clamp(value, this.minValue, this.maxValue);
        this.editText.setText(String.valueOf(this.value));
    }

    public int getValue() {
        return this.value;
    }

    public int getMinValue() {
        return this.minValue;
    }

    public void setMinValue(int minValue) {
        this.minValue = minValue;
    }

    public int getMaxValue() {
        return this.maxValue;
    }

    public void setMaxValue(int maxValue) {
        this.maxValue = maxValue;
    }

    public void increment() {
        setValue(this.value + this.step);
    }

    public void decrement() {
        setValue(this.value - this.step);
    }

    public int getStep() {
        return this.step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public OnValueChangeListener getOnValueChangeListener() {
        return this.onValueChangeListener;
    }

    public void setOnValueChangeListener(OnValueChangeListener onValueChangeListener) {
        this.onValueChangeListener = onValueChangeListener;
    }

    private void onButtonClick(View v) {
        if (isEnabled()) {
            int id = v.getId();
            if (id == R.id.BTIncrement) {
                increment();
            } else if (id == R.id.BTDecrement) {
                decrement();
            }
            if (this.onValueChangeListener != null) {
                this.onValueChangeListener.onValueChange(this, this.value);
            }
        }
    }

    @Override // android.view.View.OnTouchListener
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getAction();
        if (action == 1 || action == 3) {
            onButtonClick(v);
        }
        return true;
    }
}
