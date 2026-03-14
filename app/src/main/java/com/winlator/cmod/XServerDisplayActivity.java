package com.winlator.cmod;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Switch;
import android.widget.TextView;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.exifinterface.media.ExifInterface;
import androidx.preference.PreferenceManager;
import cn.sherlock.com.sun.media.sound.SF2Soundbank;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.DXVKConfigDialog;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contentdialog.WineD3DConfigDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
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
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.effects.CRTEffect;
import com.winlator.cmod.renderer.effects.FSREffect;
import com.winlator.cmod.renderer.effects.HDREffect;
import com.winlator.cmod.renderer.effects.NaturalEffect;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.LogView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.winhandler.TaskManagerDialog;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
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
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlinx.coroutines.DebugKt;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes8.dex */
public class XServerDisplayActivity extends AppCompatActivity {
    private CheckBox cbBattTemp;
    private CheckBox cbCpuRam;
    private CheckBox cbFps;
    private CheckBox cbGpu;
    private CheckBox cbGraph;
    private CheckBox cbRenderer;
    protected Container container;
    private ContainerManager containerManager;
    private ContentsManager contentsManager;
    private boolean cursorLock;
    private DebugDialog debugDialog;
    private DrawerLayout drawerLayout;
    private KeyValueSet dxwrapperConfig;
    private Runnable editInputControlsCallback;
    private XEnvironment environment;
    private HashMap<String, String> graphicsDriverConfig;
    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private Handler handler;
    private Runnable hideControlsRunnable;
    private ImageFs imageFs;
    private InputControlsManager inputControlsManager;
    private InputControlsView inputControlsView;
    private boolean isDarkMode;
    private TextView lblSharpness;
    private LinearLayout llNative;
    private LinearLayout llStandard;
    private MagnifierView magnifierView;
    private MidiHandler midiHandler;
    private OnExtractFileListener onExtractFileListener;
    private EnvVars overrideEnvVars;
    private SharedPreferences preferences;
    private SeekBar sbHudAlpha;
    private SeekBar sbHudScale;
    private SeekBar sbSharpness;
    private Shortcut shortcut;
    private Spinner spColorMode;
    private Spinner spNativeFPS;
    private Spinner spRenderMode;
    private Spinner spUpscalerMode;
    private String startupSelection;
    private Switch swFSR;
    private Switch swHudMaster;
    private TouchpadView touchpadView;
    private WinHandler winHandler;
    private WineInfo wineInfo;
    private WineRequestHandler wineRequestHandler;
    private XServer xServer;
    private XServerView xServerView;
    public static String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static int NOTIFICATION_ID = -1;
    private static final int[] NATIVE_FPS_VALUES = {0, 30, 45, 60, 90, 120};
    private static final Pattern SEMVER_LOOSE = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private FrameRating frameRating = null;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String emulator = Container.DEFAULT_EMULATOR;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    private float globalCursorSpeed = 1.0f;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private int frameRatingWindowId = -1;
    private final float[] xform = XForm.getInstance();
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean isRelativeMouseMovement = false;
    private boolean isMouseDisabled = false;
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda13
        @Override // androidx.activity.result.ActivityResultCallback
        public final void onActivityResult(Object obj) {
            XServerDisplayActivity.this.lambda$new$31((ActivityResult) obj);
        }
    });

    private void createNotifcationChannel() {
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "Winlator", 4);
        channel.setDescription("Winlator XServer Messages");
        NotificationManager notificationManager = (NotificationManager) getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, android.app.Activity, android.content.ComponentCallbacks
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (this.configChangedCallback != null) {
            this.configChangedCallback.run();
            this.configChangedCallback = null;
        }
    }

    private float pickHighestRefreshRate() {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode[] modes = display.getSupportedModes();
        float maxRefresh = 0.0f;
        for (Display.Mode mode : modes) {
            if (mode.getRefreshRate() > maxRefresh) {
                maxRefresh = mode.getRefreshRate();
            }
        }
        return maxRefresh;
    }

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    public void onCreate(Bundle savedInstanceState) {
        String screenSize;
        boolean xinputDisabledFromShortcut;
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.preferredRefreshRate = pickHighestRefreshRate();
        getWindow().setAttributes(params);
        setContentView(com.ludashi.benchmark.R.layout.xserver_display_activity);
        this.preloaderDialog = new PreloaderDialog(this);
        this.preferences = PreferenceManager.getDefaultSharedPreferences(this);
        this.cursorLock = this.preferences.getBoolean("cursor_lock", false);
        this.isDarkMode = this.preferences.getBoolean("dark_mode", false);
        boolean xinputDisabledFromShortcut2 = false;
        this.handler = new Handler(Looper.getMainLooper());
        final boolean isTimeoutEnabled = this.preferences.getBoolean("touchscreen_timeout_enabled", true);
        this.hideControlsRunnable = new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda11
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$onCreate$0(isTimeoutEnabled);
            }
        };
        this.contentsManager = new ContentsManager(this);
        this.contentsManager.syncContents();
        this.drawerLayout = (DrawerLayout) findViewById(com.ludashi.benchmark.R.id.DrawerLayout);
        this.drawerLayout.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda22
            @Override // android.view.View.OnApplyWindowInsetsListener
            public final WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                WindowInsets replaceSystemWindowInsets;
                replaceSystemWindowInsets = windowInsets.replaceSystemWindowInsets(0, 0, 0, 0);
                return replaceSystemWindowInsets;
            }
        });
        this.drawerLayout.setDrawerLockMode(1);
        this.imageFs = ImageFs.find(this);
        File devInputDir = new File(this.imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            for (int i = 0; i < 4; i++) {
                File eventFile = new File(devInputDir, NotificationCompat.CATEGORY_EVENT + i);
                if (eventFile.exists()) {
                    eventFile.delete();
                }
            }
            try {
                new File(devInputDir, "event0").createNewFile();
            } catch (Exception e) {
            }
        }
        this.winHandler = new WinHandler(this);
        this.winHandler.setFakeInputPath(devInputDir.getAbsolutePath());
        this.containerManager = new ContainerManager(this);
        this.container = this.containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        int containerId = getIntent().getIntExtra("container_id", 0);
        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
        }
        this.container = this.containerManager.getContainerById(containerId);
        if (this.container != null) {
            this.containerManager.activateContainer(this.container);
            if (shortcutPath != null && !shortcutPath.isEmpty()) {
                this.shortcut = new Shortcut(this.container, new File(shortcutPath));
            }
            this.taskAffinityMask = (short) ProcessHelper.getAffinityMask(this.container.getCPUList(true));
            this.taskAffinityMaskWoW64 = (short) ProcessHelper.getAffinityMask(this.container.getCPUListWoW64(true));
            if (this.shortcut != null) {
                this.taskAffinityMask = (short) ProcessHelper.getAffinityMask(this.shortcut.getExtra("cpuList", this.container.getCPUList(true)));
                this.taskAffinityMaskWoW64 = this.taskAffinityMask;
            }
            if (this.shortcut != null) {
                this.shortcut.getExtra("wmClass", "");
            }
            this.firstTimeBoot = this.container.getExtra("appVersion").isEmpty();
            String wineVersion = this.container.getWineVersion();
            this.wineInfo = WineInfo.fromIdentifier(this, this.contentsManager, wineVersion);
            this.imageFs.setWinePath(this.wineInfo.path);
            ProcessHelper.removeAllDebugCallbacks();
            boolean enableLogs = this.preferences.getBoolean("enable_wine_debug", false) || this.preferences.getBoolean("enable_box64_logs", false);
            if (enableLogs) {
                LogView.setFilename(getExecutable());
                DebugDialog debugDialog = new DebugDialog(this);
                this.debugDialog = debugDialog;
                ProcessHelper.addDebugCallback(debugDialog);
            }
            this.graphicsDriver = this.container.getGraphicsDriver();
            String graphicsDriverConfig = this.container.getGraphicsDriverConfig();
            this.audioDriver = this.container.getAudioDriver();
            this.emulator = this.container.getEmulator();
            this.midiSoundFont = this.container.getMIDISoundFont();
            this.dxwrapper = this.container.getDXWrapper();
            String dxwrapperConfig = this.container.getDXWrapperConfig();
            String screenSize2 = this.container.getScreenSize();
            this.winHandler.setInputType((byte) this.container.getInputType());
            this.lc_all = this.container.getLC_ALL();
            if (this.shortcut != null) {
                this.graphicsDriver = this.shortcut.getExtra("graphicsDriver", this.container.getGraphicsDriver());
                graphicsDriverConfig = this.shortcut.getExtra("graphicsDriverConfig", this.container.getGraphicsDriverConfig());
                this.audioDriver = this.shortcut.getExtra("audioDriver", this.container.getAudioDriver());
                this.emulator = this.shortcut.getExtra("emulator", this.container.getEmulator());
                this.dxwrapper = this.shortcut.getExtra("dxwrapper", this.container.getDXWrapper());
                dxwrapperConfig = this.shortcut.getExtra("dxwrapperConfig", this.container.getDXWrapperConfig());
                String screenSize3 = this.shortcut.getExtra("screenSize", this.container.getScreenSize());
                this.lc_all = this.shortcut.getExtra("lc_all", this.container.getLC_ALL());
                String inputType = this.shortcut.getExtra("inputType");
                if (!inputType.isEmpty()) {
                    this.winHandler.setInputType(Byte.parseByte(inputType));
                }
                String xinputDisabledString = this.shortcut.getExtra("disableXinput", "false");
                boolean xinputDisabledFromShortcut3 = parseBoolean(xinputDisabledString);
                this.winHandler.setXInputDisabled(xinputDisabledFromShortcut3);
                String sharpnessEffect = this.shortcut.getExtra("sharpnessEffect", DefaultVersion.VKD3D);
                if (sharpnessEffect.equals(DefaultVersion.VKD3D)) {
                    xinputDisabledFromShortcut = xinputDisabledFromShortcut3;
                } else {
                    double sharpnessLevel = Double.parseDouble(this.shortcut.getExtra("sharpnessLevel", "100"));
                    double sharpnessDenoise = Double.parseDouble(this.shortcut.getExtra("sharpnessDenoise", "100"));
                    xinputDisabledFromShortcut = xinputDisabledFromShortcut3;
                    this.vkbasaltConfig = "effects=" + sharpnessEffect.toLowerCase() + ";casSharpness=" + (sharpnessLevel / 100.0d) + ";dlsSharpness=" + (sharpnessLevel / 100.0d) + ";dlsDenoise=" + (sharpnessDenoise / 100.0d) + ";enableOnLaunch=True";
                }
                xinputDisabledFromShortcut2 = xinputDisabledFromShortcut;
                screenSize = screenSize3;
            } else {
                screenSize = screenSize2;
            }
            this.graphicsDriverConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);
            this.dxwrapperConfig = DXVKConfigDialog.parseConfig(dxwrapperConfig);
            if (!this.wineInfo.isWin64()) {
                this.onExtractFileListener = new OnExtractFileListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda33
                    @Override // com.winlator.cmod.core.OnExtractFileListener
                    public final File onExtractFile(File file, long j) {
                        return XServerDisplayActivity.lambda$onCreate$2(file, j);
                    }
                };
            }
            this.preloaderDialog.lambda$showOnUiThread$0(com.ludashi.benchmark.R.string.starting_up);
            this.inputControlsManager = new InputControlsManager(this);
            this.xServer = new XServer(new ScreenInfo(screenSize));
            this.xServer.setWinHandler(this.winHandler);
            final boolean[] winStarted = {false};
            this.xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() { // from class: com.winlator.cmod.XServerDisplayActivity.1
                @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
                public void onUpdateWindowContent(Window window) {
                    if (!winStarted[0] && window.isApplicationWindow()) {
                        XServerDisplayActivity.this.xServerView.getRenderer().setCursorVisible(true);
                        XServerDisplayActivity.this.preloaderDialog.closeOnUiThread();
                        winStarted[0] = true;
                    }
                    if (XServerDisplayActivity.this.frameRatingWindowId == window.id) {
                        XServerDisplayActivity.this.frameRating.update();
                    }
                }

                @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
                public void onMapWindow(Window window) {
                    XServerDisplayActivity.this.assignTaskAffinity(window);
                }

                @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
                public void onModifyWindowProperty(Window window, Property property) {
                    XServerDisplayActivity.this.changeFrameRatingVisibility(window, property);
                }

                @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
                public void onUnmapWindow(Window window) {
                    XServerDisplayActivity.this.changeFrameRatingVisibility(window, null);
                }
            });
            if (!this.midiSoundFont.equals("")) {
                final InputStream in = null;
                MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() { // from class: com.winlator.cmod.XServerDisplayActivity.2
                    @Override // com.winlator.cmod.midi.MidiManager.OnMidiLoadedCallback
                    public void onSuccess(SF2Soundbank soundbank) {
                        XServerDisplayActivity.this.midiHandler = new MidiHandler();
                        XServerDisplayActivity.this.midiHandler.setSoundBank(soundbank);
                        XServerDisplayActivity.this.midiHandler.start();
                    }

                    @Override // com.winlator.cmod.midi.MidiManager.OnMidiLoadedCallback
                    public void onFailed(Exception e2) {
                        try {
                            in.close();
                        } catch (Exception e3) {
                        }
                    }
                };
                try {
                    try {
                        if (this.midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                            try {
                                try {
                                    InputStream in2 = getAssets().open("soundfonts/" + this.midiSoundFont);
                                    try {
                                        MidiManager.load(in2, callback);
                                    } catch (Exception e2) {
                                    }
                                } catch (Exception e3) {
                                }
                            } catch (Exception e4) {
                            }
                        } else {
                            try {
                                try {
                                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), this.midiSoundFont), callback);
                                } catch (Exception e5) {
                                }
                            } catch (Exception e6) {
                            }
                        }
                    } catch (Exception e7) {
                    }
                } catch (Exception e8) {
                }
            }
            final String controlsProfile = this.shortcut != null ? this.shortcut.getExtra("controlsProfile", "") : "";
            createNotifcationChannel();
            Intent notificationIntent = new Intent(this, (Class<?>) XServerDisplayActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 67108864);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).setSmallIcon(com.ludashi.benchmark.R.drawable.ic_stat_ab_gear_0011).setContentTitle("Winlator").setContentText("Winlator is running, do not kill or swipe this notification").setPriority(1).setContentIntent(pendingIntent).setAutoCancel(false);
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
            Runnable runnable = new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda38
                @Override // java.lang.Runnable
                public final void run() {
                    XServerDisplayActivity.this.lambda$onCreate$4(controlsProfile);
                }
            };
            if (this.xServer.screenInfo.height > this.xServer.screenInfo.width) {
                setRequestedOrientation(1);
                this.configChangedCallback = runnable;
                return;
            } else {
                runnable.run();
                return;
            }
        }
        finish();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$0(boolean isTimeoutEnabled) {
        if (isTimeoutEnabled) {
            this.inputControlsView.setVisibility(8);
        }
    }

    static /* synthetic */ File lambda$onCreate$2(File file, long size) {
        String path = file.getPath();
        if (path.contains("system32/")) {
            return null;
        }
        return new File(path.replace("syswow64/", "system32/"));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$4(String controlsProfile) {
        setupUI();
        if (controlsProfile.isEmpty()) {
            simulateConfirmInputControlsDialog();
        }
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$onCreate$3();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$3() {
        setupWineSystemFiles();
        extractGraphicsDriverFiles();
        changeWineAudioDriver();
        try {
            setupXEnvironment();
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:14:0x0031, code lost:
    
        r0 = java.lang.Integer.parseInt(r2.split(":")[1].trim());
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int parseContainerIdFromDesktopFile(java.io.File r6) {
        /*
            r5 = this;
            r0 = 0
            boolean r1 = r6.exists()
            if (r1 == 0) goto L44
            java.io.BufferedReader r1 = new java.io.BufferedReader     // Catch: java.lang.NumberFormatException -> L41 java.io.IOException -> L43
            java.io.FileReader r2 = new java.io.FileReader     // Catch: java.lang.NumberFormatException -> L41 java.io.IOException -> L43
            r2.<init>(r6)     // Catch: java.lang.NumberFormatException -> L41 java.io.IOException -> L43
            r1.<init>(r2)     // Catch: java.lang.NumberFormatException -> L41 java.io.IOException -> L43
        L11:
            java.lang.String r2 = r1.readLine()     // Catch: java.lang.Throwable -> L37
            r3 = r2
            if (r2 == 0) goto L33
            java.lang.String r2 = "container_id:"
            boolean r2 = r3.startsWith(r2)     // Catch: java.lang.Throwable -> L37
            if (r2 == 0) goto L11
            java.lang.String r2 = ":"
            java.lang.String[] r2 = r3.split(r2)     // Catch: java.lang.Throwable -> L37
            r4 = 1
            r2 = r2[r4]     // Catch: java.lang.Throwable -> L37
            java.lang.String r2 = r2.trim()     // Catch: java.lang.Throwable -> L37
            int r2 = java.lang.Integer.parseInt(r2)     // Catch: java.lang.Throwable -> L37
            r0 = r2
        L33:
            r1.close()     // Catch: java.lang.NumberFormatException -> L41 java.io.IOException -> L43
            goto L44
        L37:
            r2 = move-exception
            r1.close()     // Catch: java.lang.Throwable -> L3c
            goto L40
        L3c:
            r3 = move-exception
            r2.addSuppressed(r3)     // Catch: java.lang.NumberFormatException -> L41 java.io.IOException -> L43
        L40:
            throw r2     // Catch: java.lang.NumberFormatException -> L41 java.io.IOException -> L43
        L41:
            r1 = move-exception
            goto L44
        L43:
            r1 = move-exception
        L44:
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.XServerDisplayActivity.parseContainerIdFromDesktopFile(java.io.File):int");
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
            case 2:
            case 7:
                float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
                if (!this.xServer.isRelativeMouseMovement()) {
                    this.xServer.injectPointerMoveDelta((int) transformedPoint[0], (int) transformedPoint[1]);
                    break;
                } else {
                    this.xServer.getWinHandler().mouseEvent(1, (int) transformedPoint[0], (int) transformedPoint[1], 0);
                    break;
                }
            case 8:
                float scrollY = event.getAxisValue(9);
                if (scrollY > -1.0f) {
                    if (scrollY >= 1.0f) {
                        if (!this.xServer.isRelativeMouseMovement()) {
                            this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                            this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                            break;
                        } else {
                            this.xServer.getWinHandler().mouseEvent(2048, 0, 0, ((int) scrollY) * 270);
                            break;
                        }
                    }
                } else if (!this.xServer.isRelativeMouseMovement()) {
                    this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    break;
                } else {
                    this.xServer.getWinHandler().mouseEvent(2048, 0, 0, ((int) scrollY) * 270);
                    break;
                }
                break;
            case 11:
                if (actionButton == 1) {
                    if (!this.xServer.isRelativeMouseMovement()) {
                        this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                        break;
                    } else {
                        this.xServer.getWinHandler().mouseEvent(2, 0, 0, 0);
                        break;
                    }
                } else if (actionButton == 2) {
                    if (!this.xServer.isRelativeMouseMovement()) {
                        this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                        break;
                    } else {
                        this.xServer.getWinHandler().mouseEvent(8, 0, 0, 0);
                        break;
                    }
                } else if (actionButton == 4) {
                    if (!this.xServer.isRelativeMouseMovement()) {
                        this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
                        break;
                    } else {
                        this.xServer.getWinHandler().mouseEvent(32, 0, 0, 0);
                        break;
                    }
                }
                break;
            case 12:
                if (actionButton == 1) {
                    if (!this.xServer.isRelativeMouseMovement()) {
                        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                        break;
                    } else {
                        this.xServer.getWinHandler().mouseEvent(4, 0, 0, 0);
                        break;
                    }
                } else if (actionButton == 2) {
                    if (!this.xServer.isRelativeMouseMovement()) {
                        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                        break;
                    } else {
                        this.xServer.getWinHandler().mouseEvent(16, 0, 0, 0);
                        break;
                    }
                } else if (actionButton == 4) {
                    if (!this.xServer.isRelativeMouseMovement()) {
                        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
                        break;
                    } else {
                        this.xServer.getWinHandler().mouseEvent(64, 0, 0, 0);
                        break;
                    }
                }
                break;
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, android.app.Activity
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 3 && resultCode == -1 && this.editInputControlsCallback != null) {
            this.editInputControlsCallback.run();
            this.editInputControlsCallback = null;
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    public void onResume() {
        super.onResume();
        if (this.environment != null) {
            this.xServerView.onResume();
            this.environment.onResume();
        }
        ProcessHelper.resumeAllWineProcesses();
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    public void onPause() {
        super.onPause();
        if (!isInPictureInPictureMode() && this.environment != null) {
            this.environment.onPause();
            this.xServerView.onPause();
        }
        ProcessHelper.pauseAllWineProcesses();
    }

    private void exit() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        this.preloaderDialog.showOnUiThread(com.ludashi.benchmark.R.string.shutdown);
        this.handler.postDelayed(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda19
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$exit$5();
            }
        }, 1000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$exit$5() {
        if (this.midiHandler != null) {
            this.midiHandler.stop();
        }
        if (this.environment != null) {
            this.environment.stopEnvironmentComponents();
        }
        if (this.preloaderDialog != null && this.preloaderDialog.isShowing()) {
            this.preloaderDialog.closeOnUiThread();
        }
        if (this.winHandler != null) {
            this.winHandler.stop();
        }
        if (this.wineRequestHandler != null) {
            this.wineRequestHandler.stop();
        }
        ProcessHelper.terminateAllWineProcesses();
        long start = System.currentTimeMillis();
        while (!ProcessHelper.listRunningWineProcesses().isEmpty()) {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= 1500) {
                break;
            }
        }
        this.preloaderDialog.closeOnUiThread();
        AppUtils.restartApplication(getApplicationContext());
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onStop() {
        super.onStop();
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    public void onBackPressed() {
        if (this.environment != null) {
            if (this.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                this.drawerLayout.closeDrawers();
            } else {
                this.drawerLayout.openDrawer(GravityCompat.START);
            }
        }
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && this.cursorLock) {
            this.touchpadView.requestPointerCapture();
            this.touchpadView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda37
                @Override // android.view.View.OnCapturedPointerListener
                public final boolean onCapturedPointer(View view, MotionEvent motionEvent) {
                    boolean lambda$onWindowFocusChanged$6;
                    lambda$onWindowFocusChanged$6 = XServerDisplayActivity.this.lambda$onWindowFocusChanged$6(view, motionEvent);
                    return lambda$onWindowFocusChanged$6;
                }
            });
        } else if (!hasFocus) {
            this.touchpadView.releasePointerCapture();
            this.touchpadView.setOnCapturedPointerListener(null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$onWindowFocusChanged$6(View view, MotionEvent event) {
        handleCapturedPointer(event);
        return true;
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String imgVersion = String.valueOf(this.imageFs.getVersion());
        boolean containerDataChanged = false;
        if (!this.container.getExtra("appVersion").equals(appVersion) || !this.container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(this.container);
            this.container.putExtra("appVersion", appVersion);
            this.container.putExtra("imgVersion", imgVersion);
            containerDataChanged = true;
        }
        String dxwrapper = this.dxwrapper;
        if (dxwrapper.contains("dxvk")) {
            String dxvkWrapper = "dxvk-" + this.dxwrapperConfig.get("version");
            String vkd3dWrapper = "vkd3d-" + this.dxwrapperConfig.get("vkd3dVersion");
            String ddrawrapper = this.dxwrapperConfig.get("ddrawrapper");
            dxwrapper = dxvkWrapper + ";" + vkd3dWrapper + ";" + ddrawrapper;
        }
        if (!dxwrapper.equals(this.container.getExtra("dxwrapper"))) {
            extractDXWrapperFiles(dxwrapper);
            this.container.putExtra("dxwrapper", dxwrapper);
            containerDataChanged = true;
        }
        String wincomponents = this.shortcut != null ? this.shortcut.getExtra("wincomponents", this.container.getWinComponents()) : this.container.getWinComponents();
        if (!wincomponents.equals(this.container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            this.container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }
        String desktopTheme = this.container.getDesktopTheme();
        if (!(desktopTheme + "," + this.xServer.screenInfo).equals(this.container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), this.xServer.screenInfo);
            this.container.putExtra("desktopTheme", desktopTheme + "," + this.xServer.screenInfo);
            containerDataChanged = true;
        }
        WineStartMenuCreator.create(this, this.container);
        WineUtils.createDosdevicesSymlinks(this.container);
        int inputType = this.container.getInputType();
        if (this.shortcut != null) {
            String shortcutInputType = this.shortcut.getExtra("inputType");
            if (!shortcutInputType.isEmpty()) {
                inputType = Byte.parseByte(shortcutInputType);
            }
        }
        boolean dinputEnabled = (inputType & 8) == 8;
        boolean exclusiveXInput = this.container.isExclusiveXInput();
        if (this.shortcut != null) {
            String extra = this.shortcut.getExtra("exclusiveXInput");
            if (!extra.isEmpty()) {
                exclusiveXInput = extra.equals("1");
            }
        }
        WineUtils.setJoystickRegistryKeys(this.container, dinputEnabled, exclusiveXInput);
        if (this.shortcut != null) {
            this.startupSelection = this.shortcut.getExtra("startupSelection", String.valueOf((int) this.container.getStartupSelection()));
        } else {
            this.startupSelection = String.valueOf((int) this.container.getStartupSelection());
        }
        if (!this.startupSelection.equals(this.container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(this.container, this.startupSelection);
            this.container.putExtra("startupSelection", this.startupSelection);
            containerDataChanged = true;
        }
        if (containerDataChanged) {
            this.container.saveData();
        }
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {
        String str;
        this.envVars.put("LC_ALL", this.lc_all);
        this.envVars.put("WINEPREFIX", this.imageFs.wineprefix);
        boolean enableWineDebug = this.preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = this.preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        EnvVars envVars = this.envVars;
        if (enableWineDebug && !wineDebugChannels.isEmpty()) {
            str = "+" + wineDebugChannels.replace(",", ",+");
        } else {
            str = "-all";
        }
        envVars.put("WINEDEBUG", str);
        String rootPath = this.imageFs.getRootDir().getPath();
        FileUtils.clear(this.imageFs.getTmpDir());
        this.guestProgramLauncherComponent = new GuestProgramLauncherComponent(this.contentsManager, this.contentsManager.getProfileByEntryName(this.container.getWineVersion()), this.shortcut);
        if (this.container != null) {
            this.guestProgramLauncherComponent.setContainer(this.container);
            this.guestProgramLauncherComponent.setWineInfo(this.wineInfo);
            String guestExecutable = "wine explorer /desktop=shell," + this.xServer.screenInfo + " " + getWineStartCommand();
            this.guestProgramLauncherComponent.setGuestExecutable(guestExecutable);
            this.envVars.putAll(this.container.getEnvVars());
            if (this.shortcut != null) {
                this.envVars.putAll(this.shortcut.getExtra("envVars"));
            }
            if (!this.envVars.has("WINEESYNC")) {
                this.envVars.put("WINEESYNC", "1");
            }
            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : this.container.drivesIterator()) {
                bindingPaths.add(drive[1]);
            }
            this.guestProgramLauncherComponent.setBindingPaths((String[]) bindingPaths.toArray(new String[0]));
            this.guestProgramLauncherComponent.setBox64Preset(this.shortcut != null ? this.shortcut.getExtra("box64Preset", this.container.getBox64Preset()) : this.container.getBox64Preset());
            this.guestProgramLauncherComponent.setFEXCorePreset(this.shortcut != null ? this.shortcut.getExtra("fexcorePreset", this.container.getFEXCorePreset()) : this.container.getFEXCorePreset());
        }
        if (this.overrideEnvVars != null) {
            this.envVars.putAll(this.overrideEnvVars);
            this.overrideEnvVars.clear();
        }
        this.environment = new XEnvironment(this, this.imageFs);
        this.environment.addComponent(new SysVSharedMemoryComponent(this.xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        this.environment.addComponent(new XServerComponent(this.xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)));
        if (this.audioDriver.equals(Container.DEFAULT_AUDIO_DRIVER)) {
            this.envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            this.envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            this.environment.addComponent(new ALSAServerComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)));
        } else if (this.audioDriver.equals("pulseaudio")) {
            this.envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            this.environment.addComponent(new PulseAudioComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)));
        }
        this.guestProgramLauncherComponent.setEnvVars(this.envVars);
        this.guestProgramLauncherComponent.setTerminationCallback(new Callback() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda12
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                XServerDisplayActivity.this.lambda$setupXEnvironment$7((Integer) obj);
            }
        });
        this.environment.addComponent(this.guestProgramLauncherComponent);
        File devInputDir = new File(this.imageFs.getRootDir(), "dev/input");
        if (!devInputDir.exists()) {
            devInputDir.mkdirs();
        }
        this.environment.startEnvironmentComponents();
        this.winHandler.start();
        if (this.wineRequestHandler != null) {
            this.wineRequestHandler.start();
        }
        this.dxwrapperConfig = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupXEnvironment$7(Integer status) {
        exit();
    }

    private void setupUI() {
        ControlsProfile profile;
        FrameLayout rootView = (FrameLayout) findViewById(com.ludashi.benchmark.R.id.FLXServerDisplay);
        this.xServerView = new XServerView(this, this.xServer);
        final GLRenderer renderer = this.xServerView.getRenderer();
        renderer.setCursorVisible(false);
        boolean enableLogs = true;
        if (this.shortcut != null) {
            renderer.setUnviewableWMClasses("explorer.exe");
        }
        boolean isNative = getIntent().getBooleanExtra("native_rendering", false);
        renderer.setNativeMode(isNative);
        this.xServer.setRenderer(renderer);
        rootView.addView(this.xServerView);
        this.globalCursorSpeed = this.preferences.getFloat("cursor_speed", 1.0f);
        this.touchpadView = new TouchpadView(this, this.xServer, this.timeoutHandler, this.hideControlsRunnable);
        this.touchpadView.setSensitivity(this.globalCursorSpeed);
        this.touchpadView.setMouseEnabled(!this.isMouseDisabled);
        this.touchpadView.setFourFingersTapCallback(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda40
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$setupUI$8();
            }
        });
        rootView.addView(this.touchpadView);
        this.magnifierView = new MagnifierView(this);
        this.magnifierView.setVisibility(8);
        final float[] magnifierZoom = {1.0f};
        this.magnifierView.setZoomButtonCallback(new Callback() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda41
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                XServerDisplayActivity.this.lambda$setupUI$9(magnifierZoom, (Float) obj);
            }
        });
        this.magnifierView.setHideButtonCallback(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda42
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$setupUI$10(magnifierZoom);
            }
        });
        rootView.addView(this.magnifierView);
        this.inputControlsView = new InputControlsView(this, this.timeoutHandler, this.hideControlsRunnable);
        this.inputControlsView.setOverlayOpacity(this.preferences.getFloat("overlay_opacity", 0.4f));
        this.inputControlsView.setTouchpadView(this.touchpadView);
        this.inputControlsView.setXServer(this.xServer);
        this.inputControlsView.setVisibility(8);
        rootView.addView(this.inputControlsView);
        startTouchscreenTimeout();
        boolean isTimeoutEnabled = this.preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        }
        this.frameRating = new FrameRating(this, this.graphicsDriverConfig);
        boolean showInitial = this.container != null && this.container.isShowFPS();
        this.frameRating.setVisibility(showInitial ? 0 : 8);
        rootView.addView(this.frameRating);
        this.xServerView.getRenderer().setFrameRating(this.frameRating);
        setupLeftSidebar();
        View btnLogs = findViewById(com.ludashi.benchmark.R.id.BTItemLogs);
        if (btnLogs != null) {
            if (!this.preferences.getBoolean("enable_wine_debug", false) && !this.preferences.getBoolean("enable_box64_logs", false)) {
                enableLogs = false;
            }
            btnLogs.setVisibility(enableLogs ? 0 : 8);
            btnLogs.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda43
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    XServerDisplayActivity.this.lambda$setupUI$11(view);
                }
            });
        }
        String shortcutFullscreenStretched = this.shortcut != null ? this.shortcut.getExtra("fullscreenStretched") : null;
        boolean shouldStretch = false;
        if (this.shortcut != null && shortcutFullscreenStretched != null) {
            shouldStretch = shortcutFullscreenStretched.equals("1");
        } else if (this.container != null && this.container.isFullscreenStretched()) {
            shouldStretch = true;
        }
        if (shouldStretch) {
            renderer.toggleFullscreen();
            this.touchpadView.toggleFullscreen();
        }
        if (this.shortcut != null) {
            String controlsProfile = this.shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty() && (profile = this.inputControlsManager.getProfile(Integer.parseInt(controlsProfile))) != null) {
                showInputControls(profile);
            }
            String simTouchScreen = this.shortcut.getExtra("simTouchScreen");
            this.touchpadView.setSimTouchScreen(simTouchScreen.equals("1"));
        }
        DrawerLayout drawerLayout = this.drawerLayout;
        Objects.requireNonNull(renderer);
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, new Callback() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda1
            @Override // com.winlator.cmod.core.Callback
            public final void call(Object obj) {
                GLRenderer.this.setScreenOffsetYRelativeToCursor(((Boolean) obj).booleanValue());
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupUI$8() {
        if (!this.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            this.drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupUI$9(float[] magnifierZoom, Float delta) {
        magnifierZoom[0] = Mathf.clamp(magnifierZoom[0] + delta.floatValue(), 1.0f, 3.0f);
        this.magnifierView.setZoomValue(magnifierZoom[0]);
        this.xServerView.setScaleX(magnifierZoom[0]);
        this.xServerView.setScaleY(magnifierZoom[0]);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupUI$10(float[] magnifierZoom) {
        this.magnifierView.setVisibility(8);
        magnifierZoom[0] = 1.0f;
        this.magnifierView.setZoomValue(1.0f);
        this.xServerView.setScaleX(1.0f);
        this.xServerView.setScaleY(1.0f);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupUI$11(View v) {
        if (this.debugDialog != null) {
            this.debugDialog.show();
        }
        if (this.drawerLayout != null) {
            this.drawerLayout.closeDrawers();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$12(View v) {
        toggleSubMenu(findViewById(com.ludashi.benchmark.R.id.LLSubFPS));
    }

    private void setupLeftSidebar() {
        findViewById(com.ludashi.benchmark.R.id.BTItemFPS).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda20
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$12(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemMouse).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda26
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$13(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemGraphics).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda27
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$14(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemScreen).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda28
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$15(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemInput).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda29
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$16(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemPause).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda30
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$17(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemPipMode).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda31
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$18(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemToggleFullscreen).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda32
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$19(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemMagnifier).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda34
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$20(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemTaskManager).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda35
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$21(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTItemExit).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda21
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$22(view);
            }
        });
        findViewById(com.ludashi.benchmark.R.id.BTSubKeyboard).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda23
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupLeftSidebar$23(view);
            }
        });
        Switch swRelativeMouse = (Switch) findViewById(com.ludashi.benchmark.R.id.SWRelativeMouse);
        if (swRelativeMouse != null) {
            swRelativeMouse.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda24
                @Override // android.widget.CompoundButton.OnCheckedChangeListener
                public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                    XServerDisplayActivity.this.lambda$setupLeftSidebar$24(compoundButton, z);
                }
            });
        }
        Switch swDisableMouse = (Switch) findViewById(com.ludashi.benchmark.R.id.SWDisableMouse);
        if (swDisableMouse != null) {
            swDisableMouse.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda25
                @Override // android.widget.CompoundButton.OnCheckedChangeListener
                public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                    XServerDisplayActivity.this.lambda$setupLeftSidebar$25(compoundButton, z);
                }
            });
        }
        setupGraphicsSidebar();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$13(View v) {
        toggleSubMenu(findViewById(com.ludashi.benchmark.R.id.LLSubMouse));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$14(View v) {
        toggleSubMenu(findViewById(com.ludashi.benchmark.R.id.LLSubGraphics));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$15(View v) {
        toggleSubMenu(findViewById(com.ludashi.benchmark.R.id.LLSubScreen));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$16(View v) {
        showInputControlsDialog();
        this.drawerLayout.closeDrawers();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$17(View v) {
        this.isPaused = !this.isPaused;
        if (this.isPaused) {
            ProcessHelper.pauseAllWineProcesses();
        } else {
            ProcessHelper.resumeAllWineProcesses();
        }
        ((TextView) findViewById(com.ludashi.benchmark.R.id.TVPause)).setText(this.isPaused ? "Resume" : "Pause / Resume");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$18(View v) {
        enterPictureInPictureMode();
        this.drawerLayout.closeDrawers();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$19(View v) {
        this.xServerView.getRenderer().toggleFullscreen();
        this.touchpadView.toggleFullscreen();
        this.drawerLayout.closeDrawers();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$20(View v) {
        if (this.magnifierView != null) {
            if (this.magnifierView.getVisibility() == 0) {
                this.magnifierView.setVisibility(8);
            } else {
                this.magnifierView.setVisibility(0);
            }
        }
        this.drawerLayout.closeDrawers();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$21(View v) {
        new TaskManagerDialog(this).show();
        this.drawerLayout.closeDrawers();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$22(View v) {
        this.drawerLayout.closeDrawers();
        exit();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$23(View v) {
        AppUtils.showKeyboard(this);
        this.drawerLayout.closeDrawers();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$24(CompoundButton cb, boolean checked) {
        this.isRelativeMouseMovement = checked;
        this.xServer.setRelativeMouseMovement(this.isRelativeMouseMovement);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupLeftSidebar$25(CompoundButton cb, boolean checked) {
        this.isMouseDisabled = checked;
        this.touchpadView.setMouseEnabled(!this.isMouseDisabled);
    }

    private void toggleSubMenu(View subMenu) {
        if (subMenu.getVisibility() != 0) {
            subMenu.setVisibility(0);
        } else {
            subMenu.setVisibility(8);
        }
    }

    private void setupGraphicsSidebar() {
        this.spRenderMode = (Spinner) findViewById(com.ludashi.benchmark.R.id.SPRenderMode);
        this.llStandard = (LinearLayout) findViewById(com.ludashi.benchmark.R.id.LLStandardOptions);
        this.llNative = (LinearLayout) findViewById(com.ludashi.benchmark.R.id.LLNativeOptions);
        this.spNativeFPS = (Spinner) findViewById(com.ludashi.benchmark.R.id.SPNativeFPS);
        this.swFSR = (Switch) findViewById(com.ludashi.benchmark.R.id.SWEnableFSR);
        this.spUpscalerMode = (Spinner) findViewById(com.ludashi.benchmark.R.id.SPUpscalerMode);
        this.spColorMode = (Spinner) findViewById(com.ludashi.benchmark.R.id.SPColorMode);
        this.sbSharpness = (SeekBar) findViewById(com.ludashi.benchmark.R.id.SBSharpness);
        this.lblSharpness = (TextView) findViewById(com.ludashi.benchmark.R.id.LBLSharpnessHeader);
        this.swHudMaster = (Switch) findViewById(com.ludashi.benchmark.R.id.SWHudMaster);
        this.sbHudScale = (SeekBar) findViewById(com.ludashi.benchmark.R.id.SBHudScale);
        this.sbHudAlpha = (SeekBar) findViewById(com.ludashi.benchmark.R.id.SBHudAlpha);
        this.cbFps = (CheckBox) findViewById(com.ludashi.benchmark.R.id.CBHudFps);
        this.cbGpu = (CheckBox) findViewById(com.ludashi.benchmark.R.id.CBHudGpu);
        this.cbCpuRam = (CheckBox) findViewById(com.ludashi.benchmark.R.id.CBHudCpuRam);
        this.cbBattTemp = (CheckBox) findViewById(com.ludashi.benchmark.R.id.CBHudBattTemp);
        this.cbGraph = (CheckBox) findViewById(com.ludashi.benchmark.R.id.CBHudGraph);
        this.cbRenderer = (CheckBox) findViewById(com.ludashi.benchmark.R.id.CBHudRenderer);
        GLRenderer renderer = this.xServerView != null ? this.xServerView.getRenderer() : null;
        boolean isNative = renderer != null && renderer.isNativeMode();
        LinearLayout llSubGraphics = (LinearLayout) findViewById(com.ludashi.benchmark.R.id.LLSubGraphics);
        if (llSubGraphics != null) {
            for (int i = 0; i < llSubGraphics.getChildCount(); i++) {
                View child = llSubGraphics.getChildAt(i);
                if (child instanceof TextView) {
                    String text = ((TextView) child).getText().toString();
                    if (isNative && text.contains("GAME FPS LIMIT")) {
                        child.setVisibility(8);
                    }
                }
            }
            if (isNative && this.spNativeFPS != null) {
                this.spNativeFPS.setVisibility(8);
            }
        }
        int accentColor = ContextCompat.getColor(this, com.ludashi.benchmark.R.color.colorAccent);
        if (this.spRenderMode != null) {
            setupSpinner(this.spRenderMode, Arrays.asList("Standard (Filters)", "Direct Rendering+"), accentColor);
        }
        if (this.spNativeFPS != null) {
            setupSpinner(this.spNativeFPS, Arrays.asList("Unlimited", "30 FPS", "45 FPS", "60 FPS", "90 FPS", "120 FPS"), accentColor);
        }
        if (this.spUpscalerMode != null) {
            setupSpinner(this.spUpscalerMode, Arrays.asList("Super Resolution", "DLS"), accentColor);
        }
        if (this.spColorMode != null) {
            setupSpinner(this.spColorMode, Arrays.asList("Disabled", "HDR", "Natural", "CRT Effect"), accentColor);
        }
        restoreCurrentSidebarState();
        if (this.spRenderMode != null) {
            this.spRenderMode.setEnabled(false);
            this.spRenderMode.setAlpha(0.6f);
        }
        if (this.frameRating != null) {
            this.swHudMaster.setChecked(this.frameRating.getVisibility() == 0);
            if (this.sbHudScale != null) {
                this.sbHudScale.setValue(25.0f);
            }
            if (this.sbHudAlpha != null) {
                this.sbHudAlpha.setValue(100.0f);
            }
        } else if (this.swHudMaster != null) {
            this.swHudMaster.setEnabled(false);
        }
        if (this.spRenderMode != null) {
            this.spRenderMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.XServerDisplayActivity.3
                @Override // android.widget.AdapterView.OnItemSelectedListener
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    XServerDisplayActivity.this.updateSidebarVisibility(position);
                    XServerDisplayActivity.this.applySidebarSettings();
                }

                @Override // android.widget.AdapterView.OnItemSelectedListener
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }
        AdapterView.OnItemSelectedListener applyListener = new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.XServerDisplayActivity.4
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                XServerDisplayActivity.this.applySidebarSettings();
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        if (this.spUpscalerMode != null) {
            this.spUpscalerMode.setOnItemSelectedListener(applyListener);
        }
        if (this.spColorMode != null) {
            this.spColorMode.setOnItemSelectedListener(applyListener);
        }
        if (this.spNativeFPS != null) {
            this.spNativeFPS.setOnItemSelectedListener(applyListener);
        }
        if (this.swFSR != null) {
            this.swFSR.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda6
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    XServerDisplayActivity.this.lambda$setupGraphicsSidebar$26(view);
                }
            });
        }
        if (this.sbSharpness != null && this.lblSharpness != null) {
            this.sbSharpness.setOnTouchListener(new View.OnTouchListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda7
                @Override // android.view.View.OnTouchListener
                public final boolean onTouch(View view, MotionEvent motionEvent) {
                    boolean lambda$setupGraphicsSidebar$27;
                    lambda$setupGraphicsSidebar$27 = XServerDisplayActivity.this.lambda$setupGraphicsSidebar$27(view, motionEvent);
                    return lambda$setupGraphicsSidebar$27;
                }
            });
        }
        View.OnClickListener hudListener = new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda8
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$setupGraphicsSidebar$28(view);
            }
        };
        if (this.swHudMaster != null) {
            this.swHudMaster.setOnClickListener(hudListener);
        }
        if (this.cbFps != null) {
            this.cbFps.setOnClickListener(hudListener);
        }
        if (this.cbGpu != null) {
            this.cbGpu.setOnClickListener(hudListener);
        }
        if (this.cbCpuRam != null) {
            this.cbCpuRam.setOnClickListener(hudListener);
        }
        if (this.cbBattTemp != null) {
            this.cbBattTemp.setOnClickListener(hudListener);
        }
        if (this.cbGraph != null) {
            this.cbGraph.setOnClickListener(hudListener);
        }
        if (this.cbRenderer != null) {
            this.cbRenderer.setOnClickListener(hudListener);
        }
        if (this.sbHudScale != null) {
            this.sbHudScale.setOnTouchListener(new View.OnTouchListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda9
                @Override // android.view.View.OnTouchListener
                public final boolean onTouch(View view, MotionEvent motionEvent) {
                    boolean lambda$setupGraphicsSidebar$29;
                    lambda$setupGraphicsSidebar$29 = XServerDisplayActivity.this.lambda$setupGraphicsSidebar$29(view, motionEvent);
                    return lambda$setupGraphicsSidebar$29;
                }
            });
        }
        if (this.sbHudAlpha != null) {
            this.sbHudAlpha.setOnTouchListener(new View.OnTouchListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda10
                @Override // android.view.View.OnTouchListener
                public final boolean onTouch(View view, MotionEvent motionEvent) {
                    boolean lambda$setupGraphicsSidebar$30;
                    lambda$setupGraphicsSidebar$30 = XServerDisplayActivity.this.lambda$setupGraphicsSidebar$30(view, motionEvent);
                    return lambda$setupGraphicsSidebar$30;
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupGraphicsSidebar$26(View v) {
        applySidebarSettings();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$setupGraphicsSidebar$27(View v, MotionEvent event) {
        if (event.getAction() == 1 || event.getAction() == 2) {
            float level = (this.sbSharpness.getValue() / 25.0f) + 1.0f;
            this.lblSharpness.setText(String.format(Locale.US, "Sharpness: %.1f", Float.valueOf(level)));
            if (event.getAction() == 1) {
                applySidebarSettings();
                return false;
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setupGraphicsSidebar$28(View v) {
        updateSidebarHud();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$setupGraphicsSidebar$29(View v, MotionEvent event) {
        if ((event.getAction() == 1 || event.getAction() == 2) && this.frameRating != null) {
            float scale = (this.sbHudScale.getValue() / 50.0f) + 0.5f;
            this.frameRating.setHudScale(scale);
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$setupGraphicsSidebar$30(View v, MotionEvent event) {
        if ((event.getAction() == 1 || event.getAction() == 2) && this.frameRating != null) {
            float alpha = this.sbHudAlpha.getValue() / 100.0f;
            this.frameRating.setHudAlpha(alpha);
            return false;
        }
        return false;
    }

    private void updateSidebarHud() {
        if (this.frameRating == null) {
            return;
        }
        this.frameRating.setVisibility(this.swHudMaster.isChecked() ? 0 : 8);
        if (this.swHudMaster.isChecked()) {
            this.frameRating.toggleElement(0, this.cbFps.isChecked());
            this.frameRating.toggleElement(2, this.cbGpu.isChecked());
            this.frameRating.toggleElement(3, this.cbCpuRam.isChecked());
            this.frameRating.toggleElement(4, this.cbBattTemp.isChecked());
            this.frameRating.toggleElement(5, this.cbGraph.isChecked());
            this.frameRating.toggleElement(6, this.cbRenderer.isChecked());
        }
    }

    private void restoreCurrentSidebarState() {
        GLRenderer renderer = this.xServerView != null ? this.xServerView.getRenderer() : null;
        int currentLimit = renderer != null ? renderer.getFpsLimit() : 0;
        int index = 0;
        int i = 0;
        while (true) {
            if (i >= NATIVE_FPS_VALUES.length) {
                break;
            }
            if (NATIVE_FPS_VALUES[i] == currentLimit) {
                index = i;
                break;
            }
            i++;
        }
        if (this.spNativeFPS != null) {
            this.spNativeFPS.setSelection(index);
        }
        if (renderer != null && renderer.isNativeMode()) {
            if (this.spRenderMode != null) {
                this.spRenderMode.setSelection(1);
            }
        } else if (renderer != null) {
            if (this.spRenderMode != null) {
                this.spRenderMode.setSelection(0);
            }
            FSREffect fsr = (FSREffect) renderer.getEffectComposer().getEffect(FSREffect.class);
            if (fsr != null && this.swFSR != null) {
                this.swFSR.setChecked(true);
                if (this.spUpscalerMode != null) {
                    this.spUpscalerMode.setSelection(fsr.getMode());
                }
                if (this.sbSharpness != null) {
                    this.sbSharpness.setValue((int) ((fsr.getLevel() - 1.0f) * 25.0f));
                }
            } else if (this.swFSR != null) {
                this.swFSR.setChecked(false);
            }
            if (this.spColorMode != null) {
                if (renderer.getEffectComposer().getEffect(CRTEffect.class) != null) {
                    this.spColorMode.setSelection(3);
                } else if (renderer.getEffectComposer().getEffect(HDREffect.class) != null) {
                    this.spColorMode.setSelection(1);
                } else if (renderer.getEffectComposer().getEffect(NaturalEffect.class) == null) {
                    this.spColorMode.setSelection(0);
                } else {
                    this.spColorMode.setSelection(2);
                }
            }
        }
        if (this.spRenderMode != null) {
            updateSidebarVisibility(this.spRenderMode.getSelectedItemPosition());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSidebarVisibility(int mode) {
        if (this.llStandard != null) {
            this.llStandard.setVisibility(mode == 0 ? 0 : 8);
        }
        if (this.llNative != null) {
            this.llNative.setVisibility(mode != 1 ? 8 : 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void applySidebarSettings() {
        GLRenderer renderer = this.xServerView != null ? this.xServerView.getRenderer() : null;
        if (renderer == null || this.spRenderMode == null || this.spNativeFPS == null) {
            return;
        }
        int mode = this.spRenderMode.getSelectedItemPosition();
        int fpsLimit = NATIVE_FPS_VALUES[this.spNativeFPS.getSelectedItemPosition()];
        renderer.setFpsLimit(fpsLimit);
        if (mode != 1) {
            renderer.setNativeMode(false);
        }
        switch (mode) {
            case 0:
                FSREffect currentFsr = (FSREffect) renderer.getEffectComposer().getEffect(FSREffect.class);
                if (currentFsr != null) {
                    renderer.getEffectComposer().removeEffect(currentFsr);
                }
                if (this.swFSR != null && this.swFSR.isChecked() && this.sbSharpness != null && this.spUpscalerMode != null) {
                    FSREffect newFsr = new FSREffect();
                    newFsr.setLevel((this.sbSharpness.getValue() / 25.0f) + 1.0f);
                    newFsr.setMode(this.spUpscalerMode.getSelectedItemPosition());
                    renderer.getEffectComposer().addEffect(newFsr);
                }
                HDREffect hdr = (HDREffect) renderer.getEffectComposer().getEffect(HDREffect.class);
                if (hdr != null) {
                    renderer.getEffectComposer().removeEffect(hdr);
                }
                NaturalEffect nat = (NaturalEffect) renderer.getEffectComposer().getEffect(NaturalEffect.class);
                if (nat != null) {
                    renderer.getEffectComposer().removeEffect(nat);
                }
                CRTEffect crt = (CRTEffect) renderer.getEffectComposer().getEffect(CRTEffect.class);
                if (crt != null) {
                    renderer.getEffectComposer().removeEffect(crt);
                }
                if (this.spColorMode != null) {
                    int color = this.spColorMode.getSelectedItemPosition();
                    if (color != 1) {
                        if (color == 2) {
                            renderer.getEffectComposer().addEffect(new NaturalEffect());
                            break;
                        } else if (color == 3) {
                            renderer.getEffectComposer().addEffect(new CRTEffect());
                            break;
                        }
                    } else {
                        HDREffect newHdr = new HDREffect();
                        newHdr.setStrength(1.0f);
                        renderer.getEffectComposer().addEffect(newHdr);
                        break;
                    }
                }
                break;
            case 1:
                renderer.setNativeMode(true);
                break;
        }
    }

    private void setupSpinner(Spinner spinner, List<String> items, final int colorInt) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) { // from class: com.winlator.cmod.XServerDisplayActivity.5
            @Override // android.widget.ArrayAdapter, android.widget.Adapter
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(colorInt);
                view.setTextSize(12.0f);
                view.setTypeface(null, 1);
                return view;
            }

            @Override // android.widget.ArrayAdapter, android.widget.BaseAdapter, android.widget.SpinnerAdapter
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(-1);
                view.setBackgroundColor(Color.parseColor("#333333"));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter((SpinnerAdapter) adapter);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$31(ActivityResult result) {
        if (this.editInputControlsCallback != null) {
            this.editInputControlsCallback.run();
            this.editInputControlsCallback = null;
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:14:0x002e, code lost:
    
        r0 = r2.split("=")[1].trim();
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.lang.String parseShortcutNameFromDesktopFile(java.io.File r6) {
        /*
            r5 = this;
            java.lang.String r0 = ""
            boolean r1 = r6.exists()
            if (r1 == 0) goto L3f
            java.io.BufferedReader r1 = new java.io.BufferedReader     // Catch: java.io.IOException -> L3e
            java.io.FileReader r2 = new java.io.FileReader     // Catch: java.io.IOException -> L3e
            r2.<init>(r6)     // Catch: java.io.IOException -> L3e
            r1.<init>(r2)     // Catch: java.io.IOException -> L3e
        L12:
            java.lang.String r2 = r1.readLine()     // Catch: java.lang.Throwable -> L34
            r3 = r2
            if (r2 == 0) goto L30
            java.lang.String r2 = "Name="
            boolean r2 = r3.startsWith(r2)     // Catch: java.lang.Throwable -> L34
            if (r2 == 0) goto L12
            java.lang.String r2 = "="
            java.lang.String[] r2 = r3.split(r2)     // Catch: java.lang.Throwable -> L34
            r4 = 1
            r2 = r2[r4]     // Catch: java.lang.Throwable -> L34
            java.lang.String r2 = r2.trim()     // Catch: java.lang.Throwable -> L34
            r0 = r2
        L30:
            r1.close()     // Catch: java.io.IOException -> L3e
            goto L3f
        L34:
            r2 = move-exception
            r1.close()     // Catch: java.lang.Throwable -> L39
            goto L3d
        L39:
            r3 = move-exception
            r2.addSuppressed(r3)     // Catch: java.io.IOException -> L3e
        L3d:
            throw r2     // Catch: java.io.IOException -> L3e
        L3e:
            r1 = move-exception
        L3f:
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.XServerDisplayActivity.parseShortcutNameFromDesktopFile(java.io.File):java.lang.String");
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
        ContentDialog dialog = new ContentDialog(this, com.ludashi.benchmark.R.layout.input_controls_dialog);
        dialog.setTitle(com.ludashi.benchmark.R.string.input_controls);
        dialog.setIcon(com.ludashi.benchmark.R.drawable.icon_input_controls);
        final Spinner sProfile = (Spinner) dialog.findViewById(com.ludashi.benchmark.R.id.SProfile);
        android.view.Window window = dialog.getWindow();
        boolean z = this.isDarkMode;
        int i = com.ludashi.benchmark.R.drawable.content_dialog_background_dark;
        window.setBackgroundDrawableResource(z ? com.ludashi.benchmark.R.drawable.content_dialog_background_dark : com.ludashi.benchmark.R.drawable.content_dialog_background);
        if (!this.isDarkMode) {
            i = com.ludashi.benchmark.R.drawable.content_dialog_background;
        }
        sProfile.setPopupBackgroundResource(i);
        int textColor = ContextCompat.getColor(this, this.isDarkMode ? com.ludashi.benchmark.R.color.white : com.ludashi.benchmark.R.color.black);
        ViewGroup dialogViewGroup = (ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content);
        setTextColorForDialog(dialogViewGroup, textColor);
        final Runnable loadProfileSpinner = new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda14
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$showInputControlsDialog$32(sProfile);
            }
        };
        loadProfileSpinner.run();
        final CheckBox cbShowTouchscreenControls = (CheckBox) dialog.findViewById(com.ludashi.benchmark.R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(this.inputControlsView.isShowTouchscreenControls());
        final CheckBox cbEnableTimeout = (CheckBox) dialog.findViewById(com.ludashi.benchmark.R.id.CBEnableTimeout);
        cbEnableTimeout.setChecked(this.preferences.getBoolean("touchscreen_timeout_enabled", false));
        final CheckBox cbEnableHaptics = (CheckBox) dialog.findViewById(com.ludashi.benchmark.R.id.CBEnableHaptics);
        cbEnableHaptics.setChecked(this.preferences.getBoolean("touchscreen_haptics_enabled", false));
        final Runnable updateProfile = new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda15
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$showInputControlsDialog$33(sProfile);
            }
        };
        dialog.findViewById(com.ludashi.benchmark.R.id.BTSettings).setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda16
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                XServerDisplayActivity.this.lambda$showInputControlsDialog$35(sProfile, loadProfileSpinner, updateProfile, view);
            }
        });
        dialog.setOnConfirmCallback(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda17
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$showInputControlsDialog$36(cbShowTouchscreenControls, cbEnableTimeout, cbEnableHaptics, sProfile, updateProfile);
            }
        });
        Objects.requireNonNull(updateProfile);
        dialog.setOnCancelCallback(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda18
            @Override // java.lang.Runnable
            public final void run() {
                updateProfile.run();
            }
        });
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showInputControlsDialog$32(Spinner sProfile) {
        ArrayList<ControlsProfile> profiles = this.inputControlsManager.getProfiles(true);
        ArrayList<String> profileItems = new ArrayList<>();
        int selectedPosition = 0;
        profileItems.add("-- " + getString(com.ludashi.benchmark.R.string.disabled) + " --");
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (this.inputControlsView.getProfile() != null && profile.id == this.inputControlsView.getProfile().id) {
                selectedPosition = i + 1;
            }
            profileItems.add(profile.getName());
        }
        sProfile.setAdapter((SpinnerAdapter) new ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
        sProfile.setSelection(selectedPosition);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showInputControlsDialog$33(Spinner sProfile) {
        int position = sProfile.getSelectedItemPosition();
        if (position > 0) {
            showInputControls(this.inputControlsManager.getProfiles().get(position - 1));
        } else {
            hideInputControls();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showInputControlsDialog$35(Spinner sProfile, final Runnable loadProfileSpinner, final Runnable updateProfile, View v) {
        int position = sProfile.getSelectedItemPosition();
        Intent intent = new Intent(this, (Class<?>) MainActivity.class);
        intent.putExtra("edit_input_controls", true);
        intent.putExtra("selected_profile_id", position > 0 ? this.inputControlsManager.getProfiles().get(position - 1).id : 0);
        this.editInputControlsCallback = new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda39
            @Override // java.lang.Runnable
            public final void run() {
                XServerDisplayActivity.this.lambda$showInputControlsDialog$34(loadProfileSpinner, updateProfile);
            }
        };
        this.controlsEditorActivityResultLauncher.launch(intent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showInputControlsDialog$34(Runnable loadProfileSpinner, Runnable updateProfile) {
        hideInputControls();
        this.inputControlsManager.loadProfiles(true);
        loadProfileSpinner.run();
        updateProfile.run();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showInputControlsDialog$36(CheckBox cbShowTouchscreenControls, CheckBox cbEnableTimeout, CheckBox cbEnableHaptics, Spinner sProfile, Runnable updateProfile) {
        this.inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
        boolean isTimeoutEnabled = cbEnableTimeout.isChecked();
        boolean isHapticsEnabled = cbEnableHaptics.isChecked();
        SharedPreferences.Editor editor = this.preferences.edit();
        editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
        editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
        editor.apply();
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        } else {
            this.touchpadView.setOnTouchListener(null);
        }
        int position = sProfile.getSelectedItemPosition();
        if (position > 0) {
            showInputControls(this.inputControlsManager.getProfiles().get(position - 1));
        } else {
            hideInputControls();
        }
        updateProfile.run();
    }

    private void simulateConfirmInputControlsDialog() {
        boolean isShowTouchscreenControls = this.preferences.getBoolean("show_touchscreen_controls_enabled", false);
        this.inputControlsView.setShowTouchscreenControls(isShowTouchscreenControls);
        boolean isTimeoutEnabled = this.preferences.getBoolean("touchscreen_timeout_enabled", false);
        boolean isHapticsEnabled = this.preferences.getBoolean("touchscreen_haptics_enabled", false);
        SharedPreferences.Editor editor = this.preferences.edit();
        editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
        editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
        editor.apply();
        int selectedProfileIndex = this.preferences.getInt("selected_profile_index", -1);
        if (selectedProfileIndex >= 0 && selectedProfileIndex < this.inputControlsManager.getProfiles().size()) {
            ControlsProfile profile = this.inputControlsManager.getProfiles().get(selectedProfileIndex);
            showInputControls(profile);
        } else {
            hideInputControls();
        }
        if (isTimeoutEnabled && this.inputControlsView.getVisibility() == 0) {
            startTouchscreenTimeout();
        } else {
            this.touchpadView.setOnTouchListener(null);
        }
    }

    private void startTouchscreenTimeout() {
        boolean isTimeoutEnabled = this.preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            this.inputControlsView.setVisibility(0);
            this.touchpadView.setOnTouchListener(new View.OnTouchListener() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda36
                @Override // android.view.View.OnTouchListener
                public final boolean onTouch(View view, MotionEvent motionEvent) {
                    boolean lambda$startTouchscreenTimeout$37;
                    lambda$startTouchscreenTimeout$37 = XServerDisplayActivity.this.lambda$startTouchscreenTimeout$37(view, motionEvent);
                    return lambda$startTouchscreenTimeout$37;
                }
            });
            this.timeoutHandler.removeCallbacks(this.hideControlsRunnable);
            this.timeoutHandler.postDelayed(this.hideControlsRunnable, 5000L);
            return;
        }
        this.inputControlsView.setVisibility(0);
        this.timeoutHandler.removeCallbacks(this.hideControlsRunnable);
        this.touchpadView.setOnTouchListener(null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$startTouchscreenTimeout$37(View v, MotionEvent event) {
        int action = event.getAction();
        if (action == 0 || action == 2) {
            this.inputControlsView.setVisibility(0);
            this.timeoutHandler.removeCallbacks(this.hideControlsRunnable);
            this.timeoutHandler.postDelayed(this.hideControlsRunnable, 5000L);
        }
        return false;
    }

    private void showInputControls(ControlsProfile profile) {
        this.inputControlsView.setVisibility(0);
        this.inputControlsView.requestFocus();
        this.inputControlsView.setProfile(profile);
        this.touchpadView.setSensitivity(profile.getCursorSpeed() * this.globalCursorSpeed);
        this.touchpadView.setPointerButtonRightEnabled(false);
        this.inputControlsView.invalidate();
        this.winHandler.sendGamepadState();
    }

    private void hideInputControls() {
        this.inputControlsView.setShowTouchscreenControls(true);
        this.inputControlsView.setVisibility(8);
        this.inputControlsView.setProfile(null);
        this.touchpadView.setSensitivity(this.globalCursorSpeed);
        this.touchpadView.setPointerButtonLeftEnabled(true);
        this.touchpadView.setPointerButtonRightEnabled(true);
        this.inputControlsView.invalidate();
        this.winHandler.sendGamepadState();
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private void extractGraphicsDriverFiles() {
        char c;
        String adrenoToolsDriverId = this.graphicsDriverConfig.get("version");
        File rootDir = this.imageFs.getRootDir();
        if (this.dxwrapper.contains("dxvk")) {
            DXVKConfigDialog.setEnvVars(this, this.dxwrapperConfig, this.envVars);
            String version = this.dxwrapperConfig.get("version");
            if (version.equals("1.11.1-sarek")) {
                this.envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
            }
        } else {
            WineD3DConfigDialog.setEnvVars(this, this.dxwrapperConfig, this.envVars);
        }
        boolean useDRI3 = this.preferences.getBoolean("use_dri3", true);
        if (!useDRI3) {
            this.envVars.put("MESA_VK_WSI_DEBUG", "sw");
        }
        this.envVars.put("VK_ICD_FILENAMES", this.imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");
        this.envVars.put("GALLIUM_DRIVER", "zink");
        if (this.firstTimeBoot) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper.tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers.tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs.tzst", rootDir);
        }
        if (adrenoToolsDriverId != DefaultVersion.WRAPPER) {
            AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
            adrenotoolsManager.setDriverById(this.envVars, this.imageFs, adrenoToolsDriverId);
        }
        String vulkanVersion = this.graphicsDriverConfig.get("vulkanVersion");
        String vulkanVersionPatch = GPUInformation.getVulkanVersion(adrenoToolsDriverId, this).split("\\.")[2];
        this.envVars.put("WRAPPER_VK_VERSION", vulkanVersion + "." + vulkanVersionPatch);
        String blacklistedExtensions = this.graphicsDriverConfig.get("blacklistedExtensions");
        this.envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions);
        String gpuName = this.graphicsDriverConfig.get("gpuName");
        String dxvkVersion = this.dxwrapperConfig.get("version");
        if (!gpuName.equals("Device") && !dxvkVersion.equals("1.11.1-sarek")) {
            this.envVars.put("WRAPPER_DEVICE_NAME", gpuName);
            this.envVars.put("WRAPPER_DEVICE_ID", WineD3DConfigDialog.getDeviceIdFromGPUName(this, gpuName));
            this.envVars.put("WRAPPER_VENDOR_ID", WineD3DConfigDialog.getVendorIdFromGPUName(this, gpuName));
        }
        String maxDeviceMemory = this.graphicsDriverConfig.get("maxDeviceMemory");
        if (maxDeviceMemory != null && Integer.parseInt(maxDeviceMemory) > 0) {
            this.envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory);
        }
        String presentMode = this.graphicsDriverConfig.get("presentMode");
        if (presentMode.contains("immediate")) {
            this.envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1");
        }
        this.envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);
        String resourceType = this.graphicsDriverConfig.get("resourceType");
        this.envVars.put("WRAPPER_RESOURCE_TYPE", resourceType);
        String syncFrame = this.graphicsDriverConfig.get("syncFrame");
        if (syncFrame.equals("1")) {
            this.envVars.put("MESA_VK_WSI_DEBUG", "forcesync");
        }
        String disablePresentWait = this.graphicsDriverConfig.get("disablePresentWait");
        this.envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait);
        String bcnEmulation = this.graphicsDriverConfig.get("bcnEmulation");
        String bcnEmulationType = this.graphicsDriverConfig.get("bcnEmulationType");
        switch (bcnEmulation.hashCode()) {
            case 3005871:
                if (bcnEmulation.equals(DebugKt.DEBUG_PROPERTY_VALUE_AUTO)) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case 3154575:
                if (bcnEmulation.equals("full")) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            case 3387192:
                if (bcnEmulation.equals(Container.DEFAULT_DDRAWRAPPER)) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            default:
                c = 65535;
                break;
        }
        switch (c) {
            case 0:
                if (bcnEmulationType.equals("compute") && GPUInformation.getVendorID(null, null) != 20803) {
                    this.envVars.put("ENABLE_BCN_COMPUTE", "1");
                    this.envVars.put("BCN_COMPUTE_AUTO", "1");
                }
                this.envVars.put("WRAPPER_EMULATE_BCN", ExifInterface.GPS_MEASUREMENT_3D);
                break;
            case 1:
                if (bcnEmulationType.equals("compute") && GPUInformation.getVendorID(null, null) != 20803) {
                    this.envVars.put("ENABLE_BCN_COMPUTE", "1");
                    this.envVars.put("BCN_COMPUTE_AUTO", "0");
                }
                this.envVars.put("WRAPPER_EMULATE_BCN", ExifInterface.GPS_MEASUREMENT_2D);
                break;
            case 2:
                this.envVars.put("WRAPPER_EMULATE_BCN", "0");
                break;
            default:
                this.envVars.put("WRAPPER_EMULATE_BCN", "1");
                break;
        }
        String bcnEmulationCache = this.graphicsDriverConfig.get("bcnEmulationCache");
        this.envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache);
        if (!this.vkbasaltConfig.isEmpty()) {
            this.envVars.put("ENABLE_VKBASALT", "1");
            this.envVars.put("VKBASALT_CONFIG", this.vkbasaltConfig);
        }
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        boolean handledByWinHandler = false;
        boolean handledByTouchpadView = false;
        if (this.winHandler != null) {
            handledByWinHandler = this.winHandler.onGenericMotionEvent(event);
        }
        if (this.touchpadView != null) {
            handledByTouchpadView = this.touchpadView.onExternalMouseEvent(event);
        }
        boolean handledBySuper = super.dispatchGenericMotionEvent(event);
        return handledByWinHandler || handledByTouchpadView || handledBySuper;
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.core.app.ComponentActivity, android.app.Activity, android.view.Window.Callback
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean z = false;
        if (event.getAction() != 0 || (event.getKeyCode() != 110 && event.getKeyCode() != 3 && event.getKeyCode() != 109)) {
            return !(this.inputControlsView.onKeyEvent(event) || this.winHandler.onKeyEvent(event) || !this.xServer.keyboard.onKeyEvent(event)) || (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
        }
        if (this.inputControlsView.onKeyEvent(event) || (this.winHandler != null && this.winHandler.onKeyEvent(event) && this.xServer != null && this.xServer.keyboard.onKeyEvent(event))) {
            z = true;
        }
        return true;
    }

    public InputControlsView getInputControlsView() {
        return this.inputControlsView;
    }

    private void extractDXWrapperFiles(String dxwrapper) {
        String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll"};
        File rootDir = this.imageFs.getRootDir();
        File windowsDir = new File(rootDir, "/home/xuser/.wine/drive_c/windows");
        if (!dxwrapper.contains("dxvk")) {
            if (dxwrapper.contains("wined3d")) {
                restoreOriginalDllFiles(dlls);
                return;
            }
            return;
        }
        String dxvkWrapper = dxwrapper.split(";")[0];
        String vkd3dWrapper = dxwrapper.split(";")[1];
        String ddrawrapper = dxwrapper.split(";")[2];
        ContentProfile dxvkProfile = this.contentsManager.getProfileByEntryName(dxvkWrapper);
        if (dxvkProfile == null) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxvkWrapper + ".tzst", windowsDir, this.onExtractFileListener);
            if (compareVersion(dxvkWrapper, "2.4") < 0) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-1.0.tzst", windowsDir, this.onExtractFileListener);
            }
        } else {
            this.contentsManager.applyContent(dxvkProfile);
        }
        if (vkd3dWrapper.contains(DefaultVersion.VKD3D)) {
            restoreOriginalDllFiles("d3d12.dll", "d3d12core.dll");
        } else {
            ContentProfile vkd3dProfile = this.contentsManager.getProfileByEntryName(vkd3dWrapper);
            if (vkd3dProfile == null) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + vkd3dWrapper + ".tzst", windowsDir, this.onExtractFileListener);
            } else {
                this.contentsManager.applyContent(vkd3dProfile);
            }
        }
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir, this.onExtractFileListener);
        if (ddrawrapper.contains(DefaultVersion.VKD3D)) {
            restoreOriginalDllFiles("ddraw.dll", "d3dimm.dll");
            return;
        }
        if (ddrawrapper.equals("cnc-ddraw")) {
            this.envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");
        }
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, this.onExtractFileListener);
    }

    private static int compareVersion(String varA, String varB) {
        int[] a = parseSemverLoose(varA);
        int[] b = parseSemverLoose(varB);
        if (a[0] != b[0]) {
            return a[0] - b[0];
        }
        if (a[1] != b[1]) {
            return a[1] - b[1];
        }
        return a[2] - b[2];
    }

    private static int[] parseSemverLoose(String s) {
        if (s == null) {
            return new int[]{0, 0, 0};
        }
        Matcher m = SEMVER_LOOSE.matcher(s);
        String g1 = null;
        String g2 = null;
        String g3 = null;
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
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void extractWinComponentFiles() {
        JSONObject wincomponentsJSONObject;
        JSONObject wincomponentsJSONObject2;
        String str;
        File rootDir = this.imageFs.getRootDir();
        File windowsDir = new File(rootDir, "/home/xuser/.wine/drive_c/windows");
        File systemRegFile = new File(rootDir, "/home/xuser/.wine/system.reg");
        try {
            JSONObject wincomponentsJSONObject3 = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = this.shortcut != null ? this.shortcut.getExtra("wincomponents", this.container.getWinComponents()) : this.container.getWinComponents();
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(this.container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            Iterator<String[]> it = new KeyValueSet(wincomponents).iterator();
            while (it.hasNext()) {
                String[] wincomponent = it.next();
                if (!wincomponent[1].equals(oldWinComponentsIter.next()[1]) || this.firstTimeBoot) {
                    String identifier = wincomponent[0];
                    boolean useNative = wincomponent[1].equals("1");
                    if (useNative) {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/" + identifier + ".tzst", windowsDir, this.onExtractFileListener);
                        wincomponentsJSONObject = wincomponentsJSONObject3;
                    } else {
                        JSONArray dlnames = wincomponentsJSONObject3.getJSONArray(identifier);
                        int i = 0;
                        while (i < dlnames.length()) {
                            String dlname = dlnames.getString(i);
                            if (dlname.endsWith(".exe")) {
                                wincomponentsJSONObject2 = wincomponentsJSONObject3;
                                str = dlname;
                            } else {
                                wincomponentsJSONObject2 = wincomponentsJSONObject3;
                                str = dlname + ".dll";
                            }
                            dlls.add(str);
                            i++;
                            wincomponentsJSONObject3 = wincomponentsJSONObject2;
                        }
                        wincomponentsJSONObject = wincomponentsJSONObject3;
                    }
                    WineUtils.overrideWinComponentDlls(this, this.container, identifier, useNative);
                    WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, this);
                    wincomponentsJSONObject3 = wincomponentsJSONObject;
                }
            }
            if (!dlls.isEmpty()) {
                restoreOriginalDllFiles((String[]) dlls.toArray(new String[0]));
            }
        } catch (JSONException e) {
        }
    }

    private void restoreOriginalDllFiles(String... dlls) {
        File system32dlls;
        File rootDir = this.imageFs.getRootDir();
        File windowsDir = new File(rootDir, "/home/xuser/.wine/drive_c/windows");
        if (this.wineInfo.isArm64EC()) {
            system32dlls = new File(this.imageFs.getWinePath() + "/lib/wine/aarch64-windows");
        } else {
            system32dlls = new File(this.imageFs.getWinePath() + "/lib/wine/x86_64-windows");
        }
        File syswow64dlls = new File(this.imageFs.getWinePath() + "/lib/wine/i386-windows");
        for (String dll : dlls) {
            File srcFile = new File(system32dlls, dll);
            File dstFile = new File(windowsDir, "system32/" + dll);
            FileUtils.copy(srcFile, dstFile);
            File srcFile2 = new File(syswow64dlls, dll);
            File dstFile2 = new File(windowsDir, "syswow64/" + dll);
            FileUtils.copy(srcFile2, dstFile2);
        }
    }

    private String getWineStartCommand() {
        String args;
        String exeDir;
        String filename;
        EnvVars envVars = getOverrideEnvVars();
        if (this.shortcut != null) {
            String execArgs = this.shortcut.getExtra("execArgs");
            String execArgs2 = !execArgs.isEmpty() ? " " + execArgs : "";
            if (this.shortcut.path.endsWith(".lnk")) {
                args = "\"" + this.shortcut.path + "\"" + execArgs2;
            } else {
                String fullPath = this.shortcut.path.replace("\"", "");
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
                int spaceIndex = dotIndex != -1 ? filename.indexOf(" ", dotIndex) : -1;
                if (spaceIndex != -1) {
                    execArgs2 = filename.substring(spaceIndex + 1) + execArgs2;
                    filename = filename.substring(0, spaceIndex);
                }
                args = "/dir " + StringUtils.escapeDOSPath(exeDir) + " \"" + filename + "\"" + execArgs2;
            }
        } else if (!envVars.has("EXTRA_EXEC_ARGS")) {
            args = "\"wfm.exe\"";
        } else {
            args = " " + envVars.get("EXTRA_EXEC_ARGS");
            envVars.remove("EXTRA_EXEC_ARGS");
        }
        String command = "winhandler.exe " + args;
        return command;
    }

    private String getExecutable() {
        if (this.shortcut == null || this.shortcut.path == null) {
            return "wfm.exe";
        }
        String cleanPath = this.shortcut.path.replace("\"", "");
        int lastSlash = cleanPath.lastIndexOf(47);
        int lastBackslash = cleanPath.lastIndexOf(92);
        int lastSeparator = Math.max(lastSlash, lastBackslash);
        if (lastSeparator != -1) {
            String filename = cleanPath.substring(lastSeparator + 1);
            return filename;
        }
        return cleanPath;
    }

    public XServer getXServer() {
        return this.xServer;
    }

    public WinHandler getWinHandler() {
        return this.winHandler;
    }

    public XServerView getXServerView() {
        return this.xServerView;
    }

    public Container getContainer() {
        return this.container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (this.overrideEnvVars == null) {
            this.overrideEnvVars = new EnvVars();
        }
        return this.overrideEnvVars;
    }

    private void changeWineAudioDriver() {
        if (!this.audioDriver.equals(this.container.getExtra("audioDriver"))) {
            File rootDir = this.imageFs.getRootDir();
            File userRegFile = new File(rootDir, "/home/xuser/.wine/user.reg");
            WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile);
            try {
                if (this.audioDriver.equals(Container.DEFAULT_AUDIO_DRIVER)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", Container.DEFAULT_AUDIO_DRIVER);
                } else if (this.audioDriver.equals("pulseaudio")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
                registryEditor.close();
                this.container.putExtra("audioDriver", this.audioDriver);
                this.container.saveData();
            } catch (Throwable th) {
                try {
                    registryEditor.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = this.imageFs.getRootDir();
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern_common.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, this.wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void assignTaskAffinity(Window window) {
        if (this.taskAffinityMask == 0 || this.taskAffinityMaskWoW64 == 0) {
            return;
        }
        int processId = window.getProcessId();
        String className = window.getClassName();
        int processAffinity = window.isWoW64() ? this.taskAffinityMaskWoW64 : this.taskAffinityMask;
        if (processId > 0) {
            this.winHandler.setProcessAffinity(processId, processAffinity);
        } else if (!className.isEmpty()) {
            this.winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void changeFrameRatingVisibility(Window window, final Property property) {
        if (this.frameRating == null) {
            return;
        }
        if (property != null) {
            if (property.nameAsString().contains("_MESA_DRV_ENGINE_NAME")) {
                this.frameRatingWindowId = window.id;
                runOnUiThread(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda2
                    @Override // java.lang.Runnable
                    public final void run() {
                        XServerDisplayActivity.this.lambda$changeFrameRatingVisibility$38(property);
                    }
                });
            }
            if (this.frameRatingWindowId == window.id) {
                if (property.nameAsString().contains("_MESA_DRV_GPU_NAME")) {
                    runOnUiThread(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda3
                        @Override // java.lang.Runnable
                        public final void run() {
                            XServerDisplayActivity.this.lambda$changeFrameRatingVisibility$39(property);
                        }
                    });
                }
                if (property.nameAsString().contains("_MESA_DRV")) {
                    this.frameRating.update();
                    return;
                }
                return;
            }
            return;
        }
        if (this.frameRatingWindowId == window.id) {
            this.frameRatingWindowId = -1;
            runOnUiThread(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    XServerDisplayActivity.this.lambda$changeFrameRatingVisibility$40();
                }
            });
            runOnUiThread(new Runnable() { // from class: com.winlator.cmod.XServerDisplayActivity$$ExternalSyntheticLambda5
                @Override // java.lang.Runnable
                public final void run() {
                    XServerDisplayActivity.this.lambda$changeFrameRatingVisibility$41();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$changeFrameRatingVisibility$38(Property property) {
        this.frameRating.setRenderer(property.toString());
        this.frameRating.setVisibility(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$changeFrameRatingVisibility$39(Property property) {
        this.frameRating.setGpuName(property.toString());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$changeFrameRatingVisibility$40() {
        this.frameRating.setVisibility(8);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$changeFrameRatingVisibility$41() {
        this.frameRating.reset();
    }
}
