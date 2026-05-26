package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
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
import com.winlator.cmod.renderer.VulkanRenderer;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.HudDataSource;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.LogView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.TaskManagerDialog;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.AHBSocketServerComponent;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.DirectCompositorComponent;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.xenvironment.components.XServerComponent;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;

public class XServerDisplayActivity extends AppCompatActivity {
    public static String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static int NOTIFICATION_ID = -1;
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    private WinlatorHUD frameRating = null;
    private HudDataSource hudDataSource = null;
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
    private WineRequestHandler wineRequestHandler;
    private float globalCursorSpeed = 1.0f;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private int frameRatingWindowId = -1;
    private boolean cursorLock;
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean isRelativeMouseMovement = false;
    private boolean isMouseDisabled = false;
    private Handler handler;
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;
    private boolean isDarkMode;
    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private EnvVars overrideEnvVars;

    private CheckBox cbFps, cbGpu, cbCpuRam, cbBattTemp, cbGraph, cbRenderer, cbRam, cbLatency;

    private static final int[] NATIVE_FPS_VALUES = { 0, 30, 45, 60, 90, 120 };
    private Spinner spNativeFPS;
    private static final int[] VK_PRESENT_MODE_VALUES = { 2, 0, 1, 3 };
    private static final int[] EFFECT_UPSCALER_VALUES = {
        VulkanRenderer.EFFECT_FSR,
        VulkanRenderer.EFFECT_DLS
    };
    private static final int[] EFFECT_COLOR_VALUES = {
        VulkanRenderer.EFFECT_NONE,
        VulkanRenderer.EFFECT_CRT,
        VulkanRenderer.EFFECT_HDR,
        VulkanRenderer.EFFECT_NATURAL
    };
    private Switch swEnableFSR;
    private Spinner spUpscalerMode;
    private Spinner spColorMode;
    private View btSaveGraphicsPreset;
    private com.winlator.cmod.widget.SeekBar sbSharpness;
    private LinearLayout llStandardOptions;
    private LinearLayout llFrameGenOptions;

    private Switch swHudMaster;
    private com.winlator.cmod.widget.SeekBar sbHudScale, sbHudAlpha;

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
    private float pickHighestRefreshRate() {
        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode[] modes = display.getSupportedModes();
        
        float maxRefresh = 0f;
        
        for (android.view.Display.Mode mode : modes) {
            if (mode.getRefreshRate() > maxRefresh)
                maxRefresh = mode.getRefreshRate();
        }
        
        return maxRefresh;
    } 

    private void applyRendererRefreshRatePreference(int refreshRateLimit) {
        float targetRefresh = refreshRateLimit > 0
            ? (float) refreshRateLimit
            : pickHighestRefreshRate();
        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.preferredRefreshRate = targetRefresh;
        getWindow().setAttributes(params);
        if (Build.VERSION.SDK_INT >= 30) {
            trySetViewFrameRate(getWindow().getDecorView(), targetRefresh);
            if (xServerView != null) trySetViewFrameRate(xServerView, targetRefresh);
        }
    }

    private void trySetViewFrameRate(View view, float targetRefresh) {
        if (view == null || Build.VERSION.SDK_INT < 30) return;
        try {
            java.lang.reflect.Method m = View.class.getMethod(
                "setFrameRate", float.class, int.class, int.class
            );
            m.invoke(
                view,
                targetRefresh,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                android.view.Surface.CHANGE_FRAME_RATE_ALWAYS
            );
        } catch (Exception ignored) {}
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
        isDarkMode = preferences.getBoolean("dark_mode", true);

        boolean xinputDisabledFromShortcut = false;

        handler = new Handler(Looper.getMainLooper());

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", true);

        hideControlsRunnable = () -> {
            if (isTimeoutEnabled) {
                inputControlsView.setVisibility(View.GONE);
            }
        };

        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        imageFs = ImageFs.find(this);

        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            for (int i = 0; i < 4; i++) {
                File eventFile = new File(devInputDir, "event" + i);
                if (eventFile.exists()) eventFile.delete();
            }
            try { new File(devInputDir, "event0").createNewFile(); } catch (Exception e) {}
        }

