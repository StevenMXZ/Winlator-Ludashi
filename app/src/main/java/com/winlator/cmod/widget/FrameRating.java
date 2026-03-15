package com.winlator.cmod.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.exifinterface.media.ExifInterface;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.CPUStatus;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;

/* loaded from: classes14.dex */
public class FrameRating extends LinearLayout implements Runnable {
    private final int C_BAT;
    private final int C_CPU;
    private final int C_DIVISOR;
    private final int C_FPS_OK;
    private final int C_GPU;
    private final int C_RAM;
    private final int C_TEMP;
    private final int C_VALUE;
    private int battFailCount;
    private BatteryManager batteryManager;
    private volatile float batteryWatts;
    private boolean canReadBatt;
    private boolean canReadCpu;
    private boolean canReadGpu;
    private Context context;
    private int cpuFailCount;
    private volatile int cpuPercent;
    private volatile int cpuTemp;
    private float currentMs;
    private boolean enableBattTemp;
    private boolean enableCpuRam;
    private boolean enableFps;
    private boolean enableGpu;
    private boolean enableGraph;
    private boolean enableRenderer;
    private int frameCount;
    private int gpuFailCount;
    private volatile int gpuLoad;
    private final FrameLayout graphContainer;
    private FrametimeGraphView graphView;
    private boolean isNativeActive;
    private boolean isStatsRunning;
    private float lastFPS;
    private long lastFrameNano;
    private long lastGraphRedraw;
    private long lastTime;
    private volatile String ramText;
    private String rendererName;
    private final View sep0;
    private final View sep1;
    private final View sep2;
    private final View sep3;
    private Handler statsHandler;
    private Runnable statsRunnable;
    private HandlerThread statsThread;
    private final TextView tvFpsBig;
    private final TextView tvGpuLoad;
    private final TextView tvHardwareStats;
    private final TextView tvRenderer;
    private final TextView tvWattsTemp;

