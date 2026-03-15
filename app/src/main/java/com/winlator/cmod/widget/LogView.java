package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.net.Uri;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.UnitUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;

/* loaded from: classes14.dex */
public class LogView extends View {
    private static String fileName;
    private final float defaultTextSize;
    private boolean isActionDown;
    private final PointF lastPoint;
    private final ArrayList<String> lines;
    private final Object lock;
    private final float minScrollThumbSize;
    private final Paint paint;
    private final float rowHeight;
    private final PointF scrollPosition;
    private final PointF scrollSize;
    private boolean scrollingHorizontally;
    private boolean scrollingVertically;

    public LogView(Context context) {
        this(context, null);
    }

    public LogView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LogView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public LogView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.paint = new Paint(1);
        this.lines = new ArrayList<>();
        this.rowHeight = UnitUtils.dpToPx(30.0f);
        this.defaultTextSize = UnitUtils.dpToPx(16.0f);
        this.minScrollThumbSize = UnitUtils.dpToPx(6.0f);
        this.lastPoint = new PointF();
        this.scrollPosition = new PointF();
        this.scrollSize = new PointF();
        this.isActionDown = false;
        this.scrollingHorizontally = false;
        this.scrollingVertically = false;
        this.lock = new Object();
    }

    @Override // android.view.View
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeScrollSize();
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        synchronized (this.lock) {
            this.paint.setStyle(Paint.Style.FILL);
            if (this.lines.isEmpty()) {
                this.paint.setTextSize(UnitUtils.dpToPx(20.0f));
                this.paint.setColor(-4342339);
                String text = getContext().getString(R.string.no_items_to_display);
                float centerX = (width - this.paint.measureText(text)) * 0.5f;
                float centerY = ((height - this.paint.getFontSpacing()) * 0.5f) - this.paint.ascent();
                canvas.drawText(text, centerX, centerY, this.paint);
                return;
            }
            this.paint.setTextSize(this.defaultTextSize);
            float textHeight = this.paint.getFontSpacing();
            float rowY = -this.scrollPosition.y;
            int count = this.lines.size();
            for (int i = 0; i < count; i++) {
                if (this.rowHeight + rowY >= 0.0f && rowY < height) {
                    this.paint.setColor(i % 2 != 0 ? -1968642 : -1);
                    canvas.drawRect(-this.scrollPosition.x, rowY, width, rowY + this.rowHeight, this.paint);
                    this.paint.setColor(-14606047);
                    float centerY2 = (rowY - this.paint.ascent()) + ((this.rowHeight - textHeight) * 0.5f);
                    canvas.drawText(this.lines.get(i), -this.scrollPosition.x, centerY2, this.paint);
                    rowY += this.rowHeight;
                }
                float centerY3 = this.rowHeight;
                rowY += centerY3;
            }
            drawScrollThumbs(canvas);
        }
    }

    private void drawScrollThumbs(Canvas canvas) {
        float scrollThumbX = getScrollThumbX();
        float scrollThumbY = getScrollThumbY();
        float scrollThumbWidth = getScrollThumbWidth();
        float scrollThumbHeight = getScrollThumbHeight();
        this.paint.setColor(855638016);
        float radius = this.minScrollThumbSize * 0.5f;
        canvas.drawRoundRect(scrollThumbX, getHeight() - this.minScrollThumbSize, scrollThumbX + scrollThumbWidth, getHeight(), radius, radius, this.paint);
        canvas.drawRoundRect(getWidth() - this.minScrollThumbSize, scrollThumbY, getWidth(), scrollThumbY + scrollThumbHeight, radius, radius, this.paint);
    }

    public float getScrollMaxLeft() {
        return Math.max(0.0f, this.scrollSize.x - getWidth());
    }

    public float getScrollMaxTop() {
        return Math.max(0.0f, this.scrollSize.y - getHeight());
    }

    public float getScrollThumbX() {
        float width = getWidth();
        if (this.scrollSize.x <= 0.0f || this.scrollSize.x <= width) {
            return -3.4028235E38f;
        }
        return this.scrollPosition.x * (width / this.scrollSize.x);
    }

    public float getScrollThumbY() {
        float height = getHeight();
        if (this.scrollSize.y <= 0.0f || this.scrollSize.y <= height) {
            return -3.4028235E38f;
        }
        return this.scrollPosition.y * (height / this.scrollSize.y);
    }

    public float getScrollThumbWidth() {
        float width = getWidth();
        if (this.scrollSize.x <= 0.0f || this.scrollSize.x <= width) {
            return 0.0f;
        }
        return Math.max(width - ((getScrollMaxLeft() / this.scrollSize.x) * width), this.minScrollThumbSize);
    }

    public float getScrollThumbHeight() {
        float height = getHeight();
        if (this.scrollSize.y <= 0.0f || this.scrollSize.y <= height) {
            return 0.0f;
        }
        return Math.max(height - ((getScrollMaxTop() / this.scrollSize.y) * height), this.minScrollThumbSize);
    }

    private void computeScrollSize() {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        float maxWidth = 0.0f;
        this.paint.setTextSize(this.defaultTextSize);
        int count = this.lines.size();
        for (int i = 0; i < count; i++) {
            maxWidth = Math.max(this.paint.measureText(this.lines.get(i)), maxWidth);
        }
        this.scrollSize.x = Math.max(maxWidth, width);
        this.scrollSize.y = Math.max(this.rowHeight * this.lines.size(), height);
        this.scrollPosition.set(0.0f, getScrollMaxTop());
    }

    public void clear() {
        synchronized (this.lock) {
            this.lines.clear();
        }
        postInvalidate();
    }

    public void append(String line) {
        synchronized (this.lock) {
            this.lines.add("[" + ((Object) DateFormat.format("HH:mm:ss", System.currentTimeMillis())) + "]  " + line.replace("\n", ""));
            computeScrollSize();
        }
    }

    public static void setFilename(String file) {
        fileName = file.substring(0, file.lastIndexOf("."));
    }

    public static File getLogFile(Context context) {
        File logsDir;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String winlatorPath = sp.getString("winlator_path_uri", null);
        if (winlatorPath != null) {
            Uri winlatorUri = Uri.parse(winlatorPath);
            logsDir = new File(FileUtils.getFilePathFromUri(context, winlatorUri), "logs");
        } else {
            logsDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "logs");
        }
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
        String logFile = fileName.replaceAll("\\s", "_").toLowerCase() + "_" + ((Object) DateFormat.format("yyyy-MM-dd_HH-mm-ss", new Date())) + ".txt";
        return new File(logsDir, logFile);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:19:0x00a3, code lost:
    
        return true;
     */
    @Override // android.view.View
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean onTouchEvent(android.view.MotionEvent r8) {
        /*
            r7 = this;
            int r0 = r8.getAction()
            r1 = 0
            r2 = 1
            switch(r0) {
                case 0: goto L8f;
                case 1: goto L89;
                case 2: goto Lb;
                default: goto L9;
            }
        L9:
            goto La3
        Lb:
            boolean r0 = r7.isActionDown
            if (r0 == 0) goto La3
            float r0 = r8.getX()
            android.graphics.PointF r1 = r7.lastPoint
            float r1 = r1.x
            float r0 = r0 - r1
            float r1 = r8.getY()
            android.graphics.PointF r3 = r7.lastPoint
            float r3 = r3.y
            float r1 = r1 - r3
            float r3 = java.lang.Math.abs(r0)
            r4 = 1092616192(0x41200000, float:10.0)
            int r3 = (r3 > r4 ? 1 : (r3 == r4 ? 0 : -1))
            if (r3 <= 0) goto L2d
            r7.scrollingHorizontally = r2
        L2d:
            float r3 = java.lang.Math.abs(r1)
            int r3 = (r3 > r4 ? 1 : (r3 == r4 ? 0 : -1))
            if (r3 <= 0) goto L37
            r7.scrollingVertically = r2
        L37:
            boolean r3 = r7.scrollingHorizontally
            r4 = 0
            if (r3 == 0) goto L60
            com.winlator.cmod.contentdialog.DebugDialog.setPaused(r2)
            android.graphics.PointF r3 = r7.scrollPosition
            android.graphics.PointF r5 = r7.scrollPosition
            float r5 = r5.x
            float r5 = r5 - r0
            float r6 = r7.getScrollMaxLeft()
            float r5 = com.winlator.cmod.math.Mathf.clamp(r5, r4, r6)
            r3.x = r5
            android.graphics.PointF r3 = r7.lastPoint
            float r5 = r8.getX()
            float r6 = r8.getY()
            r3.set(r5, r6)
            r7.invalidate()
        L60:
            boolean r3 = r7.scrollingVertically
            if (r3 == 0) goto L88
            com.winlator.cmod.contentdialog.DebugDialog.setPaused(r2)
            android.graphics.PointF r3 = r7.scrollPosition
            android.graphics.PointF r5 = r7.scrollPosition
            float r5 = r5.y
            float r5 = r5 - r1
            float r6 = r7.getScrollMaxTop()
            float r4 = com.winlator.cmod.math.Mathf.clamp(r5, r4, r6)
            r3.y = r4
            android.graphics.PointF r3 = r7.lastPoint
            float r4 = r8.getX()
            float r5 = r8.getY()
            r3.set(r4, r5)
            r7.invalidate()
        L88:
            goto La3
        L89:
            com.winlator.cmod.contentdialog.DebugDialog.setPaused(r1)
            r7.isActionDown = r1
            goto La3
        L8f:
            android.graphics.PointF r0 = r7.lastPoint
            float r3 = r8.getX()
            float r4 = r8.getY()
            r0.set(r3, r4)
            r7.isActionDown = r2
            r7.scrollingHorizontally = r1
            r7.scrollingVertically = r1
        La3:
            return r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.widget.LogView.onTouchEvent(android.view.MotionEvent):boolean");
    }
}