        winHandler = new WinHandler(this);
        winHandler.setFakeInputPath(devInputDir.getAbsolutePath());

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));

        String shortcutPath = getIntent().getStringExtra("shortcut_path");

        int containerId = getIntent().getIntExtra("container_id", 0);
        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
        }

        container = containerManager.getContainerById(containerId);

        if (container == null) {
            finish();
            return;
        }

        containerManager.activateContainer(container);

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }

        taskAffinityMask = (short) ProcessHelper.getAffinityMask(container.getCPUList(true));
        taskAffinityMaskWoW64 = (short) ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));

        if (shortcut != null) {
            taskAffinityMask = (short) ProcessHelper.getAffinityMask(shortcut.getExtra("cpuList", container.getCPUList(true)));
            taskAffinityMaskWoW64 = taskAffinityMask;
        }

        String wmClass = shortcut != null ? shortcut.getExtra("wmClass", "") : "";
        firstTimeBoot = container.getExtra("appVersion").isEmpty();

        String wineVersion = container.getWineVersion();
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getBoolean("enable_box64_logs", false);
if (enableLogs) {
    LogView.setFilename(getExecutable());
    ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
}

        graphicsDriver = container.getGraphicsDriver();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriver = container.getAudioDriver();
        emulator = container.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = container.getDXWrapper();
        String dxwrapperConfig = container.getDXWrapperConfig();
        screenSize = container.getScreenSize();
        winHandler.setInputType((byte) container.getInputType());
        lc_all = container.getLC_ALL();

        if (shortcut != null) {
            graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
            emulator = shortcut.getExtra("emulator", container.getEmulator());
            dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
            lc_all = shortcut.getExtra("lc_all", container.getLC_ALL());
            String inputType = shortcut.getExtra("inputType");
            if (!inputType.isEmpty()) winHandler.setInputType(Byte.parseByte(inputType));
            String xinputDisabledString = shortcut.getExtra("disableXinput", "false");
            xinputDisabledFromShortcut = parseBoolean(xinputDisabledString);
            winHandler.setXInputDisabled(xinputDisabledFromShortcut);
            String sharpnessEffect = shortcut.getExtra("sharpnessEffect", "None");
            if (!sharpnessEffect.equals("None")) {
                double sharpnessLevel = Double.parseDouble(shortcut.getExtra("sharpnessLevel", "100"));
                double sharpnessDenoise = Double.parseDouble(shortcut.getExtra("sharpnessDenoise", "100"));
                vkbasaltConfig = "effects=" + sharpnessEffect.toLowerCase() + ";" + "casSharpness=" + sharpnessLevel / 100 + ";" + "dlsSharpness=" + sharpnessLevel / 100  + ";" + "dlsDenoise=" + sharpnessDenoise / 100 + ";" + "enableOnLaunch=True";
            }
        }

        this.graphicsDriverConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);
        this.dxwrapperConfig = DXVKConfigDialog.parseConfig(dxwrapperConfig);

        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/")) return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(new ScreenInfo(screenSize));
        xServer.setWinHandler(winHandler);

        boolean[] winStarted = {false};

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }
                if (frameRating != null && frameRatingWindowId == window.id) {
                    frameRating.onFrame();
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
            InputStream in = null;
            InputStream finalIn = in;
            MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                @Override
                public void onSuccess(SF2Soundbank soundbank) {
                    midiHandler = new MidiHandler();
                    midiHandler.setSoundBank(soundbank);
                    midiHandler.start();
                }
                @Override
                public void onFailed(Exception e) {
                    try { finalIn.close(); } catch (Exception e2) {}
                }
            };
            try {
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                    MidiManager.load(in, callback);
                } else
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
            } catch (Exception e) {}
        }

        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        createNotifcationChannel();

        Intent notificationIntent = new Intent(this, XServerDisplayActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
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
            if (controlsProfile.isEmpty()) {
                simulateConfirmInputControlsDialog();
            }
            Executors.newSingleThreadExecutor().execute(() -> {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
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
        } else runnable.run();
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
            } catch (IOException | NumberFormatException e) {}
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
        int actionButton = event.getActionButton();
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement()) xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement()) xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement()) xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE); 
                }
                break;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement()) xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement()) xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement()) xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE); 
                }
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                if (xServer.isRelativeMouseMovement()) {
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
                    if (xServer.getRenderer() != null) {
                        xServer.getRenderer().updateVisualCursorPosition((int) transformedPoint[0], (int) transformedPoint[1]);
                    }
                } else xServer.injectPointerMoveDelta((int)transformedPoint[0], (int)transformedPoint[1]);
                break;
            case MotionEvent.ACTION_SCROLL:
                float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY <= -1.0f) {
                    if (xServer.isRelativeMouseMovement()) xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int)scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                } else if (scrollY >= 1.0f) {
                    if (xServer.isRelativeMouseMovement()) xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0,(int)scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                }
                break;
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
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
            xServerView.queueEvent(() -> xServerView.getRenderer().updateScene());
        }
        if (hudDataSource != null && frameRating != null && frameRating.isSavedVisible()) {
            try { hudDataSource.start(); } catch (Exception ignored) {}
        }
        ProcessHelper.resumeAllWineProcesses();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!isInPictureInPictureMode()) {
            if (environment != null) {
                environment.onPause();
                xServerView.onPause();
            }
        }
        ProcessHelper.pauseAllWineProcesses();
    }
    private void exit() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        preloaderDialog.showOnUiThread(R.string.shutdown);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (hudDataSource != null) hudDataSource.stop();
                if (midiHandler != null) midiHandler.stop();
                if (environment != null) environment.stopEnvironmentComponents();
                if (preloaderDialog != null && preloaderDialog.isShowing()) preloaderDialog.closeOnUiThread();
                if (winHandler != null) winHandler.stop();
                if (wineRequestHandler != null) wineRequestHandler.stop();
                ProcessHelper.terminateAllWineProcesses();

                /* === FIX: pre-existing UI-thread freeze on sidebar exit ===
                 *
                 * The original code spun in a 100%-CPU busy-loop on the UI
                 * thread for up to 1.5 s, polling listRunningWineProcesses()
                 * (which scans /proc) as fast as the scheduler allowed.
                 * Result: the screen visibly froze every time the user tapped
                 * the sidebar's exit button — sometimes long enough that
                 * Android nearly fired an ANR.
                 *
                 * Two problems with the original:
                 *   1. No Thread.sleep between polls → CPU burn at 100%.
                 *   2. Entire wait happened on the UI thread → screen frozen.
                 *
                 * Fix: spin off a worker thread that does the wait with a
                 * 50 ms sleep between polls (≤ 30 iterations, ~1.5 s max), then
                 * posts the cleanup back to the UI thread via runOnUiThread.
                 * The UI thread stays responsive throughout; the preloader
                 * "Shutting down…" dialog stays visible until the worker
                 * completes; the restart fires from the UI thread as before. */
                new Thread(() -> {
                    long start = System.currentTimeMillis();
                    while (!ProcessHelper.listRunningWineProcesses().isEmpty()) {
                        if (System.currentTimeMillis() - start >= 1500) break;
                        try { Thread.sleep(50); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                    runOnUiThread(() -> {
                        preloaderDialog.closeOnUiThread();
                        int selectedMenuItemId = shortcut != null
                            ? R.id.main_menu_shortcuts
                            : R.id.main_menu_containers;
                        AppUtils.restartApplication(getApplicationContext(), selectedMenuItemId);
                    });
                }, "WineProcessWaitThread").start();
            }
        }, 1000);
    }
    @Override
    protected void onDestroy() {
        if (hudDataSource != null) hudDataSource.stop();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (hudDataSource != null) hudDataSource.stop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            if (hudDataSource != null) hudDataSource.stop();
        }
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
            else drawerLayout.closeDrawers();
        }
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
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
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

        String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container);
        
        int inputType = container.getInputType();
        if (shortcut != null) {
            String shortcutInputType = shortcut.getExtra("inputType");
            if (!shortcutInputType.isEmpty()) inputType = Byte.parseByte(shortcutInputType);
        }
        boolean dinputEnabled = (inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT;
        
        boolean exclusiveXInput = container.isExclusiveXInput();
        if (shortcut != null) {
            String extra = shortcut.getExtra("exclusiveXInput");
            if (!extra.isEmpty()) exclusiveXInput = extra.equals("1");
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
        if (containerDataChanged) container.saveData();
    }
    private void setupXEnvironment() throws PackageManager.NameNotFoundException {
        envVars.put("LC_ALL", lc_all);
        envVars.put("WINEPREFIX", imageFs.wineprefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty()
                ? "+" + wineDebugChannels.replace(",", ",+")
                : "-all"
        );

        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());

        guestProgramLauncherComponent = new GuestProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(container.getWineVersion()),
                shortcut
        );

        if (container != null) {
            guestProgramLauncherComponent.setContainer(this.container);
            guestProgramLauncherComponent.setWineInfo(this.wineInfo);
            String guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " + getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);
            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");
            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) bindingPaths.add(drive[1]);
            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));
            guestProgramLauncherComponent.setBox64Preset(shortcut != null ? shortcut.getExtra("box64Preset", container.getBox64Preset()) : container.getBox64Preset());
            guestProgramLauncherComponent.setFEXCorePreset(shortcut != null ? shortcut.getExtra("fexcorePreset", container.getFEXCorePreset()) : container.getFEXCorePreset());
        }

        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear(); 
        }

        environment = new XEnvironment(this, imageFs);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        DirectCompositorComponent directCompositor = new DirectCompositorComponent(
                xServerView.getRenderer(), 4,
                xServer.screenInfo.width, xServer.screenInfo.height);
        environment.addComponent(directCompositor);
        environment.addComponent(new AHBSocketServerComponent(
                UnixSocketConfig.createSocket(rootPath, AHBSocketServerComponent.AHB_SOCKET_PATH),
                directCompositor));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)));

        if (audioDriver.equals("alsa")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)));
        } else if (audioDriver.equals("pulseaudio")) {
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(new PulseAudioComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)));
        }
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
        environment.addComponent(guestProgramLauncherComponent);

        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {}

        environment.startEnvironmentComponents();
        winHandler.start();
        if (wineRequestHandler != null) wineRequestHandler.start();
        dxwrapperConfig = null;
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final VulkanRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);

        {
            String rdrDriverId = shortcut != null ? shortcut.getRendererDriverId()
                    : (container != null ? container.getRendererDriverId() : "");
            if (rdrDriverId == null || rdrDriverId.isEmpty() || rdrDriverId.equalsIgnoreCase("system")) {
                String graphicsDriverConfigValue = shortcut != null
                        ? shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig())
                        : (container != null ? container.getGraphicsDriverConfig() : "");
                HashMap<String, String> wrapperConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfigValue);
                String wrapperDriverId = wrapperConfig.get("version");
                if (wrapperDriverId != null && !wrapperDriverId.isEmpty() && !wrapperDriverId.equalsIgnoreCase("system")) {
                    rdrDriverId = wrapperDriverId;
                } else {
                    rdrDriverId = "";
                }
            }
            if (rdrDriverId != null && !rdrDriverId.isEmpty() && !rdrDriverId.equalsIgnoreCase("system")) {
                try {
                    String filesDir = getFilesDir().getAbsolutePath();
                    String driverPath = filesDir + "/contents/adrenotools/" + rdrDriverId + "/";
                    AdrenotoolsManager atm = new AdrenotoolsManager(this);
                    String libraryName = atm.getLibraryName(rdrDriverId);
                    String nativeLibDir = AppUtils.getNativeLibDir(this);
                    if (!libraryName.isEmpty()) renderer.setDriverInfo(driverPath, libraryName, nativeLibDir);
                } catch (Exception e) {}
            }
            String pm = shortcut != null ? shortcut.getRendererPresentMode()
                : (container != null ? container.getRendererPresentMode() : "fifo");
            renderer.setVkPresentMode(com.winlator.cmod.contentdialog.RendererOptionsDialog.toVkPresentMode(pm));
            int fm = shortcut != null ? shortcut.getRendererFilterMode()
                : (container != null ? container.getRendererFilterMode() : 0);
            renderer.setFilterMode(fm);
            /* 0 = "Device Refresh Rate". Default-fallback for a missing
             * container was 60, which capped DAC at half-rate on 120 Hz
             * panels (Window.preferredRefreshRate → SF apply rate → AHB
             * slot-recycle rate). Matches Container.rendererRefreshRateLimit
             * default of 0. */
            int refreshRateLimit = shortcut != null ? shortcut.getRendererRefreshRateLimit()
                : (container != null ? container.getRendererRefreshRateLimit() : 0);
            applyRendererRefreshRatePreference(refreshRateLimit);
            renderer.setRefreshRateLimit(refreshRateLimit);
            boolean swapRB = shortcut != null ? shortcut.getRendererSwapRB()
                : (container != null && container.getRendererSwapRB());
     		renderer.setSwapRB(swapRB);
            int sidebarFpsLimit = shortcut != null ? parseIntOrDefault(shortcut.getExtra("sidebarFpsLimit", "0"), 0) : 0;
            renderer.setFpsLimit(Math.max(0, sidebarFpsLimit));
        }

        if (shortcut != null) renderer.setUnviewableWMClasses("explorer.exe");

        renderer.setGraphicsDriver(graphicsDriver);

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer, timeoutHandler, hideControlsRunnable);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMouseEnabled(!isMouseDisabled);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
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
        magnifierView = new MagnifierView(this);
        magnifierView.setVisibility(View.GONE);
        
        final float[] magnifierZoom = {1.0f};
        magnifierView.setZoomButtonCallback((Float delta) -> {
            magnifierZoom[0] = Mathf.clamp(magnifierZoom[0] + delta, 1.0f, 3.0f);
            magnifierView.setZoomValue(magnifierZoom[0]);
            xServerView.setScaleX(magnifierZoom[0]);
            xServerView.setScaleY(magnifierZoom[0]);
        });
        
        magnifierView.setHideButtonCallback(() -> {
            magnifierView.setVisibility(View.GONE);
            magnifierZoom[0] = 1.0f;
            magnifierView.setZoomValue(1.0f);
            xServerView.setScaleX(1.0f);
            xServerView.setScaleY(1.0f);
        });
        rootView.addView(magnifierView);
        
        inputControlsView = new InputControlsView(this, timeoutHandler, hideControlsRunnable);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        startTouchscreenTimeout();
        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) startTouchscreenTimeout();

        frameRating = new WinlatorHUD(this);
        hudDataSource = new HudDataSource(this);
        frameRating.setDataSource(hudDataSource);
        boolean hasSavedPref = frameRating.hasSavedPref();
        boolean showInitial = hasSavedPref ? frameRating.isSavedVisible()
                            : (container != null && container.isShowFPS());
        if (showInitial) { frameRating.enableByUser(); hudDataSource.start(); }
        else frameRating.disableByUser(false);
        rootView.addView(frameRating);

        xServerView.getRenderer().setFrameRating(frameRating);

        setupLeftSidebar();
        
        View btnLogs = findViewById(R.id.BTItemLogs);
        if (btnLogs != null) {
            boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getBoolean("enable_box64_logs", false);
            btnLogs.setVisibility(enableLogs ? View.VISIBLE : View.GONE);
            btnLogs.setOnClickListener(v -> {
                if (debugDialog != null) debugDialog.show();
                if (drawerLayout != null) drawerLayout.closeDrawers();
            });
        }
        
        String shortcutFullscreenStretched = shortcut != null ? shortcut.getExtra("fullscreenStretched") : null;
        boolean shouldStretch = false;

        if (shortcut != null && shortcutFullscreenStretched != null) shouldStretch = shortcutFullscreenStretched.equals("1");
        else if (container != null && container.isFullscreenStretched()) shouldStretch = true;

        if (shouldStretch) {
            renderer.toggleFullscreen();
            touchpadView.toggleFullscreen();
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchpadView.setSimTouchScreen(simTouchScreen.equals("1"));
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }

