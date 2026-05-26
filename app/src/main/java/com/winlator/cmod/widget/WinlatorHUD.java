package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public class WinlatorHUD extends View {
    private static final String PREFS    = "winlator_hud";
    private static final String KEY_X    = "hud_x";
    private static final String KEY_Y    = "hud_y";
    private static final String KEY_VIS  = "hud_vis";
    private static final String KEY_SHOW = "hud_show";
    private static final String KEY_SCALE= "hud_scale";
    private static final String KEY_ALPHA= "hud_alpha_int";
    private static final String KEY_VERT = "hud_vertical";

    public static final int SHOW_FPS      = 1;
    public static final int SHOW_GPU      = 1<<1;
    public static final int SHOW_CPU      = 1<<2;
    public static final int SHOW_BATT     = 1<<3;
    public static final int SHOW_GRAPH    = 1<<4;
    public static final int SHOW_RENDERER = 1<<5;
    public static final int SHOW_RAM      = 1<<6;
    public static final int SHOW_LATENCY  = 1<<7;
    private static final int SHOW_DEFAULT = 0x6F;

    private static final int C_BG   = Color.argb(180, 0,   0,   0  );
    private static final int C_WHITE= Color.WHITE;
    private static final int C_GPU  = Color.rgb(0xE0,0x40,0xFB);
    private static final int C_CPU  = Color.rgb(0x00,0xE5,0xFF);
    private static final int C_BATT = Color.rgb(0xFF,0x80,0x00);
    private static final int C_CHG  = Color.rgb(0x40,0xC4,0x40);
    private static final int C_TEMP = Color.rgb(0xEF,0x53,0x50);
    private static final int C_FPS  = Color.rgb(0x76,0xFF,0x03);
    private static final int C_REND = Color.rgb(0xFF,0xEA,0x00);
    private static final int C_RAM  = Color.rgb(0xB0,0xFF,0xB0);
    private static final int C_LAT  = Color.rgb(0x80,0xD8,0xFF);
    private static final int C_SEP  = Color.rgb(0x60,0x60,0x60);

    private float TS, TSR, PAD, GRAW, CORNER;

    private static final int TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG;
    private final Paint pBg      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal     = new Paint(TEXT_FLAGS);
    private final Paint pGpu     = new Paint(TEXT_FLAGS);
    private final Paint pCpu     = new Paint(TEXT_FLAGS);
    private final Paint pBat     = new Paint(TEXT_FLAGS);
    private final Paint pTmp     = new Paint(TEXT_FLAGS);
    private final Paint pFps     = new Paint(TEXT_FLAGS);
    private final Paint pRend    = new Paint(TEXT_FLAGS);
    private final Paint pRam     = new Paint(TEXT_FLAGS);
    private final Paint pLat     = new Paint(TEXT_FLAGS);
    private final Paint pSep     = new Paint(TEXT_FLAGS);
    private final Paint pChg     = new Paint(TEXT_FLAGS); // cached — avoids new Paint() every frame
    private final Paint pGraph   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGraphBg = new Paint();

    private final RectF bgRect = new RectF();

    private float wLabelGpu, wLabelCpu, wLabelRam, wLabelPwr, wLabelTmp, wLabelFps, wLabelLat, wSep;
    private float wVal100pct, wValFps, wValWatt, wValTemp, wValLat;

    private float cachedHorizWidth = -1;
    private boolean layoutDirty = true;

    private String strGpu = "N/A", strCpu = "N/A", strRam = "N/A";
    private String strPwr = "N/A", strTmp = "", strFps = "0";
    private String strRend = "Vulkan";
    private String strLat = "—";
    private boolean snapCharging = false;

    private int lastBgAlpha = -1;

    private final SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private HudDataSource dataSource;

    private final AtomicInteger frameAccum = new AtomicInteger(0);
    private long lastFpsNs = 0;
    /* DAC frame-count supplier — set by VulkanRenderer.setFrameRating().
     * Returns the native directFrameCount (monotonic, ticked in
     * applyScanoutBuffer). When isNative is true and this is non-null,
     * snapshot() uses it as the FPS source instead of frameAccum, which
     * stays at 0 under DAC because the X11 window-update path that used
     * to call onFrame() is disabled. */
    private LongSupplier nativeFrameCountSupplier = null;
    private long lastNativeFrameCount = -1;
    private float snapFps = 0;

    /* DAC compositor-latency supplier. Returns the EMA in microseconds
     * from the native VulkanRendererContext (T1 = recv MSG_PRESENT,
     * T2 = SurfaceFlinger onCommit). Returns 0 when there's no DAC data
     * (Native X11 mode, or the first few frames before the EMA seeds). */
    private LongSupplier latencySupplier = null;
    private long snapLatencyUs = 0;

    private int snapGpu=-1, snapCpu=-1, snapMw=-1, snapTmp=-1, snapPct=-1, snapRam=-1;
    private String rendererLabel = "Vulkan";
    private boolean isNative = false;

    private static final int GBUF = 40;
    private final float[] graph = new float[GBUF];
    private int gHead = 0;
    private float gMax = 60f;

    private int showMask = SHOW_DEFAULT;
    private float hudAlpha = 1f;
    private boolean userEnabled = false;
    private boolean vertical = false;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private static final float DRAG_THRESH = 10f;
    private long touchDownMs = 0;

    private boolean redrawScheduled = false;
    private Path cachedPath = null;
    private int lastGHead = -1;
    private final Runnable redrawRunnable = () -> {
        redrawScheduled = false;
        try {
            snapshot();
            invalidate();
        } catch (Exception ignored) {
        }
        if (getVisibility() == VISIBLE) scheduleRedraw();
    };

    public WinlatorHUD(Context context) { this(context, null); }

    public WinlatorHUD(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float d = context.getResources().getDisplayMetrics().density;
        TS     = 12f * d;
        TSR    = 11f * d;
        PAD    = 6f * d;
        GRAW   = 70f * d;
        CORNER = 5f * d;
        initPaints();
        loadPrefs();
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private void initPaints() {
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        pBg.setStyle(Paint.Style.FILL);
        pVal.setTextSize(TS);       pVal.setTypeface(mono);  pVal.setColor(C_WHITE);
        pGpu.setTextSize(TS);       pGpu.setTypeface(mono);  pGpu.setColor(C_GPU);
        pCpu.setTextSize(TS);       pCpu.setTypeface(mono);  pCpu.setColor(C_CPU);
        pBat.setTextSize(TS);       pBat.setTypeface(mono);  pBat.setColor(C_BATT);
        pTmp.setTextSize(TS);       pTmp.setTypeface(mono);  pTmp.setColor(C_TEMP);
        pFps.setTextSize(TS * 1.25f);
        pFps.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        pFps.setColor(C_FPS);
        pRend.setTextSize(TSR);     pRend.setTypeface(mono); pRend.setColor(C_REND);
        pRam.setTextSize(TS);       pRam.setTypeface(mono);  pRam.setColor(C_RAM);
        pLat.setTextSize(TS);       pLat.setTypeface(mono);  pLat.setColor(C_LAT);
        pSep.setTextSize(TS);       pSep.setTypeface(mono);  pSep.setColor(C_SEP);
        pChg.setTextSize(TS);       pChg.setTypeface(mono);  pChg.setColor(C_CHG);
        pGraph.setStyle(Paint.Style.STROKE); pGraph.setStrokeWidth(1.5f); pGraph.setColor(C_FPS);
        pGraphBg.setStyle(Paint.Style.FILL); pGraphBg.setColor(Color.argb(80,20,20,20));

        wLabelGpu  = pGpu.measureText("GPU ");
        wLabelCpu  = pCpu.measureText("CPU ");
        wLabelRam  = pRam.measureText("RAM ");
        wLabelPwr  = pBat.measureText("PWR ");
        wLabelTmp  = pTmp.measureText("TMP ");
        wLabelFps  = pFps.measureText("FPS ");
        wLabelLat  = pLat.measureText("LAT ");
        wSep       = pSep.measureText(" | ");
        wVal100pct = pVal.measureText("100%");
        wValFps    = pFps.measureText("999");
        wValWatt   = pVal.measureText("9.9W");
        wValTemp   = pVal.measureText("99°C");
        wValLat    = pVal.measureText("99.9ms");
    }

    public void setDataSource(HudDataSource ds) { this.dataSource = ds; }

    public void onFrame() { frameAccum.incrementAndGet(); }

    public void setNativeFrameCountSupplier(LongSupplier s) {
        this.nativeFrameCountSupplier = s;
        this.lastNativeFrameCount = -1;  // reset delta baseline
    }

    public void setLatencySupplier(LongSupplier s) {
        this.latencySupplier = s;
    }

    public void setIsNative(boolean n) {
        isNative = n;
        strRend = (isNative ? "+" : "") + rendererLabel;
        layoutDirty = true;
    }

    public void update() {}

    private void snapshot() {
        long now = System.nanoTime();
        if (lastFpsNs == 0) lastFpsNs = now;
        long dt = now - lastFpsNs;
        if (dt >= 350_000_000L) {
            int f;
            /* Auto-detect frame source. Don't gate on isNative — that flag
             * only flips when the X11 path is alive (it's set in
             * onUpdateWindowContent which DAC disables via
             * xServer.setRenderingEnabled(false)). Under pure DAC we'd see
             * isNative=false forever and never use the native counter.
             *
             * Strategy: if the native DAC counter advanced, use that delta.
             * Else fall back to frameAccum (X11 path). This works for both
             * modes and for the transition. */
            int xPathFrames = frameAccum.getAndSet(0);
            long nativeDelta = 0;
            if (nativeFrameCountSupplier != null) {
                long cur = nativeFrameCountSupplier.getAsLong();
                if (lastNativeFrameCount < 0) lastNativeFrameCount = cur;
                nativeDelta = cur - lastNativeFrameCount;
                if (nativeDelta < 0) nativeDelta = 0;  // wraparound or reset
                lastNativeFrameCount = cur;
            }
            f = (nativeDelta > 0)
                    ? (int) Math.min((long) Integer.MAX_VALUE, nativeDelta)
                    : xPathFrames;
            /* Keep the "+" renderer-label indicator in sync with reality:
             * if DAC frames are flowing, we're effectively in native mode
             * regardless of whether anyone called setIsNative(true) on us. */
            boolean shouldBeNative = (nativeDelta > 0);
            if (shouldBeNative != isNative) {
                isNative = shouldBeNative;
                strRend = (isNative ? "+" : "") + rendererLabel;
                layoutDirty = true;
            }
            snapFps = f * 1_000_000_000f / dt;
            lastFpsNs = now;
            graph[gHead % GBUF] = snapFps;
            gHead++;
            cachedPath = null;
            float targetMax = Math.max(30f, snapFps * 1.2f);
            gMax = gMax + (targetMax - gMax) * 0.15f;
            strFps = String.format(Locale.US, "%.0f", snapFps);
        }
        if (dataSource != null) {
            int g  = dataSource.gpuLoad.get();
            int cp = dataSource.cpuLoad.get();
            int mw = dataSource.batteryMw.get();
            int tm = dataSource.batteryTempC.get();
            int pc = dataSource.batteryPct.get();
            int rm = dataSource.ramUsagePct.get();

            if (g != snapGpu)   { snapGpu = g;  strGpu = g  >= 0 ? g  + "%" : "N/A"; }
            if (cp != snapCpu)  { snapCpu = cp; strCpu = cp >= 0 ? cp + "%" : "N/A"; }
            if (rm != snapRam)  { snapRam = rm; strRam = rm >= 0 ? rm + "%" : "N/A"; }
            if (tm != snapTmp)  { snapTmp = tm; strTmp = tm > 0 ? tm + "°C" : ""; }
            if (pc != snapPct)  { snapPct = pc; }
            if (mw != snapMw) {
                snapMw = mw;
                snapCharging = (mw == -2);
                if (snapCharging)   strPwr = "CHG";
                else if (mw > 0)    strPwr = String.format(Locale.US, "%.1fW", mw / 1000f);
                else                strPwr = "N/A";
            }
        }
        /* Poll the DAC compositor-latency supplier. Returns the EMA in µs;
         * 0 means no DAC data (Native mode or pre-seed). Convert to ms
         * with 1 decimal of precision. The string is short ("16.7ms",
         * "12.3ms") so layout invalidation is cheap. */
        if (latencySupplier != null) {
            long us = latencySupplier.getAsLong();
            if (us != snapLatencyUs) {
                snapLatencyUs = us;
                strLat = (us == 0)
                    ? "—"
                    : String.format(Locale.US, "%.1fms", us / 1000f);
                /* Width may change between "—" and a numeric value — mark
                 * the cached horizontal width dirty so the next draw
                 * re-measures. */
                layoutDirty = true;
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (getVisibility() != VISIBLE) return;
        try {
            int targetAlpha = (int)(180 * hudAlpha);
            if (targetAlpha != lastBgAlpha) {
                pBg.setAlpha(targetAlpha);
                lastBgAlpha = targetAlpha;
            }
            if (vertical) drawVertical(c);
            else          drawHorizontal(c);
        } catch (Exception e) {
        }
    }

    private void drawHorizontal(Canvas c) {
        float x = PAD;
        float rowH = TS + PAD * 2;
        float baseline = PAD + TS;

        bgRect.set(0, 0, getCachedHorizWidth(), rowH);
        c.drawRoundRect(bgRect, CORNER, CORNER, pBg);

        if ((showMask & SHOW_RENDERER) != 0) {
            c.drawText(strRend, x, baseline, pRend);
            x += pRend.measureText(strRend);
            x += drawSep(c, x, baseline);
        }
        if ((showMask & SHOW_GPU) != 0) {
            c.drawText("GPU ", x, baseline, pGpu); x += wLabelGpu;
            c.drawText(strGpu, x, baseline, pVal); x += pVal.measureText(strGpu);
            x += drawSep(c, x, baseline);
        }
        if ((showMask & SHOW_CPU) != 0) {
            c.drawText("CPU ", x, baseline, pCpu); x += wLabelCpu;
            c.drawText(strCpu, x, baseline, pVal); x += pVal.measureText(strCpu);
            x += drawSep(c, x, baseline);
        }
        if ((showMask & SHOW_RAM) != 0) {
            c.drawText("RAM ", x, baseline, pRam); x += wLabelRam;
            c.drawText(strRam, x, baseline, pVal); x += pVal.measureText(strRam);
            x += drawSep(c, x, baseline);
        }
        if ((showMask & SHOW_BATT) != 0) {
            c.drawText("PWR ", x, baseline, pBat); x += wLabelPwr;
            c.drawText(strPwr, x, baseline, snapCharging ? pChg : pVal);
            x += pVal.measureText(strPwr);
            if (!strTmp.isEmpty()) {
                x += drawSep(c, x, baseline);
                c.drawText("TMP ", x, baseline, pTmp); x += wLabelTmp;
                c.drawText(strTmp, x, baseline, pVal); x += pVal.measureText(strTmp);
            }
            x += drawSep(c, x, baseline);
        }
        if ((showMask & SHOW_FPS) != 0) {
            float fb = baseline + (pFps.getTextSize() - TS) / 2f;
            c.drawText("FPS ", x, fb, pFps); x += wLabelFps;
            c.drawText(strFps, x, fb, pFps); x += pFps.measureText(strFps);
            if ((showMask & SHOW_GRAPH) != 0) {
                x += PAD;
                drawInlineGraph(c, x, PAD, GRAW, TS + PAD);
            }
        }
        if ((showMask & SHOW_LATENCY) != 0) {
            x += drawSep(c, x, baseline);
            c.drawText("LAT ", x, baseline, pLat); x += wLabelLat;
            c.drawText(strLat, x, baseline, pVal); x += pVal.measureText(strLat);
        }
    }

    private void drawVertical(Canvas c) {
        float lineH = TS + PAD * 2;
        float rows  = countVerticalRows();
        float w     = measureVertical();
        float h     = rows * lineH + PAD;

        bgRect.set(0, 0, w, h);
        c.drawRoundRect(bgRect, CORNER, CORNER, pBg);

        float y = PAD;
        if ((showMask & SHOW_RENDERER) != 0) {
            c.drawText(strRend, PAD, y + TS, pRend);
            y += lineH;
        }
        if ((showMask & SHOW_GPU) != 0) {
            c.drawText("GPU ", PAD, y + TS, pGpu);
            c.drawText(strGpu, PAD + wLabelGpu, y + TS, pVal);
            y += lineH;
        }
        if ((showMask & SHOW_CPU) != 0) {
            c.drawText("CPU ", PAD, y + TS, pCpu);
            c.drawText(strCpu, PAD + wLabelCpu, y + TS, pVal);
            y += lineH;
        }
        if ((showMask & SHOW_RAM) != 0) {
            c.drawText("RAM ", PAD, y + TS, pRam);
            c.drawText(strRam, PAD + wLabelRam, y + TS, pVal);
            y += lineH;
        }
        if ((showMask & SHOW_BATT) != 0) {
            c.drawText("PWR ", PAD, y + TS, pBat);
            c.drawText(strPwr, PAD + wLabelPwr, y + TS, snapCharging ? pChg : pVal);
            y += lineH;
            if (!strTmp.isEmpty()) {
                c.drawText("TMP ", PAD, y + TS, pTmp);
                c.drawText(strTmp, PAD + wLabelTmp, y + TS, pVal);
                y += lineH;
            }
        }
        if ((showMask & SHOW_FPS) != 0) {
            float fb = y + TS + (pFps.getTextSize() - TS) / 2f;
            c.drawText("FPS ", PAD, fb, pFps);
            c.drawText(strFps, PAD + wLabelFps, fb, pFps);
            y += lineH;
        }
        if ((showMask & SHOW_LATENCY) != 0) {
            c.drawText("LAT ", PAD, y + TS, pLat);
            c.drawText(strLat, PAD + wLabelLat, y + TS, pVal);
        }
    }

    private float getCachedHorizWidth() {
        if (layoutDirty || cachedHorizWidth < 0) {
            cachedHorizWidth = measureHorizontal();
            layoutDirty = false;
        }
        return cachedHorizWidth;
    }

    private float drawSep(Canvas c, float x, float baseline) {
        c.drawText(" | ", x, baseline, pSep);
        return wSep;
    }

    private void drawInlineGraph(Canvas c, float x, float y, float w, float h) {
        c.drawRect(x, y, x + w, y + h, pGraphBg);
        int count = Math.min(gHead, GBUF);
        if (count < 2) return;
        if (cachedPath == null || lastGHead != gHead) {
            cachedPath = new Path();
            float bw = w / GBUF;
            boolean first = true;
            for (int i = 0; i < count; i++) {
                float v  = graph[(gHead - count + i) % GBUF];
                float px = x + i * bw;
                float py = y + h - (v / gMax) * h;
                if (first) { cachedPath.moveTo(px, py); first = false; }
                else        { cachedPath.lineTo(px, py); }
            }
            lastGHead = gHead;
        }
        c.drawPath(cachedPath, pGraph);
    }

    private float measureHorizontal() {
        float w = PAD;
        if ((showMask & SHOW_RENDERER) != 0) w += pRend.measureText(strRend) + wSep;
        if ((showMask & SHOW_GPU)      != 0) w += wLabelGpu + wVal100pct + wSep;
        if ((showMask & SHOW_CPU)      != 0) w += wLabelCpu + wVal100pct + wSep;
        if ((showMask & SHOW_RAM)      != 0) w += wLabelRam + wVal100pct + wSep;
        if ((showMask & SHOW_BATT)     != 0) {
            w += wLabelPwr + wValWatt + wSep + wLabelTmp + wValTemp + wSep;
        }
        if ((showMask & SHOW_FPS)   != 0) w += wLabelFps + wValFps;
        if ((showMask & SHOW_GRAPH) != 0) w += PAD + GRAW;
        if ((showMask & SHOW_LATENCY) != 0) w += wSep + wLabelLat + Math.max(wValLat, pVal.measureText(strLat));
        return w + PAD;
    }

    private float measureVertical() {
        float w = PAD * 2;
        if ((showMask & SHOW_RENDERER) != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strRend));
        if ((showMask & SHOW_GPU)      != 0) w = Math.max(w, PAD * 2 + wLabelGpu + wVal100pct);
        if ((showMask & SHOW_CPU)      != 0) w = Math.max(w, PAD * 2 + wLabelCpu + wVal100pct);
        if ((showMask & SHOW_RAM)      != 0) w = Math.max(w, PAD * 2 + wLabelRam + wVal100pct);
        if ((showMask & SHOW_BATT)     != 0) w = Math.max(w, PAD * 2 + wLabelPwr + wValWatt);
        if ((showMask & SHOW_FPS)      != 0) w = Math.max(w, PAD * 2 + pFps.measureText("FPS 999"));
        if ((showMask & SHOW_LATENCY)  != 0) w = Math.max(w, PAD * 2 + wLabelLat + wValLat);
        return w;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        float lineH = TS + PAD * 2;
        float w = vertical ? measureVertical() : measureHorizontal();
        float h = vertical ? (countVerticalRows() * lineH + PAD) : lineH;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    private float countVerticalRows() {
        float r = 0;
        if ((showMask & SHOW_RENDERER) != 0) r++;
        if ((showMask & SHOW_GPU)      != 0) r++;
        if ((showMask & SHOW_CPU)      != 0) r++;
        if ((showMask & SHOW_RAM)      != 0) r++;
        if ((showMask & SHOW_BATT)     != 0) { r++; if (snapTmp > 0) r++; }
        if ((showMask & SHOW_FPS)      != 0) r++;
        if ((showMask & SHOW_LATENCY)  != 0) r++;
        return Math.max(1, r);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getPointerCount() > 1) return true;
                touchX = e.getRawX(); touchY = e.getRawY();
                startX = getX();      startY = getY();
                dragging = false;
                touchDownMs = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - touchX, dy = e.getRawY() - touchY;
                if (!dragging && Math.hypot(dx, dy) > DRAG_THRESH) dragging = true;
                if (dragging) { setX(startX + dx); setY(startY + dy); }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                dragging = false;
                touchDownMs = 0;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                touchDownMs = 0;
                return true;
            case MotionEvent.ACTION_UP:
                if (e.getPointerCount() > 1) { dragging = false; return true; }
                if (dragging) {
                    savePosition();
                } else if (touchDownMs > 0 && System.currentTimeMillis() - touchDownMs < 300) {
                    vertical = !vertical;
                    prefs.edit().putBoolean(KEY_VERT, vertical).apply();
                    try { requestLayout(); invalidate(); } catch (Exception ignored) {}
                    uiHandler.postDelayed(this::ensureVisible, 250);
                }
                dragging = false;
                return true;
        }
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (userEnabled && rendererActive) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            setVisibility(VISIBLE);
            scheduleRedraw();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
    }

    private void ensureVisible() {
        if (userEnabled && rendererActive) {
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            scheduleRedraw();
        }
    }

    private void savePosition() {
        prefs.edit().putFloat(KEY_X, getX()).putFloat(KEY_Y, getY()).apply();
    }

    private void scheduleRedraw() {
        if (!redrawScheduled) {
            redrawScheduled = true;
            uiHandler.postDelayed(redrawRunnable, 400); // data polls at 1500ms, 400ms is plenty
        }
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (vis == VISIBLE) {
            scheduleRedraw();
        } else {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            if (userEnabled) {
                uiHandler.postDelayed(this::ensureVisible, 300);
            }
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && userEnabled) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            uiHandler.postDelayed(this::ensureVisible, 150);
        }
    }

    private void loadPrefs() {
        showMask = prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
        hudAlpha = prefs.getInt(KEY_ALPHA, 100) / 100f;
        vertical = prefs.getBoolean(KEY_VERT, false);
        float scale = prefs.getFloat(KEY_SCALE, 1f);
        setScaleX(scale); setScaleY(scale);
        setX(prefs.getFloat(KEY_X, 16f));
        setY(prefs.getFloat(KEY_Y, 16f));
        userEnabled = false;
        setVisibility(GONE);
    }

    private boolean rendererActive = false;

    public boolean hasSavedPref()   { return prefs.contains(KEY_VIS); }
    public boolean isSavedVisible() { return prefs.getBoolean(KEY_VIS, false); }

    public void enableByUser() {
        userEnabled = true;
        prefs.edit().putBoolean(KEY_VIS, true).apply();
        if (dataSource != null) dataSource.start();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        setVisibility(VISIBLE);
        scheduleRedraw();
    }

    public void disableByUser() {
        disableByUser(true);
    }

    public void disableByUser(boolean savePrefs) {
        userEnabled = false;
        if (savePrefs) prefs.edit().putBoolean(KEY_VIS, false).apply();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        setVisibility(GONE);
    }

    public void resetFromContainer() {
        uiHandler.post(() -> {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            frameAccum.set(0);
            snapFps = 0;
            gHead = 0;
            lastFpsNs = 0;
            lastNativeFrameCount = -1;
            if (userEnabled) {
                setVisibility(VISIBLE);
                scheduleRedraw();
            } else {
                setVisibility(GONE);
            }
        });
    }

    public void onRendererDetected(String name) {
        uiHandler.post(() -> {
            rendererActive = true;
            if (name != null && !name.isEmpty()) rendererLabel = name;
            strRend = (isNative ? "+" : "") + rendererLabel;
            layoutDirty = true;
            if (userEnabled) { setVisibility(VISIBLE); scheduleRedraw(); }
        });
    }

    public void onRendererGone() {
        uiHandler.post(() -> {
            rendererActive = false;
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            setVisibility(GONE);
            frameAccum.set(0); snapFps = 0; gHead = 0; lastFpsNs = 0; lastNativeFrameCount = -1;
        });
    }

    public void setRenderer(String name) {
        if (name != null && !name.isEmpty()) rendererLabel = name;
    }
    public void setGpuName(String name) {}

    public void toggleElement(int idx, boolean on) {
        int bit = idxToMask(idx);
        if (bit == 0) return;
        if (on) showMask |= bit; else showMask &= ~bit;
        prefs.edit().putInt(KEY_SHOW, showMask).apply();
        layoutDirty = true;
        try { requestLayout(); invalidate(); } catch (Exception ignored) {}
    }

    public void syncCheckboxes(android.widget.CheckBox cbFps, android.widget.CheckBox cbGpu,
            android.widget.CheckBox cbCpuRam, android.widget.CheckBox cbBattTemp,
            android.widget.CheckBox cbGraph, android.widget.CheckBox cbRenderer) {
        syncCheckboxes(cbFps, cbGpu, cbCpuRam, cbBattTemp, cbGraph, cbRenderer, null);
    }

    /* Overload that also accepts the Latency checkbox so the sidebar state
     * persists across launches. Older call sites without the LAT checkbox
     * keep using the 6-arg version above. */
    public void syncCheckboxes(android.widget.CheckBox cbFps, android.widget.CheckBox cbGpu,
            android.widget.CheckBox cbCpuRam, android.widget.CheckBox cbBattTemp,
            android.widget.CheckBox cbGraph, android.widget.CheckBox cbRenderer,
            android.widget.CheckBox cbLatency) {
        if (cbFps      != null) cbFps.setChecked((showMask & SHOW_FPS)       != 0);
        if (cbGpu      != null) cbGpu.setChecked((showMask & SHOW_GPU)       != 0);
        if (cbCpuRam   != null) cbCpuRam.setChecked((showMask & SHOW_CPU)    != 0);
        if (cbBattTemp != null) cbBattTemp.setChecked((showMask & SHOW_BATT) != 0);
        if (cbGraph    != null) cbGraph.setChecked((showMask & SHOW_GRAPH)   != 0);
        if (cbRenderer != null) cbRenderer.setChecked((showMask & SHOW_RENDERER) != 0);
        if (cbLatency  != null) cbLatency.setChecked((showMask & SHOW_LATENCY) != 0);
    }

    public void setHudScale(float scale) {
        setScaleX(scale); setScaleY(scale);
        prefs.edit().putFloat(KEY_SCALE, scale).apply();
    }

    public void setHudAlpha(float a) {
        hudAlpha = Math.max(0f, Math.min(1f, a));
        prefs.edit().putInt(KEY_ALPHA, (int)(hudAlpha * 100)).apply();
        invalidate();
    }

    public void reset() {
        rendererLabel = "Vulkan"; frameAccum.set(0); snapFps = 0; gHead = 0; lastFpsNs = 0; lastNativeFrameCount = -1;
    }

    /**
     * Hard reset callable from the sidebar "Reset HUD" button.
     * Forces the HUD visible and restarts the draw loop regardless of whatever
     * state it was left in (e.g. after a 5-finger system gesture or any crash).
     *
     * Key fix vs the old forceReset: also sets rendererActive=true.
     * Without it, ensureVisible() and scheduleRedraw() are no-ops because they
     * guard on rendererActive, so the HUD draw loop never restarted.
     */
    public void forceReset() {
        uiHandler.post(() -> {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            frameAccum.set(0);
            snapFps = 0; gHead = 0; lastFpsNs = 0; lastNativeFrameCount = -1;
            cachedPath = null; lastGHead = -1;
            dragging = false; touchDownMs = 0;
            rendererActive = true;
            userEnabled = true;
            prefs.edit().putBoolean(KEY_VIS, true).apply();
            if (dataSource != null) dataSource.start();
            setVisibility(VISIBLE);
            scheduleRedraw();
        });
    }

    private int idxToMask(int idx) {
        switch (idx) {
            case 0: return SHOW_FPS;
            case 2: return SHOW_GPU;
            case 3: return SHOW_CPU;
            case 4: return SHOW_BATT;
            case 5: return SHOW_GRAPH;
            case 6: return SHOW_RENDERER;
            case 7: return SHOW_RAM;
            case 8: return SHOW_LATENCY;
            default: return 0;
        }
    }
}