    public FrameRating(Context context, HashMap graphicsDriverConfig) {
        this(context, graphicsDriverConfig, null);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs) {
        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.lastTime = 0L;
        this.lastGraphRedraw = 0L;
        this.lastFrameNano = 0L;
        this.frameCount = 0;
        this.lastFPS = 0.0f;
        this.currentMs = 0.0f;
        this.enableFps = true;
        this.enableGraph = true;
        this.enableGpu = true;
        this.enableCpuRam = true;
        this.enableBattTemp = true;
        this.enableRenderer = true;
        this.cpuPercent = -1;
        this.gpuLoad = -1;
        this.batteryWatts = -1.0f;
        this.cpuTemp = -1;
        this.ramText = "N/A";
        this.rendererName = "OpenGL";
        this.canReadGpu = true;
        this.canReadCpu = true;
        this.canReadBatt = true;
        this.gpuFailCount = 0;
        this.cpuFailCount = 0;
        this.battFailCount = 0;
        this.isNativeActive = false;
        this.isStatsRunning = false;
        this.C_VALUE = Color.parseColor("#FFFFFF");
        this.C_CPU = Color.parseColor("#FFAB91");
        this.C_RAM = Color.parseColor("#90CAF9");
        this.C_BAT = Color.parseColor("#EF5350");
        this.C_TEMP = Color.parseColor("#EF5350");
        this.C_GPU = Color.parseColor("#E040FB");
        this.C_FPS_OK = Color.parseColor("#76FF03");
        this.C_DIVISOR = Color.parseColor("#616161");
        this.context = context;
        this.batteryManager = (BatteryManager) context.getSystemService("batterymanager");
        setOrientation(0);
        setLayoutParams(new ViewGroup.LayoutParams(-2, -2));
        setBackgroundColor(0);
        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, (ViewGroup) this, true);
        this.tvRenderer = (TextView) view.findViewById(R.id.TVRenderer);
        this.tvGpuLoad = (TextView) view.findViewById(R.id.TVGpuLoad);
        this.tvHardwareStats = (TextView) view.findViewById(R.id.TVHardwareStats);
        this.tvWattsTemp = (TextView) view.findViewById(R.id.TVWattsTemp);
        this.tvFpsBig = (TextView) view.findViewById(R.id.TVFpsBig);
        this.graphContainer = (FrameLayout) view.findViewById(R.id.FLGraphContainer);
        this.sep0 = view.findViewById(R.id.Sep0);
        this.sep1 = view.findViewById(R.id.Sep1);
        this.sep2 = view.findViewById(R.id.Sep2);
        this.sep3 = view.findViewById(R.id.Sep3);
        this.graphView = new FrametimeGraphView(context);
        if (this.graphContainer != null) {
            this.graphContainer.addView(this.graphView);
        }
        if (this.tvRenderer != null) {
            this.tvRenderer.setText("OpenGL");
        }
        if (this.tvFpsBig != null) {
            this.tvFpsBig.setText("60");
        }
        setupDragListener();
        initStatsThread();
    }

    private void initStatsThread() {
        this.statsRunnable = new Runnable() { // from class: com.winlator.cmod.widget.FrameRating.1
            @Override // java.lang.Runnable
            public void run() {
                if (FrameRating.this.isStatsRunning) {
                    FrameRating.this.calculateStats();
                    if (FrameRating.this.statsHandler != null) {
                        FrameRating.this.statsHandler.postDelayed(this, 1000L);
                    }
                }
            }
        };
    }

    private void startStatsUpdate() {
        if (this.isStatsRunning) {
            return;
        }
        this.isStatsRunning = true;
        this.statsThread = new HandlerThread("HardwareStatsThread");
        this.statsThread.start();
        this.statsHandler = new Handler(this.statsThread.getLooper());
        this.statsHandler.post(this.statsRunnable);
    }

    private void stopStatsUpdate() {
        this.isStatsRunning = false;
        if (this.statsHandler != null) {
            this.statsHandler.removeCallbacks(this.statsRunnable);
        }
        if (this.statsThread != null) {
            this.statsThread.quitSafely();
            this.statsThread = null;
            this.statsHandler = null;
        }
    }

    @Override // android.view.ViewGroup, android.view.View
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        bringToFront();
        setElevation(1000.0f);
        removeCallbacks(this);
        post(this);
        startStatsUpdate();
    }

    @Override // android.view.ViewGroup, android.view.View
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(this);
        stopStatsUpdate();
    }

    private void setupDragListener() {
        setOnTouchListener(new View.OnTouchListener() { // from class: com.winlator.cmod.widget.FrameRating.2
            private int activePointerId = -1;
            private float dX;
            private float dY;

            @Override // android.view.View.OnTouchListener
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getPointerCount() > 1) {
                    this.activePointerId = -1;
                    return false;
                }
                switch (event.getActionMasked()) {
                    case 0:
                        this.activePointerId = event.getPointerId(0);
                        this.dX = view.getX() - event.getRawX();
                        this.dY = view.getY() - event.getRawY();
                        view.bringToFront();
                        break;
                    case 1:
                    case 3:
                        this.activePointerId = -1;
                        break;
                    case 2:
                        if (this.activePointerId != -1) {
                            view.setX(event.getRawX() + this.dX);
                            view.setY(event.getRawY() + this.dY);
                            break;
                        }
                        break;
                }
                return false;
            }
        });
    }

    public void setRenderer(String renderer) {
        if (renderer == null) {
            return;
        }
        if (renderer.contains("DXVK")) {
            this.rendererName = "DXVK";
        } else if (renderer.contains("Turnip")) {
            this.rendererName = "Turnip";
        } else if (renderer.contains("VirGL")) {
            this.rendererName = "VirGL";
        } else if (renderer.contains("llvmpipe")) {
            this.rendererName = ExifInterface.TAG_SOFTWARE;
        } else {
            this.rendererName = renderer.replaceAll(".*Wrapper ", "").trim();
        }
        updateRendererText();
    }

    public void setIsNative(boolean isNative) {
        if (this.isNativeActive != isNative) {
            this.isNativeActive = isNative;
            post(new Runnable() { // from class: com.winlator.cmod.widget.FrameRating$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    FrameRating.this.updateRendererText();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateRendererText() {
        if (this.tvRenderer != null) {
            this.tvRenderer.setText((this.isNativeActive ? "+" : "") + this.rendererName);
            this.tvRenderer.setVisibility(this.enableRenderer ? 0 : 8);
            updateSeparators(getOrientation() == 0);
        }
    }

    public void setGpuName(String name) {
    }

    public void reset() {
        setRenderer("OpenGL");
        this.frameCount = 0;
        this.lastTime = 0L;
    }

    public void setGpuLoad(int load) {
        this.gpuLoad = load;
    }

    public void setLayoutOrientation(boolean z) {
        setOrientation(!z ? 1 : 0);
        updateSeparators(z);
        requestLayout();
    }

    public void setHudScale(float scale) {
        setScaleX(scale);
        setScaleY(scale);
    }

    public void setHudAlpha(float alpha) {
        setAlpha(alpha);
    }

    public void toggleElement(int elementIndex, boolean visible) {
        int v = visible ? 0 : 8;
        switch (elementIndex) {
            case 0:
                this.enableFps = visible;
                if (this.tvFpsBig != null) {
                    this.tvFpsBig.setVisibility(v);
                    break;
                }
                break;
            case 1:
                this.enableRenderer = visible;
                if (this.tvRenderer != null) {
                    this.tvRenderer.setVisibility(v);
                    break;
                }
                break;
            case 2:
                this.enableGpu = visible;
                if (this.tvGpuLoad != null) {
                    this.tvGpuLoad.setVisibility(v);
                    break;
                }
                break;
            case 3:
                this.enableCpuRam = visible;
                if (this.tvHardwareStats != null) {
                    this.tvHardwareStats.setVisibility(v);
                    break;
                }
                break;
            case 4:
                this.enableBattTemp = visible;
                if (this.tvWattsTemp != null) {
                    this.tvWattsTemp.setVisibility(v);
                    break;
                }
                break;
            case 5:
                this.enableGraph = visible;
                if (this.graphContainer != null) {
                    this.graphContainer.setVisibility(v);
                    break;
                }
                break;
        }
        updateSeparators(getOrientation() == 0);
    }

    private void updateSeparators(boolean horizontal) {
        int i = 8;
        if (!horizontal) {
            if (this.sep0 != null) {
                this.sep0.setVisibility(8);
            }
            if (this.sep1 != null) {
                this.sep1.setVisibility(8);
            }
            if (this.sep2 != null) {
                this.sep2.setVisibility(8);
            }
            if (this.sep3 != null) {
                this.sep3.setVisibility(8);
                return;
            }
            return;
        }
        boolean vRen = this.tvRenderer != null && this.tvRenderer.getVisibility() == 0;
        boolean vGpu = this.tvGpuLoad != null && this.tvGpuLoad.getVisibility() == 0;
        boolean vCpu = this.tvHardwareStats != null && this.tvHardwareStats.getVisibility() == 0;
        boolean vBat = this.tvWattsTemp != null && this.tvWattsTemp.getVisibility() == 0;
        boolean vFps = this.tvFpsBig != null && this.tvFpsBig.getVisibility() == 0;
        if (this.sep0 != null) {
            this.sep0.setVisibility((vRen && (vGpu || vCpu || vBat || vFps)) ? 0 : 8);
        }
        if (this.sep1 != null) {
            this.sep1.setVisibility((vGpu && (vCpu || vBat || vFps)) ? 0 : 8);
        }
        if (this.sep2 != null) {
            this.sep2.setVisibility((vCpu && (vBat || vFps)) ? 0 : 8);
        }
        if (this.sep3 != null) {
            View view = this.sep3;
            if (vBat && vFps) {
                i = 0;
            }
            view.setVisibility(i);
        }
    }

    public void update() {
        if (getVisibility() != 0) {
            return;
        }
        if (this.lastTime == 0) {
            this.lastTime = SystemClock.elapsedRealtime();
        }
        long time = SystemClock.elapsedRealtime();
        if (time >= this.lastTime + 500) {
            this.lastFPS = (this.frameCount * 1000) / (time - this.lastTime);
            post(this);
            this.lastTime = time;
            this.frameCount = 0;
        }
        this.frameCount++;
        long nowNano = System.nanoTime();
        if (this.lastFrameNano == 0) {
            this.lastFrameNano = nowNano;
        }
        float ms = (nowNano - this.lastFrameNano) / 1000000.0f;
        this.lastFrameNano = nowNano;
        if (this.enableGraph && ms > 0.0f && ms < 500.0f) {
            this.currentMs = ms;
            if (time - this.lastGraphRedraw >= 50) {
                if (this.graphView != null) {
                    this.graphView.addFrame(ms);
                    this.graphView.postInvalidate();
                }
                this.lastGraphRedraw = time;
                return;
            }
            return;
        }
        if (!this.enableGraph && ms > 0.0f && ms < 500.0f) {
            this.currentMs = ms;
        }
    }

    private long readSysFs(String path) {
        try {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                BufferedReader reader = new BufferedReader(new FileReader(f));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    return Long.parseLong(line.trim());
                }
                return 0L;
            }
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private float getBatteryCurrentAmps() {
        long currentRaw = 0;
        if (this.batteryManager != null) {
            currentRaw = this.batteryManager.getLongProperty(2);
        }
        if (currentRaw == 0 || currentRaw == Long.MIN_VALUE) {
            currentRaw = readSysFs("/sys/class/power_supply/battery/current_now");
        }
        if (currentRaw == 0 || currentRaw == Long.MIN_VALUE) {
            currentRaw = readSysFs("/sys/class/power_supply/bms/current_now");
        }
        if (currentRaw == 0 || currentRaw == Long.MIN_VALUE) {
            return -1.0f;
        }
        long currentRaw2 = Math.abs(currentRaw);
        if (currentRaw2 < 20000) {
            return currentRaw2 / 1000.0f;
        }
        return currentRaw2 / 1000000.0f;
    }

    private int calculateGPULoad() throws Exception {
        BufferedReader reader;
        File f1 = new File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        if (f1.exists() && f1.canRead()) {
            try {
                reader = new BufferedReader(new FileReader(f1));
                try {
                    String line = reader.readLine();
                    if (line != null) {
                        int parseInt = Integer.parseInt(line.trim().replaceAll("[^0-9]", ""));
                        reader.close();
                        return parseInt;
                    }
                    reader.close();
                } finally {
                }
            } catch (Exception e) {
            }
        }
        File f2 = new File("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load");
        if (f2.exists() && f2.canRead()) {
            try {
                BufferedReader reader2 = new BufferedReader(new FileReader(f2));
                try {
                    String line2 = reader2.readLine();
                    if (line2 != null) {
                        int parseInt2 = Integer.parseInt(line2.trim().replaceAll("[^0-9]", ""));
                        reader2.close();
                        return parseInt2;
                    }
                    reader2.close();
                } finally {
                    try {
                        reader2.close();
                    } catch (Throwable th) {
                        th.addSuppressed(th);
                    }
                }
            } catch (Exception e2) {
            }
        }
        File f3 = new File("/sys/class/misc/mali0/device/utilisation");
        if (f3.exists() && f3.canRead()) {
            try {
                reader = new BufferedReader(new FileReader(f3));
                try {
                    String line3 = reader.readLine();
                    if (line3 != null) {
                        int parseInt3 = Integer.parseInt(line3.trim().replaceAll("[^0-9]", ""));
                        reader.close();
                        return parseInt3;
                    }
                    reader.close();
                } finally {
                    try {
                        reader.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
            } catch (Exception e3) {
            }
        }
        File f4 = new File("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (f4.exists() && f4.canRead()) {
            try {
                BufferedReader reader3 = new BufferedReader(new FileReader(f4));
                try {
                    String line4 = reader3.readLine();
                    if (line4 != null) {
                        String[] parts = line4.trim().split("\\s+");
                        if (parts.length >= 2) {
                            long busy = Long.parseLong(parts[0]);
                            long total = Long.parseLong(parts[1]);
                            if (total != 0) {
                                int i = (int) ((100 * busy) / total);
                                reader3.close();
                                return i;
                            }
                        }
                    }
                    reader3.close();
                } finally {
                    try {
                        reader3.close();
                    } catch (Throwable th3) {
                        th.addSuppressed(th3);
                    }
                }
            } catch (Exception e4) {
            }
        }
        throw new Exception("Failed to read GPU usage.");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void calculateStats() {
        if (this.enableGpu && this.canReadGpu) {
            try {
                this.gpuLoad = calculateGPULoad();
                this.gpuFailCount = 0;
            } catch (Exception e) {
                this.gpuLoad = -1;
                this.gpuFailCount++;
                if (this.gpuFailCount > 5) {
                    this.canReadGpu = false;
                    Log.w("FrameRating", "OEM denied GPU read permission or file missing. Displaying N/A.");
                }
            }
        }
        if (this.enableCpuRam) {
            if (this.canReadCpu) {
                try {
                    short[] clocks = CPUStatus.getCurrentClockSpeeds();
                    if (clocks != null && clocks.length > 0) {
                        long cur = 0;
                        long max = 0;
                        for (int i = 0; i < clocks.length; i++) {
                            cur += clocks[i];
                            max += CPUStatus.getMaxClockSpeed(i);
                        }
                        if (max <= 0) {
                            throw new Exception("Max clock is 0");
                        }
                        this.cpuPercent = (int) ((cur * 100) / max);
                        this.cpuFailCount = 0;
                    } else {
                        throw new Exception("Clocks unavailable");
                    }
                } catch (Exception e2) {
                    this.cpuPercent = -1;
                    this.cpuFailCount++;
                    if (this.cpuFailCount > 5) {
                        this.canReadCpu = false;
                        Log.w("FrameRating", "OEM denied CPU read permission. Displaying N/A.");
                    }
                }
            }
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ((ActivityManager) this.context.getSystemService("activity")).getMemoryInfo(mi);
                long used = mi.totalMem - mi.availMem;
                this.ramText = ((100 * used) / mi.totalMem) + "%";
            } catch (Exception e3) {
                this.ramText = "N/A";
            }
        }
        if (this.enableBattTemp && this.canReadBatt) {
            try {
                float amps = getBatteryCurrentAmps();
                if (amps == -1.0f) {
                    throw new Exception("No battery access");
                }
                Intent intent = this.context.registerReceiver(null, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
                int mv = intent != null ? intent.getIntExtra("voltage", 0) : 0;
                if (mv > 0 && amps > 0.0f) {
                    this.batteryWatts = (mv / 1000.0f) * amps;
                } else {
                    this.batteryWatts = -1.0f;
                }
                if (intent != null) {
                    int temp = intent.getIntExtra("temperature", 0);
                    if (temp > 0) {
                        this.cpuTemp = temp / 10;
                    } else {
                        this.cpuTemp = -1;
                    }
                }
                this.battFailCount = 0;
            } catch (Exception e4) {
                this.batteryWatts = -1.0f;
                this.cpuTemp = -1;
                this.battFailCount++;
                if (this.battFailCount > 5) {
                    this.canReadBatt = false;
                    Log.w("FrameRating", "OEM denied Battery/Temperature read permission. Displaying N/A.");
                }
            }
        }
    }

    @Override // java.lang.Runnable
    public void run() {
        if (getVisibility() != 0) {
            postDelayed(this, 1000L);
            return;
        }
        if (this.enableGpu && this.tvGpuLoad != null) {
            SpannableStringBuilder b = new SpannableStringBuilder();
            append(b, "GPU ", this.C_GPU);
            append(b, this.gpuLoad >= 0 ? this.gpuLoad + "%" : "N/A", this.C_VALUE);
            this.tvGpuLoad.setText(b);
        }
        if (this.enableCpuRam && this.tvHardwareStats != null) {
            SpannableStringBuilder b1 = new SpannableStringBuilder();
            append(b1, "CPU ", this.C_CPU);
            append(b1, this.cpuPercent >= 0 ? this.cpuPercent + "% " : "N/A ", this.C_VALUE);
            appendDiv(b1);
            append(b1, "RAM ", this.C_RAM);
            append(b1, this.ramText, this.C_VALUE);
            this.tvHardwareStats.setText(b1);
        }
        if (this.enableBattTemp && this.tvWattsTemp != null) {
            SpannableStringBuilder b2 = new SpannableStringBuilder();
            append(b2, "BAT ", this.C_BAT);
            append(b2, this.batteryWatts >= 0.0f ? String.format(Locale.US, "%.1fW ", Float.valueOf(this.batteryWatts)) : "N/A ", this.C_VALUE);
            appendDiv(b2);
            append(b2, "TMP ", this.C_TEMP);
            append(b2, this.cpuTemp >= 0 ? this.cpuTemp + "°C" : "N/A", this.C_VALUE);
            this.tvWattsTemp.setText(b2);
        }
        if (this.enableFps && this.tvFpsBig != null) {
            this.tvFpsBig.setText(String.format(Locale.US, "%.0f", Float.valueOf(this.lastFPS)));
            this.tvFpsBig.setTextColor(this.C_FPS_OK);
        }
    }

    private void appendDiv(SpannableStringBuilder b) {
        int start = b.length();
        b.append(" | ");
        b.setSpan(new ForegroundColorSpan(this.C_DIVISOR), start, b.length(), 33);
    }

    private void append(SpannableStringBuilder b, String t, int c) {
        int start = b.length();
        b.append((CharSequence) t);
        b.setSpan(new ForegroundColorSpan(c), start, b.length(), 33);
    }

    private class FrametimeGraphView extends View {
        private final int MAX_SAMPLES;
        private final float[] history;
        private int historyIndex;
        private int historySize;
        private final Paint paintLine;
        private final Path path;

        public FrametimeGraphView(Context context) {
            super(context);
            this.path = new Path();
            this.MAX_SAMPLES = 60;
            this.history = new float[60];
            this.historySize = 0;
            this.historyIndex = 0;
            this.paintLine = new Paint();
            this.paintLine.setColor(FrameRating.this.C_FPS_OK);
            this.paintLine.setStrokeWidth(1.5f);
            this.paintLine.setStyle(Paint.Style.STROKE);
            this.paintLine.setAntiAlias(true);
            setBackgroundColor(0);
        }

        public void addFrame(float ms) {
            this.history[this.historyIndex] = Math.min(ms, 66.6f);
            this.historyIndex = (this.historyIndex + 1) % 60;
            if (this.historySize < 60) {
                this.historySize++;
            }
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (this.historySize < 2) {
                return;
            }
            float w = getWidth();
            float h = getHeight();
            float step = w / 59.0f;
            this.path.reset();
            int start = ((this.historyIndex - this.historySize) + 60) % 60;
            float first = this.history[start];
            float yStart = h - ((first / 40.0f) * h);
            this.path.moveTo(0.0f, Math.max(0.0f, yStart));
            for (int i = 1; i < this.historySize; i++) {
                int idx = (start + i) % 60;
                float val = this.history[idx];
                float y = h - ((val / 40.0f) * h);
                float x = i * step;
                this.path.lineTo(x, Math.max(0.0f, y));
            }
            canvas.drawPath(this.path, this.paintLine);
        }
    }
}
