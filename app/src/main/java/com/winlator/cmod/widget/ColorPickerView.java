package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import androidx.core.view.ViewCompat;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.UnitUtils;
import java.util.Locale;

/* loaded from: classes14.dex */
public class ColorPickerView extends View implements View.OnClickListener {
    private static final int[] colors = {16748288, 13840175, 9795021, 3046706, 33679, 161725, 6323595, 0};
    private final Bitmap colorFrame;
    private int currentColor;

    public ColorPickerView(Context context) {
        this(context, null);
    }

    public ColorPickerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ColorPickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.currentColor = ViewCompat.MEASURED_SIZE_MASK;
        this.colorFrame = BitmapFactory.decodeResource(context.getResources(), R.drawable.color_frame);
        setBackgroundResource(R.drawable.combo_box);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(this);
    }

    public int getColor() {
        return toARGB(this.currentColor);
    }

    public void setColor(int color) {
        this.currentColor = toRGB(color);
        invalidate();
    }

    public String getColorAsString() {
        return String.format(Locale.ENGLISH, "#%06X", Integer.valueOf(16777215 & this.currentColor));
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        float rectSize = height - UnitUtils.dpToPx(12.0f);
        float startX = ((width - rectSize) * 0.5f) - UnitUtils.dpToPx(16.0f);
        float startY = (height - rectSize) * 0.5f;
        Paint paint = new Paint();
        paint.setColor(toARGB(this.currentColor));
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(false);
        paint.setFilterBitmap(false);
        canvas.drawRect(startX, startY, startX + rectSize, startY + rectSize, paint);
        Rect srcRect = new Rect(0, 0, this.colorFrame.getWidth(), this.colorFrame.getHeight());
        RectF dstRect = new RectF(startX, startY, startX + rectSize, startY + rectSize);
        canvas.drawBitmap(this.colorFrame, srcRect, dstRect, paint);
    }

    public static int toARGB(int rgb) {
        return Color.argb(255, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    public static int toRGB(int argb) {
        return Color.argb(0, Color.red(argb), Color.green(argb), Color.blue(argb));
    }

    @Override // android.view.View.OnClickListener
    public void onClick(View anchor) {
        Context context = getContext();
        LinearLayout container = new LinearLayout(context);
        container.setLayoutParams(new LinearLayout.LayoutParams(-2, (int) UnitUtils.dpToPx(60.0f)));
        container.setOrientation(0);
        container.setGravity(16);
        container.setPadding(0, 0, (int) UnitUtils.dpToPx(4.0f), 0);
        Bitmap colorFrameSelected = BitmapFactory.decodeResource(context.getResources(), R.drawable.color_frame_selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams((int) UnitUtils.dpToPx(32.0f), (int) UnitUtils.dpToPx(32.0f));
        params.setMargins((int) UnitUtils.dpToPx(4.0f), 0, 0, 0);
        final PopupWindow[] popupWindow = {null};
        int[] iArr = colors;
        int length = iArr.length;
        for (int i = 0; i < length; i++) {
            final int color = iArr[i];
            ImageView imageView = new ImageView(context);
            imageView.setLayoutParams(params);
            imageView.setImageBitmap(color == this.currentColor ? colorFrameSelected : this.colorFrame);
            imageView.setBackgroundColor(toARGB(color));
            imageView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.widget.ColorPickerView$$ExternalSyntheticLambda0
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ColorPickerView.this.lambda$onClick$0(color, popupWindow, view);
                }
            });
            container.addView(imageView);
        }
        popupWindow[0] = AppUtils.showPopupWindow(anchor, container, 0, 60);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onClick$0(int color, PopupWindow[] popupWindow, View v) {
        this.currentColor = color;
        invalidate();
        if (popupWindow[0] != null) {
            popupWindow[0].dismiss();
        }
    }
}
