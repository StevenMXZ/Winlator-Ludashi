package com.winlator;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.WindowManager.LayoutParams.*;
import static com.winlator.CmdEntryPoint.ACTION_START;
import static com.winlator.XServerDisplayActivity.compareVersion;
import static com.winlator.XServerDisplayActivity.startVirGLTestServer;
import static com.winlator.input.InputStub.BUTTON_LEFT;
import static com.winlator.input.InputStub.BUTTON_MIDDLE;
import static com.winlator.input.InputStub.BUTTON_RIGHT;
import static com.winlator.inputcontrols.XKeyCodes.getMapping;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import com.winlator.box86_64.rc.RCFile;
import com.winlator.box86_64.rc.RCManager;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVK_VKD3DConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.NavigationDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUInformation;
import com.winlator.core.KeyValueSet;
import com.winlator.core.OnExtractFileListener;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.input.InputEventSender;
import com.winlator.input.TouchInputHandler;
import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.inputcontrols.XKeyCodes;
import com.winlator.midi.MidiHandler;
import com.winlator.midi.MidiManager;
import com.winlator.renderer.GLRenderer;
import com.winlator.widget.FrameRating;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.TaskManagerDialogX11;
import com.winlator.winhandler.WinHandlerX11;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GlibcProgramLauncherComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;

