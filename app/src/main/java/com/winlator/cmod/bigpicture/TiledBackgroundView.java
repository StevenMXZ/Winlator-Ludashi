package com.winlator.cmod.bigpicture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import java.io.File;
import java.util.List;

/* loaded from: classes12.dex */
public class TiledBackgroundView extends View {
    private final Runnable animationRunnable;
    private int currentFrame;
    private boolean enableParallax;
    private int frameDuration;
    private Bitmap[] frames;
    private Handler handler;
    private boolean isAnimating;
    private Paint paint;
    private float scrollSpeedX;
    private float scrollSpeedY;
    private float scrollX;
    private float scrollY;
    private Bitmap staticWallpaper;

    public TiledBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.currentFrame = 0;
        this.handler = new Handler();
        this.frameDuration = 66;
        this.isAnimating = false;
        this.enableParallax = true;
        this.scrollX = 0.0f;
        this.scrollY = 0.0f;
        this.scrollSpeedX = 2.0f;
        this.scrollSpeedY = 2.0f;
        this.animationRunnable = new Runnable() { // from class: com.winlator.cmod.bigpicture.TiledBackgroundView.1
            @Override // java.lang.Runnable
            public void run() {
                if (TiledBackgroundView.this.isAnimating) {
                    TiledBackgroundView.this.currentFrame = (TiledBackgroundView.this.currentFrame + 1) % TiledBackgroundView.this.frames.length;
                    TiledBackgroundView.this.updateShader();
                    if (TiledBackgroundView.this.enableParallax) {
                        TiledBackgroundView.this.scrollX += TiledBackgroundView.this.scrollSpeedX;
                        TiledBackgroundView.this.scrollY += TiledBackgroundView.this.scrollSpeedY;
                        if (TiledBackgroundView.this.scrollX > TiledBackgroundView.this.frames[TiledBackgroundView.this.currentFrame].getWidth()) {
                            TiledBackgroundView.this.scrollX = 0.0f;
                        }
                        if (TiledBackgroundView.this.scrollY > TiledBackgroundView.this.frames[TiledBackgroundView.this.currentFrame].getHeight()) {
                            TiledBackgroundView.this.scrollY = 0.0f;
                        }
                    }
                    TiledBackgroundView.this.invalidate();
                    TiledBackgroundView.this.handler.postDelayed(this, TiledBackgroundView.this.frameDuration);
                }
            }
        };
        loadAnimationFrames("ab");
    }

    private void loadAnimationFrames(String animationBaseName) {
        this.frames = new Bitmap[39];
        for (int i = 1; i <= this.frames.length; i++) {
            int resId = getResources().getIdentifier(animationBaseName + "_" + String.format("%04d", Integer.valueOf(i)), "drawable", getContext().getPackageName());
            this.frames[i - 1] = BitmapFactory.decodeResource(getResources(), resId);
        }
        updateShader();
    }

    public void setAnimation(String animationBaseName) {
        stopAnimation();
        this.staticWallpaper = null;
        loadAnimationFrames(animationBaseName);
        this.enableParallax = !animationBaseName.equals("ab_quilt");
        startAnimation();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateShader() {
        BitmapShader shader = new BitmapShader(this.frames[this.currentFrame], Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        this.paint = new Paint();
        this.paint.setShader(shader);
    }

    public void startAnimation() {
        this.isAnimating = true;
        this.handler.post(this.animationRunnable);
    }

    public void stopAnimation() {
        this.isAnimating = false;
        this.handler.removeCallbacks(this.animationRunnable);
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (this.staticWallpaper != null) {
            if (this.paint.getShader() != null) {
                canvas.drawPaint(this.paint);
                return;
            } else {
                canvas.drawBitmap(this.staticWallpaper, (getWidth() - this.staticWallpaper.getWidth()) / 2.0f, (getHeight() - this.staticWallpaper.getHeight()) / 2.0f, this.paint);
                return;
            }
        }
        Matrix matrix = new Matrix();
        if (this.enableParallax) {
            matrix.setTranslate(-this.scrollX, -this.scrollY);
        } else {
            matrix.setTranslate(0.0f, 0.0f);
        }
        this.paint.getShader().setLocalMatrix(matrix);
        canvas.drawPaint(this.paint);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:23:0x002f, code lost:
    
        if (r5.equals("center") != false) goto L20;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void setStaticWallpaper(final android.graphics.Bitmap r4, final java.lang.String r5) {
        /*
            r3 = this;
            int r0 = r3.getWidth()
            if (r0 == 0) goto L91
            int r0 = r3.getHeight()
            if (r0 != 0) goto Le
            goto L91
        Le:
            r3.stopAnimation()
            r3.staticWallpaper = r4
            r0 = 0
            r3.enableParallax = r0
            int r1 = r5.hashCode()
            r2 = 1
            switch(r1) {
                case -1881872635: goto L32;
                case -1364013995: goto L29;
                case 3560110: goto L1f;
                default: goto L1e;
            }
        L1e:
            goto L3c
        L1f:
            java.lang.String r0 = "tile"
            boolean r0 = r5.equals(r0)
            if (r0 == 0) goto L1e
            r0 = 2
            goto L3d
        L29:
            java.lang.String r1 = "center"
            boolean r1 = r5.equals(r1)
            if (r1 == 0) goto L1e
            goto L3d
        L32:
            java.lang.String r0 = "stretch"
            boolean r0 = r5.equals(r0)
            if (r0 == 0) goto L1e
            r0 = r2
            goto L3d
        L3c:
            r0 = -1
        L3d:
            switch(r0) {
                case 0: goto L85;
                case 1: goto L57;
                case 2: goto L41;
                default: goto L40;
            }
        L40:
            goto L8d
        L41:
            android.graphics.BitmapShader r0 = new android.graphics.BitmapShader
            android.graphics.Shader$TileMode r1 = android.graphics.Shader.TileMode.REPEAT
            android.graphics.Shader$TileMode r2 = android.graphics.Shader.TileMode.REPEAT
            r0.<init>(r4, r1, r2)
            android.graphics.Paint r1 = new android.graphics.Paint
            r1.<init>()
            r3.paint = r1
            android.graphics.Paint r1 = r3.paint
            r1.setShader(r0)
            goto L8d
        L57:
            int r0 = r4.getWidth()
            if (r0 <= 0) goto L6f
            int r0 = r4.getHeight()
            if (r0 <= 0) goto L6f
            int r0 = r3.getWidth()
            int r1 = r3.getHeight()
            android.graphics.Bitmap r4 = android.graphics.Bitmap.createScaledBitmap(r4, r0, r1, r2)
        L6f:
            android.graphics.BitmapShader r0 = new android.graphics.BitmapShader
            android.graphics.Shader$TileMode r1 = android.graphics.Shader.TileMode.CLAMP
            android.graphics.Shader$TileMode r2 = android.graphics.Shader.TileMode.CLAMP
            r0.<init>(r4, r1, r2)
            android.graphics.Paint r1 = new android.graphics.Paint
            r1.<init>()
            r3.paint = r1
            android.graphics.Paint r1 = r3.paint
            r1.setShader(r0)
            goto L8d
        L85:
            android.graphics.Paint r0 = new android.graphics.Paint
            r0.<init>()
            r3.paint = r0
        L8d:
            r3.invalidate()
            return
        L91:
            r0 = r4
            android.view.ViewTreeObserver r1 = r3.getViewTreeObserver()
            com.winlator.cmod.bigpicture.TiledBackgroundView$2 r2 = new com.winlator.cmod.bigpicture.TiledBackgroundView$2
            r2.<init>()
            r1.addOnGlobalLayoutListener(r2)
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.bigpicture.TiledBackgroundView.setStaticWallpaper(android.graphics.Bitmap, java.lang.String):void");
    }

    public void loadFramesFromPngFolder(File[] pngFiles) {
        stopAnimation();
        this.frames = new Bitmap[pngFiles.length];
        for (int i = 0; i < pngFiles.length; i++) {
            this.frames[i] = BitmapFactory.decodeFile(pngFiles[i].getAbsolutePath());
        }
        this.enableParallax = true;
        this.currentFrame = 0;
        updateShader();
        startAnimation();
    }

    public void loadFramesFromBitmaps(List<Bitmap> bitmapList) {
        stopAnimation();
        this.frames = new Bitmap[bitmapList.size()];
        for (int i = 0; i < bitmapList.size(); i++) {
            this.frames[i] = bitmapList.get(i);
        }
        this.enableParallax = true;
        this.currentFrame = 0;
        updateShader();
        startAnimation();
    }

    public void setParallax(boolean enable, float speedX, float speedY) {
        this.enableParallax = enable;
        this.scrollSpeedX = speedX;
        this.scrollSpeedY = speedY;
        invalidate();
    }

    public void setFrameDuration(int durationMillis) {
        if (durationMillis < 10) {
            durationMillis = 10;
        }
        this.frameDuration = durationMillis;
    }
}
