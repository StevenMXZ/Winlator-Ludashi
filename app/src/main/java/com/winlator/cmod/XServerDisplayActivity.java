package com.winlator.cmod;

import static com.winlator.cmod.core.AppUtils.showToast;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Rational;
import android.util.TypedValue;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.DXVKConfigDialog;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contentdialog.WineD3DConfigDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineRequestHandler;
import com.winlator.cmod.core.WineStartMenuCreator;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.midi.MidiHandler;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.LogView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.widget.EGLXServerView;
import com.winlator.cmod.widget.DisplayXServerView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.TaskManagerSidebar;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.xenvironment.components.XServerComponent;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.extensions.RandrExtension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;

public class XServerDisplayActivity extends AppCompatActivity {

    private static final boolean DISABLE_TOUCHSCREEN_AUTO_HIDE = true;
    private static final HashMap<String, Boolean> WINE_XRANDR_SUPPORT_CACHE = new HashMap<>();

    public static String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static int NOTIFICATION_ID = -1;
    private XServerRendererView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    private FrameRating classicHud = null;
    private WinlatorHUD modernHud = null;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private HashMap<String, String> graphicsDriverConfig;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String emulator = Container.DEFAULT_EMULATOR;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private String startupSelection;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    private SharedPreferences preferences;
    private OnExtractFileListener onExtractFileListener;
    private WinHandler winHandler;
    private TaskManagerSidebar taskManagerSidebar;
    private WineRequestHandler wineRequestHandler;
    private float globalCursorSpeed = 1.0f;
    private MagnifierView magnifierView;
    private boolean softStretchEnabled = false;
    private DebugDialog debugDialog;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private String wineCpuTopologyValue = "";
    private int frameRatingWindowId = -1;

    private int activeRendererWindowId = -1;
    private String lastRendererName = null;
    private boolean cursorLock;
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private boolean navigationFocused = false;
    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean isRelativeMouseMovement = false;
    private boolean isMouseDisabled = false;
    private boolean simulateTouchScreen = false;

    private SensorManager sensorManager;
    private Sensor gyroSensor;

    private long startTime;
    private SharedPreferences playtimePrefs;
    private String shortcutName;
    private Handler handler;
    private Runnable savePlaytimeRunnable;
    private static final long SAVE_INTERVAL_MS = 1000;

    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;

    private boolean isDarkMode;

    private String screenEffectProfile;

    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private EnvVars overrideEnvVars;

    private Switch swMouseGyroEnabled;

    private final SensorEventListener gyroListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
            if (winHandler == null) return;
            winHandler.updateGyroData(event.values[0], event.values[1], event.timestamp);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private boolean isMouseGyroEnabled() {
        return preferences != null && preferences.getBoolean("mouse_gyro_enabled", false);
    }

    private void updateGyroSensorRegistration() {
        if (sensorManager == null || gyroSensor == null) return;
        sensorManager.unregisterListener(gyroListener);
        if (isMouseGyroEnabled()) {
            // 4th arg maxReportLatencyUs=0 disables FIFO batching. Without it,
            // many devices only flush gyro events when the screen is touched.
            sensorManager.registerListener(
                    gyroListener,
                    gyroSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                    0
            );
        }
    }

    private void createNotifcationChannel() {
        String name = "Winlator";
        String description = "Winlator XServer Messages";
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (configChangedCallback != null) {
            configChangedCallback.run();
            configChangedCallback = null;
        }
    }

    private void advertisePanelRefreshRates() {
        RandrExtension randr = xServer.getExtension(RandrExtension.MAJOR_OPCODE);
        if (randr == null) return;

        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode[] modes = display.getSupportedModes();
        if (modes == null || modes.length == 0) return;

        java.util.TreeSet<Short> distinct =
                new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        for (android.view.Display.Mode mode : modes) {
            short hz = (short)Math.round(mode.getRefreshRate());
            if (hz > 0) distinct.add(hz);
        }
        if (distinct.isEmpty()) return;

        short[] rates = new short[distinct.size()];
        int index = 0;
        for (short hz : distinct) rates[index++] = hz;

        short activeRate = (short)Math.round(display.getRefreshRate());
        randr.setRefreshRates(rates, activeRate);
        Log.d("XServerDisplayActivity", "RandR advertising refresh rates "
                + java.util.Arrays.toString(rates) + ", active=" + activeRate);
    }

    private float pickHighestRefreshRate() {
        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode[] modes = display.getSupportedModes();

        float maxRefresh = 0f;

        for (android.view.Display.Mode mode : modes) {
            if (mode.getRefreshRate() > maxRefresh)
                maxRefresh = mode.getRefreshRate();
        }

        Log.d("XServerDisplayActivity", "Picking refresh rate " + maxRefresh);

        return maxRefresh;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);

        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.preferredRefreshRate = pickHighestRefreshRate();
        getWindow().setAttributes(params);

        setContentView(R.layout.xserver_display_activity);

        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        cursorLock = preferences.getBoolean("cursor_lock", true);

        isDarkMode = preferences.getBoolean("dark_mode", false);