public class X11Activity extends AppCompatActivity implements View.OnApplyWindowInsetsListener, NavigationView.OnNavigationItemSelectedListener {
    public static Handler handler = new Handler();
    private static TouchInputHandler mInputHandler;
    protected ICmdEntryInterface service = null;
    static InputMethodManager inputMethodManager;
    public static boolean externalKeyboardConnected = false;
    private View.OnKeyListener mLorieKeyListener;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener = (__, key) -> onPreferencesChanged();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_START.equals(intent.getAction())) {
                try {
                    Log.v("LorieBroadcastReceiver", "Got new ACTION_START intent");
                    onReceiveConnection(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "Something went wrong while we extracted connection details from binder.", e);
                }
            }
        }
    };

    @SuppressLint("StaticFieldLeak")
    private static X11Activity instance;

    public X11Activity() {
        instance = this;
    }

    public static X11Activity getInstance() {
        return instance;
    }

    private boolean emulationPaused = false;
    private DrawerLayout drawerLayout;
    //private VirtualKeyboardInputView virtualKeyboardInputView;
    //private VirtualControllerInputView virtualControllerInputView;
    private static boolean mInputHandlerRunning = false;

    private boolean navigationFocused = false;
    private boolean firstTimeBoot = false;
    private boolean capturePointerOnExternalMouse = true;
    private boolean useOldVirGL;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private int frameRatingWindowId = -1;
    private float globalCursorSpeed = 1.0f;
    private static final float MouseWheelDistance = 200.0f;
    private static final int inputControlsViewDelay = 1000;

    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String screenSize;

    private KeyValueSet dxwrapperConfig;
    private KeyValueSet graphicsDriverConfig;

    PreloaderDialog preloaderDialog = null;
    private SharedPreferences preferences;

    private ContentsManager contentsManager;
    private ContainerManager containerManager;
    private DebugDialog debugDialog;
    private XEnvironment environment;
    private ImageFs imageFs;
    private WineInfo wineInfo;
    private Shortcut shortcut;
    private InputControlsManager inputControlsManager;
    private FrameRating frameRating;
    private ScreenInfo screenInfo;
    private final EnvVars envVars = new EnvVars();
    protected Container container;
    private final WinHandlerX11 winHandlerX11 = new WinHandlerX11(this);
    private MidiHandler midiHandler;
    private OnExtractFileListener onExtractFileListener;
    private Runnable configChangedCallback = null;
    private Runnable editInputControlsCallback;

    private MagnifierView magnifierView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;

    private XServer xServer;
    private XServerView xServerView;

    @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag", "SetTextI18n"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences != null) {
            preferences.registerOnSharedPreferenceChangeListener(preferencesChangedListener);
        }

        getWindow().setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.x11_activity);

        drawerLayout = findViewById(R.id.DrawerLayoutX11);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        LorieView lorieView = findViewById(R.id.lorieView);
        View lorieParent = (View) lorieView.getParent();

        /// Initialization
        preloaderDialog = new PreloaderDialog(this);

        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        NavigationView navigationView = findViewById(R.id.NavigationViewX11);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getBoolean("enable_box86_64_logs", false);
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.main_menu_logs).setVisible(enableLogs);
        //if (XrActivity.isEnabled(this)) // TODO
            menu.findItem(R.id.main_menu_magnifier).setVisible(false);
        navigationView.setNavigationItemSelectedListener(this);
        navigationView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_ARROW));
        navigationView.setOnFocusChangeListener((v, hasFocus) -> navigationFocused = hasFocus);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                navigationView.requestFocus();
            }
        });

        imageFs = ImageFs.find(this);

        screenSize = Container.DEFAULT_SCREEN_SIZE;

        if (!isGenerateWineprefix()) {
            containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            containerManager.activateContainer(container);

            taskAffinityMask = (short)ProcessHelper.getAffinityMask(container.getCPUList(true));
            taskAffinityMaskWoW64 = (short)ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));
            firstTimeBoot = container.getExtra("appVersion").isEmpty();

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_VERSION) imageFs.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) {
                try {
                    shortcut = new Shortcut(container, new File(shortcutPath));
                } catch (Exception e) {
                    finish();
                    System.exit(0);
                }
            }

            // Retrieve secondary executable and delay
            String secondaryExec = shortcut != null ? shortcut.getExtra("secondaryExec") : null;
            int execDelay = shortcut != null ? Integer.parseInt(shortcut.getExtra("execDelay", "1")) : 1;

            // Debug logging for secondaryExec and execDelay
            Log.d("X11Activity", "Secondary Exec: " + secondaryExec);
            Log.d("X11Activity", "Execution Delay: " + execDelay);

            // If a secondary executable is specified, schedule it
            if (secondaryExec != null && !secondaryExec.isEmpty() && execDelay > 0) {
                scheduleSecondaryExecution(secondaryExec, execDelay);
                Log.d("X11Activity", "Scheduling secondary execution: " + secondaryExec + " with delay: " + execDelay);
            } else {
                Log.d("X11Activity", "No valid secondary executable or delay is zero, skipping scheduling.");
            }

            graphicsDriver = container.getGraphicsDriver();
            audioDriver = container.getAudioDriver();
            midiSoundFont = container.getMIDISoundFont();
            dxwrapper = container.getDXWrapper();
            String dxwrapperConfig = container.getDXWrapperConfig();
            graphicsDriverConfig = new KeyValueSet(container.getGraphicsDriverConfig());
            screenSize = container.getScreenSize();
            winHandlerX11.setInputType((byte) container.getInputType());
            lc_all = container.getLC_ALL();

            useOldVirGL = graphicsDriverConfig.getBoolean("useOldVirGL", false);

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                midiSoundFont = shortcut.getExtra("midiSoundFont", container.getMIDISoundFont());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = new KeyValueSet(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
                screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
                lc_all = shortcut.getExtra("lc_all", container.getLC_ALL());

                String inputType = shortcut.getExtra("inputType");
                if (!inputType.isEmpty()) winHandlerX11.setInputType(Byte.parseByte(inputType));
            }

            if (dxwrapper.contains("dxvk") || dxwrapper.contains("vkd3d") || dxwrapper.contains("wined3d"))
                this.dxwrapperConfig = DXVK_VKD3DConfigDialog.parseConfig(dxwrapperConfig);

            if (!wineInfo.isWin64()) {
                onExtractFileListener = (file, size) -> {
                    String path = file.getPath();
                    if (path.contains("system32/")) return null;
                    return new File(path.replace("syswow64/", "system32/"));
                };
            }
        }

        inputControlsManager = new InputControlsManager(this);

        xServer = new XServer(new ScreenInfo(screenSize));

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
                    try {
                        finalIn.close();
                    } catch (Exception e2) {}
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

        screenInfo = new ScreenInfo(screenSize);

        Runnable runnable = () -> {
            setupUI();

            Executors.newSingleThreadExecutor().execute(() -> {
                if (!isGenerateWineprefix()) {
                    setupWineSystemFiles();
                    extractGraphicsDriverFiles();
                    changeWineAudioDriver();
                }
                setupXEnvironment();
            });
        };

        if (screenInfo.height > screenInfo.width) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            configChangedCallback = runnable;
        } else
            runnable.run();

        SharedPreferences.Editor ed = Objects.requireNonNull(preferences).edit();
        ed.putString("displayResolutionExact", screenSize);
        ed.putString("displayResolutionMode", "exact");
        ed.putBoolean("forceLandscape", true);
        ed.putBoolean("displayStretch", false);
        ed.apply();

        preferences.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> onPreferencesChanged());
        ///

        Set<Integer> pressedKeys = new HashSet<>();
        mInputHandler = new TouchInputHandler(this, new InputEventSender(lorieView));
        mLorieKeyListener = (v, k, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                pressedKeys.add(k);
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                pressedKeys.remove(k);
            }

            if (pressedKeys.contains(KeyEvent.KEYCODE_ALT_LEFT) && pressedKeys.contains(KeyEvent.KEYCODE_Q)) {
                if (lorieView.hasPointerCapture()) {
                    lorieView.releasePointerCapture();
                }
            }

            if (k == KeyEvent.KEYCODE_ESCAPE && !(lorieView.hasPointerCapture())) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.openDrawer(GravityCompat.START);
                } else {
                    drawerLayout.closeDrawers();
                }
            }

            if (k == KeyEvent.KEYCODE_BACK) {
                ///
                if (e.getAction() == KeyEvent.ACTION_UP)
                    if (environment != null)
                        (new NavigationDialog(this)).show();
                ///
                if (e.getScanCode() == 153 && e.getDevice().getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC || e.getScanCode() == 0) {
                    boolean pointerCaptured = lorieView.hasPointerCapture();
                    if (pointerCaptured) {
                        lorieView.releasePointerCapture();
                    }
                    if (e.getAction() == KeyEvent.ACTION_UP) {
                        if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            ///drawerLayout.openDrawer(GravityCompat.START);
                        } else {
                            ///drawerLayout.closeDrawers();
                        }
                    }

                    inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);

                    return true;
                }
            } else if (k == KeyEvent.KEYCODE_VOLUME_DOWN) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            } else if (k == KeyEvent.KEYCODE_VOLUME_UP) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;
            }

            return mInputHandler.sendKeyEvent(e);
        };

        lorieParent.setOnTouchListener((v, e) -> {
            // Avoid batched MotionEvent objects and reduce potential latency.
            // For reference: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features#rendering.
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                lorieParent.requestUnbufferedDispatch(e);
            }

            switch (e.getButtonState()) {
                case MotionEvent.BUTTON_PRIMARY: {
                    lorieView.requestPointerCapture();
                    //Toast.makeText(this, getString(R.string.mouse_captured), Toast.LENGTH_SHORT).show();
                    break;
                }
                case MotionEvent.BUTTON_SECONDARY: {
                    if (!lorieView.hasPointerCapture()) {
                        if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            drawerLayout.openDrawer(GravityCompat.START);
                        } else {
                            drawerLayout.closeDrawers();
                        }

                        inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);

                        return true;
                    }
                }
            }

            return mInputHandler.handleTouchEvent(lorieParent, lorieView, e);
        });
        lorieParent.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieParent.setOnGenericMotionListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieView.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieParent.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((sfc, surfaceWidth, surfaceHeight, screenWidth, screenHeight) -> {
            String name;
            int frameRate = (int) ((lorieView.getDisplay() != null) ? lorieView.getDisplay().getRefreshRate() : 30);

            mInputHandler.handleHostSizeChanged(surfaceWidth, surfaceHeight);
            mInputHandler.handleClientSizeChanged(screenWidth, screenHeight);
            if (lorieView.getDisplay() == null || lorieView.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY) {
                name = "Builtin Display";
            } else {
                name = "External Display";
            }
            LorieView.sendWindowChange(screenWidth, screenHeight, frameRate, name);

            if (service != null && !LorieView.renderingInActivity()) {
                try {
                    service.windowChanged(sfc);
                } catch (RemoteException e) {
                    Log.e("X11Activity", "failed to send windowChanged request", e);
                }
            }
        });

        mInputHandlerRunning = true;

        lorieView.setOnFocusChangeListener((view, b) -> {
            if (!lorieView.isInLayout()) {
                lorieView.requestLayout();
            }
        });

        registerReceiver(receiver, new IntentFilter(ACTION_START), SDK_INT >= VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0);

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        tryConnect();
        onPreferencesChanged();

        if (SDK_INT >= VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 0);
        }

        onReceiveConnection(getIntent());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        getLorieView().requestFocus();

        mLorieKeyListener.onKey(null, keyCode, event);

        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event != null) {

        }
        return true;
    }

    @Override
    protected void onDestroy() {
        ///
        winHandlerX11.stop();
        if (midiHandler != null)
            midiHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        if (preloaderDialog != null && preloaderDialog.isShowing())
            preloaderDialog.close();
        ///
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    public void onReceiveConnection(Intent intent) {
        Bundle bundle = intent.getBundleExtra(null);
        if (bundle == null) return;
        IBinder ibinder = bundle.getBinder(null);
        if (ibinder == null) return;

        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            service.asBinder().linkToDeath(() -> {
                service = null;

                Log.v("Lorie", "Disconnected");
                runOnUiThread(() -> { LorieView.connect(-1); clientConnectedStateChanged();} );
            }, 0);
        } catch (RemoteException ignored) {}

        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    LorieView.startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != getIntent())
                    getIntent().putExtra(null, bundle);
            }
        } catch (Exception e) {
            Log.e("X11Activity", "Something went wrong while we were establishing connection", e);
        }
    }

    public void tryConnect() {
        if (LorieView.connected())
            return;

        if (service == null) {
            LorieView.requestConnection();
            handler.postDelayed(this::tryConnect, 250);
            return;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                LorieView.connect(fd.detachFd());
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
            } else
                handler.postDelayed(this::tryConnect, 250);
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            // We should reset the View for the case if we have sent it's surface to the client.
            getLorieView().regenerate();
            handler.postDelayed(this::tryConnect, 250);
        }
    }

    public void onPreferencesChanged() {
        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
    }

    @SuppressLint("UnsafeIntentLaunch")
    public void onPreferencesChangedCallback() {
        onWindowFocusChanged(hasWindowFocus());
        LorieView lorieView = getLorieView();

        lorieView.triggerCallback();

        lorieView.requestLayout();
        lorieView.invalidate();
    }

    @Override
    public void onResume() {
        super.onResume();
        ///
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        ///
        getLorieView().requestFocus();

        //virtualKeyboardInputView.loadPreset(getSelectedVirtualControllerPreset(selectedGameName));

        //prepareControllersMappings();
    }

    @Override
    public void onPause() {
        ///
        if (!isInPictureInPictureMode()) {
            if (environment != null) {
                environment.onPause();
                xServerView.onPause();
            }
        }
        ///
        inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        super.onPause();
    }

    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public boolean handleKey(KeyEvent e) {
        return mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
    }
    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (preferences.getBoolean("forceLandscape", true))
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        else
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());

        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );

        if (hasFocus) {
            getLorieView().regenerate();
        }

        getLorieView().requestFocus();
    }

    @NonNull
    @Override
    public WindowInsets onApplyWindowInsets(@NonNull View v, @NonNull WindowInsets insets) {
        handler.postDelayed(() -> getLorieView().triggerCallback(), 100);
        return insets;
    }

    private void clientConnectedStateChanged() {
        runOnUiThread(()-> {
            boolean connected = LorieView.connected();

            getLorieView().setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
            getLorieView().regenerate();

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected)
                tryConnect();
        });
    }

    public WinHandlerX11 getWinHandlerX11() {
        return winHandlerX11;
    }

    public Container getContainer() {
        return container;
    }

    public XServer getXServer() {
        return xServer;
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private void exit() {
        winHandlerX11.stop();
        ////midiHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        AppUtils.restartApplication(this);
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        //final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.main_menu_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_toggle_fullscreen:
                //renderer.toggleFullscreen(); //
                if (preferences.getBoolean("displayStretch", false)) {
                    SharedPreferences.Editor ed = Objects.requireNonNull(preferences).edit();
                    ed.putBoolean("displayStretch", false);
                    ed.apply();
                }
                else {
                    SharedPreferences.Editor ed = Objects.requireNonNull(preferences).edit();
                    ed.putBoolean("displayStretch", true);
                    ed.apply();
                }
                drawerLayout.closeDrawers();
                touchpadView.toggleFullscreen();
                break;
            case R.id.main_menu_toggle_orientation:
                if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    SharedPreferences.Editor ed = Objects.requireNonNull(preferences).edit();
                    ed.putBoolean("forceLandscape", false);
                    ed.apply();
                }
                else {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    SharedPreferences.Editor ed = Objects.requireNonNull(preferences).edit();
                    ed.putBoolean("forceLandscape", true);
                    ed.apply();
                }
                ControlsProfile profile = inputControlsView.getProfile();
                int id = profile == null ? -1 : profile.id;
                configChangedCallback = () -> {
                    if (profile != null) {
                        inputControlsManager = new InputControlsManager(this);
                        inputControlsManager.loadProfiles(true);
                        showInputControls(inputControlsManager.getProfile(id));
                    }
                };
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_task_manager:
                (new TaskManagerDialogX11(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.frame);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        //renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        //magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    //magnifierView.setZoomValue(renderer.getMagnifierZoom()); // TODO
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_pip_mode:
                enterPictureInPictureMode();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.main_menu_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.main_menu_exit:
                exit();
                break;
        }
        return true;
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
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

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbSimTouchScreen = dialog.findViewById(R.id.CBSimulateTouchScreen);
        cbSimTouchScreen.setChecked(touchpadView.isSimTouchScreen());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

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
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
                updateProfile.run();
            };
            controlsEditorActivityResultLauncher.launch(intent);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            touchpadView.setSimTouchScreen(cbSimTouchScreen.isChecked());
            updateProfile.run();
        });

        dialog.setOnCancelCallback(updateProfile::run);

        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
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

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        inputControlsView.invalidate();
    }

    private void scheduleSecondaryExecution(String secondaryExec, int delaySeconds) {
        if (winHandlerX11 != null) {
            winHandlerX11.execWithDelay(secondaryExec, delaySeconds);
            Log.d("X11Activity", "Scheduled secondary execution: " + secondaryExec + " with delay: " + delaySeconds);
        } else {
            Log.e("X11Activity", "WinHandler is null, cannot schedule secondary execution.");
        }
    }

    // TODO
    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.frame);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);

        /*if (shortcut != null) {
            if (shortcut.getExtra("forceFullscreen", "0").equals("1")) renderer.setForceFullscreenWMClass(shortcut.wmClass);
            renderer.setUnviewableWMClasses("explorer.exe");
        }*/

        xServer.setRenderer(renderer);
        //rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setFourFingersTapCallback(() -> {
            //if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
            if (environment != null) {
                (new NavigationDialog(this)).show();
            }
        });
        //rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        if (container != null && container.isShowFPS()) {
            frameRating = new FrameRating(this);
            frameRating.setVisibility(View.GONE);
            rootView.addView(frameRating);
        }

        boolean simTouchScreenMain = preferences.getBoolean("simTouchScreen", true);
        touchpadView.setSimTouchScreen(simTouchScreenMain);

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    public void run() {
                        if (profile != null) showInputControls(profile);
                    }
                }, inputControlsViewDelay);
            }

            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchpadView.setSimTouchScreen(simTouchScreen.equals("1") || simTouchScreenMain);
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
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

        String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String dxwrapper = this.dxwrapper;
        String dxwrapper2 = "";
        String enableDgVoodooDDraw = "";
        String enableDgVoodooD3D89 = "";
        if (dxwrapper.contains("dxvk") || dxwrapper.contains("vkd3d")) {
            dxwrapper = "dxvk-" + dxwrapperConfig.get("dxvk_version");
            dxwrapper2 = "vkd3d-" + dxwrapperConfig.get("vkd3dVersion");
            enableDgVoodooDDraw = dxwrapperConfig.get("enableDgVoodooDDraw");
            enableDgVoodooD3D89 = dxwrapperConfig.get("enableDgVoodooD3D89");
        } else if (dxwrapper.contains("wined3d")) {
            dxwrapper = "wined3d-" + dxwrapperConfig.get("wined3d_version");
        }

        if (!dxwrapper.equals(container.getExtra("dxwrapper")) ||
                !dxwrapper2.equals(container.getExtra("dxwrapper2")) ||
                !enableDgVoodooDDraw.equals(container.getExtra("enableDgVoodooDDraw")) ||
                !enableDgVoodooD3D89.equals(container.getExtra("enableDgVoodooD3D89")) ) {
            extractDXWrapperFiles(dxwrapper);
            if (!dxwrapper2.isEmpty())
                extractDXWrapperFiles(dxwrapper2);

            container.putExtra("dxwrapper", dxwrapper);
            container.putExtra("dxwrapper2", dxwrapper2);
            container.putExtra("enableDgVoodooDDraw", enableDgVoodooDDraw);
            container.putExtra("enableDgVoodooD3D89", enableDgVoodooD3D89);
            containerDataChanged = true;
        }

        if (dxwrapper.equals("cnc-ddraw")) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection() != Container.STARTUP_SELECTION_NORMAL);
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "imagefs_patches.tzst", rootDir, onExtractFileListener);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void extractWinComponentFiles() {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();

            if (firstTimeBoot) {
                for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(wincomponent[0]);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                    }
                }

                cloneOriginalDllFiles(dlls.toArray(new String[0]));
                dlls.clear();
            }

            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
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

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!dlls.isEmpty()) restoreOriginalDllFiles(dlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void cloneOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File cacheDir = new File(rootDir, ImageFs.CACHE_PATH+"/original_dlls");
        if (!cacheDir.isDirectory()) cacheDir.mkdirs();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        String[] dirnames = {"system32", "syswow64"};

        for (String dll : dlls) {
            for (String dirname : dirnames) {
                File dllFile = new File(windowsDir, dirname+"/"+dll);
                if (dllFile.isFile()) FileUtils.copy(dllFile, new File(cacheDir, dirname+"/"+dll));
            }
        }
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File cacheDir = new File(rootDir, ImageFs.CACHE_PATH+"/original_dlls");
        if (cacheDir.isDirectory()) {
            File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
            String[] dirnames = cacheDir.list();
            int filesCopied = 0;

            for (String dll : dlls) {
                boolean success = false;
                for (String dirname : dirnames) {
                    File srcFile = new File(cacheDir, dirname+"/"+dll);
                    File dstFile = new File(windowsDir, dirname+"/"+dll);
                    if (FileUtils.copy(srcFile, dstFile)) success = true;
                }
                if (success) filesCopied++;
            }

            if (filesCopied == dlls.length) return;
        }

        containerManager.extractContainerPatternFile(container.getWineVersion(), container.getRootDir(), (file, size) -> {
            String path = file.getPath();
            if (path.contains("system32/") || path.contains("syswow64/")) {
                for (String dll : dlls) {
                    if (path.endsWith("system32/"+dll) || path.endsWith("syswow64/"+dll)) return file;
                }
            }
            return null;
        });

        cloneOriginalDllFiles(dlls);
    }

    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
        if (firstTimeBoot) {
            cloneOriginalDllFiles(dlls);
            firstTimeBoot = false;
        }
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");

        switch (dxwrapper) {
            case "cnc-ddraw":
                restoreOriginalDllFiles(dlls);
                final String assetDir = "dxwrapper/cnc-ddraw-"+ DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir, onExtractFileListener);
                break;
            case "vkd3d":
                // FIXME: maybe we need first boot config here
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/dxvk-"+DefaultVersion.DXVK+".tzst", windowsDir, onExtractFileListener);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/vkd3d-"+DefaultVersion.VKD3D+".tzst", windowsDir, onExtractFileListener);
                break;
            default:
                if (dxwrapper.startsWith("dxvk")) {
                    restoreOriginalDllFiles("d3d12.dll", "d3d12core.dll", "ddraw.dll");
                    ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
                    if (profile != null)
                        contentsManager.applyContent(profile);
                    else {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxwrapper + ".tzst", windowsDir, onExtractFileListener);
                        // d8vk merged into dxvk since dxvk-2.4, so we don't need to extract d8vk after that
                        if (compareVersion(StringUtils.parseNumber(dxwrapper), "2.4") < 0)
                            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir, onExtractFileListener);

                        if (dxwrapperConfig.get("enableDgVoodooDDraw").equals("1"))
                            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/dgVoodoo-" + DefaultVersion.DGVOODOO + ".tzst", windowsDir, onExtractFileListener);

                        if (dxwrapperConfig.get("enableDgVoodooD3D89").equals("1"))
                            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/dgVoodoo-" + DefaultVersion.DGVOODOO + "-d3d.tzst", windowsDir, onExtractFileListener);
                    }
                } else if (dxwrapper.startsWith("vkd3d")) {
                    ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
                    if (profile != null)
                        contentsManager.applyContent(profile);
                    else
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxwrapper + ".tzst", windowsDir, onExtractFileListener);
                } else if (dxwrapper.startsWith("wined3d")) {
                    ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
                    restoreOriginalDllFiles(dlls);
                    if (profile != null)
                        contentsManager.applyContent(profile);
                    else {
                        if (!(dxwrapper.equals("wined3d-"+DefaultVersion.WINED3D)))
                            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxwrapper + ".tzst", windowsDir, onExtractFileListener);
                    }
                }
                break;
        }
    }

    private void extractGraphicsDriverFiles() {
        int cacheContainerId = preferences.getInt("cache_container_id", 0);
        String cacheDriverId = container.getExtra("graphicsDriver");
        String cacheOldVirGL = container.getExtra("useOldVirGL", "false");
        int cacheVortekRenderVersion = Integer.parseInt(container.getExtra("vortek_render_version", "0"));

        VortekRendererComponent.Options vortekOptions = VortekRendererComponent.Options.fromKeyValueSet(this.graphicsDriverConfig);

        boolean changed = (!cacheDriverId.equals(graphicsDriver)) || (cacheContainerId != container.id) ||
                (!cacheOldVirGL.equals(String.valueOf(useOldVirGL))) || (cacheVortekRenderVersion != vortekOptions.renderVersion);
        File rootDir = imageFs.getRootDir();

        if (changed) {
            FileUtils.delete(new File(imageFs.getLib64Dir(), "libvulkan_freedreno.so"));
            FileUtils.delete(new File(imageFs.getLib64Dir(), "libvulkan_lvp.so"));
            FileUtils.delete(new File(imageFs.getLib64Dir(), "libvulkan_vortek.so"));
            FileUtils.delete(new File(imageFs.getLib64Dir(), "libGL.so.1"));
            container.putExtra("graphicsDriver", graphicsDriver);
            container.putExtra("useOldVirGL", useOldVirGL);
            container.putExtra("vortek_render_version", vortekOptions.renderVersion);
            container.saveData();
            preferences.edit().putInt("cache_container_id", container.id).apply();
        }

        if (graphicsDriver.startsWith("turnip")) {
            if (dxwrapper.contains("dxvk") || dxwrapper.contains("vkd3d"))
                DXVK_VKD3DConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);

            envVars.put("GALLIUM_DRIVER", "zink");
            envVars.put("TU_OVERRIDE_HEAP_SIZE", "4096");
            if (!envVars.has("MESA_VK_WSI_PRESENT_MODE")) envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
            envVars.put("vblank_mode", "0");

            if (!GPUInformation.isAdreno6xx(this)) {
                EnvVars userEnvVars = new EnvVars(container.getEnvVars());
                String tuDebug = userEnvVars.get("TU_DEBUG");
                if (!tuDebug.contains("sysmem")) userEnvVars.put("TU_DEBUG", (!tuDebug.isEmpty() ? tuDebug+"," : "")+"sysmem");
                container.setEnvVars(userEnvVars.toString());
            }

            boolean useDRI3 = preferences.getBoolean("use_dri3", true);
            if (!useDRI3) {
                envVars.put("MESA_VK_WSI_PRESENT_MODE", "immediate");
                envVars.put("MESA_VK_WSI_DEBUG", "sw");
            }

            if (changed) {
                ContentProfile profile = contentsManager.getProfileByEntryName(graphicsDriver);
                if (profile != null) {
                    contentsManager.applyContent(profile);
                } else {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/turnip-" + DefaultVersion.TURNIP + ".tzst", rootDir);
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-" + DefaultVersion.ZINK + ".tzst", rootDir);
                }
            }
        }
        else if (graphicsDriver.startsWith("virgl")) {
            envVars.put("GALLIUM_DRIVER", "virpipe");
            envVars.put("VIRGL_NO_READBACK", "true");
            envVars.put("VIRGL_SERVER_PATH", rootDir + UnixSocketConfig.VIRGL_SERVER_PATH);
            envVars.put("vblank_mode", "0");
            VirGLConfigDialog.setEnvVars(this.graphicsDriverConfig, this.envVars);
            if (changed) {
                ContentProfile profile = contentsManager.getProfileByEntryName(graphicsDriver);
                if (profile != null)
                    contentsManager.applyContent(profile);
                else {
                    if (!useOldVirGL)
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-" + DefaultVersion.VIRGL + ".tzst", rootDir);
                    else
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-old-" + DefaultVersion.VIRGL + ".tzst", rootDir);
                }
            }
        }
        else if (graphicsDriver.startsWith("vortek")) {
            envVars.put("GALLIUM_DRIVER", "zink");
            envVars.put("ZINK_CONTEXT_THREADED", "1");
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");
            envVars.put("WINEVKUSEPLACEDADDR", "1");
            envVars.put("VORTEK_SERVER_PATH", rootDir + UnixSocketConfig.VORTEK_SERVER_PATH);
            if (dxwrapper.contains("dxvk") || dxwrapper.contains("vkd3d")) {
                dxwrapperConfig.put("constantBufferRangeCheck", "1");
                DXVK_VKD3DConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
            }

            if (changed) {
                if (vortekOptions.renderVersion == 2)
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-2.1.tzst", rootDir);
                else
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);

                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-" + DefaultVersion.ZINK + ".tzst", rootDir);
            }
        }
        else if (graphicsDriver.startsWith("freedreno")) {
            envVars.put("GALLIUM_DRIVER", "freedreno");
            envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
            envVars.put("vblank_mode", "0");

            if (changed) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/freedreno-" + DefaultVersion.FREEDRENO + ".tzst", rootDir);
            }
        }
        else if (graphicsDriver.startsWith("llvmpipe")) {
            if (dxwrapper.contains("dxvk") || dxwrapper.contains("vkd3d"))
                DXVK_VKD3DConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);

            boolean useDRI3 = preferences.getBoolean("use_dri3", true);
            if (!useDRI3) {
                envVars.put("MESA_VK_WSI_PRESENT_MODE", "immediate");
                envVars.put("MESA_VK_WSI_DEBUG", "sw");
            }

            if (changed) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/llvmpipe-" + DefaultVersion.LLVMPIPE + ".tzst", rootDir);
            }
        }
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

    private void setupXEnvironment() {
        envVars.put("LC_ALL", lc_all);
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", imageFs.wineprefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");

        boolean enableBox64Trace = preferences.getBoolean("enable_box64_trace", false);

        if (enableBox64Trace) {
            envVars.put("BOX64_NOBANNER", "0");
            envVars.put("BOX64_SHOWSEGV", "1");
            envVars.put("BOX64_DLSYM_ERROR", "1");
            envVars.put("BOX64_TRACE_FILE", "/storage/emulated/0/Download/Winlator/trace-%pid.txt");
        }

        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());

        boolean usrGlibc = preferences.getBoolean("use_glibc", true);
        GuestProgramLauncherComponent guestProgramLauncherComponent = usrGlibc
                ? new GlibcProgramLauncherComponent(contentsManager, contentsManager.getProfileByEntryName(container.getWineVersion()))
                : new GuestProgramLauncherComponent();

        if (container != null) {
            if (container.getStartupSelection() == Container.STARTUP_SELECTION_AGGRESSIVE) winHandlerX11.killProcess("services.exe");

            boolean wow64Mode;
            if (shortcut != null)
                wow64Mode = shortcut.getExtra("WoW64Mode", "1").equals("1");
            else
                wow64Mode = container.isWoW64Mode();

            String guestExecutable;
            if (wow64Mode)
                guestExecutable = "wine explorer /desktop=shell,"+screenInfo+" "+getWineStartCommand(); // Using WoW64 Wine
            else
                guestExecutable = "wine64 explorer /desktop=shell,"+screenInfo+" "+getWineStartCommand(); // Using bi-arch Wine

            guestProgramLauncherComponent.setWoW64Mode(wow64Mode);
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) bindingPaths.add(drive[1]);
            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));
            guestProgramLauncherComponent.setBox86Preset(shortcut != null ? shortcut.getExtra("box86Preset", container.getBox86Preset()) : container.getBox86Preset());
            guestProgramLauncherComponent.setBox64Preset(shortcut != null ? shortcut.getExtra("box64Preset", container.getBox64Preset()) : container.getBox64Preset());
        }

        environment = new XEnvironment(this, imageFs);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals("alsa")) {
            envVars.put("ANDROID_ALSA_SERVER", imageFs.getRootDir().getPath() + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)));
        }
        else if (audioDriver.equals("pulseaudio")) {
            envVars.put("PULSE_SERVER", imageFs.getRootDir().getPath() + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(new PulseAudioComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)));
        }

        if (graphicsDriver.startsWith("virgl")) {
            if (!useOldVirGL) {
                environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
            }
            else {
                startVirGLTestServer(this);
            }
        }
        else if (graphicsDriver.startsWith("vortek")) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this.graphicsDriverConfig);
            String renderName = "vortekrenderer";
            if (options.renderVersion == 1)
                renderName = "vortekrenderer-d";
            else if (options.renderVersion == 2)
                renderName = "vortekrenderer-110";
            environment.addComponent(new VortekRendererComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options, renderName));
        }

        RCManager manager = new RCManager(this);
        manager.loadRCFiles();
        int rcfileId = shortcut == null ? container.getRCFileId() :
                Integer.parseInt(shortcut.getExtra("rcfileId", String.valueOf(container.getRCFileId())));
        RCFile rcfile = manager.getRcfile(rcfileId);
        File file = new File(container.getRootDir(), ".box64rc");
        String str = rcfile == null ? "" : rcfile.generateBox86_64rc();
        FileUtils.writeString(file, str);
        envVars.put("BOX64_RCFILE", file.getAbsolutePath());

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) generateWineprefix();
        environment.startEnvironmentComponents();

        winHandlerX11.start();
        envVars.clear();
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
    }

    private void generateWineprefix() {
        Intent intent = getIntent();

        final File rootDir = imageFs.getRootDir();
        final File installedWineDir = imageFs.getInstalledWineDir();
        wineInfo = intent.getParcelableExtra("wine_info");
        envVars.put("WINEARCH", wineInfo.isWin64() ? "win64" : "win32");
        imageFs.setWinePath(wineInfo.path);

        final File containerPatternDir = new File(installedWineDir, "/preinstall/container-pattern");
        if (containerPatternDir.isDirectory()) FileUtils.delete(containerPatternDir);
        containerPatternDir.mkdirs();

        File linkFile = new File(rootDir, ImageFs.HOME_PATH);
        linkFile.delete();
        FileUtils.symlink(".."+FileUtils.toRelativePath(rootDir.getPath(), containerPatternDir.getPath()), linkFile.getPath());

        GuestProgramLauncherComponent guestProgramLauncherComponent = environment.getComponent(GuestProgramLauncherComponent.class);
//        guestProgramLauncherComponent.setGuestExecutable(wineInfo.getExecutable(this, false)+" explorer /desktop=shell,"+Container.DEFAULT_SCREEN_SIZE+" winecfg");
        guestProgramLauncherComponent.setGuestExecutable("wine explorer /desktop=shell,"+Container.DEFAULT_SCREEN_SIZE+" winecfg");

        preloaderDialog = new PreloaderDialog(this);
        guestProgramLauncherComponent.setTerminationCallback((status) -> Executors.newSingleThreadExecutor().execute(() -> {
            if (status > 0) {
                AppUtils.showToast(this, R.string.unable_to_install_wine);
                FileUtils.delete(new File(installedWineDir, "/preinstall"));
                AppUtils.restartApplication(this);
                return;
            }

            preloaderDialog.showOnUiThread(R.string.finishing_installation);
            FileUtils.writeString(new File(rootDir, ImageFs.WINEPREFIX+"/.update-timestamp"), "disable\n");

            File userDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/users/xuser");
            File[] userFiles = userDir.listFiles();
            if (userFiles != null) {
                for (File userFile : userFiles) {
                    if (FileUtils.isSymlink(userFile)) {
                        String path = userFile.getPath();
                        userFile.delete();
                        (new File(path)).mkdirs();
                    }
                }
            }

            String suffix = wineInfo.fullVersion()+"-"+wineInfo.getArch();
            File containerPatternFile = new File(installedWineDir, "/preinstall/container-pattern-"+suffix+".tzst");
            TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(rootDir, ImageFs.WINEPREFIX), containerPatternFile, MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);

            if (!containerPatternFile.renameTo(new File(installedWineDir, containerPatternFile.getName())) ||
                    !(new File(wineInfo.path)).renameTo(new File(installedWineDir, wineInfo.identifier()))) {
                containerPatternFile.delete();
            }

            FileUtils.delete(new File(installedWineDir, "/preinstall"));

            preloaderDialog.closeOnUiThread();
            AppUtils.restartApplication(this, R.id.main_menu_settings);
        }));
    }

    private void changeFrameRatingVisibility(com.winlator.xserver.Window window, Property property) {
        if (frameRating == null) return;
        if (property != null) {
            if (frameRatingWindowId == -1 && window.attributes.isMapped() && property.nameAsString().equals("_MESA_DRV")) {
                frameRatingWindowId = window.id;
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    private void assignTaskAffinity(com.winlator.xserver.Window window) {
        if (taskAffinityMask == 0) return;
        int processId = window.getProcessId();
        String className = window.getClassName();
        int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;

        if (processId > 0) {
            winHandlerX11.setProcessAffinity(processId, processAffinity);
        }
        else if (!className.isEmpty()) {
            winHandlerX11.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    private String getWineStartCommand() {
        File tempDir = new File(container.getRootDir(), ".wine/drive_c/windows/temp");
        FileUtils.clear(tempDir);

        String args = "";
        if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.path.endsWith(".lnk")) {
                args += "\""+shortcut.path+"\""+execArgs;
            }
            else {
                String exeDir = FileUtils.getDirname(shortcut.path);
                String filename = FileUtils.getName(shortcut.path);
                int dotIndex, spaceIndex;
                if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                    execArgs = filename.substring(spaceIndex+1)+execArgs;
                    filename = filename.substring(0, spaceIndex);
                }
                args += "/dir "+exeDir.replace(" ", "\\ ")+" \""+filename+"\""+execArgs;
            }
        }
        else args += "\"wfm.exe\"";

        return "winhandler.exe "+args;
    }

    public static boolean handleInputEventX11(Binding binding, boolean isActionDown) {
        if (mInputHandlerRunning) {
            KeyEvent e;
            XKeyCodes.ButtonMapping buttonMapping = getMapping(binding.keycode.toString());
            if (isActionDown)
                e = new KeyEvent(KeyEvent.ACTION_DOWN, buttonMapping.keyCode);
            else
                e = new KeyEvent(KeyEvent.ACTION_UP, buttonMapping.keyCode);
            mInputHandler.sendKeyEvent(e);
            return true;
        }
        else
            return false;
    }

    public static boolean handleTouchEventX11(View view, MotionEvent event) {
        if (mInputHandlerRunning) {
            mInputHandler.handleTouchEvent(view, view, event);
            return true;
        }
        else
            return false;
    }

    public static boolean handleInputMouseEventX11(Binding binding, boolean isActionDown) {
        if (mInputHandlerRunning) {
            Pointer.Button pointerButton = binding.getPointerButton();
            if (pointerButton != null) {
                switch (pointerButton) {
                    case BUTTON_LEFT:
                        mInputHandler.sendMouseEvent(BUTTON_LEFT, isActionDown, true);
                        break;
                    case BUTTON_MIDDLE:
                        mInputHandler.sendMouseEvent(BUTTON_MIDDLE, isActionDown, true);
                        break;
                    case BUTTON_RIGHT:
                        mInputHandler.sendMouseEvent(BUTTON_RIGHT, isActionDown, true);
                        break;
                    case BUTTON_SCROLL_UP:
                        mInputHandler.sendMouseWheelEvent(-MouseWheelDistance);
                        break;
                    case BUTTON_SCROLL_DOWN:
                        mInputHandler.sendMouseWheelEvent(MouseWheelDistance);
                        break;
                    default:
                        break;
                }
            }
            return true;
        }
        else
            return false;
    }

}