private void setupLeftSidebar() {
        findViewById(R.id.BTItemFPS).setOnClickListener(v -> toggleSubMenu(findViewById(R.id.LLSubFPS)));
        findViewById(R.id.BTItemMouse).setOnClickListener(v -> toggleSubMenu(findViewById(R.id.LLSubMouse)));
        findViewById(R.id.BTItemGraphics).setOnClickListener(v -> toggleSubMenu(findViewById(R.id.LLSubGraphics)));
        findViewById(R.id.BTItemScreen).setOnClickListener(v -> toggleSubMenu(findViewById(R.id.LLSubScreen)));

        findViewById(R.id.BTItemInput).setOnClickListener(v -> toggleSubMenu(findViewById(R.id.LLSubInput)));

        findViewById(R.id.BTSubInputControls).setOnClickListener(v -> {
            showInputControlsDialog();
            drawerLayout.closeDrawers();
        });

        findViewById(R.id.BTSubVibration).setOnClickListener(v -> {
            showVibrationDialog();
            drawerLayout.closeDrawers();
        });

        findViewById(R.id.BTItemPause).setOnClickListener(v -> {
            isPaused = !isPaused;
            if (isPaused) ProcessHelper.pauseAllWineProcesses();
            else ProcessHelper.resumeAllWineProcesses();
            ((TextView)findViewById(R.id.TVPause)).setText(isPaused ? "Resume" : "Pause / Resume");
        });

        findViewById(R.id.BTItemPipMode).setOnClickListener(v -> {
            enterPictureInPictureMode();
            drawerLayout.closeDrawers();
        });

        findViewById(R.id.BTItemToggleFullscreen).setOnClickListener(v -> {
            xServerView.getRenderer().toggleFullscreen();
            touchpadView.toggleFullscreen();
            drawerLayout.closeDrawers();
        });

        findViewById(R.id.BTItemMagnifier).setOnClickListener(v -> {
            if (magnifierView != null) {
                if (magnifierView.getVisibility() == View.VISIBLE) magnifierView.setVisibility(View.GONE);
                else magnifierView.setVisibility(View.VISIBLE);
            }
            drawerLayout.closeDrawers();
        });

        findViewById(R.id.BTItemTaskManager).setOnClickListener(v -> {
            new TaskManagerDialog(this).show();
            drawerLayout.closeDrawers();
        });

        findViewById(R.id.BTItemExit).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            exit();
        });

        findViewById(R.id.BTSubKeyboard).setOnClickListener(v -> {
            AppUtils.showKeyboard(this);
            drawerLayout.closeDrawers();
        });
        
        Switch swRelativeMouse = findViewById(R.id.SWRelativeMouse);
        if (swRelativeMouse != null) {
            swRelativeMouse.setOnCheckedChangeListener((cb, checked) -> {
                isRelativeMouseMovement = checked;
                xServer.setRelativeMouseMovement(isRelativeMouseMovement);
            });
        }

        Switch swDisableMouse = findViewById(R.id.SWDisableMouse);
        if (swDisableMouse != null) {
            swDisableMouse.setOnCheckedChangeListener((cb, checked) -> {
                isMouseDisabled = checked;
                touchpadView.setMouseEnabled(!isMouseDisabled);
            });
        } 

        setupGraphicsSidebar(); 
    }

    private void showVibrationDialog() {
        if (winHandler == null) return;

        int maxControllers = winHandler.getMaxControllers();
        boolean[] checkedItems = new boolean[maxControllers];
        String[] items = new String[maxControllers];

        for (int i = 0; i < maxControllers; i++) {
            items[i] = getString(R.string.vibration_slot, i + 1);
            checkedItems[i] = winHandler.isVibrationEnabledForSlot(i);
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.vibration)
                .setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) ->
                        winHandler.setVibrationEnabledForSlot(which, isChecked))
                .setPositiveButton(R.string.ok, null)
                .show();
    }
    
    private void toggleSubMenu(View subMenu) {
        if (subMenu.getVisibility() == View.VISIBLE) subMenu.setVisibility(View.GONE);
        else subMenu.setVisibility(View.VISIBLE);
    }

    private void setupGraphicsSidebar() {
        spNativeFPS = findViewById(R.id.SPNativeFPS);
        swEnableFSR = findViewById(R.id.SWEnableFSR);
        spUpscalerMode = findViewById(R.id.SPUpscalerMode);
        spColorMode = findViewById(R.id.SPColorMode);
        sbSharpness = findViewById(R.id.SBSharpness);
        btSaveGraphicsPreset = findViewById(R.id.BTSaveGraphicsPreset);
        llStandardOptions = findViewById(R.id.LLStandardOptions);
        llFrameGenOptions = findViewById(R.id.LLFrameGenOptions);

        swHudMaster = findViewById(R.id.SWHudMaster);
        sbHudScale = findViewById(R.id.SBHudScale);
        sbHudAlpha = findViewById(R.id.SBHudAlpha);
        cbFps = findViewById(R.id.CBHudFps);
        cbGpu = findViewById(R.id.CBHudGpu);
        cbCpuRam = findViewById(R.id.CBHudCpuRam);
        cbBattTemp = findViewById(R.id.CBHudBattTemp);
        cbGraph = findViewById(R.id.CBHudGraph);
        cbRenderer = findViewById(R.id.CBHudRenderer);
        cbRam = findViewById(R.id.CBHudRam);
        cbLatency = findViewById(R.id.CBHudLatency);

        VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        boolean isNative = renderer != null && renderer.isNativeMode();



        int accentColor = ContextCompat.getColor(this, R.color.colorAccent);

        if (spNativeFPS != null) setupSpinner(spNativeFPS, java.util.Arrays.asList("Unlimited", "30 FPS", "45 FPS", "60 FPS", "90 FPS", "120 FPS"), accentColor);
        if (spUpscalerMode != null) setupSpinner(spUpscalerMode, java.util.Arrays.asList("CAS", "DLS"), accentColor);
        if (spColorMode != null) setupSpinner(spColorMode, java.util.Arrays.asList("None", "CRT", "HDR", "Natural"), accentColor);
        if (llStandardOptions != null) llStandardOptions.setVisibility(View.VISIBLE);
        if (llFrameGenOptions != null) llFrameGenOptions.setVisibility(View.GONE);
        
        boolean hasShortcutGraphicsPreset = restoreCurrentSidebarState();

        if (frameRating != null) {
            swHudMaster.setChecked(frameRating.getVisibility() == View.VISIBLE);
            frameRating.syncCheckboxes(cbFps, cbGpu, cbCpuRam, cbBattTemp, cbGraph, cbRenderer, cbLatency);
            android.content.SharedPreferences hudPrefs = getSharedPreferences("winlator_hud", MODE_PRIVATE);
            if (sbHudScale != null) {
                float scale = hudPrefs.getFloat("hud_scale", 1f);
                sbHudScale.setValue((int)((scale - 0.5f) * 50f));
            }
            if (sbHudAlpha != null) {
                int alpha = hudPrefs.getInt("hud_alpha_int", 100);
                sbHudAlpha.setValue(alpha);
            }
        } else {
            if (swHudMaster != null) swHudMaster.setEnabled(false);
        }


        AdapterView.OnItemSelectedListener applyListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { applySidebarSettings(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };

        if (spNativeFPS != null) spNativeFPS.setOnItemSelectedListener(applyListener);
        if (spUpscalerMode != null) spUpscalerMode.setOnItemSelectedListener(applyListener);
        if (spColorMode != null) spColorMode.setOnItemSelectedListener(applyListener);
        if (swEnableFSR != null) swEnableFSR.setOnClickListener(v -> applySidebarSettings());


        View.OnClickListener hudListener = v -> updateSidebarHud();
        if (swHudMaster != null) swHudMaster.setOnClickListener(hudListener);
        if (cbFps != null) cbFps.setOnClickListener(hudListener);
        if (cbGpu != null) cbGpu.setOnClickListener(hudListener);
        if (cbCpuRam != null) cbCpuRam.setOnClickListener(hudListener);
        if (cbBattTemp != null) cbBattTemp.setOnClickListener(hudListener);
        if (cbGraph != null) cbGraph.setOnClickListener(hudListener);
        if (cbRenderer != null) cbRenderer.setOnClickListener(hudListener);
        if (cbLatency != null) cbLatency.setOnClickListener(hudListener);

        if (sbHudScale != null) {
            sbHudScale.setOnTouchListener((v, event) -> {
                if ((event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_MOVE) && frameRating != null) {
                    float scale = 0.5f + (sbHudScale.getValue() / 50.0f);
                    frameRating.setHudScale(scale);
                }
                return false;
            });
        }

        if (sbHudAlpha != null) {
            sbHudAlpha.setOnTouchListener((v, event) -> {
                if ((event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_MOVE) && frameRating != null) {
                    frameRating.setHudAlpha(sbHudAlpha.getValue() / 100.0f);
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                    }
                }
                return false;
            });
        }

        if (spNativeFPS != null) {
            int savedFps = shortcut != null
                    ? parseIntOrDefault(shortcut.getExtra("sidebarFpsLimit", "0"), 0)
                    : 0;
            int fpsIndex = 0;
            for (int i = 0; i < NATIVE_FPS_VALUES.length; i++) {
                if (NATIVE_FPS_VALUES[i] == savedFps) { fpsIndex = i; break; }
            }
            spNativeFPS.setSelection(fpsIndex);
        }
        if (sbSharpness != null) {
            sbSharpness.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_MOVE) {
                    applySidebarSettings();
                }
                return false;
            });
        }

        if (btSaveGraphicsPreset != null) {
            btSaveGraphicsPreset.setOnClickListener(v -> saveGraphicsPresetForShortcut());
        }

        if (hasShortcutGraphicsPreset) {
            applySidebarSettings();
        }
    }

    private void updateSidebarHud() {
        if (frameRating == null) return;
        if (swHudMaster.isChecked()) {
            frameRating.enableByUser();
            if (hudDataSource != null) hudDataSource.start();
        } else {
            frameRating.disableByUser();
        }
        frameRating.resetFromContainer();
        if (swHudMaster.isChecked()) {
            frameRating.toggleElement(0, cbFps.isChecked());
            frameRating.toggleElement(2, cbGpu.isChecked());
            frameRating.toggleElement(3, cbCpuRam.isChecked());
            frameRating.toggleElement(4, cbBattTemp.isChecked());
            frameRating.toggleElement(5, cbGraph.isChecked());
            frameRating.toggleElement(6, cbRenderer.isChecked());
            if (cbRam != null) frameRating.toggleElement(7, cbRam.isChecked());
            if (cbLatency != null) frameRating.toggleElement(8, cbLatency.isChecked());
        }
    }
    private boolean restoreCurrentSidebarState() {
        VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        int currentLimit = renderer != null ? renderer.getFpsLimit() : 0;
        int index = 0;
        for(int i=0; i<NATIVE_FPS_VALUES.length; i++) {
            if(NATIVE_FPS_VALUES[i] == currentLimit) { index = i; break; }
        }
        if (spNativeFPS != null) spNativeFPS.setSelection(index);

        if (renderer != null) {
            int effectId = renderer.getEffectId();
            float sharpness = renderer.getSharpness();
            if (swEnableFSR != null) swEnableFSR.setChecked(effectId != VulkanRenderer.EFFECT_NONE);
            if (sbSharpness != null) sbSharpness.setValue(Math.round(sharpness * 100f));
            if (spUpscalerMode != null) {
                int upscaleIndex = 0;
                for (int i = 0; i < EFFECT_UPSCALER_VALUES.length; i++) {
                    if (EFFECT_UPSCALER_VALUES[i] == effectId) { upscaleIndex = i; break; }
                }
                spUpscalerMode.setSelection(upscaleIndex);
            }
            if (spColorMode != null) {
                int colorIndex = 0;
                for (int i = 0; i < EFFECT_COLOR_VALUES.length; i++) {
                    if (EFFECT_COLOR_VALUES[i] == effectId) { colorIndex = i; break; }
                }
                spColorMode.setSelection(colorIndex);
            }
        }

        return restoreGraphicsPresetFromShortcut();
    }


