package com.winlator.cmod.inputcontrols;

import android.graphics.Rect;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.RangeScroller;
import com.winlator.cmod.widget.InputControlsView;
import java.util.Timer;
import java.util.TimerTask;

/* loaded from: classes14.dex */
public class RangeScroller {
    private float currentOffset;
    private final ControlElement element;
    private final InputControlsView inputControlsView;
    private float lastPosition;
    private float scrollOffset;
    private Timer timer;
    private long touchTime;
    private Binding binding = Binding.NONE;
    private boolean isActionDown = false;
    private boolean scrolling = false;

    public RangeScroller(InputControlsView inputControlsView, ControlElement element) {
        this.inputControlsView = inputControlsView;
        this.element = element;
    }

    public float getElementSize() {
        Rect boundingBox = this.element.getBoundingBox();
        return Math.max(boundingBox.width(), boundingBox.height()) / this.element.getBindingCount();
    }

    public float getScrollSize() {
        return getElementSize() * this.element.getRange().max;
    }

    public float getScrollOffset() {
        return this.scrollOffset;
    }

    public byte[] getRangeIndex() {
        ControlElement.Range range = this.element.getRange();
        byte from = (byte) Math.floor((this.scrollOffset / getElementSize()) % range.max);
        if (from < 0) {
            from = (byte) (range.max + from);
        }
        byte to = (byte) (this.element.getBindingCount() + from + 1);
        return new byte[]{from, to};
    }

    private Binding getBindingByPosition(float x, float y) {
        Rect boundingBox = this.element.getBoundingBox();
        ControlElement.Range range = this.element.getRange();
        float offset = (this.element.getOrientation() == 0 ? x - boundingBox.left : y - boundingBox.top) - this.currentOffset;
        int index = (int) Math.floor((offset / getElementSize()) % range.max);
        if (index < 0) {
            index += range.max;
        }
        switch (range) {
            case FROM_A_TO_Z:
                return Binding.valueOf("KEY_" + ((char) (index + 65)));
            case FROM_0_TO_9:
                return Binding.valueOf("KEY_" + ((index + 1) % 10));
            case FROM_F1_TO_F12:
                return Binding.valueOf("KEY_F" + (index + 1));
            case FROM_NP0_TO_NP9:
                return Binding.valueOf("KEY_KP_" + ((index + 1) % 10));
            default:
                return Binding.NONE;
        }
    }

    private boolean isTap() {
        return System.currentTimeMillis() - this.touchTime < 200;
    }

    private void destroyTimer() {
        if (this.timer != null) {
            this.timer.cancel();
            this.timer = null;
        }
    }

    public void handleTouchDown(float x, float y) {
        destroyTimer();
        this.scrolling = false;
        this.isActionDown = true;
        this.binding = getBindingByPosition(x, y);
        this.touchTime = System.currentTimeMillis();
        this.lastPosition = this.element.getOrientation() == 0 ? x : y;
        this.element.setBinding(Binding.NONE);
        this.timer = new Timer(true);
        this.timer.schedule(new AnonymousClass1(), 200L);
    }

    /* renamed from: com.winlator.cmod.inputcontrols.RangeScroller$1, reason: invalid class name */
    class AnonymousClass1 extends TimerTask {
        AnonymousClass1() {
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$run$0() {
            RangeScroller.this.inputControlsView.handleInputEvent(RangeScroller.this.binding, true);
        }

        @Override // java.util.TimerTask, java.lang.Runnable
        public void run() {
            if (!RangeScroller.this.scrolling) {
                RangeScroller.this.inputControlsView.post(new Runnable() { // from class: com.winlator.cmod.inputcontrols.RangeScroller$1$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        RangeScroller.AnonymousClass1.this.lambda$run$0();
                    }
                });
            }
        }
    }

    public void handleTouchMove(float x, float y) {
        if (this.isActionDown) {
            float position = this.element.getOrientation() == 0 ? x : y;
            float deltaPosition = position - this.lastPosition;
            if (Math.abs(deltaPosition) >= 10.0f) {
                this.scrolling = true;
                destroyTimer();
            }
            if (this.scrolling) {
                this.currentOffset += deltaPosition;
                float scrollSize = getScrollSize();
                this.scrollOffset = (-this.currentOffset) % scrollSize;
                if (this.scrollOffset < 0.0f) {
                    this.scrollOffset += scrollSize;
                }
                this.lastPosition = position;
                this.inputControlsView.invalidate();
            }
        }
    }

    public void handleTouchUp() {
        if (this.isActionDown) {
            destroyTimer();
            if (isTap() && !this.scrolling) {
                this.inputControlsView.handleInputEvent(this.binding, true);
                final Binding finalBinding = this.binding;
                this.inputControlsView.postDelayed(new Runnable() { // from class: com.winlator.cmod.inputcontrols.RangeScroller$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        RangeScroller.this.lambda$handleTouchUp$0(finalBinding);
                    }
                }, 30L);
            } else {
                this.inputControlsView.handleInputEvent(this.binding, false);
            }
        }
        this.isActionDown = false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$handleTouchUp$0(Binding finalBinding) {
        this.inputControlsView.handleInputEvent(finalBinding, false);
    }
}
