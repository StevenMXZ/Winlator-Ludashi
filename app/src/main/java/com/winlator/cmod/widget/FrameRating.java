package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.cmod.R;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.StringUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    private static final int C_FPS_FG = Color.rgb(0x00, 0xE6, 0x76);
    private Context context;
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private long nativeFrameCount, nativeElapsedMs;
    private String totalRAM = null;
    private final TextView tvFPS;
    private final TextView tvRenderer;
    private final TextView tvGPU;
    private final TextView tvRAM;
    private final int defaultFpsColor;
    private HashMap graphicsDriverConfig;
    private static final String PREFS = "winlator_hud";
    private static final String KEY_VIS = "hud_vis";
    private final SharedPreferences prefs;
    private boolean userEnabled = false;
    private VulkanRendererControls frameGenerationSource = null;
    private long lastGeneratedFrames = 0;
    private boolean generatedCounterPrimed = false;
    private volatile boolean frameGenerationActive = false;
    private volatile float outputFPS;

    private String lastKnownRenderer = null;

    public FrameRating(Context context, HashMap graphicsDriverConfig) {
        this(context, graphicsDriverConfig ,null);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs) {
        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(Context context, HashMap graphicsDriverConfig, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        tvFPS = view.findViewById(R.id.TVFPS);
        defaultFpsColor = tvFPS.getCurrentTextColor();
        tvRenderer = view.findViewById(R.id.TVRenderer);
        tvRenderer.setText("Vulkan");
        tvGPU = view.findViewById(R.id.TVGPU);
        tvGPU.setText(GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context));
        tvRAM = view.findViewById(R.id.TVRAM);
        totalRAM = getTotalRAM();
        this.graphicsDriverConfig = graphicsDriverConfig;
        addView(view);
    }

    private String getTotalRAM() {
        long[] mem = readMeminfo();
        return mem[0] > 0 ? StringUtils.formatBytes(mem[0] * 1024L) : "N/A";
    }

    private long[] readMeminfo() {
        long total = -1, avail = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:"))          total = parseMeminfoKb(line);
                else if (line.startsWith("MemAvailable:")) { avail = parseMeminfoKb(line); break; }
            }
        } catch (Exception ignored) {}
        return new long[]{total, avail};
    }

    private long parseMeminfoKb(String line) {
        try { return Long.parseLong(line.trim().split("\\s+")[1]); }
        catch (Exception e) { return -1; }
    }

    private String getAvailableRAM() {
        long[] mem = readMeminfo();
        if (mem[0] <= 0 || mem[1] < 0) return "N/A";
        long usedKb = mem[0] - mem[1];
        return StringUtils.formatBytes(usedKb * 1024L, false);
    }

    public void setRenderer(String renderer) {
        lastKnownRenderer = renderer;
        tvRenderer.setText(renderer);
    }

    public void setGpuName (String gpuName) {
        tvGPU.setText(gpuName);
    }

    public void reset() {
        nativeFrameCount = nativeElapsedMs = 0;
        generatedCounterPrimed = false;
        lastGeneratedFrames = 0;
        frameGenerationActive = false;
        tvFPS.setTextColor(defaultFpsColor);
        tvRenderer.setText(lastKnownRenderer != null ? lastKnownRenderer : "Vulkan");
        tvGPU.setText(GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context));
    }

    public boolean hasSavedPref() {
        return prefs.contains(KEY_VIS);
    }

    public boolean isSavedVisible() {
        return prefs.getBoolean(KEY_VIS, false);
    }

    public void enableByUser() {
        userEnabled = true;
        prefs.edit().putBoolean(KEY_VIS, true).apply();

        post(() -> setVisibility(View.VISIBLE));
    }

    public void disableByUser() {
        disableByUser(true);
    }

    public void disableByUser(boolean savePrefs) {
        userEnabled = false;
        if (savePrefs) prefs.edit().putBoolean(KEY_VIS, false).apply();
        setVisibility(View.GONE);
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void update() {
        if (!userEnabled) return;
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();
        if (time >= lastTime + 500) {
            long elapsed = time - lastTime;
            long generated = sampleGeneratedFrames();
            lastFPS = frameCount * 1000f / elapsed;
            outputFPS = (frameCount + generated) * 1000f / elapsed;
            post(this);
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    @Override
    public void run() {
        if (!userEnabled) return;
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        tvFPS.setTextColor(defaultFpsColor);
        String base = String.format(Locale.ENGLISH, "%.1f", lastFPS);
        android.text.SpannableString text = new android.text.SpannableString(base +
                (frameGenerationActive ? String.format(Locale.ENGLISH, " -> %.1f", outputFPS) : ""));
        if (frameGenerationActive) text.setSpan(new android.text.style.ForegroundColorSpan(
                android.graphics.Color.rgb(176, 255, 176)), base.length(), text.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvFPS.setText(text);
        tvRAM.setText(getAvailableRAM() + " GB Used / " + totalRAM + " Total");
    }

    public void updateNativeFrames(long frames, long elapsedMs) {
        if (!userEnabled || elapsedMs <= 0) return;
        nativeFrameCount += frames;
        nativeElapsedMs += elapsedMs;
        if (nativeElapsedMs < 500) return;
        long generated = sampleGeneratedFrames();
        lastFPS = nativeFrameCount * 1000f / nativeElapsedMs;
        outputFPS = (nativeFrameCount + generated) * 1000f / nativeElapsedMs;
        nativeFrameCount = nativeElapsedMs = 0;
        run();
    }

    public void setFrameGenerationSource(VulkanRendererControls source) {
        frameGenerationSource = source;
        generatedCounterPrimed = false;
        lastGeneratedFrames = 0;
    }

    private long sampleGeneratedFrames() {
        VulkanRendererControls source = frameGenerationSource;
        boolean active = source != null && source.isFrameGenerationEnabled();
        frameGenerationActive = active;
        if (!active) {
            generatedCounterPrimed = false;
            lastGeneratedFrames = 0;
            return 0;
        }

        long current = source.getGeneratedFrameCount();
        if (!generatedCounterPrimed || current < lastGeneratedFrames) {
            lastGeneratedFrames = current;
            generatedCounterPrimed = true;
            return 0;
        }

        long delta = current - lastGeneratedFrames;
        lastGeneratedFrames = current;

        return Math.max(0L, delta);
    }

}

