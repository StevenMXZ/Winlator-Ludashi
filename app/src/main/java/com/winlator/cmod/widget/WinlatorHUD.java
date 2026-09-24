package com.winlator.cmod.widget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.GPUInformation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class WinlatorHUD extends View {
    public static final String PREFS = "winlator_hud";
    private static final String KEY_X = "hud_x";
    private static final String KEY_Y = "hud_y";
    private static final String KEY_VIS = "hud_vis";
    public static final String KEY_SHOW = "hud_show";
    private static final String KEY_SHOW_V2 = "hud_show_v2";
    private static final String KEY_SCALE = "hud_scale";
    private static final String KEY_ALPHA = "hud_alpha_int";
    private static final String KEY_VERT = "hud_vertical";
    public static final String KEY_DUAL_CELL = "hud_dual_cell_correction";

    public static final int SHOW_FPS = 1;
    public static final int SHOW_GPU_USAGE = 1 << 1;
    public static final int SHOW_CPU_USAGE = 1 << 2;
    public static final int SHOW_POWER = 1 << 3;
    public static final int SHOW_RENDERER = 1 << 5;
    public static final int SHOW_RAM = 1 << 6;
    public static final int SHOW_GPU_NAME = 1 << 7;
    public static final int SHOW_CPU_TEMP = 1 << 8;
    public static final int SHOW_BATTERY_TEMP = 1 << 9;
    public static final int SHOW_CHARGE_STATE = 1 << 10;

    public static final int SHOW_GPU = SHOW_GPU_USAGE;
    public static final int SHOW_CPU = SHOW_CPU_USAGE;
    public static final int SHOW_BATT = SHOW_POWER;

    private static final int LEGACY_SHOW_DEFAULT = 0x6F;
    private static final int SHOW_DEFAULT = SHOW_FPS | SHOW_GPU_USAGE | SHOW_CPU_USAGE
            | SHOW_POWER | SHOW_RENDERER | SHOW_RAM | SHOW_GPU_NAME | SHOW_CPU_TEMP
            | SHOW_BATTERY_TEMP | SHOW_CHARGE_STATE;

    private static final int C_BG = Color.argb(180, 0, 0, 0);
    private static final int C_WHITE = Color.WHITE;
    private static final int C_GPU_NAME = Color.rgb(0xA9, 0xD6, 0xFF);
    private static final int C_GPU_USAGE = Color.rgb(0xE0, 0x40, 0xFB);
    private static final int C_CPU = Color.rgb(0x00, 0xE5, 0xFF);
    private static final int C_BATT = Color.rgb(0xFF, 0x80, 0x00);
    private static final int C_CHG = Color.rgb(0x40, 0xC4, 0x40);
    private static final int C_TEMP = Color.rgb(0xEF, 0x53, 0x50);
    private static final int C_FPS = Color.rgb(0x76, 0xFF, 0x03);
    private static final int C_REND = Color.rgb(0xFF, 0xEA, 0x00);
    private static final int C_RAM = Color.rgb(0xB0, 0xFF, 0xB0);
    private static final int C_SEP = Color.rgb(0x60, 0x60, 0x60);

    private static final int TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG
            | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG;
    private static final long STATS_INTERVAL_MS = 1500L;
    private static final long BATT_REGISTER_INTERVAL_NS = 5_000_000_000L;
    private static final float DRAG_THRESH = 10f;

    private static final String[] GPU_STATIC_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/gpuinfo",
            "/sys/devices/platform/mali/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/devices/platform/gpusysfs/gpu_busy",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/pvr/utilisation",
            "/sys/class/pvr/gpu_utilisation",
            "/sys/class/drm/card0/device/gpu_busy_percent",
            "/sys/class/devfreq/gpu/load",
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/module/ged/parameters/gpu_loading",
            "/proc/mtk_mali/utilization"
    };

    private static final String[] GPU_USAGE_FILES = {
            "gpu_busy_percentage", "gpu_busy_percent", "gpu_load", "utilisation",
            "utilization", "load", "gpu_busy", "gpuinfo"
    };

    private static final String[] GPU_NODE_TOKENS = {
            "gpu", "mali", "g3d", "kgsl", "panfrost", "pvr", "powervr", "xclipse", "sgpu"
    };

    private static final String[] CURRENT_CHANNELS = {
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/main/current_now"
    };

    private static final String[] VOLTAGE_CHANNELS = {
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
            "/sys/class/power_supply/main/voltage_now"
    };

    private static final String[] POWER_CHANNELS = {
            "/sys/class/power_supply/battery/power_now",
            "/sys/class/power_supply/bms/power_now",
            "/sys/class/power_supply/main/power_now"
    };

    private float TS, TSR, PAD, CORNER;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal = new Paint(TEXT_FLAGS);
    private final Paint pGpuName = new Paint(TEXT_FLAGS);
    private final Paint pGpuUsage = new Paint(TEXT_FLAGS);
    private final Paint pCpu = new Paint(TEXT_FLAGS);
    private final Paint pBat = new Paint(TEXT_FLAGS);
    private final Paint pTmp = new Paint(TEXT_FLAGS);
    private final Paint pFps = new Paint(TEXT_FLAGS);
    private final Paint pRend = new Paint(TEXT_FLAGS);
    private final Paint pRam = new Paint(TEXT_FLAGS);
    private final Paint pSep = new Paint(TEXT_FLAGS);
    private final Paint pChg = new Paint(TEXT_FLAGS);
    private final RectF bgRect = new RectF();

    private float wLabelGpu, wLabelCpu, wLabelRam, wLabelPwr, wLabelTmp, wLabelFps, wSep;
    private float wVal100pct, wValCpuTemp, wValFps, wValWatt, wValTemp, wChg, wChgStandalone;
    private float wInnerSpace;

    private volatile String strGpu = "N/A", strCpuUsage = "N/A", strCpuTemp = "";
    private volatile String strRam = "N/A", strPwr = "N/A", strTmp = "", strFps = "0";
    private volatile String strRend = "Vulkan", gpuNameLabel = "";

    private volatile float wDynGpu, wDynCpuUsage, wDynCpuTemp, wDynRam, wDynPwr;
    private volatile float wDynTmp, wDynFps, wDynRend, wDynGpuName;

    private int lastBgAlpha = -1;
    private volatile int showMask = SHOW_DEFAULT;
    private float hudAlpha = 1f;
    private volatile boolean userEnabled = false;
    private volatile boolean rendererActive = false;
    private boolean vertical = false;

    private final SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (!KEY_SHOW.equals(key)) return;
                final int newMask = sharedPreferences.getInt(KEY_SHOW, SHOW_DEFAULT);
                uiHandler.post(() -> {
                    if (showMask == newMask) return;
                    showMask = newMask;
                    requestRelayout();
                });
            };

    private final AtomicInteger frameAccum = new AtomicInteger(0);
    private long lastFpsNs = 0;
    private float snapFps = 0;

    private int snapGpu = -1, snapCpu = -1, snapCpuTemp = -1, snapMw = -1;
    private int snapTmp = -1, snapPct = -1, snapRam = -1;
    private volatile boolean snapCharging = false;

    private volatile String rendererLabel = "Vulkan";
    private boolean isNative = false;
    private boolean mesaRendererActive = false;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private long touchDownMs = 0;
    private boolean redrawScheduled = false;

    private HandlerThread statsThread = null;
    private Handler statsHandler = null;
    private final Runnable statsRunnable = this::doStats;

    private final BatteryManager batteryManager;
    private final IntentFilter batteryIntentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    private Intent cachedBatteryIntent = null;
    private long lastBatteryRegisterNs = 0;

    private String[] gpuPaths = new String[0];
    private boolean gpuUnavailable;
    private int gpuConsecutiveFailures;
    private Long lastMaliGpuInfoMs;
    private long lastMaliGpuInfoWallMs;
    private boolean battFailed = false;

    private final Runnable redrawRunnable = () -> {
        redrawScheduled = false;
        try {
            snapshot();
            invalidate();
        } catch (Exception ignored) {}
        if (getVisibility() == VISIBLE) scheduleRedraw();
    };

    public WinlatorHUD(Context context) {
        this(context, null);
    }

    public WinlatorHUD(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);

        float density = context.getResources().getDisplayMetrics().density;
        TS = 12f * density;
        TSR = 11f * density;
        PAD = 6f * density;
        CORNER = 5f * density;

        initPaints(density);
        detectGpuPathsOnce();
        detectGpuNameOnce();
        loadPrefs();
        refreshBackendRenderer(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private void initPaints(float density) {
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        pBg.setStyle(Paint.Style.FILL);

        pVal.setTextSize(TS); pVal.setTypeface(mono); pVal.setColor(C_WHITE);
        pGpuName.setTextSize(TS); pGpuName.setTypeface(mono); pGpuName.setColor(C_GPU_NAME);
        pGpuUsage.setTextSize(TS); pGpuUsage.setTypeface(mono); pGpuUsage.setColor(C_GPU_USAGE);
        pCpu.setTextSize(TS); pCpu.setTypeface(mono); pCpu.setColor(C_CPU);
        pBat.setTextSize(TS); pBat.setTypeface(mono); pBat.setColor(C_BATT);
        pTmp.setTextSize(TS); pTmp.setTypeface(mono); pTmp.setColor(C_TEMP);
        pFps.setTextSize(TS); pFps.setTypeface(mono); pFps.setColor(C_FPS);
        pRend.setTextSize(TSR); pRend.setTypeface(mono); pRend.setColor(C_REND);
        pRam.setTextSize(TS); pRam.setTypeface(mono); pRam.setColor(C_RAM);
        pSep.setTextSize(TS); pSep.setTypeface(mono); pSep.setColor(C_SEP);
        pChg.setTextSize(TS); pChg.setTypeface(mono); pChg.setColor(C_CHG);

        wLabelGpu = pGpuUsage.measureText("GPU ");
        wLabelCpu = pCpu.measureText("CPU ");
        wLabelRam = pRam.measureText("RAM ");
        wLabelPwr = pBat.measureText("PWR ");
        wLabelTmp = pTmp.measureText("BAT ");
        wLabelFps = pFps.measureText("FPS ");
        wSep = pSep.measureText(" | ");
        wInnerSpace = pVal.measureText(" ");

        wVal100pct = pVal.measureText("100%");
        wValCpuTemp = pVal.measureText("150°C");
        wValFps = pVal.measureText("9999") + 2f * density;
        wValWatt = pVal.measureText("99.9W");
        wValTemp = pVal.measureText("150°C");
        wChg = pChg.measureText(" CHG");
        wChgStandalone = pChg.measureText("CHG");

        wDynGpu = pVal.measureText(strGpu);
        wDynCpuUsage = pVal.measureText(strCpuUsage);
        wDynCpuTemp = pVal.measureText(strCpuTemp);
        wDynRam = pVal.measureText(strRam);
        wDynPwr = pVal.measureText(strPwr);
        wDynTmp = pVal.measureText(strTmp);
        wDynFps = pVal.measureText(strFps);
        wDynRend = pRend.measureText(strRend);
        wDynGpuName = 0f;
    }

    private void detectGpuNameOnce() {
        try {
            String name = sanitizeGpuName(GPUInformation.getRenderer(null, getContext()));
            if (isUsefulGpuName(name)) {
                gpuNameLabel = name;
                wDynGpuName = pGpuName.measureText(gpuNameLabel);
            }
        } catch (Throwable ignored) {}
    }

    private String sanitizeGpuName(String name) {
        if (name == null) return "";
        return name.trim().replaceFirst("(?i)^wrapper\\s*[:\\-]?\\s*", "").trim();
    }

    private boolean isUsefulGpuName(String name) {
        if (name == null) return false;
        String value = name.trim();
        if (value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.US);
        return !"unknown".equals(lower) && !"device".equals(lower) && !"n/a".equals(lower);
    }

    private String resolveBackendRenderer() {
        try {
            Context context = getContext();
            if (context instanceof XServerDisplayActivity) {
                XServerDisplayActivity activity = (XServerDisplayActivity) context;
                XServerRendererView view = activity.getXServerView();

                if (view instanceof XServerView
                        && activity.getXServer() != null
                        && activity.getXServer().isDisplayX()) {
                    boolean bypass = isNative;
                    Container container = activity.getContainer();
                    if (container != null) {
                        bypass |= "1".equals(container.getExtra("trueDisplayX", "0"));
                    }

                    String shortcutPath = activity.getIntent() != null
                            ? activity.getIntent().getStringExtra("shortcut_path") : null;
                    String shortcutBypass = readShortcutExtra(shortcutPath, "trueDisplayX");
                    if (shortcutBypass != null) bypass = "1".equals(shortcutBypass);
                    return bypass ? "DisplayX+" : "DisplayX";
                }

                if (view instanceof XServerView) return "EGL";
                if (view instanceof VulkanXServerView) return "Vulkan";
            }
        } catch (Throwable ignored) {}
        return "Vulkan";
    }

    private String readShortcutExtra(String path, String key) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        if (!file.isFile()) return null;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            boolean inExtraData = false;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    inExtraData = "[Extra Data]".equals(line);
                    continue;
                }
                if (!inExtraData) continue;
                String prefix = key + "=";
                if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
            }
        } catch (Exception ignored) {}
        return null;
    }
    private void refreshBackendRenderer(boolean relayout) {
        if (mesaRendererActive) return;
        rendererLabel = resolveBackendRenderer();
        strRend = rendererLabel;
        wDynRend = pRend.measureText(strRend);
        if (relayout) requestRelayout();
    }

    private void detectGpuPathsOnce() {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String path : GPU_STATIC_PATHS) {
            try {
                if (new File(path).canRead()) found.add(path);
            } catch (Exception ignored) {}
        }

        scanGpuNodesOnce(new File("/sys/class/devfreq"), found);
        scanGpuNodesOnce(new File("/sys/devices/virtual/devfreq"), found);
        scanPlatformGpuNodesOnce(new File("/sys/devices/platform"), found);

        gpuPaths = found.toArray(new String[0]);
        gpuUnavailable = gpuPaths.length == 0;
    }

    private void scanGpuNodesOnce(File root, LinkedHashSet<String> found) {
        try {
            if (!root.isDirectory()) return;
            File[] nodes = root.listFiles(File::isDirectory);
            if (nodes == null) return;
            for (File node : nodes) {
                if (!looksLikeGpuNode(node.getPath())) continue;
                addGpuUsageFiles(node, found);
            }
        } catch (Exception ignored) {}
    }

    private void scanPlatformGpuNodesOnce(File root, LinkedHashSet<String> found) {
        try {
            if (!root.isDirectory()) return;
            File[] nodes = root.listFiles(File::isDirectory);
            if (nodes == null) return;
            for (File node : nodes) {
                if (!looksLikeGpuNode(node.getName())) continue;
                addGpuUsageFiles(node, found);
            }
        } catch (Exception ignored) {}
    }

    private void addGpuUsageFiles(File node, LinkedHashSet<String> found) {
        for (String fileName : GPU_USAGE_FILES) {
            File candidate = new File(node, fileName);
            if (candidate.canRead()) found.add(candidate.getPath());
        }
    }

    private boolean looksLikeGpuNode(String path) {
        String lower = path.toLowerCase(Locale.US);
        for (String token : GPU_NODE_TOKENS) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    public void onFrame() {
        if (!rendererActive && !userEnabled) return;
        frameAccum.incrementAndGet();
    }

    public void update() {
        onFrame();
    }

    public void setIsNative(boolean n) {
        isNative = n;
        if (!mesaRendererActive) uiHandler.post(() -> refreshBackendRenderer(true));
    }

    private void doStats() {
        try {
            readStats();
        } catch (Exception ignored) {}

        if (userEnabled && statsHandler != null) {
            statsHandler.postDelayed(statsRunnable, STATS_INTERVAL_MS);
        }
        uiHandler.post(this::invalidate);
    }

    private void readStats() {
        int mask = showMask;
        if ((mask & SHOW_GPU_USAGE) != 0) readGpu();
        if ((mask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) readCpu();
        if ((mask & SHOW_RAM) != 0) readRam();
        if ((mask & (SHOW_POWER | SHOW_BATTERY_TEMP | SHOW_CHARGE_STATE)) != 0) readBattery();
    }

    private void readGpu() {
        if (gpuUnavailable) return;

        int value = -1;
        for (String path : gpuPaths) {
            value = readGpuSample(path);
            if (value >= 0) break;
        }

        if (value >= 0) {
            gpuConsecutiveFailures = 0;
            if (value != snapGpu) {
                snapGpu = value;
                strGpu = value + "%";
                wDynGpu = pVal.measureText(strGpu);
            }
            return;
        }

        if (++gpuConsecutiveFailures >= 3) {
            gpuUnavailable = true;
            if (snapGpu < 0) {
                strGpu = "N/A";
                wDynGpu = pVal.measureText(strGpu);
            }
        }
    }

    private int readGpuSample(String path) {
        try {
            if (path.endsWith("/gpubusy")) {
                String line = readFirstLine(path);
                if (line == null) return -1;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) return -1;
                long busy = Long.parseLong(parts[0]);
                long total = Long.parseLong(parts[1]);
                return total > 0L ? clampPercent((int) ((busy * 100L) / total)) : -1;
            }

            if (path.endsWith("/gpuinfo")) {
                String line = readFirstLine(path);
                if (line == null) return -1;
                String[] parts = line.trim().split("\\s+");
                long gpuMs = Long.parseLong(parts[parts.length - 1]);
                long nowMs = SystemClock.elapsedRealtime();
                Long oldGpuMs = lastMaliGpuInfoMs;
                long oldWallMs = lastMaliGpuInfoWallMs;
                lastMaliGpuInfoMs = gpuMs;
                lastMaliGpuInfoWallMs = nowMs;
                if (oldGpuMs == null || oldWallMs <= 0L) return -1;
                long wallDelta = nowMs - oldWallMs;
                long gpuDelta = Math.max(0L, gpuMs - oldGpuMs);
                return wallDelta > 0L
                        ? clampPercent((int) ((gpuDelta * 100L) / wallDelta)) : -1;
            }

            if (path.endsWith("/proc/mtk_mali/utilization")) {
                try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int idx = line.indexOf("ACTIVE=");
                        if (idx < 0) continue;
                        int start = idx + 7;
                        int end = line.indexOf(' ', start);
                        String token = end > start
                                ? line.substring(start, end) : line.substring(start);
                        token = token.replaceAll("[^0-9]", "");
                        if (!token.isEmpty()) {
                            return clampPercent(Integer.parseInt(token));
                        }
                    }
                }
                return -1;
            }

            return readPercent(path);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private int readPercent(String path) {
        String line = readFirstLine(path);
        if (line == null) return -1;
        for (String token : line.trim().split("\\s+")) {
            String digits = token.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return clampPercent(Integer.parseInt(digits));
                } catch (Exception ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private String readFirstLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private void readCpu() {
        int usage = CPUStatus.getCpuUsagePercent();
        int temp = CPUStatus.getCpuTempC();
        if (usage == snapCpu && temp == snapCpuTemp) return;

        snapCpu = usage;
        snapCpuTemp = temp;
        strCpuUsage = usage >= 0 ? usage + "%" : "N/A";
        strCpuTemp = temp > 0 ? temp + "°C" : "N/A";
        wDynCpuUsage = pVal.measureText(strCpuUsage);
        wDynCpuTemp = pVal.measureText(strCpuTemp);
    }

    private void readRam() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = -1;
            long avail = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = parseMeminfoKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    avail = parseMeminfoKb(line);
                    break;
                }
            }

            int value = (total > 0 && avail >= 0)
                    ? (int) (100L * (total - avail) / total) : -1;
            if (value != snapRam) {
                snapRam = value;
                strRam = value >= 0 ? value + "%" : "N/A";
                wDynRam = pVal.measureText(strRam);
            }
        } catch (Exception e) {
            if (snapRam != -1) {
                snapRam = -1;
                strRam = "N/A";
                wDynRam = pVal.measureText(strRam);
            }
        }
    }

    private long parseMeminfoKb(String line) {
        try {
            return Long.parseLong(line.trim().split("\\s+")[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private void readBattery() {
        if (battFailed) return;

        try {
            long now = System.nanoTime();
            if (cachedBatteryIntent == null
                    || now - lastBatteryRegisterNs >= BATT_REGISTER_INTERVAL_NS) {
                cachedBatteryIntent = getContext().registerReceiver(null, batteryIntentFilter);
                lastBatteryRegisterNs = now;
            }

            Intent batt = cachedBatteryIntent;
            if (batt == null) return;

            int rawTemp = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int tempC = rawTemp > 0 ? Math.round(rawTemp / 10f) : -1;
            if (tempC != snapTmp) {
                snapTmp = tempC;
                strTmp = tempC > 0 ? tempC + "°C" : "N/A";
                wDynTmp = pVal.measureText(strTmp);
            }

            snapPct = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;

            int voltageMv = batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            if (voltageMv <= 0) {
                long voltageUv = readFirstSysFsLong(VOLTAGE_CHANNELS);
                if (voltageUv != 0L) {
                    voltageMv = (int) (Math.abs(voltageUv) / 1000L);
                }
            }

            float amps = getBatteryCurrentAmps();
            float watts = (amps > 0f && voltageMv > 0)
                    ? (voltageMv / 1000f) * amps : -1f;

            if (watts <= 0f) {
                long powerUw = readFirstSysFsLong(POWER_CHANNELS);
                if (powerUw != 0L) watts = Math.abs(powerUw) / 1_000_000f;
            }

            if (watts > 0f && prefs.getBoolean(KEY_DUAL_CELL, false)) {
                watts *= 2f;
            }

            int mw = watts > 0f ? Math.round(watts * 1000f) : -1;
            boolean chargingChanged = charging != snapCharging;
            snapCharging = charging;

            if (mw != snapMw || chargingChanged) {
                snapMw = mw;
                strPwr = mw > 0
                        ? String.format(Locale.US, "%.1fW", mw / 1000f) : "N/A";
                wDynPwr = pVal.measureText(strPwr);
            }
        } catch (Exception e) {
            battFailed = true;
        }
    }

    private float getBatteryCurrentAmps() {
        long raw = 0L;
        if (batteryManager != null) {
            raw = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        }
        if (raw == 0L || raw == Long.MIN_VALUE) raw = readFirstSysFsLong(CURRENT_CHANNELS);
        if (raw == 0L || raw == Long.MIN_VALUE) return -1f;

        long magnitude = Math.abs(raw);
        return magnitude < 20000L ? magnitude / 1000f : magnitude / 1_000_000f;
    }

    private long readFirstSysFsLong(String[] paths) {
        for (String path : paths) {
            long value = readSysFsLong(path);
            if (value != 0L && value != Long.MIN_VALUE) return value;
        }
        return 0L;
    }

    private long readSysFsLong(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line != null ? Long.parseLong(line.trim()) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private void snapshot() {
        long now = System.nanoTime();
        if (lastFpsNs == 0) lastFpsNs = now;
        long dt = now - lastFpsNs;
        if (dt < 350_000_000L) return;

        int frames = frameAccum.getAndSet(0);
        snapFps = frames * 1_000_000_000f / dt;
        lastFpsNs = now;

        String value = String.format(Locale.US, "%.0f", snapFps);
        if (!value.equals(strFps)) {
            strFps = value;
            wDynFps = pVal.measureText(strFps);
            if (wDynFps > wValFps) requestRelayout();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE) return;

        try {
            int targetAlpha = (int) (180 * hudAlpha);
            if (targetAlpha != lastBgAlpha) {
                pBg.setAlpha(targetAlpha);
                lastBgAlpha = targetAlpha;
            }

            if (vertical) drawVertical(canvas);
            else drawHorizontal(canvas);
        } catch (Exception ignored) {}
    }

    private void drawHorizontal(Canvas canvas) {
        float x = PAD;
        float rowH = TS + PAD * 2;
        float baseline = PAD + TS;
        float contentWidth = Math.min(getWidth(), measureHorizontalContent());

        bgRect.set(0, 0, contentWidth, rowH);
        canvas.drawRoundRect(bgRect, CORNER, CORNER, pBg);

        boolean hasGroup = false;

        if ((showMask & SHOW_RENDERER) != 0) {
            canvas.drawText(strRend, x, baseline, pRend);
            x += wDynRend;
            hasGroup = true;
        }

        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText(gpuNameLabel, x, baseline, pGpuName);
            x += wDynGpuName;
            hasGroup = true;
        }

        if ((showMask & SHOW_GPU_USAGE) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("GPU ", x, baseline, pGpuUsage);
            x += wLabelGpu;
            canvas.drawText(strGpu, x, baseline, pVal);
            x += wDynGpu;
            hasGroup = true;
        }

        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("CPU ", x, baseline, pCpu);
            x += wLabelCpu;

            if ((showMask & SHOW_CPU_USAGE) != 0) {
                canvas.drawText(strCpuUsage, x, baseline, pVal);
                x += wDynCpuUsage;
            }

            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) x += wInnerSpace;
                canvas.drawText(strCpuTemp, x, baseline, pVal);
                x += wDynCpuTemp;
            }
            hasGroup = true;
        }

        if ((showMask & SHOW_RAM) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("RAM ", x, baseline, pRam);
            x += wLabelRam;
            canvas.drawText(strRam, x, baseline, pVal);
            x += wDynRam;
            hasGroup = true;
        }

        if ((showMask & SHOW_POWER) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("PWR ", x, baseline, pBat);
            x += wLabelPwr;
            canvas.drawText(strPwr, x, baseline, pVal);
            x += wDynPwr;

            if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
                canvas.drawText(" CHG", x, baseline, pChg);
                x += wChg;
            }
            hasGroup = true;
        } else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("CHG", x, baseline, pChg);
            x += wChgStandalone;
            hasGroup = true;
        }

        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("BAT ", x, baseline, pTmp);
            x += wLabelTmp;
            canvas.drawText(strTmp, x, baseline, pVal);
            x += wDynTmp;
            hasGroup = true;
        }

        if ((showMask & SHOW_FPS) != 0) {
            if (hasGroup) x += drawSep(canvas, x, baseline);
            canvas.drawText("FPS ", x, baseline, pFps);
            x += wLabelFps;
            canvas.drawText(strFps, x, baseline, pVal);
        }
    }

    private void drawVertical(Canvas canvas) {
        float lineH = TS + PAD * 2;
        float rows = countVerticalRows();
        float width = Math.min(getWidth(), measureVerticalContent());
        float height = rows * lineH + PAD;

        bgRect.set(0, 0, width, height);
        canvas.drawRoundRect(bgRect, CORNER, CORNER, pBg);

        float y = PAD;

        if ((showMask & SHOW_RENDERER) != 0) {
            canvas.drawText(strRend, PAD, y + TS, pRend);
            y += lineH;
        }

        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            canvas.drawText(gpuNameLabel, PAD, y + TS, pGpuName);
            y += lineH;
        }

        if ((showMask & SHOW_GPU_USAGE) != 0) {
            canvas.drawText("GPU ", PAD, y + TS, pGpuUsage);
            canvas.drawText(strGpu, PAD + wLabelGpu, y + TS, pVal);
            y += lineH;
        }

        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            float x = PAD;
            canvas.drawText("CPU ", x, y + TS, pCpu);
            x += wLabelCpu;

            if ((showMask & SHOW_CPU_USAGE) != 0) {
                canvas.drawText(strCpuUsage, x, y + TS, pVal);
                x += wDynCpuUsage;
            }

            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) x += wInnerSpace;
                canvas.drawText(strCpuTemp, x, y + TS, pVal);
            }
            y += lineH;
        }

        if ((showMask & SHOW_RAM) != 0) {
            canvas.drawText("RAM ", PAD, y + TS, pRam);
            canvas.drawText(strRam, PAD + wLabelRam, y + TS, pVal);
            y += lineH;
        }

        if ((showMask & SHOW_POWER) != 0) {
            canvas.drawText("PWR ", PAD, y + TS, pBat);
            float x = PAD + wLabelPwr;
            canvas.drawText(strPwr, x, y + TS, pVal);
            x += wDynPwr;
            if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
                canvas.drawText(" CHG", x, y + TS, pChg);
            }
            y += lineH;
        } else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
            canvas.drawText("CHG", PAD, y + TS, pChg);
            y += lineH;
        }

        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            canvas.drawText("BAT ", PAD, y + TS, pTmp);
            canvas.drawText(strTmp, PAD + wLabelTmp, y + TS, pVal);
            y += lineH;
        }

        if ((showMask & SHOW_FPS) != 0) {
            canvas.drawText("FPS ", PAD, y + TS, pFps);
            canvas.drawText(strFps, PAD + wLabelFps, y + TS, pVal);
        }
    }

    private float drawSep(Canvas canvas, float x, float baseline) {
        canvas.drawText(" | ", x, baseline, pSep);
        return wSep;
    }

    private float measureHorizontalContent() {
        float width = PAD * 2;
        int groups = 0;

        if ((showMask & SHOW_RENDERER) != 0) {
            width += wDynRend;
            groups++;
        }
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            width += wDynGpuName;
            groups++;
        }
        if ((showMask & SHOW_GPU_USAGE) != 0) {
            width += wLabelGpu + wDynGpu;
            groups++;
        }
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            width += wLabelCpu;
            if ((showMask & SHOW_CPU_USAGE) != 0) width += wDynCpuUsage;
            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) width += wInnerSpace;
                width += wDynCpuTemp;
            }
            groups++;
        }
        if ((showMask & SHOW_RAM) != 0) {
            width += wLabelRam + wDynRam;
            groups++;
        }
        if ((showMask & SHOW_POWER) != 0) {
            width += wLabelPwr + wDynPwr;
            if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) width += wChg;
            groups++;
        } else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
            width += wChgStandalone;
            groups++;
        }
        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            width += wLabelTmp + wDynTmp;
            groups++;
        }
        if ((showMask & SHOW_FPS) != 0) {
            width += wLabelFps + wDynFps;
            groups++;
        }

        if (groups > 1) width += (groups - 1) * wSep;
        return width;
    }

    private float measureHorizontalReserved() {
        float width = PAD * 2;
        int groups = 0;

        if ((showMask & SHOW_RENDERER) != 0) {
            width += wDynRend;
            groups++;
        }
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            width += wDynGpuName;
            groups++;
        }
        if ((showMask & SHOW_GPU_USAGE) != 0) {
            width += wLabelGpu + Math.max(wVal100pct, wDynGpu);
            groups++;
        }
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            width += wLabelCpu;
            if ((showMask & SHOW_CPU_USAGE) != 0) {
                width += Math.max(wVal100pct, wDynCpuUsage);
            }
            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) width += wInnerSpace;
                width += Math.max(wValCpuTemp, wDynCpuTemp);
            }
            groups++;
        }
        if ((showMask & SHOW_RAM) != 0) {
            width += wLabelRam + Math.max(wVal100pct, wDynRam);
            groups++;
        }
        if ((showMask & SHOW_POWER) != 0) {
            width += wLabelPwr + Math.max(wValWatt, wDynPwr);
            if ((showMask & SHOW_CHARGE_STATE) != 0) width += wChg;
            groups++;
        } else if ((showMask & SHOW_CHARGE_STATE) != 0) {
            width += wChgStandalone;
            groups++;
        }
        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            width += wLabelTmp + Math.max(wValTemp, wDynTmp);
            groups++;
        }
        if ((showMask & SHOW_FPS) != 0) {
            width += wLabelFps + Math.max(wValFps, wDynFps);
            groups++;
        }

        if (groups > 1) width += (groups - 1) * wSep;
        return width;
    }

    private float measureVerticalContent() {
        float width = PAD * 2;

        if ((showMask & SHOW_RENDERER) != 0) {
            width = Math.max(width, PAD * 2 + wDynRend);
        }
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            width = Math.max(width, PAD * 2 + wDynGpuName);
        }
        if ((showMask & SHOW_GPU_USAGE) != 0) {
            width = Math.max(width, PAD * 2 + wLabelGpu + wDynGpu);
        }
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            float row = wLabelCpu;
            if ((showMask & SHOW_CPU_USAGE) != 0) row += wDynCpuUsage;
            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) row += wInnerSpace;
                row += wDynCpuTemp;
            }
            width = Math.max(width, PAD * 2 + row);
        }
        if ((showMask & SHOW_RAM) != 0) {
            width = Math.max(width, PAD * 2 + wLabelRam + wDynRam);
        }
        if ((showMask & SHOW_POWER) != 0) {
            float row = wLabelPwr + wDynPwr;
            if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) row += wChg;
            width = Math.max(width, PAD * 2 + row);
        } else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) {
            width = Math.max(width, PAD * 2 + wChgStandalone);
        }
        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            width = Math.max(width, PAD * 2 + wLabelTmp + wDynTmp);
        }
        if ((showMask & SHOW_FPS) != 0) {
            width = Math.max(width, PAD * 2 + wLabelFps + wDynFps);
        }
        return width;
    }

    private float measureVerticalReserved() {
        float width = PAD * 2;

        if ((showMask & SHOW_RENDERER) != 0) {
            width = Math.max(width, PAD * 2 + wDynRend);
        }
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) {
            width = Math.max(width, PAD * 2 + wDynGpuName);
        }
        if ((showMask & SHOW_GPU_USAGE) != 0) {
            width = Math.max(width,
                    PAD * 2 + wLabelGpu + Math.max(wVal100pct, wDynGpu));
        }
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) {
            float row = wLabelCpu;
            if ((showMask & SHOW_CPU_USAGE) != 0) {
                row += Math.max(wVal100pct, wDynCpuUsage);
            }
            if ((showMask & SHOW_CPU_TEMP) != 0) {
                if ((showMask & SHOW_CPU_USAGE) != 0) row += wInnerSpace;
                row += Math.max(wValCpuTemp, wDynCpuTemp);
            }
            width = Math.max(width, PAD * 2 + row);
        }
        if ((showMask & SHOW_RAM) != 0) {
            width = Math.max(width,
                    PAD * 2 + wLabelRam + Math.max(wVal100pct, wDynRam));
        }
        if ((showMask & SHOW_POWER) != 0) {
            float row = wLabelPwr + Math.max(wValWatt, wDynPwr);
            if ((showMask & SHOW_CHARGE_STATE) != 0) row += wChg;
            width = Math.max(width, PAD * 2 + row);
        } else if ((showMask & SHOW_CHARGE_STATE) != 0) {
            width = Math.max(width, PAD * 2 + wChgStandalone);
        }
        if ((showMask & SHOW_BATTERY_TEMP) != 0) {
            width = Math.max(width,
                    PAD * 2 + wLabelTmp + Math.max(wValTemp, wDynTmp));
        }
        if ((showMask & SHOW_FPS) != 0) {
            width = Math.max(width,
                    PAD * 2 + wLabelFps + Math.max(wValFps, wDynFps));
        }
        return width;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float lineH = TS + PAD * 2;
        float width = vertical ? measureVerticalReserved() : measureHorizontalReserved();
        float height = vertical ? (countVerticalRows() * lineH + PAD) : lineH;
        setMeasuredDimension((int) Math.ceil(width), (int) Math.ceil(height));
    }

    private float countVerticalRows() {
        float rows = 0;
        if ((showMask & SHOW_RENDERER) != 0) rows++;
        if ((showMask & SHOW_GPU_NAME) != 0 && !gpuNameLabel.isEmpty()) rows++;
        if ((showMask & SHOW_GPU_USAGE) != 0) rows++;
        if ((showMask & (SHOW_CPU_USAGE | SHOW_CPU_TEMP)) != 0) rows++;
        if ((showMask & SHOW_RAM) != 0) rows++;
        if ((showMask & SHOW_POWER) != 0) rows++;
        else if ((showMask & SHOW_CHARGE_STATE) != 0 && snapCharging) rows++;
        if ((showMask & SHOW_BATTERY_TEMP) != 0) rows++;
        if ((showMask & SHOW_FPS) != 0) rows++;
        return Math.max(1, rows);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getPointerCount() > 1) return true;
                touchX = event.getRawX();
                touchY = event.getRawY();
                startX = getX();
                startY = getY();
                dragging = false;
                touchDownMs = System.currentTimeMillis();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - touchX;
                float dy = event.getRawY() - touchY;
                if (!dragging && Math.hypot(dx, dy) > DRAG_THRESH) dragging = true;
                if (dragging) {
                    setX(startX + dx);
                    setY(startY + dy);
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                touchDownMs = 0;
                return true;

            case MotionEvent.ACTION_UP:
                if (event.getPointerCount() > 1) {
                    dragging = false;
                    return true;
                }

                if (dragging) {
                    savePosition();
                } else if (touchDownMs > 0
                        && System.currentTimeMillis() - touchDownMs < 300) {
                    vertical = !vertical;
                    prefs.edit().putBoolean(KEY_VERT, vertical).apply();
                    requestRelayout();
                    uiHandler.postDelayed(this::ensureVisible, 250);
                }
                dragging = false;
                return true;

            default:
                return false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        if (userEnabled) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            setVisibility(VISIBLE);
            scheduleRedraw();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        uiHandler.removeCallbacks(redrawRunnable);
        stopStatsThread();
        redrawScheduled = false;
    }

    private void startStatsThread() {
        if (statsThread == null) {
            statsThread = new HandlerThread("WinlatorHUD-stats", Process.THREAD_PRIORITY_BACKGROUND);
            statsThread.start();
            statsHandler = new Handler(statsThread.getLooper());
        }
        statsHandler.removeCallbacks(statsRunnable);
        statsHandler.post(statsRunnable);
    }

    private void stopStatsThread() {
        if (statsHandler != null) statsHandler.removeCallbacks(statsRunnable);
        if (statsThread != null) {
            statsThread.quitSafely();
            statsThread = null;
            statsHandler = null;
        }
    }

    private void ensureVisible() {
        if (userEnabled) {
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
            uiHandler.postDelayed(redrawRunnable, 400);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && userEnabled) {
            scheduleRedraw();
        } else {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
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
        int mask;
        if (!prefs.getBoolean(KEY_SHOW_V2, false)) {
            if (prefs.contains(KEY_SHOW)) {
                int legacy = prefs.getInt(KEY_SHOW, LEGACY_SHOW_DEFAULT);
                mask = legacy;
                if ((legacy & SHOW_GPU_USAGE) != 0) mask |= SHOW_GPU_NAME;
                if ((legacy & SHOW_CPU_USAGE) != 0) mask |= SHOW_CPU_TEMP;
                if ((legacy & SHOW_POWER) != 0) {
                    mask |= SHOW_BATTERY_TEMP | SHOW_CHARGE_STATE;
                }
            } else {
                mask = SHOW_DEFAULT;
            }

            prefs.edit()
                    .putInt(KEY_SHOW, mask)
                    .putBoolean(KEY_SHOW_V2, true)
                    .apply();
        } else {
            mask = prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
        }

        showMask = mask;
        hudAlpha = prefs.getInt(KEY_ALPHA, 100) / 100f;
        vertical = prefs.getBoolean(KEY_VERT, false);
        float scale = prefs.getFloat(KEY_SCALE, 1f);
        setScaleX(scale);
        setScaleY(scale);
        setX(prefs.getFloat(KEY_X, 16f));
        setY(prefs.getFloat(KEY_Y, 16f));
        userEnabled = false;
        setVisibility(GONE);
    }

    public static boolean isOptionEnabled(Context context, int bit) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return (prefs.getInt(KEY_SHOW, SHOW_DEFAULT) & bit) != 0;
    }

    public static void setOptionPreference(Context context, int bit, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int mask = prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
        mask = enabled ? (mask | bit) : (mask & ~bit);
        prefs.edit().putInt(KEY_SHOW, mask).apply();
    }

    public boolean hasSavedPref() {
        return prefs.contains(KEY_VIS);
    }

    public boolean isSavedVisible() {
        return prefs.getBoolean(KEY_VIS, false);
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void enableByUser() {
        userEnabled = true;
        rendererActive = true;
        prefs.edit().putBoolean(KEY_VIS, true).apply();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        if (!mesaRendererActive) refreshBackendRenderer(true);
        startStatsThread();
        setVisibility(VISIBLE);
        scheduleRedraw();
    }

    public void disableByUser() {
        disableByUser(true);
    }

    public void disableByUser(boolean savePrefs) {
        userEnabled = false;
        stopStatsThread();
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
            lastFpsNs = 0;
            mesaRendererActive = false;
            refreshBackendRenderer(true);

            if (userEnabled) {
                setVisibility(VISIBLE);
                scheduleRedraw();
                startStatsThread();
            } else {
                setVisibility(GONE);
                stopStatsThread();
            }
        });
    }

    public void onRendererDetected(String name) {
        rendererActive = true;
        uiHandler.post(() -> {
            if (name != null && !name.trim().isEmpty()) {
                mesaRendererActive = true;
                rendererLabel = name.trim();
                strRend = rendererLabel;
                wDynRend = pRend.measureText(strRend);
                requestRelayout();
            } else {
                mesaRendererActive = false;
                refreshBackendRenderer(true);
            }

            if (userEnabled) {
                startStatsThread();
                setVisibility(VISIBLE);
                scheduleRedraw();
            }
        });
    }

    public void onRendererGone() {
        rendererActive = false;
        uiHandler.post(() -> {
            mesaRendererActive = false;
            refreshBackendRenderer(true);
        });

        uiHandler.postDelayed(() -> {
            if (rendererActive) return;
            frameAccum.set(0);
            snapFps = 0;
            lastFpsNs = 0;
            if (!"0".equals(strFps)) {
                strFps = "0";
                wDynFps = pVal.measureText("0");
            }

            if (userEnabled) {
                invalidate();
                return;
            }

            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            stopStatsThread();
            setVisibility(GONE);
        }, 400);
    }

    public void setRenderer(String name) {
        if (name == null || name.trim().isEmpty()) return;

        rendererActive = true;
        String clean = name.trim();
        uiHandler.post(() -> {
            mesaRendererActive = true;
            rendererLabel = clean;
            strRend = rendererLabel;
            wDynRend = pRend.measureText(strRend);
            requestRelayout();

            if (userEnabled) {
                startStatsThread();
                if (getVisibility() != VISIBLE) {
                    setVisibility(VISIBLE);
                    scheduleRedraw();
                }
            }
        });
    }

    public void setGpuName(String name) {
        String clean = sanitizeGpuName(name);
        if (!isUsefulGpuName(clean) || clean.equals(gpuNameLabel)) return;

        gpuNameLabel = clean;
        uiHandler.post(() -> {
            wDynGpuName = pGpuName.measureText(gpuNameLabel);
            requestRelayout();
        });
    }

    public void toggleElement(int idx, boolean on) {
        int bit = idxToMask(idx);
        if (bit == 0) return;
        if (on) showMask |= bit;
        else showMask &= ~bit;
        prefs.edit().putInt(KEY_SHOW, showMask).apply();
        requestRelayout();
    }

    public float getHudAlpha() {
        return hudAlpha;
    }

    public float getHudScale() {
        return getScaleX();
    }

    public void syncCheckboxes(android.widget.CheckBox cbFps,
            android.widget.CheckBox cbGpu,
            android.widget.CheckBox cbCpuRam,
            android.widget.CheckBox cbBattTemp,
            android.widget.CheckBox cbGraph,
            android.widget.CheckBox cbRenderer,
            android.widget.CheckBox cbRam) {
        if (cbFps != null) cbFps.setChecked((showMask & SHOW_FPS) != 0);
        if (cbGpu != null) cbGpu.setChecked((showMask & SHOW_GPU_USAGE) != 0);
        if (cbCpuRam != null) cbCpuRam.setChecked((showMask & SHOW_CPU_USAGE) != 0);
        if (cbBattTemp != null) cbBattTemp.setChecked((showMask & SHOW_POWER) != 0);
        if (cbRenderer != null) cbRenderer.setChecked((showMask & SHOW_RENDERER) != 0);
        if (cbRam != null) cbRam.setChecked((showMask & SHOW_RAM) != 0);
    }

    public void setDataSource(Object dataSource) {}

    public void setHudScale(float scale, boolean isFinalInput) {
        setScaleX(scale);
        setScaleY(scale);
        if (isFinalInput) prefs.edit().putFloat(KEY_SCALE, scale).apply();
    }

    public void setHudAlpha(float alpha, boolean isFinalInput) {
        hudAlpha = Math.max(0f, Math.min(1f, alpha));
        if (isFinalInput) prefs.edit().putInt(KEY_ALPHA, (int) (hudAlpha * 100)).apply();
        invalidate();
    }

    public void reset() {
        frameAccum.set(0);
        snapFps = 0;
        lastFpsNs = 0;
        mesaRendererActive = false;
        refreshBackendRenderer(true);
    }

    public void forceReset() {
        showMask = SHOW_DEFAULT;
        hudAlpha = 1.0f;
        vertical = false;
        setScaleX(1.0f);
        setScaleY(1.0f);
        setX(16f);
        setY(16f);
        uiHandler.post(() -> {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            frameAccum.set(0);
            snapFps = 0;
            lastFpsNs = 0;
            dragging = false;
            touchDownMs = 0;
            rendererActive = true;
            userEnabled = true;
            prefs.edit()
                    .putInt(KEY_SHOW, SHOW_DEFAULT)
                    .putInt(KEY_ALPHA, 100)
                    .putBoolean(KEY_VERT, false)
                    .putFloat(KEY_SCALE, 1.0f)
                    .putFloat(KEY_X, 16f)
                    .putFloat(KEY_Y, 16f)
                    .putBoolean(KEY_VIS, true)
                    .putBoolean(KEY_DUAL_CELL, false)
                    .apply();
            if (!mesaRendererActive) refreshBackendRenderer(true);
            startStatsThread();
            setVisibility(VISIBLE);
            scheduleRedraw();
            requestRelayout();
        });
    }

    private void requestRelayout() {
        try {
            requestLayout();
            invalidate();
        } catch (Exception ignored) {}
    }

    private int idxToMask(int idx) {
        switch (idx) {
            case 0:
                return SHOW_FPS;
            case 2:
                return SHOW_GPU_USAGE;
            case 3:
                return SHOW_CPU_USAGE;
            case 4:
                return SHOW_POWER;
            case 6:
                return SHOW_RENDERER;
            case 7:
                return SHOW_RAM;
            default:
                return 0;
        }
    }
}

