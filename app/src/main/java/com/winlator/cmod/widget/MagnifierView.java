package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.math.Mathf;

/* loaded from: classes14.dex */
public class MagnifierView extends FrameLayout {
    private Runnable hideButtonCallback;
    private short lastX;
    private short lastY;
    private final SharedPreferences preferences;
    private boolean restoreSavedPosition;
    private TextView textView;
    private Callback<Float> zoomButtonCallback;

    public MagnifierView(Context context) {
        this(context, null);
    }

    public MagnifierView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MagnifierView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MagnifierView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.restoreSavedPosition = true;
        this.lastX = (short) 0;
        this.lastY = (short) 0;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        setLayoutParams(new FrameLayout.LayoutParams(-2, -2));
        View contentView = LayoutInflater.from(context).inflate(R.layout.magnifier_view, (ViewGroup) this, false);
        final PointF startPoint = new PointF();
        final boolean[] isActionDown = {false};
        contentView.findViewById(R.id.BTMove).setOnTouchListener(new View.OnTouchListener() { // from class: com.winlator.cmod.widget.MagnifierView$$ExternalSyntheticLambda0
            @Override // android.view.View.OnTouchListener
            public final boolean onTouch(View view, MotionEvent motionEvent) {
                boolean lambda$new$0;
                lambda$new$0 = MagnifierView.this.lambda$new$0(startPoint, isActionDown, view, motionEvent);
                return lambda$new$0;
            }
        });
        contentView.findViewById(R.id.BTZoomPlus).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.widget.MagnifierView$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MagnifierView.this.lambda$new$1(view);
            }
        });
        contentView.findViewById(R.id.BTZoomMinus).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.widget.MagnifierView$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MagnifierView.this.lambda$new$2(view);
            }
        });
        contentView.findViewById(R.id.BTHide).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.widget.MagnifierView$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MagnifierView.this.lambda$new$3(view);
            }
        });
        this.textView = (TextView) contentView.findViewById(R.id.TextView);
        addView(contentView);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:15:0x0076, code lost:
    
        return true;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public /* synthetic */ boolean lambda$new$0(android.graphics.PointF r6, boolean[] r7, android.view.View r8, android.view.MotionEvent r9) {
        /*
            r5 = this;
            int r0 = r9.getAction()
            r1 = 1
            r2 = 0
            switch(r0) {
                case 0: goto L67;
                case 1: goto L2a;
                case 2: goto La;
                default: goto L9;
            }
        L9:
            goto L76
        La:
            boolean r0 = r7[r2]
            if (r0 == 0) goto L76
            float r0 = r5.getX()
            float r2 = r9.getX()
            float r3 = r6.x
            float r2 = r2 - r3
            float r0 = r0 + r2
            float r2 = r5.getY()
            float r3 = r9.getY()
            float r4 = r6.y
            float r3 = r3 - r4
            float r2 = r2 + r3
            r5.movePanel(r0, r2)
            goto L76
        L2a:
            boolean r0 = r7[r2]
            if (r0 == 0) goto L60
            short r0 = r5.lastX
            if (r0 <= 0) goto L60
            short r0 = r5.lastY
            if (r0 <= 0) goto L60
            android.content.SharedPreferences r0 = r5.preferences
            android.content.SharedPreferences$Editor r0 = r0.edit()
            java.lang.StringBuilder r3 = new java.lang.StringBuilder
            r3.<init>()
            short r4 = r5.lastX
            java.lang.StringBuilder r3 = r3.append(r4)
            java.lang.String r4 = "|"
            java.lang.StringBuilder r3 = r3.append(r4)
            short r4 = r5.lastY
            java.lang.StringBuilder r3 = r3.append(r4)
            java.lang.String r3 = r3.toString()
            java.lang.String r4 = "magnifier_view"
            android.content.SharedPreferences$Editor r0 = r0.putString(r4, r3)
            r0.apply()
        L60:
            r5.lastX = r2
            r5.lastY = r2
            r7[r2] = r2
            goto L76
        L67:
            float r0 = r9.getX()
            r6.x = r0
            float r0 = r9.getY()
            r6.y = r0
            r7[r2] = r1
        L76:
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.widget.MagnifierView.lambda$new$0(android.graphics.PointF, boolean[], android.view.View, android.view.MotionEvent):boolean");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$1(View v) {
        if (this.zoomButtonCallback != null) {
            this.zoomButtonCallback.call(Float.valueOf(0.05f));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$2(View v) {
        if (this.zoomButtonCallback != null) {
            this.zoomButtonCallback.call(Float.valueOf(-0.05f));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$3(View v) {
        if (this.hideButtonCallback != null) {
            this.hideButtonCallback.run();
        }
    }

    public void setZoomValue(float value) {
        this.textView.setText(((int) (100.0f * value)) + "%");
    }

    @Override // android.widget.FrameLayout, android.view.ViewGroup, android.view.View
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (this.restoreSavedPosition) {
            float x = 1000000.0f;
            float y = 1000000.0f;
            String config = this.preferences.getString("magnifier_view", null);
            if (config != null) {
                try {
                    String[] parts = config.split("\\|");
                    x = Short.parseShort(parts[0]);
                    y = Short.parseShort(parts[1]);
                } catch (NumberFormatException e) {
                }
            }
            movePanel(x, y);
            this.restoreSavedPosition = false;
        }
    }

    private void movePanel(float x, float y) {
        int padding = (int) UnitUtils.dpToPx(8.0f);
        ViewGroup parent = (ViewGroup) getParent();
        int width = getWidth();
        int height = getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        float x2 = Mathf.clamp(x, padding, (parentWidth - padding) - width);
        float y2 = Mathf.clamp(y, padding, (parentHeight - padding) - height);
        setX(x2);
        setY(y2);
        this.lastX = (short) x2;
        this.lastY = (short) y2;
    }

    public Callback<Float> getZoomButtonCallback() {
        return this.zoomButtonCallback;
    }

    public void setZoomButtonCallback(Callback<Float> zoomButtonCallback) {
        this.zoomButtonCallback = zoomButtonCallback;
    }

    public Runnable getHideButtonCallback() {
        return this.hideButtonCallback;
    }

    public void setHideButtonCallback(Runnable hideButtonCallback) {
        this.hideButtonCallback = hideButtonCallback;
    }
}