        boolean isOpenWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean isShareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);

        boolean xinputDisabledFromShortcut = false;

        startTime = System.currentTimeMillis();

        handler = new Handler(Looper.getMainLooper());
        savePlaytimeRunnable = new Runnable() {
            @Override
            public void run() {
                savePlaytimeData();
                handler.postDelayed(this, SAVE_INTERVAL_MS);
            }
        };
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);

        hideControlsRunnable = () -> {
            if (DISABLE_TOUCHSCREEN_AUTO_HIDE) {
                return;
            }

            if (preferences.getBoolean("touchscreen_timeout_enabled", false)
                    && inputControlsView != null
                    && inputControlsView.getProfile() != null) {
                inputControlsView.setVisibility(View.GONE);
                Log.d("XServerDisplayActivity", "Touchscreen controls hidden after timeout.");
            }
        };

        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener(
                (view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                openSidebarPanel(activeSidebarItemId, activeSidebarPanelId);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                hideAllSidebarPanels();
            }
        });

        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false)
                || preferences.getBoolean("enable_box64_logs", false);

        wireSidebarListeners(enableLogs);

        imageFs = ImageFs.find(this);

        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            for (int i = 0; i < 4; i++) {
                File eventFile = new File(devInputDir, "event" + i);
                if (eventFile.exists())
                    eventFile.delete();
            }
        }

        winHandler = new WinHandler(this);
        winHandler.setFakeInputPath(devInputDir.getAbsolutePath());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        updateGyroSensorRegistration();

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));

        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        Log.d("XServerDisplayActivity", "Shortcut Path: " + shortcutPath);

        int containerId = getIntent().getIntExtra("container_id", 0);
        Log.d("XServerDisplayActivity", "Container ID from Intent: " + containerId);
        if (containerId == 0) {
            Log.d("XServerDisplayActivity", "Container ID is 0, attempting to parse from .desktop file");

        }

        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
            Log.d("XServerDisplayActivity", "Parsed Container ID from .desktop file: " + containerId);
        }

        playtimePrefs = getSharedPreferences("playtime_stats", MODE_PRIVATE);
        shortcutName = getIntent().getStringExtra("shortcut_name");

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            if (shortcutName == null || shortcutName.isEmpty()) {
                shortcutName = parseShortcutNameFromDesktopFile(new File(shortcutPath));
                Log.d("XServerDisplayActivity", "Parsed Shortcut Name from .desktop file: " + shortcutName);
            }
        } else {
            Log.d("XServerDisplayActivity", "No shortcut path provided, skipping shortcut parsing.");
        }

        incrementPlayCount();

        Log.d("XServerDisplayActivity", "Final Container ID: " + containerId);

        container = containerManager.getContainerById(containerId);

        if (container == null) {
            Log.e("XServerDisplayActivity", "Failed to retrieve container with ID: " + containerId);
            finish();
            return;
        }

        containerManager.activateContainer(container);

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }

        taskAffinityMask = (short) ProcessHelper.getAffinityMask(container.getCPUList(true));
        taskAffinityMaskWoW64 = (short) ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));

        String affinityCpuList = container.getCPUList(true);

        if (shortcut != null) {
            affinityCpuList = shortcut.getExtra("cpuList", container.getCPUList(true));
            taskAffinityMask = (short) ProcessHelper.getAffinityMask(affinityCpuList);
            taskAffinityMaskWoW64 = taskAffinityMask;
        }

        boolean syncCpuTopology = shortcut != null
                ? shortcut.getExtra("syncCpuTopology", container.isSyncCpuTopology() ? "1" : "").equals("1")
                : container.isSyncCpuTopology();

        wineCpuTopologyValue = "";
        if (syncCpuTopology && affinityCpuList != null && !affinityCpuList.isEmpty()) {
            int coreCount = affinityCpuList.split(",").length;
            wineCpuTopologyValue = coreCount + ":" + affinityCpuList;
        }

        String wmClass = shortcut != null ? shortcut.getExtra("wmClass", "") : "";
        Log.d("XServerDisplayActivity", "Startup wmClass: " + wmClass);

        firstTimeBoot = container.getExtra("imgVersion").isEmpty();

        String wineVersion = container.getWineVersion();
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        ProcessHelper.removeAllDebugCallbacks();
        if (enableLogs) {
            LogView.setFilename(getExecutable());
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        }

        graphicsDriver = container.getGraphicsDriver();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriver = Container.normalizeAudioDriver(container.getAudioDriver());
        emulator = container.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = container.getDXWrapper();
        String dxwrapperConfig = container.getDXWrapperConfig();
        screenSize = container.getScreenSize();
        winHandler.setInputType((byte) container.getInputType());
        lc_all = container.getLC_ALL();

        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriver = Container.normalizeAudioDriver(shortcut.getExtra("audioDriver", container.getAudioDriver()));
            emulator = shortcut.getExtra("emulator", container.getEmulator());
            dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
            lc_all = shortcut.getExtra("lc_all", container.getLC_ALL());
            String inputType = shortcut.getExtra("inputType");
            if (!inputType.isEmpty())
                winHandler.setInputType(Byte.parseByte(inputType));
            String xinputDisabledString = shortcut.getExtra("disableXinput", "false");
            xinputDisabledFromShortcut = parseBoolean(xinputDisabledString);

            winHandler.setXInputDisabled(xinputDisabledFromShortcut);
            String sharpnessEffect = shortcut.getExtra("sharpnessEffect", "None");
            if (!sharpnessEffect.equals("None")) {
                double sharpnessLevel = Double.parseDouble(shortcut.getExtra("sharpnessLevel", "100"));
                double sharpnessDenoise = Double.parseDouble(shortcut.getExtra("sharpnessDenoise", "100"));
                vkbasaltConfig = "effects=" + sharpnessEffect.toLowerCase() + ";" + "casSharpness="
                        + sharpnessLevel / 100 + ";" + "dlsSharpness=" + sharpnessLevel / 100 + ";" + "dlsDenoise="
                        + sharpnessDenoise / 100 + ";" + "enableOnLaunch=True";
            }
            Log.d("XServerDisplayActivity", "XInput Disabled from Shortcut: " + xinputDisabledFromShortcut);

            simulateTouchScreen = shortcut.getExtra("simTouchScreen").equals("1");
            isRelativeMouseMovement = shortcut.getExtra("enableRelativeMouse").equals("1");
            isMouseDisabled = shortcut.getExtra("disableMouse").equals("1");
        }

        this.graphicsDriverConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);
        this.dxwrapperConfig = DXVKConfigDialog.parseConfig(dxwrapperConfig);

        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/"))
                    return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }

        boolean removeLoadingBarWhenBootingGames = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("remove_loading_bar_when_booting_games", false);
        if (!removeLoadingBarWhenBootingGames) preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        boolean useDisplayX = isDisplayXEnabled();
        int surfaceFormat = useDisplayX && "rgba8".equals(getSelectedSurfaceFormat())
                ? Drawable.HAL_PIXEL_FORMAT_RGBA_8888
                : Drawable.HAL_PIXEL_FORMAT_BGRA_8888;
        xServer = new XServer(new ScreenInfo(screenSize), surfaceFormat);
        xServer.setWinHandler(winHandler);
        xServer.setRelativeMouseMovement(isRelativeMouseMovement);
        advertisePanelRefreshRates();

        boolean[] winStarted = { false };

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    if (!simulateTouchScreen) {
                        xServerView.setCursorVisible(true);
                    }
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }

                if (frameRatingWindowId == window.id) {
                    if (classicHud != null) classicHud.update();
                    if (modernHud != null) modernHud.onFrame();
                } else if (frameRatingWindowId == -1 && lastRendererName != null
                        && window.isApplicationWindow()
                        && ((modernHud != null && modernHud.isUserEnabled())
                        || (classicHud != null && classicHud.getVisibility() == View.VISIBLE))) {

                    frameRatingWindowId = window.id;
                    activeRendererWindowId = window.id;
                    if (xServerView != null) xServerView.setFpsWindowId(window.id);
                    if (classicHud != null) classicHud.update();
                    if (modernHud != null) modernHud.onFrame();
                }
            }

            @Override
            public void onMapWindow(Window window) {
                assignTaskAffinity(window);
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                changeFrameRatingVisibility(window, property);
            }

            @Override
            public void onDestroyWindow(Window window) {
                changeFrameRatingVisibility(window, null);
            }
        });

        if (!midiSoundFont.equals("")) {
            try {
                final InputStream in;
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                } else {
                    in = null;
                }
                MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                    @Override
                    public void onSuccess(SF2Soundbank soundbank) {
                        midiHandler = new MidiHandler();
                        midiHandler.setSoundBank(soundbank);
                        midiHandler.start();
                    }

                    @Override
                    public void onFailed(Exception e) {
                        if (in != null) {
                            try {
                                in.close();
                            } catch (Exception e2) {
                            }
                        }
                    }
                };
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    MidiManager.load(in, callback);
                } else {
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
                }
            } catch (Exception e) {
            }
        }

        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        createNotifcationChannel();

        Intent notificationIntent = new Intent(this, XServerDisplayActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
                .setContentTitle("Winlator")
                .setContentText("Winlator is running, do not kill or swipe this notification")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false);

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());

        Runnable runnable = () -> {
            setupUI();
            setupSidebarInputControls();
            if (controlsProfile.isEmpty()) {

                simulateConfirmInputControlsDialog();
            }
            Executors.newSingleThreadExecutor().execute(() -> {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
                applyGameRefreshRateUnlock();
                try {
                    setupXEnvironment();
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        };

        if (xServer.screenInfo.height > xServer.screenInfo.width) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            configChangedCallback = runnable;
        } else
            runnable.run();
    }

    private int parseContainerIdFromDesktopFile(File desktopFile) {
        int containerId = 0;
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("container_id:")) {
                        containerId = Integer.parseInt(line.split(":")[1].trim());
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Error parsing container_id from .desktop file", e);
            }
        }
        return containerId;
    }

    private boolean parseBoolean(String value) {

        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }

        return false;
    }

    private void handleCapturedPointer(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS: {
                int button = event.getActionButton();
                if (button == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                } else if (button == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                } else if (button == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
                }
                break;
            }
            case MotionEvent.ACTION_BUTTON_RELEASE: {
                int button = event.getActionButton();
                if (button == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                } else if (button == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                } else if (button == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE: {
                float[] p = XForm.transformPoint(xform, event.getX(), event.getY());
                int dx = (int) p[0];
                int dy = (int) p[1];
                if (xServer.isRelativeMouseMovement())
                    winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                else
                    xServer.injectPointerMoveDelta(dx, dy);
                break;
            }
            case MotionEvent.ACTION_SCROLL: {
                float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY <= -1.0f) {
                    if (xServer.isRelativeMouseMovement()) {
                        winHandler.mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int) scrollY * 270);
                    } else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                } else if (scrollY >= 1.0f) {
                    if (xServer.isRelativeMouseMovement()) {
                        winHandler.mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int) scrollY * 270);
                    } else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                }
                break;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateGyroSensorRegistration();

        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        startTime = System.currentTimeMillis();
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);
        if (!isInPictureInPictureMode())
            ProcessHelper.resumeAllWineProcesses();
    }

    @Override
    public void onPause() {
        if (taskManagerSidebar != null) taskManagerSidebar.stop();
        if (sensorManager != null) sensorManager.unregisterListener(gyroListener);
        super.onPause();

        if (!isInPictureInPictureMode()) {
            if (environment != null) {
                environment.onPause();
                xServerView.onPause();
            }

            ProcessHelper.pauseAllWineProcesses();
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        int w = xServer.screenInfo.width;
        int h = xServer.screenInfo.height;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(w, h));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && xServerView != null) {
            int[] loc = new int[2];
            xServerView.getLocationOnScreen(loc);
            Rect hint = new Rect(loc[0], loc[1],
                    loc[0] + xServerView.getWidth(), loc[1] + xServerView.getHeight());
            builder.setSourceRectHint(hint);
        }
        enterPictureInPictureMode(builder.build());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                              Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (xServerView == null) return;
        xServerView.setPipMode(isInPictureInPictureMode);
        if (!isInPictureInPictureMode) {
            xServerView.post(() -> {
                if (xServerView == null) return;
                int w = xServerView.getWidth();
                int h = xServerView.getHeight();
                if (w > 0 && h > 0) xServerView.onSurfaceChanged(w, h);
            });
        }
    }

    private void savePlaytimeData() {
        long endTime = System.currentTimeMillis();
        long playtime = endTime - startTime;

        if (playtime < 0) {
            playtime = 0;
        }

        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playtimeKey = shortcutName + "_playtime";

        long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0) + playtime;
        editor.putLong(playtimeKey, totalPlaytime);
        editor.apply();

        startTime = System.currentTimeMillis();
    }

    private void incrementPlayCount() {
        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playCountKey = shortcutName + "_play_count";
        int playCount = playtimePrefs.getInt(playCountKey, 0) + 1;
        editor.putInt(playCountKey, playCount);
        editor.apply();
    }

    private void exit() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        boolean removeLoadingBar = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("remove_loading_bar_when_booting_games", false);
        if (!removeLoadingBar) preloaderDialog.showOnUiThread(R.string.shutdown);

        if (xServerView != null) {
            xServerView.forceCleanup();
            xServerView.setVisibility(View.GONE);
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);

        if (midiHandler != null)
            midiHandler.stop();

        if (sensorManager != null)
            sensorManager.unregisterListener(gyroListener);

        if (environment != null)
            environment.stopEnvironmentComponents();
        if (winHandler != null)
            winHandler.stop();
        if (wineRequestHandler != null)
            wineRequestHandler.stop();

        Executors.newSingleThreadExecutor().execute(() -> {

            ProcessHelper.terminateAllWineProcesses();

            long start = System.currentTimeMillis();
            while (!ProcessHelper.listRunningWineProcesses().isEmpty()) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= 1500) {

                    for (String pid : ProcessHelper.listRunningWineProcesses()) {
                        ProcessHelper.killProcess(Integer.parseInt(pid));
                    }
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            preloaderDialog.closeOnUiThread();
            runOnUiThread(this::finish);
        });
    }

    @Override
    protected void onDestroy() {
        if (taskManagerSidebar != null) taskManagerSidebar.stop();
        if (sensorManager != null) sensorManager.unregisterListener(gyroListener);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            } else
                drawerLayout.closeDrawers();
        }
    }

    private void showVibrationDialog() {
        if (winHandler == null)
            return;

        Context context = this;
        int maxControllers = winHandler.getMaxControllers();
        boolean[] checkedItems = new boolean[maxControllers];
        String[] items = new String[maxControllers];

        for (int i = 0; i < maxControllers; i++) {
            items[i] = getString(R.string.vibration_slot, i + 1);
            checkedItems[i] = winHandler.isVibrationEnabledForSlot(i);
        }

        new androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.vibration)
                .setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> {
                    winHandler.setVibrationEnabledForSlot(which, isChecked);
                })
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && cursorLock)
            touchpadView.requestPointerCapture();
        else if (!hasFocus)
            touchpadView.releasePointerCapture();
    }

    private void setupWineSystemFiles() {
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;

        if (!container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(container);
            container.putExtra("imgVersion", imgVersion);
            containerDataChanged = true;
        }

        String dxwrapper = this.dxwrapper;

        if (dxwrapper.contains("dxvk")) {
            String dxvkWrapper = "dxvk-" + dxwrapperConfig.get("version");
            String vkd3dWrapper = "vkd3d-" + dxwrapperConfig.get("vkd3dVersion");
            String ddrawrapper = dxwrapperConfig.get("ddrawrapper");
            dxwrapper = dxvkWrapper + ";" + vkd3dWrapper + ";" + ddrawrapper;
        }

        if (!dxwrapper.equals(container.getExtra("dxwrapper"))) {
            extractDXWrapperFiles(dxwrapper);
            container.putExtra("dxwrapper", dxwrapper);
            containerDataChanged = true;
        }

        String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents())
                : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme + "," + xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme + "," + xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container);

        int inputType = container.getInputType();
        if (shortcut != null) {
            String shortcutInputType = shortcut.getExtra("inputType");
            if (!shortcutInputType.isEmpty()) {
                inputType = Byte.parseByte(shortcutInputType);
            }
        }
        boolean dinputEnabled = (inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT;

        boolean exclusiveXInput = container.isExclusiveXInput();
        if (shortcut != null) {
            String extra = shortcut.getExtra("exclusiveXInput");
            if (!extra.isEmpty())
                exclusiveXInput = extra.equals("1");
        }

        WineUtils.setJoystickRegistryKeys(container, dinputEnabled, exclusiveXInput);

        if (shortcut != null)
            startupSelection = shortcut.getExtra("startupSelection", String.valueOf(container.getStartupSelection()));
        else
            startupSelection = String.valueOf(container.getStartupSelection());

        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(container, startupSelection);
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }
        if (containerDataChanged)
            container.saveData();
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {

        envVars.put("LC_ALL", lc_all);
        envVars.put("WINEPREFIX", imageFs.wineprefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels",
                SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty()
                ? "+" + wineDebugChannels.replace(",", ",+")
                : "-all");

        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());

        guestProgramLauncherComponent = new GuestProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(container.getWineVersion()),
                shortcut);

        if (container != null) {
            if (Byte.parseByte(startupSelection) == Container.STARTUP_SELECTION_AGGRESSIVE) {

            }
            guestProgramLauncherComponent.setContainer(this.container);
            guestProgramLauncherComponent.setWineInfo(this.wineInfo);

            String guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " + getWineStartCommand();

            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());

            if (shortcut != null)
                envVars.putAll(shortcut.getExtra("envVars"));

            applyOpenGLDriverEnvVars();

            if (!wineCpuTopologyValue.isEmpty()) {
                envVars.put("WINE_CPU_TOPOLOGY", wineCpuTopologyValue);
            }

            if (!envVars.has("WINEESYNC")) {
                envVars.put("WINEESYNC", "1");
            }
            if (!envVars.has("WINE_FAST_YIELD")) {
                envVars.put("WINE_FAST_YIELD", "1");
            }

            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) {
                bindingPaths.add(drive[1]);
            }

            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));

            guestProgramLauncherComponent.setBox64Preset(
                    shortcut != null
                            ? shortcut.getExtra("box64Preset", container.getBox64Preset())
                            : container.getBox64Preset());

            guestProgramLauncherComponent.setFEXCorePreset(
                    shortcut != null
                            ? shortcut.getExtra("fexcorePreset", container.getFEXCorePreset())
                            : container.getFEXCorePreset());
        }

        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear();
        }

        environment = new XEnvironment(this, imageFs);
        environment.addComponent(
                new SysVSharedMemoryComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(
                new XServerComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)));

        if (audioDriver.equals("alsa")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(
                    new ALSAServerComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)));
        } else if (audioDriver.equals("pulseaudio") || audioDriver.equals("pulse-audio-gn")) {
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(
                    new PulseAudioComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH),
                            audioDriver.equals("pulse-audio-gn")));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> runOnUiThread(this::exit));

        environment.addComponent(guestProgramLauncherComponent);

        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {

        }

        environment.startEnvironmentComponents();

        winHandler.start();

        if (wineRequestHandler != null)
            wineRequestHandler.start();

        dxwrapperConfig = null;

    }

    private void createWrapperScript(String path, String content) {
        File scriptFile = new File(path);
        FileUtils.writeString(scriptFile, content);
        scriptFile.setExecutable(true);
    }

    private boolean isDisplayXEnabled() {
        if (shortcut != null) return shortcut.getUseDisplayX();
        return container != null && container.getUseDisplayX();
    }

    private String getSelectedSurfaceFormat() {
        if (shortcut != null) return shortcut.getSurfaceFormat();
        return container != null ? container.getSurfaceFormat() : "rgba8";
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);

        boolean useDisplayX = isDisplayXEnabled();

        boolean useEGL = !useDisplayX && (shortcut != null ? shortcut.getRendererNative()
                : (container != null && container.getRendererNative()));

        if (useDisplayX) {
            DisplayXServerView.loadNativeLibrary();
            boolean performanceMode = shortcut != null
                    ? shortcut.getDisplayXPerformanceMode()
                    : container == null || container.getDisplayXPerformanceMode();
            boolean presentAtRefreshRate = shortcut != null
                    ? shortcut.getDisplayXPresentAtRefreshRate()
                    : container == null || container.getDisplayXPresentAtRefreshRate();
            xServerView = new DisplayXServerView(
                    this,
                    xServer,
                    performanceMode,
                    presentAtRefreshRate);
        } else if (useEGL) {
            EGLXServerView.loadNativeLibrary();
            xServerView = new EGLXServerView(this, xServer);
        } else {
            VulkanXServerView.loadNativeLibrary();
            xServerView = new VulkanXServerView(this, xServer);
        }
        final XServerRendererView renderer = xServerView;
        renderer.setCursorVisible(false);

        if (renderer instanceof VulkanXServerView) {
            VulkanXServerView vkRenderer = (VulkanXServerView) renderer;

            String rendererDriverId = shortcut != null ? shortcut.getRendererDriverId()
                    : (container != null ? container.getRendererDriverId() : "");
            if (rendererDriverId == null || rendererDriverId.isEmpty()) {
                rendererDriverId = graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null;
            }
            if (rendererDriverId != null && !rendererDriverId.isEmpty() && !rendererDriverId.equalsIgnoreCase("system")) {
                try {
                    String driverPath = getFilesDir().getAbsolutePath() + "/contents/adrenotools/" + rendererDriverId + "/";
                    AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
                    String libraryName = adrenotoolsManager.getLibraryName(rendererDriverId);
                    String nativeLibDir = AppUtils.getNativeLibDir(this);
                    if (!libraryName.isEmpty())
                        vkRenderer.setDriverInfo(driverPath, libraryName, nativeLibDir);
                } catch (Exception ignored) {
                }
            }

            String presentMode = shortcut != null ? shortcut.getRendererPresentMode()
                    : (container != null ? container.getRendererPresentMode() : "fifo");
            vkRenderer.setVkPresentMode(com.winlator.cmod.contentdialog.RendererOptionsDialog.toVkPresentMode(presentMode));
            vkRenderer.setFilterMode(shortcut != null ? shortcut.getRendererFilterMode()
                    : (container != null ? container.getRendererFilterMode() : 0));
        } else if (renderer instanceof EGLXServerView) {
            EGLXServerView eglRenderer = (EGLXServerView) renderer;
            eglRenderer.setFilterMode(shortcut != null ? shortcut.getRendererFilterMode()
                    : (container != null ? container.getRendererFilterMode() : 0));
        }

        if (shortcut != null) {
            renderer.setUnviewableWMClasses("explorer.exe");
        }

        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer, timeoutHandler, hideControlsRunnable);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMouseEnabled(!isMouseDisabled);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START))
                drawerLayout.openDrawer(GravityCompat.START);
        });
        View.OnCapturedPointerListener capturedPointerListener = new View.OnCapturedPointerListener() {
            @Override
            public boolean onCapturedPointer(View view, MotionEvent event) {
                handleCapturedPointer(event);
                return true;
            }
        };
        touchpadView.setOnCapturedPointerListener(cursorLock ? capturedPointerListener : null);
        touchpadView.setFocusable(true);
        touchpadView.setFocusableInTouchMode(true);
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this, timeoutHandler, hideControlsRunnable);
        inputControlsView
                .setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        }

        if (container != null) {
            String hudModeExtra = container.getExtra("hudMode");
            int hudMode = !hudModeExtra.isEmpty()
                    ? Integer.parseInt(hudModeExtra)
                    : (container.isShowFPS() ? 1 : 0);

            if (hudMode == 1) {

                classicHud = new FrameRating(this, graphicsDriverConfig);
                classicHud.setVisibility(View.GONE);
                rootView.addView(classicHud);
                renderer.setFrameRating(classicHud);
            } else if (hudMode == 2) {

                modernHud = new WinlatorHUD(this);
                modernHud.setVisibility(View.GONE);
                rootView.addView(modernHud);
                modernHud.enableByUser();
                renderer.setFrameRating(modernHud);
            }
        }

        String shortcutFullscreenStretched = shortcut != null ? shortcut.getExtra("fullscreenStretched") : null;

        boolean shouldStretch = false;

        if (shortcut != null && shortcutFullscreenStretched != null) {

            shouldStretch = shortcutFullscreenStretched.equals("1");
        } else if (container != null && container.isFullscreenStretched()) {

            shouldStretch = true;
        }

        if (shouldStretch) {

            renderer.toggleFullscreen();
            touchpadView.toggleFullscreen();
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null)
                    showInputControls(profile);
            }

            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchpadView.setSimTouchScreen(simTouchScreen.equals("1"));
            if (simulateTouchScreen) {
                renderer.setCursorVisible(false);
            }
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);

        setupSidebarHudControls();
        setupSidebarGraphicsControls();
    }

    private ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            });

    private String parseShortcutNameFromDesktopFile(File desktopFile) {
        String shortcutName = "";
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Name=")) {
                        shortcutName = line.split("=")[1].trim();
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e("XServerDisplayActivity", "Error reading shortcut name from .desktop file", e);
            }
        }
        return shortcutName;
    }

    private void setTextColorForDialog(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {

                setTextColorForDialog((ViewGroup) child, color);
            } else if (child instanceof TextView) {

                ((TextView) child).setTextColor(color);
            }
        }
    }

    private void wireSidebarListeners(boolean enableLogs) {

        View btItemLogs = findViewById(R.id.BTItemLogs);
        if (btItemLogs != null)
            btItemLogs.setVisibility(enableLogs ? View.VISIBLE : View.GONE);

        if (XrActivity.isEnabled(this)) {
            View btItemMagnifier = findViewById(R.id.BTItemMagnifier);
            if (btItemMagnifier != null)
                btItemMagnifier.setVisibility(View.GONE);
        }

        toggleOnClick(R.id.BTItemInput, R.id.LLSubInput);
        toggleOnClick(R.id.BTItemMouse, R.id.LLSubMouse);
        toggleOnClick(R.id.BTItemFPS, R.id.LLSubFPS);
        toggleOnClick(R.id.BTItemGraphics, R.id.LLSubGraphics);
        toggleOnClick(R.id.BTItemScreen, R.id.LLSubScreen);
        openSidebarPanel(R.id.BTItemFPS, R.id.LLSubFPS);

        ViewGroup btItemPause = (ViewGroup) findViewById(R.id.BTItemPause);
        if (btItemPause != null) {
            ImageView pauseIcon = (ImageView) btItemPause.getChildAt(0);
            btItemPause.setOnClickListener(v -> {
                if (isPaused) {
                    ProcessHelper.resumeAllWineProcesses();
                    if (pauseIcon != null) pauseIcon.setImageResource(R.drawable.icon_pause);
                } else {
                    ProcessHelper.pauseAllWineProcesses();
                    if (pauseIcon != null) pauseIcon.setImageResource(R.drawable.icon_play);
                }
                isPaused = !isPaused;
                drawerLayout.closeDrawers();
            });
        }

        View btSubKeyboard = findViewById(R.id.BTSubKeyboard);
        if (btSubKeyboard != null) {
            btSubKeyboard.setOnClickListener(v -> {
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
            });
        }

        setupSidebarInputControls();

        View btSubVibration = findViewById(R.id.BTSubVibration);
        if (btSubVibration != null) {
            btSubVibration.setOnClickListener(v -> {
                showVibrationDialog();
                drawerLayout.closeDrawers();
            });
        }

        Switch swRelativeMouse = findViewById(R.id.SWRelativeMouse);
        if (swRelativeMouse != null) {
            swRelativeMouse.setChecked(isRelativeMouseMovement);
            swRelativeMouse.setOnCheckedChangeListener((btn, checked) -> {
                isRelativeMouseMovement = checked;
                if (xServer != null)
                    xServer.setRelativeMouseMovement(isRelativeMouseMovement);
            });
        }

        Switch swDisableMouse = findViewById(R.id.SWDisableMouse);
        if (swDisableMouse != null) {
            swDisableMouse.setChecked(isMouseDisabled);
            swDisableMouse.setOnCheckedChangeListener((btn, checked) -> {
                isMouseDisabled = checked;
                if (touchpadView != null)
                    touchpadView.setMouseEnabled(!isMouseDisabled);
            });
        }

        swMouseGyroEnabled = findViewById(R.id.SWMouseGyroEnabled);
        if (swMouseGyroEnabled != null) {
            swMouseGyroEnabled.setChecked(isMouseGyroEnabled());
            swMouseGyroEnabled.setOnCheckedChangeListener((btn, checked) -> {
                preferences.edit().putBoolean("mouse_gyro_enabled", checked).apply();
                if (winHandler != null) winHandler.reloadGyroSettings();
                updateGyroSensorRegistration();
            });
        }

        // ======================================
// GYRO X / Y SENSITIVITY
// ======================================

        SeekBar sbGyroX = findViewById(R.id.SBGyroX);
        SeekBar sbGyroY = findViewById(R.id.SBGyroY);

        TextView lblGyroX = findViewById(R.id.LBLGyroX);
        TextView lblGyroY = findViewById(R.id.LBLGyroY);


// ======================================
// GYRO X
// ======================================

        if (sbGyroX != null) {

            float savedX = preferences.getFloat(
                    "gyro_x_sensitivity",
                    1.0f
            );

            savedX = Math.max(
                    0.10f,
                    Math.min(3.00f, savedX)
            );

            // Convertimos 0.10 - 3.00
            // a 0 - 100 para nuestro SeekBar.
            float sliderX =
                    ((savedX - 0.10f) / 2.90f) * 100.0f;

            sbGyroX.setValue(sliderX);

            if (lblGyroX != null) {
                lblGyroX.setText(
                        String.format(
                                "Gyro X Sensitivity: %.2f",
                                savedX
                        )
                );
            }

            sbGyroX.setOnValueChangeListener((sb, value) -> {

                float sensitivity =
                        0.10f + (value / 100.0f) * 2.90f;

                preferences.edit()
                        .putFloat(
                                "gyro_x_sensitivity",
                                sensitivity
                        )
                        .apply();

                if (winHandler != null) {
                    winHandler.setGyroSensitivityX(
                            sensitivity
                    );
                }

                if (lblGyroX != null) {
                    lblGyroX.setText(
                            String.format(
                                    "Gyro X Sensitivity: %.2f",
                                    sensitivity
                            )
                    );
                }
            });
        }


// ======================================
// GYRO Y
// ======================================

        if (sbGyroY != null) {

            float savedY = preferences.getFloat(
                    "gyro_y_sensitivity",
                    1.0f
            );

            savedY = Math.max(
                    0.10f,
                    Math.min(3.00f, savedY)
            );

            float sliderY =
                    ((savedY - 0.10f) / 2.90f) * 100.0f;

            sbGyroY.setValue(sliderY);

            if (lblGyroY != null) {
                lblGyroY.setText(
                        String.format(
                                "Gyro Y Sensitivity: %.2f",
                                savedY
                        )
                );
            }

            sbGyroY.setOnValueChangeListener((sb, value) -> {

                float sensitivity =
                        0.10f + (value / 100.0f) * 2.90f;

                preferences.edit()
                        .putFloat(
                                "gyro_y_sensitivity",
                                sensitivity
                        )
                        .apply();

                if (winHandler != null) {
                    winHandler.setGyroSensitivityY(
                            sensitivity
                    );
                }

                if (lblGyroY != null) {
                    lblGyroY.setText(
                            String.format(
                                    "Gyro Y Sensitivity: %.2f",
                                    sensitivity
                            )
                    );
                }
            });
        }

        View btItemPipMode = findViewById(R.id.BTItemPipMode);
        if (btItemPipMode != null) {
            btItemPipMode.setOnClickListener(v -> {
                enterPipMode();
                drawerLayout.closeDrawers();
            });
        }

        View btItemToggleFullscreen = findViewById(R.id.BTItemToggleFullscreen);
        if (btItemToggleFullscreen != null) {
            btItemToggleFullscreen.setOnClickListener(v -> {
                if (xServerView != null) {
                    xServerView.toggleFullscreen();
                    if (touchpadView != null)
                        touchpadView.toggleFullscreen();
                }
                drawerLayout.closeDrawers();
            });
        }

        View btItemMagnifier = findViewById(R.id.BTItemMagnifier);
        if (btItemMagnifier != null) {
            btItemMagnifier.setOnClickListener(v -> {
                if (xServerView instanceof DisplayXServerView) {
                    showToast(this, R.string.magnifier_not_available);
                    drawerLayout.closeDrawers();
                    return;
                }
                if (xServerView != null) {
                    final XServerRendererView renderer = xServerView;
                    if (magnifierView == null) {
                        FrameLayout flContainer = findViewById(R.id.FLXServerDisplay);
                        magnifierView = new MagnifierView(this);
                        magnifierView.setZoomButtonCallback(value -> {
                            renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                            magnifierView.setZoomValue(renderer.getMagnifierZoom());
                        });
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                        magnifierView.setHideButtonCallback(() -> {
                            flContainer.removeView(magnifierView);
                            magnifierView = null;
                        });
                        flContainer.addView(magnifierView);
                    }
                }
                drawerLayout.closeDrawers();
            });
        }

        View btItemSoftStretch = findViewById(R.id.BTItemSoftStretch);
        if (btItemSoftStretch != null) {
            btItemSoftStretch.setOnClickListener(v -> {
                if (xServerView != null) {
                    softStretchEnabled = !softStretchEnabled;
                    XServerRendererView rendererRef = xServerView;

                    if (softStretchEnabled && !rendererRef.isFullscreen()) {
                        rendererRef.toggleFullscreen();
                        if (touchpadView != null) touchpadView.toggleFullscreen();
                    }
                    if (rendererRef instanceof VulkanXServerView) {
                        ((VulkanXServerView) rendererRef).setStretchMode(softStretchEnabled ? 1 : 0);
                    }
                    btItemSoftStretch.setSelected(softStretchEnabled);
                }
                drawerLayout.closeDrawers();
            });
        }

        View btItemTaskManager = findViewById(R.id.BTItemTaskManager);
        if (btItemTaskManager != null) {
            btItemTaskManager.setOnClickListener(v -> {
                openSidebarPanel(R.id.BTItemTaskManager, R.id.LLSubTaskManager);
                View taskPanel = findViewById(R.id.LLSubTaskManager);
                if (taskPanel != null) {
                    if (taskManagerSidebar == null)
                        taskManagerSidebar = new TaskManagerSidebar(this, taskPanel);
                    taskManagerSidebar.start();
                }
            });
        }

        if (btItemLogs != null) {
            btItemLogs.setOnClickListener(v -> {
                if (debugDialog != null)
                    debugDialog.show();
                drawerLayout.closeDrawers();
            });
        }

        View btItemExit = findViewById(R.id.BTItemExit);
        if (btItemExit != null) {
            btItemExit.setOnClickListener(v -> {
                drawerLayout.closeDrawers();
                exit();
            });
        }
    }

    private int activeSidebarItemId = R.id.BTItemFPS;
    private int activeSidebarPanelId = R.id.LLSubFPS;

    private final int[] sidebarPanelIds = {
            R.id.LLSubInput,
            R.id.LLSubMouse,
            R.id.LLSubFPS,
            R.id.LLSubGraphics,
            R.id.LLSubScreen,
            R.id.LLSubTaskManager
    };

    private final int[] sidebarItemIds = {
            R.id.BTItemInput,
            R.id.BTItemMouse,
            R.id.BTItemFPS,
            R.id.BTItemGraphics,
            R.id.BTItemScreen,
            R.id.BTItemTaskManager
    };

    private void hideAllSidebarPanels() {
        if (taskManagerSidebar != null) taskManagerSidebar.stop();
        for (int panelId : sidebarPanelIds) {
            View panel = findViewById(panelId);
            if (panel != null) panel.setVisibility(View.GONE);
        }
    }

    private void setSidebarActiveItem(int activeId) {
        float density = getResources().getDisplayMetrics().density;
        for (int itemId : sidebarItemIds) {
            View item = findViewById(itemId);
            if (item == null) continue;
            if (itemId == activeId) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.parseColor("#0F2D42"));
                background.setStroke((int) (1.1f * density), Color.parseColor("#0288D1"));
                background.setCornerRadius(density * 15);
                item.setBackground(background);
                item.animate().scaleX(1.025f).scaleY(1.025f).setDuration(105).start();
            } else {
                TypedValue outValue = new TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                item.setBackgroundResource(outValue.resourceId);
                item.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start();
            }
        }
    }

    private void openSidebarPanel(int parentId, int subId) {
        hideAllSidebarPanels();
        View sub = findViewById(subId);
        if (sub != null) {
            float density = getResources().getDisplayMetrics().density;
            sub.setVisibility(View.VISIBLE);
            sub.setAlpha(0.0f);
            sub.setTranslationX(-8.0f * density);
            sub.animate().alpha(1.0f).translationX(0.0f).setDuration(130).start();
        }
        setSidebarActiveItem(parentId);
        if (parentId != R.id.BTItemMouse && parentId != R.id.BTItemPause) {
            activeSidebarItemId = parentId;
            activeSidebarPanelId = subId;
        }
    }

    private void toggleOnClick(int parentId, int subId) {
        View parent = findViewById(parentId);
        View sub = findViewById(subId);
        if (parent != null && sub != null) {
            parent.setOnClickListener(v -> openSidebarPanel(parentId, subId));
        }
    }

    private ArrayAdapter<String> createSidebarSpinnerAdapter(String[] items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.parseColor("#EEF7FF"));
                view.setTextSize(14);
                view.setSingleLine(true);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.parseColor("#EEF7FF"));
                view.setBackgroundColor(Color.parseColor("#0E2231"));
                view.setTextSize(14);
                return view;
            }
        };
    }

    private void setupSidebarHudControls() {
        Switch       swHudMaster     = findViewById(R.id.SWHudMaster);
        Spinner      spHudStyle      = findViewById(R.id.SPHudStyle);
        LinearLayout llHudStyleRow   = findViewById(R.id.LLHudStyleRow);
        LinearLayout llModernOptions = findViewById(R.id.LLModernHudOptions);
        CheckBox     cbFps           = findViewById(R.id.CBHudFps);
        CheckBox     cbGpu           = findViewById(R.id.CBHudGpu);
        CheckBox     cbCpuRam        = findViewById(R.id.CBHudCpuRam);
        CheckBox     cbRam           = findViewById(R.id.CBHudRam);
        CheckBox     cbBattTemp      = findViewById(R.id.CBHudBattTemp);
        CheckBox     cbGraph         = findViewById(R.id.CBHudGraph);
        CheckBox     cbRenderer      = findViewById(R.id.CBHudRenderer);
        SeekBar      sbScale         = findViewById(R.id.SBHudScale);
        SeekBar      sbAlpha         = findViewById(R.id.SBHudAlpha);
        View         btResetHud      = findViewById(R.id.BTResetHud);

        int currentMode = 0;
        if      (modernHud  != null) currentMode = 2;
        else if (classicHud != null) currentMode = 1;
        else if (container  != null) {
            String extra = container.getExtra("hudMode");
            if (!extra.isEmpty())           currentMode = Integer.parseInt(extra);
            else if (container.isShowFPS()) currentMode = 1;
        }

        boolean hudOn    = currentMode != 0;
        boolean isModern = currentMode == 2;

        if (spHudStyle != null) {
            ArrayAdapter<String> styleAdapter = createSidebarSpinnerAdapter(new String[]{"Classic", "Modern"});
            styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spHudStyle.setAdapter(styleAdapter);
            spHudStyle.setSelection(isModern ? 1 : 0, false);
        }
        if (llHudStyleRow  != null) llHudStyleRow.setVisibility(hudOn ? View.VISIBLE : View.GONE);
        if (llModernOptions != null) llModernOptions.setVisibility(isModern ? View.VISIBLE : View.GONE);

        if (modernHud != null) {
            modernHud.syncCheckboxes(cbFps, cbGpu, cbCpuRam, cbBattTemp, cbGraph, cbRenderer);
            if (cbRam != null) cbRam.setChecked(true);
            bindModernHudCheckboxes(cbFps, cbGpu, cbCpuRam, cbRam, cbBattTemp, cbRenderer);
        }
        if (sbScale != null) sbScale.setOnValueChangeListener((sb, v) -> {
            if (modernHud != null) modernHud.setHudScale(1f + (v - 50f) / 50f);
        });
        if (sbAlpha != null) sbAlpha.setOnValueChangeListener((sb, v) -> {
            if (modernHud != null) modernHud.setHudAlpha(v / 100f);
        });
        if (btResetHud != null) btResetHud.setOnClickListener(v -> {
            if (modernHud != null) modernHud.forceReset();
        });

        if (swHudMaster != null) {
            swHudMaster.setChecked(hudOn);
            swHudMaster.setOnCheckedChangeListener((btn, checked) -> {
                int style = resolveSelectedStyle(spHudStyle);
                if (checked) {
                    enableHudLazily(style);
                    if (llHudStyleRow  != null) llHudStyleRow.setVisibility(View.VISIBLE);
                    if (llModernOptions != null)
                        llModernOptions.setVisibility(style == 2 ? View.VISIBLE : View.GONE);
                    if (style == 2 && modernHud != null) {
                        modernHud.syncCheckboxes(cbFps, cbGpu, cbCpuRam, cbBattTemp, cbGraph, cbRenderer);
                        if (cbRam != null) cbRam.setChecked(true);
                        bindModernHudCheckboxes(cbFps, cbGpu, cbCpuRam, cbRam, cbBattTemp, cbRenderer);
                    }
                    saveHudModeToContainer(style);
                } else {
                    if (classicHud != null) classicHud.disableByUser();
                    if (modernHud  != null) modernHud.disableByUser();
                    if (llHudStyleRow  != null) llHudStyleRow.setVisibility(View.GONE);
                    if (llModernOptions != null) llModernOptions.setVisibility(View.GONE);
                    saveHudModeToContainer(0);
                }
            });
        }

        if (spHudStyle != null) {
            spHudStyle.post(() -> spHudStyle.setOnItemSelectedListener(
                    new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                            if (swHudMaster == null || !swHudMaster.isChecked()) return;
                            int newStyle = (pos == 1) ? 2 : 1;
                            if (classicHud != null) classicHud.disableByUser(false);
                            if (modernHud  != null) modernHud.disableByUser(false);
                            enableHudLazily(newStyle);
                            if (llModernOptions != null)
                                llModernOptions.setVisibility(newStyle == 2 ? View.VISIBLE : View.GONE);
                            if (newStyle == 2 && modernHud != null) {
                                modernHud.syncCheckboxes(cbFps, cbGpu, cbCpuRam, cbBattTemp, cbGraph, cbRenderer);
                                if (cbRam != null) cbRam.setChecked(true);
                                bindModernHudCheckboxes(cbFps, cbGpu, cbCpuRam, cbRam, cbBattTemp, cbRenderer);
                            }
                            saveHudModeToContainer(newStyle);
                        }
                        @Override public void onNothingSelected(AdapterView<?> p) {}
                    }
            ));
        }
    }

    private void enableHudLazily(int style) {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        if (rootView == null || xServerView == null) return;
        final XServerRendererView renderer = xServerView;

        boolean rendererAlreadyActive = (activeRendererWindowId != -1);

        if (style == 2) {
            if (modernHud == null) {
                modernHud = new WinlatorHUD(this);
                modernHud.setVisibility(View.GONE);
                rootView.addView(modernHud);
                renderer.setFrameRating(modernHud);
                if (rendererAlreadyActive) {

                    frameRatingWindowId = activeRendererWindowId;
                    renderer.setFpsWindowId(frameRatingWindowId);

                    final String name = lastRendererName;
                    modernHud.onRendererDetected(name);
                }
            }
            modernHud.enableByUser();
        } else {
            if (classicHud == null) {
                classicHud = new FrameRating(this, graphicsDriverConfig);
                classicHud.setVisibility(View.GONE);
                rootView.addView(classicHud);
                renderer.setFrameRating(classicHud);
                if (rendererAlreadyActive) {
                    frameRatingWindowId = activeRendererWindowId;
                    renderer.setFpsWindowId(frameRatingWindowId);
                    runOnUiThread(() -> classicHud.update());
                }
            }
            classicHud.enableByUser();
        }
    }

    private void bindModernHudCheckboxes(CheckBox cbFps, CheckBox cbGpu, CheckBox cbCpuRam,
                                         CheckBox cbRam, CheckBox cbBattTemp, CheckBox cbRenderer) {
        if (modernHud == null) return;
        if (cbFps      != null) cbFps.setOnCheckedChangeListener((b, v) -> modernHud.toggleElement(0, v));
        if (cbGpu      != null) cbGpu.setOnCheckedChangeListener((b, v) -> modernHud.toggleElement(2, v));
        if (cbCpuRam   != null) cbCpuRam.setOnCheckedChangeListener((b, v) -> modernHud.toggleElement(3, v));
        if (cbRam      != null) cbRam.setOnCheckedChangeListener((b, v) -> modernHud.toggleElement(7, v));
        if (cbBattTemp != null) cbBattTemp.setOnCheckedChangeListener((b, v) -> modernHud.toggleElement(4, v));
        if (cbRenderer != null) cbRenderer.setOnCheckedChangeListener((b, v) -> modernHud.toggleElement(6, v));
    }

    private int resolveSelectedStyle(Spinner spHudStyle) {
        if (spHudStyle == null) return 1;
        return spHudStyle.getSelectedItemPosition() == 1 ? 2 : 1;
    }

    private void saveHudModeToContainer(int mode) {
        if (container == null) return;
        container.putExtra("hudMode", String.valueOf(mode));
        container.setShowFPS(mode != 0);
        container.saveData();
    }

    private void setupSidebarGraphicsControls() {
        final boolean isVulkanRenderer = xServerView instanceof VulkanXServerView;

        View reshadePanel = findViewById(R.id.LLSubReshade);
        View postFxDivider = findViewById(R.id.VPostFXDivider);
        View postFxOptions = findViewById(R.id.LLPostFXOptions);

        if (reshadePanel != null) {
            reshadePanel.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        }
        if (postFxDivider != null) {
            postFxDivider.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        }
        if (postFxOptions != null) {
            postFxOptions.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        }

        final XServerRendererView renderer = xServerView;
        final VulkanXServerView  vkRenderer = renderer instanceof VulkanXServerView ? (VulkanXServerView) renderer : null;
        final EGLXServerView     eglRenderer = renderer instanceof EGLXServerView ? (EGLXServerView) renderer : null;
        final DisplayXServerView displayXRenderer = renderer instanceof DisplayXServerView ? (DisplayXServerView) renderer : null;

        Spinner spNativeFPS        = findViewById(R.id.SPNativeFPS);
        View    llStandardOptions  = findViewById(R.id.LLStandardOptions);
        Switch  swEnableFSR        = findViewById(R.id.SWEnableFSR);
        Spinner spUpscalerMode     = findViewById(R.id.SPUpscalerMode);
        View    lblSharpnessHeader = findViewById(R.id.LBLSharpnessHeader);
        SeekBar sbSharpness        = findViewById(R.id.SBSharpness);
        Spinner spPostFXMode       = findViewById(R.id.SPPostFXMode);
        Spinner spColorMode        = findViewById(R.id.SPColorMode);
        View    btSaveGraphicsPreset = findViewById(R.id.BTSaveGraphicsPreset);
        View    llFrameGenOptions  = findViewById(R.id.LLFrameGenOptions);
        Spinner spFrameGenFPS      = findViewById(R.id.SPFrameGenFPS);

        if (llFrameGenOptions != null) llFrameGenOptions.setVisibility(View.GONE);
        if (spFrameGenFPS  != null) spFrameGenFPS.setVisibility(View.GONE);
        if (spColorMode    != null) spColorMode.setVisibility(View.GONE);
        if (llStandardOptions != null) llStandardOptions.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        if (btSaveGraphicsPreset != null) btSaveGraphicsPreset.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
        if (eglRenderer != null) {
            if (swEnableFSR        != null) swEnableFSR.setVisibility(View.GONE);
            if (spUpscalerMode     != null) spUpscalerMode.setVisibility(View.GONE);
            if (lblSharpnessHeader != null) lblSharpnessHeader.setVisibility(View.GONE);
            if (sbSharpness        != null) sbSharpness.setVisibility(View.GONE);
            if (spPostFXMode       != null) spPostFXMode.setVisibility(View.GONE);
        }

        final int[]    fpsValues = {0, 30, 60, 90, 120};
        final String[] fpsLabels = {"Off", "30 FPS", "60 FPS", "90 FPS", "120 FPS"};

        if (spNativeFPS != null) {
            ArrayAdapter<String> a = createSidebarSpinnerAdapter(fpsLabels);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spNativeFPS.setAdapter(a);
            String savedFps = container != null ? container.getExtra("graphicsFpsPreset") : "";
            int savedFpsPos = savedFps.isEmpty() ? 0 : Integer.parseInt(savedFps);
            if (savedFpsPos < 0 || savedFpsPos >= fpsLabels.length) savedFpsPos = 0;
            spNativeFPS.setSelection(savedFpsPos);
            int initialFpsLimit = savedFpsPos < fpsValues.length ? fpsValues[savedFpsPos] : 0;
            if (vkRenderer != null) vkRenderer.setFpsLimit(initialFpsLimit);
            if (eglRenderer != null) eglRenderer.setFpsLimit(initialFpsLimit);
            if (displayXRenderer != null) displayXRenderer.setFpsLimit(initialFpsLimit);
            spNativeFPS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (llStandardOptions != null) llStandardOptions.setVisibility(isVulkanRenderer ? View.VISIBLE : View.GONE);
                    if (llFrameGenOptions != null) llFrameGenOptions.setVisibility(View.GONE);
                    int fpsLimit = pos < fpsValues.length ? fpsValues[pos] : 0;
                    if (vkRenderer != null) vkRenderer.setFpsLimit(fpsLimit);
                    if (eglRenderer != null) eglRenderer.setFpsLimit(fpsLimit);
                    if (displayXRenderer != null) displayXRenderer.setFpsLimit(fpsLimit);
                    if (!isVulkanRenderer && container != null) {
                        container.putExtra("graphicsFpsPreset", String.valueOf(pos));
                        container.saveData();
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        }

        if (btSaveGraphicsPreset != null) {
            btSaveGraphicsPreset.setOnClickListener(v -> {
                if (container == null) return;
                container.putExtra("graphicsFpsPreset",
                        String.valueOf(spNativeFPS != null ? spNativeFPS.getSelectedItemPosition() : 0));
                if (vkRenderer != null) {
                    container.putExtra("graphicsFilterMode",
                            String.valueOf(swEnableFSR != null && swEnableFSR.isChecked()
                                    ? (spUpscalerMode != null ? spUpscalerMode.getSelectedItemPosition() + 2 : 2) : 0));
                    container.putExtra("graphicsSharpness",
                            String.valueOf(sbSharpness != null ? sbSharpness.getValue() : 50f));
                    container.putExtra("graphicsPostFXMode",
                            String.valueOf(spPostFXMode != null ? spPostFXMode.getSelectedItemPosition() : 0));
                    container.putExtra("graphicsColorMode", "0");
                }
                container.saveData();
                Toast.makeText(this, "Preset saved", Toast.LENGTH_SHORT).show();
            });
        }

        if (vkRenderer == null) return;

        final String[] upscalerLabels = {"SGSR", "FSR"};
        if (spUpscalerMode != null) {
            ArrayAdapter<String> a = createSidebarSpinnerAdapter(upscalerLabels);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spUpscalerMode.setAdapter(a);
            spUpscalerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (swEnableFSR != null && swEnableFSR.isChecked())
                        vkRenderer.setFilterMode(pos + 2);
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        }

        String savedSharp = container != null ? container.getExtra("graphicsSharpness") : "";
        float  initSharp  = savedSharp.isEmpty() ? 50f : Float.parseFloat(savedSharp);
        if (sbSharpness != null) {
            sbSharpness.setValue(initSharp);
            vkRenderer.setSharpness(initSharp / 100f);
            sbSharpness.setOnValueChangeListener((sb, v) -> vkRenderer.setSharpness(v / 100f));
        }

        Runnable updateSharpnessVis = () -> {
            boolean fsrOn = swEnableFSR  != null && swEnableFSR.isChecked();
            boolean dlsOn = spPostFXMode != null && spPostFXMode.getSelectedItemPosition() == 1;
            int vis = (fsrOn || dlsOn) ? View.VISIBLE : View.GONE;
            if (lblSharpnessHeader != null) lblSharpnessHeader.setVisibility(vis);
            if (sbSharpness        != null) sbSharpness.setVisibility(vis);
        };

        String savedFilter = container != null ? container.getExtra("graphicsFilterMode") : "";
        boolean fsrOn = !savedFilter.isEmpty() && Integer.parseInt(savedFilter) > 0;
        if (swEnableFSR != null) {
            swEnableFSR.setChecked(fsrOn);
            if (spUpscalerMode != null)
                spUpscalerMode.setVisibility(fsrOn ? View.VISIBLE : View.GONE);
            if (fsrOn)
                vkRenderer.setFilterMode(spUpscalerMode != null
                        ? spUpscalerMode.getSelectedItemPosition() + 2 : 2);
            swEnableFSR.setOnCheckedChangeListener((btn, checked) -> {
                if (spUpscalerMode != null)
                    spUpscalerMode.setVisibility(checked ? View.VISIBLE : View.GONE);
                vkRenderer.setFilterMode(checked
                        ? (spUpscalerMode != null ? spUpscalerMode.getSelectedItemPosition() + 2 : 2)
                        : (container != null ? container.getRendererFilterMode() : 0));
                updateSharpnessVis.run();
            });
        }

        final String[] pfxLabels = {"None", "DLS", "CRT", "HDR", "Natural"};
        if (spPostFXMode != null) {
            ArrayAdapter<String> a = createSidebarSpinnerAdapter(pfxLabels);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spPostFXMode.setAdapter(a);

            String savedPFX = container != null ? container.getExtra("graphicsPostFXMode") : "";
            int initPFX = savedPFX.isEmpty() ? 0 : Integer.parseInt(savedPFX);
            if (initPFX < 0 || initPFX >= pfxLabels.length) initPFX = 0;

            spPostFXMode.setSelection(initPFX, false);
            if (initPFX > 0) vkRenderer.setPostFXMode(initPFX);

            spPostFXMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    vkRenderer.setPostFXMode(pos);
                    updateSharpnessVis.run();
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        }

        String savedPFXInit = container != null ? container.getExtra("graphicsPostFXMode") : "";
        boolean dlsRestored = !savedPFXInit.isEmpty() && Integer.parseInt(savedPFXInit) == 1;
        int sharpVis = (fsrOn || dlsRestored) ? View.VISIBLE : View.GONE;
        if (lblSharpnessHeader != null) lblSharpnessHeader.setVisibility(sharpVis);
        if (sbSharpness        != null) sbSharpness.setVisibility(sharpVis);

        final String[] frameGenLabels = {"2x Interpolation", "Always On"};
        if (spFrameGenFPS != null) {
            ArrayAdapter<String> a = createSidebarSpinnerAdapter(frameGenLabels);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spFrameGenFPS.setAdapter(a);
        }
    }

    private void setupSidebarInputControls() {
        if (inputControlsView == null || inputControlsManager == null) return;

        Spinner spInputControlsProfile = findViewById(R.id.SPInputControlsProfile);
        Switch swShowTouchscreenControls = findViewById(R.id.SWShowTouchscreenControls);
        Switch swEnableTimeout = findViewById(R.id.SWEnableTouchscreenTimeout);
        Switch swEnableHaptics = findViewById(R.id.SWEnableTouchscreenHaptics);
        View btInputControlsSettings = findViewById(R.id.BTInputControlsSettings);
        SeekBar sbControlsOpacity = findViewById(R.id.SBControlsOpacity);

        if (spInputControlsProfile == null)
            return;

        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- " + getString(R.string.disabled) + " --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (inputControlsView.getProfile() != null && profile.id == inputControlsView.getProfile().id)
                    selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            ArrayAdapter<String> adapter = createSidebarSpinnerAdapter(profileItems.toArray(new String[0]));
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spInputControlsProfile.setAdapter(adapter);
            spInputControlsProfile.setSelection(selectedPosition, false);
        };
        loadProfileSpinner.run();

        if (swShowTouchscreenControls != null)
            swShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());
        if (swEnableTimeout != null)
            swEnableTimeout.setChecked(preferences.getBoolean("touchscreen_timeout_enabled", false));
        if (swEnableHaptics != null)
            swEnableHaptics.setChecked(preferences.getBoolean("touchscreen_haptics_enabled", false));

        if (sbControlsOpacity != null) {
            sbControlsOpacity.setValue(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY) * 100f);
            sbControlsOpacity.setOnValueChangeListener((sb, v) -> {
                float opacity = v / 100f;
                preferences.edit().putFloat("overlay_opacity", opacity).apply();
                inputControlsView.setOverlayOpacity(opacity);
                inputControlsView.invalidate();
            });
        }

        Runnable applySidebarInputControls = () -> {
            if (swShowTouchscreenControls != null) {
                boolean showControls = swShowTouchscreenControls.isChecked();
                inputControlsView.setShowTouchscreenControls(showControls);
                preferences.edit().putBoolean("show_touchscreen_controls_enabled", showControls).apply();
            }

            boolean isTimeoutEnabled = swEnableTimeout != null && swEnableTimeout.isChecked();
            boolean isHapticsEnabled = swEnableHaptics != null && swEnableHaptics.isChecked();
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
            editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
            editor.putInt("selected_profile_index", spInputControlsProfile.getSelectedItemPosition() - 1);
            editor.apply();

            int position = spInputControlsProfile.getSelectedItemPosition();
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles();
            if (position > 0 && position - 1 < profiles.size()) {
                showInputControls(profiles.get(position - 1));
            } else {
                hideInputControls();
            }

            if (isTimeoutEnabled && inputControlsView.getVisibility() == View.VISIBLE) {
                startTouchscreenTimeout();
            } else if (touchpadView != null) {
                touchpadView.setOnTouchListener(null);
            }
        };

        spInputControlsProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applySidebarInputControls.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        if (swShowTouchscreenControls != null)
            swShowTouchscreenControls.setOnCheckedChangeListener((buttonView, isChecked) -> applySidebarInputControls.run());
        if (swEnableTimeout != null)
            swEnableTimeout.setOnCheckedChangeListener((buttonView, isChecked) -> applySidebarInputControls.run());
        if (swEnableHaptics != null)
            swEnableHaptics.setOnCheckedChangeListener((buttonView, isChecked) -> applySidebarInputControls.run());

        if (btInputControlsSettings != null) {
            btInputControlsSettings.setOnClickListener(v -> {
                int position = spInputControlsProfile.getSelectedItemPosition();
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("edit_input_controls", true);
                intent.putExtra("selected_profile_id",
                        position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
                editInputControlsCallback = () -> {
                    hideInputControls();
                    inputControlsManager.loadProfiles(true);
                    loadProfileSpinner.run();
                    applySidebarInputControls.run();
                };
                controlsEditorActivityResultLauncher.launch(intent);
            });
        }
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);

        dialog.getWindow().setBackgroundDrawableResource(
                isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sProfile.setPopupBackgroundResource(
                isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

        int textColor = ContextCompat.getColor(this, isDarkMode ? R.color.white : R.color.black);
        ViewGroup dialogViewGroup = (ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content);
        setTextColorForDialog(dialogViewGroup, textColor);

        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- " + getString(R.string.disabled) + " --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (inputControlsView.getProfile() != null && profile.id == inputControlsView.getProfile().id)
                    selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        final CheckBox cbEnableTimeout = dialog.findViewById(R.id.CBEnableTimeout);
        cbEnableTimeout.setChecked(preferences.getBoolean("touchscreen_timeout_enabled", false));

        final CheckBox cbEnableHaptics = dialog.findViewById(R.id.CBEnableHaptics);
        cbEnableHaptics.setChecked(preferences.getBoolean("touchscreen_haptics_enabled", false));

        final Runnable updateProfile = () -> {
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            } else
                hideInputControls();
        };

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id",
                    position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
                updateProfile.run();
            };
            controlsEditorActivityResultLauncher.launch(intent);
        });

        dialog.setOnConfirmCallback(() -> {
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            boolean isTimeoutEnabled = cbEnableTimeout.isChecked();
            boolean isHapticsEnabled = cbEnableHaptics.isChecked();
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
            editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
            editor.apply();

            if (isTimeoutEnabled) {
                startTouchscreenTimeout();
            } else {
                touchpadView.setOnTouchListener(null);
            }
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            } else
                hideInputControls();
            updateProfile.run();
        });

        dialog.setOnCancelCallback(updateProfile::run);

        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void simulateConfirmInputControlsDialog() {

        boolean isShowTouchscreenControls = preferences.getBoolean("show_touchscreen_controls_enabled", false);

        inputControlsView.setShowTouchscreenControls(isShowTouchscreenControls);

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        boolean isHapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", false);

        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
        editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
        editor.apply();

        int selectedProfileIndex = preferences.getInt("selected_profile_index", -1);

        if (selectedProfileIndex >= 0 && selectedProfileIndex < inputControlsManager.getProfiles().size()) {

            ControlsProfile profile = inputControlsManager.getProfiles().get(selectedProfileIndex);
            showInputControls(profile);
        } else {

            hideInputControls();
        }

        if (isTimeoutEnabled && inputControlsView.getVisibility() == View.VISIBLE) {
            startTouchscreenTimeout();
        } else {
            touchpadView.setOnTouchListener(null);
        }

        Log.d("XServerDisplayActivity", "Input controls simulated confirmation executed.");
    }

    private void startTouchscreenTimeout() {
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
        }

        if (DISABLE_TOUCHSCREEN_AUTO_HIDE) {
            Log.d("XServerDisplayActivity", "Touchscreen auto-hide disabled; controls remain visible.");
            if (touchpadView != null) {
                touchpadView.setOnTouchListener(null);
            }
            if (inputControlsView != null && inputControlsView.getProfile() != null) {
                inputControlsView.setVisibility(View.VISIBLE);
            }
            return;
        }

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);

        if (isTimeoutEnabled) {
            if (inputControlsView != null && inputControlsView.getProfile() != null) {
                inputControlsView.setVisibility(View.VISIBLE);
            }
            Log.d("XServerDisplayActivity", "Timeout is enabled, setting up timeout logic.");

            touchpadView.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    if (inputControlsView != null && inputControlsView.getProfile() != null) {
                        inputControlsView.setVisibility(View.VISIBLE);
                    }

                    timeoutHandler.removeCallbacks(hideControlsRunnable);
                    timeoutHandler.postDelayed(hideControlsRunnable, 5000);
                }

                return false;
            });

            timeoutHandler.postDelayed(hideControlsRunnable, 5000);
        } else {
            Log.d("XServerDisplayActivity", "Timeout is disabled, controls will stay visible.");

            if (inputControlsView != null && inputControlsView.getProfile() != null) {
                inputControlsView.setVisibility(View.VISIBLE);
            }
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            touchpadView.setOnTouchListener(null);
        }
    }

    private void showInputControls(ControlsProfile profile) {
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
        }

        inputControlsView.setProfile(profile);
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    private void hideInputControls() {
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
        }

        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    private String getSelectedOpenGLDriver() {
        return "freedreno".equalsIgnoreCase(graphicsDriver) ? "freedreno" : "zink";
    }

    private void extractOpenGLDriver(File rootDir) {
        String selectedDriver = getSelectedOpenGLDriver();
        String installedDriver = container.getExtra("installedOpenGLDriver", "");

        if (!firstTimeBoot && selectedDriver.equals(installedDriver))
            return;

        Log.d("XServerDisplayActivity", "Installing OpenGL driver " + selectedDriver
                + " (installed: '" + installedDriver + "')");

        boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                "graphics_driver/opengl_" + selectedDriver + ".tzst", rootDir);
        if (!extracted) {
            Log.e("XServerDisplayActivity", "Failed to extract OpenGL driver " + selectedDriver);
            return;
        }

        container.putExtra("installedOpenGLDriver", selectedDriver);
        container.saveData();
    }

    private boolean isMesaGlVersionOverrideManual() {
        if (shortcut != null) {
            String shortcutEnvironment = shortcut.getExtra("envVars", "");
            EnvVars shortcutEnvVars = new EnvVars(shortcutEnvironment);
            if (shortcutEnvVars.has("MESA_GL_VERSION_OVERRIDE")) {
                return !"1".equals(shortcut.getExtra("autoMesaGlVersionOverride", "0"));
            }
        }

        if (container == null) return false;
        EnvVars containerEnvVars = new EnvVars(container.getEnvVars());
        return containerEnvVars.has("MESA_GL_VERSION_OVERRIDE")
                && !"1".equals(container.getExtra("autoMesaGlVersionOverride", "0"));
    }

    private void applyOpenGLDriverEnvVars() {
        boolean freedreno = "freedreno".equals(getSelectedOpenGLDriver());

        envVars.put("GALLIUM_DRIVER", freedreno ? "freedreno" : "zink");
        envVars.put("MESA_LOADER_DRIVER_OVERRIDE", freedreno ? "kgsl" : "zink");

        if (freedreno) {
            if (!envVars.has("MESA_GL_VERSION_OVERRIDE")) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");
            envVars.remove("ZINK_DESCRIPTORS");
            envVars.remove("ZINK_DEBUG");
            envVars.remove("ZINK_INLINE_UNIFORMS");
            if (!envVars.has("FD_MESA_DEBUG")) envVars.put("FD_MESA_DEBUG", "hiprio");
            if (!envVars.has("vblank_mode")) envVars.put("vblank_mode", "0");
        } else {
            if (!isMesaGlVersionOverrideManual()
                    && "3.3".equals(envVars.get("MESA_GL_VERSION_OVERRIDE"))) {
                envVars.remove("MESA_GL_VERSION_OVERRIDE");
            }
            if (!envVars.has("ZINK_DESCRIPTORS")) envVars.put("ZINK_DESCRIPTORS", "lazy");
            if (!envVars.has("ZINK_DEBUG")) envVars.put("ZINK_DEBUG", "compact");
        }
    }

    private void extractGraphicsDriverFiles() {
        String adrenoToolsDriverId = graphicsDriverConfig.get("version");

        Log.d("GraphicsDriverExtraction", "Adrenotools DriverID: " + adrenoToolsDriverId);

        File rootDir = imageFs.getRootDir();

        if (dxwrapper.contains("dxvk")) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
            String version = dxwrapperConfig.get("version");
            if (version.equals("1.11.1-sarek")) {
                Log.d("GraphicsDriverExtraction", "Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass");
                envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
            }
        } else {
            WineD3DConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
        }

        boolean useDRI3 = preferences.getBoolean("use_dri3", true);
        if (!useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "sw");
        }

        envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");

        File graphicsRuntimeMarker = new File(rootDir,
                "usr/lib/.winlator-graphics-runtime-c7474f7e-25b50a11-v2");
        if (firstTimeBoot || !graphicsRuntimeMarker.isFile()) {
            Log.d("XServerDisplayActivity", "Installing paired Pipetto wrapper and common graphics runtime");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper" + ".tzst",
                    rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers" + ".tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs" + ".tzst",
                    rootDir);
            FileUtils.writeString(graphicsRuntimeMarker,
                    "wrapper=c7474f7e;extra_libs=25b50a11;layers=9d57736d;opengl=split-v1");
        }

        extractOpenGLDriver(rootDir);

        if (!"System".equals(adrenoToolsDriverId)) {
            AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
            adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId);
        }

        String vulkanVersion = graphicsDriverConfig.get("vulkanVersion");
        String vulkanVersionPatch = GPUInformation.getVulkanVersion(adrenoToolsDriverId, this).split("\\.")[2];
        vulkanVersion = vulkanVersion + "." + vulkanVersionPatch;
        envVars.put("WRAPPER_VK_VERSION", vulkanVersion);

        String blacklistedExtensions = graphicsDriverConfig.get("blacklistedExtensions");
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions);

        String gpuName = graphicsDriverConfig.get("gpuName");
        String dxvkVersion = dxwrapperConfig.get("version");
        if (!gpuName.equals("Device") && !dxvkVersion.equals("1.11.1-sarek")) {
            envVars.put("WRAPPER_DEVICE_NAME", gpuName);
            envVars.put("WRAPPER_DEVICE_ID", WineD3DConfigDialog.getDeviceIdFromGPUName(this, gpuName));
            envVars.put("WRAPPER_VENDOR_ID", WineD3DConfigDialog.getVendorIdFromGPUName(this, gpuName));
        }

        String maxDeviceMemory = graphicsDriverConfig.get("maxDeviceMemory");
        if (maxDeviceMemory != null && Integer.parseInt(maxDeviceMemory) > 0)
            envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory);

        String presentMode = graphicsDriverConfig.get("presentMode");
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1");
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        String resourceType = graphicsDriverConfig.get("resourceType");
        envVars.put("WRAPPER_RESOURCE_TYPE", resourceType);

        String syncFrame = graphicsDriverConfig.get("syncFrame");
        if (syncFrame.equals("1"))
            envVars.put("MESA_VK_WSI_DEBUG", "forcesync");

        String disablePresentWait = graphicsDriverConfig.get("disablePresentWait");
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait);

        String bcnEmulation = graphicsDriverConfig.get("bcnEmulation");
        String bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType");

        switch (bcnEmulation) {
            case "auto" -> {
                if (bcnEmulationType.equals("compute") && GPUInformation.getVendorID(null, null) != 0x5143) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "1");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "3");
            }
            case "full" -> {
                if (bcnEmulationType.equals("compute") && GPUInformation.getVendorID(null, null) != 0x5143) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "0");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "2");
            }
            case "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0");
            default -> envVars.put("WRAPPER_EMULATE_BCN", "1");
        }

        String bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache");
        envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache);

        if (xServer.getSurfaceFormat() == Drawable.HAL_PIXEL_FORMAT_RGBA_8888) {
            envVars.put("WRAPPER_SURFACE_FORMAT", "rgba8");
        }

        if (!vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1");
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        boolean handledByWinHandler = false;
        boolean handledByTouchpadView = false;

        if (winHandler != null) {
            handledByWinHandler = winHandler.onGenericMotionEvent(event);
            if (handledByWinHandler) {

            }
        }

        if (touchpadView != null) {
            handledByTouchpadView = touchpadView.onExternalMouseEvent(event);
            if (handledByTouchpadView) {

            }
        }

        boolean handledBySuper = super.dispatchGenericMotionEvent(event);
        if (!handledBySuper) {

        }

        return handledByWinHandler || handledByTouchpadView || handledBySuper;
    }

    private static final int RECAPTURE_DELAY_MS = 10000;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE || event.getKeyCode() == KeyEvent.KEYCODE_HOME
                    || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_SELECT) {
                boolean handled = inputControlsView.onKeyEvent(event)
                        || (winHandler != null && winHandler.onKeyEvent(event))
                        && (xServer != null && xServer.keyboard.onKeyEvent(event));
                return true;
            }
        }

        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event)
                && xServer.keyboard.onKeyEvent(event)) ||
                (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private static final String TAG = "DXWrapperExtraction";

    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = { "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll",
                "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll" };

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("dxvk")) {
            Log.d(TAG, "Extracting DXVK wrapper files, version: " + dxwrapper);

            String dxvkWrapper = dxwrapper.split(";")[0];
            String vkd3dWrapper = dxwrapper.split(";")[1];
            String ddrawrapper = dxwrapper.split(";")[2];

            ContentProfile dxvkProfile = contentsManager.getProfileByEntryName(dxvkWrapper);
            if (dxvkProfile != null) {
                Log.d(TAG, "Applying user-defined DXVK content profile: " + dxvkWrapper);
                contentsManager.applyContent(dxvkProfile);
            } else {
                Log.d(TAG, "Extracting fallback DXVK .tzst archive: " + dxvkWrapper);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxvkWrapper + ".tzst",
                        windowsDir, onExtractFileListener);

                if (compareVersion(dxvkWrapper, "2.4") < 0) {
                    Log.d(TAG, "Extracting d8vk as part of DXVK version " + dxvkWrapper);
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                            "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir, onExtractFileListener);
                }
            }

            if (vkd3dWrapper.contains("None")) {
                Log.d(TAG, "No VKD3D has been selected, restoring original d3d12");
                restoreOriginalDllFiles(new String[] { "d3d12.dll", "d3d12core.dll" });
            } else {
                ContentProfile vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper);
                if (vkd3dProfile != null) {
                    Log.d(TAG, "Applying user-defined VKD3D content profile: " + vkd3dWrapper);
                    contentsManager.applyContent(vkd3dProfile);
                } else {
                    Log.d(TAG, "Extracting fallback VKD3D .tzst archive: " + vkd3dWrapper);
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                            "dxwrapper/" + vkd3dWrapper + ".tzst", windowsDir, onExtractFileListener);
                }
            }

            Log.d(TAG, "Extracting nglide wrapper");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir,
                    onExtractFileListener);

            if (ddrawrapper.contains("None")) {
                Log.d(TAG, "No DDRaw wrapper has been selected, restoring original ddraw files");
                restoreOriginalDllFiles(new String[] { "ddraw.dll", "d3dimm.dll" });
            } else {
                if (ddrawrapper.equals("cnc-ddraw"))
                    envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");

                Log.d(TAG, "Extracting ddrawrapper " + ddrawrapper);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst",
                        windowsDir, onExtractFileListener);
            }

            Log.d(TAG, "Finished extraction of DXVK wrapper files, version: " + dxwrapper);
        } else if (dxwrapper.contains("wined3d")) {
            Log.d(TAG, "Restoring original DLL files for wined3d.");
            restoreOriginalDllFiles(dlls);
        }
    }

    private static int compareVersion(String varA, String varB) {
        int[] a = parseSemverLoose(varA);
        int[] b = parseSemverLoose(varB);

        if (a[0] != b[0])
            return a[0] - b[0];
        if (a[1] != b[1])
            return a[1] - b[1];
        return a[2] - b[2];
    }

    private static final Pattern SEMVER_LOOSE = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static int[] parseSemverLoose(String s) {
        if (s == null)
            return new int[] { 0, 0, 0 };

        Matcher m = SEMVER_LOOSE.matcher(s);

        String g1 = null, g2 = null, g3 = null;
        while (m.find()) {
            g1 = m.group(1);
            g2 = m.group(2);
            g3 = m.group(3);
        }

        if (g1 == null || g2 == null) {
            return new int[] { 0, 0, 0 };
        }

        int major = safeParseInt(g1);
        int minor = safeParseInt(g2);
        int patch = safeParseInt(g3);
        return new int[] { major, minor, patch };
    }

    private static int safeParseInt(String s) {
        if (s == null || s.isEmpty())
            return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void extractWinComponentFiles() {
        Log.d("XServerDisplayActivity", "Extracting WinComponents");
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX + "/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(
                    FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents())
                    : container.getWinComponents();

            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(
                    container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1]) && !firstTimeBoot)
                    continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                            "wincomponents/" + identifier + ".tzst", windowsDir, onExtractFileListener);
                } else {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname + ".dll" : dlname);
                    }
                }
                Log.d("XServerDisplayActivity",
                        "Setting wincomponent " + identifier + " to " + String.valueOf(useNative));
                WineUtils.overrideWinComponentDlls(this, container, identifier, useNative);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, this);
            }

            if (!dlls.isEmpty())
                restoreOriginalDllFiles(dlls.toArray(new String[0]));
        } catch (JSONException e) {
        }
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");
        File system32dlls = null;
        File syswow64dlls = null;

        if (wineInfo.isArm64EC())
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/aarch64-windows");
        else
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/x86_64-windows");

        syswow64dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");

        for (String dll : dlls) {
            File srcFile = new File(system32dlls, dll);
            File dstFile = new File(windowsDir, "system32/" + dll);
            FileUtils.copy(srcFile, dstFile);
            srcFile = new File(syswow64dlls, dll);
            dstFile = new File(windowsDir, "syswow64/" + dll);
            FileUtils.copy(srcFile, dstFile);
        }
    }

    private String getWineStartCommand() {

        EnvVars envVars = getOverrideEnvVars();

        String args = "";

        if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " " + execArgs : "";

            if (shortcut.path.endsWith(".lnk")) {
                args += "\"" + shortcut.path + "\"" + execArgs;
            } else {
                String fullPath = shortcut.path.replace("\"", "");
                String exeDir;
                String filename;

                if (fullPath.contains("\\")) {
                    int lastSlash = fullPath.lastIndexOf("\\");
                    if (lastSlash != -1) {
                        exeDir = fullPath.substring(0, lastSlash);
                        filename = fullPath.substring(lastSlash + 1);
                    } else {
                        exeDir = "D:\\";
                        filename = fullPath;
                    }
                } else {
                    exeDir = FileUtils.getDirname(fullPath);
                    filename = FileUtils.getName(fullPath);
                }

                int dotIndex = filename.lastIndexOf(".");
                int spaceIndex = (dotIndex != -1) ? filename.indexOf(" ", dotIndex) : -1;

                if (spaceIndex != -1) {
                    execArgs = filename.substring(spaceIndex + 1) + execArgs;
                    filename = filename.substring(0, spaceIndex);
                }

                args += "/dir " + StringUtils.escapeDOSPath(exeDir) + " \"" + filename + "\"" + execArgs;
            }
        } else {

            if (envVars.has("EXTRA_EXEC_ARGS")) {
                args += " " + envVars.get("EXTRA_EXEC_ARGS");
                envVars.remove("EXTRA_EXEC_ARGS");
            } else {
                args += "\"wfm.exe\"";
            }
        }

        String command = "winhandler.exe " + args;

        return command;
    }

    private String getExecutable() {
        String filename = "wfm.exe";
        if (shortcut != null && shortcut.path != null) {
            String cleanPath = shortcut.path.replace("\"", "");
            int lastSlash = cleanPath.lastIndexOf('/');
            int lastBackslash = cleanPath.lastIndexOf('\\');
            int lastSeparator = Math.max(lastSlash, lastBackslash);
            if (lastSeparator != -1) {
                filename = cleanPath.substring(lastSeparator + 1);
            } else {
                filename = cleanPath;
            }
        }
        return filename;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerRendererView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) {
            overrideEnvVars = new EnvVars();
        }
        return overrideEnvVars;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = imageFs.getRootDir();
            File userRegFile = new File(rootDir, ImageFs.WINEPREFIX + "/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals("alsa")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                } else if (audioDriver.equals("pulseaudio") || audioDriver.equals("pulse-audio-gn")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }


    private void applyGameRefreshRateUnlock() {
        final String x11DriverKey = "Software\\Wine\\X11 Driver";
        final boolean xrandrCapable = isSelectedWineXrandrCapable();
        File userRegFile = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (xrandrCapable) {
                registryEditor.setStringValue(x11DriverKey, "EmulateModelist", "Y");
                registryEditor.setStringValue(x11DriverKey, "EmulateModeset", "Y");
            } else {
                registryEditor.removeValue(x11DriverKey, "EmulateModelist");
                registryEditor.removeValue(x11DriverKey, "EmulateModeset");
            }
        }

        Log.d("XServerDisplayActivity", "RandR Wine mode emulation: "
                + (xrandrCapable ? "disabled" : "unchanged (layer has no XRandR)"));
    }

    private boolean isSelectedWineXrandrCapable() {
        if (wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) return false;

        String cacheKey = wineInfo.identifier() + "@" + wineInfo.path;
        synchronized (WINE_XRANDR_SUPPORT_CACHE) {
            Boolean cached = WINE_XRANDR_SUPPORT_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }

        File winex11 = new File(wineInfo.path, "lib/wine/aarch64-unix/winex11.so");
        if (!winex11.isFile()) {
            winex11 = new File(wineInfo.path, "lib/wine/x86_64-unix/winex11.so");
        }

        boolean capable = winex11.isFile()
                && fileContainsAny(winex11, "libXrandr.so", "RRQueryVersion")
                && !fileContainsAny(winex11, "XRandR support not compiled in.");

        synchronized (WINE_XRANDR_SUPPORT_CACHE) {
            WINE_XRANDR_SUPPORT_CACHE.put(cacheKey, capable);
        }
        return capable;
    }

    private static boolean fileContainsAny(File file, String... markers) {
        byte[][] needles = new byte[markers.length][];
        int maxNeedle = 0;
        for (int i = 0; i < markers.length; i++) {
            needles[i] = markers[i].getBytes(StandardCharsets.US_ASCII);
            maxNeedle = Math.max(maxNeedle, needles[i].length);
        }

        final int chunkSize = 64 * 1024;
        byte[] buffer = new byte[chunkSize + maxNeedle];
        try (FileInputStream input = new FileInputStream(file)) {
            int carry = 0;
            int read;
            while ((read = input.read(buffer, carry, chunkSize)) != -1) {
                int length = carry + read;
                for (byte[] needle : needles) {
                    if (indexOfBytes(buffer, length, needle) >= 0) return true;
                }

                carry = Math.min(length, maxNeedle - 1);
                System.arraycopy(buffer, length - carry, buffer, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static int indexOfBytes(byte[] haystack, int haystackLength, byte[] needle) {
        if (needle.length == 0 || needle.length > haystackLength) return -1;

        outer:
        for (int i = 0; i <= haystackLength - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern_common.tzst", rootDir);
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void assignTaskAffinity(Window window) {
        if (taskAffinityMask == 0 || taskAffinityMaskWoW64 == 0)
            return;
        int processId = window.getProcessId();
        String className = window.getClassName();
        int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        } else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    private void changeFrameRatingVisibility(Window window, Property property) {

        String propName = (property != null) ? property.nameAsString() : null;

        if (property != null) {
            if (activeRendererWindowId == -1 && propName.contains("_MESA_DRV")) {
                activeRendererWindowId = window.id;
            }

            if (propName.contains("_MESA_DRV_ENGINE_NAME")
                    && (activeRendererWindowId == -1 || window.id == activeRendererWindowId)) {
                lastRendererName = property.toString();
            }
        } else if (activeRendererWindowId != -1 && window.id == activeRendererWindowId) {

            activeRendererWindowId = -1;
            lastRendererName = null;
        }

        if (classicHud == null && modernHud == null) return;

        if (property != null) {
            if (frameRatingWindowId == -1 && propName.contains("_MESA_DRV")) {
                frameRatingWindowId = window.id;
                if (xServerView != null) xServerView.setFpsWindowId(window.id);
                Log.d("XServerDisplayActivity", "Showing hud for Window " + window.getName());
                if (classicHud != null) classicHud.update();

                if (modernHud != null) {
                    final String nameToPass = lastRendererName;
                    runOnUiThread(() -> modernHud.onRendererDetected(nameToPass));
                }
            }

            if (propName.contains("_MESA_DRV_ENGINE_NAME") && window.id == frameRatingWindowId) {
                String rendererName = property.toString();
                if (classicHud != null) runOnUiThread(() -> classicHud.setRenderer(rendererName));
                if (modernHud != null) runOnUiThread(() -> modernHud.setRenderer(rendererName));
            }
            if (propName.contains("_MESA_DRV_GPU_NAME") && window.id == frameRatingWindowId) {
                String gpuName = property.toString();
                if (classicHud != null) runOnUiThread(() -> classicHud.setGpuName(gpuName));
                if (modernHud != null) runOnUiThread(() -> modernHud.setGpuName(gpuName));
            }
        } else if (frameRatingWindowId != -1 && window.id == frameRatingWindowId) {

            frameRatingWindowId = -1;
            if (xServerView != null) xServerView.setFpsWindowId(-1);
            Log.d("XServerDisplayActivity", "Hiding hud for Window " + window.getName());
            if (classicHud != null) runOnUiThread(() -> {
                classicHud.setVisibility(View.GONE);
                classicHud.reset();
            });
            if (modernHud != null) runOnUiThread(() -> modernHud.onRendererGone());
        }
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

}