private void applySidebarSettings() {
        VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        if (renderer == null || spNativeFPS == null) return;
        int fpsLimit = NATIVE_FPS_VALUES[spNativeFPS.getSelectedItemPosition()];
        renderer.setFpsLimit(fpsLimit);
        if (shortcut != null) {
            shortcut.putExtra("sidebarFpsLimit", String.valueOf(fpsLimit));
            shortcut.saveData();
        }
        int selectedEffect = (spColorMode != null) ? EFFECT_COLOR_VALUES[spColorMode.getSelectedItemPosition()] : VulkanRenderer.EFFECT_NONE;
        boolean superResolutionEnabled = swEnableFSR != null && swEnableFSR.isChecked();
        int upscalerEffect = (spUpscalerMode != null) ? EFFECT_UPSCALER_VALUES[spUpscalerMode.getSelectedItemPosition()] : VulkanRenderer.EFFECT_FSR;
        int effectId = selectedEffect != VulkanRenderer.EFFECT_NONE
                ? selectedEffect
                : (superResolutionEnabled ? upscalerEffect : VulkanRenderer.EFFECT_NONE);
        float sharpness = sbSharpness != null ? (sbSharpness.getValue() / 100.0f) : 0.5f;
        renderer.setEffect(effectId, sharpness);
    }

    private void saveGraphicsPresetForShortcut() {
        if (shortcut == null) {
            AppUtils.showToast(this, "Open a game shortcut to save this preset");
            return;
        }
        int upscalerIndex = spUpscalerMode != null ? spUpscalerMode.getSelectedItemPosition() : 0;
        int effectIndex = spColorMode != null ? spColorMode.getSelectedItemPosition() : 0;
        int sharpnessValue = sbSharpness != null ? Math.round(sbSharpness.getValue()) : 50;
        boolean superResolutionEnabled = swEnableFSR != null && swEnableFSR.isChecked();
        int fpsLimit = spNativeFPS != null ? NATIVE_FPS_VALUES[spNativeFPS.getSelectedItemPosition()] : 0;
        shortcut.putExtra("sidebarUpscalerIndex", String.valueOf(upscalerIndex));
        shortcut.putExtra("sidebarEffectIndex", String.valueOf(effectIndex));
        shortcut.putExtra("sidebarSharpness", String.valueOf(sharpnessValue));
        shortcut.putExtra("sidebarSuperResolution", superResolutionEnabled ? "1" : "0");
        shortcut.putExtra("sidebarFpsLimit", String.valueOf(fpsLimit));
        shortcut.saveData();
        AppUtils.showToast(this, "Graphics preset saved to shortcut");
    }

    private boolean restoreGraphicsPresetFromShortcut() {
        if (shortcut == null) return false;
        boolean hasSavedPreset =
                !shortcut.getExtra("sidebarUpscalerIndex", "").isEmpty() ||
                !shortcut.getExtra("sidebarEffectIndex", "").isEmpty() ||
                !shortcut.getExtra("sidebarSharpness", "").isEmpty() ||
                !shortcut.getExtra("sidebarSuperResolution", "").isEmpty();

        if (!hasSavedPreset) return false;

        if (spUpscalerMode != null) {
            int idx = parseIntOrDefault(shortcut.getExtra("sidebarUpscalerIndex", "0"), 0);
            idx = Math.max(0, Math.min(idx, EFFECT_UPSCALER_VALUES.length - 1));
            spUpscalerMode.setSelection(idx);
        }
        if (spColorMode != null) {
            int idx = parseIntOrDefault(shortcut.getExtra("sidebarEffectIndex", "0"), 0);
            idx = Math.max(0, Math.min(idx, EFFECT_COLOR_VALUES.length - 1));
            spColorMode.setSelection(idx);
        }
        if (spNativeFPS != null) {
            int savedFps = parseIntOrDefault(shortcut.getExtra("sidebarFpsLimit", "0"), 0);
            int fpsIndex = 0;
            for (int i = 0; i < NATIVE_FPS_VALUES.length; i++) {
                if (NATIVE_FPS_VALUES[i] == savedFps) { fpsIndex = i; break; }
            }
            spNativeFPS.setSelection(fpsIndex);
        }
        if (sbSharpness != null) {
            int sharpness = parseIntOrDefault(shortcut.getExtra("sidebarSharpness", "50"), 50);
            sharpness = Math.max(0, Math.min(sharpness, 100));
            sbSharpness.setValue(sharpness);
        }
        if (swEnableFSR != null) {
            swEnableFSR.setChecked("1".equals(shortcut.getExtra("sidebarSuperResolution", "0")));
        }
        return true;
    }

    private int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void setupSpinner(Spinner spinner, java.util.List<String> items, int colorInt) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(colorInt);
                view.setTextSize(12);
                view.setTypeface(null, android.graphics.Typeface.BOLD);
                return view;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setBackgroundColor(Color.parseColor("#333333"));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            }
    );

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
            } catch (IOException e) {}
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
    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);

        dialog.getWindow().setBackgroundDrawableResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sProfile.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

        int textColor = ContextCompat.getColor(this, isDarkMode ? R.color.white : R.color.black);
        ViewGroup dialogViewGroup = (ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content);
        setTextColorForDialog(dialogViewGroup, textColor);

        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
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
            }
            else hideInputControls();
        };
        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
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
            }
            else hideInputControls();
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
    }

    private void startTouchscreenTimeout() {
        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);

        if (isTimeoutEnabled) {
            inputControlsView.setVisibility(View.VISIBLE);

            touchpadView.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    inputControlsView.setVisibility(View.VISIBLE);
                    timeoutHandler.removeCallbacks(hideControlsRunnable);
                    timeoutHandler.postDelayed(hideControlsRunnable, 5000); 
                }
                return false; 
            });

            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000); 
        } else {
            inputControlsView.setVisibility(View.VISIBLE); 
            timeoutHandler.removeCallbacks(hideControlsRunnable); 
            touchpadView.setOnTouchListener(null); 
        }
    }
    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }
    private void extractGraphicsDriverFiles() {
        String adrenoToolsDriverId = graphicsDriverConfig.get("version");

        File rootDir = imageFs.getRootDir();

        if (dxwrapper.contains("dxvk")) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
            String version = dxwrapperConfig.get("version");
            if (version.equals("1.11.1-sarek")) {
                envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
            }
        }
        else {
            WineD3DConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
        }

        boolean useDRI3 = preferences.getBoolean("use_dri3", true);
        if (!useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "sw");
        }

        envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");
        envVars.put("GALLIUM_DRIVER", "zink");
        if (firstTimeBoot) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper" + ".tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers" + ".tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs" + ".tzst", rootDir);
            if (wineInfo.isArm64EC() && !GPUInformation.getRenderer(null, null).contains("Mali")) {
                TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        this,
                        "graphics_driver/zink_dlls" + ".tzst",
                        new File(rootDir, imageFs.WINEPREFIX + "/drive_c/windows")
                );
            }
        }

        if (!"System".equalsIgnoreCase(adrenoToolsDriverId)) {
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
        }

        if (touchpadView != null) {
            handledByTouchpadView = touchpadView.onExternalMouseEvent(event);
        }

        boolean handledBySuper = super.dispatchGenericMotionEvent(event);

        return handledByWinHandler || handledByTouchpadView || handledBySuper;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE || event.getKeyCode() == KeyEvent.KEYCODE_HOME || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_SELECT) {
                boolean handled = inputControlsView.onKeyEvent(event) || (winHandler != null && winHandler.onKeyEvent(event)) && (xServer != null && xServer.keyboard.onKeyEvent(event));
                return true;
            }
        }
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
                (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll"};

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("dxvk")) {
            String dxvkWrapper = dxwrapper.split(";")[0];
            String vkd3dWrapper = dxwrapper.split(";")[1];
            String ddrawrapper = dxwrapper.split(";")[2];
            
            ContentProfile dxvkProfile = contentsManager.getProfileByEntryName(dxvkWrapper);
            if (dxvkProfile != null) {
                contentsManager.applyContent(dxvkProfile);
            } else {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxvkWrapper + ".tzst", windowsDir, onExtractFileListener);

                if (compareVersion(dxvkWrapper, "2.4") < 0) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir, onExtractFileListener);
                }
            }
            if (vkd3dWrapper.contains("None")) {
                restoreOriginalDllFiles(new String[]{"d3d12.dll", "d3d12core.dll"});
            }
            else {
                ContentProfile vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper);
                if (vkd3dProfile != null) {
                    contentsManager.applyContent(vkd3dProfile);
                } else {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + vkd3dWrapper + ".tzst", windowsDir, onExtractFileListener);
                }
            }

            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir, onExtractFileListener);

            if (ddrawrapper.contains("None")) {
                restoreOriginalDllFiles(new String[]{ "ddraw.dll", "d3dimm.dll" });
            }
            else {
                if (ddrawrapper.equals("cnc-ddraw"))
                    envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");

                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, onExtractFileListener);
            }

        } else if (dxwrapper.contains("wined3d")) {
            restoreOriginalDllFiles(dlls);
        }
    }
    private static int compareVersion(String varA, String varB) {
        int[] a = parseSemverLoose(varA);
        int[] b = parseSemverLoose(varB);

        if (a[0] != b[0]) return a[0] - b[0];
        if (a[1] != b[1]) return a[1] - b[1];
        return a[2] - b[2];
    }

    private static final Pattern SEMVER_LOOSE =
            Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static int[] parseSemverLoose(String s) {
        if (s == null) return new int[]{0, 0, 0};

        Matcher m = SEMVER_LOOSE.matcher(s);

        String g1 = null, g2 = null, g3 = null;
        while (m.find()) {
            g1 = m.group(1);
            g2 = m.group(2);
            g3 = m.group(3);
        }

        if (g1 == null || g2 == null) {
            return new int[]{0, 0, 0};
        }

        int major = safeParseInt(g1);
        int minor = safeParseInt(g2);
        int patch = safeParseInt(g3);
        return new int[]{major, minor, patch};
    }
    private static int safeParseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
    
    private void extractWinComponentFiles() {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();

            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1]) && !firstTimeBoot) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, onExtractFileListener);
                }
                else {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                    }
                }
                WineUtils.overrideWinComponentDlls(this, container, identifier, useNative);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, this);
            }

            if (!dlls.isEmpty()) restoreOriginalDllFiles(dlls.toArray(new String[0]));
        }
        catch (JSONException e) {}
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
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

    public XServerView getXServerView() {
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
            File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals("alsa")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals("pulseaudio")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }
    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern_common.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void assignTaskAffinity(Window window) {
        if (taskAffinityMask == 0 || taskAffinityMaskWoW64 == 0) return;
        int processId = window.getProcessId();
        String className = window.getClassName();
        int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        }
        else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    private void changeFrameRatingVisibility(Window window, Property property) {
        if (frameRating == null) return;
        if (property != null) {
            if (frameRatingWindowId == -1 && property.nameAsString().contains("_MESA_DRV")) {
                frameRatingWindowId = window.id;
            }
            if (property.nameAsString().contains("_MESA_DRV_ENGINE_NAME")) {
                String rendName = property.toString();
                runOnUiThread(() -> frameRating.onRendererDetected(rendName));
            }
        } else if (frameRatingWindowId == window.id) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.onRendererGone());
        }
    }
    }
